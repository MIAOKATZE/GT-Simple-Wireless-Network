package com.miaokatze.gtswn.common.panel;

import java.util.List;
import java.util.Map;

import net.minecraft.world.World;

/**
 * 信息屏广播端口（B07 = O2-B07 拆环后半）：common.tile → network 的反向边经本接口反转。
 * <p>
 * {@code TileEntityNetworkInfoPanel} 不再 import {@code GTSWNPacketHandler} /
 * {@code PacketSyncAEMonitorData}，AE 监控数据推送改经本端口；由 network 侧实现类
 * （{@code NetworkPanelBroadcastPort}）在注册期注入（CommonProxy.init 一行），
 * 推送域（O2-04 拆出后）只持本端口，O2-22/23 广播裁剪在本端口实现内落点。
 */
public interface PanelBroadcastPort {

    /**
     * 向信息屏周围 64 格客户端推送 AE 监控数据（原 TE 内联 sendToAllAround + PacketSyncAEMonitorData，
     * 中心点 +0.5D 与 64D 半径逐字一致）。
     *
     * @param world          服务端世界（取推送维度）
     * @param x              信息屏 X 坐标
     * @param y              信息屏 Y 坐标
     * @param z              信息屏 Z 坐标
     * @param chartKey       走势图绑定 key（null = 无绑定或离线空推送）
     * @param chartSamples   走势图样本列表
     * @param monitorLatest  监控列表最新值（key → 最新样本）
     * @param monitorAvg300s 监控列表 300s 平均变化率（key → 每分钟数量变化）
     */
    void broadcastAEMonitorData(World world, int x, int y, int z, String chartKey, List<AEMonitorSample> chartSamples,
        Map<String, AEMonitorSample> monitorLatest, Map<String, Double> monitorAvg300s);
}
