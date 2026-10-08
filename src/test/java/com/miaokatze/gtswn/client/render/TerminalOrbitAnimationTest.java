package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;

public class TerminalOrbitAnimationTest {

    @Test
    public void allColorsChangeOnlyMovingParticlePixelsAndKeepOriginalOuterFrames() throws Exception {
        for (String name : new String[] { "ME_Network_Quantum_Terminal", "ME_Network_Quantum_Terminal_Alt" }) {
            ResourceLocation location = new ResourceLocation("gtswn", "textures/items/" + name + ".png");
            QuantumTintedTexturesTest.ResourceManager resources = new QuantumTintedTexturesTest.ResourceManager(null);
            BufferedImage source;
            try (InputStream input = resources.getResource(location)
                .getInputStream()) {
                source = ImageIO.read(input);
            }
            AnimationMetadataSection original = (AnimationMetadataSection) resources.getResource(location)
                .getMetadata("animation");
            int duration = original.getFrameTime();
            int period = duration * 8;
            TerminalOrbitAnimation purple = TerminalOrbitAnimation.create(source, original, QuantumNetworkColor.rgb(0));
            assertEquals(period * 16, purple.image.getHeight());
            for (int color = 0; color < QuantumNetworkColor.COUNT; color++) {
                QuantumTintedTextures.ColorSprite sprite = new QuantumTintedTextures.ColorSprite(
                    "test_terminal_" + name + color,
                    "gtswn:" + name,
                    "items",
                    color,
                    true,
                    null);
                assertFalse(sprite.load(resources, location));
                assertEquals(period, sprite.getFrameCount());
                java.lang.reflect.Field field = TextureAtlasSprite.class.getDeclaredField("animationMetadata");
                field.setAccessible(true);
                AnimationMetadataSection loaded = (AnimationMetadataSection) field.get(sprite);
                assertEquals(1, loaded.getFrameTime());
                boolean changed = false;
                for (int tick = 0; tick < period; tick++) {
                    int[] pixels = sprite.getFrameTextureData(tick)[0];
                    for (int y = 0; y < 16; y++) {
                        for (int x = 0; x < 16; x++) {
                            int pixel = pixels[y * 16 + x];
                            assertEquals(source.getRGB(x, tick / duration * 16 + y) >>> 24, pixel >>> 24);
                            if (!TerminalOrbitAnimation.insideScreen(x, y)
                                || (!TerminalOrbitAnimation.oldParticleMask(source, x, y, tick / duration)
                                    && TerminalOrbitAnimation.coverage(x, y, tick, period) == 0)) {
                                assertEquals(source.getRGB(x, tick / duration * 16 + y), pixel);
                            }
                            if (pixel != purple.image.getRGB(x, tick * 16 + y)) {
                                changed = true;
                                assertTrue(TerminalOrbitAnimation.insideScreen(x, y));
                                assertTrue(TerminalOrbitAnimation.coverage(x, y, tick, period) > 0);
                            }
                        }
                    }
                }
                assertEquals(color != 0, changed);
            }
        }
    }

    @Test
    public void orbitsAreOpposedAndHoldNormalPosesAcrossLoopSeam() {
        for (int period : new int[] { 80, 16 }) {
            for (int tick = 0; tick < period; tick++) {
                double[] first = TerminalOrbitAnimation.position(tick, period, 0);
                double[] second = TerminalOrbitAnimation.position(tick, period, 1);
                double[] next = TerminalOrbitAnimation.position((tick + 1) % period, period, 0);
                assertEquals(15, first[0] + second[0], 1e-12);
                assertEquals(17, first[1] + second[1], 1e-12);
                double step = Math.hypot((next[0] - first[0]) / 2.5, (next[1] - first[1]) / 2);
                if (period == 80 && tick % 4 != 3) assertEquals(0, step, 0);
                else assertTrue(step > 0.2);
            }
            org.junit.Assert.assertArrayEquals(
                TerminalOrbitAnimation.position(0, period, 0),
                TerminalOrbitAnimation.position(period, period, 0),
                0);
        }
    }

