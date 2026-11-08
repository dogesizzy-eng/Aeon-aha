package com.example.aha;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Client-only key bindings for the five Aha actions (Z / X / C / V / B). Rebindable in Controls > "Aha Combat". */
public final class AhaClientKeyHandler {

    private AhaClientKeyHandler() {}

    public static final String CATEGORY = "key.categories.aha";
    public static final KeyMapping BASIC_KEY = new KeyMapping("key.aha.basic", GLFW.GLFW_KEY_Z, CATEGORY);
    public static final KeyMapping SKILL_KEY = new KeyMapping("key.aha.skill", GLFW.GLFW_KEY_X, CATEGORY);
    public static final KeyMapping ULTIMATE_KEY = new KeyMapping("key.aha.ultimate", GLFW.GLFW_KEY_C, CATEGORY);
    public static final KeyMapping ELATION_KEY = new KeyMapping("key.aha.elation", GLFW.GLFW_KEY_V, CATEGORY);
    public static final KeyMapping TECHNIQUE_KEY = new KeyMapping("key.aha.technique", GLFW.GLFW_KEY_B, CATEGORY);
    public static final KeyMapping RADIAL_KEY = new KeyMapping("key.aha.radial_menu", GLFW.GLFW_KEY_R, CATEGORY);

    @Mod.EventBusSubscriber(modid = AhaGeckoRegistration.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModEvents {
        private ModEvents() {}

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(BASIC_KEY);
            event.register(SKILL_KEY);
            event.register(ULTIMATE_KEY);
            event.register(ELATION_KEY);
            event.register(TECHNIQUE_KEY);
            event.register(RADIAL_KEY);
        }
    }

    @Mod.EventBusSubscriber(modid = AhaGeckoRegistration.MODID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private ForgeEvents() {}

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            while (BASIC_KEY.consumeClick())    AhaCinematicCameraEngine.requestSkill(AhaSkill.BASIC_ATTACK);
            while (SKILL_KEY.consumeClick())    AhaCinematicCameraEngine.requestSkill(AhaSkill.SKILL);
            while (ULTIMATE_KEY.consumeClick()) AhaCinematicCameraEngine.requestSkill(AhaSkill.ULTIMATE);
            while (ELATION_KEY.consumeClick())  AhaCinematicCameraEngine.requestSkill(AhaSkill.ELATION_SKILL);
            while (TECHNIQUE_KEY.consumeClick()) AhaCinematicCameraEngine.requestSkill(AhaSkill.TECHNIQUE);
            while (RADIAL_KEY.consumeClick()) {
                if (!AhaCinematicCameraEngine.isActive()) mc.setScreen(new AhaRadialMenuScreen());
            }
        }
    }
}
