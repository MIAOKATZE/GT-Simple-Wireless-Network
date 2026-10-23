package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

public class FactoryMachineFilterTest {

    @Test
    public void disabledMachineNeedsAttentionButIgnoredMachineDoesNot() {
        Entry disabled = entry(2, 0, 0, 0);
        Entry ignored = entry(3, 3, 64, 64);
        assertTrue(matches(FactoryMachineFilter.STOPPED, disabled, true));
        assertTrue(matches(FactoryMachineFilter.ATTENTION, disabled, true));
        assertTrue(matches(FactoryMachineFilter.IGNORED, ignored, true));
        assertFalse(matches(FactoryMachineFilter.ATTENTION, ignored, true));
        assertEquals("ignored", FactoryMachineFilter.status(ignored, true));
    }

    @Test
    public void staleSnapshotsCannotConfirmCurrentRunningOrFaultState() {
        for (int state = 0; state <= 2; state++) {
            Entry historical = entry(state, 3, 0, 0);
            assertTrue(matches(FactoryMachineFilter.ALL, historical, false));
            assertTrue(matches(FactoryMachineFilter.STALE, historical, false));
            assertFalse(matches(FactoryMachineFilter.RUNNING, historical, false));
            assertFalse(matches(FactoryMachineFilter.IDLE, historical, false));
            assertFalse(matches(FactoryMachineFilter.STOPPED, historical, false));
            assertFalse(matches(FactoryMachineFilter.UNKNOWN, historical, false));
            assertFalse(matches(FactoryMachineFilter.ATTENTION, historical, false));
            assertEquals("stale", FactoryMachineFilter.status(historical, false));
        }
    }

    @Test
    public void warmupIsNotAProblemAndUnknownPowerDoesNotEraseRunningState() {
        Entry warming = entry(1, 2, 0, 0);
        Entry unknownPower = entry(1, 3, 0, 0);
        assertFalse(matches(FactoryMachineFilter.ATTENTION, warming, true));
        assertTrue(matches(FactoryMachineFilter.RUNNING, unknownPower, true));
        assertTrue(matches(FactoryMachineFilter.UNKNOWN, unknownPower, true));
        assertTrue(matches(FactoryMachineFilter.ATTENTION, unknownPower, true));
        assertEquals("running", FactoryMachineFilter.status(unknownPower, true));
    }

    @Test
    public void stateAndObservedDirectionCanBeCombinedForBidirectionalMachine() {
        Entry running = entry(1, 0, 64, 64);
        assertTrue(FactoryMachineFilter.RUNNING.matches(running, true, DevicePowerFilter.GENERATE_EU));
        assertTrue(FactoryMachineFilter.RUNNING.matches(running, true, DevicePowerFilter.CONSUME_EU));
        assertFalse(FactoryMachineFilter.IDLE.matches(running, true, DevicePowerFilter.GENERATE_EU));
    }

    private static boolean matches(FactoryMachineFilter filter, Entry entry, boolean fresh) {
        return filter.matches(entry, fresh, DevicePowerFilter.ALL);
    }

    private static Entry entry(int state, int evidence, long in, long out) {
        return new Entry(
            "0:1:2:3",
            "Machine",
            (byte) state,
            (byte) 0,
            in,
            out,
            in,
            out,
            0,
            1,
            2,
            3,
            "",
            "",
            -1,
            (byte) evidence,
            (byte) 0);
    }
}
