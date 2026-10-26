package com.miaokatze.gtswn.network;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Review;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Negotiated typed rows: link power OR quantum anchor fields, never fixed-zero placeholders. */
public final class EngineeringTypedSnapshot implements IMessage {

    public EngineeringSnapshot snapshot;

    public EngineeringTypedSnapshot() {}

    public EngineeringTypedSnapshot(EngineeringSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    @Override
    public void toBytes(ByteBuf b) {
        EngineeringSnapshot s = snapshot;
        b.writeLong(s.sessionId);
        b.writeByte(s.slot);
        b.writeLong(0L);
        b.writeByte(s.occupiedMask);
        string(b, s.status);
        b.writeInt(s.monitored);
        b.writeInt(s.unknown);
        b.writeInt(s.totalBound);
        b.writeByte(
            (s.truncated ? 1 : 0) | (s.coverageChanged ? 2 : 0)
                | (s.coverageBoundOnly ? 4 : 0)
                | (s.axisSymmetrical ? 8 : 0)
                | (s.patternResolved ? 16 : 0));
        power(b, s.before);
        power(b, s.current);
        b.writeBoolean(s.review != null);
        if (s.review != null) {
            Review r = s.review;
            b.writeLong(r.baselineTick);
            b.writeInt(r.comparableMonitored);
            b.writeInt(r.addedMonitored);
            b.writeInt(r.removedMonitored);
            power(b, r.comparableBefore);
            power(b, r.comparableCurrent);
            b.writeInt(r.loadedChunks);
            b.writeInt(r.totalChunks);
            b.writeInt(r.scannedChunks);
            b.writeInt(r.modeled);
            b.writeBoolean(r.comparable);
            b.writeBoolean(r.discovering);
        }
        QuantumNetworkData q = s.quantum;
        b.writeBoolean(q != null);
        if (q != null) {
            b.writeBoolean(q.online);
            b.writeInt(q.anchorDim);
            b.writeInt(q.anchorX);
            b.writeInt(q.anchorY);
            b.writeInt(q.anchorZ);
            b.writeInt(q.totalChannels);
            b.writeInt(q.usedChannels);
            b.writeInt(q.quantumNodeCount);
            b.writeInt(q.incorporationCount);
            b.writeInt(q.incorporationChannels);
            b.writeBoolean(q.channelsInfinite);
        }
        b.writeShort(s.nodes.size());
        for (Node n : s.nodes) {
            b.writeByte(n.kind);
            b.writeInt(n.dim);
            b.writeInt(n.x);
            b.writeInt(n.y);
            b.writeInt(n.z);
            string(b, n.owner);
            string(b, n.ownerName);
            string(b, n.name);
            b.writeBoolean(n.online);
            if (n.kind == 1) {
                b.writeByte(n.mode);
                b.writeLong(n.voltage);
                b.writeDouble(n.amperage);
                b.writeLong(n.stored);
                b.writeLong(n.capacity);
            } else {
                b.writeInt(n.anchorDim);
                b.writeInt(n.anchorX);
                b.writeInt(n.anchorY);
                b.writeInt(n.anchorZ);
                b.writeInt(n.channels);
            }
        }
        b.writeByte(s.cells.size());
        for (Cell c : s.cells) {
            b.writeInt(c.x);
            b.writeInt(c.z);
            b.writeInt(c.monitored);
            b.writeInt(c.unknown);
            power(b, c.power);
        }
    }

    @Override
    public void fromBytes(ByteBuf b) {
        snapshot = null;
        if (b.readableBytes() > 350_000) return;
        try {
            long id = b.readLong();
            int slot = b.readUnsignedByte();
            long tick = b.readLong();
            int occupied = b.readUnsignedByte();
            String status = string(b);
            int monitored = count(b.readInt(), 4096), unknown = count(b.readInt(), 4096);
            int total = count(b.readInt(), 4096), flags = b.readUnsignedByte();
            Power before = power(b), current = power(b);
            Review review = null;
            if (bool(b)) {
                long baselineTick = b.readLong();
                int comparable = count(b.readInt(), 4096), added = count(b.readInt(), 4096);
                int removed = count(b.readInt(), 4096);
                Power comparedBefore = power(b), comparedCurrent = power(b);
                int loaded = count(b.readInt(), 576), chunks = count(b.readInt(), 576),
                    scanned = count(b.readInt(), 576);
                int modeled = count(b.readInt(), 4096);
                review = new Review(
                    baselineTick,
                    comparable,
                    added,
                    removed,
                    comparedBefore,
                    comparedCurrent,
                    loaded,
                    chunks,
                    scanned,
                    modeled,
                    bool(b),
                    bool(b));
            }
            QuantumNetworkData q = null;
            int hasQuantum = b.readUnsignedByte();
            if (hasQuantum > 1) return;
            if (hasQuantum == 1) {
                q = new QuantumNetworkData();
                q.online = bool(b);
                q.anchorDim = b.readInt();
                q.anchorX = b.readInt();
                q.anchorY = b.readInt();
                q.anchorZ = b.readInt();
                q.totalChannels = nonnegative(b.readInt());
                q.usedChannels = nonnegative(b.readInt());
                q.quantumNodeCount = nonnegative(b.readInt());
                q.incorporationCount = nonnegative(b.readInt());
                q.incorporationChannels = nonnegative(b.readInt());
                q.channelsInfinite = bool(b);
            }
            int n = count(b.readUnsignedShort(), EngineeringSnapshot.MAX_NODES);
            List<Node> nodes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int kind = b.readUnsignedByte(), dim = b.readInt(), x = b.readInt(), y = b.readInt(), z = b.readInt();
                if (kind < 1 || kind > 3) return;
                String owner = string(b), ownerName = string(b), name = string(b);
                boolean online = bool(b);
                int mode = 0, ad = 0, ax = 0, ay = 0, az = 0, channels = 0;
                long voltage = 0, stored = 0, capacity = 0;
                double amperage = 0;
                if (kind == 1) {
                    mode = b.readUnsignedByte();
                    voltage = b.readLong();
                    amperage = finite(b.readDouble());
                    stored = b.readLong();
                    capacity = b.readLong();
                } else {
                    ad = b.readInt();
                    ax = b.readInt();
                    ay = b.readInt();
                    az = b.readInt();
                    channels = nonnegative(b.readInt());
                }
                nodes.add(
                    new Node(
                        kind,
                        dim,
                        x,
                        y,
                        z,
                        owner,
                        ownerName,
                        name,
                        online,
                        mode,
                        voltage,
                        amperage,
                        stored,
                        capacity,
                        ad,
                        ax,
                        ay,
                        az,
                        channels));
            }
            int c = count(b.readUnsignedByte(), EngineeringGrid.MAX_CELLS);
            List<Cell> cells = new ArrayList<>(c);
            for (int i = 0; i < c; i++) cells
                .add(new Cell(b.readInt(), b.readInt(), count(b.readInt(), 4096), count(b.readInt(), 4096), power(b)));
            if (b.isReadable() || id <= 0 || slot > 7 || flags > 31) return;
            snapshot = new EngineeringSnapshot(
                id,
                slot,
                tick,
                occupied,
                status,
                nodes,
                cells,
                before,
                current,
                monitored,
                unknown,
                total,
                (flags & 1) != 0,
                (flags & 2) != 0,
                (flags & 4) != 0,
                q,
                (flags & 8) != 0,
                (flags & 16) != 0,
                review);
        } catch (RuntimeException invalid) {
            snapshot = null;
        }
    }

    private static boolean bool(ByteBuf b) {
        return count(b.readUnsignedByte(), 1) == 1;
    }

    private static int nonnegative(int value) {
        return count(value, Integer.MAX_VALUE);
    }

    private static int count(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Invalid engineering count");
        return value;
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Invalid engineering power");
        return value;
    }

    private static void power(ByteBuf b, Power p) {
        b.writeDouble(p.instIn);
        b.writeDouble(p.instOut);
        b.writeDouble(p.avgIn);
        b.writeDouble(p.avgOut);
    }

    private static Power power(ByteBuf b) {
        return new Power(
            finite(b.readDouble()),
            finite(b.readDouble()),
            finite(b.readDouble()),
            finite(b.readDouble()));
    }

    private static void string(ByteBuf b, String value) {
        String bounded = value == null ? "" : value.substring(0, Math.min(96, value.length()));
        byte[] bytes = bounded.getBytes(StandardCharsets.UTF_8);
        b.writeShort(bytes.length);
        b.writeBytes(bytes);
    }

    private static String string(ByteBuf b) {
        int length = count(b.readUnsignedShort(), 384);
        if (length > b.readableBytes()) throw new IllegalArgumentException("Truncated engineering string");
        byte[] bytes = new byte[length];
        b.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

}
