package com.miaokatze.gtswn.client.engineering;

/** Player-owned onboarding. Only a rendered dashboard completes discovery; viewing a cue never does. */
final class FactoryDashboardHintState {

    static final long EXIT_DELAY = 5_000, DISPLAY_TIME = 12_000;

    private boolean completed, themeShown, factory, factoryPending, worldPending;
    private long worldDue, discoveryStarted = -1, themeStarted = -1;

    FactoryDashboardHintState(boolean completed, boolean themeShown) {
        this.completed = completed;
        this.themeShown = themeShown;
    }

    void observeFactory(boolean active, long now) {
        if (active == factory) return;
        factory = active;
        discoveryStarted = -1;
        if (active) {
            factoryPending = !completed;
            worldPending = false;
        } else {
            factoryPending = false;
            worldPending = !completed;
            worldDue = now + EXIT_DELAY;
        }
    }

    boolean discoveryVisible(long now) {
        if (completed || !(factory ? factoryPending : worldPending && now >= worldDue)) return false;
        if (discoveryStarted < 0) discoveryStarted = now;
        if (now - discoveryStarted < DISPLAY_TIME) return true;
        if (factory) factoryPending = false;
        else worldPending = false;
        return false;
    }

    boolean dashboardOpened() {
        if (completed) return false;
        completed = true;
        factoryPending = worldPending = false;
        discoveryStarted = -1;
        return true;
    }

    boolean themeVisible(long now) {
        return completed && themeStarted >= 0 && now - themeStarted < DISPLAY_TIME;
    }

    /** Called only when the first dashboard frame and its theme cue can actually be drawn. */
    boolean showTheme(long now) {
        if (!completed || themeShown) return false;
        themeShown = true;
        themeStarted = now;
        return true;
    }

    void disconnect() {
        factory = factoryPending = worldPending = false;
        discoveryStarted = themeStarted = -1;
    }
}
