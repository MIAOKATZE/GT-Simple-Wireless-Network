package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** Geometry checks prevent a large native run silently overwriting fixtures or joining adjacent module grids. */
public class AeStorageStressLayoutTest {

    @Test
    public void bothNativeLayoutsFitResetBoundsWithoutOverlappingBlocks() {
        for (boolean large : new boolean[] { false, true }) {
            AeStorageStressLayout layout = new AeStorageStressLayout(large);
            Set<String> occupied = new HashSet<>();
            for (AeStorageStressLayout.Placement p : layout.placements) {
                assertTrue(p.x >= -32 && p.x <= 31 && p.z >= -32 && p.z <= 31);
                assertTrue(p.y >= 64 && p.y <= 127);
                assertTrue("Duplicate block at " + key(p.x, p.y, p.z), occupied.add(key(p.x, p.y, p.z)));
            }
            assertFalse(occupied.contains(key(TestWorldFixtures.PANEL_X, 65, TestWorldFixtures.PANEL_Z)));
            assertFalse("Default player entrance must stay empty", occupied.contains(key(0, 65, 0)));
            assertFalse(occupied.contains(key(TestWorldFixtures.CONTROLLER_X, 65, TestWorldFixtures.CONTROLLER_Z)));
            assertEquals(198 + layout.modules * 5 + layout.cpus * 9, occupied.size());
        }
    }

    @Test
    public void eachStorageNodeHasExactlyFourNativeDevicesAndAirBetweenModules() {
        for (boolean large : new boolean[] { false, true }) {
            AeStorageStressLayout layout = new AeStorageStressLayout(large);
            Map<String, AeStorageStressLayout.Placement> occupied = new HashMap<>();
            for (AeStorageStressLayout.Placement p : layout.placements) occupied.put(key(p.x, p.y, p.z), p);
            int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 }, { 0, 1, 0 }, { 0, -1, 0 } };
            for (int module = 0; module < layout.modules; module++) {
                int adjacent = 0;
                for (int[] d : directions) {
                    AeStorageStressLayout.Placement neighbor = occupied.get(
                        key(
                            AeStorageStressLayout.x(module) + d[0],
                            AeStorageStressLayout.y(module) + d[1],
                            AeStorageStressLayout.z(module) + d[2]));
                    if (neighbor == null) continue;
                    assertEquals(module, neighbor.module);
                    assertTrue(
                        neighbor.kind == AeStorageStressLayout.Kind.DRIVE
                            || neighbor.kind == AeStorageStressLayout.Kind.CHEST
                            || neighbor.kind == AeStorageStressLayout.Kind.INTERFACE);
                    adjacent++;
                }
                assertEquals(4, adjacent);
            }
            for (AeStorageStressLayout.Placement p : layout.placements) {
                if (p.module < 0) continue;
                for (int[] d : directions) {
                    AeStorageStressLayout.Placement other = occupied.get(key(p.x + d[0], p.y + d[1], p.z + d[2]));
                    if (other != null)
                        assertEquals("Physical connection between unrelated modules", p.module, other.module);
                }
            }
        }
    }

    @Test
    public void actualCpuCuboidsAndControllerFaceBudgetCoverBothWorkloads() {
        for (boolean large : new boolean[] { false, true }) {
            AeStorageStressLayout layout = new AeStorageStressLayout(large);
            int controllers = 0, cpuStorage = 0, accelerators = 0, monitors = 0, cpuParts = 0;
            Set<String> controllerPositions = new HashSet<>();
            for (AeStorageStressLayout.Placement p : layout.placements) {
                if (p.kind == AeStorageStressLayout.Kind.CONTROLLER) {
                    controllers++;
                    controllerPositions.add(key(p.x, p.y, p.z));
                }
                if (p.kind == AeStorageStressLayout.Kind.CPU_STORAGE) cpuStorage++;
                if (p.kind == AeStorageStressLayout.Kind.CPU_ACCELERATOR) accelerators++;
                if (p.kind == AeStorageStressLayout.Kind.CPU_MONITOR) monitors++;
                if (p.kind.name()
                    .startsWith("CPU_")) {
                    cpuParts++;
                    int x = p.x - AeStorageStressLayout.x(p.module);
                    int y = p.y - AeStorageStressLayout.y(p.module) - 5;
                    int z = p.z - AeStorageStressLayout.z(p.module);
                    assertTrue(x >= 0 && x < 2 && y >= 0 && y < 2 && z >= 0 && z < 2);
                }
            }
            assertEquals(196, controllers);
            assertEquals(layout.cpus, cpuStorage);
            assertEquals(layout.cpus, accelerators);
            assertEquals(layout.cpus, monitors);
            assertEquals(layout.cpus * 8, cpuParts);
            int exposedFaces = 0;
            int[][] directions = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
            for (AeStorageStressLayout.Placement p : layout.placements) {
                if (p.kind != AeStorageStressLayout.Kind.CONTROLLER) continue;
                for (int[] d : directions)
                    if (!controllerPositions.contains(key(p.x + d[0], p.y + d[1], p.z + d[2]))) exposedFaces++;
            }
            assertEquals(24704, exposedFaces * 32);
            assertTrue(layout.modules * 4 + layout.cpus < exposedFaces * 32);
            assertEquals(large ? 576 : 288, layout.nodes);
            assertEquals(large ? 5632 : 2816, layout.cells);
        }
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }
}
