package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.common.engineering.EngineeringDashboardSessions;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** New ID41: legacy view contract plus complete-baseline ACK and field projection. */
public final class PacketEngineeringControl implements IMessage {

    public PacketEngineeringSubscribe view;
    public long acknowledged;
    public int fields;
    public boolean quantum;
    public int dimension, chunkX, chunkZ;
    public boolean valid;
    public static final int NODES = 1, CELLS = 2, ALL = 3;

    public PacketEngineeringControl() {}

    public PacketEngineeringControl(PacketEngineeringSubscribe view, long acknowledged, int fields) {
        this.view = view;
        this.acknowledged = acknowledged;
        this.fields = fields;
    }

    public static PacketEngineeringControl quantumAck(int dimension, int chunkX, int chunkZ, long revision) {
        PacketEngineeringControl packet = new PacketEngineeringControl();
        packet.quantum = true;
        packet.dimension = dimension;
        packet.chunkX = chunkX;
        packet.chunkZ = chunkZ;
        packet.acknowledged = revision;
        packet.valid = true;
        return packet;
    }

    public void toBytes(ByteBuf out) {
        out.writeByte(quantum ? 1 : 0);
        out.writeLong(acknowledged);
        if (quantum) {
            out.writeInt(dimension);
            out.writeInt(chunkX);
            out.writeInt(chunkZ);
        } else {
            out.writeByte(fields);
            view.toBytes(out);
        }
    }

    public void fromBytes(ByteBuf in) {
        view = null;
        valid = false;
        quantum = false;
        try {
            if (in.readableBytes() < 21 || in.readableBytes() > 555) return;
            int kind = in.readUnsignedByte();
            acknowledged = in.readLong();
            if (acknowledged < 0 || kind > 1) return;
            if (kind == 1) {
                if (in.readableBytes() != 12) return;
                dimension = in.readInt();
                chunkX = in.readInt();
                chunkZ = in.readInt();
                quantum = valid = true;
                return;
            }
            fields = in.readUnsignedByte();
            if (acknowledged < 0 || fields > ALL) return;
            PacketEngineeringSubscribe value = new PacketEngineeringSubscribe();
            value.fromBytes(in);
            if (value.valid) {
                view = value;
                valid = true;
            }
        } catch (RuntimeException invalid) {
            view = null;
        }
    }

    public static final class Handler implements IMessageHandler<PacketEngineeringControl, IMessage> {

        public IMessage onMessage(PacketEngineeringControl message, MessageContext context) {
            if (context.side.isServer() && message.quantum && message.valid) {
                com.miaokatze.gtswn.common.quantum.QuantumIncorporationVisualSync.enqueueAck(
                    context.getServerHandler().playerEntity,
                    message.dimension,
                    message.chunkX,
                    message.chunkZ,
                    message.acknowledged);
            } else if (context.side.isServer() && message.view != null) EngineeringDashboardSessions.enqueueCompact(
                context.getServerHandler().playerEntity,
                message.view,
                message.acknowledged,
                message.fields);
            return null;
        }
    }
}
