package com.miaokatze.gtswn.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;

public class QuantumConnectionMaterialTest {

    private static final String[] LAYERS = { "ME_Network_Quantum_Node_opaque", "ME_Network_Quantum_Node_translucent",
        "ME_Network_Quantum_Node_OFF_opaque", "ME_Network_Quantum_Node_OFF_translucent" };

    @Test
    public void actualLayersChangeOnlyArmBorderPixelsInEveryFrame() throws Exception {
        for (int layer = 0; layer < LAYERS.length; layer++) {
            BufferedImage source = source(LAYERS[layer]);
            int[] original = source.getRGB(0, 0, 32, source.getHeight(), null, 0, 32);
            boolean translucent = layer % 2 == 1;
            BufferedImage result = QuantumConnectionMaterial.create(source, translucent);
            for (int frame = 0; frame < source.getHeight(); frame += 32) {
                for (int y = 0; y < 32; y++) {
                    for (int x = 0; x < 32; x++) {
                        int expected = original[(frame + y) * 32 + x];
                        if (outsideCore(y) && border(x)) {
                            expected = translucent ? original[(frame + y) * 32 + (x == 14 ? 13 : 18)]
                                : expected & 0x00FFFFFF;
                        } else if (outsideCore(x) && border(y)) {
                            expected = translucent ? original[(frame + (y == 14 ? 13 : 18)) * 32 + x]
                                : expected & 0x00FFFFFF;
                        }
                        assertEquals(
                            LAYERS[layer] + ":" + frame + ":" + x + ":" + y,
                            expected,
                            result.getRGB(x, frame + y));
                        assertEquals(original[(frame + y) * 32 + x], source.getRGB(x, frame + y));
                    }
                }
                // Each arm's eight-pixel transverse strip now has a two-pixel center and three-pixel edges.
                for (int axial = 0; axial < 32; axial++) {
                    if (!outsideCore(axial)) continue;
                    for (int cross = 12; cross < 20; cross++) {
                        boolean center = cross == 15 || cross == 16;
                        int alpha = result.getRGB(cross, frame + axial) >>> 24;
                        int transposedAlpha = result.getRGB(axial, frame + cross) >>> 24;
                        assertEquals(center != translucent, alpha != 0);
                        assertEquals(center != translucent, transposedAlpha != 0);
                    }
                }
            }
        }
    }

    @Test
    public void connectionSpritesPreserveFrameMetadataCoreAndNetworkColorsOnReload() throws Exception {
        QuantumTintedTexturesTest.ResourceManager resources = new QuantumTintedTexturesTest.ResourceManager(null);
        for (int layer = 0; layer < LAYERS.length; layer++) {
            BufferedImage source = source(LAYERS[layer]);
            ResourceLocation location = new ResourceLocation("gtswn", "textures/blocks/" + LAYERS[layer] + ".png");
            IResource resource = resources.getResource(location);
            AnimationMetadataSection metadata = (AnimationMetadataSection) resource.getMetadata("animation");
            for (int color : new int[] { 0, 1, 5, 11, 14 }) {
                QuantumTintedTextures.ColorSprite sprite = new QuantumTintedTextures.ColorSprite(
                    "test_connection_" + layer + "_" + color,
                    "gtswn:" + LAYERS[layer],
                    "blocks",
                    color,
                    false,
                    true,
                    layer % 2 == 1,
                    null);
                BufferedImage material = QuantumConnectionMaterial.create(source, layer % 2 == 1);
                BufferedImage expected = color == 0 ? material
                    : QuantumTintedTextures.recolor(material, QuantumNetworkColor.rgb(color));
                BufferedImage core = color == 0 ? source
                    : QuantumTintedTextures.recolor(source, QuantumNetworkColor.rgb(color));
                for (int reload = 0; reload < 2; reload++) {
                    assertFalse(sprite.load(resources, location));
                    assertEquals(32, sprite.getIconWidth());
                    assertEquals(32, sprite.getIconHeight());
                    assertEquals(layer < 2 ? 32 : 1, sprite.getFrameCount());
                    assertEquals(metadata != null, sprite.hasAnimationMetadata());
                    for (int frame = 0; frame < sprite.getFrameCount(); frame++) {
                        int[] loaded = sprite.getFrameTextureData(frame)[0];
                        for (int y = 0; y < 32; y++) {
                            for (int x = 0; x < 32; x++) {
                                int expectedPixel = expected.getRGB(x, frame * 32 + y);
                                int loadedPixel = loaded[y * 32 + x];
                                assertEquals(expectedPixel >>> 24, loadedPixel >>> 24);
                                // Vanilla fills RGB under zero alpha while loading mipmaps.
                                if (expectedPixel >>> 24 != 0) assertEquals(expectedPixel, loadedPixel);
                                if (x >= 10 && x < 22 && y >= 10 && y < 22) {
                                    int corePixel = core.getRGB(x, frame * 32 + y);
                                    assertEquals(corePixel >>> 24, loadedPixel >>> 24);
                                    if (corePixel >>> 24 != 0) assertEquals(corePixel, loadedPixel);
                                }
                            }
                        }
                    }
                    if (metadata != null) {
                        Field field = TextureAtlasSprite.class.getDeclaredField("animationMetadata");
                        field.setAccessible(true);
                        AnimationMetadataSection loaded = (AnimationMetadataSection) field.get(sprite);
                        assertEquals(metadata.getFrameTime(), loaded.getFrameTime());
                        assertEquals(sprite.getFrameCount(), loaded.getFrameCount());
                        for (int frame = 0; frame < loaded.getFrameCount(); frame++) {
                            assertEquals(frame, loaded.getFrameIndex(frame));
                            assertEquals(metadata.getFrameTime(), loaded.getFrameTimeSingle(frame));
                        }
                    }
                }
            }
        }
    }

