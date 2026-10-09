package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.Test;

public class LinkNodeMaterialTest {

    @Test
    public void materialCachesAndResolutionsKeepLegacyAndHoneycombDistinct() {
        assertEquals(16, LinkNodeVisuals.resolution("old"));
        assertEquals(16, LinkNodeVisuals.resolution("oldplus"));
        assertEquals(LinkNodeVisuals.textureIndex("medium"), LinkNodeVisuals.textureIndex("high"));
        assertTrue(LinkNodeVisuals.textureIndex("old") != LinkNodeVisuals.textureIndex("oldplus"));
        assertTrue(LinkNodeVisuals.textureIndex("old") != LinkNodeVisuals.textureIndex("high"));
    }

    @Test
    public void preservedLegacyAssetsContainFourDistinctTransparentFrames() throws Exception {
        for (String folder : new String[] { "blocks/covers", "covers" }) {
            for (String kind : new String[] { "input", "output" }) {
                String path = "/assets/gtswn/textures/" + folder + "/wireless_connector_" + kind + ".png";
                try (InputStream stream = getClass().getResourceAsStream(path)) {
                    assertNotNull(path, stream);
                    BufferedImage image = ImageIO.read(stream);
                    assertEquals(16, image.getWidth());
                    assertEquals(64, image.getHeight());
                    int[][] frames = new int[4][];
                    for (int i = 0; i < 4; i++) {
                        frames[i] = image.getRGB(0, i * 16, 16, 16, null, 0, 16);
                        assertTrue(
                            Arrays.stream(frames[i])
                                .anyMatch(pixel -> (pixel >>> 24) == 0));
                        assertTrue(
                            Arrays.stream(frames[i])
                                .anyMatch(pixel -> (pixel >>> 24) != 0));
                    }
                    for (int i = 0; i < 4; i++) for (int j = i + 1; j < 4; j++) {
                        assertTrue(path, !Arrays.equals(frames[i], frames[j]));
                    }
                }
            }
        }
    }
}
