package com.miaokatze.gtswn.register;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.render.TextureFactory;

/**
 * 材质注册管理器 - 统一管理所有材质
 */
public class TextureManager {

    private static final Map<String, ITexture> textureCache = new HashMap<>();

    /**
     * 从缓存获取材质，如果不存在则创建
     */
    public static ITexture getOrCreateTexture(String name, Textures.BlockIcons icon) {
        return textureCache.computeIfAbsent(name, k -> TextureFactory.of(icon));
    }

    /**
     * 从缓存获取材质
     */
    public static ITexture getTexture(String name) {
        return textureCache.get(name);
    }

    /**
     * 注册材质到缓存
     */
    public static void registerTexture(String name, ITexture texture) {
        textureCache.put(name, texture);
    }

    /**
     * 创建 ResourceLocation
     */
    public static ResourceLocation createResourceLocation(String path) {
        return new ResourceLocation("gtswn", path);
    }

    /**
     * 清空材质缓存
     */
    public static void clearCache() {
        textureCache.clear();
    }
}
