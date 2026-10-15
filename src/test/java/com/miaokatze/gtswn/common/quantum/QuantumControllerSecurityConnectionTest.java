package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.misc.TileSecurity;
import appeng.tile.networking.TileController;

public class QuantumControllerSecurityConnectionTest {

    @Test
    public void nativeSecurityStationKeepsItsControllerSideWhileOtherMachinesRemainIsolated() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        world.tiles = new HashMap<>();
        world.tiles.put(QuantumControllerRegistry.pack(1, 64, 0), (TileEntity) allocate(TileSecurity.class));
        world.tiles.put(QuantumControllerRegistry.pack(-1, 64, 0), new TileEntity());
        for (int generation = 0; generation < 2; generation++) {
            Controller controller = (Controller) allocate(Controller.class);
            controller.proxy = new AENetworkProxy((IGridProxyable) (TileEntity) controller, "proxy", null, true);
            world.tiles.put(QuantumControllerRegistry.pack(0, 64, 0), controller);
            QuantumControllerEventHandler.applyConnectionFilter(world, 0, 64, 0);
            assertEquals(EnumSet.of(ForgeDirection.EAST), controller.proxy.getConnectableSides());
            // The periodic sweep must preserve the same native security route without another rebuild.
            EnumSet<ForgeDirection> sides = controller.proxy.getConnectableSides();
            QuantumControllerEventHandler.applyConnectionFilter(world, 0, 64, 0);
            assertSame(sides, controller.proxy.getConnectableSides());
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static class Controller extends TileController {

        private AENetworkProxy proxy;

        @Override
        public AENetworkProxy getProxy() {
            return proxy;
        }
    }

    private static class FixtureWorld extends World {

        private Map<Long, TileEntity> tiles;

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
