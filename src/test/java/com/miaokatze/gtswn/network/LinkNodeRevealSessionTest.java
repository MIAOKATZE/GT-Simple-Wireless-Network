package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;

public class LinkNodeRevealSessionTest {

    @Test
    public void repeatedScanRequiresSameWorldAndUnexpiredWindow() {
        Object world = new Object();
        LinkNodeRevealSession session = new LinkNodeRevealSession(world, 15000L);
        assertTrue(session.isActive(world, 14999L));
        assertFalse(session.isActive(world, 15000L));
        assertFalse(session.isActive(world, 15001L));
        assertFalse(session.isActive(new Object(), 1000L));
    }

    @Test
    public void firstScanFiltersModeAndRepeatPreservesBothCoversOnSameMachine() {
        assertEquals(1, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 0, false));
        assertEquals(2, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 1, false));
        assertEquals(3, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 0, true));
        assertEquals(3, LinkNodeRevealSession.visibleTypes((byte) 3, (byte) 1, true));
        assertEquals(0, LinkNodeRevealSession.visibleTypes((byte) 2, (byte) 0, false));
        assertEquals(2, LinkNodeRevealSession.visibleTypes((byte) 2, (byte) 0, true));
    }

    @Test
    public void modeFilteringPrecedesLimitAndPreservesPruning() {
        Map<Long, Byte> index = new HashMap<>();
        Map<Long, Byte> actualTypes = new HashMap<>();
        for (int x = -8; x < 8; x++) {
            for (int z = -8; z < 8; z++) {
                index.put(WirelessNodeIndexCodec.pack(x, 64, z), (byte) 1);
            }
        }
        long energy = WirelessNodeIndexCodec.pack(32, 64, 0);
        long stale = WirelessNodeIndexCodec.pack(33, 64, 0);
        long unloaded = WirelessNodeIndexCodec.pack(34, 64, 0);
        index.put(energy, (byte) 0);
        index.put(stale, (byte) 0);
        index.put(unloaded, (byte) 0);
        WirelessNodeIndexCodec.Probe probe = (packed, x, y, z, type) -> {
            if (packed == stale) return WirelessNodeIndexCodec.ProbeResult.INVALID;
            if (packed == unloaded) return WirelessNodeIndexCodec.ProbeResult.UNLOADED;
            actualTypes.put(packed, (byte) (1 << type));
            return WirelessNodeIndexCodec.ProbeResult.VALID;
        };
        long center = WirelessNodeIndexCodec.pack(0, 64, 0);
        WirelessNodeIndexCodec.RadiusSelection first = WirelessNodeIndexCodec.selectWithinRadius(
            index,
            center,
            4096,
            256,
            LinkNodeRevealSession.visibleProbe(probe, actualTypes, (byte) 0, false));
        assertEquals(1, first.selected.size());
        assertEquals(energy, first.selected.get(0).packed);
        assertEquals(1, first.pruned.size());
        assertTrue(first.pruned.contains(stale));
        assertFalse(first.pruned.contains(unloaded));
        assertEquals(259, index.size());

        WirelessNodeIndexCodec.RadiusSelection repeated = WirelessNodeIndexCodec.selectWithinRadius(
            index,
            center,
            4096,
            256,
            LinkNodeRevealSession.visibleProbe(probe, actualTypes, (byte) 0, true));
        assertEquals(256, repeated.selected.size());
        assertTrue(repeated.pruned.contains(stale));
    }
}
