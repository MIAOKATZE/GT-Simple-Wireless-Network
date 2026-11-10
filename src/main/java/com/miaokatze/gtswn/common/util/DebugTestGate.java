package com.miaokatze.gtswn.common.util;

/** Passive production boundary: ordinary startup never loads the optional test subsystem. */
public final class DebugTestGate {

    public static final String DISABLED_MESSAGE = "GTSWN agent test harness is disabled. Set testworld.enableAgentTestHarness=true in config/gtswn/gtswn.cfg and restart Minecraft/server.";

    public interface Harness {

        void preInit(java.io.File configDir);

        void init();

        void registerBlocks();

        void serverStarting();

        void clientInit();

        void shutdown();

        void execute(net.minecraft.command.ICommandSender sender, String[] args);

        java.util.List<String> complete(String[] args);
    }

    private static Harness harness;
    private static boolean latched;
    private static volatile boolean bootEnabled;
    private static volatile boolean probeSessionActive;

    private DebugTestGate() {}

    /** Called once after startup configuration is read. Reloading config cannot enable a running server. */
    public static synchronized void latchAtStartup(boolean configuredEnabled) {
        if (latched) return;
        if (configuredEnabled) {
            try {
                harness = (Harness) Class.forName("com.miaokatze.gtswn.common.testworld.TestLabBootstrap")
                    .getDeclaredConstructor()
                    .newInstance();
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IllegalStateException(
                    "GTSWN test harness requested but test support is unavailable. Replace normal GTSWN and MiaoKatzeCore JARs with their matching -testlab variants (same mod IDs; do not install both).",
                    failure);
            }
        }
        bootEnabled = configuredEnabled;
        probeSessionActive = false;
        latched = true;
    }

    public static Harness harness() {
        requireEnabled();
        return harness;
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
