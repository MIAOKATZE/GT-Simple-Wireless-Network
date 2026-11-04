package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private static Object connection;

    private PanelHistoryClientCache() {}

    public static void clear() {
        CACHE.clear();
        world = null;
        connection = null;
    }

    public static void receive(PacketSyncPanelHistory message) {
        Minecraft mc = Minecraft.getMinecraft();
        session(
            mc.theWorld,
            mc.getNetHandler() == null ? null
                : mc.getNetHandler()
                    .getNetworkManager());
        if (mc.theWorld == null || !mc.theWorld.blockExists(message.x, message.y, message.z)) return;
        TileEntity target = mc.theWorld.getTileEntity(message.x, message.y, message.z);
        if (!(target instanceof TileEntityNetworkInfoPanel)) return;
        long ack = receive((TileEntityNetworkInfoPanel) target, message);
        if (ack >= 0) GTSWNPacketHandler.NETWORK.sendToServer(new PacketAckPanelHistory(message, ack));
    }

    static void session(Object nextWorld, Object nextConnection) {
        if (world != nextWorld || connection != nextConnection) {
            CACHE.clear();
            world = nextWorld;
            connection = nextConnection;
        }
    }

    /** Returns the history ACK, or -1 when this screen/sequence does not accept the message. */
    static long receive(TileEntityNetworkInfoPanel panel, PacketSyncPanelHistory message) {
        if (!message.panelId.equals(panel.getPortableMonitorId())) return -1;
        State old = CACHE.get(panel);
        boolean sameStream = old != null && old.session.equals(message.sessionId)
            && old.panelId.equals(message.panelId);
        if (sameStream && message.sequence <= old.sequence) return -1;
        boolean sameEU = sameStream && old.euSource.equals(message.history.euSource);
        boolean sameAESource = sameStream && old.aeSource.equals(message.history.aeSource);
        boolean sameAE = sameAESource && old.chartKey.equals(message.history.chartKey);
        PacketSyncPortableMonitor.Panel base = sameStream ? old.panel : null;
        PacketSyncPortableMonitor.Panel next = new PacketSyncPortableMonitor.Panel();
        next.renderVersion = message.history.targetVersion;
        next.currentEnergy = message.energy;
        boolean historyApplied = message.history.apply(next, base);
        NBTTagCompound eu = new NBTTagCompound();
        eu.setString("cachedEu", message.energy);
        eu.setDouble("cachedEut", message.power);
        eu.setString("cachedStatus", message.status);
        if (historyApplied || !sameEU) {
            eu.setLong("euSyncSeq", message.sequence);
            eu.setInteger("euSyncKind", 0);
            NBTTagList samples = new NBTTagList();
            if (historyApplied) {
                for (NetworkInfoSample sample : next.render.eu) samples.appendTag(sample.toNBT());
            }
            eu.setTag("samples", samples);
        }
        panel.receiveCompactMonitorData(
            eu,
            historyApplied ? next.render.config : null,
            message.aePresent,
            historyApplied ? next.render.ae
                : sameAE ? new ArrayList<>(panel.getAEChartSamples()) : Collections.emptyList(),
            message.latest,
            message.averages);
        if (!message.aePresent && !sameAE) {
            // A chart selection alone keeps same-source rows; a new source/session invalidates them too.
            panel.receiveAEMonitorData(
                Collections.emptyList(),
                sameAESource ? new HashMap<>(panel.getAEMonitorLatest()) : Collections.emptyMap(),
                sameAESource ? new HashMap<>(panel.getAEMonitorAvg300s()) : Collections.emptyMap());
        }
        // Live rows are complete snapshots. A missing history base only requests a full chart;
        // retain the accepted live sequence so delayed packets cannot roll the display back.
        CACHE.put(panel, new State(message, historyApplied ? next : null));
        return historyApplied ? message.sequence : 0;
    }

    private static final class State {

        final UUID panelId;
        final UUID session;
        final long sequence;
        final String euSource, aeSource, chartKey;
        final PacketSyncPortableMonitor.Panel panel;

        State(PacketSyncPanelHistory message, PacketSyncPortableMonitor.Panel panel) {
            panelId = message.panelId;
            session = message.sessionId;
            sequence = message.sequence;
            euSource = message.history.euSource;
            aeSource = message.history.aeSource;
            chartKey = message.history.chartKey;
            this.panel = panel;
        }
    }
}
