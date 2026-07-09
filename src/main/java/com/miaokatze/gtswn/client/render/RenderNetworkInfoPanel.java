package com.miaokatze.gtswn.client.render;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;

public class RenderNetworkInfoPanel extends TileEntitySpecialRenderer {

    private static final int ENERGY_COLOR = 0x1F6FFF;
    private static final int EUT_COLOR = 0xFF7A18;
    private static final int AXIS_COLOR = 0x4E5964;
    private static final int TICK_COLOR = 0x6A7680;

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
        GL11.glTranslatef(
            -64.0F - getCoreHorizontalOffset(panel, screen) * 128.0F,
            -64.0F - getCoreVerticalOffset(panel, screen) * 128.0F,
            0.0F);

        int pixelWidth = widthBlocks * 128;
        int pixelHeight = heightBlocks * 128;
        drawPanel(panel, pixelWidth, pixelHeight);

        GL11.glPopMatrix();
    }

    private int getCoreHorizontalOffset(TileEntityNetworkInfoPanel panel, NetworkScreen screen) {
        if (screen == null) {
            return 0;
        }
        int facing = screen.facing;
        if (facing == 2 || facing == 3) {
            return panel.xCoord - screen.minX;
        }
        return panel.zCoord - screen.minZ;
    }

    private int getCoreVerticalOffset(TileEntityNetworkInfoPanel panel, NetworkScreen screen) {
        if (screen == null) {
            return 0;
        }
        return screen.maxY - panel.yCoord;
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
        int edge = Math.max(8, Math.min(16, Math.min(width, height) / 18));
        int safe = edge + 10;
        int briefHeight = Math.max(34, height * 15 / 100);
        briefHeight = Math.min(briefHeight, height - safe * 2 - 50);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean fogEnabled = GL11.glIsEnabled(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_CULL_FACE);

        // v1.4.5：将光照贴图设为全亮(240,240)，避免环境光照导致 TESR 渲染颜色偏淡
        // 保存当前 lightmap 坐标以便渲染结束后恢复
        float prevLightmapX = OpenGlHelper.lastBrightnessX;
        float prevLightmapY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        if (panel.hasScreenBackgroundColor()) {
            fillRect(0, 0, width, height, panel.getScreenBackgroundColor());
        }
        // v1.4.5：移除额外外边框渲染，由方块本身承担边界
        drawBrief(panel, font, safe, width - safe * 2, safe, briefHeight);

        int chartTop = safe + briefHeight + 12;
        int chartBottom = height - safe;
        if (chartBottom - chartTop > 42) {
            drawChart(panel, safe, chartTop, width - safe * 2, chartBottom - chartTop);
        }

        if (depthEnabled) {
            GL11.glEnable(GL11.GL_DEPTH_TEST);
        } else {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        if (fogEnabled) {
            GL11.glEnable(GL11.GL_FOG);
        } else {
            GL11.glDisable(GL11.GL_FOG);
        }
        // 恢复光照贴图坐标，避免影响后续方块/实体渲染
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, prevLightmapX, prevLightmapY);
        GL11.glEnable(GL11.GL_LIGHTING);
        if (cullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawBrief(TileEntityNetworkInfoPanel panel, FontRenderer font, int x, int w, int y, int h) {
        float scale = panel.getBriefFontScale();
        int lineCount = 1 + (panel.isShowBriefEnergy() ? 1 : 0) + (panel.isShowBriefStatus() ? 1 : 0);
        int lineStep = Math.max(10, Math.round(10.0F * scale));
        int lineY = y + Math.max(0, (h - lineCount * lineStep) / 2);
        drawScaledCentered(font, tr("gtswn.network_info.screen.title"), x, w, lineY, 0x26323D, scale);
        lineY += lineStep;
        if (panel.isShowBriefEnergy()) {
            drawScaledCentered(
                font,
                StatCollector.translateToLocalFormatted(
                    "gtswn.network_info.screen.energy",
                    FormatUtil.formatBigInteger(panel.getCachedEu())),
                x,
                w,
                lineY,
                ENERGY_COLOR,
                scale);
            lineY += lineStep;
        }
        if (panel.isShowBriefStatus()) {
            drawScaledCentered(
                font,
                StatCollector.translateToLocalFormatted("gtswn.network_info.screen.status", panel.getCachedStatus()),
                x,
                w,
                lineY,
                EUT_COLOR,
                scale);
        }
    }

    private void drawChart(TileEntityNetworkInfoPanel panel, int x, int y, int w, int h) {
        if (w <= 70 || h <= 55 || (!panel.isShowChartEnergy() && !panel.isShowChartStatus())) {
            return;
        }
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String title = StatCollector
            .translateToLocalFormatted("gtswn.network_info.screen.history", panel.getWindowName());
        drawCentered(font, title, x, w, y, 0x34404A);

        int plotX = x + 54;
        int plotY = y + 22;
        int plotW = w - 108;
        int plotH = h - 52;
        if (plotW <= 20 || plotH <= 16) {
            return;
        }
        Integer chartBg = panel.getChartBackgroundColor();
        if (chartBg != null) {
            fillRect(plotX, plotY, plotW, plotH, chartBg.intValue());
        }

        List<NetworkInfoSample> samples = panel.getCachedSamples();
        if (samples.size() < 2) {
            drawAcademicAxes(panel, font, plotX, plotY, plotW, plotH, null, null);
            drawCentered(
                font,
                StatCollector.translateToLocalFormatted("gtswn.network_info.screen.collecting", samples.size()),
                plotX,
                plotW,
                plotY + plotH / 2 - 4,
                0x777777);
            return;
        }

        double[] energyValues = panel.isShowChartEnergy() ? energyValues(samples) : null;
        double[] eutValues = panel.isShowChartStatus() ? eutValues(samples) : null;
        double[] energyRange = energyValues == null ? null
            : range(energyValues, panel.getEnergyAxisMin(), panel.getEnergyAxisMax());
        double[] eutRange = eutValues == null ? null : range(eutValues, panel.getEutAxisMin(), panel.getEutAxisMax());
        drawAcademicAxes(panel, font, plotX, plotY, plotW, plotH, energyRange, eutRange);
        if (panel.getChartLayoutMode() == 0 && panel.isShowChartEnergy() && panel.isShowChartStatus()) {
            int upperH = Math.max(8, plotH / 2 - 7);
            int lowerY = plotY + plotH / 2 + 7;
            int lowerH = Math.max(8, plotH - (lowerY - plotY));
            drawSeries(
                energyValues,
                energyRange,
                plotX,
                plotY,
                plotW,
                upperH,
                ENERGY_COLOR,
                panel.getTrendLineThickness(),
                panel.getTrendLineSmoothing());
            drawSeries(
                eutValues,
                eutRange,
                plotX,
                lowerY,
                plotW,
                lowerH,
                EUT_COLOR,
                panel.getTrendLineThickness(),
                panel.getTrendLineSmoothing());
        } else {
            if (energyValues != null) {
                drawSeries(
                    energyValues,
                    energyRange,
                    plotX,
                    plotY,
                    plotW,
                    plotH,
                    ENERGY_COLOR,
                    panel.getTrendLineThickness(),
                    panel.getTrendLineSmoothing());
            }
            if (eutValues != null) {
                drawSeries(
                    eutValues,
                    eutRange,
                    plotX,
                    plotY,
                    plotW,
                    plotH,
                    EUT_COLOR,
                    panel.getTrendLineThickness(),
                    panel.getTrendLineSmoothing());
            }
        }
    }

    private void drawAcademicAxes(TileEntityNetworkInfoPanel panel, FontRenderer font, int x, int y, int w, int h,
        double[] energyRange, double[] eutRange) {
        int thickness = panel.getChartBorderThickness();
        fillRect(x, y, w + thickness, thickness, AXIS_COLOR);
        fillRect(x, y + h, w + thickness, thickness, AXIS_COLOR);
        fillRect(x, y, thickness, h + thickness, AXIS_COLOR);
        fillRect(x + w, y, thickness, h + thickness, AXIS_COLOR);

        for (int i = 0; i < 5; i++) {
            int ty = y + h - i * h / 4;
            fillRect(x - 4, ty, 4, Math.max(1, thickness), TICK_COLOR);
            fillRect(x + w, ty, 4, Math.max(1, thickness), TICK_COLOR);
            if (energyRange != null) {
                double value = energyRange[0] + (energyRange[1] - energyRange[0]) * i / 4.0D;
                String label = sci(value);
                font.drawString(label, x - 8 - font.getStringWidth(label), ty - 4, ENERGY_COLOR);
            }
            if (eutRange != null) {
                double value = eutRange[0] + (eutRange[1] - eutRange[0]) * i / 4.0D;
                font.drawString(sci(value), x + w + 8, ty - 4, EUT_COLOR);
            }
        }
        for (int i = 0; i < 5; i++) {
            int tx = x + i * w / 4;
            fillRect(tx, y + h, Math.max(1, thickness), 4, TICK_COLOR);
            String label = i == 0 ? "-" + panel.getWindowName() : i == 4 ? tr("gtswn.network_info.screen.now") : "";
            if (!label.isEmpty()) {
                font.drawString(label, tx - font.getStringWidth(label) / 2, y + h + 8, 0x4E5964);
            }
        }
        drawCentered(font, tr("gtswn.network_info.screen.time_axis"), x, w, y + h + 20, 0x4E5964);
        if (energyRange != null) {
            drawRotated(font, tr("gtswn.network_info.screen.energy_axis"), x - 56, y + h / 2 + 42, ENERGY_COLOR);
        }
        if (eutRange != null) {
            drawRotated(font, tr("gtswn.network_info.screen.eut_axis"), x + w + 48, y + h / 2 + 38, EUT_COLOR);
        }
    }

    private void drawSeries(double[] values, double[] range, int x, int y, int w, int h, int color, int thickness,
        int smoothing) {
        if (values == null || values.length < 2 || h <= 2 || range == null) {
            return;
        }
        double min = range[0];
        double max = range[1];
        // smoothing 配置映射为样条分段数：0=线性(1段)，1..12 → 4..26 段
        int segments = smoothing <= 0 ? 1 : smoothing * 2 + 2;
        double[][] path = catmullRomPath(values, segments);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(thickness);
        setColor(color);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        double denom = values.length - 1;
        for (int i = 0; i < path.length; i++) {
            double normalized = (path[i][1] - min) / (max - min);
            // clamp 到 [0,1] 防止样条过冲溢出图表区
            normalized = Math.max(0.0D, Math.min(1.0D, normalized));
            double px = x + path[i][0] / denom * w;
            double py = y + h - normalized * h;
            GL11.glVertex3d(px, py, 0.0D);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private static double[] energyValues(List<NetworkInfoSample> samples) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = samples.get(i).eu.doubleValue();
        }
        return values;
    }

    private static double[] eutValues(List<NetworkInfoSample> samples) {
        double[] values = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            values[i] = samples.get(i).eut;
        }
        return values;
    }

    private static double[] range(double[] values, Double customMin, Double customMax) {
        double min = values[0];
        double max = values[0];
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (customMin != null) {
            min = customMin.doubleValue();
        }
        if (customMax != null) {
            max = customMax.doubleValue();
        }
        if (Math.abs(max - min) < 0.000001D || max < min) {
            double center = (max + min) / 2.0D;
            min = center - 0.5D;
            max = center + 0.5D;
        }
        return new double[] { min, max };
    }

    /**
     * 用 Catmull-Rom 样条曲线在样品点之间生成密集顶点路径。
     * 样条经过每个样品点（p1、p2 为段端点）；两端用线性外推虚拟点处理边界。
     * X 等间距索引映射：path[i][0] = 索引坐标（浮点），path[i][1] = 值。
     *
     * @param values          样品值数组（已按时间索引化，X 等间距）
     * @param segmentsPerSpan 每相邻两点间的插值段数（≥1；1 = 线性）
     * @return 密集顶点路径，长度 = (values.length - 1) * segmentsPerSpan + 1
     */
    private static double[][] catmullRomPath(double[] values, int segmentsPerSpan) {
        int n = values.length;
        if (n < 2 || segmentsPerSpan < 1) {
            // 退化情况：直接返回原始点
            double[][] path = new double[n][2];
            for (int i = 0; i < n; i++) {
                path[i][0] = i;
                path[i][1] = values[i];
            }
            return path;
        }
        int total = (n - 1) * segmentsPerSpan + 1;
        double[][] path = new double[total][2];
        path[0][0] = 0D;
        path[0][1] = values[0];

        int idx = 1;
        for (int i = 0; i < n - 1; i++) {
            // 4 控制点：p1=段起点, p2=段终点, p0/p3=相邻点（首尾线性外推）
            double p0 = (i == 0) ? 2 * values[0] - values[1] : values[i - 1];
            double p1 = values[i];
            double p2 = values[i + 1];
            double p3 = (i + 2 >= n) ? 2 * values[n - 1] - values[n - 2] : values[i + 2];

            for (int s = 1; s <= segmentsPerSpan; s++) {
                double t = (double) s / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;
                // 标准 Catmull-Rom 基矩阵（张力 0.5）
                double y = 0.5D * (2 * p1 + (-p0 + p2) * t
                    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                    + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
                path[idx][0] = i + t;
                path[idx][1] = y;
                idx++;
            }
        }
        return path;
    }

    private void fillRect(int x, int y, int w, int h, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        setColor(color);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex3d(x, y, 0.0D);
        GL11.glVertex3d(x + w, y, 0.0D);
        GL11.glVertex3d(x + w, y + h, 0.0D);
        GL11.glVertex3d(x, y + h, 0.0D);
        GL11.glEnd();
        if (blendEnabled) {
            GL11.glEnable(GL11.GL_BLEND);
        } else {
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        if (cullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    private void drawCentered(FontRenderer font, String text, int x, int w, int y, int color) {
        font.drawString(text, x + (w - font.getStringWidth(text)) / 2, y, color);
    }

    private void drawScaledCentered(FontRenderer font, String text, int x, int w, int y, int color, float scale) {
        GL11.glPushMatrix();
        float centerX = x + w / 2.0F;
        GL11.glTranslatef(centerX, y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        font.drawString(text, -font.getStringWidth(text) / 2, 0, color);
        GL11.glPopMatrix();
    }

    private void drawRotated(FontRenderer font, String text, int x, int y, int color) {
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0.0F);
        GL11.glRotatef(-90.0F, 0.0F, 0.0F, 1.0F);
        font.drawString(text, 0, 0, color);
        GL11.glPopMatrix();
    }

    private void setColor(int color) {
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        GL11.glColor4f(r, g, b, 1.0F);
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
