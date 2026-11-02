package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class StressMachineLayoutTest {

    @Test
    public void nativeFluidPortsAreDirectionalAndRespectRestrictedHatchAdders() {
        for (StressMachineLayout.Definition definition : StressMachineLayout.MULTIS) {
            if (definition.type.equals("MTELargeFluidExtractor")) {
                assertFalse(definition.fluidInput);
                assertTrue(definition.fluidOutput);
            } else if (definition.type.equals("MTEIndustrialFormingPress")) {
                assertTrue(definition.fluidInput);
                assertFalse(definition.fluidOutput);
            }
        }
        assertTrue(StressMachineLayout.MEGA_CHEMICAL.fluidInput);
        assertTrue(StressMachineLayout.MEGA_CHEMICAL.fluidOutput);
        assertTrue(StressMachineLayout.ORE_FACTORY.fluidInput);
        assertFalse(StressMachineLayout.ORE_FACTORY.fluidOutput);
        assertFalse(StressMachineLayout.PARALLEL_FURNACE.fluidInput);
        assertFalse(StressMachineLayout.PARALLEL_FURNACE.fluidOutput);
    }

    @Test
    public void all448RealMachineEnvelopesAndConnectionBordersAreDisjointAndBounded() {
        Set<String> occupied = new HashSet<>();
        int singles = 0, multis = 0, generators = 0;
        for (int index = 0; index < StressMachineLayout.COUNT; index++) {
            StressMachineLayout.Slot slot = StressMachineLayout.slot(index);
            if (index < StressMachineLayout.SINGLE_COUNT) singles++;
            else if (index < StressMachineLayout.SINGLE_COUNT + StressMachineLayout.MULTI_COUNT) multis++;
            else generators++;
            assertTrue(slot.minX >= -80 && slot.maxX < 80 && slot.minZ >= -80 && slot.maxZ < 80);
            assertTrue(slot.minY >= 64 && slot.maxY <= 255);
            assertTrue(slot.x >= slot.minX && slot.x <= slot.maxX);
            assertTrue(slot.y >= slot.minY && slot.y <= slot.maxY);
            assertTrue(slot.z >= slot.minZ && slot.z <= slot.maxZ);
            // Reserve the entire authored cell at all occupied heights, plus native attachment headroom.
            for (int x = slot.cellX; x < slot.cellX + slot.cellWidth; x++)
                for (int z = slot.cellZ; z < slot.cellZ + slot.cellWidth; z++)
                    for (int y = slot.minY - 1; y <= slot.maxY + 1; y++) {
                        assertTrue(x >= -80 && x < 80 && z >= -80 && z < 80 && y >= 64 && y <= 255);
                        String position = x + ":" + y + ":" + z;
                        assertTrue(
                            "Overlapping fixture / native connection cell at " + position,
                            occupied.add(position));
                    }
        }
        assertEquals(128, singles);
        assertEquals(256, multis);
        assertEquals(64, generators);
    }

    @Test
    public void nativeNorthOffsetsPreserveTheActualStructureAxes() {
        for (int index = StressMachineLayout.SINGLE_COUNT; index
            < StressMachineLayout.SINGLE_COUNT + StressMachineLayout.MULTI_COUNT; index++) {
            StressMachineLayout.Slot slot = StressMachineLayout.slot(index);
            StressMachineLayout.Definition d = slot.definition;
            assertEquals(slot.maxX, slot.x + d.offsetX);
            assertEquals(slot.minX, slot.x - (d.width - 1 - d.offsetX));
            assertEquals(slot.maxY, slot.y + d.offsetY);
            assertEquals(slot.minY, slot.y - (d.height - 1 - d.offsetY));
            assertEquals(slot.minZ, slot.z - d.offsetZ);
            assertEquals(slot.maxZ, slot.z + d.depth - 1 - d.offsetZ);
            assertTrue(slot.containsConnection(slot.minX - 1, slot.y, slot.minZ));
            assertTrue(slot.containsConnection(slot.minX, slot.maxY + 1, slot.minZ));
            assertFalse(slot.containsConnection(slot.cellX + slot.cellWidth, slot.y, slot.minZ));
        }
    }

    @Test
    public void retainsNineteenMultiblockClassesWithExplicitHighThroughputAndLargeStructures() {
        Map<String, Integer> counts = new HashMap<>();
        for (int local = 0; local < StressMachineLayout.MULTI_COUNT; local++) {
            StressMachineLayout.Slot slot = StressMachineLayout.slot(StressMachineLayout.SINGLE_COUNT + local);
            counts.merge(slot.definition.type, 1, Integer::sum);
        }
        assertEquals(19, counts.size());
        for (int i = 0; i < StressMachineLayout.MULTIS.length; i++)
            assertEquals(Integer.valueOf(i < 8 ? 14 : 13), counts.get(StressMachineLayout.MULTIS[i].type));
        assertEquals(Integer.valueOf(16), counts.get("MTEMultiFurnace"));
        assertEquals(Integer.valueOf(16), counts.get("MTEMegaChemicalReactor"));
        assertEquals(Integer.valueOf(8), counts.get("MTEIntegratedOreFactory"));
        for (int local = 216; local < 224; local++) {
            StressMachineLayout.Slot slot = StressMachineLayout.slot(StressMachineLayout.SINGLE_COUNT + local);
            assertEquals(20, slot.cellWidth);
            assertEquals(15, slot.definition.width);
            assertEquals(13, slot.definition.depth);
            assertEquals(160, slot.minY);
            assertEquals(168, slot.maxY);
        }
        int largeGenerators = 0;
        for (int index = 384; index < 448; index++)
            if (StressMachineLayout.slot(index).definition == StressMachineLayout.LARGE_GENERATOR) largeGenerators++;
        assertEquals(16, largeGenerators);
    }
}
