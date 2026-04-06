package com.miaokatze.gtswn.main;

import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.loader.MachineLoader;
import com.miaokatze.gtswn.register.CreativeTabManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import gregtech.api.GregTechAPI;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());

        GTSimpleWirelessNetwork.LOG.info("========================================");
        GTSimpleWirelessNetwork.LOG.info("GTSimpleWirelessNetwork 开始初始化...");
        GTSimpleWirelessNetwork.LOG.info("版本: " + Tags.VERSION);
        GTSimpleWirelessNetwork.LOG.info("========================================");

        Runnable registerRunnable = () -> {
            GTSimpleWirelessNetwork.LOG.info("[步骤 1/3] 开始注册机器...");
            try {
                MachineLoader.initMachines();
                GTSimpleWirelessNetwork.LOG.info("[步骤 1/3] ✅ 机器注册成功！");
            } catch (Throwable t) {
                GTSimpleWirelessNetwork.LOG.error("[步骤 1/3] ❌ 机器注册失败", t);
            }
        };

        // 将注册任务添加到 GregTech 队列（唯一注册方式）
        try {
            if (GregTechAPI.sAfterGTLoad == null) {
                GTSimpleWirelessNetwork.LOG.warn("⚠️  GregTechAPI.sAfterGTLoad == null; 无法添加注册任务");
            } else {
                int before = GregTechAPI.sAfterGTLoad.size();
                GregTechAPI.sAfterGTLoad.add(registerRunnable);
                int after = GregTechAPI.sAfterGTLoad.size();
                GTSimpleWirelessNetwork.LOG
                    .info("[步骤 1/3] 📋 已将注册任务添加到 GregTech 队列 (大小: " + before + " -> " + after + ")");
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("❌ 无法将注册任务添加到 GregTech 队列", t);
        }

        GTSimpleWirelessNetwork.LOG.info("========================================");
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    @SuppressWarnings({ "unused" })
    public void init(FMLInitializationEvent event) {
        GTSimpleWirelessNetwork.LOG.info("========================================");
        GTSimpleWirelessNetwork.LOG.info("[步骤 2/3] 初始化创造模式物品栏...");

        // Initialize creative tab
        CreativeTabManager.initCreativeTab();
        GTSimpleWirelessNetwork.LOG.info("[步骤 2/3] ✅ 创造模式物品栏初始化完成");

        // 不再在此处重复注册机器，因为已经在 preInit 中通过队列或立即注册完成了
        GTSimpleWirelessNetwork.LOG.info("[步骤 2/3] ℹ️  机器已在 preInit 阶段注册，跳过重复注册");
        GTSimpleWirelessNetwork.LOG.info("========================================");
    }

    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    @SuppressWarnings({ "unused" })
    public void postInit(FMLPostInitializationEvent event) {}

    // register server commands in this event handler (Remove if not needed)
    @SuppressWarnings({ "unused" })
    public void serverStarting(FMLServerStartingEvent event) {}

    // Called at the end of mod loading lifecycle. Final attempt to register if needed.
    public void loadComplete(cpw.mods.fml.common.event.FMLLoadCompleteEvent event) {}
}
