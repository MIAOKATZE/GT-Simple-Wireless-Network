package com.miaokatze.gtswn.crossmod.mm;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public class IncorporationCopyCostTest {

    @Test
    public void successfulNewEntryConsumesOneAnchorAndRepeatedRestorePreservesIt() {
        Target target = new Target();
        assertTrue(
            target.apply(false)
                .created());
        assertEquals(1, target.anchors.get());
        assertEquals(1, target.installs.get());
        assertTrue(
            target.apply(false)
                .successful());
        assertFalse(
            target.apply(false)
                .created());
        assertEquals(1, target.anchors.get());
        assertEquals(1, target.installs.get());
        assertEquals(0, target.refunds.get());
    }

    @Test
    public void unavailableAnchorNeverRunsInstallation() {
        Target target = new Target();
        target.available = false;
        IncorporationCopyCost.Result result = target.apply(false);
        assertFalse(result.successful());
        assertTrue(result.missingMaterial());
        assertEquals(0, target.installs.get());
        assertEquals(0, target.refunds.get());
        assertFalse(target.live.get());
    }

    @Test
    public void failedInstallRefundsAndDoesNotReportSuccess() {
        Target target = new Target();
        target.create = false;
        IncorporationCopyCost.Result result = target.apply(false);
        assertFalse(result.successful());
        assertFalse(result.missingMaterial());
        assertEquals(0, target.anchors.get());
        assertEquals(1, target.refunds.get());
    }

    @Test
    public void exceptionBeforeRegistrationRefundsButExceptionAfterRegistrationCommits() {
        Target before = new Target();
        before.create = false;
        before.fail = true;
        IncorporationCopyCost.Result failed = before.apply(false);
        assertFalse(failed.successful());
        assertNotNull(failed.failure());
        assertEquals(0, before.anchors.get());
        assertEquals(1, before.refunds.get());

        Target after = new Target();
        after.fail = true;
        IncorporationCopyCost.Result committed = after.apply(false);
        assertTrue(committed.created());
        assertNotNull(committed.failure());
        assertTrue(after.live.get());
        assertEquals(1, after.anchors.get());
        assertEquals(0, after.refunds.get());
    }

    @Test
    public void creativePlacementNeverReservesOrRefundsRealMaterials() {
        Target target = new Target();
        target.available = false;
        assertTrue(
            target.apply(true)
                .created());
        assertEquals(0, target.anchors.get());
        assertEquals(1, target.installs.get());
        target.live.set(false);
        target.create = false;
        assertFalse(
            target.apply(true)
                .successful());
        assertEquals(0, target.refunds.get());
    }

    @Test
    public void unknownRegistrationStateDoesNotRefundAndLeaveAPotentiallyFreeEntry() {
        AtomicInteger refunds = new AtomicInteger();
        try {
            IncorporationCopyCost.apply(
                false,
                false,
                () -> true,
                () -> {},
                () -> { throw new IllegalStateException("registry inaccessible"); },
                refunds::incrementAndGet);
            fail("The registration check must remain visible to the caller");
        } catch (IllegalStateException expected) {
            assertEquals(0, refunds.get());
        }
    }

    private static final class Target {

        final AtomicBoolean live = new AtomicBoolean();
        final AtomicInteger anchors = new AtomicInteger(), refunds = new AtomicInteger(),
            installs = new AtomicInteger();
        boolean available = true, create = true, fail;

        IncorporationCopyCost.Result apply(boolean creative) {
            return IncorporationCopyCost.apply(live.get(), creative, () -> {
                if (!available) return false;
                anchors.incrementAndGet();
                return true;
            }, () -> {
                installs.incrementAndGet();
                if (create) live.set(true);
                if (fail) throw new IllegalStateException("native bridge update");
            }, live::get, () -> {
                assertFalse(live.get());
                refunds.incrementAndGet();
                anchors.decrementAndGet();
            });
        }
    }
}
