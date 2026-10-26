package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Sent only after the server confirms PORTABLE capability; protocol 60 remains an independent fallback. */
public final class PacketRequestPortableHistory implements IMessage {

    private static final int MAGIC = 0x50575231;
    public PacketRequestPortableMonitor request;
    public boolean valid;

    public PacketRequestPortableHistory() {}

    public PacketRequestPortableHistory(PacketRequestPortableMonitor request) {
        this.request = request;
        valid = request.valid;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(MAGIC);
        buf.writeLong(request.knownRenderVersion);
        buf.writeLong(request.knownConfigVersion);
        buf.writeByte(PacketSyncPortableMonitor.count(request.historyCursors.size(), 16));
        for (java.util.Map.Entry<java.util.UUID, PacketRequestPortableMonitor.HistoryCursor> entry : request.historyCursors
            .entrySet()) {
            buf.writeLong(
                entry.getKey()
                    .getMostSignificantBits());
            buf.writeLong(
                entry.getKey()
                    .getLeastSignificantBits());
            buf.writeLong(entry.getValue().version);
            buf.writeLong(entry.getValue().configVersion);
            buf.writeByte(entry.getValue().fields);
        }
        buf.writeByte(PacketSyncPortableMonitor.count(request.metadataVersions.size(), 16));
        for (java.util.Map.Entry<java.util.UUID, Long> entry : request.metadataVersions.entrySet()) {
            buf.writeLong(
                entry.getKey()
                    .getMostSignificantBits());
            buf.writeLong(
                entry.getKey()
                    .getLeastSignificantBits());
            buf.writeLong(entry.getValue());
        }
        request.toBytes(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        if (buf.readableBytes() < 102 || buf.readableBytes() > PacketRequestPortableMonitor.MAX_BYTES + 934) return;
        try {
            if (buf.readInt() != MAGIC) return;
            long history = buf.readLong(), config = buf.readLong();
            if (history < 0 || config < 0) return;
            PacketRequestPortableMonitor decoded = new PacketRequestPortableMonitor();
            int count = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 16);
            for (int i = 0; i < count; i++) {
                java.util.UUID id = new java.util.UUID(buf.readLong(), buf.readLong());
                long version = buf.readLong(), configVersion = buf.readLong();
                int fields = buf.readUnsignedByte();
                if (version <= 0 || configVersion <= 0
                    || fields < 1
                    || fields > 3
                    || decoded.historyCursors.containsKey(id)) return;
                decoded.historyCursors
                    .put(id, new PacketRequestPortableMonitor.HistoryCursor(version, configVersion, fields));
            }
            count = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 16);
            for (int i = 0; i < count; i++) {
                java.util.UUID id = new java.util.UUID(buf.readLong(), buf.readLong());
                long revision = buf.readLong();
                if (revision <= 0 || decoded.metadataVersions.put(id, revision) != null) return;
            }
            decoded.fromBytes(buf);
            if (!decoded.valid || decoded.knownRenderVersion != history) return;
            decoded.knownConfigVersion = config;
            decoded.rawHistory = true;
            request = decoded;
            valid = true;
        } catch (RuntimeException malformed) {
            request = null;
        }
    }

    public static final class Handler implements IMessageHandler<PacketRequestPortableHistory, IMessage> {

        @Override
        public IMessage onMessage(PacketRequestPortableHistory message, MessageContext ctx) {
            if (message.valid && MonitorCapabilities
                .serverSupports(ctx.getServerHandler().playerEntity, MonitorCapabilities.PORTABLE))
                PortableMonitorRequestQueue.enqueue(ctx.getServerHandler().playerEntity, message.request);
            return null;
        }
    }
}
