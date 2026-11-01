package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.EnumSet;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.me.helpers.AENetworkProxy;

public class MatrixProxyLifecycleTest {

    @Test
    public void electingAnAlreadyLoadedMasterRebuildsItsNodeWithItsNativeIdentity() throws Exception {
        Fixture fixture = new Fixture();
        fixture.proxy.onReady();
        Endpoint original = fixture.proxy.endpoint;
        original.identity.setInteger("p", 17);
        original.identity.setLong("k", 123456789L);
        original.identity.setLong("g", 73L);

        fixture.port.bind(group(fixture.member));

        assertTrue(fixture.proxy.isReady());
        assertEquals(1, original.destroyed);
        assertNotSame(original.node, fixture.port.node());
        assertEquals(original.identity, fixture.proxy.endpoint.identity);
        assertEquals(EnumSet.of(GridFlags.REQUIRE_CHANNEL), fixture.proxy.flagsAtCreation);
    }

    @Test
    public void changingBetweenMasterAndMemberRebuildsOnlyWhenTheRoleChanges() throws Exception {
        Fixture fixture = new Fixture();
        fixture.proxy.onReady();
        OrderedAssemblyGroup first = group(fixture.member);
        fixture.port.bind(first);
        Endpoint master = fixture.proxy.endpoint;
        assertTrue(fixture.proxy.isReady());

        fixture.port.bind(first);
        fixture.port.bind(group(fixture.member));
        assertSame(master.node, fixture.port.node());
        assertEquals(0, master.destroyed);

        fixture.port.bind(group(member()));
        assertTrue(fixture.proxy.isReady());
        assertEquals(1, master.destroyed);
        assertNotSame(master.node, fixture.port.node());
        assertEquals(EnumSet.noneOf(GridFlags.class), fixture.proxy.flagsAtCreation);
    }

    @Test
    public void bindingBeforeTheFirstTickPreservesDeferredIdentityWithoutStartingTheProxy() throws Exception {
        Fixture fixture = new Fixture();
        NBTTagCompound saved = new NBTTagCompound();
        NBTTagCompound identity = new NBTTagCompound();
        identity.setInteger("p", 29);
        identity.setLong("k", 789L);
        identity.setLong("g", 101L);
        saved.setTag("matrixProxy", identity);
        fixture.proxy.readFromNBT(saved);

        fixture.port.bind(group(fixture.member));

        assertFalse(fixture.proxy.isReady());
        assertNull(fixture.proxy.endpoint);
        assertNull(fixture.port.node());
        fixture.proxy.onReady();
        assertEquals(identity, fixture.proxy.endpoint.identity);
        assertEquals(EnumSet.of(GridFlags.REQUIRE_CHANNEL), fixture.proxy.flagsAtCreation);
    }

    @Test
    public void theGroupOwnsTheRestoredLedgerAfterBinding() throws Exception {
        Fixture fixture = new Fixture();
        NBTTagCompound ledger = new NBTTagCompound();
        ledger.setString("stage", "IDLE");
        field(MatrixAssemblyPort.class, "restoredLedger").set(fixture.port, ledger);

        fixture.port.bind(group(fixture.member));

        assertNull(fixture.port.restoredLedger());
    }

    @Test
    public void powerAndChannelTransitionsRefreshThePatternCatalogueOncePerChange() throws Exception {
        Fixture fixture = new Fixture();
        Field pending = field(MatrixAssemblyPort.class, "patternChange");
        pending.setBoolean(fixture.port, false);
        fixture.proxy.active = true;
        fixture.port.tick(1);
        assertTrue(pending.getBoolean(fixture.port));
        pending.setBoolean(fixture.port, false);
        fixture.port.tick(2);
        assertFalse(pending.getBoolean(fixture.port));
        fixture.proxy.active = false;
        fixture.port.tick(3);
        assertTrue(pending.getBoolean(fixture.port));
        pending.setBoolean(fixture.port, false);
        fixture.port.tick(4);
        assertFalse(pending.getBoolean(fixture.port));
    }

    private static MatrixAssemblyMember member() {
        return (MatrixAssemblyMember) Proxy.newProxyInstance(
            MatrixAssemblyMember.class.getClassLoader(),
            new Class<?>[] { MatrixAssemblyMember.class },
            (proxy, method, args) -> { throw new AssertionError(method); });
    }

    private static OrderedAssemblyGroup group(MatrixAssemblyMember master) throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        OrderedAssemblyGroup group = (OrderedAssemblyGroup) ((sun.misc.Unsafe) singleton.get(null))
            .allocateInstance(OrderedAssemblyGroup.class);
        field(OrderedAssemblyGroup.class, "items").set(group, Collections.singletonList(master));
        field(OrderedAssemblyGroup.class, "master").set(group, master);
        return group;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class Fixture {

        final MatrixAssemblyMember member;
        final MatrixAssemblyPort port;
        final RecordingProxy proxy = new RecordingProxy();

        Fixture() throws Exception {
            MatrixAssemblyPort[] holder = new MatrixAssemblyPort[1];
            member = (MatrixAssemblyMember) Proxy.newProxyInstance(
                MatrixAssemblyMember.class.getClassLoader(),
                new Class<?>[] { MatrixAssemblyMember.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("matrixPort")) return holder[0];
                    throw new AssertionError(method);
                });
            port = new MatrixAssemblyPort(member);
            holder[0] = port;
            field(MatrixAssemblyPort.class, "proxy").set(port, proxy);
        }
    }

    /** Keep AE's real ready/save/load/destroy lifecycle; replace only native node allocation. */
    private static final class RecordingProxy extends AENetworkProxy {

        Endpoint endpoint;
        EnumSet<GridFlags> flagsAtCreation;
        boolean active;

        RecordingProxy() {
            super(null, "matrixProxy", null, true);
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public IGridNode getNode() {
            if (!isReady()) return null;
            try {
                Field node = field(AENetworkProxy.class, "node");
                if (node.get(this) == null) {
                    endpoint = new Endpoint();
                    flagsAtCreation = getFlags().clone();
                    node.set(this, endpoint.node);
                    readFromNBT((NBTTagCompound) field(AENetworkProxy.class, "data").get(this));
                }
                return endpoint.node;
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
    }

    private static final class Endpoint {

        NBTTagCompound identity = new NBTTagCompound();
        int destroyed;
        final IGridNode node = (IGridNode) Proxy.newProxyInstance(
            IGridNode.class.getClassLoader(),
            new Class<?>[] { IGridNode.class },
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "saveToNBT":
                        ((NBTTagCompound) args[1]).setTag((String) args[0], identity.copy());
                        return null;
                    case "loadFromNBT":
                        identity = (NBTTagCompound) ((NBTTagCompound) args[1]).getCompoundTag((String) args[0])
                            .copy();
                        return null;
                    case "destroy":
                        destroyed++;
                        return null;
                    default:
                        throw new AssertionError(method);
                }
            });
    }
}
