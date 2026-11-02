package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.fluids.FluidStack;

import org.junit.BeforeClass;
import org.junit.Test;

import com.miaokatze.gtswn.common.performance.PerformanceAudit;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.test.FluidRegistryTestBootstrap;

/** Direct production-controller calls, with no game, chunk loads, or filesystem-backed world. */
public class AEMonitorSamplingCostTest {

    static Item item;

    @BeforeClass
    public static void registerItem() throws Exception {
        item = new Item();
        Method register = Item.itemRegistry.getClass()
            .getDeclaredMethod("addObjectRaw", int.class, String.class, Object.class);
        register.setAccessible(true);
        register.invoke(Item.itemRegistry, 31019, "gtswn_test:ae_sampling_cost", item);
    }

    @Test
    public void unobservedFirstSampleDoesNotTurnIntoAnEveryTickPlayerScan() throws Exception {
        Fixture fixture = new Fixture(false);
        withSamplingEnabled(() -> {
            for (int tick = 0; tick < 1000; tick++) fixture.tick(tick);
            assertEquals(-1L, fixture.controller.getLastAESampleTick());
            assertEquals(0, fixture.query.connectionChecks);
            assertEquals(50, fixture.players.scans);
        });
    }

    @Test
    public void alignedChecksKeepTheDefaultSamplingPeriodAndDepartureGraceAtEveryStartupPhase() throws Exception {
        withSamplingEnabled(() -> {
            for (int start = 0; start < 100; start++) {
                Fixture fixture = new Fixture(true);
                fixture.tick(start);
                assertEquals(start, fixture.controller.getLastAESampleTick());
                for (int tick = start + 1; tick <= 200; tick++) fixture.tick(tick);
                assertEquals(200L, fixture.controller.getLastAESampleTick());
                fixture.players.clear();
                for (int tick = 201; tick <= 300; tick++) fixture.tick(tick);
                assertEquals(300L, fixture.controller.getLastAESampleTick());
                fixture.world.tick = 301;
                assertFalse(fixture.controller.hasSamplingInterest());
                for (int tick = 301; tick <= 400; tick++) fixture.tick(tick);
                assertEquals(300L, fixture.controller.getLastAESampleTick());
            }
        });
    }

    @Test
    public void arrivalIsSeenWithinOneInterestPeriodEvenBeforeAnySampleExists() throws Exception {
        Fixture fixture = new Fixture(false);
        withSamplingEnabled(() -> {
            fixture.tick(7);
            fixture.players.add(player());
            for (int tick = 8; tick <= 20; tick++) fixture.tick(tick);
            assertTrue(fixture.controller.getLastAESampleTick() >= 8L);
            assertTrue(fixture.controller.getLastAESampleTick() <= 20L);
            assertEquals(2, fixture.players.scans);
        });
    }

