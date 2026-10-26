package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Explicit opt-in discriminator; the protocol-60 request/response layout is never changed. */
public final class PacketSyncPortableHistory implements IMessage {

    private static final int MAGIC = 0x50574831;
    public PacketSyncPortableMonitor snapshot;
    public boolean valid;

    public PacketSyncPortableHistory() {}

    public PacketSyncPortableHistory(PacketSyncPortableMonitor snapshot) {
        this.snapshot = snapshot;
        this.valid = true;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        int packetStart = buf.writerIndex();
        buf.writeInt(MAGIC);
        int lengthOffset = buf.writerIndex();
        buf.writeInt(0);
        int start = buf.writerIndex();
        PacketSyncPortableMonitor wire = new PacketSyncPortableMonitor(
            snapshot.terminalId,
            snapshot.sequence,
            snapshot.hudMode);
        wire.bindingRevision = snapshot.bindingRevision;
        wire.result = snapshot.result;
        for (PacketSyncPortableMonitor.Panel panel : snapshot.panels)
            wire.panels.add(PortablePanelMetadata.wire(panel));
        wire.toBytes(buf);
        buf.setInt(lengthOffset, buf.writerIndex() - start);
        for (PacketSyncPortableMonitor.Panel panel : snapshot.panels) {
            buf.writeLong(panel.metadataVersion);
            buf.writeBoolean(panel.metadataUnchanged);
            buf.writeByte(panel.historyFields);
            buf.writeBoolean(panel.historyPatch != null);
            if (panel.historyPatch != null) panel.historyPatch.write(buf);
            for (PacketSyncPortableMonitor.Row row : panel.rows) {
                buf.writeLong(row.sampleTime);
                buf.writeLong(row.sampleTick);
            }
        }
        if (buf.writerIndex() - packetStart > PacketSyncPortableMonitor.MAX_BYTES)
            throw new IllegalArgumentException("Portable raw response bound");
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        snapshot = null;
        if (buf.readableBytes() < 8 || buf.readableBytes() > PacketSyncPortableMonitor.MAX_BYTES) return;
        try {
            if (buf.readInt() != MAGIC) return;
            int length = buf.readInt();
            if (length < 39 || length > buf.readableBytes()) return;
            PacketSyncPortableMonitor decoded = new PacketSyncPortableMonitor();
            decoded.fromBytes(buf.readSlice(length));
            if (!decoded.valid) return;
            for (PacketSyncPortableMonitor.Panel panel : decoded.panels) {
                if (panel.render != null || panel.chartsUnchanged
                    || panel.euChart.length != 0
                    || panel.aeChart.length != 0
                    || panel.euRateChart.length != 0
                    || panel.aeRateChart.length != 0) return;
                panel.metadataVersion = buf.readLong();
                panel.metadataUnchanged = buf.readBoolean();
                if (panel.metadataVersion <= 0) return;
                panel.historyFields = PacketSyncPortableMonitor.count(buf.readUnsignedByte(), 3);
                if (buf.readBoolean()) panel.historyPatch = PortableHistoryPatch.read(buf);
                for (PacketSyncPortableMonitor.Row row : panel.rows) {
                    row.sampleTime = buf.readLong();
                    row.sampleTick = buf.readLong();
                }
            }
            if (buf.isReadable()) return;
            decoded.rawHistory = true;
            snapshot = decoded;
            valid = true;
        } catch (RuntimeException malformed) {
            snapshot = null;
        }
    }

    public static final class Handler implements IMessageHandler<PacketSyncPortableHistory, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncPortableHistory message, MessageContext ctx) {
            if (message.valid) GTSimpleWirelessNetwork.proxy.handleSyncPortableMonitor(message.snapshot);
            return null;
        }
    }
}
