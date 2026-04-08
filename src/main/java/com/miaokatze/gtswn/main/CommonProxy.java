package com.miaokatze.gtswn.main;

import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.loader.ItemLoader;
import com.miaokatze.gtswn.loader.MachineLoader;
import com.miaokatze.gtswn.loader.TestMachineRecipeLoader;
import com.miaokatze.gtswn.register.CreativeTabManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import gregtech.api.GregTechAPI;

/**
 * 通用代理类
 * 处理服务端和客户端共有的逻辑，如配置加载、机器注册、创造模式物品栏初始化等。
 */
public class CommonProxy {

    /**
     * 预初始化阶段 (PreInit)
     * 在此阶段读取配置文件，并将机器注册任务添加到 GregTech 的处理队列中。
     */
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        GTSimpleWirelessNetwork.LOG.info("GTSimpleWirelessNetwork 开始初始化 (版本: " + Tags.VERSION + ")");

        // 注册物品
        GTSimpleWirelessNetwork.LOG.info("[0/3] 开始注册物品...");
        try {
            ItemLoader.initItems();
            GTSimpleWirelessNetwork.LOG.info("[0/3] 物品注册完成。");
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[0/3] 物品注册过程中发生严重错误，请检查日志", t);
        }

        // 定义机器注册任务
        Runnable registerRunnable = () -> {
            GTSimpleWirelessNetwork.LOG.info("[1/3] 开始执行机器注册流程...");
            try {
                MachineLoader.initMachines();
                GTSimpleWirelessNetwork.LOG.info("[1/3] 机器注册流程执行完毕。");
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[1/3] 机器注册过程中发生严重错误，请检查日志", t);
            }
        };

        // 将注册任务添加到 GregTech 的 sAfterGTLoad 队列
        try {
            if (GregTechAPI.sAfterGTLoad == null) {
                GTSimpleWirelessNetwork.LOG.warn("警告: GregTechAPI.sAfterGTLoad 为空，无法添加注册任务。");
            } else {
                int before = GregTechAPI.sAfterGTLoad.size();
                GregTechAPI.sAfterGTLoad.add(registerRunnable);
                int after = GregTechAPI.sAfterGTLoad.size();
                GTSimpleWirelessNetwork.LOG
                    .info("[1/3] 已将机器注册任务加入 GregTech 加载队列 (队列大小: " + before + " -> " + after + ")");
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("无法将注册任务添加到 GregTech 队列", t);
        }
    }

    /**
     * 初始化阶段 (Init)
     * 在此阶段完成创造模式物品栏的初始化。
     */
    @SuppressWarnings({ "unused" })
    public void init(FMLInitializationEvent event) {
        // 1. 确保机器注册任务已执行（通过 GregTech 队列在 preInit 结束时触发）
        // 2. 初始化创造模式物品栏（此时 GTSWNItemList 应已被 set() 填充）
        GTSimpleWirelessNetwork.LOG.info("[2/3] 开始初始化创造模式物品栏...");

        CreativeTabManager.initCreativeTab();
        GTSimpleWirelessNetwork.LOG.info(
            "[2/3] 创造模式物品栏初始化完成，当前包含 " + CreativeTabManager.getItemsToAdd()
                .size() + " 个物品。");
    }

    /**
     * 后初始化阶段 (PostInit)
     * 处理与其他模组的交互或完成最终设置，如注册测试配方。
     */
    @SuppressWarnings({ "unused" })
    public void postInit(FMLPostInitializationEvent event) {
        GTSimpleWirelessNetwork.LOG.info("[3/3] 开始注册测试配方...");
        try {
            TestMachineRecipeLoader.initRecipes();
            GTSimpleWirelessNetwork.LOG.info("[3/3] 测试配方注册完成。");
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[3/3] 测试配方注册过程中发生错误", t);
        }
    }

    /**
     * 服务器启动阶段
     * 用于注册服务器端命令。
     */
    @SuppressWarnings({ "unused" })
    public void serverStarting(FMLServerStartingEvent event) {}

    /**
     * 模组加载完成阶段
     * 如果之前注册失败，可以在此处进行最后的补救尝试。
     */
    public void loadComplete(cpw.mods.fml.common.event.FMLLoadCompleteEvent event) {}
}
