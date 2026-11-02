package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.Payment;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport.PaymentSource;

public class DeviceTerminalPaymentTest {

    @Test
    public void reserveThenMainInventoryThenExperienceIsFixedIncludingZeroFee() {
        for (int cost : new int[] { 0, 1, 1000000 }) {
            assertPayment(PaymentSource.RESERVE, 1, DeviceTerminalTeleport.selectPayment(1, true, cost));
            assertPayment(PaymentSource.RESERVE, 1, DeviceTerminalTeleport.selectPayment(640, false, cost));
            assertPayment(PaymentSource.INVENTORY, 1, DeviceTerminalTeleport.selectPayment(0, true, cost));
            assertPayment(
                cost == 0 ? PaymentSource.FREE : PaymentSource.EXPERIENCE,
                cost,
                DeviceTerminalTeleport.selectPayment(0, false, cost));
        }
    }

    @Test
    public void depletedLastAnchorAndChangedRateRequireAnotherConfirmation() {
        Payment lastReserve = DeviceTerminalTeleport.selectPayment(1, false, 1);
        Payment backpack = DeviceTerminalTeleport.selectPayment(0, true, 1);
        Payment experience = DeviceTerminalTeleport.selectPayment(0, false, 1);
        assertFalse(lastReserve.same(backpack));
        assertFalse(lastReserve.same(experience));
        assertFalse(backpack.same(experience));
        assertFalse(experience.same(DeviceTerminalTeleport.selectPayment(0, false, 2)));
        assertFalse(experience.same(DeviceTerminalTeleport.selectPayment(0, false, 0)));
        assertTrue(lastReserve.same(DeviceTerminalTeleport.selectPayment(640, true, 0)));
        assertTrue(experience.same(DeviceTerminalTeleport.selectPayment(0, false, 1)));
    }

    private static void assertPayment(PaymentSource source, int amount, Payment actual) {
        assertEquals(source, actual.source);
        assertEquals(amount, actual.amount);
    }
}
