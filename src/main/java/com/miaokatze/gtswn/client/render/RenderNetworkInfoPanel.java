package com.miaokatze.gtswn.client.render;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;

public class RenderNetworkInfoPanel extends TileEntitySpecialRenderer {

    @Override
    public void renderTileEntityAt(TileEntity tile, double x, double y, double z, float partialTicks) {
        if (!(tile instanceof TileEntityNetworkInfoPanel)) {
            return;
        }
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) tile;
        NetworkScreen screen = panel.getScreen();
        int widthBlocks = screen == null ? 1 : Math.max(1, screen.getWidth());
        int heightBlocks = screen == null ? 1 : Math.max(1, screen.getHeight());

        GL11.glPushMatrix();
        setupFaceTransform(panel.getBlockMetadata(), x, y, z);
        float scale = 1.0F / 128.0F;
        GL11.glScalef(scale, -scale, scale);
        GL11.glTranslatef(-64.0F, -64.0F, 0.0F);

        int pixelWidth = widthBlocks * 128;
        int pixelHeight = heightBlocks * 128;
        drawPanel(panel, pixelWidth, pixelHeight);

        GL11.glPopMatrix();
    }

    private void setupFaceTransform(int facing, double x, double y, double z) {
        switch (facing) {
            case 2:
                GL11.glTranslated(x + 0.5D, y + 0.5D, z - 0.002D);
                GL11.glRotatef(180F, 0F, 1F, 0F);
                break;
            case 4:
                GL11.glTranslated(x - 0.002D, y + 0.5D, z + 0.5D);
                GL11.glRotatef(-90F, 0F, 1F, 0F);
                break;
            case 5:
                GL11.glTranslated(x + 1.002D, y + 0.5D, z + 0.5D);
                GL11.glRotatef(90F, 0F, 1F, 0F);
                break;
            case 3:
            default:
                GL11.glTranslated(x + 0.5D, y + 0.5D, z + 1.002D);
                break;
        }
    }

    private void drawPanel(TileEntityNetworkInfoPanel panel, int width, int height) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        int padding = 8;
        int briefHeight = Math.max(30, height * panel.getBriefRatio() / 100);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        font.drawString(tr("gtswn.network_info.screen.title"), padding, padding, 0x26323D);
        int lineY = padding + 12;
        if (panel.isShowBriefEnergy()) {
            font.drawString(
                StatCollector.translateToLocalFormatted(
                    "gtswn.network_info.screen.energy",
                    FormatUtil.formatBigInteger(panel.getCachedEu())),
                padding,
                lineY,
                0x2F80ED);
            lineY += 10;
        }
        if (panel.isShowBriefStatus()) {
            font.drawString(
                StatCollector.translateToLocalFormatted("gtswn.network_info.screen.status", panel.getCachedStatus()),
                padding,
                lineY,
                0xE07A2F);
        }
        drawChart(panel, padding, briefHeight, width - padding * 2, height - briefHeight - padding);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawChart(TileEntityNetworkInfoPanel panel, int x, int y, int w, int h) {
        if (w <= 20 || h <= 20 || (!panel.isShowChartEnergy() && !panel.isShowChartStatus())) {
            return;
        }
        drawLine(x, y + h, x + w, y + h, 0x667684);
        drawLine(x, y, x, y + h, 0x667684);
        Minecraft.getMinecraft().fontRenderer.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.screen.history", panel.getWindowName()),
            x + 3,
            y + 3,
            0x34404A);
        List<NetworkInfoSample> samples = panel.getCachedSamples();
        if (samples.size() < 2) {
            Minecraft.getMinecraft().fontRenderer.drawString(
                StatCollector.translateToLocalFormatted("gtswn.network_info.screen.collecting", samples.size()),
                x + 8,
                y + h / 2,
                0x777777);
            return;
        }
        drawChartLabels(panel, samples, x, y, w, h);
        if (panel.getChartLayoutMode() == 0 && panel.isShowChartEnergy() && panel.isShowChartStatus()) {
            drawEnergySeries(samples, x + 18, y + 18, w - 28, h / 2 - 22, 0x2F80ED);
            drawEutSeries(samples, x + 18, y + h / 2 + 10, w - 28, h / 2 - 18, 0xE07A2F);
        } else {
            if (panel.isShowChartEnergy()) {
                drawEnergySeries(samples, x + 18, y + 18, w - 28, h - 28, 0x2F80ED);
            }
            if (panel.isShowChartStatus()) {
                drawEutSeries(samples, x + 18, y + 18, w - 28, h - 28, 0xE07A2F);
            }
        }
    }

    private void drawChartLabels(TileEntityNetworkInfoPanel panel, List<NetworkInfoSample> samples, int x, int y, int w,
        int h) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        if (panel.isShowChartEnergy()) {
            double[] range = rangeEnergy(samples);
            font.drawString(sci(range[1]), x + 2, y + 14, 0x2F80ED);
            font.drawString("EU", x + 2, y + h - 8, 0x2F80ED);
        }
        if (panel.isShowChartStatus()) {
            double[] range = rangeEut(samples);
            font.drawString(sci(range[1]), x + w - 34, y + 14, 0xE07A2F);
            font.drawString("EU/t", x + w - 26, y + h - 8, 0xE07A2F);
        }
        font.drawString("0", x + 18, y + h - 8, 0x606A74);
        font.drawString(panel.getWindowName(), x + w - 28, y + h - 8, 0x606A74);
    }

    private void drawEnergySeries(List<NetworkInfoSample> samples, int x, int y, int w, int h, int color) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = samples.get(i).eu.doubleValue();
        }
        drawSeries(values, x, y, w, h, color);
    }

    private void drawEutSeries(List<NetworkInfoSample> samples, int x, int y, int w, int h, int color) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = samples.get(i).eut;
        }
        drawSeries(values, x, y, w, h, color);
    }

    private void drawSeries(double[] values, int x, int y, int w, int h, int color) {
        if (h <= 2) {
            return;
        }
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
        GL11.glLineWidth(2.0F);
        setColor(color);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i < values.length; i++) {
            double px = x + (values.length == 1 ? 0.0D : (double) i / (values.length - 1) * w);
            double py = y + h - (values[i] - min) / (max - min) * h;
            GL11.glVertex3d(px, py, 0.0D);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawLine(int x1, int y1, int x2, int y2, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(1.0F);
        setColor(color);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(x1, y1, 0.0D);
        GL11.glVertex3d(x2, y2, 0.0D);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void setColor(int color) {
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        GL11.glColor4f(r, g, b, 1.0F);
    }

    private static double[] rangeEnergy(List<NetworkInfoSample> samples) {
        double min = samples.get(0).eu.doubleValue();
        double max = min;
        for (NetworkInfoSample sample : samples) {
            double value = sample.eu.doubleValue();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new double[] { min, max };
    }

    private static double[] rangeEut(List<NetworkInfoSample> samples) {
        double min = samples.get(0).eut;
        double max = min;
        for (NetworkInfoSample sample : samples) {
            min = Math.min(min, sample.eut);
            max = Math.max(max, sample.eut);
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
        return (value < 0.0D ? "-" : "") + String.format(Locale.ROOT, "%.1fE%d", mantissa, exp);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
