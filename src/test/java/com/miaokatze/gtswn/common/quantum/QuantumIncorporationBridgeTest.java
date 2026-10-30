package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

import com.mojang.authlib.GameProfile;

import appeng.api.features.IPlayerRegistry;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.util.ReadOnlyCollection;

public class QuantumIncorporationBridgeTest {

    @Test
    public void legacyIncorporationResolvesItsOwnUuidAndPersistsNativeActorWithoutInventingIdentity() throws Exception {
        String uuid = UUID.randomUUID()
            .toString();
        NBTTagCompound legacy = new NBTTagCompound();
        legacy.setString("identity", "saved-generation");
        legacy.setString("owner", uuid);
        NBTTagList entries = new NBTTagList();
        entries.appendTag(legacy);
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("entries", entries);
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("legacy");
        registry.readFromNBT(saved);
        NBTTagCompound roundTrip = new NBTTagCompound();
        registry.writeToNBT(roundTrip);
        assertFalse(
            roundTrip.getTagList("entries", 10)
                .getCompoundTagAt(0)
                .hasKey("nativeOwnerID"));
        Field registered = QuantumIncorporationRegistry.class.getDeclaredField("entries");
        registered.setAccessible(true);
        QuantumIncorporationRegistry.Entry entry = ((Map<Long, QuantumIncorporationRegistry.Entry>) registered
            .get(registry)).values()
                .iterator()
                .next();
        IPlayerRegistry players = (IPlayerRegistry) Proxy.newProxyInstance(
            IPlayerRegistry.class.getClassLoader(),
            new Class<?>[] { IPlayerRegistry.class },
            (proxy, method, args) -> {
                assertEquals(UUID.fromString(uuid), ((GameProfile) args[0]).getId());
                return 37;
            });
        assertEquals(37, entry.resolveNativeActor(players));
        registry.writeToNBT(roundTrip);
        NBTTagCompound current = roundTrip.getTagList("entries", 10)
            .getCompoundTagAt(0);
        assertEquals(uuid, current.getString("owner"));
        assertEquals(37, current.getInteger("nativeOwnerID"));
        registry.readFromNBT(roundTrip);
        entry = ((Map<Long, QuantumIncorporationRegistry.Entry>) registered.get(registry)).values()
            .iterator()
            .next();
        assertEquals(-1, entry.resolveNativeActor(null)); // A saved number is not current UUID authority.
        assertEquals(37, entry.resolveNativeActor(players));
        assertEquals(37, entry.resolveNativeActor(null)); // Only this session's resolved number is cached.
        set(entry, "nativeOwnerID", -1);
        set(entry, "owner", "invalid-or-missing-legacy-uuid");
        assertEquals(-1, entry.resolveNativeActor(players));
    }

    @Test
    public void restoredIncorporationRevalidatesNativeActorAgainstCurrentUuidMapping() throws Exception {
        UUID owner = UUID.randomUUID();
        NBTTagCompound binding = new NBTTagCompound();
        binding.setString("identity", "saved-generation");
        binding.setString("owner", owner.toString());
        binding.setInteger("nativeOwnerID", 37); // This number now belongs to another UUID in AE's restored file.
        NBTTagList entries = new NBTTagList();
        entries.appendTag(binding);
        NBTTagCompound saved = new NBTTagCompound();
        saved.setTag("entries", entries);
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("restored");
        registry.readFromNBT(saved);
        QuantumIncorporationRegistry.Entry entry = registry.snapshot()
            .iterator()
            .next();
        AtomicInteger calls = new AtomicInteger();
        IPlayerRegistry players = (IPlayerRegistry) Proxy.newProxyInstance(
            IPlayerRegistry.class.getClassLoader(),
            new Class<?>[] { IPlayerRegistry.class },
            (proxy, method, args) -> {
                calls.incrementAndGet();
                assertEquals(owner, ((GameProfile) args[0]).getId());
                return 91;
            });
        assertEquals(-1, entry.resolveNativeActor(null));
        assertEquals(91, entry.resolveNativeActor(players));
        assertEquals(91, entry.resolveNativeActor(players));
        assertEquals(1, calls.get()); // Revalidation is once per restored entry, not every bridge tick.
        NBTTagCompound current = new NBTTagCompound();
        registry.writeToNBT(current);
        assertEquals(
            91,
            current.getTagList("entries", 10)
                .getCompoundTagAt(0)
                .getInteger("nativeOwnerID"));

        binding.setString("owner", "invalid-owner");
        registry.readFromNBT(saved);
        entry = registry.snapshot()
            .iterator()
            .next();
        assertEquals(-1, entry.resolveNativeActor(players));
        assertEquals(1, calls.get()); // Even a saved privileged number cannot replace a missing UUID.
    }

