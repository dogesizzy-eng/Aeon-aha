package com.example.aha;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.example.aha.AhaCombatPackets.Beat;
import com.example.aha.AhaCombatPackets.LockPhase;
import com.example.aha.AhaGeckoRegistration.AhaAvatarEntity;
import com.example.aha.AhaGeckoRegistration.AhaFxEntity;

import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Server-authoritative half of the cutscene combat. Owns the per-player session (Wish Power, Punchlines, SP,
 * "Faces of Elation" turns), the lock on the player and the target mob, the tick timeline that registers damage,
 * Elation Debt, and the Misjudgment Dimension technique.
 *
 * <h2>Required data pack files (damage types)</h2>
 * <pre>
 * data/aha/damage_type/quantum_elation.json:
 *   { "exhaustion": 0.0, "message_id": "aha.quantum_elation", "scaling": "never" }
 * data/aha/damage_type/quantum_elation_pierce.json:        (same content, message_id "aha.quantum_elation_pierce")
 * data/aha/damage_type/quantum_elation_true.json:          (same content, message_id "aha.quantum_elation_true")
 * data/minecraft/tags/damage_type/bypasses_armor.json:
 *   { "values": [ "aha:quantum_elation_pierce", "aha:quantum_elation_true" ] }
 * data/minecraft/tags/damage_type/bypasses_effects | bypasses_resistance | bypasses_enchantments:
 *   { "values": [ "aha:quantum_elation_true" ] }                (Elation Skill's rose explosion = True DMG)
 * </pre>
 * "DEF Ignore" from Faces of Elation is modelled as switching to the armor-bypassing damage type.
 *
 * <h2>Safety rails (a frozen mob must never stay frozen)</h2>
 * Every mob we setNoAi(true) is tagged in its persistent NBT with its previous state. It is restored on cutscene
 * end, on abort, on player logout/death/dimension change, on server stop, and (for crashes) when the mob next
 * joins a level with the tag and no live action owns it.
 *
 * <h2>Simplifications to know about</h2>
 * <ul>
 *   <li>Session state is in memory and resets when combat goes idle (see COMBAT_IDLE_TICKS) or on logout.
 *       Move it to a capability if you want it to persist.</li>
 *   <li>"Zero-AV extra turn" is modelled as: the next action on that target has no transition delay and does
 *       not consume a Faces of Elation turn. There is no full action-value timeline in vanilla Minecraft.</li>
 *   <li>Crit Rate / Crit DMG are not vanilla stats; they are rolled inside {@link #strike}.</li>
 *   <li>Punchline generation beyond the technique's +20 preload is up to you: call {@link #addPunchlines}.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AhaGeckoRegistration.MODID)
public final class AhaCombatServer {

    private AhaCombatServer() {}

    // ---- tunables (placeholders; change freely) ----
    public static final int WISH_CAP = 10;
    public static final int PUNCH_CAP = 40;
    public static final int TECHNIQUE_COOLDOWN = 600;

    /** Aha Instant needs at least this many Punchlines to fire the Elation Skill; the skill consumes all of them. */
    public static final int ELATION_MIN_PUNCH = 10;

    private static final int SP_START = 3;
    private static final int SP_CAP = 5;
    private static final int ZONE_PRELOAD_PUNCHLINES = 20;
    private static final int ZONE_TICKS = 600;
    private static final double ZONE_RADIUS = 10.0D;
    private static final int INTRO_TICKS = 12;
    /** Even with "no transition", wait this long so the avatar is tracked by the client before its animation triggers. */
    private static final int MIN_PREROLL = 2;
    private static final int FACES_TURNS = 3;
    private static final double MAX_TARGET_DIST = 24.0D;
    private static final int COMBAT_IDLE_TICKS = 1200;
    private static final int EXTRA_TURN_WINDOW_TICKS = 200;

    // Elation Skill (main game: 60% Elation DMG to all enemies, then True DMG = 20% of the DMG dealt by this Aha Instant,
    // split equally among the enemies). The 60% is spread over 4 damage cards; the 20% is the rose explosion.
    private static final float ELATION_CARD_MULT = 0.30F;          // x4 cards = 1.2 in BASE_DAMAGE units (Basic is 1.0)
    private static final float ELATION_PUNCH_SCALE = 0.05F;        // +5% card damage per consumed Punchline
    private static final float ELATION_TRUE_RATIO = 0.20F;
    private static final float BLISS_RECOVER = 0.10F;              // Faces of Elation: recover 10% of consumed Punchlines
    private static final double ELATION_RADIUS = 10.0D;

    private static final float BASE_DAMAGE = 6.0F;
    private static final float BASE_CRIT_RATE = 0.05F;
    private static final float FACES_CRIT_RATE = 0.25F;
    private static final float BASE_CRIT_DMG = 0.5F;
    private static final float TEAM_CRIT_DMG_BONUS = 0.4F;     // active at PUNCH_CAP Punchlines

    private static final double DEBT_STEP = 0.08D;              // -8% max HP per application
    private static final double DEBT_MAX = 0.6D;                // never below 40% of original max HP
    private static final UUID DEBT_MODIFIER_ID = UUID.fromString("5d6f0c1e-7a41-4b0f-9a3e-0a11e1a7d3b7");

    private static final String TAG_FROZEN = "aha_frozen";
    private static final String TAG_PREV_NOAI = "aha_prev_noai";

    private static final ResourceKey<DamageType> QUANTUM =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(AhaGeckoRegistration.MODID, "quantum_elation"));
    private static final ResourceKey<DamageType> QUANTUM_TRUE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(AhaGeckoRegistration.MODID, "quantum_elation_true"));
    private static final ResourceKey<DamageType> QUANTUM_PIERCE =
            ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(AhaGeckoRegistration.MODID, "quantum_elation_pierce"));

    // =====================================================================================================
    // State (server thread only)
    // =====================================================================================================

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private record Zone(ResourceKey<Level> dim, Vec3 center, long expiresAt, @Nullable UUID fxId) {}

    private static final class Session {
        int wish;
        int punch;
        int sp = SP_START;
        int facesTurns;
        boolean extraTurn;
        int extraTurnTargetId = -1;
        long extraTurnUntil;
        @Nullable Zone zone;
        @Nullable Action action;
        long lastActive;
        long lastRequest;
        long techniqueReadyAt;
    }

    /** One running cutscene. Exists from LOCK to UNLOCK. */
    private static final class Action {
        final ServerLevel level;
        final AhaSkill skill;
        final int targetId;
        int allyId = -1;
        /** Starts at -preRoll; 0 = animation start. Same convention as the client clock. */
        int elapsed;
        boolean freeTurn;
        boolean spRefunded;
        int punchSpent;
        float elationTotal;
        boolean prevInvulnerable;
        Vec3 anchor = Vec3.ZERO;
        float yaw;
        float pitch;
        @Nullable AhaAvatarEntity avatar;
        List<LivingEntity> pool = new ArrayList<>();
        int[] bounceVictim;
        final Map<UUID, Boolean> frozen = new HashMap<>();   // mob -> its previous noAi flag

        Action(ServerLevel level, AhaSkill skill, int targetId) {
            this.level = level;
            this.skill = skill;
            this.targetId = targetId;
            this.bounceVictim = new int[skill.impactTicks().length];
            Arrays.fill(this.bounceVictim, -1);
        }
    }

    private static Session session(ServerPlayer p) {
        return SESSIONS.computeIfAbsent(p.getUUID(), id -> {
            Session s = new Session();
            s.lastActive = p.level().getGameTime();
            return s;
        });
    }

    // =====================================================================================================
    // Entry point from C2SUseSkill: validate everything, then lock and start the timeline
    // =====================================================================================================

    /** @return true if the cutscene actually started (the staff item uses this to decide whether to show a cooldown). */
    public static boolean handleUseSkill(ServerPlayer p, AhaSkill skill, int targetId, int allyId) {
        ServerLevel level = p.serverLevel();
        Session s = session(p);
        long now = level.getGameTime();

        if (s.action != null || now - s.lastRequest < 5) return false;   // one cutscene at a time, light spam guard
        s.lastRequest = now;

        // Target: only the skills that need one are checked. The Technique is aimless.
        Mob target = null;
        if (skill.needsTarget()) {
            Entity e = level.getEntity(targetId);
            if (!(e instanceof Mob m) || !m.isAlive()
                    || p.distanceToSqr(m) > MAX_TARGET_DIST * MAX_TARGET_DIST || !p.hasLineOfSight(m)) {
                hint(p, "No valid target in sight");
                return false;
            }
            target = m;
        }
        if (skill == AhaSkill.ULTIMATE && s.wish < skill.wishCost()) {
            hint(p, "Not enough Wish Power (" + s.wish + "/" + skill.wishCost() + ")");
            return false;
        }
        if (skill == AhaSkill.SKILL && s.sp < 1) {
            hint(p, "No Skill Points");
            return false;
        }
        if (skill == AhaSkill.ELATION_SKILL && s.punch < ELATION_MIN_PUNCH) {
            hint(p, "Not enough Punchlines (" + s.punch + "/" + ELATION_MIN_PUNCH + ")");
            return false;
        }
        if (skill == AhaSkill.TECHNIQUE && now < s.techniqueReadyAt) {
            hint(p, "Technique on cooldown (" + ((s.techniqueReadyAt - now + 19) / 20) + "s)");
            return false;
        }

        Action a = new Action(level, skill, target != null ? target.getId() : -1);
        LivingEntity ally = skill == AhaSkill.SKILL ? pickAlly(p, allyId) : p;
        a.allyId = ally.getId();

        // Extra turn earned by striking a mob inside the Misjudgment Dimension.
        a.freeTurn = target != null && s.extraTurn && now <= s.extraTurnUntil && s.extraTurnTargetId == target.getId();
        if (a.freeTurn) s.extraTurn = false;

        a.anchor = p.position();
        if (target != null) {
            Vec3 d = target.position().subtract(p.position());
            a.yaw = (float) (Mth.atan2(-d.x, d.z) * (180D / Math.PI));
        } else {
            a.yaw = p.getYRot();                                        // aimless: keep facing where the player already faces
        }
        a.pitch = 0F;
        p.setYRot(a.yaw);
        p.setXRot(a.pitch);
        p.setYHeadRot(a.yaw);

        int preRoll = a.freeTurn ? MIN_PREROLL : INTRO_TICKS;
        a.elapsed = -preRoll;

        // Spend resources up front; abort() refunds if the cutscene dies before it pays off.
        if (skill == AhaSkill.ULTIMATE) s.wish -= skill.wishCost();
        if (skill == AhaSkill.SKILL) s.sp -= 1;
        if (skill == AhaSkill.ELATION_SKILL) {                          // Aha Instant consumes ALL Punchline stacks
            a.punchSpent = s.punch;
            s.punch = 0;
        }
        if (skill == AhaSkill.TECHNIQUE) {
            s.techniqueReadyAt = now + TECHNIQUE_COOLDOWN;
            p.getCooldowns().addCooldown(AhaGeckoRegistration.STAFF.get(), TECHNIQUE_COOLDOWN);   // staff shows the same cooldown
        }

        // Lock: the player is held in place and invulnerable, the target loses its AI.
        a.prevInvulnerable = p.isInvulnerable();
        p.setInvulnerable(true);
        if (target != null) freeze(a, target);

        AhaAvatarEntity avatar = AhaGeckoRegistration.AVATAR.get().create(level);
        if (avatar != null) {
            avatar.setPos(a.anchor.x, a.anchor.y, a.anchor.z);
            avatar.setYRot(a.yaw);
            avatar.setOwner(p.getUUID());
            level.addFreshEntity(avatar);
            a.avatar = avatar;
        }

        s.action = a;
        s.lastActive = now;

        AhaCombatPackets.sendToPlayer(new AhaCombatPackets.S2CLockState(LockPhase.LOCK, skill, a.targetId, a.allyId,
                a.anchor.x, a.anchor.y, a.anchor.z, a.yaw, a.pitch, preRoll), p);
        syncResources(p, s);
        return true;
    }

    private static void hint(ServerPlayer p, String text) {
        p.displayClientMessage(Component.literal(text), true);
    }

    // =====================================================================================================
    // Per-tick driver
    // =====================================================================================================

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        for (Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Session> en = it.next();
            Session s = en.getValue();
            ServerPlayer p = server.getPlayerList().getPlayer(en.getKey());

            if (p == null) {
                if (s.action != null) cleanup(null, s.action);
                it.remove();
                continue;
            }

            long now = p.level().getGameTime();
            if (s.zone != null && now > s.zone.expiresAt()) s.zone = null;

            if (s.action != null) {
                tickAction(p, s);
            } else if (now - s.lastActive > COMBAT_IDLE_TICKS) {
                it.remove();                                           // combat is over: resources reset
            }
        }
    }

    private static void tickAction(ServerPlayer p, Session s) {
        Action a = s.action;
        if (a == null) return;

        if (!p.isAlive() || p.level() != a.level) {                    // died or changed dimension mid-cutscene
            abort(p, s, true);
            return;
        }
        Entity target = a.level.getEntity(a.targetId);

        // Authoritative lock: the client also zeroes input, but the server never trusts that.
        if (p.position().distanceToSqr(a.anchor) > 2.25D) {
            p.connection.teleport(a.anchor.x, a.anchor.y, a.anchor.z, a.yaw, a.pitch);
        }
        p.setDeltaMovement(0.0D, Math.min(0.0D, p.getDeltaMovement().y), 0.0D);

        // Knockback from scripted hits must not drag frozen mobs around the cutscene.
        for (UUID id : a.frozen.keySet()) {
            Entity m = a.level.getEntity(id);
            if (m != null) m.setDeltaMovement(Vec3.ZERO);
        }

        if (a.elapsed >= 0) runTimeline(p, s, a, target);

        a.elapsed++;
        if (a.elapsed >= a.skill.durationTicks()) finish(p, s, a);
    }

    /** The server-side mirror of the animation: fire on exactly the ticks listed in AhaSkill. */
    private static void runTimeline(ServerPlayer p, Session s, Action a, @Nullable Entity target) {
        int t = a.elapsed;
        AhaSkill skill = a.skill;

        if (t == 0) {
            if ((skill == AhaSkill.ULTIMATE || skill == AhaSkill.ELATION_SKILL) && target != null) {
                double radius = skill == AhaSkill.ELATION_SKILL ? ELATION_RADIUS : 8.0D;
                a.pool = enemiesAround(a.level, target.position(), radius, target, p);
                for (LivingEntity v : a.pool) {
                    if (v instanceof Mob m) freeze(a, m);
                }
            }
            // Same tick the clock starts: the animation starts on every tracking client.
            if (a.avatar != null) a.avatar.triggerAnim(AhaGeckoRegistration.MAIN_CONTROLLER, skill.trigger());
        }

        int[] impacts = skill.impactTicks();
        for (int i = 0; i < impacts.length; i++) {
            AhaFxEntity.Kind fx = leadKind(skill, i);
            if (fx != null && t + fx.lead == impacts[i]) spawnLeadFx(p, a, target, i);
            if (t == impacts[i]) impact(p, s, a, target, i);
        }
    }

    /** Which FX entity (if any) announces impact {@code i} of a skill. Its Kind.lead says how many ticks early it spawns. */
    @Nullable
    private static AhaFxEntity.Kind leadKind(AhaSkill skill, int i) {
        return switch (skill) {
            case BASIC_ATTACK -> AhaFxEntity.Kind.HEART_CREST;
            case SKILL -> i == 1 ? AhaFxEntity.Kind.AOE_BLAST : null;
            case ULTIMATE -> i == 0 ? AhaFxEntity.Kind.MAIN_STRIKE : AhaFxEntity.Kind.BOUNCE;
            case ELATION_SKILL -> i < 4 ? AhaFxEntity.Kind.BOUNCE : AhaFxEntity.Kind.ROSE;   // cards, then the rose
            case TECHNIQUE -> null;                                     // the dome is spawned by castTechnique on the impact tick
        };
    }

    private static void spawnLeadFx(ServerPlayer p, Action a, @Nullable Entity target, int i) {
        switch (a.skill) {
            case BASIC_ATTACK -> {
                if (target != null) AhaFxEntity.spawn(a.level, AhaFxEntity.Kind.HEART_CREST, target.position(), 0);
            }
            case SKILL -> {
                if (i == 1) {
                    Entity ally = a.level.getEntity(a.allyId);
                    AhaFxEntity.spawn(a.level, AhaFxEntity.Kind.AOE_BLAST, (ally != null ? ally : p).position(), 0);
                }
            }
            case ULTIMATE -> {
                if (i == 0) {
                    if (target != null) AhaFxEntity.spawn(a.level, AhaFxEntity.Kind.MAIN_STRIKE, target.position(), 0);
                } else {
                    LivingEntity v = randomAlive(a, target, p);
                    if (v != null) {
                        a.bounceVictim[i] = v.getId();                 // remembered so FX and damage agree
                        AhaFxEntity.spawn(a.level, AhaFxEntity.Kind.BOUNCE, v.position(), 0);
                    }
                }
            }
            case ELATION_SKILL -> {
                if (i < 4) {
                    int n = 0;
                    for (LivingEntity v : a.pool) {                     // one card FX on every enemy, capped for the client
                        if (v.isAlive() && n++ < 8) AhaFxEntity.spawn(a.level, AhaFxEntity.Kind.BOUNCE, v.position(), 0);
                    }
                } else {
                    AhaSounds.play(a.level, target != null ? target.position() : p.position(), AhaSounds.ELATION_BLOOM);
                    if (target != null) AhaFxEntity.spawn(a.level, AhaFxEntity.Kind.ROSE, roseCenter(a, target), 0);
                }
            }
            default -> { }
        }
    }

    /** The rose floats over the middle of the enemy group (falls back to the primary target). */
    private static Vec3 roseCenter(Action a, Entity target) {
        double x = 0, y = target.getY(), z = 0;
        int n = 0;
        for (LivingEntity v : a.pool) {
            if (!v.isAlive()) continue;
            x += v.getX();
            z += v.getZ();
            y = Math.min(y, v.getY());
            n++;
        }
        return n == 0 ? target.position() : new Vec3(x / n, y, z / n);
    }

    private static void impact(ServerPlayer p, Session s, Action a, @Nullable Entity target, int i) {
        switch (a.skill) {
            case BASIC_ATTACK -> {
                AhaSounds.play(a.level, target != null ? target.position() : p.position(), AhaSounds.BASIC_HIT);
                if (target instanceof LivingEntity le) strike(p, s, le, 1.0F);
                addWish(s, a.skill.wishGain());
            }
            case SKILL -> {
                if (i == 0) {
                    AhaSounds.play(a.level, p.position(), AhaSounds.SKILL_BUFF);
                    buffAlly(a);
                    addWish(s, a.skill.wishGain());
                    beat(p, Beat.ALLY_BUFF);
                } else {
                    AhaSounds.play(a.level, p.position(), AhaSounds.SKILL_BLAST);
                    blast(p, s, a);
                    s.sp = Math.min(SP_CAP, s.sp + 1);                 // refund: Skill is SP-neutral
                    a.spRefunded = true;
                    beat(p, Beat.AOE_BLAST);
                }
            }
            case ULTIMATE -> {
                if (i == 0) {
                    AhaSounds.play(a.level, target != null ? target.position() : p.position(), AhaSounds.ULT_STRIKE);
                    if (target instanceof LivingEntity le) strike(p, s, le, 3.0F);
                    beat(p, Beat.MAIN_STRIKE);
                } else {
                    LivingEntity v = bounceVictim(a, i, target, p);
                    if (v != null) { strike(p, s, v, 0.6F); AhaSounds.play(a.level, v.position(), AhaSounds.ULT_BOUNCE); }
                    beat(p, Beat.BOUNCE);
                }
            }
            case ELATION_SKILL -> elationImpact(p, s, a, target, i);
            case TECHNIQUE -> {
                castTechnique(p);                                       // the dimension opens exactly on the impact tick
                beat(p, Beat.ZONE_OPEN);
            }
        }
        syncResources(p, s);
    }

    /**
     * Elation Skill, in two stages that mirror the main game:
     * <ol>
     *   <li>impacts 0..3: four Punchline damage cards hit EVERY enemy (together 60% Elation DMG, scaled by the
     *       Punchlines this Aha Instant consumed). The damage is totalled in {@code a.elationTotal}.</li>
     *   <li>impact 4: the cards have been fused into a rose, which detonates. It deals True DMG equal to 20% of the
     *       total dealt by this Aha Instant, split equally among all enemies.</li>
     * </ol>
     */
    private static void elationImpact(ServerPlayer p, Session s, Action a, @Nullable Entity target, int i) {
        if (i < 4) {
            float scale = 1.0F + a.punchSpent * ELATION_PUNCH_SCALE;
            for (LivingEntity v : a.pool) {
                if (!v.isAlive()) continue;
                a.elationTotal += strike(p, s, v, ELATION_CARD_MULT * scale);
                AhaSounds.play(a.level, v.position(), AhaSounds.ELATION_CARD);
            }
            beat(p, Beat.CARD_HIT);
            return;
        }

        List<LivingEntity> alive = new ArrayList<>();
        for (LivingEntity v : a.pool) {
            if (v.isAlive()) alive.add(v);
        }
        if (!alive.isEmpty()) {
            float each = a.elationTotal * ELATION_TRUE_RATIO / alive.size();      // "equally distributed among all enemies"
            for (LivingEntity v : alive) trueStrike(p, v, each);
        }
        AhaSounds.play(a.level, target != null ? target.position() : p.position(), AhaSounds.ELATION_ROSE);
        addWish(s, a.skill.wishGain());                                           // Talent: +1 Wish Power after an Elation Skill
        beat(p, Beat.ROSE_BURST);
    }

    private static void beat(ServerPlayer p, Beat beat) {
        AhaCombatPackets.sendToPlayer(new AhaCombatPackets.S2CBeat(beat), p);
    }

    // =====================================================================================================
    // Skill effects
    // =====================================================================================================

    private static void buffAlly(Action a) {
        Entity e = a.level.getEntity(a.allyId);
        if (e instanceof LivingEntity ally) {
            ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 1200, 1));
            ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 1200, 0));
            ally.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 0));
        }
    }

    private static void blast(ServerPlayer p, Session s, Action a) {
        Entity ally = a.level.getEntity(a.allyId);
        Vec3 center = (ally != null ? ally : p).position();
        for (LivingEntity v : enemiesAround(a.level, center, 6.0D, a.level.getEntity(a.targetId), p)) {
            if (v instanceof Mob m) freeze(a, m);
            strike(p, s, v, 0.8F);
        }
    }

    /**
     * Registers one scripted hit. Crits are rolled here (Crit Rate / Crit DMG are not vanilla attributes). While
     * the Faces of Elation state is up the hit uses the armor-bypassing damage type ("DEF Ignore").
     */
    private static float strike(ServerPlayer p, Session s, LivingEntity victim, float multiplier) {
        if (!victim.isAlive()) return 0.0F;

        boolean faces = s.facesTurns > 0;
        float critRate = BASE_CRIT_RATE + (faces ? FACES_CRIT_RATE : 0F);
        float critDmg = BASE_CRIT_DMG + (s.punch >= PUNCH_CAP ? TEAM_CRIT_DMG_BONUS : 0F);

        float dmg = BASE_DAMAGE * multiplier + (float) p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        if (p.getRandom().nextFloat() < critRate) dmg *= 1.0F + critDmg;

        victim.invulnerableTime = 0;                                    // rapid Bounce hits would otherwise hit i-frames
        victim.hurt(quantumSource(p, faces), dmg);
        victim.setDeltaMovement(Vec3.ZERO);
        victim.hurtMarked = true;

        if (victim.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.REVERSE_PORTAL, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5D,
                    victim.getZ(), 16, 0.4D, 0.5D, 0.4D, 0.05D);
        }
        return dmg;
    }

    /** True DMG: ignores armor, enchantments, Resistance and absorbed effects (see the bypasses_* tags for quantum_elation_true). */
    private static void trueStrike(ServerPlayer p, LivingEntity victim, float amount) {
        if (!victim.isAlive()) return;
        victim.invulnerableTime = 0;
        victim.hurt(trueSource(p), Math.max(1.0F, amount));
        victim.setDeltaMovement(Vec3.ZERO);
        victim.hurtMarked = true;

        if (victim.level() instanceof ServerLevel sl) {
            double y = victim.getY() + victim.getBbHeight() * 0.5D;
            sl.sendParticles(ParticleTypes.EXPLOSION, victim.getX(), y, victim.getZ(), 2, 0.4D, 0.4D, 0.4D, 0.0D);
            sl.sendParticles(ParticleTypes.FLAME, victim.getX(), y, victim.getZ(), 28, 0.5D, 0.6D, 0.5D, 0.12D);
        }
    }

    private static DamageSource trueSource(ServerPlayer p) {
        Registry<DamageType> reg = p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(reg.getHolderOrThrow(QUANTUM_TRUE), p, p);
    }

    private static DamageSource quantumSource(ServerPlayer p, boolean pierce) {
        Registry<DamageType> reg = p.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE);
        return new DamageSource(reg.getHolderOrThrow(pierce ? QUANTUM_PIERCE : QUANTUM), p, p);
    }

    private static void addWish(Session s, int amount) {
        s.wish = Math.min(WISH_CAP, s.wish + amount);
    }

    /** Public hook for your own Punchline generation rules. */
    public static void addPunchlines(ServerPlayer p, int amount) {
        Session s = session(p);
        s.punch = Math.min(PUNCH_CAP, s.punch + amount);
        syncResources(p, s);
    }

    // =====================================================================================================
    // Finish / abort / cleanup
    // =====================================================================================================

    private static void finish(ServerPlayer p, Session s, Action a) {
        Entity target = a.level.getEntity(a.targetId);
        if (target instanceof Mob mob && mob.isAlive()) applyElationDebt(mob);   // at the END of the cutscene
        if (a.skill == AhaSkill.ELATION_SKILL) {                        // the whole Aha Instant marks every enemy it hit
            for (LivingEntity v : a.pool) {
                if (v != target && v instanceof Mob m && m.isAlive()) applyElationDebt(m);
            }
            if (s.facesTurns > 0 && a.punchSpent > 0) {                 // Bliss: recover 10% of the consumed Punchlines
                s.punch = Math.min(PUNCH_CAP, s.punch + Math.round(a.punchSpent * BLISS_RECOVER));
            }
        }

        if (a.skill == AhaSkill.ULTIMATE) {
            s.facesTurns = FACES_TURNS;                                 // 3-turn "Faces of Elation": Crit Rate up, DEF ignore
        } else if (a.skill.usesTurn() && !a.freeTurn && s.facesTurns > 0) {
            s.facesTurns--;                                             // Basic / Skill consume a turn; extra turns, ults, Elation Skill and Technique do not
        }

        cleanup(p, a);
        s.action = null;
        s.lastActive = a.level.getGameTime();

        AhaCombatPackets.sendToPlayer(AhaCombatPackets.S2CLockState.unlock(false), p);
        syncResources(p, s);
    }

    private static void abort(@Nullable ServerPlayer p, Session s, boolean notify) {
        Action a = s.action;
        if (a == null) return;

        // Refund anything that has not paid off yet.
        if (a.skill == AhaSkill.ULTIMATE && a.elapsed < a.skill.impactTicks()[0]) {
            s.wish = Math.min(WISH_CAP, s.wish + a.skill.wishCost());
        }
        if (a.skill == AhaSkill.SKILL && !a.spRefunded) s.sp = Math.min(SP_CAP, s.sp + 1);
        if (a.skill == AhaSkill.ELATION_SKILL && a.elapsed < a.skill.impactTicks()[0]) {
            s.punch = Math.min(PUNCH_CAP, s.punch + a.punchSpent);      // cards never flew: give the Punchlines back
        }
        if (a.skill == AhaSkill.TECHNIQUE && a.elapsed < a.skill.impactTicks()[0]) {
            s.techniqueReadyAt = 0L;                                    // the dimension never opened: no cooldown
            if (p != null) p.getCooldowns().removeCooldown(AhaGeckoRegistration.STAFF.get());
        }

        cleanup(p, a);
        s.action = null;

        if (p != null && notify) {
            AhaCombatPackets.sendToPlayer(AhaCombatPackets.S2CLockState.unlock(true), p);
            syncResources(p, s);
        }
    }

    private static void cleanup(@Nullable ServerPlayer p, Action a) {
        unfreezeAll(a);
        if (a.avatar != null) a.avatar.discard();
        if (p != null) p.setInvulnerable(a.prevInvulnerable);
    }

    private static void syncResources(ServerPlayer p, Session s) {
        AhaCombatPackets.sendToPlayer(new AhaCombatPackets.S2CResources(s.wish, s.punch, s.sp, s.facesTurns, s.extraTurn), p);
    }

    // =====================================================================================================
    // Elation Debt: permanent, stacking Max HP reduction via an AttributeModifier
    // =====================================================================================================

    private static void applyElationDebt(Mob mob) {
        AttributeInstance maxHp = mob.getAttribute(Attributes.MAX_HEALTH);
        if (maxHp == null) return;

        double step = mob.getType().is(Tags.EntityTypes.BOSSES) ? DEBT_STEP * 0.25D : DEBT_STEP;
        AttributeModifier old = maxHp.getModifier(DEBT_MODIFIER_ID);
        double total = Math.min(DEBT_MAX, (old == null ? 0.0D : -old.getAmount()) + step);

        // One modifier with a fixed UUID: replaced (not stacked) each time, so the total is bounded by DEBT_MAX.
        // addPermanentModifier is saved with the mob's NBT, so the debt survives restarts and chunk unloads.
        maxHp.removeModifier(DEBT_MODIFIER_ID);
        maxHp.addPermanentModifier(new AttributeModifier(DEBT_MODIFIER_ID, "aha_elation_debt", -total,
                AttributeModifier.Operation.MULTIPLY_TOTAL));
        if (mob.getHealth() > mob.getMaxHealth()) mob.setHealth(mob.getMaxHealth());

        // Visible marker: amplifier = number of stacks - 1. Removing the effect (milk) does NOT undo the debt.
        int stacks = Math.max(1, (int) Math.round(total / DEBT_STEP));
        mob.addEffect(new MobEffectInstance(AhaGeckoRegistration.ELATION_DEBT.get(), MobEffectInstance.INFINITE_DURATION,
                stacks - 1, false, true, true));
    }

    // =====================================================================================================
    // Technique: Misjudgment Dimension (right-click the staff) and the zone strike
    // =====================================================================================================

    public static void castTechnique(ServerPlayer p) {
        Session s = session(p);
        ServerLevel level = p.serverLevel();

        if (s.zone != null && s.zone.fxId() != null) {                  // recast replaces the old dome
            Entity old = level.getEntity(s.zone.fxId());
            if (old != null) old.discard();
        }
        AhaFxEntity fx = AhaFxEntity.spawn(level, AhaFxEntity.Kind.ZONE, p.position(), ZONE_TICKS);
        s.zone = new Zone(level.dimension(), p.position(), level.getGameTime() + ZONE_TICKS, fx == null ? null : fx.getUUID());
        s.lastActive = level.getGameTime();
        AhaSounds.play(level, p.position(), AhaSounds.TECHNIQUE);
        hint(p, "Misjudgment Dimension");
    }

    private static boolean insideZone(Session s, Entity e) {
        Zone z = s.zone;
        return z != null && e.level().dimension() == z.dim()
                && e.position().distanceToSqr(z.center()) <= ZONE_RADIUS * ZONE_RADIUS;
    }

    /**
     * Striking a mob inside the zone: no cutscene transition, +20 Punchlines (cap 40, team Crit DMG bonus at cap) and an
     * immediate extra turn. The next action on that mob skips its pre-roll and does not consume a Faces turn.
     */
    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !(event.getTarget() instanceof Mob mob)) return;
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.zone == null || s.action != null || !insideZone(s, mob)) return;
        if (!(p.getMainHandItem().is(AhaGeckoRegistration.STAFF.get()) || p.getOffhandItem().is(AhaGeckoRegistration.STAFF.get()))) return;

        event.setCanceled(true);                                        // replace the vanilla hit with the technique strike
        AhaFxEntity.spawn(p.serverLevel(), AhaFxEntity.Kind.HEART_CREST, mob.position(), 0);   // heart crest from the "Attack" clip

        long now = p.level().getGameTime();
        s.punch = Math.min(PUNCH_CAP, s.punch + ZONE_PRELOAD_PUNCHLINES);
        s.extraTurn = true;
        s.extraTurnTargetId = mob.getId();
        s.extraTurnUntil = now + EXTRA_TURN_WINDOW_TICKS;
        s.lastActive = now;

        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, EXTRA_TURN_WINDOW_TICKS, 0, false, false));
        hint(p, "Extra turn!");
        syncResources(p, s);
    }

    // =====================================================================================================
    // Freezing (setNoAi) with crash-safe restoration
    // =====================================================================================================

    private static void freeze(Action a, Mob mob) {
        if (a.frozen.containsKey(mob.getUUID())) return;
        boolean prev = mob.isNoAi();
        a.frozen.put(mob.getUUID(), prev);

        CompoundTag data = mob.getPersistentData();
        data.putBoolean(TAG_FROZEN, true);
        data.putBoolean(TAG_PREV_NOAI, prev);

        mob.setNoAi(true);
        mob.setDeltaMovement(Vec3.ZERO);
    }

    private static void unfreezeAll(Action a) {
        for (Map.Entry<UUID, Boolean> en : a.frozen.entrySet()) {
            Entity e = a.level.getEntity(en.getKey());
            if (e instanceof Mob m) restore(m, en.getValue());
        }
        a.frozen.clear();
    }

    private static void restore(Mob mob, boolean prevNoAi) {
        mob.setNoAi(prevNoAi);
        CompoundTag data = mob.getPersistentData();
        data.remove(TAG_FROZEN);
        data.remove(TAG_PREV_NOAI);
    }

    /** Crash recovery: a mob saved while frozen (server crashed mid-cutscene) is released when it loads back in. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        CompoundTag data = mob.getPersistentData();
        if (!data.getBoolean(TAG_FROZEN)) return;
        for (Session s : SESSIONS.values()) {
            if (s.action != null && s.action.frozen.containsKey(mob.getUUID())) return;   // still legitimately frozen
        }
        restore(mob, data.getBoolean(TAG_PREV_NOAI));
    }

    // =====================================================================================================
    // Lifecycle: never leave a lock behind
    // =====================================================================================================

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Session s = SESSIONS.remove(p.getUUID());
        if (s != null && s.action != null) abort(p, s, false);       // unfreezes mobs, discards the avatar
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Session s = SESSIONS.get(p.getUUID());
        if (s != null && s.action != null) abort(p, s, true);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Map.Entry<UUID, Session> en : SESSIONS.entrySet()) {
            Session s = en.getValue();
            if (s.action != null) {
                ServerPlayer p = event.getServer().getPlayerList().getPlayer(en.getKey());
                abort(p, s, false);
            }
        }
        SESSIONS.clear();
    }

    // =====================================================================================================
    // Target / ally helpers
    // =====================================================================================================

    /** Ally for the Skill: the one the client named, else the nearest owned tame / player-made golem, else the caster. */
    private static LivingEntity pickAlly(ServerPlayer p, int requestedId) {
        ServerLevel level = p.serverLevel();
        if (requestedId >= 0) {
            Entity e = level.getEntity(requestedId);
            if (isEligibleAlly(p, e)) return (LivingEntity) e;
        }
        return level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(16.0D), e -> isEligibleAlly(p, e))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(p)))
                .map(e -> (LivingEntity) e)
                .orElse(p);
    }

    private static boolean isEligibleAlly(ServerPlayer p, @Nullable Entity e) {
        if (!(e instanceof LivingEntity le) || !le.isAlive() || le == p) return false;
        if (le instanceof TamableAnimal tame) return tame.isOwnedBy(p);
        return le instanceof IronGolem golem && golem.isPlayerCreated();
    }

    private static List<LivingEntity> enemiesAround(ServerLevel level, Vec3 center, double radius,
                                                    @Nullable Entity primary, ServerPlayer owner) {
        AABB box = new AABB(center, center).inflate(radius);
        List<LivingEntity> list = new ArrayList<>(level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != owner && e.isAlive() && e instanceof Enemy));
        if (primary instanceof LivingEntity le && le.isAlive() && !list.contains(le)) list.add(le);
        if (list.size() > 12) return new ArrayList<>(list.subList(0, 12));    // cap the cutscene's workload
        return list;
    }

    @Nullable
    private static LivingEntity randomAlive(Action a, @Nullable Entity fallback, ServerPlayer p) {
        List<LivingEntity> alive = new ArrayList<>();
        for (LivingEntity v : a.pool) {
            if (v.isAlive()) alive.add(v);
        }
        if (!alive.isEmpty()) return alive.get(p.getRandom().nextInt(alive.size()));
        return fallback instanceof LivingEntity le && le.isAlive() ? le : null;
    }

    @Nullable
    private static LivingEntity bounceVictim(Action a, int i, @Nullable Entity target, ServerPlayer p) {
        Entity e = a.bounceVictim[i] >= 0 ? a.level.getEntity(a.bounceVictim[i]) : null;
        if (e instanceof LivingEntity le && le.isAlive()) return le;
        return randomAlive(a, target, p);
    }
}
