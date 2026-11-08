package com.example.aha;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only radial skill picker. Five wedges, clockwise from the top: Basic, Skill, Ultimate, Elation Skill, Technique.
 * Click a wedge, or press 1 / 2 / 3 / 4 / 5, to cast (the Technique needs no target). Esc or the radial key closes it.
 * The screen closes BEFORE the request is sent so the aim ray is taken from the unchanged player view.
 */
public class AhaRadialMenuScreen extends Screen {

    private static final AhaSkill[] SKILLS = {AhaSkill.BASIC_ATTACK, AhaSkill.SKILL, AhaSkill.ULTIMATE,
            AhaSkill.ELATION_SKILL, AhaSkill.TECHNIQUE};
    private static final String[] LABEL_KEYS = {"key.aha.basic", "key.aha.skill", "key.aha.ultimate",
            "key.aha.elation", "key.aha.technique"};

    private static final ResourceLocation[] ICONS = {
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/icon_basic.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/icon_skill.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/icon_ultimate.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/icon_elation.png"),
            new ResourceLocation(AhaGeckoRegistration.MODID, "textures/gui/icon_technique.png")
    };

    private static final float INNER = 38F;
    private static final float OUTER = 112F;
    private static final double HALF_SPAN = 180D / SKILLS.length;   // degrees, 5 wedges * 72
    private static final double GAP = 2D;          // degrees trimmed from each wedge edge

    public AhaRadialMenuScreen() {
        super(Component.translatable("key.aha.radial_menu"));
    }

    /** Centre angle of wedge i in screen space (y grows downward): -90 = top, then clockwise in 72 degree steps. */
    private static double centre(int i) {
        return -90D + 2D * HALF_SPAN * i;
    }

    private static double wrap(double deg) {
        deg %= 360D;
        if (deg > 180D) deg -= 360D;
        if (deg < -180D) deg += 360D;
        return deg;
    }

    private int hovered(int mouseX, int mouseY) {
        double dx = mouseX - this.width / 2.0D;
        double dy = mouseY - this.height / 2.0D;
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < INNER || dist > OUTER) return -1;
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        for (int i = 0; i < SKILLS.length; i++) {
            if (Math.abs(wrap(angle - centre(i))) <= HALF_SPAN) return i;
        }
        return -1;
    }

    private void select(int index) {
        if (index < 0 || index >= SKILLS.length) return;
        AhaSkill skill = SKILLS[index];
        this.onClose();
        AhaCinematicCameraEngine.requestSkill(skill);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int h = hovered((int) mouseX, (int) mouseY);
            if (h >= 0) {
                select(h);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_1) { select(0); return true; }
        if (keyCode == GLFW.GLFW_KEY_2) { select(1); return true; }
        if (keyCode == GLFW.GLFW_KEY_3) { select(2); return true; }
        if (keyCode == GLFW.GLFW_KEY_4) { select(3); return true; }
        if (keyCode == GLFW.GLFW_KEY_5) { select(4); return true; }
        if (AhaClientKeyHandler.RADIAL_KEY.matches(keyCode, scanCode)) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);

        float cx = this.width / 2.0F;
        float cy = this.height / 2.0F;
        int hover = hovered(mouseX, mouseY);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = g.pose().last().pose();
        for (int i = 0; i < SKILLS.length; i++) {
            boolean on = i == hover;
            int color = on ? 0xD07B2CBF : 0xB0141420;
            float outer = on ? OUTER + 6F : OUTER;
            double c = centre(i);
            drawSector(matrix, cx, cy, INNER, outer, c - HALF_SPAN + GAP, c + HALF_SPAN - GAP, color);
        }
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        for (int i = 0; i < SKILLS.length; i++) {
            double rad = Math.toRadians(centre(i));
            int lx = (int) (cx + Math.cos(rad) * (INNER + OUTER) / 2.0D);
            int ly = (int) (cy + Math.sin(rad) * (INNER + OUTER) / 2.0D);
            int color = i == hover ? 0xFFFFFFFF : 0xFFCCCCCC;
            RenderSystem.enableBlend();
            g.blit(ICONS[i], lx - 11, ly - 32, 22, 22, 0F, 0F, 32, 32, 32, 32);
            RenderSystem.disableBlend();
            g.drawCenteredString(this.font, Component.translatable(LABEL_KEYS[i]), lx, ly - 6, color);
            Component sub = SKILLS[i].wishCost() > 0
                    ? Component.translatable("gui.aha.radial.cost", SKILLS[i].wishCost())
                    : SKILLS[i] == AhaSkill.ELATION_SKILL
                    ? Component.translatable("gui.aha.radial.punch", AhaCombatServer.ELATION_MIN_PUNCH)
                    : Component.literal("[" + (i + 1) + "]");
            g.drawCenteredString(this.font, sub, lx, ly + 5, 0xFFFFD750);
        }

        g.drawCenteredString(this.font, Component.literal("Aha"), (int) cx, (int) cy - 4, 0xFFFFD750);
        g.drawCenteredString(this.font, Component.translatable("gui.aha.radial.hint"),
                (int) cx, (int) (cy + OUTER + 18F), 0xFFAAAAAA);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private static void drawSector(Matrix4f m, float cx, float cy, float r0, float r1,
                                   double deg0, double deg1, int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int gr = (argb >>> 8) & 0xFF;
        int bl = argb & 0xFF;
        int steps = 28;
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= steps; i++) {
            double t = Math.toRadians(deg0 + (deg1 - deg0) * i / steps);
            float cos = (float) Math.cos(t);
            float sin = (float) Math.sin(t);
            buf.vertex(m, cx + cos * r1, cy + sin * r1, 0F).color(r, gr, bl, a).endVertex();
            buf.vertex(m, cx + cos * r0, cy + sin * r0, 0F).color(r, gr, bl, a).endVertex();
        }
        Tesselator.getInstance().end();
    }
}
