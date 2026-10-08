package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.client.resources.data.AnimationMetadataSectionSerializer;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.util.ResourceLocation;

import org.junit.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class QuantumTintedTexturesTest {

    @Test
    public void neutralCasingAndTransparentPixelsRemainExactAcrossColors() {
        BufferedImage source = new BufferedImage(4, 1, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = { 0xFFFFFFFF, 0x7FBCBCBC, 0xFF202020, 0x00AE63FF };
        for (int x = 0; x < pixels.length; x++) {
            source.setRGB(x, 0, pixels[x]);
        }
        for (int color : new int[] { 0xB02E26, 0x5E7C16, 0x3C44AA, 0xF9FFFE }) {
            BufferedImage result = QuantumTintedTextures.recolor(source, color);
            for (int x = 0; x < pixels.length; x++) {
                assertEquals(pixels[x], result.getRGB(x, 0));
            }
        }
    }

    @Test
    public void redAndGreenStayVisibleAndPreserveAlphaInEveryAnimationFrame() {
        BufferedImage animation = new BufferedImage(1, 3, BufferedImage.TYPE_INT_ARGB);
        animation.setRGB(0, 0, 0xFFAE63FF);
        animation.setRGB(0, 1, 0x7F7439BA);
        animation.setRGB(0, 2, 0x3FE1ACFF);
        for (int color : new int[] { 0xB02E26, 0x5E7C16 }) {
            BufferedImage result = QuantumTintedTextures.recolor(animation, color);
            assertEquals(animation.getHeight(), result.getHeight());
            for (int y = 0; y < animation.getHeight(); y++) {
                int pixel = result.getRGB(0, y);
                assertEquals(animation.getRGB(0, y) >>> 24, pixel >>> 24);
                int r = pixel >> 16 & 255, g = pixel >> 8 & 255, b = pixel & 255;
                assertTrue(Math.max(r, Math.max(g, b)) > 60);
                assertTrue(color == 0xB02E26 ? r > g && r > b : g > r && g > b);
            }
        }
    }

    @Test
    public void actualTerminalAndNodeLayersLoadAllFramesAndPreserveAlpha() throws Exception {
        String[] sources = { "items/ME_Network_Quantum_Terminal", "items/ME_Network_Quantum_Terminal_Alt",
            "blocks/ME_Network_Quantum_Node", "blocks/ME_Network_Quantum_Node_OFF",
            "blocks/ME_Network_Quantum_Node_opaque", "blocks/ME_Network_Quantum_Node_translucent",
            "blocks/ME_Network_Quantum_Node_OFF_opaque", "blocks/ME_Network_Quantum_Node_OFF_translucent" };
        ResourceManager resources = new ResourceManager(null);
        for (String source : sources) {
            ResourceLocation location = new ResourceLocation("gtswn", "textures/" + source + ".png");
            IResource resource = resources.getResource(location);
            BufferedImage image;
            try (InputStream stream = resource.getInputStream()) {
                image = ImageIO.read(stream);
            }
            AnimationMetadataSection metadata = (AnimationMetadataSection) resource.getMetadata("animation");
            int size = image.getWidth();
            int frames = metadata == null ? 1 : image.getHeight() / size;
            String[] names = source.split("/");
            for (int color : new int[] { 1, 5, 11, 14 }) {
                TextureAtlasSprite sprite = new QuantumTintedTextures.ColorSprite(
                    "test_color_" + color,
                    "gtswn:" + names[1],
                    names[0],
                    color);
                assertFalse(source, sprite.load(resources, location));
                assertEquals(source, size, sprite.getIconWidth());
                assertEquals(source, size, sprite.getIconHeight());
                assertEquals(source, frames, sprite.getFrameCount());
                assertEquals(source, metadata != null, sprite.hasAnimationMetadata());
                for (int frame = 0; frame < frames; frame++) {
                    int[][] mipmaps = sprite.getFrameTextureData(frame);
                    assertNotNull(source, mipmaps);
                    assertEquals(source, 32 - Integer.numberOfLeadingZeros(size), mipmaps.length);
                    assertEquals(source, size * size, mipmaps[0].length);
                    for (int y = 0; y < size; y++) {
                        for (int x = 0; x < size; x++) {
                            assertEquals(
                                source,
                                image.getRGB(x, frame * size + y) >>> 24,
                                mipmaps[0][y * size + x] >>> 24);
                        }
                    }
                }
                java.lang.reflect.Field animation = TextureAtlasSprite.class.getDeclaredField("animationMetadata");
                animation.setAccessible(true);
                AnimationMetadataSection loaded = (AnimationMetadataSection) animation.get(sprite);
                if (metadata != null) {
                    assertEquals(source, metadata.getFrameTime(), loaded.getFrameTime());
                }
            }
        }
    }

    @Test
    public void customAnimationFrameOrderAndDurationsPassThroughVanillaLoader() throws Exception {
        String custom = "{\"frametime\":7,\"frames\":[{\"index\":2,\"time\":3},0]}";
        ResourceManager resources = new ResourceManager(custom);
        TextureAtlasSprite sprite = new QuantumTintedTextures.ColorSprite(
            "test_explicit_frames",
            "gtswn:ME_Network_Quantum_Terminal",
            "items",
            14);
        assertFalse(sprite.load(resources, new ResourceLocation("gtswn", "test")));
        assertEquals(3, sprite.getFrameCount());
        assertNotNull(sprite.getFrameTextureData(0));
        assertNotNull(sprite.getFrameTextureData(2));
        java.lang.reflect.Field animation = TextureAtlasSprite.class.getDeclaredField("animationMetadata");
        animation.setAccessible(true);
        AnimationMetadataSection loaded = (AnimationMetadataSection) animation.get(sprite);
        assertEquals(2, loaded.getFrameIndex(0));
        assertEquals(0, loaded.getFrameIndex(1));
        assertEquals(3, loaded.getFrameTimeSingle(0));
        assertEquals(7, loaded.getFrameTimeSingle(1));
    }

    @Test
    public void twentyFourPixelAnchorLoadsAllFramesWithStableAlphaAndVisiblePulse() throws Exception {
        ResourceLocation location = new ResourceLocation("gtswn", "textures/items/Quantum_Incorporation_Anchor.png");
        IResource resource = new ResourceManager(null).getResource(location);
        BufferedImage image;
        try (InputStream input = resource.getInputStream()) {
            image = ImageIO.read(input);
        }
        assertEquals(24, image.getWidth());
        assertEquals(384, image.getHeight());
        AnimationMetadataSection metadata = (AnimationMetadataSection) resource.getMetadata("animation");
        assertEquals(2, metadata.getFrameTime());
        TextureAtlasSprite sprite = new TextureAtlasSprite("anchor_24px") {};
        BufferedImage[] mipmaps = new BufferedImage[5];
        mipmaps[0] = image;
        sprite.loadSprite(mipmaps, metadata, false);
        assertEquals(24, sprite.getIconWidth());
        assertEquals(24, sprite.getIconHeight());
        assertEquals(16, sprite.getFrameCount());
        for (int frame = 0; frame < 16; frame++) {
            int[] pixels = sprite.getFrameTextureData(frame)[0];
            assertEquals(24 * 24, pixels.length);
            int changed = 0;
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int first = image.getRGB(x, y);
                    int pixel = pixels[y * 24 + x];
                    assertEquals(first >>> 24, pixel >>> 24);
                    if ((first >>> 24) > 0 && pixel != first) changed++;
                }
            }
            if (frame == 8) assertTrue(changed >= 24);
        }
    }

    static final class ResourceManager implements IResourceManager {

        private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(AnimationMetadataSection.class, new AnimationMetadataSectionSerializer())
            .create();
        private final String overrideAnimation;

        ResourceManager(String overrideAnimation) {
            this.overrideAnimation = overrideAnimation;
        }

        @Override
        public Set<String> getResourceDomains() {
            return Collections.singleton("gtswn");
        }

        @Override
        public IResource getResource(ResourceLocation location) throws IOException {
            String path = "/assets/" + location.getResourceDomain() + "/" + location.getResourcePath();
            if (QuantumTintedTexturesTest.class.getResource(path) == null) {
                throw new IOException("Resource absent: " + path);
            }
            return new IResource() {

                @Override
                public InputStream getInputStream() {
                    return QuantumTintedTexturesTest.class.getResourceAsStream(path);
                }

                @Override
                public boolean hasMetadata() {
                    return overrideAnimation != null
                        || QuantumTintedTexturesTest.class.getResource(path + ".mcmeta") != null;
                }

                @Override
                public IMetadataSection getMetadata(String section) {
                    if (!"animation".equals(section)) {
                        return null;
                    }
                    if (overrideAnimation != null) {
                        return GSON.fromJson(overrideAnimation, AnimationMetadataSection.class);
                    }
                    InputStream stream = QuantumTintedTexturesTest.class.getResourceAsStream(path + ".mcmeta");
                    if (stream == null) {
                        return null;
                    }
                    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        JsonObject object = GSON.fromJson(reader, JsonObject.class);
                        return GSON.fromJson(object.get("animation"), AnimationMetadataSection.class);
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            };
        }

        @Override
        public List<IResource> getAllResources(ResourceLocation location) throws IOException {
            return Collections.singletonList(getResource(location));
        }
    }
}
