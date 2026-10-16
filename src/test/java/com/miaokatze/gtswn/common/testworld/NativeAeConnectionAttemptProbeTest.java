package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldServer;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import com.miaokatze.gtswn.common.quantum.QuantumNodeBridgeRelay;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes.Type;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;

/** Counts native security-gate entry, independently of the later authorization verdict. */
public class NativeAeConnectionAttemptProbeTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @After
    public void stop() {
        TestWorldProbes.stop();
    }

    @Test
    public void eitherEnrolledEndpointCountsOnceAndBothEnrolledEndpointsNeverDoubleCount() throws Exception {
        List<String> events = start();
        World world = world(true, TestDimension.dimId(), false);
        IGridNode first = endpoint(tile(world, 1));
        IGridNode second = endpoint(tile(world, 2));
        TestWorldProbes.addPosition(1, 65, 8);
        TestWorldProbes.addPosition(2, 65, 8);
        long before = TestWorldProbes.nativeAeConnectionAttempts();
        TestWorldProbes.aeConnectionAttempt(first, second);
        TestWorldProbes.aeConnectionAttempt(null, second);
        assertEquals(2L, TestWorldProbes.count(Type.AE_CONNECTION_ATTEMPT));
        assertEquals(before + 2, TestWorldProbes.nativeAeConnectionAttempts());
        assertEquals(2, events.size());
    }

    @Test
    public void unrelatedWorldsCoordinatesClientTilesAndUnattachedHostsAreIgnored() throws Exception {
        start();
        TestWorldProbes.addPosition(1, 65, 8);
        long before = TestWorldProbes.nativeAeConnectionAttempts();
        TestWorldProbes.aeConnectionAttempt(endpoint(tile(world(false, TestDimension.dimId(), false), 1)), null);
        TestWorldProbes.aeConnectionAttempt(endpoint(tile(world(true, TestDimension.dimId() + 1, false), 1)), null);
        TestWorldProbes.aeConnectionAttempt(endpoint(tile(world(true, TestDimension.dimId(), true), 1)), null);
        TestWorldProbes.aeConnectionAttempt(endpoint(tile(world(true, TestDimension.dimId(), false), 2)), null);
        TestWorldProbes.aeConnectionAttempt(endpoint(tile(null, 1)), null);
        TestWorldProbes.aeConnectionAttempt(null, null);
        assertEquals(0L, TestWorldProbes.count(Type.AE_CONNECTION_ATTEMPT));
        assertEquals(before, TestWorldProbes.nativeAeConnectionAttempts());
    }

    @Test
    public void relayUsesActualOwnerTileAndMonotonicCounterSurvivesScenarioAndSinkFailure() throws Exception {
        start();
        TileEntityNetworkQuantumNode owner = (TileEntityNetworkQuantumNode) allocate(
            TileEntityNetworkQuantumNode.class);
        owner.xCoord = 1;
        owner.yCoord = 65;
        owner.zCoord = 8;
        owner.setWorldObj(world(true, TestDimension.dimId(), false));
        IGridNode relay = endpoint(new QuantumNodeBridgeRelay(owner, null));
        TestWorldProbes.addPosition(1, 65, 8);
        long before = TestWorldProbes.nativeAeConnectionAttempts();
        TestWorldProbes.aeConnectionAttempt(relay, relay);
        TestWorldProbes.stop();
        assertEquals(before + 1, TestWorldProbes.nativeAeConnectionAttempts());
        TestWorldProbes
            .start((type, detail) -> { throw new IllegalStateException("diagnostic sink"); }, TestDimension.dimId());
        assertEquals(0L, TestWorldProbes.count(Type.AE_CONNECTION_ATTEMPT));
        TestWorldProbes.addPosition(1, 65, 8);
        TestWorldProbes.aeConnectionAttempt(relay, null);
        assertEquals(before + 2, TestWorldProbes.nativeAeConnectionAttempts());
        assertEquals(1L, TestWorldProbes.count(Type.AE_CONNECTION_ATTEMPT));
    }

    @Test
    public void inactiveSessionDoesNotReadEitherEndpoint() {
        TestWorldProbes.stop();
        IGridNode unreadable = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> { throw new AssertionError("Idle probe read an endpoint"); });
        long before = TestWorldProbes.nativeAeConnectionAttempts();
        TestWorldProbes.aeConnectionAttempt(unreadable, unreadable);
        assertEquals(before, TestWorldProbes.nativeAeConnectionAttempts());
    }

    private static List<String> start() {
        List<String> events = new ArrayList<>();
        TestWorldProbes.start((type, detail) -> events.add(type), TestDimension.dimId());
        return events;
    }

    private static IGridNode endpoint(IGridHost host) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> method.getName()
                .equals("getMachine") ? host : null);
    }

    private static IGridHost tile(World world, int x) throws Exception {
        TileEntity tile = (TileEntity) allocate(Class.forName("appeng.tile.networking.TileController"));
        tile.xCoord = x;
        tile.yCoord = 65;
        tile.zCoord = 8;
        tile.setWorldObj(world);
        return (IGridHost) tile;
    }

    private static World world(boolean test, int dimension, boolean remote) throws Exception {
        World world = (World) allocate(WorldServer.class);
        WorldProvider provider = test ? new WorldProviderTest() : new WorldProviderSurface();
        provider.dimensionId = dimension;
        field(World.class, "provider").set(world, provider);
        field(World.class, "isRemote").setBoolean(world, remote);
        return world;
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
