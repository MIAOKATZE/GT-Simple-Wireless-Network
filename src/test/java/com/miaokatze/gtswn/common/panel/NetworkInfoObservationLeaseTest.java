package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import org.junit.Test;

public class NetworkInfoObservationLeaseTest {

    @Test
    public void shortObservationExpiresAtItsOwnBoundNotLegacyFiveMinutes() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.renewObservation(100, 40);
        assertTrue(data.isRequestActive(100));
        assertTrue(data.isRequestActive(139));
        assertFalse(data.isRequestActive(140));
        assertFalse(data.isRequestActive(139));
    }

    @Test
    public void legacyApiRetainsExact6000TickContract() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.updateRequestTick(100);
        data.renewObservation(100, 40);
        assertTrue(data.isRequestActive(140));
        assertTrue(data.isRequestActive(6099));
        assertFalse(data.isRequestActive(6100));
    }

    @Test
    public void shortRenewalCannotShortenAnotherObserversLease() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.renewObservation(100, 120);
        data.renewObservation(110, 40);
        assertTrue(data.isRequestActive(219));
        assertFalse(data.isRequestActive(220));
    }

    @Test
    public void shortObservationCanOutliveLegacyLeaseWithoutRenewingLegacy() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.updateRequestTick(0);
        data.renewObservation(5990, 40);
        assertTrue(data.isRequestActive(6000));
        assertFalse(data.isRequestActive(6030));
    }

    @Test
    public void rollbackAndFutureRequestCannotReviveOldObservation() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.renewObservation(100, 200);
        assertTrue(data.isRequestActive(200));
        assertFalse(data.isRequestActive(150));
        assertFalse(data.isRequestActive(200));
        data.renewObservation(1000, 100);
        assertFalse(data.isRequestActive(10));
        assertFalse(data.isRequestActive(1000));
        data.renewObservation(1001, 40);
        assertTrue(data.isRequestActive(1001));
    }

    @Test
    public void observationIsBoundedAndNeitherLeaseRestoresFromNbt() {
        NetworkInfoDataSet data = new NetworkInfoDataSet();
        data.renewObservation(10, Long.MAX_VALUE);
        assertTrue(data.isRequestActive(209));
        assertFalse(data.isRequestActive(210));
        data.updateRequestTick(210);
        data.renewObservation(210, 100);
        data.readFromNBT(data.toNBT());
        assertFalse(data.isRequestActive(210));
        data.renewObservation(211, 100);
        data.clear();
        assertFalse(data.isRequestActive(211));
    }
}
