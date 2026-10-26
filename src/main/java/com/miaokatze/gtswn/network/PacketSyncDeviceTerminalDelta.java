package com.miaokatze.gtswn.network;

import java.util.UUID;

import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Compact pages never overwrite the legacy discriminator or layout. All identity fields are server assigned. */
public final class PacketSyncDeviceTerminalDelta implements IMessage {

    public static final int FULL = 0, DELTA = 1, KEEPALIVE = 2, REVOKED = 3;
    public static final int MAX_PAGE_BYTES = 256 * 1024;
    public static final int HEADER_BYTES = 66;
    private UUID terminalId, session;
    private long epoch, baseVersion, version;
    private int kind, pageIndex, pageTotal, entryTotal;
    private byte[] payload = new byte[0];

    public PacketSyncDeviceTerminalDelta() {}

    public PacketSyncDeviceTerminalDelta(UUID terminalId, UUID session, long epoch, long baseVersion, long version,
        int kind, int pageIndex, int pageTotal, int entryTotal, byte[] payload) {
        this.terminalId = terminalId;
        this.session = session;
        this.epoch = epoch;
        this.baseVersion = baseVersion;
        this.version = version;
        this.kind = kind;
        this.pageIndex = pageIndex;
        this.pageTotal = pageTotal;
        this.entryTotal = entryTotal;
        this.payload = payload.clone();
        if (!valid()) throw new IllegalArgumentException("Invalid compact device page");
    }

    private boolean valid() {
        return terminalId != null && session != null
            && epoch > 0
            && baseVersion >= -1
            && version >= 0
            && kind >= FULL
            && kind <= REVOKED
            && pageTotal >= 1
            && pageTotal <= 8
            && entryTotal >= 0
            && entryTotal <= 1024
            && pageIndex >= 0
            && pageIndex < pageTotal
            && pageTotal == Math.max(1, (entryTotal + 127) / 128)
            && payload.length <= MAX_PAGE_BYTES
            && (kind < KEEPALIVE || payload.length == 0)
            && (kind != DELTA || baseVersion >= 0)
            && (kind >= KEEPALIVE || baseVersion < version)
            && (kind != KEEPALIVE || baseVersion == version && pageIndex == 0)
            && (kind != REVOKED || baseVersion == -1 && version == 0 && entryTotal == 0);
    }

    @Override
    public void toBytes(ByteBuf out) {
        out.writeByte(1);
        out.writeByte(kind);
        out.writeLong(terminalId.getMostSignificantBits());
        out.writeLong(terminalId.getLeastSignificantBits());
        out.writeLong(session.getMostSignificantBits());
        out.writeLong(session.getLeastSignificantBits());
        out.writeLong(epoch);
        out.writeLong(baseVersion);
        out.writeLong(version);
        out.writeByte(pageIndex);
        out.writeByte(pageTotal);
        out.writeShort(entryTotal);
        out.writeInt(payload.length);
        out.writeBytes(payload);
    }

    @Override
    public void fromBytes(ByteBuf in) {
        terminalId = null;
        payload = new byte[0];
        try {
            if (in.readableBytes() < HEADER_BYTES || in.readUnsignedByte() != 1) return;
            kind = in.readUnsignedByte();
            UUID terminal = new UUID(in.readLong(), in.readLong());
            session = new UUID(in.readLong(), in.readLong());
            epoch = in.readLong();
            baseVersion = in.readLong();
            version = in.readLong();
            pageIndex = in.readUnsignedByte();
            pageTotal = in.readUnsignedByte();
            entryTotal = in.readUnsignedShort();
            int length = in.readInt();
            if (length < 0 || length > MAX_PAGE_BYTES || length != in.readableBytes()) return;
            payload = new byte[length];
            in.readBytes(payload);
            terminalId = terminal;
            if (!valid()) terminalId = null;
        } catch (RuntimeException malformed) {
            terminalId = null;
            payload = new byte[0];
        }
    }

    public int encodedBytes() {
        return HEADER_BYTES + payload.length;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getSession() {
        return session;
    }

    public long getEpoch() {
        return epoch;
    }

    public long getBaseVersion() {
        return baseVersion;
    }

    public long getVersion() {
        return version;
    }

    public int getKind() {
        return kind;
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public int getPageTotal() {
        return pageTotal;
    }

    public int getEntryTotal() {
        return entryTotal;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public static final class Handler implements IMessageHandler<PacketSyncDeviceTerminalDelta, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncDeviceTerminalDelta msg, MessageContext context) {
            if (!context.side.isServer() && msg.terminalId != null)
                GTSimpleWirelessNetwork.proxy.handleSyncDeviceTerminalDelta(msg);
            return null;
        }
    }
}
