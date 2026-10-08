package com.miaokatze.gtswn.client.render;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Atlas variants for node material and the terminal's two orbiting particles. */
@SideOnly(Side.CLIENT)
public final class QuantumTintedTextures {

    private QuantumTintedTextures() {}

    public static IIcon[] register(IIconRegister register, String sourceName, String folder) {
        return register(register, sourceName, folder, false);
    }

    public static IIcon[] registerTerminal(IIconRegister register, String sourceName, String folder) {
        return register(register, sourceName, folder, true);
    }

    private static IIcon[] register(IIconRegister register, String sourceName, String folder, boolean terminal) {
        IIcon[] variants = new IIcon[QuantumNetworkColor.COUNT];
        IIcon original = register.registerIcon(sourceName);
        variants[0] = original;
        if (!(register instanceof TextureMap)) {
            java.util.Arrays.fill(variants, original);
            return variants;
        }
        TextureMap atlas = (TextureMap) register;
        for (int i = terminal ? 0 : 1; i < variants.length; i++) {
            String name = sourceName + "_color_" + i;
            TextureAtlasSprite existing = atlas.getTextureExtry(name);
            if (existing == null) {
                existing = new ColorSprite(name, sourceName, folder, i, terminal, original);
                atlas.setTextureEntry(name, existing);
            }
            variants[i] = existing;
        }
        return variants;
    }

    public static IIcon select(IIcon[] variants, int index) {
        IIcon variant = variants[QuantumNetworkColor.normalize(index)];
        return variant instanceof ColorSprite && !((ColorSprite) variant).loaded ? ((ColorSprite) variant).fallback
            : variant;
    }

    static final class ColorSprite extends TextureAtlasSprite {

        private final ResourceLocation source;
        private final int color;
        private final boolean terminal;
        private final IIcon fallback;
        private boolean loaded;

        ColorSprite(String name, String sourceName, String folder, int index) {
            this(name, sourceName, folder, index, false, null);
        }

        ColorSprite(String name, String sourceName, String folder, int index, boolean terminal, IIcon fallback) {
            super(name);
            this.terminal = terminal;
            this.fallback = fallback;
            ResourceLocation sourceIcon = new ResourceLocation(sourceName);
            source = new ResourceLocation(
                sourceIcon.getResourceDomain(),
                "textures/" + folder + "/" + sourceIcon.getResourcePath() + ".png");
            color = QuantumNetworkColor.rgb(index);
        }

        @Override
        public boolean hasCustomLoader(IResourceManager manager, ResourceLocation location) {
            return true;
        }

        @Override
        public boolean load(IResourceManager manager, ResourceLocation location) {
            loaded = false;
            try {
                IResource resource = manager.getResource(source);
                try (InputStream stream = resource.getInputStream()) {
                    BufferedImage image = ImageIO.read(stream);
                    if (image == null) {
                        throw new IOException("Invalid quantum texture");
                    }
                    AnimationMetadataSection metadata = (AnimationMetadataSection) resource.getMetadata("animation");
                    BufferedImage recolored;
                    if (terminal) {
                        TerminalOrbitAnimation animation = TerminalOrbitAnimation.create(image, metadata, color);
                        recolored = animation.image;
                        metadata = animation.metadata;
                    } else {
                        recolored = recolor(image, color);
                    }
                    int mipLevels = 32 - Integer.numberOfLeadingZeros(image.getWidth());
                    BufferedImage[] mipmaps = new BufferedImage[mipLevels];
                    mipmaps[0] = recolored;
                    loadSprite(mipmaps, metadata, false);
                    loaded = true;
                    return false;
                }
            } catch (IOException | RuntimeException exception) {
                FMLClientHandler.instance()
                    .trackBrokenTexture(source, exception.getMessage());
                return true;
            }
        }
    }

    static BufferedImage recolor(BufferedImage source, int color) {
        BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        float[] target = Color.RGBtoHSB(color >> 16 & 255, color >> 8 & 255, color & 255, null);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int pixel = source.getRGB(x, y);
                int r = pixel >> 16 & 255;
                int g = pixel >> 8 & 255;
                int b = pixel & 255;
                // Neutral casing and highlights are copied exactly. Only purple material changes hue.
                if (r > g + 8 && b > g + 8 && pixel >>> 24 != 0) {
                    float[] material = Color.RGBtoHSB(r, g, b, null);
                    float saturation = material[1] * target[1];
                    float brightness = material[2] * target[2];
                    pixel = (pixel & 0xFF000000) | (Color.HSBtoRGB(target[0], saturation, brightness) & 0xFFFFFF);
                }
                result.setRGB(x, y, pixel);
            }
        }
        return result;
    }
}
