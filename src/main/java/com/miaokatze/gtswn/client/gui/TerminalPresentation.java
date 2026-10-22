package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;

/** The C wheel and factory dock share the same real terminal identities and empty-item artwork. */
public final class TerminalPresentation {

    private TerminalPresentation() {}

    private static String tr(String key) {
        return TerminalBaubleController.text("gtswn.engineering." + key);
    }

    public static ItemStack placeholder(int slot) {
        if (slot == 0) return GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        if (slot == 1) return GTSWNItemList.Wireless_Energy_Tap.get(1);
        if (slot == 2) return GTSWNItemList.Device_Info_Terminal.get(1);
        return GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
    }

    public static String identity(ItemStack stack, int slot) {
        if (stack == null) return TerminalBaubleController.text("gtswn.terminal_wheel.empty");
        if (slot >= 3) {
            String color = TerminalBaubleController
                .text("gtswn.color." + QuantumNetworkColor.name(QuantumNetworkColor.get(stack)));
            int[] a = ItemNetworkQuantumTerminal.getAnchor(stack);
            return color + " · "
                + (a == null ? TerminalBaubleController.text("gtswn.tooltip.quantum_terminal.unbound")
                    : "DIM " + a[0] + " · " + a[1] + "/" + a[2] + "/" + a[3]);
        }
        if (slot == 1) return tr(WirelessEnergyTap.getOutputModeStatic(stack) ? "tools.dynamo" : "tools.energy");
        if (slot == 2) {
            EngineeringTerminalHudSnapshot.Source source = EngineeringTerminalHudSnapshot
                .capture(net.minecraft.client.Minecraft.getMinecraft().thePlayer).device;
            return source.fresh
                ? TerminalBaubleController.text("gtswn.engineering.tools.device_count", source.devices.size())
                : tr("devices") + " · " + tr("waiting");
        }
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack)) return tr("tools.unbound");
        String owner = stack.stackTagCompound == null ? "" : stack.stackTagCompound.getString("OwnerName");
        if (owner.isEmpty()) owner = tr("dash.unknown");
        return owner + " · "
            + MonitorBindings.read(stack)
                .size()
            + " "
            + tr("tools.screens");
    }

    public static void drawIcon(Minecraft mc, FontRenderer fontRendererObj, ItemStack stack, int x, int y,
        boolean present, float size) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(size / 16f, size / 16f, 1);
            if (present) {
                RenderHelper.enableGUIStandardItemLighting();
                RenderItem.getInstance()
                    .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, 0, 0);
                RenderHelper.disableStandardItemLighting();
            } else {
                // RenderItem resets alpha internally. Empty previews use the item's actual icon with explicit alpha.
                IIcon icon = stack.getItem()
                    .getIcon(stack, 0);
                if (icon == null) return;
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationItemsTexture);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(0.7f, 0.75f, 0.8f, 0.24f);
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                tess.addVertexWithUV(0, 16, 0, icon.getMinU(), icon.getMaxV());
                tess.addVertexWithUV(16, 16, 0, icon.getMaxU(), icon.getMaxV());
                tess.addVertexWithUV(16, 0, 0, icon.getMaxU(), icon.getMinV());
                tess.addVertexWithUV(0, 0, 0, icon.getMinU(), icon.getMinV());
                tess.draw();
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

}
