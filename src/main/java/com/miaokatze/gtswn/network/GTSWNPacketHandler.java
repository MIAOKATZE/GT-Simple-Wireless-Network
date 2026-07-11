package com.miaokatze.gtswn.network;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * GTSWN 网络包通道与注册中心。
 * <p>
 * 用于修复「便携无线监测终端在服务器中始终显示 0EU」的 Bug：
 * GlobalEnergy 数据仅存在于服务端，客户端直接调用 {@code WirelessNetworkManager.getUserEU} 恒返 0，
 * 故改为客户端周期性发请求包、服务端查询后回响应包的 C→S→C 模式。
 * <p>
 * discriminator 分配约定：
 * <ul>
 * <li>0 = {@link PacketRequestWirelessEU}（C→S 请求 EU）</li>
 * <li>1 = {@link PacketResponseWirelessEU}（S→C 响应 EU）</li>
 * <li>2 = {@link PacketUpdateNetworkInfoPanelConfig}（C→S 信息屏配置）</li>
 * <li>3 = {@link PacketUpdateAETabState}（C→S AE 标签页+监视列表）</li>
 * <li>4 = {@link PacketSyncAEMonitorData}（S→C AE 监控数据同步）</li>
 * </ul>
 */
public class GTSWNPacketHandler {

    /** 模组网络通道名（与 modid 保持一致便于识别） */
    public static final SimpleNetworkWrapper NETWORK = NetworkRegistry.INSTANCE.newSimpleChannel("gtswn");

    /**
     * 注册所有网络包。需在 {@code CommonProxy.preInit} 阶段调用一次。
     * <p>
     * 注册顺序即 discriminator 编号，勿随意调整已占用编号以免协议失配。
     */
    public static void register() {
        // 0: 客户端→服务端 请求 EU
        NETWORK.registerMessage(PacketRequestWirelessEU.Handler.class, PacketRequestWirelessEU.class, 0, Side.SERVER);
        // 1: 服务端→客户端 响应 EU
        NETWORK.registerMessage(PacketResponseWirelessEU.Handler.class, PacketResponseWirelessEU.class, 1, Side.CLIENT);
        NETWORK.registerMessage(
            PacketUpdateNetworkInfoPanelConfig.Handler.class,
            PacketUpdateNetworkInfoPanelConfig.class,
            2,
            Side.SERVER);
        // 3: 客户端→服务端 AE 标签页切换+监视列表操作
        NETWORK.registerMessage(PacketUpdateAETabState.Handler.class, PacketUpdateAETabState.class, 3, Side.SERVER);
        // 4: 服务端→客户端 AE 监控数据同步
        NETWORK.registerMessage(PacketSyncAEMonitorData.Handler.class, PacketSyncAEMonitorData.class, 4, Side.CLIENT);
    }
}
