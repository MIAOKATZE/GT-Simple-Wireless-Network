package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.exceptions.SecurityConnectionException;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPart;
import appeng.api.util.AECableType;
import appeng.me.helpers.AENetworkProxy;
import appeng.parts.CableBusContainer;
import appeng.tile.networking.TileController;
import appeng.util.ReadOnlyCollection;

public class QuantumMaintenanceRetryTest {

    @Test
    @SuppressWarnings("unchecked")
    public void retryWindowSkipsEvenLoadedAnchorsAndTheirNativeAuthorizationInputs() throws Exception {
        int dimension = 37175;
        Map<Integer, WorldServer> worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds")
            .get(null);
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tile = new Device();
        MapStorage storage = new MapStorage(null);
        storage.setData(QuantumControllerRegistry.DATA_NAME, new QuantumControllerRegistry("unused") {

            @Override
            public boolean isSuspendedAnchor(long position, String generation) {
                throw new AssertionError("Retry window must precede anchor and permission resolution");
            }
        });
        field(net.minecraft.world.World.class, "perWorldStorage").set(world, storage);
        field(net.minecraft.world.World.class, "provider").set(world, new WorldProviderSurface());
        WorldServer previous = worlds.put(dimension, world);
        try {
            QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("retry-fixture");
            QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
            entry.dim = dimension;
            field(entry.getClass(), "nextAttempt").setLong(entry, 100);
            Method maintain = method(
                QuantumIncorporationRegistry.class,
                "maintain",
                net.minecraft.world.World.class,
                QuantumIncorporationRegistry.Entry.class);
            for (int tick = 0; tick < 100; tick++) {
                world.tick = tick;
                field(entry.getClass(), "lastIsolation").setLong(entry, tick);
                maintain.invoke(registry, world, entry);
            }
            assertEquals(0, ((Device) world.tile).nodeReads);
        } finally {
            if (previous == null) worlds.remove(dimension);
            else worlds.put(dimension, previous);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void suspendedSameGenerationBindingsRetryNaturallyAfterResumingEvenAtMaximumBackoff() throws Exception {
        int dimension = 37176;
        Map<Integer, WorldServer> worlds = (Map<Integer, WorldServer>) field(DimensionManager.class, "worlds")
            .get(null);
        FixtureWorld anchor = (FixtureWorld) allocate(FixtureWorld.class);
        FixtureWorld local = (FixtureWorld) allocate(FixtureWorld.class);
        Device device = new Device();
        device.rejectReads = false;
        local.tile = device;
        TargetController target = (TargetController) allocate(TargetController.class);
        anchor.tile = target;
        boolean[] suspended = { true };
        QuantumControllerRegistry controllers = new QuantumControllerRegistry("resume-controller-fixture") {

            @Override
            public boolean isSuspendedAnchor(long position, String generation) {
                assertEquals("same-generation", generation);
                return suspended[0];
            }
        };
        MapStorage storage = new MapStorage(null);
        storage.setData(QuantumControllerRegistry.DATA_NAME, controllers);
        field(net.minecraft.world.World.class, "perWorldStorage").set(anchor, storage);
        WorldProviderSurface provider = new WorldProviderSurface();
        provider.dimensionId = dimension;
        field(net.minecraft.world.World.class, "provider").set(anchor, provider);
        WorldServer previous = worlds.put(dimension, anchor);
        try (ServerClock clock = new ServerClock()) {
            QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("resume-fixture");
            QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
            entry.dim = dimension;
            entry.anchorGeneration = "same-generation";
            field(entry.getClass(), "block").set(entry, "fixture-block");
            field(entry.getClass(), "tileClass").set(entry, Device.class.getName());
            field(entry.getClass(), "identity").set(entry, "same-incorporation");
            field(entry.getClass(), "owner").set(entry, "same-owner");
            ((Map<Long, QuantumIncorporationRegistry.Entry>) field(registry.getClass(), "entries").get(registry))
                .put(entry.position, entry);
            method(registry.getClass(), "index", entry.getClass()).invoke(registry, entry);
            field(entry.getClass(), "normalizedRegistry").set(entry, controllers);
            field(entry.getClass(), "normalizedRevision").setLong(entry, controllers.getRevision());
            field(entry.getClass(), "normalizedKey").set(entry, method(entry.getClass(), "anchorKey").invoke(entry));
            field(entry.getClass(), "normalizedGeneration").set(entry, entry.anchorGeneration);
            NBTTagCompound before = new NBTTagCompound();
            registry.writeToNBT(before);
            registry.controllerSuspended(dimension, Collections.singleton(0L));
            Method maintain = method(
                registry.getClass(),
                "maintain",
                net.minecraft.world.World.class,
                entry.getClass());
            for (int tick : new int[] { 0, 20, 60, 140, 300 }) {
                local.tick = tick;
                field(entry.getClass(), "lastIsolation").setLong(entry, tick);
                maintain.invoke(registry, local, entry);
            }
            assertEquals(500, field(entry.getClass(), "nextAttempt").getLong(entry));
            assertEquals(200, field(entry.getClass(), "backoff").getInt(entry));
            suspended[0] = false;
            for (int tick = 301; tick <= 500; tick++) {
                local.tick = tick;
                clock.tick(tick);
                field(entry.getClass(), "lastIsolation").setLong(entry, tick);
                maintain.invoke(registry, local, entry);
            }
            assertEquals(1, target.nodeReads);
            assertTrue(device.nodeReads > 0);
            assertSame(
                entry,
                registry.snapshot()
                    .iterator()
                    .next());
            NBTTagCompound after = new NBTTagCompound();
            registry.writeToNBT(after);
            assertEquals(before, after);
        } finally {
            if (previous == null) worlds.remove(dimension);
            else worlds.put(dimension, previous);
        }
    }

    @Test
    public void disconnectedEntriesWaitBeforeNativeGettersAndDoNotSpendBudgetOnMissingAnchors() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        Device tile = new Device();
        world.tile = tile;
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("retry-fixture");
        Method maintain = method(
            QuantumIncorporationRegistry.class,
            "maintain",
            net.minecraft.world.World.class,
            QuantumIncorporationRegistry.Entry.class);
        int previousBudget = field(QuantumIncorporationRegistry.class, "connectionsThisTick").getInt(null);
        field(QuantumIncorporationRegistry.class, "connectionsThisTick").setInt(null, 0);
        try {
            for (int index = 0; index < 6; index++) {
                QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
                entry.position = QuantumControllerRegistry.pack(index, 64, 0);
                entry.dim = 2_147_483_000;
                field(entry.getClass(), "nextAttempt").setLong(entry, 100);
                for (int tick = 0; tick < 100; tick++) {
                    world.tick = tick;
                    // Isolation has its own existing 20t schedule; this assertion targets bridge retries.
                    field(entry.getClass(), "lastIsolation").setLong(entry, tick);
                    maintain.invoke(registry, world, entry);
                }
                world.tick = 100;
                field(entry.getClass(), "lastIsolation").setLong(entry, 100);
                maintain.invoke(registry, world, entry);
                assertEquals(120, field(entry.getClass(), "nextAttempt").getLong(entry));
                assertEquals(40, field(entry.getClass(), "backoff").getInt(entry));
            }
            assertEquals(0, tile.nodeReads);
            assertEquals(0, field(QuantumIncorporationRegistry.class, "connectionsThisTick").getInt(null));
        } finally {
            field(QuantumIncorporationRegistry.class, "connectionsThisTick").setInt(null, previousBudget);
        }
    }

    @Test
    public void failedPartConnectionUsesTwentyToTwoHundredTickBackoff() throws Exception {
        try (ServerClock clock = new ServerClock()) {
            PartsFixture fixture = new PartsFixture();
            int[] reads = fixture.add(ForgeDirection.DOWN);
            for (int tick = 0; tick <= 500; tick++) fixture.tick(clock, tick);
            assertEquals("Attempts at 0, 20, 60, 140, 300 and 500 only", 6, reads[0]);
        }
    }

    @Test
    public void sixFailingFacesMakeProgressUnderTheSharedTwoAttemptBudget() throws Exception {
        try (ServerClock clock = new ServerClock()) {
            PartsFixture fixture = new PartsFixture();
            ArrayList<int[]> reads = new ArrayList<>();
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) reads.add(fixture.add(side));
            int previous = 0;
            for (int tick = 0; tick < 3; tick++) {
                fixture.tick(clock, tick);
                int total = 0;
                for (int[] count : reads) total += count[0];
                assertEquals(2, total - previous);
                previous = total;
            }
            for (int[] count : reads) assertEquals(1, count[0]);
            for (int tick = 3; tick < 20; tick++) fixture.tick(clock, tick);
            for (int[] count : reads) assertEquals(1, count[0]);
        }
    }

