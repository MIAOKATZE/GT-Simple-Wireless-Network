package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.*;

import java.io.File;
import java.lang.reflect.Field;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraftforge.common.DimensionManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.client.testworld.UiDebugSuite;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.config.Config;

public class TestHarnessDisabledLifecycleTest {

    private static final int DIMENSION = 118737;
    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();
    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(false);
    private final Map<Field, Object> original = new LinkedHashMap<>();
    private Map<String, TestScenario> scenarios;
    private Map<File, TestWorldReport.WriteReceipt> pending;
    private boolean dimensionBit;

    private Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void replace(Class<?> owner, String name, Object value) throws Exception {
        Field field = field(owner, name);
        if (!original.containsKey(field)) original.put(field, field.get(null));
        field.set(null, value);
    }

    @Before
    @SuppressWarnings("unchecked")
    public void preserveSubsystemState() throws Exception {
        assertFalse(DimensionManager.isDimensionRegistered(DIMENSION));
        assertFalse(((Map<?, ?>) field(DimensionManager.class, "providers").get(null)).containsKey(DIMENSION));
        dimensionBit = ((BitSet) field(DimensionManager.class, "dimensionMap").get(null)).get(DIMENSION);
        replace(Config.class, "testDimensionId", DIMENSION);
        replace(TestDimension.class, "id", Integer.MIN_VALUE);
        replace(TestDimension.class, "problem", "not initialized");
        replace(TestWorldManager.class, "registered", false);
        replace(TestWorldManager.class, "data", null);
        replace(TestWorldManager.class, "phase", "IDLE");
        replace(TestWorldManager.class, "finalWrite", null);
        replace(TestWorldManager.class, "boundReportTarget", null);
        replace(TestWorldManager.class, "reportPath", "");
        replace(TestUiKit.class, "registered", false);
        replace(UiDebugSuite.class, "registered", false);
        Field writer = field(TestWorldReport.class, "writer");
        original.put(writer, writer.get(null));
        scenarios = new LinkedHashMap<>(
            (Map<String, TestScenario>) field(TestWorldManager.class, "SCENARIOS").get(null));
        Map<File, TestWorldReport.WriteReceipt> retained = (Map<File, TestWorldReport.WriteReceipt>) field(
            TestWorldManager.class,
            "PENDING_FINAL_WRITES").get(null);
        pending = new LinkedHashMap<>(retained);
        retained.clear();
    }

    @After
    @SuppressWarnings("unchecked")
    public void restoreSubsystemState() throws Exception {
        TestWorldReport.shutdownWriter();
        if (DimensionManager.isDimensionRegistered(DIMENSION)) DimensionManager.unregisterDimension(DIMENSION);
        DimensionManager.unregisterProviderType(DIMENSION);
        ((BitSet) field(DimensionManager.class, "dimensionMap").get(null)).set(DIMENSION, dimensionBit);
        for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey()
            .set(null, entry.getValue());
        Map<String, TestScenario> registry = (Map<String, TestScenario>) field(TestWorldManager.class, "SCENARIOS")
            .get(null);
        registry.clear();
        registry.putAll(scenarios);
        Map<File, TestWorldReport.WriteReceipt> retained = (Map<File, TestWorldReport.WriteReceipt>) field(
            TestWorldManager.class,
            "PENDING_FINAL_WRITES").get(null);
        retained.clear();
        retained.putAll(pending);
    }

    @Test
    public void disabledStartupLeavesBothDimensionAndProviderSlotsUnused() {
        TestDimension.init(directory.getRoot());
        assertFalse(DimensionManager.isDimensionRegistered(DIMENSION));
        assertTrue(DimensionManager.registerProviderType(DIMENSION, WorldProviderTest.class, false));
        assertFalse(DebugTestGate.isEnabled());
    }

    @Test
    public void disabledLifecycleDoesNotRegisterListenersRecoverJournalOrCreateWorker() throws Exception {
        TestWorldData retained = new TestWorldData("disabled-canary");
        retained.operation = "REPORTING";
        retained.activeRun = "11111111-1111-1111-1111-111111111111";
        File unavailableReport = new File(directory.getRoot(), "must-not-be-written.json");
        retained.lastReport = unavailableReport.getAbsolutePath();
        replace(TestWorldManager.class, "data", retained);
        Object writer = field(TestWorldReport.class, "writer").get(null);
        TestWorldManager.init();
        TestWorldManager.serverStarting();
        TestWorldManager.shutdown();
        TestUiKit.register();
        UiDebugSuite.register();
        TestWorldReport.startWriter();
        TestWorldReport.markInterrupted(unavailableReport, retained.activeRun, DIMENSION);
        assertFalse(field(TestWorldManager.class, "registered").getBoolean(null));
        assertFalse(field(TestUiKit.class, "registered").getBoolean(null));
        assertFalse(field(UiDebugSuite.class, "registered").getBoolean(null));
        assertSame(retained, field(TestWorldManager.class, "data").get(null));
        assertSame(writer, field(TestWorldReport.class, "writer").get(null));
        assertNull(field(TestWorldManager.class, "runPerformance").get(null));
        assertNull(field(TestWorldManager.class, "phasePerformance").get(null));
        assertNull(field(TestWorldManager.class, "tickPerformance").get(null));
        assertEquals("REPORTING", retained.operation);
        assertFalse(unavailableReport.exists());
        TestUiKit.restore(null); // Disabled restoration must not inspect a player or inventory.
        try {
            TestWorldManager.report();
            fail("Disabled journal access was accepted");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("enableAgentTestHarness"));
        }
        try {
            TestUiKit.grant(null);
            fail("Disabled kit grant was accepted");
        } catch (IllegalStateException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("enableAgentTestHarness"));
        }
    }

    @Test
    public void disabledProbeStartCannotActivateProductionHooksOrAllocateASession() throws Exception {
        TestWorldProbes.stop();
        TestWorldProbes.start((type, detail) -> fail("Disabled probe sink was invoked"), DIMENSION);
        DebugTestGate.setProbeSessionActive(true);
        assertFalse(DebugTestGate.probesActive());
        assertFalse(TestWorldProbes.enabled());
        assertNull(field(TestWorldProbes.class, "session").get(null));
    }

    @Test
    public void explicitStartupOptInAllowsDimensionAndRunnerRegistration() throws Exception {
        harness.restart(true);
        TestDimension.init(directory.getRoot());
        assertTrue(DimensionManager.isDimensionRegistered(DIMENSION));
        assertEquals(DIMENSION, DimensionManager.getProviderType(DIMENSION));
        TestWorldManager.serverStarting();
        assertFalse(
            TestWorldManager.ids()
                .isEmpty());
        assertNotNull(field(TestWorldReport.class, "writer").get(null));
        assertNull(field(TestWorldManager.class, "runPerformance").get(null));
        assertNull(field(TestWorldManager.class, "phasePerformance").get(null));
        TestWorldManager.shutdown();
    }
}
