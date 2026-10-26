package com.miaokatze.gtswn.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Exact, bounded metadata dictionary. Row order is part of identity, never inferred from a hash. */
public final class PortablePanelMetadata {

    private PortablePanelMetadata() {}

    static byte[] identity(PacketSyncPortableMonitor.Panel panel) {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeInt(panel.binding.dimension);
            buf.writeInt(panel.binding.x);
            buf.writeInt(panel.binding.y);
            buf.writeInt(panel.binding.z);
            buf.writeLong(panel.binding.panelId.getMostSignificantBits());
            buf.writeLong(panel.binding.panelId.getLeastSignificantBits());
            PacketSyncPortableMonitor.writeString(buf, panel.ownerName, 128);
            PacketSyncPortableMonitor.writeString(buf, panel.window, 32);
            PacketSyncPortableMonitor.writeString(buf, panel.aeWindow, 32);
            PacketSyncPortableMonitor.writeString(buf, panel.chartKey, 256);
            buf.writeByte(panel.tab);
            buf.writeByte(panel.displayMode);
            buf.writeBoolean(panel.palette.enabled);
            buf.writeInt(panel.palette.energyFlat);
            buf.writeInt(panel.palette.energyUp);
            buf.writeInt(panel.palette.energyDown);
            buf.writeInt(panel.palette.rateFlat);
            buf.writeInt(panel.palette.rateUp);
            buf.writeInt(panel.palette.rateDown);
            buf.writeBoolean(panel.detail);
            buf.writeByte(panel.state);
            buf.writeByte(panel.historyFields);
            if (panel.historyPatch != null) {
                PacketSyncPortableMonitor.writeString(buf, panel.historyPatch.euSource, 256);
                PacketSyncPortableMonitor.writeString(buf, panel.historyPatch.aeSource, 256);
            }
            buf.writeShort(panel.rows.size());
            for (PacketSyncPortableMonitor.Row row : panel.rows) {
                PacketSyncPortableMonitor.writeString(buf, row.key, 256);
                PacketSyncPortableMonitor.writeString(buf, row.name, 128);
                buf.writeByte(row.kind);
                buf.writeBoolean(row.monitored);
                PortableMonitorIcon.write(buf, row.icon);
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /** Build a detached legacy-layout container with acknowledged dictionary fields omitted. */
    static PacketSyncPortableMonitor.Panel wire(PacketSyncPortableMonitor.Panel source) {
        if (!source.metadataUnchanged) return source;
        PacketSyncPortableMonitor.Panel target = new PacketSyncPortableMonitor.Panel();
        target.binding = source.binding;
        target.state = source.state;
        target.currentEnergy = source.currentEnergy;
        target.aeConnected = source.aeConnected;
        target.detail = source.detail;
        target.sampleTime = source.sampleTime;
        target.renderVersion = source.renderVersion;
        for (PacketSyncPortableMonitor.Row row : source.rows) {
            PacketSyncPortableMonitor.Row live = new PacketSyncPortableMonitor.Row();
            live.kind = row.kind;
            live.amount = row.amount;
            live.rate = row.rate;
            live.average = row.average;
            target.rows.add(live);
        }
        return target;
    }

    /** Missing or reordered dictionaries cause an atomic full reset, never blank names or row-index aliasing. */
    public static boolean restore(PacketSyncPortableMonitor.Panel target, PacketSyncPortableMonitor.Panel base) {
        if (!target.metadataUnchanged) return target.metadataVersion > 0;
        if (base == null || base.binding == null
            || target.binding == null
            || !base.binding.panelId.equals(target.binding.panelId)
            || base.binding.dimension != target.binding.dimension
            || base.binding.x != target.binding.x
            || base.binding.y != target.binding.y
            || base.binding.z != target.binding.z
            || base.metadataVersion != target.metadataVersion
            || base.rows.size() != target.rows.size()
            || base.state != target.state
            || base.detail != target.detail) return false;
        for (int i = 0; i < target.rows.size(); i++) if (target.rows.get(i).kind != base.rows.get(i).kind) return false;
        target.ownerName = base.ownerName;
        target.window = base.window;
        target.aeWindow = base.aeWindow;
        target.chartKey = base.chartKey;
        target.tab = base.tab;
        target.displayMode = base.displayMode;
        target.palette = base.palette;
        for (int i = 0; i < target.rows.size(); i++) {
            PacketSyncPortableMonitor.Row row = target.rows.get(i), old = base.rows.get(i);
            row.key = old.key;
            row.name = old.name;
            row.icon = old.icon;
            row.monitored = old.monitored;
        }
        target.metadataUnchanged = false;
        return true;
    }
}
