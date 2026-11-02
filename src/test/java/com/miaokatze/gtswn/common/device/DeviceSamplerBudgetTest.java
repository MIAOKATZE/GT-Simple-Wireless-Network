package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;

import org.junit.Test;

import com.miaokatze.gtswn.common.monitoring.MonitorProfile;
import com.miaokatze.gtswn.common.monitoring.MonitoringProfiles;
import com.miaokatze.gtswn.common.monitoring.SourceKey;
import com.miaokatze.gtswn.common.performance.PerformanceAudit;

import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Exercises the production drain with a deterministic clock; no sleeps or Minecraft process are needed. */
public class DeviceSamplerBudgetTest {

    private static final long BUDGET_NANOS = 2_000_000L;

    @Test
    public void slowFirstItemCompletesAndRefillKeepsPendingItemsAhead() throws Exception {
        try (Fixture fixture = new Fixture(3)) {
            Terminal terminal = fixture.addTerminal(false);
            fixture.refill();
            fixture.primeEnergy();
            fixture.sampleNanos = BUDGET_NANOS;
            fixture.drain();
            assertEquals(Arrays.asList(0), fixture.visits);
            assertEquals(fixture.keys.subList(1, 3), fixture.pending());
            assertPower(terminal, fixture, 0, 1, 1000, 2000);

            fixture.refill();
            assertEquals(
                Arrays.asList(fixture.keys.get(1), fixture.keys.get(2), fixture.keys.get(0)),
                fixture.pending());
            fixture.sampleNanos = 0;
            fixture.drain();
            assertEquals(Arrays.asList(0, 1, 2, 0), fixture.visits);
            assertTrue(
                fixture.pending()
                    .isEmpty());
            for (int i = 0; i < 3; i++) assertPower(terminal, fixture, i, 1, 1000, 2000);
        }
    }

    @Test
    public void countCapAndCrossTerminalDedupRetainObservedPowerAndFifo() throws Exception {
        try (Fixture fixture = new Fixture(105)) {
            Terminal first = fixture.addTerminal(true), second = fixture.addTerminal(true);
            fixture.refill();
            assertEquals(
                105,
                fixture.pending()
                    .size());
            fixture.primeEnergy();
            fixture.sourceChecks = 0;
            fixture.drain();
            assertEquals(100, fixture.visits.size());
            assertEquals(fixture.keys.subList(100, 105), fixture.pending());
            // Each terminal's complete source scope is checked once per drain, not once per machine.
            assertEquals(210, fixture.sourceChecks);
            fixture.drain();
            assertEquals(105, fixture.visits.size());
            assertTrue(
                fixture.pending()
                    .isEmpty());
            assertEquals(420, fixture.sourceChecks);
            for (int i = 0; i < 105; i++) {
                assertEquals(
                    i,
                    fixture.visits.get(i)
                        .intValue());
                assertPower(first, fixture, i, 1, 1000, 2000);
                assertPower(second, fixture, i, 1, 1000, 2000);
            }
        }
    }

    @Test
    public void expensiveAuthorizationStillAdvancesOneItemPerDrain() throws Exception {
        try (Fixture fixture = new Fixture(3)) {
            fixture.addTerminal(true);
            fixture.refill();
            fixture.primeEnergy();
            fixture.authorizationNanos = BUDGET_NANOS;
            fixture.sourceChecks = 0;
            for (int i = 0; i < 3; i++) {
                fixture.drain();
                assertEquals(i + 1, fixture.visits.size());
                assertEquals((i + 1) * 3, fixture.sourceChecks);
                assertEquals(fixture.keys.subList(i + 1, 3), fixture.pending());
            }
            assertEquals(Arrays.asList(0, 1, 2), fixture.visits);
        }
    }

