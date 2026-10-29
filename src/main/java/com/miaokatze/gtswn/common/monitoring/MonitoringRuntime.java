package com.miaokatze.gtswn.common.monitoring;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.monitoring.web.ReadonlyWebService;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;
import com.miaokatze.gtswn.network.telemetry.TelemetryTransport;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Main-thread lifecycle adapter; HTTP code receives only authorized immutable copies. */
public final class MonitoringRuntime {

    private static long ticks;

    public static void start() {
        ticks = 0;
        TelemetryTransport.configure(
            Config.monitorPlayerBytesPerSecond,
            Config.monitorGlobalBytesPerSecond,
            Config.monitorPendingPerPlayer);
        try {
            ReadonlyWebService.configure(
                new ReadonlyWebService.Settings(
                    Config.monitorWebEnabled,
                    Config.monitorWebBindAddress,
                    Config.monitorWebPort,
                    Config.monitorWebMaxDashboards,
                    Config.monitorWebTokenHashEnvironment,
                    Config.monitorWebBytesPerSecond));
            ReadonlyWebService.start();
        } catch (java.io.IOException | IllegalArgumentException failure) {
            ReadonlyWebService.stop();
            GTSimpleWirelessNetwork.LOG.error(
                "Read-only monitoring web listener was not started; verify loopback configuration and operator authentication setup.");
        }
    }

    public static void stop() {
        ReadonlyWebService.stop();
        TelemetryTransport.clear();
        MonitorCapabilities.clear();
        com.miaokatze.gtswn.common.monitoring.ae.MonitorIdentityGridCache.clearSession();
        com.miaokatze.gtswn.common.device.DeviceSampleScheduler.clearSession();
        com.miaokatze.gtswn.common.panel.NetworkInfoMonitorScheduler.clearSession();
        com.miaokatze.gtswn.network.C2SPacketMonitorProfileAction.ServerQueue.clear();
        com.miaokatze.gtswn.common.quantum.QuantumIncorporationVisualSync.clear();
        com.miaokatze.gtswn.network.NetworkPanelBroadcastPort.clear();
        com.miaokatze.gtswn.network.PortableMonitorRequestQueue.clear();
        ticks = 0;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ReadonlyWebService.isRunning()) return;
        MinecraftServer server = MinecraftServer.getServer();
        World world = server == null ? null : server.worldServerForDimension(0);
        if (world == null) {
            ReadonlyWebService.publish(java.util.Collections.emptyList());
            return;
        }
        MonitoringProfiles profiles = MonitoringProfiles.get(world);
        // Only OP-selected dashboard definitions can create publication work or invalidate published pages.
        com.miaokatze.gtswn.common.monitoring.web.WebDashboardRegistry dashboards = com.miaokatze.gtswn.common.monitoring.web.WebDashboardRegistry
            .get(world);
        dashboards.revalidate(profiles);
        if (++ticks % 40 == 0) ReadonlyWebService.publish(dashboards.exportSnapshots(profiles));
    }
}