    @Test
    public void replacingPartOrCoreBypassesTheOldIdentitiesRetryWindow() throws Exception {
        try (ServerClock clock = new ServerClock()) {
            PartsFixture fixture = new PartsFixture();
            int[] old = fixture.add(ForgeDirection.DOWN);
            fixture.tick(clock, 0);
            int[] replacement = fixture.add(ForgeDirection.DOWN);
            fixture.tick(clock, 1);
            assertEquals(1, old[0]);
            assertEquals(1, replacement[0]);
            fixture.core = node();
            field(AENetworkProxy.class, "node").set(fixture.proxy, fixture.core);
            fixture.tick(clock, 2);
            assertEquals(2, replacement[0]);
        }
    }

    private static final class PartsFixture {

        final FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        final TileEntityNetworkQuantumNode tile = new TileEntityNetworkQuantumNode() {

            @Override
            protected void connectPartNodes(IGridNode core, IGridNode part) throws SecurityConnectionException {
                throw new SecurityConnectionException();
            }
        };
        final Map<ForgeDirection, IPart> parts = new EnumMap<>(ForgeDirection.class);
        final AENetworkProxy proxy = new AENetworkProxy(tile, "proxy", null, true);
        IGridNode core = node();

        PartsFixture() throws Exception {
            tile.setWorldObj(world);
            field(AENetworkProxy.class, "node").set(proxy, core);
            field(TileEntityNetworkQuantumNode.class, "gridProxy").set(tile, proxy);
            field(TileEntityNetworkQuantumNode.class, "partContainer").set(tile, new CableBusContainer(tile) {

                @Override
                public IPart getPart(ForgeDirection side) {
                    return parts.get(side);
                }
            });
        }

