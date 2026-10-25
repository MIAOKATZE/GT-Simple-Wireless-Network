package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FactoryDashboardHintStateTest {

    @Test
    public void worldHasNoDiscoveryUntilFactoryWasEntered() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(false, 0);
        assertFalse(state.discoveryVisible(100_000));
    }

    @Test
    public void unreadFactoryCueSurvivesExitAndWaitsExactlyFiveSeconds() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(true, 0);
        // No render during factory mode (F1, another GUI, or immediate departure).
        state.observeFactory(false, 200);
        assertFalse(state.discoveryVisible(5_199));
        assertTrue(state.discoveryVisible(5_200));
        assertTrue(state.discoveryVisible(17_199));
        assertFalse(state.discoveryVisible(17_200));
        assertFalse(state.discoveryVisible(100_000));
        state.observeFactory(false, 100_100);
        assertFalse(state.discoveryVisible(100_100));
    }

    @Test
    public void readingCueNeverCompletesDiscoveryAndFallbackRemainsBounded() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(true, 10);
        assertTrue(state.discoveryVisible(10));
        assertFalse(state.discoveryVisible(12_010));
        assertFalse(state.discoveryVisible(13_000));
        state.observeFactory(false, 14_000);
        assertFalse(state.discoveryVisible(18_999));
        assertTrue(state.discoveryVisible(19_000));
        assertFalse(state.discoveryVisible(31_000));
        state.observeFactory(true, 32_000);
        assertTrue(state.discoveryVisible(32_000));
    }

    @Test
    public void openingDashboardCancelsBothFactoryAndPendingWorldDiscoveryForever() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(true, 0);
        state.observeFactory(false, 10);
        assertTrue(state.dashboardOpened());
        assertFalse(state.dashboardOpened());
        assertFalse(state.discoveryVisible(5_010));
        state.observeFactory(true, 10_000);
        assertFalse(state.discoveryVisible(10_000));
        state.observeFactory(false, 11_000);
        assertFalse(state.discoveryVisible(16_000));
    }

    @Test
    public void reenteringFactoryCancelsOldFallbackAndExitRestartsItsDelay() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(true, 0);
        state.observeFactory(false, 100);
        state.observeFactory(true, 1_000);
        assertTrue(state.discoveryVisible(5_100));
        state.observeFactory(false, 6_000);
        assertFalse(state.discoveryVisible(10_999));
        assertTrue(state.discoveryVisible(11_000));
    }

    @Test
    public void themeHasIndependentPersistedOneTimeFlagAndRequiresSuccessfulDashboard() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        assertFalse(state.showTheme(0));
        assertFalse(state.themeVisible(0));
        assertTrue(state.dashboardOpened());
        // Successful dashboard completion alone does not consume the theme cue.
        assertTrue(state.showTheme(1_000));
        assertTrue(state.themeVisible(12_999));
        assertFalse(state.showTheme(12_999));
        assertFalse(state.themeVisible(13_000));
        state.disconnect();
        assertFalse(state.showTheme(14_000));
        assertFalse(state.themeVisible(14_000));
    }

    @Test
    public void legacyDiscoveryCompletionStillAllowsFirstThemeCue() {
        FactoryDashboardHintState legacy = new FactoryDashboardHintState(true, false);
        legacy.observeFactory(true, 0);
        assertFalse(legacy.discoveryVisible(0));
        assertFalse(legacy.dashboardOpened());
        assertTrue(legacy.showTheme(50));
        assertTrue(legacy.themeVisible(50));
        FactoryDashboardHintState bothDone = new FactoryDashboardHintState(true, true);
        assertFalse(bothDone.showTheme(50));
        assertFalse(bothDone.themeVisible(50));
    }

    @Test
    public void disconnectDropsSessionRemindersWithoutCompletingDiscovery() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(true, 0);
        state.observeFactory(false, 100);
        state.disconnect();
        assertFalse(state.discoveryVisible(5_100));
        state.observeFactory(true, 6_000);
        assertTrue(state.discoveryVisible(6_000));
    }

    @Test
    public void displayBudgetStartsOnActualVisibleRenderNotHiddenElapsedTime() {
        FactoryDashboardHintState state = new FactoryDashboardHintState(false, false);
        state.observeFactory(true, 0);
        assertTrue(state.discoveryVisible(100_000));
        assertTrue(state.discoveryVisible(111_999));
        assertFalse(state.discoveryVisible(112_000));
    }
}
