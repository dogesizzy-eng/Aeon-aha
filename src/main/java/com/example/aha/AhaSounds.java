package com.example.aha;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Sound events for the skill beats. assets/aha/sounds.json maps each one to a vanilla placeholder sound.
 * To use your own audio: put an .ogg in assets/aha/sounds/ and point the event in sounds.json at it
 * (see assets/aha/sounds/README.txt).
 */
public final class AhaSounds {

    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, AhaGeckoRegistration.MODID);

    public static final RegistryObject<SoundEvent> BASIC_HIT = reg("basic_hit");
    public static final RegistryObject<SoundEvent> SKILL_BUFF = reg("skill_buff");
    public static final RegistryObject<SoundEvent> SKILL_BLAST = reg("skill_blast");
    public static final RegistryObject<SoundEvent> ULT_STRIKE = reg("ult_strike");
    public static final RegistryObject<SoundEvent> ULT_BOUNCE = reg("ult_bounce");
    public static final RegistryObject<SoundEvent> TECHNIQUE = reg("technique");
    /** Elation Skill: one Punchline card landing, the rose blooming, the rose detonating. */
    public static final RegistryObject<SoundEvent> ELATION_CARD = reg("elation_card");
    public static final RegistryObject<SoundEvent> ELATION_BLOOM = reg("elation_bloom");
    public static final RegistryObject<SoundEvent> ELATION_ROSE = reg("elation_rose");

    private AhaSounds() {}

    private static RegistryObject<SoundEvent> reg(String name) {
        return SOUNDS.register(name,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(AhaGeckoRegistration.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    public static void play(ServerLevel level, Vec3 pos, RegistryObject<SoundEvent> sound) {
        level.playSound(null, pos.x, pos.y, pos.z, sound.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
    }
}
