package com.miaokatze.gtswn.client.gui;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

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
    private final int xSize = 390;
    private final int ySize = 273;

    public GuiNetworkInfoPanel(TileEntityNetworkInfoPanel panel) {
        this.panel = panel;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        left = (width - xSize) / 2;
        top = (height - ySize) / 2;
        int y1 = top + 38;
        int y2 = top + 58;
        buttonList.add(
            new GuiButton(
                0,
                left + 12,
                y1,
                84,
                16,
                bool(tr("gtswn.network_info.gui.brief.eu"), panel.isShowBriefEnergy())));
        buttonList.add(
            new GuiButton(
                1,
                left + 100,
                y1,
                84,
                16,
                bool(tr("gtswn.network_info.gui.brief.status"), panel.isShowBriefStatus())));
        buttonList.add(
            new GuiButton(
                2,
                left + 188,
                y1,
                84,
                16,
                bool(tr("gtswn.network_info.gui.chart.eu"), panel.isShowChartEnergy())));
        buttonList.add(
            new GuiButton(
                3,
                left + 276,
                y1,
                96,
                16,
                bool(tr("gtswn.network_info.gui.chart.eut"), panel.isShowChartStatus())));
        buttonList.add(
            new GuiButton(
                4,
                left + 12,
                y2,
                104,
                16,
                tr("gtswn.network_info.gui.window") + ": " + panel.getWindowName()));
        buttonList.add(new GuiButton(5, left + 122, y2, 24, 16, "-"));
        buttonList.add(new GuiButton(6, left + 150, y2, 24, 16, "+"));
        buttonList.add(
            new GuiButton(
                7,
                left + 180,
                y2,
                74,
                16,
                panel.getChartLayoutMode() == 0 ? tr("gtswn.network_info.gui.split")
                    : tr("gtswn.network_info.gui.overlay")));
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
                    button.displayString = bool(tr("gtswn.network_info.gui.brief.eu"), panel.isShowBriefEnergy());
                    break;
                case 1:
                    button.displayString = bool(tr("gtswn.network_info.gui.brief.status"), panel.isShowBriefStatus());
                    break;
                case 2:
                    button.displayString = bool(tr("gtswn.network_info.gui.chart.eu"), panel.isShowChartEnergy());
                    break;
                case 3:
                    button.displayString = bool(tr("gtswn.network_info.gui.chart.eut"), panel.isShowChartStatus());
                    break;
                case 4:
                    button.displayString = tr("gtswn.network_info.gui.window") + ": " + panel.getWindowName();
                    break;
                case 7:
                    button.displayString = panel.getChartLayoutMode() == 0 ? tr("gtswn.network_info.gui.split")
                        : tr("gtswn.network_info.gui.overlay");
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
        fontRendererObj
            .drawString(EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.title"), left + 12, top + 12, 0x28313A);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.tab.wireless_eu"), left + 12, top + 24, 0x286080);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.owner", safe(panel.getOwnerName())),
            left + 12,
            top + 88,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector
                .translateToLocalFormatted("gtswn.network_info.energy", FormatUtil.formatNormal(panel.getCachedEu())),
            left + 12,
            top + 102,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.status", panel.getCachedStatus()),
            left + 12,
            top + 116,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.brief_ratio", panel.getBriefRatio()),
            left + 262,
            top + 62,
            0x2F3640);
        drawMiniChart(left + 12, top + 136, xSize - 24, 118);
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
        drawRect(left + 8, top + 82, left + xSize - 8, top + 83, 0xFFB8C0C8);
        drawRect(left + 8, top + 130, left + xSize - 8, top + 131, 0xFFB8C0C8);
    }

    private void drawMiniChart(int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + h, 0xFFF7F8F9);
        drawRect(x, y + h - 1, x + w, y + h, 0xFF8C98A4);
        drawRect(x, y, x + 1, y + h, 0xFF8C98A4);
        List<NetworkInfoSample> samples = panel.getCachedSamples();
        if (samples.size() < 2) {
            fontRendererObj.drawString(
                StatCollector.translateToLocalFormatted("gtswn.network_info.gui.waiting", samples.size()),
                x + 8,
                y + h / 2 - 4,
                0x777777);
            return;
        }
        int plotX = x + 38;
        int plotY = y + 12;
        int plotW = w - 54;
        int plotH = h - 30;
        drawChartAxes(samples, x, y, w, h, plotX, plotY, plotW, plotH);
        drawSeries(samples, plotX, plotY, plotW, plotH, panel.isShowChartEnergy(), panel.isShowChartStatus());
    }

    private void drawChartAxes(List<NetworkInfoSample> samples, int x, int y, int w, int h, int plotX, int plotY,
        int plotW, int plotH) {
        drawRect(plotX, plotY + plotH, plotX + plotW, plotY + plotH + 1, 0xFF8C98A4);
        drawRect(plotX, plotY, plotX + 1, plotY + plotH, 0xFF8C98A4);
        drawRect(plotX + plotW, plotY, plotX + plotW + 1, plotY + plotH, 0xFFCFD5DC);

        if (panel.isShowChartEnergy()) {
            double[] range = rangeEnergy(samples);
            fontRendererObj.drawString(sci(range[1]), x + 5, plotY, 0x2F80ED);
            fontRendererObj.drawString(sci(range[0]), x + 5, plotY + plotH - 8, 0x2F80ED);
            fontRendererObj.drawString("EU", x + 5, y + h - 12, 0x2F80ED);
        }
        if (panel.isShowChartStatus()) {
            double[] range = rangeEut(samples);
            int right = x + w - 32;
            fontRendererObj.drawString(sci(range[1]), right, plotY, 0xE07A2F);
            fontRendererObj.drawString(sci(range[0]), right, plotY + plotH - 8, 0xE07A2F);
            fontRendererObj.drawString("EU/t", right - 4, y + h - 12, 0xE07A2F);
        }
        fontRendererObj.drawString("-" + panel.getWindowName(), plotX, y + h - 12, 0x606A74);
        fontRendererObj.drawString("Now", plotX + plotW - 18, y + h - 12, 0x606A74);
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

    private static double[] rangeEnergy(List<NetworkInfoSample> samples) {
        double min = samples.get(0).eu.doubleValue();
        double max = min;
        for (NetworkInfoSample sample : samples) {
            double value = sample.eu.doubleValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return normalizeRange(min, max);
    }

    private static double[] rangeEut(List<NetworkInfoSample> samples) {
        double min = samples.get(0).eut;
        double max = min;
        for (NetworkInfoSample sample : samples) {
            min = Math.min(min, sample.eut);
            max = Math.max(max, sample.eut);
        }
        return normalizeRange(min, max);
    }

    private static double[] normalizeRange(double min, double max) {
        if (Math.abs(max - min) < 0.000001D) {
            max = min + 1.0D;
        }
        return new double[] { min, max };
    }

    private static String sci(double value) {
        if (Math.abs(value) < 0.000001D) {
            return "0";
        }
        double abs = Math.abs(value);
        int exp = (int) Math.floor(Math.log10(abs));
        double mantissa = abs / Math.pow(10.0D, exp);
        String sign = value < 0.0D ? "-" : "";
        return sign + String.format(Locale.ROOT, "%.1fE%d", mantissa, exp);
    }

    private static String bool(String label, boolean value) {
        return label + " " + (value ? tr("gtswn.network_info.gui.on") : tr("gtswn.network_info.gui.off"));
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? tr("gtswn.network_info.unknown") : value;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
