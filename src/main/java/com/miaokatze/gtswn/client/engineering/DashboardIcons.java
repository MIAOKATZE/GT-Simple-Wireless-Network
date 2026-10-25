package com.miaokatze.gtswn.client.engineering;

import org.lwjgl.opengl.GL11;

import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiRenderer;
import com.miaokatze.miaokatzecore.client.render.GlScope;
import com.miaokatze.miaokatzecore.client.ui.MinecraftUiRenderer;

/** Font-independent, shared dashboard action glyphs on a sixteen-unit grid. */
public final class DashboardIcons {

    public enum Icon {

        BACK(10, 3, 5, 8, 5, 8, 10, 13, 5, 8, 13, 8),
        EDIT(3, 10, 10, 3, 10, 3, 13, 6, 13, 6, 6, 13, 6, 13, 3, 13, 3, 13, 3, 10, 8, 5, 11, 8),
        CHECK(3, 8, 7, 12, 7, 12, 13, 4),
        LIBRARY(3, 3, 6, 3, 6, 3, 6, 13, 6, 13, 3, 13, 3, 13, 3, 3, 8, 3, 8, 13, 10, 4, 13, 12),
        FIT(2, 6, 2, 2, 2, 2, 6, 2, 10, 2, 14, 2, 14, 2, 14, 6, 14, 10, 14, 14, 14, 14, 10, 14, 6, 14, 2, 14, 2, 14, 2,
            10),
        THEME(3, 3, 13, 3, 13, 3, 13, 13, 13, 13, 3, 13, 3, 13, 3, 3, 8, 3, 8, 13, 10, 5, 11, 5, 10, 8, 11, 8, 10, 11,
            11, 11),
        CLOSE(4, 4, 12, 12, 12, 4, 4, 12),
        DELETE(3, 4, 13, 4, 6, 2, 10, 2, 4, 4, 5, 13, 5, 13, 11, 13, 11, 13, 12, 4, 7, 6, 7, 11, 9, 6, 9, 11),
        LINK(3, 8, 3, 4, 3, 4, 8, 4, 8, 4, 8, 7, 6, 9, 6, 12, 6, 12, 12, 12, 12, 12, 12, 8, 5, 10, 10, 5),
        UNLINK(4, 4, 12, 12, 12, 4, 4, 12),
        ADD(3, 8, 13, 8, 8, 3, 8, 13),
        PREVIOUS(10, 3, 5, 8, 5, 8, 10, 13, 5, 8, 13, 8),
        NEXT(6, 3, 11, 8, 11, 8, 6, 13, 3, 8, 11, 8),
        INVENTORY(2, 5, 8, 2, 8, 2, 14, 5, 14, 5, 14, 12, 14, 12, 8, 15, 8, 15, 2, 12, 2, 12, 2, 5, 2, 5, 8, 8, 8, 8,
            14, 5, 8, 8, 8, 15),
        DELTA(2, 12, 8, 3, 8, 3, 14, 12, 14, 12, 2, 12),
        PAGE(4, 2, 11, 2, 11, 2, 13, 4, 13, 4, 13, 14, 13, 14, 4, 14, 4, 14, 4, 2, 6, 6, 10, 6, 6, 9, 10, 9),
        SETTINGS(2, 4, 14, 4, 2, 8, 14, 8, 2, 12, 14, 12, 5, 2, 5, 6, 11, 6, 11, 10, 7, 10, 7, 14),
        PLAY(5, 3, 12, 8, 12, 8, 5, 13, 5, 13, 5, 3),
        PAUSE(5, 3, 5, 13, 10, 3, 10, 13),
        CHART(2, 2, 2, 13, 2, 13, 14, 13, 4, 10, 7, 6, 7, 6, 10, 9, 10, 9, 13, 3),
        GAUGE(2, 12, 2, 7, 2, 7, 5, 3, 5, 3, 11, 3, 11, 3, 14, 7, 14, 7, 14, 12, 8, 11, 11, 6),
        MACHINE(2, 3, 14, 3, 14, 3, 14, 13, 14, 13, 2, 13, 2, 13, 2, 3, 5, 6, 10, 6, 5, 9, 7, 9, 10, 10, 11, 10),
        RESOURCE(2, 5, 8, 2, 8, 2, 14, 5, 14, 5, 14, 12, 14, 12, 8, 15, 8, 15, 2, 12, 2, 12, 2, 5, 2, 5, 8, 8, 8, 8, 14,
            5, 8, 8, 8, 15),
        HISTORY(2, 2, 2, 13, 2, 13, 14, 13, 4, 10, 7, 6, 7, 6, 10, 9, 10, 9, 13, 3),
        WIRELESS(2, 5, 5, 2, 5, 2, 11, 2, 11, 2, 14, 5, 4, 8, 6, 6, 6, 6, 10, 6, 10, 6, 12, 8, 7, 10, 9, 10, 8, 12, 8,
            14),
        SORT(3, 3, 13, 3, 5, 7, 11, 7, 7, 11, 9, 11, 8, 11, 8, 14),
        FLUID(8, 2, 3, 9, 3, 9, 3, 12, 3, 12, 6, 14, 6, 14, 10, 14, 10, 14, 13, 12, 13, 12, 13, 9, 13, 9, 8, 2);

