package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class TestWorldFootprintLifecycleTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);
    private final Map<Field, Object> original = new LinkedHashMap<>();
    private TestWorldData journal;

    @Before
    public void preserveManager() throws Exception {
        for (String name : new String[] { "data", "selection", "scenarioIndex", "report", "phase", "current", "world",
            "requestedRadius", "resetFootprint", "phasePerformance", "closedResetPerformance", "loadIndex",
            "resetCursor", "operationTicks", "finalWrite", "boundReportTarget" }) {
            Field field = field(TestWorldManager.class, name);
            original.put(field, field.get(null));
        }
        Field problem = field(TestDimension.class, "problem");
        original.put(problem, problem.get(null));
        problem.set(null, null);
        journal = new TestWorldData("footprint-lifecycle");
        set("data", journal);
        set("report", null);
        set("current", null);
        set("world", null);
        set("finalWrite", null);
        set("boundReportTarget", null);
        set("scenarioIndex", 0);
    }

    @After
    public void restoreManager() throws Exception {
        for (Map.Entry<Field, Object> entry : original.entrySet()) entry.getKey()
            .set(null, entry.getValue());
    }

    @Test
    public void stoppedLargeResetLeavesRecoveryBoundaryForNextBuildAndStatus() throws Exception {
        set("selection", Arrays.asList(scenario("large", 80, false)));
        beginReset();
        assertEquals(80, journal.authoredRadius);
        assertEquals(80, footprint().radius);
        String status = TestWorldManager.status();
        assertTrue(status.contains("x/z=-80..79"));
        assertTrue(status.contains("chunks=0/100"));
        assertTrue(status.contains("reset=0/6553600"));
        assertTrue(
            TestWorldManager.report()
                .contains("scan=x/z=-80..79"));
        TestWorldManager.stop("administrator cancelled while loading outer chunks");
        assertFalse(TestWorldManager.busy());
        assertFalse(journal.built);
        assertEquals(80, journal.authoredRadius);

        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        journal = new TestWorldData("after-restart");
        journal.readFromNBT(saved);
        set("data", journal);
        set("selection", null); // Both build and reset use this path.
        beginReset();
        assertEquals(32, field(TestWorldManager.class, "requestedRadius").getInt(null));
        assertEquals(80, footprint().radius);
        assertEquals(80, journal.authoredRadius);
    }

    @Test
    public void allExcludesLargeCaseButStillCleansItsSavedPerimeter() throws Exception {
        TestScenario small = scenario("normal", 32, true);
        TestScenario large = scenario("large", 80, false);
        journal.authoredRadius = 80;
        set("selection", TestWorldManager.selectScenarios("all", Arrays.asList(small, large)));
        beginReset();
        assertEquals(32, field(TestWorldManager.class, "requestedRadius").getInt(null));
        assertEquals(100, footprint().chunkCount());
        assertEquals(80, journal.authoredRadius);
    }

    @Test
    public void oldScenarioAndBuildRequestsKeepSixteenChunks() throws Exception {
        set("selection", Arrays.asList(scenario("normal", 32, true)));
        beginReset();
        assertEquals(16, footprint().chunkCount());
        set("selection", null);
        beginReset();
        assertEquals(16, footprint().chunkCount());
        assertEquals(32, journal.authoredRadius);
    }

    private TestScenario scenario(String id, int radius, boolean includeInAll) {
        return new TestScenario() {

            @Override
            public String id() {
                return id;
            }

            @Override
            public int footprintRadius() {
                return radius;
            }

            @Override
            public boolean includeInAll() {
                return includeInAll;
            }

            @Override
            public void start(TestContext context) {}

            @Override
            public void tick(TestContext context) {}
        };
    }

    private void beginReset() throws Exception {
        Method begin = TestWorldManager.class.getDeclaredMethod("beginReset");
        begin.setAccessible(true);
        begin.invoke(null);
    }

    private TestWorldFootprint footprint() throws Exception {
        return (TestWorldFootprint) field(TestWorldManager.class, "resetFootprint").get(null);
    }

    private Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void set(String name, Object value) throws Exception {
        field(TestWorldManager.class, name).set(null, value);
    }
}
