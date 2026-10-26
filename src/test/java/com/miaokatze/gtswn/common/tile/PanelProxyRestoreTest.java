package com.miaokatze.gtswn.common.tile;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;

import org.junit.Test;

import appeng.api.networking.IGridNode;
import appeng.me.helpers.AENetworkProxy;

public class PanelProxyRestoreTest {

    @Test
    public void deferredWorldAssignmentPreservesTheSameNativeStorageOwnerAndSecurityAsImmediateLoad() throws Exception {
        NBTTagCompound saved = saved(11);
        Panel immediate = new Panel();
        immediate.setWorldObj(world());
        immediate.readFromNBT(saved);
        Panel deferred = new Panel();
        deferred.readFromNBT(saved);
        assertEquals(0, deferred.proxy.reads);
        deferred.setWorldObj(world());
        flush(deferred);
        assertEquals(immediate.proxy.loaded, deferred.proxy.loaded);
        assertEquals(11, deferred.proxy.loaded.getLong("g"));
        assertEquals(73, deferred.proxy.loaded.getInteger("p"));
        assertEquals(123456789, deferred.proxy.loaded.getLong("k"));
    }

    @Test
    public void deferredInputOwnsItsProxyCopyBeforeTheCallerReusesTheParentTag() throws Exception {
        NBTTagCompound saved = saved(11);
        Panel deferred = new Panel();
        deferred.readFromNBT(saved);
        saved.getCompoundTag("proxy")
            .setLong("g", 999);
        saved.getCompoundTag("proxy")
            .setInteger("p", 0);
        saved.removeTag("proxy");
        deferred.setWorldObj(world());
        flush(deferred);
        assertEquals(11, deferred.proxy.loaded.getLong("g"));
        assertEquals(73, deferred.proxy.loaded.getInteger("p"));
    }

    @Test
    public void immediateInputAlsoOwnsItsNativeParentWrapper() throws Exception {
        NBTTagCompound saved = saved(11);
        Panel immediate = new Panel();
        immediate.setWorldObj(world());
        immediate.readFromNBT(saved);
        saved.getCompoundTag("proxy")
            .setLong("g", 999);
        saved.removeTag("proxy");
        assertEquals(
            11,
            immediate.proxy.parent.getCompoundTag("proxy")
                .getLong("g"));
    }

    @Test
    public void missingOrCorruptStorageNeverLoadsAnAccidentalNativeStorageZero() throws Exception {
        NBTTagCompound wrongType = new NBTTagCompound();
        wrongType.setString("proxy", "bad");
        NBTTagCompound emptyNode = new NBTTagCompound();
        emptyNode.setTag("proxy", new NBTTagCompound());
        NBTTagCompound wrongIdType = saved(11);
        wrongIdType.getCompoundTag("proxy")
            .setString("g", "11");
        for (NBTTagCompound invalid : new NBTTagCompound[] { new NBTTagCompound(), wrongType, emptyNode, wrongIdType,
            saved(-1) }) {
            Panel deferred = new Panel();
            deferred.readFromNBT(invalid);
            deferred.setWorldObj(world());
            flush(deferred);
            assertEquals(0, deferred.proxy.reads);
            assertNull(deferred.proxy.loaded);
            Panel immediate = new Panel();
            immediate.setWorldObj(world());
            immediate.readFromNBT(invalid);
            assertEquals(0, immediate.proxy.reads);
        }
    }

    @Test
    public void explicitlySavedRealStorageZeroRemainsValid() throws Exception {
        Panel deferred = new Panel();
        deferred.readFromNBT(saved(0));
        deferred.setWorldObj(world());
        flush(deferred);
        assertEquals(1, deferred.proxy.reads);
        assertTrue(deferred.proxy.loaded.hasKey("g", 4));
        assertEquals(0, deferred.proxy.loaded.getLong("g"));
        assertEquals(73, deferred.proxy.loaded.getInteger("p"));
    }

    @Test
    public void aLaterReadWithoutAUsableProxyDoesNotReplayThePreviousPendingBinding() throws Exception {
        Panel deferred = new Panel();
        deferred.readFromNBT(saved(11));
        deferred.readFromNBT(new NBTTagCompound());
        deferred.setWorldObj(world());
        flush(deferred);
        assertEquals(0, deferred.proxy.reads);
    }

    private static NBTTagCompound saved(long storage) {
        NBTTagCompound parent = new NBTTagCompound(), proxy = new NBTTagCompound();
        proxy.setLong("g", storage);
        proxy.setInteger("p", 73);
        proxy.setLong("k", 123456789);
        parent.setTag("proxy", proxy);
        return parent;
    }

    private static void flush(Panel panel) {
        try {
            panel.updateEntity();
            fail("Fixture must stop after restoring the proxy and before native onReady");
        } catch (TickBoundary expected) { /* Avoid unrelated world, sampling and grid initialization. */ }
    }

    private static final class TickBoundary extends RuntimeException {
    }

    private static final class Panel extends TileEntityNetworkInfoPanel {

        final InputProxy proxy;

        Panel() throws Exception {
            proxy = new InputProxy(this);
        }

        @Override
        public AENetworkProxy getProxy() {
            return proxy;
        }
    }

    private static final class InputProxy extends AENetworkProxy {

        int reads;
        NBTTagCompound loaded, parent;

        InputProxy(Panel panel) throws Exception {
            super(panel, "proxy", null, true);
            IGridNode node = (IGridNode) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (object, method, args) -> {
                    if ("loadFromNBT".equals(method.getName())) {
                        assertEquals("proxy", args[0]);
                        loaded = (NBTTagCompound) ((NBTTagCompound) args[1]).getCompoundTag((String) args[0])
                            .copy();
                    }
                    return null;
                });
            Field field = AENetworkProxy.class.getDeclaredField("node");
            field.setAccessible(true);
            field.set(this, node);
        }

        @Override
        public void readFromNBT(NBTTagCompound tag) {
            reads++;
            parent = tag;
            super.readFromNBT(tag); // Exercise AE's real parent-wrapper/name forwarding contract.
        }

        @Override
        public void onReady() {
            throw new TickBoundary();
        }
    }

    private static FixtureWorld world() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return (FixtureWorld) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), FixtureWorld.class);
    }

    private static final class FixtureWorld extends World {

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        public long getTotalWorldTime() {
            return 100;
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
}
