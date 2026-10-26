package com.miaokatze.gtswn.client;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketAckPanelHistory;
import com.miaokatze.gtswn.network.PacketSyncPanelHistory;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

/**
 * Client-main-thread physical panel baseline. A missing chunk never causes a world lookup/load outside loaded chunks.
 */
public final class PanelHistoryClientCache {

    private static final Map<TileEntityNetworkInfoPanel, State> CACHE = new WeakHashMap<>();
    private static Object world;

    private PanelHistoryClientCache() {}

    public static void clear() {
        CACHE.clear();
        world = null;
    }

    public static void receive(PacketSyncPanelHistory message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld) {
            CACHE.clear();
            world = mc.theWorld;
        }
        if (mc.theWorld == null || !mc.theWorld.blockExists(message.x, message.y, message.z)) return;
        TileEntity target = mc.theWorld.getTileEntity(message.x, message.y, message.z);
        if (!(target instanceof TileEntityNetworkInfoPanel)) return;
        TileEntityNetworkInfoPanel panel = (TileEntityNetworkInfoPanel) target;
        if (!message.panelId.equals(panel.getPortableMonitorId())) return;
        State old = CACHE.get(panel);
        if (old != null && old.session.equals(message.sessionId) && message.sequence <= old.sequence) return;
        PacketSyncPortableMonitor.Panel base = old != null && old.session.equals(message.sessionId) ? old.panel : null;
        PacketSyncPortableMonitor.Panel next = new PacketSyncPortableMonitor.Panel();
        next.renderVersion = message.history.targetVersion;
        next.currentEnergy = message.energy;
        if (!message.history.apply(next, base)) {
            CACHE.remove(panel);
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketAckPanelHistory(message, 0));
            return;
        }
        NBTTagCompound eu = new NBTTagCompound();
        eu.setString("cachedEu", message.energy);
        eu.setDouble("cachedEut", message.power);
        eu.setString("cachedStatus", message.status);
        eu.setLong("euSyncSeq", message.sequence);
        eu.setInteger("euSyncKind", 0);
        NBTTagList samples = new NBTTagList();
        for (NetworkInfoSample sample : next.render.eu) samples.appendTag(sample.toNBT());
        eu.setTag("samples", samples);
        panel.receiveCompactMonitorData(
            eu,
            next.render.config,
            message.aePresent,
            next.render.ae,
            message.latest,
            message.averages);
        CACHE.put(panel, new State(message.sessionId, message.sequence, next));
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketAckPanelHistory(message, message.sequence));
    }

    private static final class State {

        final UUID session;
        final long sequence;
        final PacketSyncPortableMonitor.Panel panel;

        State(UUID session, long sequence, PacketSyncPortableMonitor.Panel panel) {
            this.session = session;
            this.sequence = sequence;
            this.panel = panel;
        }
    }
}
