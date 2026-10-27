package com.miaokatze.gtswn.network.telemetry;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ByteDeltaWireCostTest {

    private static int roundTrip(ByteDelta delta, byte[] base, byte[] expected) {
        ByteBuf wire = Unpooled.buffer();
        try {
            delta.write(wire);
            int bytes = wire.readableBytes();
            ByteDelta decoded = ByteDelta.read(wire);
            assertFalse(wire.isReadable());
            assertArrayEquals(expected, decoded.apply(base));
            return bytes;
        } finally {
            wire.release();
        }
    }

    @Test
    public void fullCostIncludesItsSingleRangeHeader() {
        byte[] base = new byte[16], target = base.clone();
        java.util.Arrays.fill(target, 0, 8, (byte) 1);
        ByteDelta delta = ByteDelta.between(base, target);
        assertFalse(delta.full);
        assertEquals(23, roundTrip(delta, base, target));
        assertEquals(31, roundTrip(ByteDelta.between(null, target), null, target));
    }

    @Test
    public void exactlyMaximumRangesStillUseTheExistingValidDeltaFormat() {
        byte[] base = new byte[40960], target = base.clone();
        for (int i = 0; i < 4096; i++) target[i * 10] = 1;
        ByteDelta delta = ByteDelta.between(base, target);
        assertFalse(delta.full);
        assertEquals(ByteDelta.MAX_RANGES, delta.offsets.length);
        assertEquals(36871, roundTrip(delta, base, target));
        assertEquals(40975, roundTrip(ByteDelta.between(null, target), null, target));
    }

    @Test
    public void exceedingRangeLimitStillResetsInFull() {
        byte[] base = new byte[40970], target = base.clone();
        for (int i = 0; i < 4097; i++) target[i * 10] = 1;
        ByteDelta delta = ByteDelta.between(base, target);
        assertTrue(delta.full);
        assertEquals(40985, roundTrip(delta, base, target));
    }
}
