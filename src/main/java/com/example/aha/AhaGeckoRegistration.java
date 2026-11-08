package com.example.aha;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
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

    public static final RegistryObject<MobEffect> ELATION_DEBT = MOB_EFFECTS.register("elation_debt", ElationDebtEffect::new);

    private AhaGeckoRegistration() {}

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        ITEMS.register(modBus);
        MOB_EFFECTS.register(modBus);
    }

    // =====================================================================================================
    // Avatar puppet
    // =====================================================================================================

    public static class AhaAvatarEntity extends Entity implements GeoEntity {

        private static final EntityDataAccessor<Optional<UUID>> OWNER =
                SynchedEntityData.defineId(AhaAvatarEntity.class, EntityDataSerializers.OPTIONAL_UUID);

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
            if (!this.level().isClientSide && this.tickCount > 600) this.discard();
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

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            AnimationController<AhaAvatarEntity> main = new AnimationController<AhaAvatarEntity>(
                    this, MAIN_CONTROLLER, 4, state -> state.setAndContinue(IDLE))
                    .setCustomInstructionKeyframeHandler(event -> onKeyframe(event.getKeyframeData().getInstructions()));

            for (AhaSkill skill : AhaSkill.values()) {
                main.triggerableAnim(skill.trigger(), RawAnimation.begin().thenPlay("animation.aha." + skill.trigger()));
            }
            controllers.add(main);
        }

        private void onKeyframe(String instructions) {
            if (instructions.contains("heart_glow")) {
                for (int i = 0; i < 8; i++) {
                    this.level().addParticle(ParticleTypes.GLOW,
                            getX() + this.random.nextGaussian() * 0.25D - 0.4D,
                            getY() + 1.9D + this.random.nextGaussian() * 0.25D,
                            getZ() + this.random.nextGaussian() * 0.25D, 0.0D, 0.03D, 0.0D);
                }
            }
            if (instructions.contains("pink_glow")) {
                for (int i = 0; i < 18; i++) {
                    this.level().addParticle(i % 3 == 0 ? ParticleTypes.HEART : ParticleTypes.ENCHANT,
                            getX() + this.random.nextGaussian() * 0.5D,
                            getY() + 1.8D + this.random.nextGaussian() * 0.4D,
                            getZ() + this.random.nextGaussian() * 0.5D, 0.0D, 0.05D, 0.0D);
                }
            }
            if (instructions.contains("eye_glow")) {
                for (int i = 0; i < 12; i++) {
                    this.level().addParticle(ParticleTypes.END_ROD,
                            getX() + this.random.nextGaussian() * 0.15D,
                            getY() + 1.65D + this.random.nextGaussian() * 0.1D,
                            getZ() + this.random.nextGaussian() * 0.15D,
                            this.random.nextGaussian() * 0.04D, 0.02D, this.random.nextGaussian() * 0.04D);
                }
            }
            if (instructions.contains("card_glow")) {
                DustParticleOptions spark = new DustParticleOptions(new Vector3f(1.0F, 0.85F, 0.35F), 1.0F);
                for (int i = 0; i < 14; i++) {
                    this.level().addParticle(i % 2 == 0 ? ParticleTypes.END_ROD : spark,
                            getX() - 0.45D + this.random.nextGaussian() * 0.2D, getY() + 1.5D + this.random.nextGaussian() * 0.2D,
                            getZ() - 0.3D + this.random.nextGaussian() * 0.2D, 0.0D, 0.03D, 0.0D);
                }
            }
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
    // FX entity
    // =====================================================================================================

    public static class AhaFxEntity extends Entity implements GeoEntity {

        public enum Kind {
            BOUNCE(false, 24, 8),
            AOE_BLAST(false, 120, 90),
            MAIN_STRIKE(false, 50, 11),
            ZONE(true, 600, 0),
            HEART_CREST(false, 36, 15),
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
            controllers.add(new AnimationController<AhaFxEntity>(this, "fx", 0,
                    state -> state.setAndContinue(ANIMS.get(getKind()))));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    }

    // =====================================================================================================
    // Staff item (Forge Client Extension)
    // =====================================================================================================

    public static class AhaStaffItem extends Item implements GeoItem {

        private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

        public AhaStaffItem(Properties properties) {
            super(properties);
            SingletonGeoAnimatable.registerSyncedAnimatable(this);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
                if (AhaCombatServer.handleUseSkill(serverPlayer, AhaSkill.TECHNIQUE, -1, -1)) {
                    triggerAnim(player, GeoItem.getOrAssignId(stack, serverLevel), STAFF_CONTROLLER, "cast");
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        @Override
        public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
            controllers.add(new AnimationController<AhaStaffItem>(this, STAFF_CONTROLLER, 0, state -> PlayState.STOP)
                    .triggerableAnim("cast", RawAnimation.begin().thenPlay("animation.aha_staff.cast")));
        }

        @Override
        public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

        @Override
        public void initializeClient(Consumer<IClientItemExtensions> consumer) {
            consumer.accept(new IClientItemExtensions() {
                private AhaStaffRenderer renderer;

                @Override
                public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                    if (this.renderer == null) this.renderer = new AhaStaffRenderer();
                    return this.renderer;
                }
            });
        }
    }

    public static class AhaStaffRenderer extends GeoItemRenderer<AhaStaffItem> {
        public AhaStaffRenderer() {
            super(new DefaultedItemGeoModel<>(new ResourceLocation(MODID, "aha_staff")));
        }
    }

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
