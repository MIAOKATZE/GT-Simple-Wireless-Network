package com.miaokatze.gtswn.client;

import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

/** Color-only thermal geometry, independent of the preceding world renderer's texture units. */
final class DeviceEnergyRenderState {

    static final int FULL_BRIGHTNESS = 0x00F000F0;

    private DeviceEnergyRenderState() {}

    /** Called inside the renderer's saved attribute scope; ordinary GL calls remain Angelica redirectable. */
    static void begin(boolean depthTest) {
        // Disabling only the active unit can leave the block atlas enabled on unit zero.
        // Angelica's expanded vertex format otherwise inherits its previous UV/lightmap coordinates.
        GL13.glActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL13.glActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(false);
        GL11.glDepthFunc(GL11.GL_LEQUAL);
        if (depthTest) GL11.glEnable(GL11.GL_DEPTH_TEST);
        else GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    static void start(Tessellator tessellator, int mode) {
        tessellator.startDrawing(mode);
        // Explicit per-vertex brightness also avoids Angelica's packedLastBrightness fallback.
        tessellator.setBrightness(FULL_BRIGHTNESS);
    }
}
