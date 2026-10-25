package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.Test;

public class GridDialMotionTest {

    @Test
    public void chargeAndDischargeMoveOppositeWaysAndUnknownAndZeroStayStill() {
        double charge = GridDialMotion.phase(1, BigDecimal.TEN);
        double discharge = GridDialMotion.phase(1, BigDecimal.TEN.negate());
        assertTrue(charge > 0 && charge < .5);
        assertEquals(1, charge + discharge, 1e-12);
        assertTrue(GridDialMotion.phase(2, BigDecimal.TEN) > charge);
        assertEquals(0, GridDialMotion.phase(100, null), 0);
        assertEquals(0, GridDialMotion.phase(100, BigDecimal.ZERO), 0);
        assertEquals(.25, GridDialMotion.phase(1, new BigDecimal(BigInteger.TEN.pow(400))), 0);
    }
}
