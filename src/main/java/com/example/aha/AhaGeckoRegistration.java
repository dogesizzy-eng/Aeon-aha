package com.example.aha;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.joml.Vector3f;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.client.RenderProvider;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.model.DefaultedItemGeoModel;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * GeckoLib setup for Aha: everything that needs a registry entry or a GeckoLib animatable.
 *
 * <h2>Targets</h2>
 * Forge 1.20.1 (47.x), Java 17, GeckoLib 4.4+ for 1.20.1. build.gradle:
 * <pre>
 * repositories { maven { url = "https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/" } }
 * dependencies { implementation fg.deobf("software.bernie.geckolib:geckolib-forge-1.20.1:4.x.y") }  // pin the latest 4.x
 * </pre>
 * Also declare geckolib as a required dependency in mods.toml.
 *
 * <h2>Wiring (mod main class)</h2>
 * <pre>
 * &#64;Mod(AhaGeckoRegistration.MODID)
 * public class AhaMod {
 *     public AhaMod() { AhaGeckoRegistration.register(FMLJavaModLoadingContext.get().getModEventBus()); }
 * }
 * </pre>
 *
 * <h2>Two GeckoLib realities worth knowing</h2>
 * <ol>
 *   <li><b>The vanilla Player cannot implement GeoEntity</b> (you cannot change its superclass). So the player
 *   is "animated" through a puppet: {@link AhaAvatarEntity}, a GeckoLib entity spawned at the player's position
 *   for the length of the cutscene, while the real player model is hidden client-side (see the camera engine).
 *   The alternative is GeckoLib's GeoReplacedEntity + a replaced-entity renderer on RenderPlayerEvent.Pre, which
 *   is more invasive and fights other mods' player renderers.</li>
 *   <li><b>GeckoLib 4 has no particle-animation system.</b> What it does have is keyframe handlers inside
 *   animations (sound / particle / custom-instruction). We use custom-instruction keyframes to emit vanilla
 *   particles at exact animation moments, and GeckoLib-animated "FX entities" (bounce projectiles, AoE blast,
 *   dimension dome) for anything that needs real animated geometry.</li>
 * </ol>
 *
 * <h2>Server/animation sync strategy</h2>
 * Animations are purely cosmetic. They are started by the server (triggerAnim sends the packet) on the same
 * tick the server-side timeline in {@link AhaCombatServer} starts, and damage is registered on the tick
 * numbers in {@link AhaSkill}. The client never reports hits, so lag or a skipped frame cannot desync damage.
 *
 * <h2>Assets you must author (Blockbench + GeckoLib plugin)</h2>
 * <pre>
 * geo/entity/aha_avatar.geo.json              animations/entity/aha_avatar.animation.json   (names: animation.aha.idle,
 *                                                                                           animation.aha.basic_slash,
 *                                                                                           animation.aha.skill_aoe_cascade,
 *                                                                                           animation.aha.ultimate_faces)
 * geo/entity/fx_bounce|aoe_blast|main_strike|zone.geo.json   animations/entity/aha_fx.animation.json
 *                                              (names: animation.aha_fx.bounce|aoe_blast|main_strike|zone)
 * geo/item/aha_staff.geo.json                 animations/item/aha_staff.animation.json      (animation.aha_staff.cast)
 * textures/entity/aha_avatar.png, fx_*.png, textures/item/aha_staff.png, textures/gui/aha_mask_0..3.png (256x256)
 * </pre>
 * In Blockbench, add a custom-instruction keyframe named "quantum_burst" at the impact frame of each skill
 * animation to get the particle burst below.
 */
public final class AhaGeckoRegistration {

