package com.miaokatze.gtswn.main;

import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.GTswn_Cover_Dynamo_Wireless;
import static com.miaokatze.gtswn.common.api.enums.GTSWNItemList.GTswn_Cover_Energy_Wireless;

import java.io.File;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;

import com.miaokatze.gtswn.Tags;
import com.miaokatze.gtswn.common.command.CommandGTSWN;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.gui.GTSWNGuiHandler;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.panel.NetworkInfoMonitorScheduler;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.common.quantum.QuantumChunkLoaderCallback;
import com.miaokatze.gtswn.common.quantum.QuantumControllerEventHandler;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.crossmod.waila.WailaIntegration;
import com.miaokatze.gtswn.loader.ItemLoader;
import com.miaokatze.gtswn.loader.MachineLoader;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.NetworkPanelBroadcastPort;
import com.miaokatze.gtswn.network.PacketSyncAEMonitorData;
import com.miaokatze.gtswn.network.PacketSyncQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSyncQuantumTerminalDataLite;
import com.miaokatze.gtswn.network.PanelActionQueue;
import com.miaokatze.gtswn.recipe.CraftingRecipes;
import com.miaokatze.gtswn.register.CreativeTabManager;
import com.miaokatze.gtswn.register.TextureManager;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
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
        // v1.6.19：性能审计开关（preInit 配置读取后设置，重启生效；关闭时完全静默）
        PerformanceAudit.setEnabled(Config.performanceAuditEnabled);
        // v1.6.20：性能审计报告周期（分钟；preInit 配置读取后设置，重启生效）
        PerformanceAudit.setReportIntervalMinutes(Config.performanceAuditIntervalMinutes);

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

        // 将机器注册任务加入 GregTechAPI.sAfterGTPreload 队列
        // 由 GT5U 在其 PreInit 主体末尾（GTMod.java 行 342-344，紧接 LoaderMetaTileEntities.run 之后）代为触发
        // 这是 GT5U 生态中第三方 mod 注册机器的推荐做法（参考 GigaGramFab.java 行 65-98）
        // 前置条件：@Mod 注解声明 required-before:gregtech，确保本 mod 的 preInit 在 GT preInit 之前执行
        GregTechAPI.sAfterGTPreload.add(registerRunnable);
        GTSimpleWirelessNetwork.LOG.info("[1/3] 已将机器注册任务加入 GregTech sAfterGTPreload 队列。");

        // 注册网络包通道（便携监测终端 EU 同步：修复客户端恒显示 0EU 的 Bug）
        GTSWNPacketHandler.register();
        // B07（O2-B07，吸收 B2-01）：面板操作队列自宿主 tick 监听注册——包 2/3 世界态修改
        // 由 ServerTickEvent(END) 主线程排空（与通道注册同址，队列即 network→tile 唯一受控调用点）
        PanelActionQueue.register();
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

        // 注册无线 EU 监控调度器到 FMLCommonHandler 事件总线（server tick）
        // 调度器在 ServerTickEvent END phase 每 100t 统一采样所有活跃玩家的 EU 数据
        // （v1.5.17 起调度器自驱，无 panel 侧静态字段注入——原 setMonitorScheduler 死代码已随 OPT-5 清理）
        NetworkInfoMonitorScheduler scheduler = new NetworkInfoMonitorScheduler();
        FMLCommonHandler.instance()
            .bus()
            .register(scheduler);
        GTSimpleWirelessNetwork.LOG.info("[2/3] 无线 EU 监控调度器已注册到事件总线。");

        // B07（O2-B07 拆环后半）：注入 network 侧广播端口——TEPanel 推送不再直引 GTSWNPacketHandler，
        // tile→network 依赖归零（推送域 O2-04 拆出后只持端口，O2-22/23 广播裁剪在端口实现内落点）
        TileEntityNetworkInfoPanel.setBroadcastPort(new NetworkPanelBroadcastPort());
        GTSimpleWirelessNetwork.LOG.info("[2/3] 信息屏广播端口已注入（tile→network 拆环闭合）。");

        // 注册量子化控制器事件处理器（T2）：
        // - Forge 事件总线：右键拦截 / 挖掘减速 / 邻接通知 / 破坏出册
        // - FML 事件总线：ServerTickEvent 每秒巡检（连接过滤兜底 + D8 自动合并）
        // 同一实例注册两条总线，内部冷却表/巡检 tick 计数状态共享
        QuantumControllerEventHandler quantumHandler = new QuantumControllerEventHandler();
        MinecraftForge.EVENT_BUS.register(quantumHandler);
        FMLCommonHandler.instance()
            .bus()
            .register(quantumHandler);
        GTSimpleWirelessNetwork.LOG.info("[2/3] 量子化控制器事件处理器已注册到双事件总线。");

        // v1.6.19：注册性能审计 tick 结算监听（ServerTickEvent END；开关关闭时完全静默）
        FMLCommonHandler.instance()
            .bus()
            .register(new PerformanceAudit.ServerTickListener());
        GTSimpleWirelessNetwork.LOG.info("[2/3] 性能审计 tick 结算监听已注册到事件总线。");

        // v1.6.2：WAILA 软集成——检测到 WAILA 才经 IMC 注册量子节点状态显示，无 WAILA 不影响运行
        if (Loader.isModLoaded("Waila")) {
            WailaIntegration.init();
            GTSimpleWirelessNetwork.LOG.info("[2/3] 检测到 WAILA，量子节点状态显示已注册。");
        }

        // 注册旧版量子节点 ForgeChunkManager Ticket 清理 callback。
        // 必须在 init 阶段（ForgeChunkManager 已就绪、worlds 尚未加载）注册，
        // 服务器重启加载旧 forcedchunks.dat 时 callback.ticketsLoaded 会释放遗留 Ticket；
        // 当前量子节点不再申请或主动强制加载区块。
        ForgeChunkManager
            .setForcedChunkLoadingCallback(GTSimpleWirelessNetwork.instance, new QuantumChunkLoaderCallback());
        GTSimpleWirelessNetwork.LOG.info("[2/3] 量子节点旧版 Ticket 清理 callback 已注册。");
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

    /**
     * 处理服务端→客户端 量子终端数据同步包（客户端专用逻辑）。
     * <p>
     * 服务端空实现：此包只发往客户端，服务端收到也不会调用本方法。
     * 客户端逻辑由 {@link ClientProxy#handleSyncQuantumTerminalData} 重写。
     * <p>
     * 【为什么这样设计】与 {@link #handleSyncAEMonitorData} 相同的 hotfix v1.5.14 模式：
     * 包 Handler 方法体不得引用 @SideOnly(Side.CLIENT) 客户端类（如 Minecraft/GuiScreen），
     * 否则 registerMessage 时类加载解析会在服务端被 SideTransformer 拒绝崩服。
     * 统一经 @SidedProxy 委托，Handler 只引用双端类型。
     *
     * @param msg 量子终端数据同步包
     */
    public void handleSyncQuantumTerminalData(PacketSyncQuantumTerminalData msg) {
        // 服务端空实现：此包只发往客户端
    }

    /**
     * 处理服务端→客户端 量子终端短回包（disc 7，O2-17，客户端专用逻辑）。
     * <p>
     * 服务端空实现：此包只发往客户端。客户端逻辑由
     * {@link ClientProxy#handleSyncQuantumTerminalDataLite} 重写。
     * 设计与 {@link #handleSyncQuantumTerminalData} 相同的 hotfix v1.5.14
     * 类加载安全模式（Handler 只引用双端类型，经 @SidedProxy 委托）。
     *
     * @param msg 量子终端数据短回包（仅 9 字段有效）
     */
    public void handleSyncQuantumTerminalDataLite(PacketSyncQuantumTerminalDataLite msg) {
        // 服务端空实现：此包只发往客户端
    }

    public void openQuantumTerminalGui() {}

    public void openNetworkInfoPanelGui(TileEntityNetworkInfoPanel panel) {}

    /**
     * HUD 模式切换意图（O2-B10：items→hud 拆环，物品类不再字节码引用客户端类
     * {@code WirelessMonitorHUD}，类加载安全由「不执行即不解析」惯例升级为 @SidedProxy 结构保证）。
     * <p>
     * 服务端空实现：HUD 仅客户端存在。客户端逻辑由 {@link ClientProxy#toggleHudMode} 重写，
     * 与 {@link #handleResponseEU} 等同用 @SidedProxy 委托范式（包 1/4/6 同源）。
     *
     * @param mode      新 HUD 显示模式（0=关闭，1=常规计数，2=科学计数）
     * @param ownerUUID 拥有者 UUID（可为 null/空，语义同原静态 setter：空值不更新 owner 缓存）
     */
    public void toggleHudMode(int mode, String ownerUUID) {
        // 服务端空实现：HUD 仅客户端
    }

    public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
        return null;
    }
}
