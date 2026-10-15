package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
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

import com.miaokatze.gtswn.config.Config;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;
import appeng.util.ReadOnlyCollection;

public class QuantumNetworkSafetyPolicyTest {

    @Test
    public void policyDefaultsOffAndControllerPropertySurvivesRegistryReloadAndDequantization() throws Exception {
        FixtureWorld world = world(0);
        Controller tile = controller(world, 0);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        assertFalse(policy.isSafetyEnabled(world, members(0)));
        policy.setSafety(world, members(0), true);
        assertTrue(tile.gtswn$isNetworkSafetyEnabled());
        NBTTagCompound saved = new NBTTagCompound();
        policy.writeToNBT(saved);
        policy.readFromNBT(saved);
        assertTrue(policy.isSafetyEnabled(world, members(0)));
        QuantumControllerRegistry.get(world)
            .dequantize(0, 64, 0);
        QuantumControllerRegistry.get(world)
            .quantize(0, 64, 0);
        assertTrue(policy.isSafetyEnabled(world, members(0)));
    }

    @Test
    public void oldEnrollmentAndCoordinateRecordsCannotEnableAReplacementController() throws Exception {
        FixtureWorld world = world(0);
        controller(world, 0);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setIntArray("enrolled", new int[] { 0, 64, 0 });
        policy.readFromNBT(legacy);
        assertFalse(policy.isSafetyEnabled(world, members(0)));
        policy.setSafety(world, members(0), true);
        assertTrue(policy.isSafetyEnabled(world, members(0)));
        controller(world, 0);
        assertFalse(policy.isSafetyEnabled(world, members(0)));
    }

    @Test
    public void migrationCarriesExplicitPolicyAndNeverWeakensAnOnTarget() throws Exception {
        FixtureWorld source = world(0), target = world(-1);
        controller(source, 0);
        controller(target, 32);
        QuantumNetworkSecurityRegistry from = QuantumNetworkSecurityRegistry.get(source);
        QuantumNetworkSecurityRegistry to = QuantumNetworkSecurityRegistry.get(target);
        QuantumNetworkSecurityRegistry.transfer(source, members(0), target, members(32));
        assertFalse(to.isSafetyEnabled(target, members(32)));
        from.setSafety(source, members(0), true);
        QuantumNetworkSecurityRegistry.transfer(source, members(0), target, members(32));
        assertTrue(to.isSafetyEnabled(target, members(32)));
        from.setSafety(source, members(0), false);
        QuantumNetworkSecurityRegistry.transfer(source, members(0), target, members(32));
        assertTrue(to.isSafetyEnabled(target, members(32)));
    }

    @Test
    public void explicitOffSurvivesUnloadedMemberReloadAndDoesNotTransferToReplacement() throws Exception {
        FixtureWorld world = world(0);
        Controller first = controller(world, 0), second = controller(world, 1);
        Set<Long> structure = new java.util.HashSet<>(members(0));
        structure.addAll(members(1));
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        policy.setSafety(world, structure, true);
        world.tiles.remove(QuantumControllerRegistry.pack(1, 64, 0));
        policy.setSafety(world, structure, false);
        assertFalse(first.gtswn$isNetworkSafetyEnabled());
        assertTrue(second.gtswn$isNetworkSafetyEnabled());
        NBTTagCompound saved = new NBTTagCompound();
        policy.writeToNBT(saved);
        policy.readFromNBT(saved);
        world.tiles.put(QuantumControllerRegistry.pack(1, 64, 0), second);
        assertFalse(policy.isSafetyEnabled(world, structure));
        assertFalse(second.gtswn$isNetworkSafetyEnabled());
        world.tiles.remove(QuantumControllerRegistry.pack(1, 64, 0));
        policy.setSafety(world, structure, true);
        first.gtswn$setNetworkSafetyEnabled(false);
        Controller replacement = controller(world, 1);
        assertFalse(policy.isSafetyEnabled(world, structure));
        assertFalse(replacement.gtswn$isNetworkSafetyEnabled());
    }

