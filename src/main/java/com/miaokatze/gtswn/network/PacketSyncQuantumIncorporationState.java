package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** S2C chunk snapshot. An empty snapshot removes every incorporation in that chunk. */
public final class PacketSyncQuantumIncorporationState implements IMessage {

    // A vanilla chunk has at most 16 * 256 * 16 different block positions.
    private static final int MAX_ENTRIES = 65536;
    private int dimension, chunkX, chunkZ;
    private boolean valid;
    private final List<Position> positions = new ArrayList<>();

    public PacketSyncQuantumIncorporationState() {}

    public PacketSyncQuantumIncorporationState(int dimension, int chunkX, int chunkZ, List<Position> positions) {
        if (positions.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many chunk positions");
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.positions.addAll(positions);
        valid = true;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeInt(positions.size());
        for (Position position : positions) {
            buf.writeShort(position.local);
            buf.writeLong(position.identityMost);
            buf.writeLong(position.identityLeast);
            buf.writeByte(position.color);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = false;
        positions.clear();
        if (buf.readableBytes() < 16) return;
        dimension = buf.readInt();
        chunkX = buf.readInt();
        chunkZ = buf.readInt();
        int count = buf.readInt();
        if (count < 0 || count > MAX_ENTRIES || buf.readableBytes() != count * 19) return;
        for (int i = 0; i < count; i++) {
            int local = buf.readUnsignedShort();
            long most = buf.readLong(), least = buf.readLong();
            int color = buf.readUnsignedByte();
            if (!QuantumNetworkColor.isValid(color)) {
                positions.clear();
                return;
            }
            positions.add(new Position(local, most, least, color));
        }
        valid = true;
    }

    public boolean isValid() {
        return valid;
    }

    public int getDimension() {
        return dimension;
    }

    public int getChunkX() {
        return chunkX;
    }

    public int getChunkZ() {
        return chunkZ;
    }

    /** Each entry packs local coordinates (y:8, z:4, x:4) and its persistent UUID. */
    public List<Position> getPositions() {
        return Collections.unmodifiableList(positions);
    }

    public static final class Position {

        public final int local;
        public final int color;
        public final long identityMost, identityLeast;

        public Position(int local, UUID identity) {
            this(local, identity, QuantumNetworkColor.DEFAULT);
        }

        public Position(int local, UUID identity, int color) {
            this(local, identity.getMostSignificantBits(), identity.getLeastSignificantBits(), color);
        }

        private Position(int local, long identityMost, long identityLeast, int color) {
            this.color = QuantumNetworkColor.normalize(color);
            this.local = local;
            this.identityMost = identityMost;
            this.identityLeast = identityLeast;
        }
    }

    public static final class Handler implements IMessageHandler<PacketSyncQuantumIncorporationState, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncQuantumIncorporationState message, MessageContext context) {
            if (context.side.isClient() && message.isValid()) {
                GTSimpleWirelessNetwork.proxy.handleSyncQuantumIncorporationState(message);
            }
            return null;
        }
    }
}