    @Test
    public void particlesCopyOriginalFiveByFourSpritesIncludingTheirColoredCornerGlints() throws Exception {
        QuantumTintedTexturesTest.ResourceManager resources = new QuantumTintedTexturesTest.ResourceManager(null);
        for (String name : new String[] { "ME_Network_Quantum_Terminal", "ME_Network_Quantum_Terminal_Alt" }) {
            ResourceLocation location = new ResourceLocation("gtswn", "textures/items/" + name + ".png");
            BufferedImage source;
            try (InputStream input = resources.getResource(location)
                .getInputStream()) {
                source = ImageIO.read(input);
            }
            AnimationMetadataSection metadata = (AnimationMetadataSection) resources.getResource(location)
                .getMetadata("animation");
            TerminalOrbitAnimation purple = TerminalOrbitAnimation.create(source, metadata, QuantumNetworkColor.rgb(0));
            TerminalOrbitAnimation red = TerminalOrbitAnimation.create(source, metadata, QuantumNetworkColor.rgb(14));
            int period = purple.image.getHeight() / 16;
            // The unchanged PNG is the contract: 12 colored body pixels and two pale corner glints per sprite.
            assertEquals(0xFFE09EE4, source.getRGB(4, 6));
            assertEquals(0xFFE4AAE6, source.getRGB(3, 7));
            int originalParticlePixels = 0;
            for (int y = 5; y <= 12; y++) {
                for (int x = 3; x <= 12; x++) {
                    if (TerminalOrbitAnimation.oldParticleMask(source, x, y, 0)) originalParticlePixels++;
                }
            }
            assertEquals(28, originalParticlePixels);
            for (int[] point : new int[][] { { 9, 7 }, { 10, 7 }, { 11, 12 } }) {
                assertFalse(TerminalOrbitAnimation.oldParticleMask(source, point[0], point[1], 0));
                boolean checked = false;
                for (int tick = 0; tick < metadata.getFrameTime(); tick++) {
                    if (TerminalOrbitAnimation.coverage(point[0], point[1], tick, period) != 0) continue;
                    assertEquals(
                        source.getRGB(point[0], point[1]),
                        purple.image.getRGB(point[0], tick * 16 + point[1]));
                    checked = true;
                }
                assertTrue(checked);
            }
            for (int particle = 0; particle < 2; particle++) {
                int count = 0;
                int left = particle == 0 ? 3 : 8, top = particle == 0 ? 6 : 8;
                for (int y = 0; y < 4; y++) {
                    for (int x = 0; x < 5; x++) {
                        if (TerminalOrbitAnimation.oldParticleMask(source, left + x, top + y, 0)) count++;
                    }
                }
                assertEquals(14, count);
            }
            for (int tick = 0; tick < period; tick++) {
                int[][] expected = new int[16][16];
                for (int particle = 0; particle < 2; particle++) {
                    int left = particle == 0 ? 3 : 8, top = particle == 0 ? 6 : 8;
                    double[] center = TerminalOrbitAnimation.position(tick, period, particle);
                    int destX = (int) Math.round(center[0] - 2), destY = (int) Math.round(center[1] - 1.5);
                    for (int y = 0; y < 4; y++) {
                        for (int x = 0; x < 5; x++) {
                            if (!TerminalOrbitAnimation.oldParticleMask(source, left + x, top + y, 0)) continue;
                            assertTrue(TerminalOrbitAnimation.insideScreen(destX + x, destY + y));
                            int destinationAlpha = source
                                .getRGB(destX + x, tick / metadata.getFrameTime() * 16 + destY + y) & 0xFF000000;
                            expected[destY + y][destX + x] = destinationAlpha
                                | (source.getRGB(left + x, top + y) & 0xFFFFFF);
                        }
                    }
                }
                boolean moved = false;
                for (int y = 5; y <= 12; y++) {
                    for (int x = 3; x <= 12; x++) {
                        if (expected[y][x] != 0) {
                            assertEquals(expected[y][x], purple.image.getRGB(x, tick * 16 + y));
                            int pixel = red.image.getRGB(x, tick * 16 + y);
                            assertTrue((pixel >> 16 & 255) > (pixel >> 8 & 255));
                            assertTrue((pixel >> 16 & 255) > (pixel & 255));
                        }
                        moved |= (purple.image.getRGB(x, tick * 16 + y) != red.image.getRGB(x, tick * 16 + y))
                            != (purple.image.getRGB(x, ((tick + 1) % period) * 16 + y)
                                != red.image.getRGB(x, ((tick + 1) % period) * 16 + y));
                    }
                }
                if (period == 80 && tick % 4 != 3) assertFalse(moved);
                if (period == 16) assertTrue(moved);
            }
        }
    }

    @Test
    public void unloadedDefaultAndColoredSpritesFallBackToOriginalIcon() {
        IIcon original = new QuantumTintedTextures.ColorSprite("fallback", "gtswn:test", "items", 0);
        IIcon[] variants = new IIcon[QuantumNetworkColor.COUNT];
        for (int color = 0; color < variants.length; color++) {
            variants[color] = new QuantumTintedTextures.ColorSprite(
                "test_fallback_" + color,
                "gtswn:test",
                "items",
                color,
                true,
                original);
            assertSame(original, QuantumTintedTextures.select(variants, color));
        }
    }

    @Test
    public void explicitBackgroundTimelineIsExpandedWithoutChangingOrderOrDuration() throws Exception {
        QuantumTintedTexturesTest.ResourceManager resources = new QuantumTintedTexturesTest.ResourceManager(
            "{\"frametime\":7,\"frames\":[{\"index\":2,\"time\":3},0]}");
        ResourceLocation location = new ResourceLocation("gtswn", "textures/items/ME_Network_Quantum_Terminal.png");
        BufferedImage source;
        try (InputStream input = resources.getResource(location)
            .getInputStream()) {
            source = ImageIO.read(input);
        }
        QuantumTintedTextures.ColorSprite sprite = new QuantumTintedTextures.ColorSprite(
            "test_terminal_explicit",
            "gtswn:ME_Network_Quantum_Terminal",
            "items",
            0,
            true,
            null);
        assertFalse(sprite.load(resources, location));
        assertEquals(10, sprite.getFrameCount());
        for (int tick = 0; tick < 10; tick++) {
            int frame = tick < 3 ? 2 : 0;
            assertEquals(source.getRGB(7, frame * 16 + 2), sprite.getFrameTextureData(tick)[0][2 * 16 + 7]);
        }
    }
}