        int[] add(ForgeDirection side) {
            int[] reads = { 0 };
            parts.put(
                side,
                (IPart) Proxy.newProxyInstance(
                    IPart.class.getClassLoader(),
                    new Class<?>[] { IPart.class },
                    (ignored, called, args) -> {
                        if (called.getName()
                            .equals("getGridNode")) {
                            reads[0]++;
                            return core;
                        }
                        return null;
                    }));
            return reads;
        }

        void tick(ServerClock clock, int tick) throws Exception {
            world.tick = tick;
            clock.tick(tick);
            method(TileEntityNetworkQuantumNode.class, "rewirePartConnections").invoke(tile);
        }
    }

    private static IGridNode node() {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, called, args) -> {
                if (called.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(new ArrayList<IGridConnection>());
                if (called.getName()
                    .equals("equals")) return proxy == args[0];
                if (called.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                return null;
            });
    }

    private static final class Device extends TileEntity implements IGridHost {

        int nodeReads;
        boolean rejectReads = true;

        @Override
        public IGridNode getGridNode(ForgeDirection side) {
            nodeReads++;
            if (rejectReads) throw new AssertionError("Retry window must precede native getters");
            return null;
        }

        @Override
        public AECableType getCableConnectionType(ForgeDirection side) {
            return AECableType.NONE;
        }

        @Override
        public void securityBreak() {}
    }

    private static final class TargetController extends TileController {

        int nodeReads;

        @Override
        public IGridNode getGridNode(ForgeDirection side) {
            nodeReads++;
            return null;
        }
    }

    private static final class ServerClock implements AutoCloseable {

        final Field singleton;
        final Object previous;
        final MinecraftServer server = (MinecraftServer) allocate(DedicatedServer.class);
        final long previousBudgetTick = field(TileEntityNetworkQuantumNode.class, "connectBudgetTick").getLong(null);
        final int previousBudgetCount = field(TileEntityNetworkQuantumNode.class, "connectsThisTick").getInt(null);
        final long previousIncorporationTick = field(QuantumIncorporationRegistry.class, "budgetTick").getLong(null);
        final int previousIncorporationCount = field(QuantumIncorporationRegistry.class, "connectionsThisTick")
            .getInt(null);

        ServerClock() throws Exception {
            Field found = null;
            for (Field candidate : MinecraftServer.class.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == MinecraftServer.class) {
                    candidate.setAccessible(true);
                    found = candidate;
                }
            }
            assertNotNull(found);
            singleton = found;
            previous = singleton.get(null);
            singleton.set(null, server);
            field(TileEntityNetworkQuantumNode.class, "connectBudgetTick").setLong(null, Long.MIN_VALUE);
            field(QuantumIncorporationRegistry.class, "budgetTick").setLong(null, Long.MIN_VALUE);
        }

        void tick(int tick) throws Exception {
            field(MinecraftServer.class, "tickCounter").setInt(server, tick);
        }

        @Override
        public void close() throws Exception {
            singleton.set(null, previous);
            field(TileEntityNetworkQuantumNode.class, "connectBudgetTick").setLong(null, previousBudgetTick);
            field(TileEntityNetworkQuantumNode.class, "connectsThisTick").setInt(null, previousBudgetCount);
            field(QuantumIncorporationRegistry.class, "budgetTick").setLong(null, previousIncorporationTick);
            field(QuantumIncorporationRegistry.class, "connectionsThisTick").setInt(null, previousIncorporationCount);
        }
    }

    private static final class FixtureWorld extends WorldServer {

        long tick;
        TileEntity tile;

        private FixtureWorld() {
            super(null, (ISaveHandler) null, "retry-fixture", 0, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return tick;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tile;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }
}
