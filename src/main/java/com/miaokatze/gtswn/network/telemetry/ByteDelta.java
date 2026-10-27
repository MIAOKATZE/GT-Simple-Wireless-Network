package com.miaokatze.gtswn.network.telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.netty.buffer.ByteBuf;

/** Bounded exact byte ranges over a completely acknowledged immutable baseline. */
public final class ByteDelta {

    public static final int MAX_BYTES = 340_000, MAX_RANGES = 4096;
    private static final int HEADER_BYTES = 7, RANGE_HEADER_BYTES = 8;
    public final boolean full;
    public final int length;
    public final int[] offsets;
    public final byte[][] ranges;

    private ByteDelta(boolean full, int length, int[] offsets, byte[][] ranges) {
        this.full = full;
        this.length = length;
        this.offsets = offsets;
        this.ranges = ranges;
    }

    public static ByteDelta between(byte[] base, byte[] target) {
        if (target == null || target.length > MAX_BYTES) throw new IllegalArgumentException("Oversized snapshot");
        if (base == null || base.length != target.length) return full(target);
        List<Integer> starts = new ArrayList<>();
        List<byte[]> pieces = new ArrayList<>();
        int cost = HEADER_BYTES;
        for (int i = 0; i < target.length;) {
            if (base[i] == target[i]) {
                i++;
                continue;
            }
            int start = i++, end = i;
            while (i < target.length && i - end <= 8) {
                if (base[i] != target[i]) end = i + 1;
                i++;
            }
            byte[] piece = Arrays.copyOfRange(target, start, end);
            starts.add(start);
            pieces.add(piece);
            cost += RANGE_HEADER_BYTES + piece.length;
            if (starts.size() > MAX_RANGES || cost >= target.length + HEADER_BYTES + RANGE_HEADER_BYTES)
                return full(target);
        }
        int[] offsets = new int[starts.size()];
        for (int i = 0; i < offsets.length; i++) offsets[i] = starts.get(i);
        return new ByteDelta(false, target.length, offsets, pieces.toArray(new byte[pieces.size()][]));
    }

    private static ByteDelta full(byte[] target) {
        return new ByteDelta(true, target.length, new int[] { 0 }, new byte[][] { target.clone() });
    }

    public byte[] apply(byte[] base) {
        if (!full && (base == null || base.length != length)) return null;
        byte[] output = full ? new byte[length] : base.clone();
        for (int i = 0; i < offsets.length; i++) System.arraycopy(ranges[i], 0, output, offsets[i], ranges[i].length);
        return output;
    }

    public void write(ByteBuf out) {
        out.writeBoolean(full);
        out.writeInt(length);
        out.writeShort(offsets.length);
        for (int i = 0; i < offsets.length; i++) {
            out.writeInt(offsets[i]);
            out.writeInt(ranges[i].length);
            out.writeBytes(ranges[i]);
        }
    }

    public static ByteDelta read(ByteBuf in) {
        int kind = in.readUnsignedByte(), length = in.readInt(), count = in.readUnsignedShort();
        if (kind > 1 || length < 0 || length > MAX_BYTES || count > MAX_RANGES || kind == 1 && count != 1)
            throw new IllegalArgumentException("Invalid delta header");
        int[] offsets = new int[count];
        byte[][] ranges = new byte[count][];
        int end = 0;
        for (int i = 0; i < count; i++) {
            int offset = in.readInt(), size = in.readInt();
            if (offset < end || size < 0 || offset > length || size > length - offset || size > in.readableBytes())
                throw new IllegalArgumentException("Invalid delta range");
            offsets[i] = offset;
            ranges[i] = new byte[size];
            in.readBytes(ranges[i]);
            end = offset + size;
        }
        if (kind == 1 && (offsets[0] != 0 || ranges[0].length != length))
            throw new IllegalArgumentException("Incomplete reset");
        return new ByteDelta(kind == 1, length, offsets, ranges);
    }
}
