package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class CopyMaterialTransactionTest {

    @Test
    public void missingMaterialCannotInstallOrRefundAnItemThatWasNeverReserved() {
        assertFalse(CopyMaterialTransaction.execute(() -> false, () -> {
            fail("No material means no installation");
            return true;
        }, () -> fail("Nothing was installed"), () -> fail("Nothing was withdrawn")));
    }

    @Test
    public void successfulInstallationConsumesExactlyOneReservation() {
        AtomicInteger materials = new AtomicInteger(2);
        assertTrue(
            CopyMaterialTransaction.execute(
                () -> materials.getAndDecrement() > 0,
                () -> true,
                () -> fail("Successful installation must remain"),
                () -> fail("Committed material cannot be refunded")));
        assertEquals(1, materials.get());
    }

    @Test
    public void declinedInstallationRollsBackBeforeRefundingItsReservation() {
        List<String> operations = new ArrayList<>();
        assertFalse(CopyMaterialTransaction.execute(() -> {
            operations.add("reserve");
            return true;
        }, () -> {
            operations.add("install");
            return false;
        }, () -> operations.add("rollback"), () -> operations.add("refund")));
        assertEquals(Arrays.asList("reserve", "install", "rollback", "refund"), operations);
    }

    @Test
    public void exceptionAfterAttachmentRemovesTheNewCoverAndReturnsTheMaterial() {
        AtomicInteger attached = new AtomicInteger();
        AtomicInteger materials = new AtomicInteger(1);
        IllegalStateException failure = new IllegalStateException("configuration failed");
        try {
            CopyMaterialTransaction.execute(() -> materials.decrementAndGet() == 0, () -> {
                attached.incrementAndGet();
                throw failure;
            }, () -> attached.decrementAndGet(), () -> materials.incrementAndGet());
            fail("The caller must observe installation failure");
        } catch (IllegalStateException expected) {
            assertSame(failure, expected);
        }
        assertEquals(0, attached.get());
        assertEquals(1, materials.get());
    }

    @Test
    public void failedRollbackCannotRefundAStillInstalledCover() {
        try {
            CopyMaterialTransaction.execute(
                () -> true,
                () -> false,
                () -> { throw new IllegalStateException("cover still attached"); },
                () -> fail("Refunding here would leave a free installed cover"));
            fail("Rollback failure must be observable");
        } catch (IllegalStateException expected) {
            assertEquals("cover still attached", expected.getMessage());
        }
    }

    @Test
    public void optionalWirelessFundingFailureDoesNotCancelInstallationOrRefundRequiredMaterial() {
        AtomicInteger materials = new AtomicInteger(1);
        AtomicInteger attached = new AtomicInteger();
        assertTrue(CopyMaterialTransaction.execute(() -> materials.decrementAndGet() == 0, () -> {
            attached.incrementAndGet();
            long funded = com.miaokatze.gtswn.common.covers.WirelessCoverCopy.refillBuffer(7, 100, 0.1, cost -> false);
            assertEquals(7, funded);
            return true;
        }, () -> fail("Optional funding must not remove the cover"), () -> fail("The cover was installed")));
        assertEquals(0, materials.get());
        assertEquals(1, attached.get());
    }
}
