package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import org.junit.Test;

public class AEMonitorInterestTest {

    @Test
    public void observerGraceExpiresAndCanBeRenewed() {
        AEMonitorInterest interest = new AEMonitorInterest();
        Object world = new Object();
        assertFalse(interest.active(world, 100));
        interest.renew(world, 100);
        assertTrue(interest.active(world, 199));
        assertTrue(interest.active(world, 200));
        assertFalse(interest.active(world, 201));
        interest.renew(world, 202);
        assertTrue(interest.active(world, 302));
    }

    @Test
    public void worldReplacementAndRollbackExpireOldCredentials() {
        AEMonitorInterest interest = new AEMonitorInterest();
        Object world = new Object();
        interest.renew(world, 100);
        assertFalse(interest.active(new Object(), 101));
        interest.renew(world, 100);
        assertFalse(interest.active(world, 99));
        assertFalse(interest.active(world, 100));
    }

    @Test
    public void explicitJobLeaseIsBoundedAndIndependentOfViewerDeparture() {
        AEMonitorInterest online = new AEMonitorInterest(), job = new AEMonitorInterest();
        Object world = new Object();
        online.renew(world, 100);
        job.renew(world, 100, Long.MAX_VALUE);
        assertFalse(online.active(world, 201));
        assertTrue(job.active(world, 1300));
        assertFalse(job.active(world, 1301));
    }
}
