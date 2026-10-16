package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumRegistryStressBenchmarkTest.CountingRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumRegistryStressBenchmarkTest.Metrics;
import com.miaokatze.gtswn.common.quantum.QuantumRegistryStressBenchmarkTest.Support;
import com.miaokatze.gtswn.config.Config;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;
import appeng.util.ReadOnlyCollection;

/** Optional synthetic grid experiment; it measures production security code, not AE connection creation. */
public class QuantumSecurityStressBenchmarkTest {

    @Test
    public void explicitlySelectedSecurityStress() throws Exception {
        assumeTrue("Select with -Dgtswn.stress=true in the test JVM", Boolean.getBoolean("gtswn.stress"));
        Support.validateSelection();
        boolean originalSafety = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int side : Support.sides()) {
                for (String phase : phases()) {
                    for (int sample = -Support.warmups(); sample < Support.samples(); sample++) {
                        Fixture fixture = new Fixture(side);
                        boolean off = "off_aliases".equals(phase);
                        if (!off) QuantumNetworkSecurityRegistry.get(fixture.world)
                            .setSafety(fixture.world, fixture.positions, true);
                        // Verify the same fixture and prime topology/policy before changing only the ACL.
                        assertEquals(true, fixture.call(0, off));
                        boolean expected = !"on_revoke".equals(phase);
                        if (!expected) fixture.permissionGranted = false;
                        fixture.metrics.clear();
                        long started = System.nanoTime();
                        int accepted = 0;
                        for (int call = 0; call < Support.calls(); call++) if (fixture.call(call, off)) accepted++;
                        long nanos = System.nanoTime() - started;
                        assertEquals(expected ? Support.calls() : 0, accepted);
                        if (sample >= 0) rows.add(
                            Support.row("security_" + phase, side, sample, Support.calls(), nanos, fixture.metrics));
                    }
                }
            }
            Support.write("security", rows);
        } finally {
            Config.quantumSafetyEnabled = originalSafety;
        }
    }

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("gtswn.stress")) throw new IllegalStateException("Requires -Dgtswn.stress=true");
        new QuantumSecurityStressBenchmarkTest().explicitlySelectedSecurityStress();
    }

    private static String[] phases() {
        String selected = System.getProperty("gtswn.stress.securityPhases", "all");
        if ("all".equals(selected)) return new String[] { "off_aliases", "on_granted", "on_revoke" };
        if ("off_aliases".equals(selected)) return new String[] { "off_aliases" };
        throw new IllegalArgumentException("gtswn.stress.securityPhases must be all or off_aliases");
    }

    private static final class Fixture {

        final Metrics metrics = new Metrics();
        final FixtureWorld world;
        final Set<Long> positions;
        final long[] aliases;
        final Map<Long, Controller> controllers = new HashMap<>();
        final Map<Class<? extends IGridHost>, List<IGridNode>> machines = new LinkedHashMap<>();
        final Map<Class<? extends IGridHost>, Machines> machineSets = new HashMap<>();
        final EnumSet<SecurityPermissions> build = EnumSet.of(SecurityPermissions.BUILD);
        boolean permissionGranted = true;
        final IGrid grid;

        @SuppressWarnings("unchecked")
        Fixture(int side) throws Exception {
            world = (FixtureWorld) allocate(FixtureWorld.class);
            world.metrics = metrics;
            world.tiles = new HashMap<>();
            WorldProvider provider = new WorldProviderSurface();
            provider.dimensionId = 0;
            field(World.class, "provider").set(world, provider);
            MapStorage storage = new MapStorage(null);
            field(World.class, "perWorldStorage").set(world, storage);
            storage.setData(QuantumControllerRegistry.DATA_NAME, new CountingRegistry(metrics));
            IPathingGrid path = (IPathingGrid) Proxy.newProxyInstance(
                IPathingGrid.class.getClassLoader(),
                new Class<?>[] { IPathingGrid.class },
                (proxy, method, args) -> false);
            ReadOnlyCollection<Class<? extends IGridHost>> classes = new ReadOnlyCollection<>(machines.keySet());
            grid = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getMachinesClasses")) {
                        metrics.gridClasses++;
                        return classes;
                    }
                    if (method.getName()
                        .equals("getMachines")) {
                        metrics.gridMachines++;
                        return machineSets.get(args[0]);
                    }
                    if (method.getName()
                        .equals("getCache")) return args[0] == IPathingGrid.class ? path : null;
                    return null;
                });
            positions = QuantumRegistryStressBenchmarkTest.cube(side);
            aliases = QuantumRegistryStressBenchmarkTest.aliases(side);
            for (long position : positions) {
                Controller tile = (Controller) allocate(Support.fixtureControllerType(Controller.class));
                tile.metrics = metrics;
                tile.identity = "fixture-" + position;
                tile.xCoord = QuantumControllerRegistry.unpackX(position);
                tile.yCoord = QuantumControllerRegistry.unpackY(position);
                tile.zCoord = QuantumControllerRegistry.unpackZ(position);
                tile.setWorldObj(world);
                tile.node = add(tile);
                world.tiles.put(position, tile);
                controllers.put(position, tile);
            }
            IGridHost security = (IGridHost) Proxy.newProxyInstance(
                IGridHost.class.getClassLoader(),
                new Class<?>[] { IGridHost.class, ISecurityProvider.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getOwner")) return 1;
                    if (method.getName()
                        .equals("getSecurityKey")) return 123L;
                    if (method.getName()
                        .equals("readPermissions")) {
                        metrics.permissionReads++;
                        if (permissionGranted) ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0]).put(7, build);
                    }
                    return null;
                });
            add(security);
            for (Map.Entry<Class<? extends IGridHost>, List<IGridNode>> entry : machines.entrySet())
                machineSets.put(entry.getKey(), new Machines(entry.getKey(), entry.getValue()));
            QuantumControllerRegistry.get(world)
                .quantizeAll(positions);
        }

        IGridNode add(IGridHost host) {
            IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getGrid")) return grid;
                    if (method.getName()
                        .equals("getMachine")) {
                        metrics.machineReads++;
                        return host;
                    }
                    if (method.getName()
                        .equals("getPlayerID")) return 7;
                    return null;
                });
            machines.computeIfAbsent(host.getClass(), ignored -> new ArrayList<>())
                .add(node);
            return node;
        }

        boolean call(int index, boolean off) {
            long alias = aliases[index % aliases.length];
            Controller tile = controllers.get(alias);
            return off
                ? QuantumConnectionSecurity
                    .bypassesNetworkSafety(world, tile.xCoord, tile.yCoord, tile.zCoord, tile.node, tile.node)
                : QuantumConnectionSecurity
                    .mayBridge(world, tile.xCoord, tile.yCoord, tile.zCoord, tile.node, tile.node, () -> 7);
        }
    }

    private static final class Machines extends ReadOnlyCollection<IGridNode> implements IMachineSet {

        final Class<? extends IGridHost> type;

        Machines(Class<? extends IGridHost> type, List<IGridNode> nodes) {
            super(nodes);
            this.type = type;
        }

        @Override
        public Class<? extends IGridHost> getMachineClass() {
            return type;
        }
    }

    public static class Controller extends TileController implements QuantumControllerIdentity {

        Metrics metrics;
        String identity;
        IGridNode node;
        boolean safety;

        @Override
        public boolean gtswn$isNetworkSafetyEnabled() {
            metrics.safetyReads++;
            return safety;
        }

        @Override
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

        @Override
        public IGridNode getGridNode(ForgeDirection direction) {
            return node;
        }
    }

    private static final class FixtureWorld extends World {

        Map<Long, TileEntity> tiles;
        Metrics metrics;

        private FixtureWorld() {
            super((ISaveHandler) null, "stress-fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
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