    public static final String MODID = "aha";
    public static final String MAIN_CONTROLLER = "aha_main";
    public static final String STAFF_CONTROLLER = "aha_staff";

    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    private static final DeferredRegister<MobEffect> MOB_EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, MODID);

    public static final RegistryObject<EntityType<AhaAvatarEntity>> AVATAR = ENTITIES.register("aha_avatar",
            () -> EntityType.Builder.<AhaAvatarEntity>of(AhaAvatarEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.9F).clientTrackingRange(10).updateInterval(2).noSave().fireImmune()
                    .build("aha_avatar"));

    public static final RegistryObject<EntityType<AhaFxEntity>> FX = ENTITIES.register("aha_fx",
            () -> EntityType.Builder.<AhaFxEntity>of(AhaFxEntity::new, MobCategory.MISC)
                    .sized(2.0F, 2.0F).clientTrackingRange(16).updateInterval(20).noSave().fireImmune()
                    .build("aha_fx"));

    public static final RegistryObject<Item> STAFF = ITEMS.register("aha_staff",
            () -> new AhaStaffItem(new Item.Properties().stacksTo(1)));

    /** Marker/visual status for "Elation Debt". The permanent Max HP loss itself is an AttributeModifier (see AhaCombatServer). */
    public static final RegistryObject<MobEffect> ELATION_DEBT = MOB_EFFECTS.register("elation_debt", ElationDebtEffect::new);

    private AhaGeckoRegistration() {}

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        MOB_EFFECTS.register(modBus);
    }

    // =====================================================================================================
    // Avatar puppet: the GeckoLib-animated stand-in for the player during a cutscene
    // =====================================================================================================

    public static class AhaAvatarEntity extends Entity implements GeoEntity {

        private static final EntityDataAccessor<Optional<UUID>> OWNER =
                SynchedEntityData.defineId(AhaAvatarEntity.class, EntityDataSerializers.OPTIONAL_UUID);

        /** Client-side: players who currently have an avatar. The camera engine hides their real model. */
        private static final Set<UUID> PERFORMERS = ConcurrentHashMap.newKeySet();

        private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.aha.idle");

        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        public AhaAvatarEntity(EntityType<?> type, Level level) {
            super(type, level);
            this.noPhysics = true;
        }

        public static boolean isPerforming(UUID playerId) {
            return PERFORMERS.contains(playerId);
        }

        public void setOwner(UUID id) { this.entityData.set(OWNER, Optional.of(id)); }

        @Nullable
        public UUID getOwnerId() { return this.entityData.get(OWNER).orElse(null); }

        @Override
        protected void defineSynchedData() { this.entityData.define(OWNER, Optional.empty()); }

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override
        public boolean isPickable() { return false; }

        @Override
        public boolean isPushable() { return false; }

        @Override
        public void tick() {
            super.tick();
            // Leak guard: the combat server normally discards us; never outlive a crashed cutscene.
            if (!this.level().isClientSide && this.tickCount > 600) this.discard();     // longest cutscene is 380 ticks + pre-roll
        }

        @Override
        public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
            super.onSyncedDataUpdated(key);
            if (this.level().isClientSide && OWNER.equals(key)) {
                UUID id = getOwnerId();
                if (id != null) PERFORMERS.add(id);
            }
        }

        @Override
        public void remove(RemovalReason reason) {
            if (this.level().isClientSide) {
                UUID id = getOwnerId();
                if (id != null) PERFORMERS.remove(id);
            }
            super.remove(reason);
        }

        // ---- GeckoLib ----

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            // One controller: idle loop by default, and one TRIGGERABLE animation per skill.
            // triggerAnim(MAIN_CONTROLLER, skill.trigger()) on the server plays it on every tracking client.
            AnimationController<AhaAvatarEntity> main = new AnimationController<AhaAvatarEntity>(
                    this, MAIN_CONTROLLER, 4, state -> state.setAndContinue(IDLE))
                    .setCustomInstructionKeyframeHandler(event -> onKeyframe(event.getKeyframeData().getInstructions()));

            for (AhaSkill skill : AhaSkill.values()) {
                main.triggerableAnim(skill.trigger(), RawAnimation.begin().thenPlay("animation.aha." + skill.trigger()));
            }
            controllers.add(main);
        }

        /** Runs on the client render thread, driven by keyframes inside the animation. Cosmetic only. */
        private void onKeyframe(String instructions) {
            // Wand tip glows while the heart crest forms (basic attack, 0.9 s).
            if (instructions.contains("heart_glow")) {
                for (int i = 0; i < 8; i++) {
                    this.level().addParticle(ParticleTypes.GLOW,
                            getX() + this.random.nextGaussian() * 0.25D - 0.4D,
                            getY() + 1.9D + this.random.nextGaussian() * 0.25D,
                            getZ() + this.random.nextGaussian() * 0.25D, 0.0D, 0.03D, 0.0D);
                }
            }
            // Pink glow around Aha's head while she charges the skill (skill, 0.75 s).
            if (instructions.contains("pink_glow")) {
                for (int i = 0; i < 18; i++) {
                    this.level().addParticle(i % 3 == 0 ? ParticleTypes.HEART : ParticleTypes.ENCHANT,
                            getX() + this.random.nextGaussian() * 0.5D,
                            getY() + 1.8D + this.random.nextGaussian() * 0.4D,
                            getZ() + this.random.nextGaussian() * 0.5D, 0.0D, 0.05D, 0.0D);
                }
            }
            // Eye flare for the close-up (skill, 3.0 s).
            if (instructions.contains("eye_glow")) {
                for (int i = 0; i < 12; i++) {
                    this.level().addParticle(ParticleTypes.END_ROD,
                            getX() + this.random.nextGaussian() * 0.15D,
                            getY() + 1.65D + this.random.nextGaussian() * 0.1D,
                            getZ() + this.random.nextGaussian() * 0.15D,
                            this.random.nextGaussian() * 0.04D, 0.02D, this.random.nextGaussian() * 0.04D);
                }
            }
            // A card appears between Aha's fingers (Elation Skill intro, Technique Intro).
            if (instructions.contains("card_glow")) {
                DustParticleOptions spark = new DustParticleOptions(new Vector3f(1.0F, 0.85F, 0.35F), 1.0F);
                for (int i = 0; i < 14; i++) {
                    this.level().addParticle(i % 2 == 0 ? ParticleTypes.END_ROD : spark,
                            getX() - 0.45D + this.random.nextGaussian() * 0.2D, getY() + 1.5D + this.random.nextGaussian() * 0.2D,
                            getZ() - 0.3D + this.random.nextGaussian() * 0.2D, 0.0D, 0.03D, 0.0D);
                }
            }
            // Golden star ring (crown stage / ring behind the grin ball).
            if (instructions.contains("stars")) {
                for (int i = 0; i < 24; i++) {
                    double a = i / 24.0D * Math.PI * 2.0D;
                    this.level().addParticle(ParticleTypes.END_ROD,
                            getX() + Math.cos(a) * 1.6D, getY() + 1.2D + this.random.nextDouble() * 1.2D, getZ() + Math.sin(a) * 1.6D,
                            Math.cos(a) * 0.04D, 0.03D, Math.sin(a) * 0.04D);
                }
            }
            if (instructions.contains("hearts")) {
                for (int i = 0; i < 30; i++) {
                    this.level().addParticle(ParticleTypes.HEART,
                            getX() + this.random.nextGaussian() * 1.4D,
                            getY() + 1.0D + this.random.nextDouble() * 2.0D,
                            getZ() + this.random.nextGaussian() * 1.4D,
                            this.random.nextGaussian() * 0.05D, 0.08D + this.random.nextDouble() * 0.08D,
                            this.random.nextGaussian() * 0.05D);
                }
            }
            if (instructions.contains("quantum_burst")) {
                for (int i = 0; i < 14; i++) {
                    this.level().addParticle(ParticleTypes.REVERSE_PORTAL,
                            getX() + this.random.nextGaussian() * 0.7D,
                            getY() + 1.0D + this.random.nextGaussian() * 0.5D,
                            getZ() + this.random.nextGaussian() * 0.7D,
                            0.0D, 0.04D, 0.0D);
                }
            }
            // Elation Skill: red petals swirl around the fused card, then the detonation.
            if (instructions.contains("rose_petals")) {
                DustParticleOptions petal = new DustParticleOptions(new Vector3f(0.86F, 0.08F, 0.19F), 1.4F);
                for (int i = 0; i < 26; i++) {
                    double a = i / 26.0D * Math.PI * 2.0D;
                    this.level().addParticle(i % 4 == 0 ? ParticleTypes.CRIMSON_SPORE : petal,
                            getX() + Math.cos(a) * 1.1D, getY() + 1.1D + this.random.nextDouble() * 1.0D, getZ() + Math.sin(a) * 1.1D,
                            -Math.sin(a) * 0.06D, 0.04D, Math.cos(a) * 0.06D);
                }
            }
            if (instructions.contains("rose_burst")) {
                DustParticleOptions petal = new DustParticleOptions(new Vector3f(1.0F, 0.25F, 0.2F), 1.8F);
                for (int i = 0; i < 40; i++) {
                    this.level().addParticle(i % 3 == 0 ? ParticleTypes.FLAME : petal,
                            getX() + this.random.nextGaussian() * 0.5D, getY() + 1.2D + this.random.nextGaussian() * 0.4D,
                            getZ() + this.random.nextGaussian() * 0.5D,
                            this.random.nextGaussian() * 0.25D, 0.1D + this.random.nextDouble() * 0.2D, this.random.nextGaussian() * 0.25D);
                }
            }
            if (instructions.contains("confetti")) {
                for (int i = 0; i < 40; i++) {
                    this.level().addParticle(i % 2 == 0 ? ParticleTypes.FIREWORK : ParticleTypes.END_ROD,
                            getX() + this.random.nextGaussian() * 1.2D,
                            getY() + 1.5D + this.random.nextGaussian() * 0.8D,
                            getZ() + this.random.nextGaussian() * 1.2D,
                            this.random.nextGaussian() * 0.12D, 0.10D + this.random.nextDouble() * 0.15D,
                            this.random.nextGaussian() * 0.12D);
                }
            }
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    }

    // =====================================================================================================
    // FX entity: GeckoLib-animated visuals (bounce projectile, AoE blast, main strike, dimension dome)
    // They deal NO damage. The server timeline does. They are spawned a few ticks before the impact tick so
    // the visual "arrives" exactly when the damage registers.
    // =====================================================================================================

    public static class AhaFxEntity extends Entity implements GeoEntity {

        /**
         * Each FX kind carries its own lead: ticks the server spawns it BEFORE the impact tick, equal to the point in the
         * FX animation where the hit lands (heart crest bursts at 0.75 s, grin ball detonates at 4.5 s, ...).
         * Do not append kinds in the middle: the ordinal is what gets synced to clients.
         */
        public enum Kind {
            BOUNCE(false, 24, 8),
            AOE_BLAST(false, 120, 90),
            MAIN_STRIKE(false, 50, 11),
            ZONE(true, 600, 0),
            HEART_CREST(false, 36, 15),
            /** Elation Skill: the Punchline cards fuse into a rose (0-1 s), it blooms and detonates at 2.5 s = lead 50. */
            ROSE(false, 70, 50);

            public final boolean loop;
            public final int lifetime;
            public final int lead;

            Kind(boolean loop, int lifetime, int lead) {
                this.loop = loop;
                this.lifetime = lifetime;
                this.lead = lead;
            }

            public String assetName() { return name().toLowerCase(Locale.ROOT); }

            public String animation() { return "animation.aha_fx." + assetName(); }

            /** The dimension dome plays an opening sweep once, then loops. */
            public RawAnimation rawAnimation() {
                if (this == ZONE) return RawAnimation.begin().thenPlay("animation.aha_fx.zone_open").thenLoop(animation());
                return loop ? RawAnimation.begin().thenLoop(animation()) : RawAnimation.begin().thenPlay(animation());
            }
        }

        private static final EntityDataAccessor<Integer> KIND =
                SynchedEntityData.defineId(AhaFxEntity.class, EntityDataSerializers.INT);

        private static final EnumMap<Kind, RawAnimation> ANIMS = new EnumMap<>(Kind.class);
        static {
            for (Kind k : Kind.values()) {
                ANIMS.put(k, k.rawAnimation());
            }
        }

        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
        private int lifetime = 40;

        public AhaFxEntity(EntityType<?> type, Level level) {
            super(type, level);
            this.noPhysics = true;
        }

        /** Server-side helper. lifetimeOverride &lt;= 0 uses the kind's default. */
        @Nullable
        public static AhaFxEntity spawn(ServerLevel level, Kind kind, Vec3 pos, int lifetimeOverride) {
            AhaFxEntity fx = FX.get().create(level);
            if (fx == null) return null;
            fx.setKind(kind);
            fx.lifetime = lifetimeOverride > 0 ? lifetimeOverride : kind.lifetime;
            fx.setPos(pos.x, pos.y, pos.z);
            level.addFreshEntity(fx);
            return fx;
        }

        public void setKind(Kind kind) { this.entityData.set(KIND, kind.ordinal()); }

        public Kind getKind() {
            Kind[] all = Kind.values();
            return all[Mth.clamp(this.entityData.get(KIND), 0, all.length - 1)];
        }

        @Override
        protected void defineSynchedData() { this.entityData.define(KIND, 0); }

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}

        @Override
        public boolean isPickable() { return false; }

        @Override
        public void tick() {
            super.tick();
            if (!this.level().isClientSide && this.tickCount >= this.lifetime) this.discard();
        }

        /** Big FX (dome, grin ball + heart burst, 20-block light beam) must not be frustum-culled when only part is on screen. */
        @Override
        public AABB getBoundingBoxForCulling() {
            return switch (getKind()) {
                case ZONE -> getBoundingBox().inflate(12.0D);
                case AOE_BLAST -> getBoundingBox().inflate(6.0D).expandTowards(0.0D, 8.0D, 0.0D);
                case MAIN_STRIKE -> getBoundingBox().inflate(3.0D).expandTowards(0.0D, 20.0D, 0.0D);
                case ROSE -> getBoundingBox().inflate(12.0D).expandTowards(0.0D, 6.0D, 0.0D);
                default -> super.getBoundingBoxForCulling();
            };
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            // Predicate-driven (no triggers): the synced KIND decides which animation plays, so a client that
            // starts tracking the entity late still shows the right thing.
            controllers.add(new AnimationController<AhaFxEntity>(this, "fx", 0,
                    state -> state.setAndContinue(ANIMS.get(getKind()))));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    }

    // =====================================================================================================
    // Staff item (GeoItem). Right-click = Technique "Misjudgment Dimension".
    // =====================================================================================================

    public static class AhaStaffItem extends Item implements GeoItem {

        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
        private final Supplier<Object> renderProvider = GeoItem.makeRenderer(this);

        public AhaStaffItem(Properties properties) {
            super(properties);
            // Required so server-triggered animations on this singleton item reach the clients.
            SingletonGeoAnimatable.registerSyncedAnimatable(this);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                // Same path as the Technique key: the server validates, starts the Technique Intro cutscene and applies the
                // cooldown (to the session and to this staff). Nothing is cast, and no cooldown is shown, if it was refused.
                if (AhaCombatServer.handleUseSkill(serverPlayer, AhaSkill.TECHNIQUE, -1, -1)) {
                    triggerAnim(player, GeoItem.getOrAssignId(stack, serverLevel), STAFF_CONTROLLER, "cast");
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            // STOP predicate = no default animation; only the triggered "cast" ever plays.
            controllers.add(new AnimationController<AhaStaffItem>(this, STAFF_CONTROLLER, 0, state -> PlayState.STOP)
                    .triggerableAnim("cast", RawAnimation.begin().thenPlay("animation.aha_staff.cast")));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

        @Override
        public void createRenderer(Consumer<Object> consumer) {
            consumer.accept(new RenderProvider() {
                private AhaStaffRenderer renderer;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (this.renderer == null) this.renderer = new AhaStaffRenderer();
                    return this.renderer;
                }
            });
        }

        @Override
        public Supplier<Object> getRenderProvider() { return renderProvider; }
    }

    /** Client-only; only ever instantiated from the RenderProvider above. */
    public static class AhaStaffRenderer extends GeoItemRenderer<AhaStaffItem> {
        public AhaStaffRenderer() {
            super(new DefaultedItemGeoModel<>(new ResourceLocation(MODID, "aha_staff")));
        }
    }

    // =====================================================================================================
    // Elation Debt status effect (visual marker + icon; the HP loss is applied in AhaCombatServer)
    // =====================================================================================================

    public static class ElationDebtEffect extends MobEffect {
        public ElationDebtEffect() { super(MobEffectCategory.HARMFUL, 0x7B2CBF); }
    }

    // =====================================================================================================
    // Client renderer registration
    // =====================================================================================================

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ClientModEvents {

        private ClientModEvents() {}

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(AVATAR.get(),
                    ctx -> new GeoEntityRenderer<>(ctx, new DefaultedEntityGeoModel<>(new ResourceLocation(MODID, "aha_avatar"))));
            event.registerEntityRenderer(FX.get(), ctx -> new GeoEntityRenderer<>(ctx, new FxModel()));
        }
    }

    /** One FX entity type, several looks: the model/texture follow the synced Kind. */
    private static final class FxModel extends GeoModel<AhaFxEntity> {
        @Override
        public ResourceLocation getModelResource(AhaFxEntity fx) {
            return new ResourceLocation(MODID, "geo/entity/fx_" + fx.getKind().assetName() + ".geo.json");
        }

        @Override
        public ResourceLocation getTextureResource(AhaFxEntity fx) {
            return new ResourceLocation(MODID, "textures/entity/fx_" + fx.getKind().assetName() + ".png");
        }

        @Override
        public ResourceLocation getAnimationResource(AhaFxEntity fx) {
            return new ResourceLocation(MODID, "animations/entity/aha_fx.animation.json");
        }
    }
}
