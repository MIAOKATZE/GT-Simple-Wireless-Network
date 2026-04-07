package com.miaokatze.gtswn.register;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.ResourceLocation;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.render.TextureFactory;

/**
 * 材质注册管理器
 * 统一管理模组内的所有材质资源，提供材质缓存、自定义图标定义以及资源路径创建功能。
 */
public class TextureManager {

    // --- 测试机器专用材质图标 ---
    /** EV 等级测试机器顶部材质 */
    public static final IIconContainer TEX_TEST_EV = new Textures.BlockIcons.CustomIcon("gtswn:MTETEST_1");
    /** IV 等级测试机器顶部材质 */
    public static final IIconContainer TEX_TEST_IV = new Textures.BlockIcons.CustomIcon("gtswn:MTETEST_2");
    /** LuV 等级测试机器顶部材质 */
    public static final IIconContainer TEX_TEST_LUV = new Textures.BlockIcons.CustomIcon("gtswn:MTETEST_3");

    // 材质缓存表，用于存储已创建的 ITexture 实例以提高性能
    private static final Map<String, ITexture> textureCache = new HashMap<>();

    /**
     * 获取或创建材质
     * 如果缓存中不存在该名称的材质，则根据提供的 BlockIcons 创建并缓存
     * 
     * @param name 材质的唯一标识名
     * @param icon GregTech 内置的方块图标枚举
     * @return 对应的 ITexture 实例
     */
    public static ITexture getOrCreateTexture(String name, Textures.BlockIcons icon) {
        return textureCache.computeIfAbsent(name, k -> TextureFactory.of(icon));
    }

    /**
     * 从缓存中获取材质
     * 
     * @param name 材质的唯一标识名
     * @return 对应的 ITexture 实例，若不存在则返回 null
     */
    public static ITexture getTexture(String name) {
        return textureCache.get(name);
    }

    /**
     * 手动注册材质到缓存
     * 
     * @param name    材质的唯一标识名
     * @param texture 要缓存的 ITexture 实例
     */
    public static void registerTexture(String name, ITexture texture) {
        textureCache.put(name, texture);
    }

    /**
     * 创建模组专属的资源位置 (ResourceLocation)
     * 
     * @param path 资源路径（不包含命名空间）
     * @return 完整的 ResourceLocation 对象
     */
    public static ResourceLocation createResourceLocation(String path) {
        return new ResourceLocation("gtswn", path);
    }

    /**
     * 清空所有材质缓存
     * 通常在模组重载或测试时使用
     */
    public static void clearCache() {
        textureCache.clear();
    }
}
