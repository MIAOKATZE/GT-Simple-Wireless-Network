package com.miaokatze.gtswn.common.testworld;

/** Stable individually runnable scenario IDs. Each scenario provisions its own minimal real fixture. */
public final class ScenarioRegistry {

    private ScenarioRegistry() {}

    public static void register() {
        TestWorldManager.register(new WirelessEuScenario(false));
        TestWorldManager.register(new WirelessEuScenario(true));
        TestWorldManager.register(new EnergyMonitorScenario());
        TestWorldManager.register(new PanelStructureScenario());
        TestWorldManager.register(new PanelAeSamplingScenario());
        TestWorldManager.register(new TerminalRenameScenario());
        TestWorldManager.register(new DeviceWorldScenario());
        TestWorldManager.register(new PortableMonitorScenario());
        TestWorldManager.register(new WirelessTapScenario());
        TestWorldManager.register(new QuantumWorldScenario(QuantumWorldScenario.Mode.BRIDGE));
        TestWorldManager.register(new QuantumWorldScenario(QuantumWorldScenario.Mode.MIGRATION));
        TestWorldManager.register(new QuantumWorldScenario(QuantumWorldScenario.Mode.INCORPORATION));
        TestWorldManager.register(new QuantumWorldScenario(QuantumWorldScenario.Mode.SAFETY));
        TestWorldManager.register(new NativeSecurityScenario(NativeSecurityScenario.Mode.PERMISSIONS));
        TestWorldManager.register(new NativeSecurityScenario(NativeSecurityScenario.Mode.RECOVERY));
        TestWorldManager.register(new ManualClientScenario());
    }
}
