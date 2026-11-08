package com.example.aha;

import java.util.EnumMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * CLIENT-ONLY cinematic camera for Aha's cutscenes. Never reference this class from common code; the packets
 * reach it through DistExecutor.
 *
 * <h2>How the camera is actually moved</h2>
 * ViewportEvent.ComputeCameraAngles can change yaw/pitch/roll but NOT the camera position (Camera#setPosition is
 * protected and the event fires after Camera#setup has already run). So position is handled the mixin-free way:
 * <ol>
 *   <li>On LOCK we create a client-only {@link Marker} (never added to the level) and make it the camera entity
 *       via Minecraft#setCameraEntity, forcing first-person camera type so vanilla does not push the camera back.</li>
 *   <li>Every frame, in RenderTickEvent (START), which fires before GameRenderer#renderLevel, we compute the
 *       cinematic pose and write it to the marker, with the "old" position equal to the new one so vanilla's
 *       partial-tick interpolation cannot add lag.</li>
 *   <li>ComputeCameraAngles then only adds roll (shake + dutch tilt) and ComputeFov applies the zoom.</li>
 * </ol>
 * Known quirk: vanilla's Camera eases its internal eye height toward the camera entity's eye height (0 for a
 * Marker) by 50% per tick. We cancel that analytically ({@link #eyeLag}); the residual error is a few
 * centimetres in the first ticks and is hidden by the intro blend, letterbox and flash. If you accept one tiny
 * mixin (an accessor for Camera#eyeHeight/eyeHeightOld) you can delete eyeLag() entirely.
 *
 * <h2>Shot model</h2>
 * Each skill is a list of {@link Shot}s. A shot orbits a focus point (player / target / ally / midpoint) with
 * radius, height and azimuth (degrees, measured relative to the player-to-target axis) all smoothstep-lerped
 * across the shot. Between shots the poses are cross-blended for {@value #BLEND_TICKS} ticks; the first shot is
 * blended in from the player's real eye pose during the pre-roll and the last shot is blended back out during
 * the final {@value #OUTRO_TICKS} ticks.
 *
 * <h2>Hooking up keys (not included, 3 lines)</h2>
 * Register KeyMappings on the MOD bus (RegisterKeyMappingsEvent), then in a ClientTickEvent:
 * <pre>
 *   while (BASIC_KEY.consumeClick())    AhaCinematicCameraEngine.requestSkill(AhaSkill.BASIC_ATTACK);
 *   while (SKILL_KEY.consumeClick())    AhaCinematicCameraEngine.requestSkill(AhaSkill.SKILL);
 *   while (ULTIMATE_KEY.consumeClick()) AhaCinematicCameraEngine.requestSkill(AhaSkill.ULTIMATE);
 * </pre>
 */
@Mod.EventBusSubscriber(modid = AhaGeckoRegistration.MODID, value = Dist.CLIENT)
public final class AhaCinematicCameraEngine {

    private AhaCinematicCameraEngine() {}

    // ---- tuning ----
    private static final int BLEND_TICKS = 4;
    private static final int OUTRO_TICKS = 8;
    private static final double PLAYER_FOCUS_Y = 1.4D;
    private static final double TARGET_RANGE = 24.0D;

    // ---- cutscene state (client thread only) ----
    private static boolean active;
    private static AhaSkill skill;
    private static int targetId;
    private static int allyId;
    private static Vec3 anchor = Vec3.ZERO;
    private static float lockYaw;
    private static float lockPitch;
    private static int preRoll;
    /** Cutscene clock in ticks. Starts at -preRoll, 0 = animation start (same convention as the server). */
    private static int tick;
    private static Vec3 lastTarget = Vec3.ZERO;
    private static Vec3 lastAlly = Vec3.ZERO;

    // ---- camera swap state ----
    @Nullable private static Marker camAnchor;
    @Nullable private static Entity prevCameraEntity;
    @Nullable private static CameraType prevCameraType;
    private static int ticksSinceSwap;
    private static float eyeHeightAtSwap;

    // ---- per-frame outputs ----
    private static float currentFov = -1F;
    private static float shakeRoll;
    private static float trauma;
    private static float flash;

    // ---- HUD snapshot (S2CResources) ----
    private static int hudWish;
    private static int hudPunch;
    private static int hudSp;
    private static int hudFaces;
    private static boolean hudExtra;

    private static final ResourceLocation[] MASKS = {
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/aha_mask_0.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/aha_mask_1.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/aha_mask_2.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/aha_mask_3.png")
    };

    public static boolean isActive() { return active; }

    // =====================================================================================================
    // Shots
    // =====================================================================================================

    private enum Focus { PLAYER, TARGET, ALLY, MIDPOINT }

    /**
     * @param from,to  tick range of the shot (cutscene clock)
     * @param center   what the orbit circles around
     * @param r0,r1    orbit radius at start/end
     * @param h0,h1    camera height above the center at start/end
     * @param az0,az1  azimuth in degrees relative to the player->target axis: 0 = camera beyond the center toward
     *                 the target (faces the player), 90 = side-on, 180 = over the player's shoulder
     * @param look     what the camera looks at
     * @param fov0,fov1 field of view in degrees at start/end (the ultimate's zoom lives here)
     */
    private record Shot(int from, int to, Focus center, float r0, float r1, float h0, float h1,
                        float az0, float az1, Focus look, float fov0, float fov1) {}

    private static final Map<AhaSkill, Shot[]> SHOTS = new EnumMap<>(AhaSkill.class);

    /** Client-only white flashes on fixed cutscene ticks, read off the clip: {tick, intensity x100}. */
    private static final Map<AhaSkill, int[][]> FLASH_CUES = new EnumMap<>(AhaSkill.class);

    /** Ultimate crown-stage window (ticks) during which the shifting Aha masks are drawn: 3.75 s - 10.5 s. */
    private static final int MASK_FROM = 75;
    private static final int MASK_TO = 210;

    static {
        // Shots follow the camera cuts of the HSR showcase clip (20 ticks = 1 s of clip time).

        // Basic (60 ticks, clip 1:03-1:06): side-on wide of Aha and the enemy while the wand is raised and the heart
        // crest forms (0-1.75 s), then a punch-in on the burst and a slow pull-back for the confetti.
        SHOTS.put(AhaSkill.BASIC_ATTACK, new Shot[] {
                new Shot(0, 35, Focus.MIDPOINT, 9.0F, 7.5F, 1.6F, 1.4F, 95F, 85F, Focus.MIDPOINT, 55F, 48F),
                new Shot(35, 60, Focus.TARGET, 5.0F, 7.0F, 1.4F, 2.0F, 120F, 150F, Focus.TARGET, 45F, 58F)
        });
        FLASH_CUES.put(AhaSkill.BASIC_ATTACK, new int[][] {{35, 60}});

        // Skill (360 ticks, clip 1:06-1:24):
        //   0-2.8 s   wide on Aha casting beside her ally          2.8-3.8 s  extreme close-up of the glowing eye
        //   3.8-6.0 s star emblem push-in on the ally              6.0-7.5 s  white flash -> ally buff badge + confetti
        //   7.5-8.8 s crown flash on the ally                      8.8-10.8 s low angle up at the giant grin ball
        //   10.8-12.9 s enemy-level view of the ball landing       12.9-14.5 s wide on the neon-heart burst
        //   14.5-16 s Aha's shocked close-up                       16-18 s     crane out for the confetti finish
        SHOTS.put(AhaSkill.SKILL, new Shot[] {
                new Shot(0, 56, Focus.PLAYER, 6.5F, 5.5F, 1.6F, 1.6F, 70F, 55F, Focus.PLAYER, 62F, 55F),
                new Shot(56, 76, Focus.PLAYER, 0.9F, 0.7F, 0.25F, 0.25F, 25F, 20F, Focus.PLAYER, 38F, 30F),
                new Shot(76, 120, Focus.ALLY, 3.0F, 2.0F, 0.6F, 0.6F, 0F, -10F, Focus.ALLY, 70F, 58F),
                new Shot(120, 150, Focus.ALLY, 4.0F, 3.5F, 1.0F, 1.6F, 15F, 10F, Focus.ALLY, 60F, 52F),
                new Shot(150, 176, Focus.ALLY, 3.5F, 2.6F, 1.8F, 2.2F, 10F, 0F, Focus.ALLY, 50F, 42F),
                new Shot(176, 216, Focus.ALLY, 7.0F, 6.0F, 0.3F, 0.8F, 200F, 215F, Focus.ALLY, 75F, 68F),
                new Shot(216, 258, Focus.TARGET, 9.0F, 6.0F, 1.0F, 1.4F, 190F, 230F, Focus.TARGET, 70F, 58F),
                new Shot(258, 290, Focus.MIDPOINT, 12.0F, 14.0F, 4.0F, 5.0F, 200F, 250F, Focus.MIDPOINT, 70F, 78F),
                new Shot(290, 320, Focus.PLAYER, 2.5F, 2.2F, 0.3F, 0.3F, 15F, 15F, Focus.PLAYER, 42F, 42F),
                new Shot(320, 360, Focus.MIDPOINT, 12.0F, 16.0F, 3.0F, 8.0F, 250F, 320F, Focus.MIDPOINT, 70F, 70F)
        });
        FLASH_CUES.put(AhaSkill.SKILL, new int[][] {{120, 100}, {150, 60}, {210, 40}, {255, 40}, {330, 100}});

        // Ultimate (380 ticks, clip 1:24-1:43):
        //   0-1.25 s  silhouette intro                  1.25-2.5 s wand close-up        2.5-3.75 s curtain reveal (wide)
        //   3.75-10.5 s crown-stage hold (slow push-in, Aha masks overlay)                10.5-11.6 s crescent + star burst
        //   11.6-14 s giant card                        14-15 s    card strike on the target
        //   15-16.75 s light-ray bursts (bounces)       16.75-18 s red checker field   18-19 s hearts finale
        SHOTS.put(AhaSkill.ULTIMATE, new Shot[] {
                new Shot(0, 25, Focus.PLAYER, 3.0F, 2.5F, 1.0F, 1.2F, 160F, 200F, Focus.PLAYER, 55F, 55F),
                new Shot(25, 50, Focus.PLAYER, 2.2F, 2.8F, 0.6F, 0.9F, 20F, 35F, Focus.PLAYER, 45F, 50F),
                new Shot(50, 75, Focus.PLAYER, 9.0F, 9.0F, 2.2F, 2.2F, 0F, 10F, Focus.PLAYER, 60F, 55F),
                new Shot(75, 210, Focus.PLAYER, 5.5F, 3.5F, 0.2F, 0.6F, -10F, 10F, Focus.PLAYER, 60F, 44F),
                new Shot(210, 232, Focus.PLAYER, 3.5F, 2.0F, 0.8F, 1.2F, 10F, 40F, Focus.PLAYER, 44F, 36F),
                new Shot(232, 280, Focus.PLAYER, 2.0F, 3.0F, 1.0F, 0.8F, 40F, 20F, Focus.PLAYER, 36F, 50F),
                new Shot(280, 300, Focus.TARGET, 6.0F, 3.0F, 1.6F, 1.0F, 200F, 215F, Focus.TARGET, 70F, 48F),
                new Shot(300, 335, Focus.MIDPOINT, 7.0F, 9.0F, 1.5F, 2.5F, 190F, 130F, Focus.TARGET, 80F, 70F),
                new Shot(335, 360, Focus.MIDPOINT, 10.0F, 12.0F, 2.0F, 2.6F, 250F, 280F, Focus.MIDPOINT, 66F, 72F),
                new Shot(360, 380, Focus.PLAYER, 3.0F, 4.0F, 1.2F, 1.8F, 30F, 20F, Focus.PLAYER, 50F, 60F)
        });
        FLASH_CUES.put(AhaSkill.ULTIMATE, new int[][] {{0, 70}, {232, 30}, {290, 100}, {378, 100}});

        // Elation Skill (380 ticks, "Elation Skill" clip, 0.75 s per contact-sheet frame):
        //   0-0.75 s   white-gold flash cut                   0.75-2.25 s Aha's crowned intro, a card in her hand
        //   2.25-3.75 s extreme close-up of the card          3.75-7.5 s  the Punchline cards gather (wide orbit)
        //   7.5-9.0 s   damage cards fly into every enemy     9.0-10.0 s  Aha cups the fused card (rose forms)
        //   10.0-12.0 s the rose blooms over the enemies      12.0-13.1 s detonation (True DMG)
        //   13.1-14.25 s Aha rises against the moon           14.25-15 s  laughing close-up, confetti
        //   15-19 s     red checker-board finale (wide, slow crane)
        SHOTS.put(AhaSkill.ELATION_SKILL, new Shot[] {
                new Shot(0, 15, Focus.MIDPOINT, 12.0F, 11.0F, 3.0F, 2.5F, 100F, 90F, Focus.MIDPOINT, 70F, 66F),
                new Shot(15, 45, Focus.PLAYER, 3.0F, 2.2F, 0.8F, 1.2F, 25F, 15F, Focus.PLAYER, 50F, 42F),
                new Shot(45, 75, Focus.PLAYER, 1.3F, 0.9F, 0.4F, 0.4F, 30F, 20F, Focus.PLAYER, 38F, 30F),
                new Shot(75, 150, Focus.PLAYER, 7.0F, 9.0F, 1.5F, 3.0F, 90F, 200F, Focus.PLAYER, 75F, 65F),
                new Shot(150, 180, Focus.TARGET, 7.0F, 5.0F, 1.5F, 1.8F, 200F, 170F, Focus.TARGET, 62F, 52F),
                new Shot(180, 200, Focus.PLAYER, 2.2F, 1.6F, 1.0F, 1.2F, 20F, 10F, Focus.PLAYER, 42F, 36F),
                new Shot(200, 240, Focus.MIDPOINT, 9.0F, 11.0F, 2.0F, 3.0F, 150F, 120F, Focus.TARGET, 62F, 70F),
                new Shot(240, 262, Focus.TARGET, 8.0F, 12.0F, 2.0F, 5.0F, 120F, 90F, Focus.TARGET, 78F, 85F),
                new Shot(262, 285, Focus.PLAYER, 4.0F, 5.0F, 0.3F, 0.6F, 200F, 200F, Focus.PLAYER, 50F, 60F),
                new Shot(285, 300, Focus.PLAYER, 2.4F, 2.0F, 1.2F, 1.2F, 10F, 0F, Focus.PLAYER, 42F, 40F),
                new Shot(300, 380, Focus.MIDPOINT, 11.0F, 14.0F, 3.0F, 5.0F, 90F, 60F, Focus.MIDPOINT, 65F, 60F)
        });
        FLASH_CUES.put(AhaSkill.ELATION_SKILL, new int[][] {{0, 60}, {150, 40}, {240, 100}, {262, 40}, {285, 60}});

        // Technique Intro (80 ticks): hero shot while the wand twirls, low angle as it is raised, then a wide pull-out as the
        // Misjudgment Dimension opens on tick 40.
        SHOTS.put(AhaSkill.TECHNIQUE, new Shot[] {
                new Shot(0, 22, Focus.PLAYER, 3.5F, 2.6F, 0.8F, 1.2F, 20F, 35F, Focus.PLAYER, 55F, 45F),
                new Shot(22, 40, Focus.PLAYER, 3.0F, 5.0F, 1.2F, 0.2F, 35F, 20F, Focus.PLAYER, 45F, 60F),
                new Shot(40, 80, Focus.PLAYER, 9.0F, 12.0F, 2.0F, 5.0F, 150F, 210F, Focus.PLAYER, 70F, 70F)
        });
        FLASH_CUES.put(AhaSkill.TECHNIQUE, new int[][] {{0, 50}, {40, 100}});
    }

    private record Pose(Vec3 pos, float yaw, float pitch, float fov) {
        static Pose lerp(Pose a, Pose b, float f) {
            return new Pose(a.pos.add(b.pos.subtract(a.pos).scale(f)),
                    Mth.rotLerp(f, a.yaw, b.yaw), Mth.lerp(f, a.pitch, b.pitch), Mth.lerp(f, a.fov, b.fov));
        }
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0F, 1F);
        return x * x * (3F - 2F * x);
    }

    // =====================================================================================================
    // Entry points called by packets / key handlers
    // =====================================================================================================

    /** Called by a key handler. Aims with a 24-block ray (vanilla's pick range is only ~3). */
    public static void requestSkill(AhaSkill requested) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || active) return;

        if (!requested.needsTarget()) {                                  // Technique Intro: no aim ray, no target
            AhaCombatPackets.CHANNEL.sendToServer(new AhaCombatPackets.C2SUseSkill(requested, -1, -1));
            return;
        }

        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F);
        Vec3 end = eye.add(look.scale(TARGET_RANGE));
        AABB box = mc.player.getBoundingBox().expandTowards(look.scale(TARGET_RANGE)).inflate(1.0D);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(mc.player, eye, end, box,
                e -> e instanceof Mob && e.isPickable() && !e.isSpectator(), TARGET_RANGE * TARGET_RANGE);

        if (hit == null) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("Aim at a mob first"), true);
            return;
        }
        AhaCombatPackets.CHANNEL.sendToServer(new AhaCombatPackets.C2SUseSkill(requested, hit.getEntity().getId(), -1));
    }

    public static void onLockState(AhaCombatPackets.S2CLockState msg) {
        switch (msg.phase()) {
            case LOCK -> begin(msg);
            case UNLOCK -> end(false);
            case ABORT -> end(true);
        }
    }

    public static void onBeat(AhaCombatPackets.Beat beat) {
        if (!active) return;
        switch (beat) {
            case MAIN_STRIKE -> { trauma = 1.0F; flash = 0.6F; }
            case BOUNCE -> trauma = Math.max(trauma, 0.45F);
            case AOE_BLAST -> { trauma = Math.max(trauma, 0.6F); flash = 0.3F; }
            case ALLY_BUFF -> flash = 0.25F;
            case CARD_HIT -> { trauma = Math.max(trauma, 0.3F); flash = Math.max(flash, 0.12F); }
            case ROSE_BURST -> { trauma = 1.0F; flash = 0.9F; }
            case ZONE_OPEN -> { trauma = Math.max(trauma, 0.4F); flash = Math.max(flash, 0.5F); }
        }
    }

    public static void onResources(AhaCombatPackets.S2CResources msg) {
        hudWish = msg.wishPower();
        hudPunch = msg.punchlines();
        hudSp = msg.skillPoints();
        hudFaces = msg.facesTurns();
        hudExtra = msg.extraTurn();
    }

    // =====================================================================================================
    // Begin / end
    // =====================================================================================================

    private static void begin(AhaCombatPackets.S2CLockState m) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || m.skill() == null) return;
        if (active) end(true);

        skill = m.skill();
        targetId = m.targetId();
        allyId = m.allyId();
        anchor = new Vec3(m.ax(), m.ay(), m.az());
        lockYaw = m.yaw();
        lockPitch = m.pitch();
        preRoll = Math.max(0, m.preRoll());
        tick = -preRoll;
        lastAlly = anchor;
        if (targetId < 0) {
            // Aimless skill (Technique): the "target" is a point 6 blocks ahead of Aha so the shots still have an axis.
            double yr = Math.toRadians(lockYaw);
            lastTarget = anchor.add(-Math.sin(yr) * 6.0D, 1.0D, Math.cos(yr) * 6.0D);
        } else {
            lastTarget = anchor;
        }
        trauma = 0F;
        flash = 0F;
        currentFov = -1F;

        prevCameraEntity = mc.getCameraEntity();
        prevCameraType = mc.options.getCameraType();
        eyeHeightAtSwap = mc.player.getEyeHeight();
        ticksSinceSwap = 0;

        // Start the marker exactly where the real camera is so the swap itself is invisible.
        camAnchor = new Marker(EntityType.MARKER, mc.level);
        Vec3 eye = mc.player.getEyePosition();
        camAnchor.setPos(eye.x, eye.y, eye.z);

        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.setCameraEntity(camAnchor);
        active = true;
    }

    private static void end(boolean aborted) {
        if (!active) return;
        active = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setCameraEntity(prevCameraEntity != null && prevCameraEntity.isAlive() ? prevCameraEntity : mc.player);
        }
        if (prevCameraType != null) mc.options.setCameraType(prevCameraType);
        camAnchor = null;
        prevCameraEntity = null;
        skill = null;
        currentFov = -1F;
        shakeRoll = 0F;
        trauma = 0F;
        // Camera#eyeHeight now eases back up from ~0; a white flash covers those few frames.
        flash = aborted ? 0.6F : 1.0F;
    }

    // =====================================================================================================
    // Pose computation
    // =====================================================================================================

    private static Vec3 tracked(int id, float pt, boolean isTarget) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(id);
        if (e != null) {
            Vec3 v = e.getPosition(pt).add(0, e.getBbHeight() * 0.6D, 0);
            if (isTarget) lastTarget = v; else lastAlly = v;   // if the mob dies mid-cutscene we hold its last position
            return v;
        }
        return isTarget ? lastTarget : lastAlly;
    }

    private static Vec3 focus(Focus f, Vec3 p, Vec3 t, Vec3 a) {
        return switch (f) {
            case PLAYER -> p;
            case TARGET -> t;
            case ALLY -> a;
            case MIDPOINT -> p.add(t.subtract(p).scale(0.5D));
        };
    }

    private static Pose shotPose(Shot s, float t, Vec3 p, Vec3 tgt, Vec3 ally, Vec3 axisTo) {
        float k = Mth.clamp((t - s.from) / (float) (s.to - s.from), 0F, 1F);
        float e = smooth(k);

        Vec3 center = focus(s.center, p, tgt, ally);
        Vec3 look = focus(s.look, p, tgt, ally);

        double heading = Math.atan2(axisTo.z - p.z, axisTo.x - p.x);          // player -> target direction
        double az = heading + Math.toRadians(Mth.lerp(e, s.az0, s.az1));
        double radius = Mth.lerp(e, s.r0, s.r1);
        Vec3 pos = center.add(Math.cos(az) * radius, Mth.lerp(e, s.h0, s.h1), Math.sin(az) * radius);

        Vec3 d = look.subtract(pos);
        float yaw = (float) (Mth.atan2(-d.x, d.z) * (180D / Math.PI));
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * (180D / Math.PI));
        return new Pose(pos, yaw, pitch, Mth.lerp(e, s.fov0, s.fov1));
    }

    private static Pose playerPose(float pt) {
        Minecraft mc = Minecraft.getInstance();
        float baseFov = mc.options.fov().get().floatValue();
        return new Pose(mc.player.getEyePosition(pt), mc.player.getViewYRot(pt), mc.player.getViewXRot(pt), baseFov);
    }

    private static Pose computePose(float t, float pt) {
        Vec3 p = anchor.add(0, PLAYER_FOCUS_Y, 0);
        Vec3 tgt = tracked(targetId, pt, true);
        Vec3 ally = skill == AhaSkill.SKILL ? tracked(allyId, pt, false) : tgt;
        Vec3 axisTo = skill == AhaSkill.SKILL ? ally : tgt;

        Shot[] shots = SHOTS.get(skill);
        float tc = Math.max(t, 0F);

        int idx = shots.length - 1;
        for (int i = 0; i < shots.length; i++) {
            if (tc < shots[i].to) { idx = i; break; }
        }

        Pose pose = shotPose(shots[idx], tc, p, tgt, ally, axisTo);

        // Cross-blend from the previous shot's final pose so cuts are lerps, not snaps.
        if (idx > 0) {
            float since = tc - shots[idx].from;
            if (since < BLEND_TICKS) {
                Pose prev = shotPose(shots[idx - 1], shots[idx - 1].to, p, tgt, ally, axisTo);
                pose = Pose.lerp(prev, pose, smooth(since / BLEND_TICKS));
            }
        }

        // Intro: ease from the player's real camera into the first shot during the pre-roll.
        if (t < 0F && preRoll > 0) {
            pose = Pose.lerp(playerPose(pt), pose, smooth((t + preRoll) / preRoll));
        }

        // Outro: ease back to the player's real camera over the last OUTRO_TICKS.
        int total = skill.durationTicks();
        if (t > total - OUTRO_TICKS) {
            pose = Pose.lerp(pose, playerPose(pt), smooth((t - (total - OUTRO_TICKS)) / OUTRO_TICKS));
        }
        return pose;
    }

    /**
     * Height by which vanilla's Camera#eyeHeight still lags behind the Marker's eye height (0). Camera eases
     * eyeHeight toward the camera entity's eye height by 50% each tick: E(n) = E0 * 0.5^n, with eyeHeightOld = E(n-1).
     */
    private static double eyeLag(float pt) {
        if (ticksSinceSwap > 14) return 0.0D;
        double cur = eyeHeightAtSwap * Math.pow(0.5D, ticksSinceSwap);
        double old = ticksSinceSwap == 0 ? eyeHeightAtSwap : eyeHeightAtSwap * Math.pow(0.5D, ticksSinceSwap - 1);
        return Mth.lerp(pt, old, cur);
    }

    // =====================================================================================================
    // Forge event hooks
    // =====================================================================================================

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;

        trauma = Math.max(0F, trauma - 0.03F);
        flash = Math.max(0F, flash - 0.12F);
        if (!active) return;

        if (mc.player == null || mc.level == null || skill == null) {
            end(true);
            return;
        }
        tick++;
        ticksSinceSwap++;

        int[][] cues = FLASH_CUES.get(skill);
        if (cues != null) {
            for (int[] c : cues) {
                if (c[0] == tick) flash = Math.max(flash, c[1] / 100F);
            }
        }

        // Keep the (hidden) real player facing the target even if the mouse moves.
        mc.player.setYRot(lockYaw);
        mc.player.setXRot(lockPitch);
        mc.player.yRotO = lockYaw;
        mc.player.xRotO = lockPitch;

        // Fail-safe: the server always sends UNLOCK/ABORT. If that packet is somehow lost, never trap the player.
        if (tick > skill.durationTicks() + 40) end(true);
    }

    /** Fires before GameRenderer#renderLevel, i.e. before Camera#setup reads the camera entity. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || !active || camAnchor == null || skill == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        float pt = event.renderTickTime;
        float t = tick + pt;
        Pose pose = computePose(t, pt);

        // Trauma-style shake: offset grows with trauma^2, built from out-of-phase sines so it stays smooth.
        float tr = Math.max(0F, trauma - 0.03F * pt);
        float s = tr * tr;
        float time = mc.level.getGameTime() + pt;
        double ox = s * 0.45D * (Mth.sin(time * 2.9F) + Mth.sin(time * 5.3F + 1.7F)) * 0.5D;
        double oy = s * 0.45D * (Mth.sin(time * 3.7F + 0.6F) + Mth.sin(time * 6.1F)) * 0.5D;
        shakeRoll = s * 5.0F * Mth.sin(time * 4.3F);

        double x = pose.pos().x + ox;
        double y = pose.pos().y + oy - eyeLag(pt);
        double z = pose.pos().z;

        camAnchor.setPos(x, y, z);
        camAnchor.xo = x; camAnchor.xOld = x;
        camAnchor.yo = y; camAnchor.yOld = y;
        camAnchor.zo = z; camAnchor.zOld = z;
        camAnchor.setYRot(pose.yaw());
        camAnchor.setXRot(pose.pitch());
        camAnchor.yRotO = pose.yaw();
        camAnchor.xRotO = pose.pitch();

        currentFov = pose.fov();
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (active) event.setRoll(event.getRoll() + shakeRoll);
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (active && currentFov > 0F) event.setFOV(currentFov);
    }

    // ---- hide the real player (the GeckoLib avatar stands in) and the first-person hand ----

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (AhaGeckoRegistration.AhaAvatarEntity.isPerforming(event.getEntity().getUUID())) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (active) event.setCanceled(true);
    }

    // ---- input lock (the server enforces position too; this is the client half) ----

    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!active) return;
        var in = event.getInput();
        in.forwardImpulse = 0F;
        in.leftImpulse = 0F;
        in.up = false;
        in.down = false;
        in.left = false;
        in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!active) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!active) return;
        var next = event.getNewScreen();
        // Let pause/death through (they are not "skipping"); block inventory and friends.
        if (next != null && !(next instanceof PauseScreen) && !(next instanceof DeathScreen)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        active = false;
        skill = null;
        camAnchor = null;
        prevCameraEntity = null;
        flash = 0F;
        trauma = 0F;
    }

    // =====================================================================================================
    // GUI overlay: letterbox, the shifting masks of Aha, flash, and a small resource HUD
    // =====================================================================================================

    @Mod.EventBusSubscriber(modid = AhaGeckoRegistration.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {

        private ModBusEvents() {}

        @SubscribeEvent
        public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("aha_cutscene", AhaCinematicCameraEngine::renderOverlay);
        }
    }

    private static void renderOverlay(ForgeGui gui, GuiGraphics g, float pt, int w, int h) {
        Minecraft mc = Minecraft.getInstance();

        if (active && skill != null) {
            float t = tick + pt;
            float in = smooth((t + preRoll) / 8F);
            float out = smooth((skill.durationTicks() - t) / 8F);
            int bar = (int) (Math.min(in, out) * h * 0.11F);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);

            if (skill == AhaSkill.ULTIMATE && t >= MASK_FROM && t < MASK_TO) drawMasks(g, t, w, h);   // masks flicker during the crown-stage hold
        }

        float f = Mth.clamp(flash - 0.12F * pt, 0F, 1F);
        if (f > 0F) g.fill(0, 0, w, h, ((int) (f * 255F) << 24) | 0xFFFFFF);

        if (!active) {
            String line = "Wish " + hudWish + "/" + AhaCombatServer.WISH_CAP
                    + "   Punchlines " + hudPunch + "/" + AhaCombatServer.PUNCH_CAP
                    + "   SP " + hudSp
                    + (hudFaces > 0 ? "   Faces x" + hudFaces : "")
                    + (hudExtra ? "   EXTRA TURN" : "");
            g.drawString(mc.font, line, 6, h - 14, 0xE0C8FF, true);
        }
    }

    /** Masks cycle every 6 ticks, each pulsing in and out. Textures are expected to be 256x256 with alpha. */
    private static void drawMasks(GuiGraphics g, float t, int w, int h) {
        int idx = ((int) (t / 6F)) % MASKS.length;
        float local = (t % 6F) / 6F;
        float alpha = (float) Math.sin(local * Math.PI) * 0.85F;
        int size = (int) (h * 0.6F * (1.0F + 0.08F * local));
        int x = (w - size) / 2;
        int y = (h - size) / 2;

        RenderSystem.enableBlend();
        g.setColor(1F, 1F, 1F, alpha);
        g.blit(MASKS[idx], x, y, size, size, 0F, 0F, 256, 256, 256, 256);
        g.setColor(1F, 1F, 1F, 1F);
        RenderSystem.disableBlend();
    }
}