    @Test
    public void unavailableNativeActorIsRetriedWithoutChangingSavedOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        QuantumIncorporationRegistry.Entry entry = new QuantumIncorporationRegistry.Entry();
        set(entry, "owner", owner.toString());
        AtomicInteger calls = new AtomicInteger();
        IPlayerRegistry players = (IPlayerRegistry) Proxy.newProxyInstance(
            IPlayerRegistry.class.getClassLoader(),
            new Class<?>[] { IPlayerRegistry.class },
            (proxy, method, args) -> {
                assertEquals(owner, ((GameProfile) args[0]).getId());
                int attempt = calls.incrementAndGet();
                if (attempt == 1) throw new IllegalStateException("Native registry is not ready");
                return attempt == 2 ? -1 : 91;
            });
        assertEquals(-1, entry.resolveNativeActor(players));
        assertEquals(-1, entry.resolveNativeActor(players));
        assertEquals(91, entry.resolveNativeActor(players));
        assertEquals(91, entry.resolveNativeActor(players));
        assertEquals(3, calls.get());
        assertEquals(owner.toString(), entry.owner());
    }

    @Test
    public void relayIsOneNativeDenseNodeWithoutConsumingAChannel() throws Exception {
        Class<?> leaf = Class.forName(QuantumIncorporationRegistry.class.getName() + "$BillingLeaf");
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        IGridBlock bridge = (IGridBlock) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(leaf);
        assertEquals(EnumSet.of(GridFlags.DENSE_CAPACITY), bridge.getFlags());
        assertFalse(bridge.isWorldAccessible());
        assertTrue(
            bridge.getConnectableSides()
                .isEmpty());
    }

    @Test
    public void twoEdgeRelayIsLiveAndCountsChannelsOnceAndReclaimsBothEdges() throws Exception {
        IGrid grid = (IGrid) Proxy.newProxyInstance(
            IGrid.class.getClassLoader(),
            new Class<?>[] { IGrid.class },
            (proxy, method, args) -> null);
        ArrayList<IGridConnection> localEdges = new ArrayList<>(), relayEdges = new ArrayList<>(),
            targetEdges = new ArrayList<>();
        AtomicInteger localDestroyed = new AtomicInteger(), targetDestroyed = new AtomicInteger(),
            relayDestroyed = new AtomicInteger();
        IGridNode local = node(localEdges, grid, new AtomicInteger()), relay = node(relayEdges, grid, relayDestroyed),
            target = node(targetEdges, grid, new AtomicInteger());
        IGridConnection deviceEdge = edge(localEdges, relayEdges, localDestroyed),
            controllerEdge = edge(relayEdges, targetEdges, targetDestroyed);
        QuantumIncorporationRegistry registry = new QuantumIncorporationRegistry("suspended");
        NBTTagCompound saved = new NBTTagCompound(), binding = new NBTTagCompound();
        binding.setLong("pos", QuantumControllerRegistry.pack(10, 64, 0));
        binding.setString("identity", "unchanged-incarnation");
        binding.setString("owner", "unchanged-owner");
        binding.setString("anchorGeneration", "unchanged-generation");
        binding.setInteger("nativeOwnerID", 37);
        NBTTagList bindings = new NBTTagList();
        bindings.appendTag(binding);
        saved.setTag("entries", bindings);
        registry.readFromNBT(saved);
        QuantumIncorporationRegistry.Entry entry = registry.snapshot()
            .iterator()
            .next();
        NBTTagCompound before = new NBTTagCompound();
        registry.writeToNBT(before);
        set(entry, "local", local);
        set(entry, "billingNode", relay);
        set(entry, "target", target);
        set(entry, "connection", deviceEdge);
        set(entry, "billingConnection", controllerEdge);
        Field activeField = QuantumIncorporationRegistry.class.getDeclaredField("ACTIVE");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<AnchorKey, Set<QuantumIncorporationRegistry.Entry>> active = (Map<AnchorKey, Set<QuantumIncorporationRegistry.Entry>>) activeField
            .get(null);
        AnchorKey anchor = new AnchorKey(0, 0, 0, 0);
        Set<QuantumIncorporationRegistry.Entry> indexed = Collections.newSetFromMap(new IdentityHashMap<>());
        indexed.add(entry);
        Set<QuantumIncorporationRegistry.Entry> previous = active.put(anchor, indexed);
        try {
            assertTrue(entry.isConnected());
            assertEquals(32, entry.usedChannels());
            assertArrayEquals(
                new int[] { 1, 32, 32 },
                QuantumIncorporationRegistry.incorporationStats(
                    0,
                    Collections.singleton(QuantumControllerRegistry.pack(0, 0, 0)),
                    grid,
                    Collections.emptySet()));
            registry.controllerSuspended(0, Collections.singleton(QuantumControllerRegistry.pack(0, 0, 0)));
            registry.controllerSuspended(0, Collections.singleton(QuantumControllerRegistry.pack(0, 0, 0)));
            assertFalse(entry.isConnected());
            assertEquals(1, localDestroyed.get());
            assertEquals(1, targetDestroyed.get());
            assertEquals(1, relayDestroyed.get());
            NBTTagCompound after = new NBTTagCompound();
            registry.writeToNBT(after);
            assertEquals(before, after);
            assertSame(
                entry,
                registry.snapshot()
                    .iterator()
                    .next());
        } finally {
            active.remove(anchor);
            if (previous != null) active.put(anchor, previous);
        }
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static IGridNode node(ArrayList<IGridConnection> edges, IGrid grid, AtomicInteger destroyed) {
        return (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getConnections")) return new ReadOnlyCollection<>(edges);
                if (method.getName()
                    .equals("getGrid")) return grid;
                if (method.getName()
                    .equals("destroy")) destroyed.incrementAndGet();
                return null;
            });
    }

    private static IGridConnection edge(ArrayList<IGridConnection> first, ArrayList<IGridConnection> second,
        AtomicInteger destroyed) {
        IGridConnection edge = (IGridConnection) Proxy.newProxyInstance(
            IGridConnection.class.getClassLoader(),
            new Class<?>[] { IGridConnection.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("equals")) return proxy == args[0];
                if (method.getName()
                    .equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName()
                    .equals("getUsedChannels")) return 32;
                if (method.getName()
                    .equals("destroy")) {
                    destroyed.incrementAndGet();
                    first.remove(proxy);
                    second.remove(proxy);
                }
                return null;
            });
        first.add(edge);
        second.add(edge);
        return edge;
    }
}
