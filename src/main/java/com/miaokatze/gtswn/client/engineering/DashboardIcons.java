package com.miaokatze.gtswn.client.engineering;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;

import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiRenderer;
import com.miaokatze.miaokatzecore.client.render.GlScope;
import com.miaokatze.miaokatzecore.client.ui.MinecraftUiRenderer;

/** Shared factory and dashboard pixel glyphs. Cached once, drawn in a single native batch. */
public final class DashboardIcons {

    public enum Icon {

        BACK(10, 3, 5, 8, 5, 8, 10, 13, 5, 8, 13, 8),
        EDIT(3, 10, 10, 3, 10, 3, 13, 6, 13, 6, 6, 13, 6, 13, 3, 13, 3, 13, 3, 10, 8, 5, 11, 8),
        CHECK(3, 8, 7, 12, 7, 12, 13, 4),
        LIBRARY(12),
        FIT(2, 6, 2, 2, 2, 2, 6, 2, 10, 2, 14, 2, 14, 2, 14, 6, 14, 10, 14, 14, 14, 14, 10, 14, 6, 14, 2, 14, 2, 14, 2,
            10),
        THEME(3, 3, 13, 3, 13, 3, 13, 13, 13, 13, 3, 13, 3, 13, 3, 3, 8, 3, 8, 13, 10, 5, 11, 5, 10, 8, 11, 8, 10, 11,
            11, 11),
        CLOSE(84),
        DELETE(3, 4, 13, 4, 6, 2, 10, 2, 4, 4, 5, 13, 5, 13, 11, 13, 11, 13, 12, 4, 7, 6, 7, 11, 9, 6, 9, 11),
        LINK(3, 8, 3, 4, 3, 4, 8, 4, 8, 4, 8, 7, 6, 9, 6, 12, 6, 12, 12, 12, 12, 12, 12, 8, 5, 10, 10, 5),
        UNLINK(84),
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
        CHART(48),
        GAUGE(0),
        MACHINE(2, 3, 14, 3, 14, 3, 14, 13, 14, 13, 2, 13, 2, 13, 2, 3, 5, 6, 10, 6, 5, 9, 7, 9, 10, 10, 11, 10),
        RESOURCE(2, 5, 8, 2, 8, 2, 14, 5, 14, 5, 14, 12, 14, 12, 8, 15, 8, 15, 2, 12, 2, 12, 2, 5, 2, 5, 8, 8, 8, 8, 14,
            5, 8, 8, 8, 15),
        HISTORY(2, 2, 2, 13, 2, 13, 14, 13, 4, 10, 7, 6, 7, 6, 10, 9, 10, 9, 13, 3),
        WIRELESS(2, 5, 5, 2, 5, 2, 11, 2, 11, 2, 14, 5, 4, 8, 6, 6, 6, 6, 10, 6, 10, 6, 12, 8, 7, 10, 9, 10, 8, 12, 8,
            14),
        SORT(3, 3, 13, 3, 5, 7, 11, 7, 7, 11, 9, 11, 8, 11, 8, 14),
        SAVE(3, 2, 12, 2, 12, 2, 14, 4, 14, 4, 14, 14, 14, 14, 2, 14, 2, 14, 2, 2, 5, 2, 5, 6, 5, 6, 11, 6, 11, 6, 11,
            2, 5, 10, 11, 10, 5, 13, 11, 13),
        SHARE(8, 11, 8, 2, 8, 2, 4, 6, 8, 2, 12, 6, 3, 9, 3, 14, 3, 14, 13, 14, 13, 14, 13, 9),
        FLUID(8, 2, 3, 9, 3, 9, 3, 12, 3, 12, 6, 14, 6, 14, 10, 14, 10, 14, 13, 12, 13, 12, 13, 9, 13, 9, 8, 2);

        private final boolean[] pixels;

        Icon(double... lines) {
            pixels = rasterize(lines);
        }

        Icon(int atlasX) {
            pixels = atlasPixels(atlasX);
        }
    }

    private DashboardIcons() {}

    /** Match the factory atlas: ten pixels, one-pixel strokes and an unscaled optical footprint. */
    public static void draw(UiRenderer renderer, Icon icon, double x, double y, double size, int color) {
        if (size <= 0 || icon == null) return;
        double footprint = Math.min(size, 10);
        double left = x + (size - footprint) / 2, top = y + (size - footprint) / 2;
        if (renderer instanceof MinecraftUiRenderer) {
            drawPixels(icon.pixels, left, top, footprint, color);
            return;
        }
        double scale = footprint / 10;
        for (int i = 0; i < 100; i++) if (icon.pixels[i])
            renderer.fill(new UiRect(left + i % 10 * scale, top + i / 10 * scale, scale, scale), color);
    }

    static void drawAtlas(int atlasX, double x, double y, int color) {
        drawPixels(Atlas.PIXELS[atlasX / 12], x, y, 10, color);
    }

    private static void drawPixels(boolean[] pixels, double x, double y, double size, int color) {
        double scale = size / 10;
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
            for (int i = 0; i < 100; i++) if (pixels[i]) {
                double left = x + i % 10 * scale, top = y + i / 10 * scale;
                GL11.glVertex2d(left, top);
                GL11.glVertex2d(left + scale, top);
                GL11.glVertex2d(left + scale, top + scale);
                GL11.glVertex2d(left, top + scale);
            }
            GL11.glEnd();
        }
    }

    /** Existing glyph silhouettes are reused from the actual atlas, with theme-safe tinting. */
    static boolean[] atlasPixels(int atlasX) {
        return Atlas.PIXELS[atlasX / 12];
    }

    private static final class Atlas {

        private static final boolean[][] PIXELS = load();

        private static boolean[][] load() {
            try (InputStream stream = DashboardIcons.class
                .getResourceAsStream("/assets/gtswn/textures/gui/factory_atlas.png")) {
                if (stream == null) throw new IOException("Missing factory atlas");
                BufferedImage image = ImageIO.read(stream);
                boolean[][] result = new boolean[8][100];
                for (int icon = 0; icon < result.length; icon++) for (int i = 0; i < 100; i++)
                    result[icon][i] = image.getRGB(icon * 12 + i % 10, 40 + i / 10) >>> 24 != 0;
                return result;
            } catch (IOException failure) {
                throw new IllegalStateException("Could not load factory action glyphs", failure);
            }
        }
    }

    private static boolean[] rasterize(double[] lines) {
        boolean[] pixels = new boolean[100];
        for (int i = 0; i < lines.length; i += 4) {
            int x1 = (int) Math.round(lines[i] * 9 / 16), y1 = (int) Math.round(lines[i + 1] * 9 / 16);
            int x2 = (int) Math.round(lines[i + 2] * 9 / 16), y2 = (int) Math.round(lines[i + 3] * 9 / 16);
            int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
            for (int step = 0; step <= steps; step++) {
                double fraction = steps == 0 ? 0 : step / (double) steps;
                int x = (int) Math.round(x1 + (x2 - x1) * fraction);
                int y = (int) Math.round(y1 + (y2 - y1) * fraction);
                pixels[y * 10 + x] = true;
            }
        }
        return pixels;
    }
}
