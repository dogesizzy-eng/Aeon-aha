package com.example.aha;

import java.util.function.Supplier;

import javax.annotation.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Network layer for the turn-based lock state.
 *
 * <pre>
 *   CLIENT                                   SERVER (authoritative)
 *   C2SUseSkill(skill, target, ally) ------> validate: target, range, line of sight, resources, no active action
 *                                            freeze mob (setNoAi), anchor player, spawn avatar, spend resources
 *   S2CLockState(LOCK, ...)          <------ client: lock input, swap to cinematic camera, start overlay
 *   S2CBeat(MAIN_STRIKE...)          <------ server fires one on each impact tick -> client shake / flash
 *   S2CResources(...)                <------ HUD sync (Wish Power, Punchlines, SP, Faces turns, extra turn)
 *   S2CLockState(UNLOCK | ABORT)     <------ cutscene over (or aborted): client restores camera + input
 * </pre>
 *
 * Design rules:
 * <ul>
 *   <li>The client can only ASK (C2SUseSkill). It has no "cancel" or "skip" packet, so cutscenes are
 *       unskippable by construction. Only the server ends a lock.</li>
 *   <li>Every S2C handler hops to {@link AhaCinematicCameraEngine} through DistExecutor so the client-only
 *       class is never loaded on a dedicated server.</li>
 *   <li>Enum ids are bounds-checked on decode; a malformed packet is ignored instead of throwing.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = AhaGeckoRegistration.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class AhaCombatPackets {

    /** Bumped to "2": AhaSkill gained ELATION_SKILL and TECHNIQUE, so old and new clients must not talk to each other. */
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(AhaGeckoRegistration.MODID, "combat"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private AhaCombatPackets() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(AhaCombatPackets::registerAll);
    }

    private static void registerAll() {
        int id = 0;
        CHANNEL.messageBuilder(C2SUseSkill.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(C2SUseSkill::encode).decoder(C2SUseSkill::decode).consumerMainThread(C2SUseSkill::handle).add();
        CHANNEL.messageBuilder(S2CLockState.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CLockState::encode).decoder(S2CLockState::decode).consumerMainThread(S2CLockState::handle).add();
        CHANNEL.messageBuilder(S2CBeat.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CBeat::encode).decoder(S2CBeat::decode).consumerMainThread(S2CBeat::handle).add();
        CHANNEL.messageBuilder(S2CResources.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(S2CResources::encode).decoder(S2CResources::decode).consumerMainThread(S2CResources::handle).add();
    }

    public static void sendToPlayer(Object message, ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    // =====================================================================================================
    // Shared enums
    // =====================================================================================================

    public enum LockPhase { LOCK, UNLOCK, ABORT }

    /** Server-timed moments the client reacts to (shake, flash). Fired on the matching impact tick. */
    public enum Beat {
        ALLY_BUFF, AOE_BLAST, MAIN_STRIKE, BOUNCE,
        /** One Punchline card lands (Elation Skill). */
        CARD_HIT,
        /** The rose detonates (Elation Skill finisher). */
        ROSE_BURST,
        /** The Misjudgment Dimension opens (Technique Intro). */
        ZONE_OPEN
    }

    // =====================================================================================================
    // C2S: ask to use a skill
    // =====================================================================================================

    /**
     * @param targetId entity id of the mob the player is locking onto, or -1 for skills that need no target
     *                 (the Technique Intro; see {@link AhaSkill#needsTarget()})
     * @param allyId   entity id of the designated ally for SKILL, or -1 to let the server pick the nearest
     *                 tamed animal / player-made iron golem (falls back to the caster)
     */
    public record C2SUseSkill(@Nullable AhaSkill skill, int targetId, int allyId) {

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(skill == null ? -1 : skill.ordinal());
            buf.writeVarInt(targetId);
            buf.writeInt(allyId);
        }

        static C2SUseSkill decode(FriendlyByteBuf buf) {
            return new C2SUseSkill(AhaSkill.byId(buf.readVarInt()), buf.readVarInt(), buf.readInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer sender = ctx.get().getSender();
            if (sender != null && skill != null) {
                // Everything is re-validated server-side; nothing in this packet is trusted.
                AhaCombatServer.handleUseSkill(sender, skill, targetId, allyId);
            }
        }
    }

    // =====================================================================================================
    // S2C: lock / unlock the player's turn-based state
    // =====================================================================================================

    /**
     * @param preRoll ticks of "transition" before the animation starts. 0-ish (the server keeps a 2-tick
     *                minimum so the avatar entity is tracked before its animation is triggered) when the
     *                target was struck inside the Misjudgment Dimension.
     */
    public record S2CLockState(LockPhase phase, @Nullable AhaSkill skill, int targetId, int allyId,
                               double ax, double ay, double az, float yaw, float pitch, int preRoll) {

        public static S2CLockState unlock(boolean aborted) {
            return new S2CLockState(aborted ? LockPhase.ABORT : LockPhase.UNLOCK, null, -1, -1, 0, 0, 0, 0F, 0F, 0);
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeByte(phase.ordinal());
            buf.writeVarInt(skill == null ? -1 : skill.ordinal());
            buf.writeVarInt(targetId);
            buf.writeVarInt(allyId);
            buf.writeDouble(ax);
            buf.writeDouble(ay);
            buf.writeDouble(az);
            buf.writeFloat(yaw);
            buf.writeFloat(pitch);
            buf.writeVarInt(preRoll);
        }

        static S2CLockState decode(FriendlyByteBuf buf) {
            LockPhase[] phases = LockPhase.values();
            LockPhase phase = phases[Mth.clamp(buf.readByte(), 0, phases.length - 1)];
            return new S2CLockState(phase, AhaSkill.byId(buf.readVarInt()), buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AhaCinematicCameraEngine.onLockState(this));
        }
    }

    // =====================================================================================================
    // S2C: server-timed beats (screen shake / flash synced to the real impact tick)
    // =====================================================================================================

    public record S2CBeat(Beat beat) {

        void encode(FriendlyByteBuf buf) { buf.writeByte(beat.ordinal()); }

        static S2CBeat decode(FriendlyByteBuf buf) {
            Beat[] all = Beat.values();
            return new S2CBeat(all[Mth.clamp(buf.readByte(), 0, all.length - 1)]);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AhaCinematicCameraEngine.onBeat(beat));
        }
    }

    // =====================================================================================================
    // S2C: HUD resource snapshot
    // =====================================================================================================

    public record S2CResources(int wishPower, int punchlines, int skillPoints, int facesTurns, boolean extraTurn) {

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(wishPower);
            buf.writeVarInt(punchlines);
            buf.writeVarInt(skillPoints);
            buf.writeVarInt(facesTurns);
            buf.writeBoolean(extraTurn);
        }

        static S2CResources decode(FriendlyByteBuf buf) {
            return new S2CResources(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> AhaCinematicCameraEngine.onResources(this));
        }
    }
}
