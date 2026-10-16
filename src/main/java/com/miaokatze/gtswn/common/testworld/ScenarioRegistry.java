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
        TestWorldManager.register(new QuantumAnchorLifecycleScenario());
        TestWorldManager.register(new ManualClientScenario());
        TestWorldManager.register(new QuantumStressScenario());
        TestWorldManager.register(new AeStorageStressScenario(false));
        TestWorldManager.register(new AeStorageStressScenario(true));
        TestWorldManager.register(new AeMachineStressScenario());
        TestWorldManager.register(new AeMachineStressScenario(AeMachineStressLayout.Profile.TWENTY_FIVE_HUNDRED));
        TestWorldManager.register(new TestScenario() {

            @Override
            public String id() {
                return "performance.baseline";
            }

            @Override
            public String description() {
                return "Explicit-only 200 natural ticks with quiescent authored fixtures; contextual baseline, not mod-exclusive cost";
            }

            @Override
            public boolean includeInAll() {
                return false;
            }

            @Override
            public void start(TestContext context) {
                context.event(
                    "performance_scope",
                    "Quiescent fixture world; all server/mod work still runs. Compare only matched runtime conditions; no causal attribution or fixed performance threshold.");
            }

            @Override
            public void tick(TestContext context) {
                if (context.tick() >= 200)
                    context.pass("Observed 200 natural ticks of quiescent fixtures; no performance assertion");
            }
        });
    }
}
