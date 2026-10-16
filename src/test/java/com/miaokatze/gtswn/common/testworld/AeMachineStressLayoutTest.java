package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class AeMachineStressLayoutTest {

    @Test
    public void tenThousandNodesAndAllNativeEndpointsFitThePersistedFootprint() {
        assertLayout(AeMachineStressLayout.Profile.TEN_THOUSAND, 10000, 1024, 256, 41734);
        assertEquals(10000, AeMachineStressLayout.NODES);
        assertEquals(5000, AeMachineStressLayout.PAIRS);
        assertEquals(1024, AeMachineStressLayout.MACHINES);
        assertEquals(256, AeMachineStressLayout.STORAGE_PAIRS);
        assertEquals(2560, AeMachineStressLayout.FULL_CELLS);
        assertEquals(256, AeMachineStressLayout.EMPTY_CELLS);
        assertEquals(12560, AeMachineStressLayout.CHANNELS);
        assertEquals(AeMachineStressLayout.Profile.TEN_THOUSAND, new AeMachineStressLayout().profile);
    }

    @Test
    public void twentyFiveHundredUsesTheSameMaterialPathsAndAllPaletteTypes() {
        AeMachineStressLayout.Profile profile = AeMachineStressLayout.Profile.TWENTY_FIVE_HUNDRED;
        assertLayout(profile, 2500, 256, 64, 10582);
        assertEquals(3140, profile.channels);
        assertEquals(640, profile.fullCells);
        assertEquals(64, profile.emptyCells);
        assertEquals(333002880L, profile.fullCells * 520192L + profile.pairs * 64L);
        int[] types = new int[AeMachineStressLayout.ITEM_TYPES];
        for (int cell = 0; cell < profile.fullCells; cell++) types[cell % types.length]++;
        for (int type = 0; type < types.length; type++) assertEquals(type < 128 ? 3 : 2, types[type]);
        assertEquals(1640576L, types[0] * 520192L + profile.pairs * 64L);
        assertEquals(1560576L, types[1] * 520192L);
        AeMachineStressScenario original = new AeMachineStressScenario();
        AeMachineStressScenario reduced = new AeMachineStressScenario(profile);
        assertEquals("ae.machine_stress_10k", original.id());
        assertEquals("ae.machine_stress_2500", reduced.id());
        assertFalse(original.includeInAll());
        assertFalse(reduced.includeInAll());
        assertEquals(80, reduced.footprintRadius());
        assertEquals(30000, reduced.timeoutTicks());
    }

    private static void assertLayout(AeMachineStressLayout.Profile profile, int nodes, int machines, int storagePairs,
        int placements) {
        AeMachineStressLayout layout = new AeMachineStressLayout(profile);
        assertEquals(placements, layout.placements.size());
        Map<String, AeMachineStressLayout.Placement> occupied = new HashMap<>();
        Map<AeMachineStressLayout.Kind, Integer> counts = new HashMap<>();
        Set<String> chunks = new HashSet<>();
        for (AeMachineStressLayout.Placement p : layout.placements) {
            assertTrue(p.x >= -80 && p.x < 80 && p.z >= -80 && p.z < 80 && p.y >= 64 && p.y <= 127);
            assertFalse("Overlapping native blocks: " + key(p.x, p.y, p.z), occupied.containsKey(key(p.x, p.y, p.z)));
            occupied.put(key(p.x, p.y, p.z), p);
            counts.put(p.kind, counts.getOrDefault(p.kind, 0) + 1);
            chunks.add((p.x >> 4) + ":" + (p.z >> 4));
        }
        assertEquals(100, chunks.size());
        assertEquals(Integer.valueOf(nodes), counts.get(AeMachineStressLayout.Kind.NODE));
        assertEquals(Integer.valueOf(nodes), counts.get(AeMachineStressLayout.Kind.INTERFACE));
        assertEquals(Integer.valueOf(machines), counts.get(AeMachineStressLayout.Kind.MACHINE));
        assertEquals(Integer.valueOf(storagePairs), counts.get(AeMachineStressLayout.Kind.DRIVE));
        assertEquals(Integer.valueOf(storagePairs), counts.get(AeMachineStressLayout.Kind.CHEST));
        assertEquals(Integer.valueOf(nodes), counts.get(AeMachineStressLayout.Kind.HOPPER_EAST));
        assertEquals(Integer.valueOf(nodes / 2), counts.get(AeMachineStressLayout.Kind.HOPPER_NORTH));
        assertEquals(Integer.valueOf(nodes / 2), counts.get(AeMachineStressLayout.Kind.SOURCE_LOCK));
        assertEquals(Integer.valueOf(196), counts.get(AeMachineStressLayout.Kind.CONTROLLER));
        assertEquals(Integer.valueOf(1), counts.get(AeMachineStressLayout.Kind.POWER));
        assertEquals(Integer.valueOf(1), counts.get(AeMachineStressLayout.Kind.ENTRY));
        assertEquals(nodes, profile.nodes);
        assertEquals(machines, profile.machines);
        assertEquals(storagePairs, profile.storagePairs);
        assertEquals(nodes / 2, profile.pairs);
        assertTrue(profile.disturbedFirst >= machines * 2);
        assertTrue(profile.disturbedFirst + 8 <= nodes);
        assertTrue(profile.channels < AeMachineStressLayout.CHANNEL_BUDGET);
        for (int pair = 0; pair < profile.pairs; pair++) {
            int x = AeMachineStressLayout.x(pair), y = AeMachineStressLayout.y(pair), z = AeMachineStressLayout.z(pair);
            for (int side = 0; side < 2; side++) {
                AeMachineStressLayout.Placement node = occupied.get(key(x + side * 2, y, z));
                AeMachineStressLayout.Placement iface = occupied.get(key(x + side * 2, y - side, z + 1 - side));
                assertEquals(AeMachineStressLayout.Kind.NODE, node.kind);
                assertEquals(AeMachineStressLayout.Kind.INTERFACE, iface.kind);
                assertEquals(node.index, iface.index);
            }
            if (pair < machines) assertEquals(AeMachineStressLayout.Kind.MACHINE, occupied.get(key(x + 1, y, z)).kind);
            assertEquals(AeMachineStressLayout.Kind.SOURCE_LOCK, occupied.get(key(x, y - 2, z + 1)).kind);
            assertEquals(AeMachineStressLayout.Kind.HOPPER_EAST, occupied.get(key(x, y - 1, z + 1)).kind);
            assertEquals(AeMachineStressLayout.Kind.HOPPER_EAST, occupied.get(key(x + 1, y - 1, z + 1)).kind);
            assertEquals(AeMachineStressLayout.Kind.HOPPER_NORTH, occupied.get(key(x + 2, y - 1, z + 1)).kind);
            if (pair < storagePairs) {
                assertEquals(AeMachineStressLayout.Kind.DRIVE, occupied.get(key(x - 1, y, z)).kind);
                assertEquals(AeMachineStressLayout.Kind.CHEST, occupied.get(key(x + 1, y - 1, z)).kind);
            }
        }
        assertFalse("Player entrance stays free", occupied.containsKey(key(0, 65, 0)));
    }

    @Test
    public void connectedControllerGeometrySuppliesEnoughRealFacesWithoutCrossingOpposingAxes() {
        AeMachineStressLayout layout = new AeMachineStressLayout();
        Map<String, AeMachineStressLayout.Placement> controllers = new HashMap<>();
        for (AeMachineStressLayout.Placement p : layout.placements)
            if (p.kind == AeMachineStressLayout.Kind.CONTROLLER) controllers.put(key(p.x, p.y, p.z), p);
        int faces = 0;
        int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        for (AeMachineStressLayout.Placement p : controllers.values()) {
            int opposingAxes = 0;
            for (int axis = 0; axis < 3; axis++) {
                int[] a = directions[axis * 2], b = directions[axis * 2 + 1];
                if (controllers.containsKey(key(p.x + a[0], p.y + a[1], p.z + a[2]))
                    && controllers.containsKey(key(p.x + b[0], p.y + b[1], p.z + b[2]))) opposingAxes++;
            }
            assertTrue("Controller cross at " + key(p.x, p.y, p.z), opposingAxes <= 1);
            for (int[] d : directions) if (!controllers.containsKey(key(p.x + d[0], p.y + d[1], p.z + d[2]))) faces++;
        }
        assertEquals(196, controllers.size());
        assertEquals(772, faces);
        assertEquals(AeMachineStressLayout.CHANNEL_BUDGET, faces * 32);
        assertEquals(12560, AeMachineStressLayout.CHANNELS);
        assertTrue(AeMachineStressLayout.CHANNELS < faces * 32);
        // Flood the authored controller graph; native validation remains a separate runtime assertion.
        Map<String, Boolean> visited = new HashMap<>();
        visited.put(key(AeStressControllerLayout.X, AeStressControllerLayout.Y, AeStressControllerLayout.Z), true);
        boolean changed;
        do {
            changed = false;
            for (AeMachineStressLayout.Placement p : controllers.values()) {
                if (visited.containsKey(key(p.x, p.y, p.z))) continue;
                for (int[] d : directions) if (visited.containsKey(key(p.x + d[0], p.y + d[1], p.z + d[2]))) {
                    visited.put(key(p.x, p.y, p.z), true);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        assertEquals(controllers.size(), visited.size());
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
