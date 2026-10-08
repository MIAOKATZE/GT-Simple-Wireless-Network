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
            assertTrue(source.getRGB(5, 7) != purple.image.getRGB(5, 7));
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
    public void orbitsAreOpposedAndAdvanceUniformlyAcrossLoopSeam() {
        for (int period : new int[] { 80, 16 }) {
            double expected = 2 * Math.sin(Math.PI / period);
            for (int tick = 0; tick < period; tick++) {
                double[] first = TerminalOrbitAnimation.position(tick, period, 0);
                double[] second = TerminalOrbitAnimation.position(tick, period, 1);
                double[] next = TerminalOrbitAnimation.position((tick + 1) % period, period, 0);
                assertEquals(15, first[0] + second[0], 1e-12);
                assertEquals(17, first[1] + second[1], 1e-12);
                assertEquals(expected, Math.hypot((next[0] - first[0]) / 3, (next[1] - first[1]) / 2), 1e-12);
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