    @Test
    public void remainingWorkRechecksRevokedReaderWhileAuthorizedTerminalContinues() throws Exception {
        try (Fixture fixture = new Fixture(3)) {
            Terminal revoked = fixture.addTerminal(true), allowed = fixture.addTerminal(true);
            fixture.refill();
            fixture.primeEnergy();
            fixture.sampleNanos = BUDGET_NANOS;
            fixture.drain();
            fixture.profiles.revoke(revoked.owner, revoked.id, fixture.holder);
            fixture.sampleNanos = 0;
            fixture.drain();
            assertEquals(Arrays.asList(0, 1, 2), fixture.visits);
            assertTrue(
                fixture.pending()
                    .isEmpty());
            assertPower(revoked, fixture, 0, 1, 1000, 2000);
            for (int i = 1; i < 3; i++) {
                assertEquals(0, revoked.data.records.get(fixture.keys.get(i)).count);
                assertFalse(
                    revoked.data.records.get(fixture.keys.get(i))
                        .sampledFrom(fixture.world.tiles.get(i)));
            }
            for (int i = 0; i < 3; i++) assertPower(allowed, fixture, i, 1, 1000, 2000);
        }
    }

    @Test
    public void remainingWorkRechecksNativeSourceBeforeObservation() throws Exception {
        try (Fixture fixture = new Fixture(2)) {
            Terminal terminal = fixture.addTerminal(true);
            fixture.refill();
            fixture.primeEnergy();
            fixture.sampleNanos = BUDGET_NANOS;
            fixture.drain();
            fixture.sourceStatus = MonitorProfile.Status.SOURCE_REPLACED;
            fixture.drain();
            assertEquals(Arrays.asList(0), fixture.visits);
            assertTrue(
                fixture.pending()
                    .isEmpty());
            assertEquals(0, terminal.data.records.get(fixture.keys.get(1)).count);
            assertEquals(
                MonitorProfile.Status.SOURCE_REPLACED,
                fixture.profiles.profileForTerminal(terminal.id)
                    .status());
        }
    }

    @Test
    public void timeSlicedSamplesKeepAccumulatedEnergyAndFifoAcrossGameTicks() throws Exception {
        try (Fixture fixture = new Fixture(3)) {
            Terminal first = fixture.addTerminal(false), second = fixture.addTerminal(false);
            fixture.refill();
            fixture.primeEnergy();
            fixture.sampleNanos = BUDGET_NANOS;
            for (int i = 0; i < 3; i++) {
                fixture.tick(102 + i);
                fixture.drain();
                assertPower(first, fixture, i, 1, 2000.0 / (2 + i), 4000.0 / (2 + i));
                assertPower(second, fixture, i, 1, 2000.0 / (2 + i), 4000.0 / (2 + i));
            }
            fixture.tick(110);
            fixture.recordEnergy();
            fixture.refill();
            for (int i = 0; i < 3; i++) {
                fixture.tick(112 + i);
                fixture.drain();
                double averageIn = (2000.0 / (2 + i) + 200) / 2;
                double averageOut = (4000.0 / (2 + i) + 400) / 2;
                assertPower(first, fixture, i, 2, averageIn, averageOut);
                assertPower(second, fixture, i, 2, averageIn, averageOut);
            }
            assertEquals(Arrays.asList(0, 1, 2, 0, 1, 2), fixture.visits);
            assertTrue(
                fixture.pending()
                    .isEmpty());
        }
    }

