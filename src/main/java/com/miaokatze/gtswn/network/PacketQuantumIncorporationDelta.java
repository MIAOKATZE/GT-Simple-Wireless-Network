package com.miaokatze.gtswn.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.network.PacketSyncQuantumIncorporationState.Position;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/** ID43: acknowledged chunk baseline; full reset, upsert, delete and empty are explicit. */
public final class PacketQuantumIncorporationDelta implements IMessage {

    public int dimension, chunkX, chunkZ;
    public long base, target;
    public boolean full, valid;
    public final List<Position> upserts = new ArrayList<>();
    public final List<Integer> removals = new ArrayList<>();

    public PacketQuantumIncorporationDelta() {}

    public PacketQuantumIncorporationDelta(PacketSyncQuantumIncorporationState current,
        PacketSyncQuantumIncorporationState previous, long base, long target) {
        dimension = current.getDimension();
        chunkX = current.getChunkX();
        chunkZ = current.getChunkZ();
        full = previous == null;
        this.base = full ? 0 : base;
        this.target = target;
        Map<Integer, Position> old = new HashMap<>();
        if (previous != null) for (Position position : previous.getPositions()) old.put(position.local, position);
        for (Position position : current.getPositions()) {
            Position before = old.remove(position.local);
            if (before == null || before.identityMost != position.identityMost
                || before.identityLeast != position.identityLeast
                || before.color != position.color) upserts.add(position);
        }
        removals.addAll(old.keySet());
        valid = true;
    }

    public PacketSyncQuantumIncorporationState apply(PacketSyncQuantumIncorporationState previous) {
        if (!valid || !full && previous == null) return null;
        Map<Integer, Position> result = new HashMap<>();
        if (!full) for (Position position : previous.getPositions()) result.put(position.local, position);
        for (int local : removals) result.remove(local);
        for (Position position : upserts) result.put(position.local, position);
        if (result.size() > 65536) return null;
        return new PacketSyncQuantumIncorporationState(dimension, chunkX, chunkZ, new ArrayList<>(result.values()));
    }

    public void toBytes(ByteBuf out) {
        out.writeByte(1);
        out.writeInt(dimension);
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        out.writeLong(base);
        out.writeLong(target);
        out.writeBoolean(full);
        out.writeInt(upserts.size());
        out.writeInt(removals.size());
        for (Position p : upserts) {
            out.writeShort(p.local);
            out.writeLong(p.identityMost);
            out.writeLong(p.identityLeast);
            out.writeByte(p.color);
        }
        for (int local : removals) out.writeShort(local);
    }

    public void fromBytes(ByteBuf in) {
        valid = false;
        upserts.clear();
        removals.clear();
        try {
            if (in.readableBytes() < 38 || in.readableBytes() > 1_400_000 || in.readUnsignedByte() != 1) return;
            dimension = in.readInt();
            chunkX = in.readInt();
            chunkZ = in.readInt();
            base = in.readLong();
            target = in.readLong();
            int reset = in.readUnsignedByte(), count = in.readInt(), removed = in.readInt();
            full = reset == 1;
            if (reset > 1 || base < 0
                || target <= 0
                || target < base
                || full && (base != 0 || removed != 0)
                || !full && base == 0
                || count < 0
                || count > 65536
                || removed < 0
                || removed > 65536
                || in.readableBytes() != count * 19 + removed * 2) return;
            Set<Integer> seen = new HashSet<>();
            for (int i = 0; i < count; i++) {
                int local = in.readUnsignedShort();
                long most = in.readLong(), least = in.readLong();
                int color = in.readUnsignedByte();
                if (!seen.add(local) || !QuantumNetworkColor.isValid(color)) return;
                upserts.add(new Position(local, new UUID(most, least), color));
            }
            for (int i = 0; i < removed; i++) {
                int local = in.readUnsignedShort();
                if (!seen.add(local)) return;
                removals.add(local);
            }
            valid = true;
        } catch (RuntimeException invalid) {
            valid = false;
        }
    }

    public static final class Handler implements IMessageHandler<PacketQuantumIncorporationDelta, IMessage> {

        public IMessage onMessage(PacketQuantumIncorporationDelta message, MessageContext context) {
            if (context.side.isClient() && message.valid) Client.receive(message);
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    private static final class Client {

        static void receive(PacketQuantumIncorporationDelta message) {
            final Object connection = net.minecraft.client.Minecraft.getMinecraft()
                .getNetHandler();
            net.minecraft.client.Minecraft.getMinecraft()
                .func_152344_a(() -> {
                    if (connection == net.minecraft.client.Minecraft.getMinecraft()
                        .getNetHandler())
                        com.miaokatze.gtswn.client.QuantumIncorporationClientState
                            .acceptDelta(net.minecraft.client.Minecraft.getMinecraft().theWorld, message);
                });
        }
    }
}
