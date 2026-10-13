package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.network.PortableMonitorIcon;

/** Icon column in the HUD's existing scaled coordinate space. No registry lookup or packet work here. */
public final class PortableMonitorIcons {

    private PortableMonitorIcons() {}

    public static void draw(PortableMonitorIcon icon, int x, int y, int size) {
        if (icon == null || size <= 0) return;
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (icon.item != null) {
                GL11.glTranslatef(x, y, 0);
                GL11.glScalef(size / 16f, size / 16f, 1);
                RenderHelper.enableGUIStandardItemLighting();
                RenderItem.getInstance()
                    .renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), icon.item, 0, 0);
                RenderHelper.disableStandardItemLighting();
            } else if (icon.fluid != null) {
                FluidStack fluid = icon.fluid;
                IIcon texture = fluid.getFluid()
                    .getIcon(fluid);
                if (texture == null && fluid.getFluid()
                    .getBlock() != null)
                    texture = fluid.getFluid()
                        .getBlock()
                        .getIcon(0, 0);
                if (texture == null) return;
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationBlocksTexture);
                int color = fluid.getFluid()
                    .getColor(fluid);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_ALPHA_TEST);
                GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                tess.addVertexWithUV(x, y + size, 0, texture.getMinU(), texture.getMaxV());
                tess.addVertexWithUV(x + size, y + size, 0, texture.getMaxU(), texture.getMaxV());
                tess.addVertexWithUV(x + size, y, 0, texture.getMaxU(), texture.getMinV());
                tess.addVertexWithUV(x, y, 0, texture.getMinU(), texture.getMinV());
                tess.draw();
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