    @Test
    public void unloadedItemAlsoYieldsAndNeverLoadsAChunk() throws Exception {
        try (Fixture fixture = new Fixture(2)) {
            fixture.addTerminal(false);
            fixture.refill();
            fixture.loaded = false;
            fixture.chunkCheckNanos = BUDGET_NANOS;
            fixture.drain();
            assertEquals(fixture.keys.subList(1, 2), fixture.pending());
            assertTrue(fixture.visits.isEmpty());
            fixture.loaded = true;
            fixture.chunkCheckNanos = 0;
            fixture.drain();
            assertEquals(Arrays.asList(1), fixture.visits);
            assertTrue(
                fixture.pending()
                    .isEmpty());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void namedSlicesCountExecutedStagesAndDoNothingWhileDisabled() throws Exception {
        boolean enabled = PerformanceAudit.enabled();
        Map<String, long[]> slices = (Map<String, long[]>) field(PerformanceAudit.class, "SLICES").get(null);
        Map<String, long[]> previous = new LinkedHashMap<>(slices);
        long topLevelNanos = field(PerformanceAudit.class, "perTickNanos").getLong(null);
        try (Fixture fixture = new Fixture(2)) {
            fixture.addTerminal(false);
            fixture.refill();
            fixture.sampleNanos = BUDGET_NANOS;
            slices.clear();
            PerformanceAudit.setEnabled(true);
            fixture.drain();
            for (String name : new String[] { "gtswn.monitor.ditDrain", "gtswn.monitor.ditDiscovery",
                "gtswn.monitor.ditProvider" }) {
                assertNotNull("Missing performance slice: " + name, slices.get(name));
                assertEquals(1, slices.get(name)[0]);
            }
            PerformanceAudit.setEnabled(false);
            fixture.drain();
            assertEquals(3, slices.size());
            for (long[] counters : slices.values()) assertEquals(1, counters[0]);
            assertEquals(topLevelNanos, field(PerformanceAudit.class, "perTickNanos").getLong(null));
        } finally {
            PerformanceAudit.setEnabled(enabled);
            slices.clear();
            slices.putAll(previous);
        }
    }

    private static void assertPower(Terminal terminal, Fixture fixture, int index, int count, double averageIn,
        double averageOut) {
        DeviceTerminalDataStore.MachineRecord record = terminal.data.records.get(fixture.keys.get(index));
        assertEquals(DeviceTerminalDataStore.STATE_RUNNING, record.state);
        assertEquals(count, record.count);
        assertEquals(count, record.idx);
        assertEquals(100L * count, record.instIn);
        assertEquals(200L * count, record.instOut);
        assertEquals(averageIn, record.inAvg, 1e-9);
        assertEquals(averageOut, record.outAvg, 1e-9);
        assertTrue(record.sampledFrom(fixture.world.tiles.get(index)));
        assertEquals("", record.recipeIn);
        assertEquals("", record.recipeOut);
    }

    private static final class Clock implements LongSupplier {

        long nanos = 1;

        @Override
        public long getAsLong() {
            return nanos;
        }
    }

    private static final class Terminal {

        final UUID owner, id;
        final DeviceTerminalDataStore.TerminalData data;

        Terminal(UUID owner, UUID id, DeviceTerminalDataStore.TerminalData data) {
            this.owner = owner;
            this.id = id;
            this.data = data;
        }
    }

    private static final class Fixture implements AutoCloseable {

        final int dimension = 37192;
        final UUID holder = UUID.randomUUID();
        final Clock clock = new Clock();
        final DeviceSampleScheduler scheduler = new DeviceSampleScheduler(clock);
        final List<String> keys = new ArrayList<>();
        final List<Integer> visits = new ArrayList<>();
        final FixtureWorld world;
        final Map<Integer, WorldServer> worlds;
        final WorldServer previous;
        final DeviceTerminalDataStore store;
        final MonitoringProfiles profiles;
        boolean loaded = true;
        long sampleNanos, authorizationNanos, chunkCheckNanos;
        int sourceChecks;
        MonitorProfile.Status sourceStatus = MonitorProfile.Status.LIVE;

        @SuppressWarnings("unchecked")
        Fixture(int machineCount) throws Exception {
            world = (FixtureWorld) allocate(FixtureWorld.class);
            world.tiles = new LinkedHashMap<>();
            field(World.class, "perWorldStorage").set(world, new MapStorage(null));
            field(World.class, "chunkProvider").set(
                world,
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { IChunkProvider.class },
                    (p, m, a) -> {
                        if (m.getName()
                            .equals("chunkExists")) {
                            clock.nanos += chunkCheckNanos;
                            return loaded;
                        }
                        throw new AssertionError("Sampler must not load chunks: " + m.getName());
                    }));
            for (int i = 0; i < machineCount; i++) {
                FixtureMachine machine = new FixtureMachine(this, i);
                FixtureTile tile = new FixtureTile();
                tile.setWorldObj(world);
                tile.xCoord = i;
                tile.setMetaTileEntity(machine);
                machine.setBaseMetaTileEntity(tile);
                world.tiles.put(i, tile);
                keys.add(dimension + ":" + i + ":0:0");
            }
            worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds").get(null);
            previous = worlds.put(dimension, world);
            store = DeviceTerminalDataStore.get(world);
            profiles = MonitoringProfiles.get(world);
            Field access = field(MonitoringProfiles.class, "sourceAccess");
            access.set(
                profiles,
                Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { access.getType() }, (p, m, a) -> {
                    assertEquals("check", m.getName());
                    sourceChecks++;
                    clock.nanos += authorizationNanos;
                    return sourceStatus;
                }));
        }

        Terminal addTerminal(boolean managed) throws Exception {
            UUID owner = UUID.randomUUID(), id = UUID.randomUUID();
            if (managed) {
                Map<String, SourceKey> sources = new LinkedHashMap<>();
                for (String key : keys) sources.put(
                    key,
                    new SourceKey(
                        SourceKey.Kind.DIT,
                        profiles.epoch(),
                        UUID.randomUUID()
                            .toString(),
                        1));
                Method create = MonitoringProfiles.class.getDeclaredMethod("create", UUID.class, UUID.class, Map.class);
                create.setAccessible(true);
                MonitorProfile profile = (MonitorProfile) create.invoke(profiles, owner, null, sources);
                id = profile.id;
                profiles.share(owner, id, holder);
            }
            DeviceTerminalDataStore.TerminalData data = store.getOrCreateTerminal(id);
            data.profileManaged = managed;
            data.boundKeys.addAll(keys);
            for (String key : keys) {
                DeviceTerminalDataStore.MachineRecord record = new DeviceTerminalDataStore.MachineRecord();
                record.metaTileId = world.tiles.get(0)
                    .getMetaTileID();
                record.recipeIn = record.recipeOut = "old recipe";
                data.records.put(key, record);
            }
            DeviceTerminalDataStore.ensureActiveTerminal(holder, id);
            return new Terminal(owner, id, data);
        }

        void refill() throws Exception {
            Method refill = DeviceSampleScheduler.class.getDeclaredMethod("refillWorkQueue", World.class);
            refill.setAccessible(true);
            refill.invoke(scheduler, world);
        }

        void drain() throws Exception {
            Method drain = DeviceSampleScheduler.class
                .getDeclaredMethod("drainSamples", MinecraftServer.class, World.class);
            drain.setAccessible(true);
            drain.invoke(scheduler, null, world);
        }

        @SuppressWarnings("unchecked")
        List<String> pending() throws Exception {
            return new ArrayList<>((Collection<String>) field(DeviceSampleScheduler.class, "workQueue").get(scheduler));
        }

        void tick(long tick) {
            world.tick = tick;
            DeviceFlowMonitor.tick(this, tick);
        }

        void primeEnergy() {
            tick(100);
            for (FixtureTile tile : world.tiles.values()) DeviceFlowMonitor.watch(tile.getMetaTileEntity(), 1000);
            tick(101);
            recordEnergy();
            tick(102);
        }

        void recordEnergy() {
            for (FixtureTile tile : world.tiles.values()) {
                Object machine = tile.getMetaTileEntity();
                DeviceFlowMonitor.Reading outer = DeviceFlowMonitor.enter(machine);
                assertTrue(DeviceFlowMonitor.active());
                DeviceFlowMonitor.energyChange(machine, 10000, 8000);
                DeviceFlowMonitor.energyChange(new Object(), 0, 4000);
                DeviceFlowMonitor.leave(outer);
            }
        }

        @Override
        public void close() {
            DeviceTerminalDataStore.onPlayerLoggedOut(holder);
            DeviceFlowMonitor.clear();
            if (previous == null) worlds.remove(dimension);
            else worlds.put(dimension, previous);
        }
    }

    private static final class FixtureMachine extends DeviceMachineIdentityTest.IdentityMulti {

        private final Fixture fixture;
        private final int index;

        FixtureMachine(Fixture fixture, int index) {
            this.fixture = fixture;
            this.index = index;
        }

        @Override
        public String getLocalName() {
            fixture.visits.add(index);
            fixture.clock.nanos += fixture.sampleNanos;
            return "sampler budget fixture";
        }
    }

    private static final class FixtureTile extends BaseMetaTileEntity {

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public boolean isAllowedToWork() {
            return true;
        }
    }

    private static final class FixtureWorld extends WorldServer {

        Map<Integer, FixtureTile> tiles;
        long tick;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "sampler-budget-fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return tick;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(x);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