    @Test
    public void explicitAnimationOrderAndFrameTimesPassThroughConnectionLoader() throws Exception {
        QuantumTintedTexturesTest.ResourceManager resources = new QuantumTintedTexturesTest.ResourceManager(
            "{\"frametime\":7,\"frames\":[{\"index\":2,\"time\":3},0]}");
        QuantumTintedTextures.ColorSprite sprite = new QuantumTintedTextures.ColorSprite(
            "test_connection_order",
            "gtswn:" + LAYERS[0],
            "blocks",
            0,
            false,
            true,
            false,
            null);
        assertFalse(sprite.load(resources, new ResourceLocation("gtswn", "test")));
        Field field = TextureAtlasSprite.class.getDeclaredField("animationMetadata");
        field.setAccessible(true);
        AnimationMetadataSection loaded = (AnimationMetadataSection) field.get(sprite);
        assertEquals(2, loaded.getFrameCount());
        assertEquals(2, loaded.getFrameIndex(0));
        assertEquals(0, loaded.getFrameIndex(1));
        assertEquals(3, loaded.getFrameTimeSingle(0));
        assertEquals(7, loaded.getFrameTimeSingle(1));
    }

    @Test
    public void nonAtlasRegistrationAndUnloadedSpritesFallBackToOriginalIcon() {
        IIcon original = (IIcon) Proxy.newProxyInstance(
            IIcon.class.getClassLoader(),
            new Class<?>[] { IIcon.class },
            (proxy, method, arguments) -> null);
        IIcon[] variants = QuantumTintedTextures.registerConnection(name -> original, "gtswn:test", false);
        for (int color = 0; color < QuantumNetworkColor.COUNT; color++) {
            assertSame(original, QuantumTintedTextures.select(variants, color));
        }
        variants[0] = new QuantumTintedTextures.ColorSprite(
            "test_connection",
            "gtswn:test",
            "blocks",
            0,
            false,
            true,
            false,
            original);
        assertSame(original, QuantumTintedTextures.select(variants, 0));
    }

    private static boolean outsideCore(int coordinate) {
        return coordinate < 10 || coordinate >= 22;
    }

    private static boolean border(int coordinate) {
        return coordinate == 14 || coordinate == 17;
    }

    private static BufferedImage source(String name) throws Exception {
        try (InputStream stream = QuantumConnectionMaterialTest.class
            .getResourceAsStream("/assets/gtswn/textures/blocks/" + name + ".png")) {
            return ImageIO.read(stream);
        }
    }
}
