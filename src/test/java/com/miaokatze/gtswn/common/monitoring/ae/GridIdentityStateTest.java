package com.miaokatze.gtswn.common.monitoring.ae;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

public class GridIdentityStateTest {

    private static void settle(GridIdentityState state) {
        state.update(false);
        state.update(false);
    }

    private static GridIdentityState restored(UUID id) {
        GridIdentityState state = new GridIdentityState();
        state.join(new GridIdentityState.Origin(id));
        state.addNode();
        return state;
    }

    @Test
    public void ordinaryReloadResumesAndReadsNeverMint() {
        UUID saved = UUID.randomUUID();
        GridIdentityState state = restored(saved);
        assertNull(state.current(false));
        assertNull(state.current(false));
        state.update(true);
        assertNull(state.current(true));
        settle(state);
        assertEquals(saved, state.current(false));
        assertEquals(saved, state.saved());
        for (int i = 0; i < 100; i++) {
            state.update(false);
            assertEquals(saved, state.current(false));
        }
    }

    @Test
    public void sameOriginBootSplitReunionPreservesSavedIdentity() {
        UUID saved = UUID.randomUUID();
        GridIdentityState first = restored(saved);
        GridIdentityState second = new GridIdentityState();
        second.join(first.split());
        second.addNode();
        first.update(true);
        second.update(true);
        second.removeNode();
        first.join(second.transfer());
        settle(first);
        assertEquals(saved, first.current(false));
    }

    @Test
    public void survivingBootFragmentsBothRotateNotOnlyFirstSettled() {
        UUID saved = UUID.randomUUID();
        GridIdentityState first = restored(saved);
        GridIdentityState second = new GridIdentityState();
        second.join(first.split());
        second.addNode();
        settle(first);
        settle(second);
        first.update(false);
        assertNotEquals(saved, first.current(false));
        assertNotEquals(saved, second.current(false));
        assertNotEquals(first.current(false), second.current(false));
    }

    @Test
    public void distinctEstablishedMergeCannotInheritEitherConsent() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        GridIdentityState first = restored(a), second = restored(b);
        settle(first);
        settle(second);
        second.removeNode();
        first.join(second.transfer());
        assertNull(first.current(false));
        assertNull(first.saved());
        settle(first);
        assertNotEquals(a, first.current(false));
        assertNotEquals(b, first.current(false));
    }

    @Test
    public void newCableGridDoesNotInvalidateEstablishedSource() {
        UUID saved = UUID.randomUUID();
        GridIdentityState first = restored(saved);
        settle(first);
        GridIdentityState cable = new GridIdentityState();
        cable.addNode();
        cable.removeNode();
        first.join(cable.transfer());
        first.addNode();
        settle(first);
        assertEquals(saved, first.current(false));
    }

    @Test
    public void plainRemovalWithoutTransferPreservesSurvivingIdentity() {
        UUID saved = UUID.randomUUID();
        GridIdentityState first = restored(saved);
        first.addNode();
        settle(first);
        first.transfer(); // Native saving must not erase publication evidence.
        first.removeNode();
        assertNull(first.current(false));
        assertEquals(saved, first.saved());
        settle(first);
        assertEquals(saved, first.current(false));
    }

    @Test
    public void newSourceMintsOnceOnlyAfterSettling() {
        GridIdentityState state = new GridIdentityState();
        state.addNode();
        assertNull(state.current(false));
        assertNull(state.saved());
        settle(state);
        UUID id = state.current(false);
        assertNotNull(id);
        assertEquals(id, state.transfer().id);
        assertEquals(id, state.current(false));
    }

    @Test
    public void unavailablePathingNeverPublishesEvenPreviouslySettled() {
        GridIdentityState state = restored(UUID.randomUUID());
        settle(state);
        assertNull(state.current(true));
        state.update(true);
        assertNull(state.current(false));
        settle(state);
        assertNotNull(state.current(false));
    }

    @Test
    public void earlierSettledFragmentWaitsForBootingPeerAndCanReunite() {
        UUID saved = UUID.randomUUID();
        GridIdentityState first = restored(saved), second = new GridIdentityState();
        second.join(first.split());
        second.addNode();
        settle(first);
        second.update(true);
        assertNull(first.current(false));
        assertFalse(first.transfer().retired);
        second.removeNode();
        first.join(second.transfer());
        settle(first);
        assertEquals(saved, first.current(false));
    }

}