        private final double[] lines;

        Icon(double... lines) {
            this.lines = lines;
        }
    }

    private DashboardIcons() {}

    public static void draw(UiRenderer renderer, Icon icon, double x, double y, double size, int color) {
        if (size <= 0 || icon == null) return;
        double[] lines = icon.lines;
        if (renderer instanceof MinecraftUiRenderer) {
            drawNative(lines, x, y, size, color);
            return;
        }
        double scale = size / 16;
        for (int i = 0; i < lines.length; i += 4) line(
            renderer,
            x + lines[i] * scale,
            y + lines[i + 1] * scale,
            x + lines[i + 2] * scale,
            y + lines[i + 3] * scale,
            Math.max(1, scale),
            color);
    }

    /** The Minecraft bridge already applied the scene/card matrix and clip. One batch per glyph. */
    private static void drawNative(double[] lines, double x, double y, double size, int color) {
        double scale = size / 16;
        double halfWidth = Math.max(1, scale) / 2;
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(
                (color >> 16 & 255) / 255f,
                (color >> 8 & 255) / 255f,
                (color & 255) / 255f,
                (color >>> 24) / 255f);
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = 0; i < lines.length; i += 4) {
                double x1 = x + lines[i] * scale, y1 = y + lines[i + 1] * scale;
                double x2 = x + lines[i + 2] * scale, y2 = y + lines[i + 3] * scale;
                double dx = x2 - x1, dy = y2 - y1;
                double length = Math.sqrt(dx * dx + dy * dy);
                double px = -dy / length * halfWidth, py = dx / length * halfWidth;
                GL11.glVertex2d(x1 + px, y1 + py);
                GL11.glVertex2d(x2 + px, y2 + py);
                GL11.glVertex2d(x2 - px, y2 - py);
                GL11.glVertex2d(x1 - px, y1 - py);
            }
            GL11.glEnd();
        }
    }

    private static void line(UiRenderer renderer, double x1, double y1, double x2, double y2, double thickness,
        int color) {
        if (x1 == x2 || y1 == y2) {
            renderer.fill(
                new UiRect(
                    Math.min(x1, x2),
                    Math.min(y1, y2),
                    Math.abs(x2 - x1) + thickness,
                    Math.abs(y2 - y1) + thickness),
                color);
            return;
        }
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1))));
        for (int i = 0; i <= steps; i++) {
            double fraction = i / (double) steps;
            renderer
                .fill(new UiRect(x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, thickness, thickness), color);
        }
    }
}
