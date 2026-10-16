package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

public class TestWorldFootprintTest {

    @Test
    public void oldAndMalformedJournalsRemainBoundedAndRoundOutward() {
        TestWorldData legacy = restore(new NBTTagCompound());
        assertEquals(32, legacy.authoredRadius);
        for (int[] example : new int[][] { { Integer.MIN_VALUE, 32 }, { 0, 32 }, { 31, 32 }, { 33, 48 }, { 49, 64 },
            { 65, 80 }, { Integer.MAX_VALUE, 80 } }) {
            NBTTagCompound saved = new NBTTagCompound();
            saved.setInteger("authoredRadius", example[0]);
            assertEquals(example[1], restore(saved).authoredRadius);
        }
    }

    @Test
    public void interruptedExpansionSurvivesRestartAndShrinksOnlyAfterCompletedReset() {
        TestWorldData journal = new TestWorldData("large-test");
        assertEquals(
            100,
            journal.beginResetFootprint(80)
                .chunkCount());
        // Persist an interrupted build/run without relying on its operation or report fields.
        journal.operation = "STOPPED";
        TestWorldData restarted = restore(snapshot(journal));
        assertEquals(80, restarted.authoredRadius);
        assertEquals(80, restarted.beginResetFootprint(32).radius);
        assertEquals(80, restore(snapshot(restarted)).authoredRadius);
        restarted.completeResetFootprint(32);
        assertEquals(32, restore(snapshot(restarted)).authoredRadius);
        assertEquals(
            16,
            restarted.beginResetFootprint(32)
                .chunkCount());
    }

    @Test
    public void everyExpandedResetColumnAndChunkIsVisitedExactlyOnceIncludingOuterCorners() {
        TestWorldFootprint footprint = new TestWorldFootprint(80);
        Set<Integer> columns = new HashSet<>();
        Set<Integer> chunks = new HashSet<>();
        for (int cursor = 0; cursor < footprint.blockCount(); cursor += 256) {
            int x = footprint.blockX(cursor);
            int z = footprint.blockZ(cursor);
            assertTrue(x >= -80 && x <= 79);
            assertTrue(z >= -80 && z <= 79);
            assertTrue(columns.add((x + 80) * 160 + z + 80));
            assertEquals(x, footprint.blockX(cursor + 255));
            assertEquals(z, footprint.blockZ(cursor + 255));
        }
        for (int index = 0; index < footprint.chunkCount(); index++) {
            int x = footprint.chunkX(index);
            int z = footprint.chunkZ(index);
            assertTrue(x >= -5 && x <= 4);
            assertTrue(z >= -5 && z <= 4);
            assertTrue(chunks.add((x + 5) * 10 + z + 5));
        }
        assertEquals(25600, columns.size());
        assertEquals(100, chunks.size());
        assertEquals(6553600, footprint.blockCount());
        assertEquals(-80, footprint.blockX(0));
        assertEquals(-80, footprint.blockZ(0));
        assertEquals(79, footprint.blockX(footprint.blockCount() - 1));
        assertEquals(79, footprint.blockZ(footprint.blockCount() - 1));
    }

    @Test
    public void defaultBoundsStaySmallAndExpandedFixtureBoundsRemainHalfOpen() {
        TestWorldFootprint original = new TestWorldFootprint(32);
        assertEquals(16, original.chunkCount());
        assertEquals(1048576, original.blockCount());
        assertEquals(-32, original.blockX(0));
        assertEquals(31, original.blockZ(original.blockCount() - 1));
        assertFalse(TestWorldFootprint.containsFixture(32, -75, 80, 72));
        assertTrue(TestWorldFootprint.containsFixture(80, -80, 64, -80));
        assertTrue(TestWorldFootprint.containsFixture(80, 79, 127, 79));
        assertFalse(TestWorldFootprint.containsFixture(80, -81, 80, 0));
        assertFalse(TestWorldFootprint.containsFixture(80, 80, 80, 0));
        assertFalse(TestWorldFootprint.containsFixture(80, 0, 80, -81));
        assertFalse(TestWorldFootprint.containsFixture(80, 0, 80, 80));
        assertFalse(TestWorldFootprint.containsFixture(80, 0, 63, 0));
        assertFalse(TestWorldFootprint.containsFixture(80, 0, 128, 0));
    }

    @Test
    public void scenarioBoundsRejectMisalignedOrUnboundedRequestsWithoutChangingJournal() {
        TestWorldData journal = new TestWorldData("invalid-test");
        for (int radius : new int[] { Integer.MIN_VALUE, 0, 16, 33, 79, 81, Integer.MAX_VALUE }) {
            try {
                journal.beginResetFootprint(radius);
                fail("Accepted invalid radius " + radius);
            } catch (IllegalArgumentException expected) {
                assertEquals(32, journal.authoredRadius);
            }
        }
    }

    private NBTTagCompound snapshot(TestWorldData journal) {
        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        return saved;
    }

    private TestWorldData restore(NBTTagCompound saved) {
        TestWorldData journal = new TestWorldData("restored-test");
        journal.readFromNBT(saved);
        return journal;
    }
}
