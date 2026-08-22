package com.miaokatze.gtswn.network;

import java.util.List;
import java.util.Map;

import net.minecraft.world.World;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.PanelBroadcastPort;

import cpw.mods.fml.common.network.NetworkRegistry;

/**
 * {@link PanelBroadcastPort} 的 network 侧实现（B07 = O2-B07 拆环后半）：
 * tile→network 依赖反转的注入端，信息屏推送经本类走 {@code GTSWNPacketHandler.NETWORK} 通道。
 * <p>
 * 包体构造与 TargetPoint（维度取 world、中心点 +0.5D、半径 64D）自 TE 原内联推送逐字搬迁，
 * 线上格式与推送范围零变化。
 */
public final class NetworkPanelBroadcastPort implements PanelBroadcastPort {

    @Override
    public void broadcastAEMonitorData(World world, int x, int y, int z, String chartKey,
        List<AEMonitorSample> chartSamples, Map<String, AEMonitorSample> monitorLatest,
        Map<String, Double> monitorAvg300s) {
        GTSWNPacketHandler.NETWORK.sendToAllAround(
            new PacketSyncAEMonitorData(x, y, z, chartKey, chartSamples, monitorLatest, monitorAvg300s),
            new NetworkRegistry.TargetPoint(world.provider.dimensionId, x + 0.5D, y + 0.5D, z + 0.5D, 64.0D));
    }
}
