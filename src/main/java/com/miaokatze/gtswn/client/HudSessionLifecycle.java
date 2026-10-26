package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.world.WorldEvent;

import com.miaokatze.gtswn.common.hud.HudController;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** Clears client HUD state even while rendering is paused or no world is being drawn. */
public final class HudSessionLifecycle {

    private final HudController controller;
    private final com.miaokatze.gtswn.client.engineering.DisplayContinuity.Session displaySession = new com.miaokatze.gtswn.client.engineering.DisplayContinuity.Session();

    public HudSessionLifecycle(HudController controller) {
        this.controller = controller;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        Object connection = mc.getNetHandler() == null ? null
            : mc.getNetHandler()
                .getNetworkManager();
        if (displaySession.update(mc.theWorld, mc.thePlayer, connection)) clearDisplays();
        if (controller.synchronizeSession(mc.theWorld, mc.thePlayer, connection))
            PortableMonitorClientCache.resetForHudSession();
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        // Integrated-server unloads and late unloads from an earlier world do not own this client session.
        if (event.world.isRemote && displaySession.ownsWorld(event.world)) {
            clearDisplays();
            displaySession.clear();
        }
        if (event.world.isRemote && controller.unloadWorld(event.world))
            PortableMonitorClientCache.resetForHudSession();
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Object connection = event.manager;
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                if (displaySession.ownsConnection(connection)) {
                    clearDisplays();
                    displaySession.clear();
                }
                if (controller.disconnect(connection)) PortableMonitorClientCache.resetForHudSession();
            });
    }

    private void clearDisplays() {
        com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard.resetSession();
        com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot.resetSession();
        com.miaokatze.gtswn.client.engineering.EngineeringDashboardCache.resetSession();
        com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources.resetSession();
        PortableMonitorClientCache.resetForHudSession();
        DeviceTerminalClientCache.clear();
    }
}
