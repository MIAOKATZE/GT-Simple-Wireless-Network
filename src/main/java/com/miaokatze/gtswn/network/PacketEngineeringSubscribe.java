package com.miaokatze.gtswn.network;

import java.nio.charset.StandardCharsets;

import com.miaokatze.gtswn.common.engineering.EngineeringDashboardSessions;
import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Bounded view and expected provider identity; actual possession and permissions remain server facts. */
public final class PacketEngineeringSubscribe implements IMessage {

    public long sessionId;
    public int slot, dim, minCellX, minCellZ, maxCellX, maxCellZ;
    public boolean gtGrid, baseline, close, boundOnly;
    public boolean valid;
    public SourceOrigin sourceOrigin = SourceOrigin.NONE;
    public String sourceBinding = "";

    public PacketEngineeringSubscribe source(Source source) {
        sourceOrigin = source.origin;
        sourceBinding = source.binding;
        valid = validFields();
        return this;
    }

    public boolean matches(Source actual) {
        return actual.present() && sourceOrigin == actual.origin && sourceBinding.equals(actual.binding);
    }

    public PacketEngineeringSubscribe() {}

    public PacketEngineeringSubscribe(long sessionId, int slot, int dim, int minCellX, int minCellZ, int maxCellX,
        int maxCellZ, boolean gtGrid, boolean baseline, boolean close) {
        this(sessionId, slot, dim, minCellX, minCellZ, maxCellX, maxCellZ, gtGrid, baseline, close, false);
    }

    public PacketEngineeringSubscribe(long sessionId, int slot, int dim, int minCellX, int minCellZ, int maxCellX,
        int maxCellZ, boolean gtGrid, boolean baseline, boolean close, boolean boundOnly) {
        this.sessionId = sessionId;
        this.slot = slot;
        this.dim = dim;
        this.minCellX = minCellX;
        this.minCellZ = minCellZ;
        this.maxCellX = maxCellX;
        this.maxCellZ = maxCellZ;
        this.gtGrid = gtGrid;
        this.baseline = baseline;
        this.close = close;
        this.boundOnly = boundOnly;
        valid = validFields();
    }

    private boolean validFields() {
        return sourceOrigin != null && sourceBinding != null
            && sourceBinding.getBytes(StandardCharsets.UTF_8).length <= 512
            && sessionId > 0
            && slot >= 0
            && slot < 8
            && (close || EngineeringGrid.valid(minCellX, minCellZ, maxCellX, maxCellZ, gtGrid));
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        valid = false;
        if (buffer.readableBytes() < 33 || buffer.readableBytes() > 545) return;
        sessionId = buffer.readLong();
        slot = buffer.readUnsignedByte();
        dim = buffer.readInt();
        minCellX = buffer.readInt();
        minCellZ = buffer.readInt();
        maxCellX = buffer.readInt();
        maxCellZ = buffer.readInt();
        int flags = buffer.readUnsignedByte();
        gtGrid = (flags & 1) != 0;
        baseline = (flags & 2) != 0;
        close = (flags & 4) != 0;
        boundOnly = (flags & 8) != 0;
        int origin = buffer.readUnsignedByte();
        int length = buffer.readUnsignedShort();
        if (origin >= SourceOrigin.values().length || length > 512 || buffer.readableBytes() != length) return;
        sourceOrigin = SourceOrigin.values()[origin];
        byte[] identity = new byte[length];
        buffer.readBytes(identity);
        sourceBinding = new String(identity, StandardCharsets.UTF_8);
        valid = flags <= 15 && validFields();
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeLong(sessionId);
        buffer.writeByte(slot);
        buffer.writeInt(dim);
        buffer.writeInt(minCellX);
        buffer.writeInt(minCellZ);
        buffer.writeInt(maxCellX);
        buffer.writeInt(maxCellZ);
        buffer.writeByte((gtGrid ? 1 : 0) | (baseline ? 2 : 0) | (close ? 4 : 0) | (boundOnly ? 8 : 0));
        buffer.writeByte(sourceOrigin.ordinal());
        byte[] identity = sourceBinding.getBytes(StandardCharsets.UTF_8);
        buffer.writeShort(identity.length);
        buffer.writeBytes(identity);
    }

    public static final class Handler implements IMessageHandler<PacketEngineeringSubscribe, IMessage> {

        @Override
        public IMessage onMessage(PacketEngineeringSubscribe message, MessageContext context) {
            if (context.side.isServer() && message.valid)
                EngineeringDashboardSessions.enqueue(context.getServerHandler().playerEntity, message);
            return null;
        }
    }
}
