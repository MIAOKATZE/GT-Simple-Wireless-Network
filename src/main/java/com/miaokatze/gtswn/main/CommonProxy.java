package com.miaokatze.gtswn.main;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.GTswn_Cover_Dynamo_Wireless;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.GTswn_Cover_Energy_Wireless;

import java.io.File;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.common.command.CommandGTSWN;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.gui.GTSWNGuiHandler;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.loader.ItemLoader;
import com.miaokatze.gtswn.loader.MachineLoader;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncAEMonitorData;
import com.miaokatze.gtswn.recipe.CraftingRecipes;
import com.miaokatze.gtswn.register.CreativeTabManager;
import com.miaokatze.gtswn.register.TextureManager;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.render.TextureFactory;

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
        // 配置文件统一放在 config/gtswn/ 目录下
        // Configurations are stored under config/gtswn/ directory
        File suggestedConfigFile = event.getSuggestedConfigurationFile();
        File configDir = new File(suggestedConfigFile.getParentFile(), "gtswn");
        if (!configDir.exists() && !configDir.mkdirs()) {
            GTSimpleWirelessNetwork.LOG.warn("无法创建配置目录: " + configDir.getAbsolutePath());
        }
        File mainConfigFile = new File(configDir, "gtswn.cfg");
        File networkConfigFile = new File(configDir, "gtswn_network.cfg");
        File aeConfigFile = new File(configDir, "gtswn_ae.cfg");
        Config.synchronizeConfiguration(mainConfigFile);
        Config.synchronizeNetworkConfiguration(networkConfigFile);
        Config.synchronizeAEConfiguration(new net.minecraftforge.common.config.Configuration(aeConfigFile));

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

        // 注册网络包通道（便携监测终端 EU 同步：修复客户端恒显示 0EU 的 Bug）
        GTSWNPacketHandler.register();
        NetworkRegistry.INSTANCE.registerGuiHandler(GTSimpleWirelessNetwork.instance, new GTSWNGuiHandler());
    }

    /**
     * 初始化阶段 (Init)
     * 在此阶段完成创造模式物品栏的初始化，并注册服务端 Tick 事件处理器。
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
     * 处理与其他模组的交互或完成最终设置，如注册合成配方。
     */
    @SuppressWarnings({ "unused" })
    public void postInit(FMLPostInitializationEvent event) {
        GTSimpleWirelessNetwork.LOG.info("[3/3] 开始注册合成配方...");
        try {
            CraftingRecipes.init();
            GTSimpleWirelessNetwork.LOG.info("[3/3] 合成配方注册完成。");
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[3/3] 合成配方注册过程中发生错误", t);
        }

        // 注册GTswn覆盖板
        GTSimpleWirelessNetwork.LOG.info("[PostInit] 开始注册GTswn覆盖板...");
        try {
            // 注册无线能量覆盖板（输入）-用我们自己的纹理！
            CoverRegistry.registerCover(
                GTswn_Cover_Energy_Wireless.get(1),
                TextureFactory.of(TextureManager.TEX_WIRELESS_CONNECTOR_INPUT),
                context -> new GTswn_Cover_EnergyWireless(context),
                CoverRegistry.INTERCEPTS_RIGHT_CLICK_COVER_PLACER);

            // 注册无线动力覆盖板（输出）-用我们自己的纹理！
            CoverRegistry.registerCover(
                GTswn_Cover_Dynamo_Wireless.get(1),
                TextureFactory.of(TextureManager.TEX_WIRELESS_CONNECTOR_OUTPUT),
                context -> new GTswn_Cover_DynamoWireless(context),
                CoverRegistry.INTERCEPTS_RIGHT_CLICK_COVER_PLACER);

            GTSimpleWirelessNetwork.LOG.info("[PostInit] GTswn覆盖板注册成功！");
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[PostInit] GTswn覆盖板注册失败", t);
        }
    }

    /**
     * 服务器启动阶段
     * 用于注册服务器端命令。
     */
    @SuppressWarnings({ "unused" })
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandGTSWN());
    }

    /**
     * 服务器已启动阶段（v1.5.15 新增）。
     * <p>
     * 在此阶段所有世界已加载，可安全获取 overworld 并执行 WorldSavedData 清理。
     * 用于清理超过 {@link Config#keepHistoryDays} 天未采样的网络信息屏历史数据集，避免内存泄漏。
     */
    @SuppressWarnings({ "unused" })
    public void serverStarted(FMLServerStartedEvent event) {
        // 仅在配置启用清理时执行（keepHistoryDays=0 表示永不清理）
        if (Config.keepHistoryDays <= 0) {
            return;
        }
        try {
            // 获取 overworld（dimension 0），NetworkInfoDataStore 存储在 perWorldStorage 中
            World overworld = MinecraftServer.getServer()
                .worldServerForDimension(0);
            if (overworld == null) {
                GTSimpleWirelessNetwork.LOG.warn("[serverStarted] overworld 不可用，跳过网络信息屏历史数据清理");
                return;
            }
            long cutoffMs = System.currentTimeMillis() - Config.keepHistoryDays * 24L * 3600L * 1000L;
            int removed = NetworkInfoDataStore.get(overworld)
                .cleanupStale(cutoffMs);
            if (removed > 0) {
                GTSimpleWirelessNetwork.LOG
                    .info("[serverStarted] 已清理 " + removed + " 个超过 " + Config.keepHistoryDays + " 天未采样的网络信息屏数据集");
            }
        } catch (Throwable t) {
            GTSimpleWirelessNetwork.LOG.error("[serverStarted] 清理网络信息屏历史数据时发生错误", t);
        }
    }

    /**
     * 模组加载完成阶段
     * 如果之前注册失败，可以在此处进行最后的补救尝试。
     */
    public void loadComplete(cpw.mods.fml.common.event.FMLLoadCompleteEvent event) {}

    /**
     * 【hotfix v1.5.14】处理服务端→客户端 EU 响应包（客户端专用逻辑）。
     * <p>
     * 服务端空实现：此包只发往客户端，服务端收到也不会调用本方法。
     * 客户端逻辑由 {@link ClientProxy#handleResponseEU} 重写。
     * <p>
     * 【为什么这样设计】原 {@code PacketResponseWirelessEU.Handler} 直接调用
     * {@code Minecraft.getMinecraft().func_152344_a(...)}，虽然 Minecraft 类本身无 @SideOnly 注解
     * 当前不崩溃，但为了一致性和健壮性，统一通过 @SidedProxy 委托，避免 Handler 类方法体
     * 引用客户端 API。
     *
     * @param euStr 服务端传来的 EU 字符串
     */
    public void handleResponseEU(String euStr) {
        // 服务端空实现：此包只发往客户端
    }

    /**
     * 【hotfix v1.5.14】处理服务端→客户端 AE 监控数据同步包（客户端专用逻辑）。
     * <p>
     * 服务端空实现：此包只发往客户端，服务端收到也不会调用本方法。
     * 客户端逻辑由 {@link ClientProxy#handleSyncAEMonitorData} 重写。
     * <p>
     * 【为什么这样设计】原 {@code PacketSyncAEMonitorData.Handler} 直接调用
     * {@code Minecraft.getMinecraft().theWorld}，而 {@code Minecraft.theWorld} 字段
     * 声明类型是 {@code WorldClient}（@SideOnly(Side.CLIENT)）。在 Java 25 JVM + Forge 1.7.10 下，
     * {@code SimpleNetworkWrapper.registerMessage} 调用 {@code Handler.class.newInstance()}
     * 会触发 {@code getDeclaredConstructors0()} 解析方法体引用类型，导致 WorldClient 被加载，
     * 被 SideTransformer 拒绝，抛出 NoClassDefFoundError 崩服。
     * 通过 @SidedProxy 委托，Handler 类方法体不再引用任何客户端类，彻底避免类加载触发。
     *
     * @param msg AE 监控数据同步包
     */
    public void handleSyncAEMonitorData(PacketSyncAEMonitorData msg) {
        // 服务端空实现：此包只发往客户端
    }

    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
