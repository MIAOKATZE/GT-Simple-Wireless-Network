package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.gui.FontRenderer;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;
import com.miaokatze.gtswn.common.panel.TrendLinePalette;

/** Simple HUD chart deliberately differs from the full information-screen canvas. */
public final class PortableMonitorCharts {

    private PortableMonitorCharts() {}

    public static void drawCompact(PinnedLine line, int x, int y, int width, int height, FontRenderer font) {
        if (!line.text.isEmpty()) font.drawStringWithShadow(font.trimStringToWidth(line.text, width), x, y, 0xffffff);
        if (!line.currentText.isEmpty())
            font.drawStringWithShadow(font.trimStringToWidth(line.currentText, width), x, y + 10, 0xffffff);
        int color = line.change > 0 ? 0x60dc83 : line.change < 0 ? 0xf16f68 : 0x57d8ca;
        int plotTop = line.text.isEmpty() ? line.currentText.isEmpty() ? 4 : 14 : line.currentText.isEmpty() ? 14 : 24;
        drawChart(
            line.chart,
            x + 2,
            y + plotTop,
            Math.max(1, width - 4),
            Math.max(1, height - plotTop - 6),
            line.euGraph ? line.palette : null,
            false,
            color);
        drawChart(
            line.rateChart,
            x + 2,
            y + plotTop,
            Math.max(1, width - 4),
            Math.max(1, height - plotTop - 6),
            line.euGraph ? line.palette : null,
            true,
            0xffa551);
    }

    private static void drawChart(double[] values, int x, int y, int width, int height, TrendLinePalette palette,
        boolean rate, int fallback) {
        if (values.length < 2) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        boolean directional = palette != null && palette.enabled;
        GL11.glBegin(directional ? GL11.GL_LINES : GL11.GL_LINE_STRIP);
        if (!directional) {
            int color = palette == null ? fallback : palette.segmentColor(0, 0, rate);
            GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
            // End strips across unavailable points so a missing sample cannot fabricate a trend.
            for (int i = 0; i < values.length; i++) {
                if (!Double.isFinite(values[i])) {
                    GL11.glEnd();
                    GL11.glBegin(GL11.GL_LINE_STRIP);
                    continue;
                }
                GL11.glVertex2d(x + i * width / (double) (values.length - 1), y + height * (1 - values[i]));
            }
        } else for (int i = 1; i < values.length; i++) {
            if (!Double.isFinite(values[i - 1]) || !Double.isFinite(values[i])) continue;
            int color = palette == null ? fallback : palette.segmentColor(values[i - 1], values[i], rate);
            GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
            GL11.glVertex2d(x + (i - 1) * width / (double) (values.length - 1), y + height * (1 - values[i - 1]));
            GL11.glVertex2d(x + i * width / (double) (values.length - 1), y + height * (1 - values[i]));
        }
        GL11.glEnd();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
