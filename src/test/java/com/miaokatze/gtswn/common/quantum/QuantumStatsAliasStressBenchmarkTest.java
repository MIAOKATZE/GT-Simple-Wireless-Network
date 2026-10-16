package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumRegistryStressBenchmarkTest.CountingRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumRegistryStressBenchmarkTest.Metrics;
import com.miaokatze.gtswn.common.quantum.QuantumRegistryStressBenchmarkTest.Support;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.tile.networking.TileController;
import appeng.util.ReadOnlyCollection;

/** Same getOrCompute contract in v1.8.69/v1.8.73/candidate; no safety-feature comparison. */
public class QuantumStatsAliasStressBenchmarkTest {

    private static final int SIDE = 7, NODE_COUNT = 128;

    @Test
    public void explicitlySelectedStatsAliasStress() throws Exception {
        assumeTrue("Select with -Dgtswn.stress=true in the test JVM", Boolean.getBoolean("gtswn.stress"));
        Support.validateSelection();
        List<Map<String, Object>> rows = new ArrayList<>();
        Class<? extends IGridHost> previousNodeClass = QuantumNodeTypes.nodeClass();
        try {
            QuantumNodeTypes.register(TileEntityNetworkQuantumNode.class);
            for (String phase : new String[] { "cold_aliases", "aliases_after_first", "revision_change",
                "bucket_change" }) {
                for (int sample = -Support.warmups(); sample < Support.samples(); sample++) {
                    Fixture fixture = new Fixture();
                    QuantumNetworkStatsCache.clear();
                    if (!"cold_aliases".equals(phase)) fixture.get(0);
                    if ("revision_change".equals(phase)) QuantumControllerRegistry.get(fixture.world)
                        .quantize(31, 64, 31);
                    if ("bucket_change".equals(phase)) fixture.world.time += 100;
                    fixture.metrics.clear();
                    long started = System.nanoTime();
                    long checksum = 0;
                    QuantumNetworkStatsCache.Snapshot last = null;
                    for (int call = 0; call < Support.calls(); call++) {
                        last = fixture.get(call);
                        if (last != null) checksum += last.totalChannels + last.usedChannels + last.quantumNodeCount;
                    }
                    long nanos = System.nanoTime() - started;
                    assertNotNull(last);
                    assertEquals(
                        SIDE * SIDE * SIDE,
                        last.getStructure()
                            .size());
                    assertEquals(NODE_COUNT, last.quantumNodeCount);
                    assertEquals(NODE_COUNT, last.usedChannels);
                    assertEquals((long) (6 * SIDE * SIDE * 32 + 2 * NODE_COUNT) * Support.calls(), checksum);
                    if (sample >= 0) {
                        Map<String, Object> row = Support
                            .row("stats_" + phase, SIDE, sample, Support.calls(), nanos, fixture.metrics);
                        row.put("quantumNodes", NODE_COUNT);
                        row.put("gridConnections", NODE_COUNT);
                        rows.add(row);
                    }
                }
            }
            Support.write("stats_alias", rows);
        } finally {
            QuantumNetworkStatsCache.clear();
            QuantumNodeTypes.register(previousNodeClass);
        }
    }

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("gtswn.stress")) throw new IllegalStateException("Requires -Dgtswn.stress=true");
        new QuantumStatsAliasStressBenchmarkTest().explicitlySelectedStatsAliasStress();
    }

    private static final class Fixture {

        final Metrics metrics = new Metrics();
        final FixtureWorld world;
        final AnchorKey[] keys = new AnchorKey[4];
        final IGrid grid;

        Fixture() throws Exception {
            world = (FixtureWorld) allocate(FixtureWorld.class);
            world.metrics = metrics;
            world.tiles = new HashMap<>();
            world.time = 100;
            WorldProvider provider = new WorldProviderSurface();
            provider.dimensionId = 0;
            field(World.class, "provider").set(world, provider);
            MapStorage storage = new MapStorage(null);
            field(World.class, "perWorldStorage").set(world, storage);
            storage.setData(QuantumControllerRegistry.DATA_NAME, new CountingRegistry(metrics));
            Set<Long> positions = QuantumRegistryStressBenchmarkTest.cube(SIDE);
            for (long position : positions) {
                Controller tile = (Controller) allocate(Support.fixtureControllerType(Controller.class));
                tile.identity = "fixture-" + position;
                tile.xCoord = QuantumControllerRegistry.unpackX(position);
                tile.yCoord = QuantumControllerRegistry.unpackY(position);
                tile.zCoord = QuantumControllerRegistry.unpackZ(position);
                tile.setWorldObj(world);
                world.tiles.put(position, tile);
            }
            QuantumControllerRegistry.get(world)
                .quantizeAll(positions);
            long[] aliases = QuantumRegistryStressBenchmarkTest.aliases(SIDE);
            for (int index = 0; index < aliases.length; index++) keys[index] = new AnchorKey(
                0,
                QuantumControllerRegistry.unpackX(aliases[index]),
                QuantumControllerRegistry.unpackY(aliases[index]),
                QuantumControllerRegistry.unpackZ(aliases[index]));
            List<IGridNode> nodes = new ArrayList<>();
            for (int index = 0; index < NODE_COUNT; index++) {
                IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
                    IGridConnection.class.getClassLoader(),
                    new Class<?>[] { IGridConnection.class },
                    (proxy, method, args) -> {
                        if (method.getName()
                            .equals("getUsedChannels")) {
                            metrics.channelReads++;
                            return 1;
                        }
                        return null;
                    });
                ReadOnlyCollection<IGridConnection> connections = new ReadOnlyCollection<>(
                    Collections.singletonList(edge));
                IGridNode node = (IGridNode) Proxy.newProxyInstance(
                    IGridNode.class.getClassLoader(),
                    new Class<?>[] { IGridNode.class },
                    (proxy, method, args) -> {
                        if (method.getName()
                            .equals("getConnections")) {
                            metrics.connectionReads++;
                            return connections;
                        }
                        return null;
                    });
                nodes.add(node);
            }
            Machines machines = new Machines(nodes);
            grid = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getMachines")) {
                        metrics.gridMachines++;
                        return machines;
                    }
                    return null;
                });
        }

        QuantumNetworkStatsCache.Snapshot get(int call) {
            return QuantumNetworkStatsCache.getOrCompute(keys[call % keys.length], world, grid);
        }
    }

    private static final class Machines extends ReadOnlyCollection<IGridNode> implements IMachineSet {

        Machines(List<IGridNode> nodes) {
            super(nodes);
        }

        @Override
        public Class<? extends IGridHost> getMachineClass() {
            return TileEntityNetworkQuantumNode.class;
        }
    }

    public static class Controller extends TileController implements QuantumControllerIdentity {

        String identity;
        boolean safety;

        // Extra methods on v1.8.69's identity interface; required interface methods on v1.8.73+.
        public boolean gtswn$isNetworkSafetyEnabled() {
            return safety;
        }

        public void gtswn$setNetworkSafetyEnabled(boolean enabled) {
            if (safety == enabled) return;
            safety = enabled;
            Support.fixturePolicyChanged();
        }

        @Override
        public String gtswn$getControllerIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setControllerIdentity(String value) {
            if (java.util.Objects.equals(identity, value)) return;
            identity = value;
            Support.fixturePolicyChanged();
        }

        @Override
        public void markDirty() {}
    }

    private static final class FixtureWorld extends World {

        Map<Long, TileEntity> tiles;
        Metrics metrics;
        long time;

        private FixtureWorld() {
            super(
                (ISaveHandler) null,
                "stats-stress-fixture",
                (WorldProvider) null,
                (WorldSettings) null,
                (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return time;
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

        @Override
        public boolean blockExists(int x, int y, int z) {
            metrics.worldExists++;
            return tiles.containsKey(QuantumControllerRegistry.pack(x, y, z));
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            metrics.worldTiles++;
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
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
