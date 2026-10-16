package com.miaokatze.gtswn.common.util;

/** Primitive-only production boundary: ordinary startup never loads the optional test subsystem. */
public final class DebugTestGate {

    public static final String DISABLED_MESSAGE = "GTSWN agent test harness is disabled. Set testworld.enableAgentTestHarness=true in config/gtswn/gtswn.cfg and restart Minecraft/server.";
    private static boolean latched;
    private static volatile boolean bootEnabled;
    private static volatile boolean probeSessionActive;

    private DebugTestGate() {}

    /** Called once after startup configuration is read. Reloading config cannot enable a running server. */
    public static synchronized void latchAtStartup(boolean configuredEnabled) {
        if (latched) return;
        bootEnabled = configuredEnabled;
        probeSessionActive = false;
        latched = true;
    }

    public static boolean isEnabled() {
        return bootEnabled;
    }

    /** One volatile read/branch at production hooks; no test class, enum, collection or lock is touched. */
    public static boolean probesActive() {
        return probeSessionActive;
    }

    public static void setProbeSessionActive(boolean active) {
        probeSessionActive = bootEnabled && active;
    }

    public static void requireEnabled() {
        if (!bootEnabled) throw new IllegalStateException(DISABLED_MESSAGE);
    }
}
