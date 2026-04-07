package com.miaokatze.gtswn.main;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.miaokatze.gtswn.Tags;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

/**
 * 模组主类
 * 负责模组的入口管理、生命周期事件分发以及代理类的初始化。
 */
@Mod(
    modid = GTSimpleWirelessNetwork.MODID,
    version = Tags.VERSION,
    name = "GTSimpleWirelessNetwork",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:gregtech;")
public class GTSimpleWirelessNetwork {

    // 模组唯一标识符 (Mod ID)
    public static final String MODID = "gtswn";

    // 日志记录器，用于输出模组运行信息
    public static final Logger LOG = LogManager.getLogger(MODID);

    // 代理类实例，用于处理客户端和服务端的差异化逻辑
    @SidedProxy(
        clientSide = "com.miaokatze.gtswn.main.ClientProxy",
        serverSide = "com.miaokatze.gtswn.main.CommonProxy")
    public static CommonProxy proxy;

    /**
     * 预初始化阶段 (PreInit)
     * 模组加载的最早阶段，通常用于读取配置、注册方块和物品。
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    /**
     * 初始化阶段 (Init)
     * 在此阶段进行模组的详细设置，如注册配方、初始化数据结构等。
     */
    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    /**
     * 后初始化阶段 (PostInit)
     * 处理与其他模组的交互，确保所有模组都已加载完毕后再进行最终配置。
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    /**
     * 服务器启动阶段
     * 用于注册服务器端命令或处理服务器特有的初始化逻辑。
     */
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    /**
     * 模组加载完成阶段
     * 在所有模组都加载完成后调用，适合执行最终的兼容性检查或补救措施。
     */
    @Mod.EventHandler
    public void loadComplete(FMLLoadCompleteEvent event) {
        if (proxy != null) {
            try {
                proxy.loadComplete(event);
            } catch (Throwable t) {
                LOG.error("在 loadComplete 阶段调用代理类时发生错误", t);
            }
        }
    }
}
