package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketUpdateNetworkInfoPanelConfig;

public class GuiNetworkInfoPanel extends GuiScreen {

    private final TileEntityNetworkInfoPanel panel;
    private int left;
    private int top;
    private final int xSize = 300;
    private final int ySize = 210;

    public GuiNetworkInfoPanel(TileEntityNetworkInfoPanel panel) {
        this.panel = panel;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        left = (width - xSize) / 2;
        top = (height - ySize) / 2;
        buttonList.add(new GuiButton(0, left + 12, top + 38, 118, 20, bool("Brief EU", panel.isShowBriefEnergy())));
        buttonList
            .add(new GuiButton(1, left + 138, top + 38, 130, 20, bool("Brief Status", panel.isShowBriefStatus())));
        buttonList.add(new GuiButton(2, left + 12, top + 64, 118, 20, bool("Chart EU", panel.isShowChartEnergy())));
        buttonList.add(new GuiButton(3, left + 138, top + 64, 130, 20, bool("Chart EU/t", panel.isShowChartStatus())));
        buttonList.add(new GuiButton(4, left + 12, top + 90, 118, 20, "Window: " + panel.getWindowName()));
        buttonList.add(new GuiButton(5, left + 138, top + 90, 40, 20, "-"));
        buttonList.add(new GuiButton(6, left + 182, top + 90, 40, 20, "+"));
        buttonList
            .add(new GuiButton(7, left + 226, top + 90, 42, 20, panel.getChartLayoutMode() == 0 ? "Split" : "Overlay"));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        GTSWNPacketHandler.NETWORK
            .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, button.id));
    }

    @Override
    public void updateScreen() {
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            switch (button.id) {
                case 0:
                    button.displayString = bool("Brief EU", panel.isShowBriefEnergy());
                    break;
                case 1:
                    button.displayString = bool("Brief Status", panel.isShowBriefStatus());
                    break;
                case 2:
                    button.displayString = bool("Chart EU", panel.isShowChartEnergy());
                    break;
                case 3:
                    button.displayString = bool("Chart EU/t", panel.isShowChartStatus());
                    break;
                case 4:
                    button.displayString = "Window: " + panel.getWindowName();
                    break;
                case 7:
                    button.displayString = panel.getChartLayoutMode() == 0 ? "Split" : "Overlay";
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawPanelBackground();
        fontRendererObj.drawString(EnumChatFormatting.BOLD + "Network Info Panel", left + 12, top + 12, 0x28313A);
        fontRendererObj.drawString("Wireless EU Network", left + 12, top + 24, 0x286080);
        fontRendererObj.drawString("Owner: " + safe(panel.getOwnerName()), left + 12, top + 120, 0x2F3640);
        fontRendererObj.drawString(
            "Energy: " + FormatUtil.formatNormal(panel.getCachedEu()) + " EU",
            left + 12,
            top + 134,
            0x2F3640);
        fontRendererObj.drawString("Status: " + panel.getCachedStatus(), left + 12, top + 148, 0x2F3640);
        fontRendererObj.drawString("Brief ratio: " + panel.getBriefRatio() + "%", left + 12, top + 104, 0x2F3640);
        drawMiniChart(left + 12, top + 164, 256, 34);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawPanelBackground() {
        drawRect(left, top, left + xSize, top + ySize, 0xFFE8EAEC);
        drawRect(left, top, left + xSize, top + 1, 0xFF607080);
        drawRect(left, top + ySize - 1, left + xSize, top + ySize, 0xFF607080);
        drawRect(left, top, left + 1, top + ySize, 0xFF607080);
        drawRect(left + xSize - 1, top, left + xSize, top + ySize, 0xFF607080);
        drawRect(left + 8, top + 32, left + xSize - 8, top + 33, 0xFFB8C0C8);
        drawRect(left + 8, top + 116, left + xSize - 8, top + 117, 0xFFB8C0C8);
    }

    private void drawMiniChart(int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + h, 0xFFF7F8F9);
        drawRect(x, y + h - 1, x + w, y + h, 0xFF8C98A4);
        drawRect(x, y, x + 1, y + h, 0xFF8C98A4);
        List<NetworkInfoSample> samples = panel.getCachedSamples();
        if (samples.size() < 2) {
            fontRendererObj.drawString("Waiting for samples...", x + 8, y + 12, 0x777777);
            return;
        }
        drawSeries(samples, x + 4, y + 4, w - 8, h - 8, panel.isShowChartEnergy(), panel.isShowChartStatus());
    }

    private void drawSeries(List<NetworkInfoSample> samples, int x, int y, int w, int h, boolean eu, boolean eut) {
        if (eu) {
            double[] values = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                values[i] = samples.get(i).eu.doubleValue();
            }
            drawLineSeries(values, x, y, w, h, 0xFF2F80ED);
        }
        if (eut) {
            double[] values = new double[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                values[i] = samples.get(i).eut;
            }
            drawLineSeries(values, x, y, w, h, 0xFFE07A2F);
        }
    }

    private void drawLineSeries(double[] values, int x, int y, int w, int h, int color) {
        double min = values[0];
        double max = values[0];
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (Math.abs(max - min) < 0.000001D) {
            max = min + 1.0D;
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(1.5F);
        GL11.glColor4ub((byte) (color >> 16), (byte) (color >> 8), (byte) color, (byte) 255);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i < values.length; i++) {
            double px = x + (values.length == 1 ? 0.0D : (double) i / (values.length - 1) * w);
            double py = y + h - (values[i] - min) / (max - min) * h;
            GL11.glVertex2d(px, py);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static String bool(String label, boolean value) {
        return label + ": " + (value ? "ON" : "OFF");
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "Unknown" : value;
    }
}