    @Test
    public void portableAndJobLeasesRenewIndependentlyOfPhysicalChecks() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.world.tick = 100;
        fixture.controller.renewPortableInterest();
        fixture.controller.renewMonitoringJobInterest(400);
        fixture.world.tick = 150;
        fixture.controller.renewPortableInterest();
        fixture.world.tick = 250;
        assertTrue(fixture.controller.hasSamplingInterest());
        fixture.world.tick = 251;
        assertTrue(fixture.controller.hasSamplingInterest());
        fixture.world.tick = 500;
        assertTrue(fixture.controller.hasSamplingInterest());
        fixture.world.tick = 501;
        assertFalse(fixture.controller.hasSamplingInterest());
        fixture.controller.renewPortableInterest();
        assertTrue(fixture.controller.hasSamplingInterest());
    }

    @Test
    public void rollbackBetweenChecksAndWorldReplacementImmediatelyRecheckPhysicalObservers() throws Exception {
        Fixture fixture = new Fixture(true);
        fixture.world.tick = 100;
        fixture.controller.renewMonitoringJobInterest(500);
        assertTrue(fixture.controller.hasSamplingInterest());
        fixture.world.tick = 110;
        assertTrue(fixture.controller.hasSamplingInterest());
        fixture.players.clear();
        fixture.world.tick = 105;
        assertFalse(fixture.controller.hasSamplingInterest());
        assertEquals(2, fixture.players.scans);
        Fixture replacement = new Fixture(false);
        replacement.world.tick = 106;
        fixture.core.setWorldObj(replacement.world);
        assertFalse(fixture.controller.hasSamplingInterest());
        assertEquals(1, replacement.players.scans);
        replacement.players.add(player());
        replacement.world.tick = 104;
        assertTrue(fixture.controller.hasSamplingInterest());
        assertEquals(2, replacement.players.scans);
    }

    @Test
    public void oneRoundDeduplicatesRolesButRetainsNbtVariantsAndNextTickChanges() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.query.connected = true;
        ItemStack chart = stack("a"), duplicate = stack("a"), other = stack("b");
        field(AEMonitorController.class, "chartItem").set(fixture.controller, chart);
        monitoredItems(fixture.controller).add(duplicate);
        monitoredItems(fixture.controller).add(other);
        // Exercise the live per-key path without also running its existing reconciliation oracle.
        field(AEMonitorController.class, "mismatchCount").setInt(fixture.controller, 1);
        Method sample = AEMonitorController.class.getDeclaredMethod("sampleAENetwork", long.class);
        sample.setAccessible(true);
        sample.invoke(fixture.controller, 100L);
        AEMonitorDataSet data = AEMonitorDataStore.get(fixture.world)
            .getIfPresent("ae-cost-fixture");
        assertEquals(2, data.sampledKeyCount());
        assertEquals(2, fixture.query.itemReads);
        assertEquals(10L, data.newest(AEMonitorController.aeKey(chart)).amount);
        assertEquals(25L, data.newest(AEMonitorController.aeKey(other)).amount);
        chart.getTagCompound()
            .setString("variant", "c");
        sample.invoke(fixture.controller, 200L);
        assertEquals(3, data.sampledKeyCount());
        assertEquals(5, fixture.query.itemReads);
        assertEquals(40L, data.newest(AEMonitorController.aeKey(chart)).amount);
        assertEquals(200L, data.newest(AEMonitorController.aeKey(duplicate)).tick);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void fluidRolesRetainPreciseTagsAndReadChangesOnTheNextRound() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.query.connected = true;
        FluidStack first = FluidRegistryTestBootstrap.water(1000);
        first.tag = new NBTTagCompound();
        first.tag.setString("variant", "a");
        FluidStack duplicate = first.copy(), other = first.copy();
        duplicate.amount = 2000;
        other.tag.setString("variant", "b");
        List<FluidStack> fluids = (List<FluidStack>) field(AEMonitorController.class, "monitoredFluids")
            .get(fixture.controller);
        fluids.add(first);
        fluids.add(duplicate);
        fluids.add(other);
        field(AEMonitorController.class, "mismatchCount").setInt(fixture.controller, 1);
        Method sample = AEMonitorController.class.getDeclaredMethod("sampleAENetwork", long.class);
        sample.setAccessible(true);
        sample.invoke(fixture.controller, 100L);
        AEMonitorDataSet data = AEMonitorDataStore.get(fixture.world)
            .getIfPresent("ae-cost-fixture");
        assertEquals(2, data.sampledKeyCount());
        assertEquals(2, fixture.query.fluidReads);
        assertEquals(10L, data.newest(AEMonitorController.aeKey(first)).amount);
        assertEquals(25L, data.newest(AEMonitorController.aeKey(other)).amount);
        first.tag.setString("variant", "c");
        sample.invoke(fixture.controller, 200L);
        assertEquals(3, data.sampledKeyCount());
        assertEquals(5, fixture.query.fluidReads);
        assertEquals(40L, data.newest(AEMonitorController.aeKey(first)).amount);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void existingAuditSlicesRecordEnabledStagesAndSkipDisabledCalls() throws Exception {
        boolean enabled = PerformanceAudit.enabled();
        Map<String, long[]> slices = (Map<String, long[]>) field(PerformanceAudit.class, "SLICES").get(null);
        Map<String, long[]> previous = new LinkedHashMap<>(slices);
        slices.clear();
        try {
            withSamplingEnabled(() -> {
                Fixture fixture = new Fixture(true);
                fixture.query.connected = true;
                field(AEMonitorController.class, "chartItem").set(fixture.controller, stack("a"));
                field(AEMonitorController.class, "mismatchCount").setInt(fixture.controller, 1);
                PerformanceAudit.setEnabled(true);
                fixture.tick(100);
                assertEquals(1L, slices.get("gtswn.monitor.aeInterest")[0]);
                assertEquals(1L, slices.get("gtswn.monitor.aeSample")[0]);
                assertEquals(1L, slices.get("gtswn.monitor.aeProjection")[0]);
                PerformanceAudit.setEnabled(false);
                assertEquals(0L, PerformanceAudit.startSlice());
                fixture.tick(200);
                assertEquals(3, slices.size());
                for (long[] stage : slices.values()) assertEquals(1L, stage[0]);
            });
        } finally {
            PerformanceAudit.setEnabled(enabled);
            slices.clear();
            slices.putAll(previous);
        }
    }

    static ItemStack stack(String variant) {
        ItemStack stack = new ItemStack(item, 1, 0);
        stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setString("variant", variant);
        return stack;
    }

    @SuppressWarnings("unchecked")
    static List<ItemStack> monitoredItems(AEMonitorController controller) throws Exception {
        return (List<ItemStack>) field(AEMonitorController.class, "monitoredItems").get(controller);
    }

    static final class Fixture {

        final FixtureWorld world = allocate(FixtureWorld.class);
        final CountingPlayers players = new CountingPlayers();
        final FixturePanel core = new FixturePanel();
        final Query query = new Query();
        final AEMonitorController controller = new AEMonitorController(
            core,
            query,
            new PanelConfigStore(() -> {}),
            () -> {});

        Fixture(boolean nearby) throws Exception {
            field(World.class, "perWorldStorage").set(world, new MapStorage(null));
            world.playerEntities = players;
            core.setWorldObj(world);
            if (nearby) players.add(player());
        }

        void tick(long tick) {
            world.tick = tick;
            controller.tickSampling(tick);
        }
    }

    static final class Query implements AEMonitorController.AEQuery {

        final Object grid = new Object();
        boolean connected;
        int connectionChecks, itemReads, fluidReads;

        public boolean isConnected() {
            connectionChecks++;
            return connected;
        }

        public Object gridIdentity() {
            return grid;
        }

        public long itemAmount(ItemStack stack) {
            itemReads++;
            String variant = stack.getTagCompound()
                .getString("variant");
            return "a".equals(variant) ? 10L : "b".equals(variant) ? 25L : 40L;
        }

        public long fluidAmount(FluidStack stack) {
            fluidReads++;
            String variant = stack.tag.getString("variant");
            return "a".equals(variant) ? 10L : "b".equals(variant) ? 25L : 40L;
        }

        public Map<String, Long> bulkAmounts(List<ItemStack> items, List<FluidStack> fluids) {
            throw new AssertionError("This fixture exercises the production per-key path");
        }
    }

    static final class CountingPlayers extends ArrayList<EntityPlayer> {

        int scans;

        @Override
        public Iterator<EntityPlayer> iterator() {
            scans++;
            return super.iterator();
        }
    }

    static final class FixturePanel extends TileEntityNetworkInfoPanel {

        @Override
        public String getAEMonitorDataKey() {
            return "ae-cost-fixture";
        }
    }

    static final class FixtureWorld extends World {

        long tick;

        private FixtureWorld() {
            super((ISaveHandler) null, "ae-cost", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        public long getTotalWorldTime() {
            return tick;
        }

        protected IChunkProvider createChunkProvider() {
            throw new AssertionError("Interest and sampling must not load chunks");
        }

        protected int func_152379_p() {
            return 0;
        }

        public Entity getEntityByID(int id) {
            throw new AssertionError("Interest only inspects players already in this dimension");
        }
    }

    static EntityPlayer player() throws Exception {
        return allocate(EntityPlayerMP.class);
    }

    static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = field(unsafe, "theUnsafe");
        return type.cast(
            unsafe.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), type));
    }

    static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    interface CheckedAction {

        void run() throws Exception;
    }

    static void withSamplingEnabled(CheckedAction action) throws Exception {
        boolean enabled = Config.aeChartEnabled;
        int interval = Config.aeSampleInterval;
        Config.aeChartEnabled = true;
        Config.aeSampleInterval = 100;
        try {
            action.run();
        } finally {
            Config.aeChartEnabled = enabled;
            Config.aeSampleInterval = interval;
        }
    }
}
