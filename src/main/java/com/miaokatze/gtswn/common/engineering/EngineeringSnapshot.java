package com.miaokatze.gtswn.common.engineering;

import java.util.Collections;
import java.util.List;

import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;

/** Bounded read-only dashboard transport. Power covers monitored devices, never total chunk demand. */
public final class EngineeringSnapshot {

    public static final int MAX_NODES = 256;
    public final long sessionId, serverTick;
    public final int slot, occupiedMask;
    public final String status;
    public final List<Node> nodes;
    public final List<Cell> cells;
    public final Power before, current, delta;
    public final int monitored, unknown, totalBound;
    public final boolean truncated, coverageChanged, coverageBoundOnly;
    public final boolean axisSymmetrical, patternResolved;
    public final Review review;
    /** Basic existing quantum network fields only; no storage, throughput or device catalogue. */
    public final QuantumNetworkData quantum;

    public EngineeringSnapshot(long sessionId, int slot, long serverTick, int occupiedMask, String status,
        List<Node> nodes, List<Cell> cells, Power before, Power current, int monitored, int unknown, int totalBound,
        boolean truncated, boolean coverageChanged, boolean coverageBoundOnly, QuantumNetworkData quantum) {
        this(
            sessionId,
            slot,
            serverTick,
            occupiedMask,
            status,
            nodes,
            cells,
            before,
            current,
            monitored,
            unknown,
            totalBound,
            truncated,
            coverageChanged,
            coverageBoundOnly,
            quantum,
            false,
            true);
    }

    public EngineeringSnapshot(long sessionId, int slot, long serverTick, int occupiedMask, String status,
        List<Node> nodes, List<Cell> cells, Power before, Power current, int monitored, int unknown, int totalBound,
        boolean truncated, boolean coverageChanged, boolean coverageBoundOnly, QuantumNetworkData quantum,
        boolean axisSymmetrical, boolean patternResolved) {
        this(
            sessionId,
            slot,
            serverTick,
            occupiedMask,
            status,
            nodes,
            cells,
            before,
            current,
            monitored,
            unknown,
            totalBound,
            truncated,
            coverageChanged,
            coverageBoundOnly,
            quantum,
            axisSymmetrical,
            patternResolved,
            null);
    }

    public EngineeringSnapshot(long sessionId, int slot, long serverTick, int occupiedMask, String status,
        List<Node> nodes, List<Cell> cells, Power before, Power current, int monitored, int unknown, int totalBound,
        boolean truncated, boolean coverageChanged, boolean coverageBoundOnly, QuantumNetworkData quantum,
        boolean axisSymmetrical, boolean patternResolved, Review review) {
        this.sessionId = sessionId;
        this.slot = slot;
        this.serverTick = serverTick;
        this.occupiedMask = occupiedMask;
        this.status = status;
        this.nodes = Collections.unmodifiableList(new java.util.ArrayList<>(nodes));
        this.cells = Collections.unmodifiableList(new java.util.ArrayList<>(cells));
        this.before = before;
        this.current = current;
        this.delta = review == null ? current.minus(before) : review.comparableCurrent.minus(review.comparableBefore);
        this.monitored = monitored;
        this.unknown = unknown;
        this.totalBound = totalBound;
        this.truncated = truncated;
        this.coverageChanged = coverageChanged;
        this.coverageBoundOnly = coverageBoundOnly;
        this.quantum = quantum;
        this.axisSymmetrical = axisSymmetrical;
        this.patternResolved = patternResolved;
        this.review = review;
    }

    public static final class Power {

        public static final Power ZERO = new Power(0, 0, 0, 0);
        public final double instIn, instOut, avgIn, avgOut;

        public Power(double instIn, double instOut, double avgIn, double avgOut) {
            this.instIn = finite(instIn);
            this.instOut = finite(instOut);
            this.avgIn = finite(avgIn);
            this.avgOut = finite(avgOut);
        }

        public Power plus(Power other) {
            return new Power(
                sum(instIn, other.instIn),
                sum(instOut, other.instOut),
                sum(avgIn, other.avgIn),
                sum(avgOut, other.avgOut));
        }

        public Power minus(Power other) {
            return new Power(
                sum(instIn, -other.instIn),
                sum(instOut, -other.instOut),
                sum(avgIn, -other.avgIn),
                sum(avgOut, -other.avgOut));
        }

        private static double sum(double first, double second) {
            double result = first + second;
            return Double.isInfinite(result) ? Math.copySign(Double.MAX_VALUE, result) : result;
        }

        private static double finite(double value) {
            return Double.isFinite(value) ? value : 0;
        }
    }

    public static final class Cell {

        public final int x, z, monitored, unknown;
        public final Power power;

        public Cell(int x, int z, int monitored, int unknown, Power power) {
            this.x = x;
            this.z = z;
            this.monitored = monitored;
            this.unknown = unknown;
            this.power = power;
        }
    }

    /** Delta compares only the intersection of fresh devices. Lost coverage never becomes a zero load. */
    public static final class Review {

        public final long baselineTick;
        public final int comparableMonitored, addedMonitored, removedMonitored;
        public final Power comparableBefore, comparableCurrent;
        public final int loadedChunks, totalChunks, scannedChunks, modeled;
        public final boolean comparable, discovering;

        public Review(long baselineTick, int comparableMonitored, int addedMonitored, int removedMonitored,
            Power comparableBefore, Power comparableCurrent, int loadedChunks, int totalChunks, int scannedChunks,
            int modeled, boolean comparable, boolean discovering) {
            this.baselineTick = baselineTick;
            this.comparableMonitored = comparableMonitored;
            this.addedMonitored = addedMonitored;
            this.removedMonitored = removedMonitored;
            this.comparableBefore = comparableBefore;
            this.comparableCurrent = comparableCurrent;
            this.loadedChunks = loadedChunks;
            this.totalChunks = totalChunks;
            this.scannedChunks = scannedChunks;
            this.modeled = modeled;
            this.comparable = comparable;
            this.discovering = discovering;
        }
    }

    public static final class Node {

        public final int kind, dim, x, y, z, mode, anchorDim, anchorX, anchorY, anchorZ, channels;
        public final String owner, ownerName, name;
        public final boolean online;
        public final long voltage, stored, capacity;
        public final double amperage;

        public Node(int kind, int dim, int x, int y, int z, String owner, String ownerName, String name, boolean online,
            int mode, long voltage, double amperage, long stored, long capacity, int anchorDim, int anchorX,
            int anchorY, int anchorZ, int channels) {
            this.kind = kind;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner == null ? "" : owner;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.name = name == null ? "" : name;
            this.online = online;
            this.mode = mode;
            this.voltage = voltage;
            this.amperage = Double.isFinite(amperage) ? amperage : 0;
            this.stored = stored;
            this.capacity = capacity;
            this.anchorDim = anchorDim;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.anchorZ = anchorZ;
            this.channels = channels;
        }
    }
}
