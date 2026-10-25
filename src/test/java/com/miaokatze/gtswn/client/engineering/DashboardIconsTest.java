package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.DashboardIcons.Icon;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.api.ui.component.UiRenderer;

public class DashboardIconsTest {

    @Test
    public void reusedActionSilhouettesMatchTheShippedFactoryAtlas() throws Exception {
        BufferedImage atlas = ImageIO.read(getClass().getResource("/assets/gtswn/textures/gui/factory_atlas.png"));
        Icon[] icons = { Icon.GAUGE, Icon.LIBRARY, Icon.CHART, Icon.CLOSE };
        int[] offsets = { 0, 12, 48, 84 };
        for (int glyph = 0; glyph < icons.length; glyph++) {
            Pixels renderer = new Pixels();
            DashboardIcons.draw(renderer, icons[glyph], 0, 0, 10, 0xFF123456);
            boolean[] actual = new boolean[100];
            for (UiRect pixel : renderer.rectangles) {
                assertEquals(1, pixel.width, 0);
                assertEquals(1, pixel.height, 0);
                actual[(int) pixel.y * 10 + (int) pixel.x] = true;
            }
            for (int i = 0; i < 100; i++)
                assertEquals(atlas.getRGB(offsets[glyph] + i % 10, 40 + i / 10) >>> 24 != 0, actual[i]);
        }
    }

    @Test
    public void toolbarAndCardGlyphsKeepOnePixelStrokesInsideTheirSharedTenPixelFootprint() {
        for (Icon icon : Icon.values()) {
            Pixels renderer = new Pixels();
            DashboardIcons.draw(renderer, icon, 4, 8, 16, 0xFF123456);
            assertFalse(icon.name(), renderer.rectangles.isEmpty());
            for (UiRect pixel : renderer.rectangles) {
                assertEquals(1, pixel.width, 0);
                assertEquals(1, pixel.height, 0);
                assertTrue(pixel.x >= 7 && pixel.right() <= 17);
                assertTrue(pixel.y >= 11 && pixel.bottom() <= 21);
            }
        }
    }

    private static final class Pixels implements UiRenderer {

        final List<UiRect> rectangles = new ArrayList<>();

        public void fill(UiRect rect, int color) {
            rectangles.add(rect);
        }

        public void text(String text, double x, double y, int color) {}

        public void pushClip(UiRect rect) {}

        public void popClip() {}
    }
}
