package com.miaokatze.gtswn.common.hud;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.common.monitoring.WirelessEUPayload;

public class WirelessEUPayloadTest {

    private static String payload(UUID epoch, UUID account, long tick, String amount) {
        return WirelessEUPayload.PREFIX + epoch + ":" + account + ":" + tick + ":" + amount;
    }

    @Test
    public void malformedAndUnknownMessagesAreNotZeroObservations() {
        for (String text : new String[] { "", "invalid", "$gtswn:eu3:0", "$gtswn:denied", "01", "+1", "0:0" })
            assertNull(WirelessEUPayload.parse(text));
        assertEquals(BigInteger.ZERO, WirelessEUPayload.parse("0").amount);
        String large = new String(new char[WirelessEUPayload.MAX_AMOUNT + 1]).replace('\0', '9');
        assertNull(WirelessEUPayload.parse(large));
    }

    @Test
    public void nativeAccountAndEpochChangesResetHistoryButOrdinarySamplesDoNot() {
        HudState state = new HudState();
        UUID epoch = UUID.randomUUID(), account = UUID.randomUUID();
        assertTrue(state.acceptMeasurement(WirelessEUPayload.parse(payload(epoch, account, 10, "100")), 999));
        assertTrue(state.acceptMeasurement(WirelessEUPayload.parse(payload(epoch, account, 20, "200")), 1000));
        assertEquals(2, state.dataSet.size());
        assertEquals(
            10,
            state.dataSet.snapshot()
                .get(0).tick);
        assertFalse(state.acceptMeasurement(WirelessEUPayload.parse(payload(epoch, account, 20, "200")), 1001));
        assertFalse(state.acceptMeasurement(WirelessEUPayload.parse("0"), 1001));
        assertFalse(state.acceptMeasurement(null, 1001));
        assertEquals(2, state.dataSet.size());
        assertTrue(
            state.acceptMeasurement(WirelessEUPayload.parse(payload(epoch, UUID.randomUUID(), 30, "900")), 1002));
        assertEquals(1, state.dataSet.size());
        assertTrue(
            state.acceptMeasurement(WirelessEUPayload.parse(payload(UUID.randomUUID(), account, 1, "50")), 1003));
        assertEquals(1, state.dataSet.size());
        assertEquals(
            BigInteger.valueOf(50),
            state.dataSet.snapshot()
                .get(0).eu);
    }

    @Test
    public void legacyFallbackAndSameConnectionDimensionTransferKeepTheirExistingContract() {
        HudState state = new HudState();
        assertTrue(state.acceptMeasurement(WirelessEUPayload.parse("10"), 100));
        assertTrue(state.acceptMeasurement(WirelessEUPayload.parse("20"), 200));
        assertEquals(2, state.dataSet.size());
        UUID epoch = UUID.randomUUID(), account = UUID.randomUUID();
        assertTrue(state.acceptMeasurement(WirelessEUPayload.parse(payload(epoch, account, 300, "30")), 300));
        state.resetForSession(true);
        assertTrue(state.acceptMeasurement(WirelessEUPayload.parse(payload(epoch, account, 400, "40")), 400));
        assertEquals(2, state.dataSet.size());
        state.resetForSession(false);
        assertNull(state.historySourceToken);
        assertTrue(state.dataSet.isEmpty());
    }
}