    @Test
    public void offWithoutHistoricalTrustedKeysBypassesOwnProvidersButNeverForeignProtectedDomains() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            FixtureWorld world = world(0);
            Controller own = controller(world, 0), other = controller(world, 32);
            Grid ownGrid = new Grid(), foreignGrid = new Grid();
            own.node = ownGrid.add(own);
            other.node = foreignGrid.add(other);
            QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
            assertTrue(QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.node, own.node));
            ownGrid.add(provider(123L));
            assertTrue(QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.node, own.node));
            IGridNode foreignProvider = foreignGrid.add(provider(456L));
            assertFalse(QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.node, other.node));
            foreignGrid.remove(foreignProvider);
            assertTrue(QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.node, other.node));
            policy.setSafety(world, members(32), true);
            assertFalse(QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.node, other.node));
            policy.setSafety(world, members(0), true);
            assertFalse(QuantumConnectionSecurity.bypassesNetworkSafety(world, 0, 64, 0, own.node, own.node));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    private static Set<Long> members(int x) {
        return Collections.singleton(QuantumControllerRegistry.pack(x, 64, 0));
    }

    private static FixtureWorld world(int dimension) throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tiles = new HashMap<>();
        WorldProvider provider = new WorldProviderSurface();
        provider.dimensionId = dimension;
        field(World.class, "provider").set(world, provider);
        field(World.class, "perWorldStorage").set(world, new MapStorage(null));
        return world;
    }

    private static Controller controller(FixtureWorld world, int x) throws Exception {
        Controller tile = (Controller) allocate(Controller.class);
        tile.identity = "";
        tile.xCoord = x;
        tile.yCoord = 64;
        tile.setWorldObj(world);
        world.tiles.put(QuantumControllerRegistry.pack(x, 64, 0), tile);
        QuantumControllerRegistry.get(world)
            .quantize(x, 64, 0);
        return tile;
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

    private static IGridHost provider(long key) {
        return (IGridHost) Proxy.newProxyInstance(
            IGridHost.class.getClassLoader(),
            new Class<?>[] { IGridHost.class, ISecurityProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getSecurityKey")) return key;
                if (method.getName()
                    .equals("getOwner")) return 1;
                return null;
            });
    }

    private static class Controller extends TileController implements QuantumControllerIdentity {

        String identity;
        IGridNode node;

        private boolean networkSafetyEnabled;

        @Override
        public boolean gtswn$isNetworkSafetyEnabled() {
            return networkSafetyEnabled;
        }

        @Override
        public void gtswn$setNetworkSafetyEnabled(boolean enabled) {
            networkSafetyEnabled = enabled;
        }

        @Override
        public String gtswn$getControllerIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setControllerIdentity(String value) {
            identity = value;
        }

        @Override
        public void markDirty() {}

        @Override
        public IGridNode getGridNode(ForgeDirection direction) {
            return node;
        }
    }

    private static class Grid {

        final Map<Class<? extends IGridHost>, List<IGridNode>> machines = new HashMap<>();
        final IPathingGrid path = (IPathingGrid) Proxy.newProxyInstance(
            IPathingGrid.class.getClassLoader(),
            new Class<?>[] { IPathingGrid.class },
            (proxy, method, args) -> false);
        final IGrid grid = (IGrid) Proxy
            .newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[] { IGrid.class }, (proxy, method, args) -> {
                if (method.getName()
                    .equals("getMachinesClasses")) return new ReadOnlyCollection<>(machines.keySet());
                if (method.getName()
                    .equals("getMachines"))
                    return new Machines((Class<? extends IGridHost>) args[0], machines.get(args[0]));
                if (method.getName()
                    .equals("getCache")) return args[0] == IPathingGrid.class ? path : null;
                return null;
            });

        IGridNode add(IGridHost host) {
            IGridNode node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> method.getName()
                    .equals("getGrid") ? grid
                        : method.getName()
                            .equals("getMachine") ? host : null);
            machines.computeIfAbsent(host.getClass(), ignored -> new ArrayList<>())
                .add(node);
            return node;
        }

        void remove(IGridNode node) {
            List<IGridNode> values = machines.get(
                node.getMachine()
                    .getClass());
            values.removeIf(value -> value == node);
            if (values.isEmpty()) machines.remove(
                node.getMachine()
                    .getClass());
        }
    }

    private static class Machines extends ReadOnlyCollection<IGridNode> implements IMachineSet {

        private final Class<? extends IGridHost> type;

        Machines(Class<? extends IGridHost> type, List<IGridNode> nodes) {
            super(nodes);
            this.type = type;
        }

        @Override
        public Class<? extends IGridHost> getMachineClass() {
            return type;
        }
    }

    private static class FixtureWorld extends World {

        Map<Long, TileEntity> tiles;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
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
            return tiles.containsKey(QuantumControllerRegistry.pack(x, y, z));
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            return tiles.get(QuantumControllerRegistry.pack(x, y, z));
        }
    }
}
