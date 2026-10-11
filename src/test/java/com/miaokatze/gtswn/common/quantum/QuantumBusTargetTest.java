package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import appeng.api.parts.IPart;
import appeng.tile.networking.TileCableBus;

public class QuantumBusTargetTest {

    private static class Bus extends TileCableBus {

        boolean center;
        boolean empty;
        IPart part = (IPart) Proxy.newProxyInstance(
            IPart.class.getClassLoader(),
            new Class<?>[] { IPart.class },
            (proxy, method, args) -> null);

        @Override
        public IPart getPart(ForgeDirection side) {
            return side == ForgeDirection.UNKNOWN && center ? part : null;
        }

        @Override
        public boolean isEmpty() {
            return empty;
        }
    }

    private static IBlockAccess world(Map<String, TileEntity> tiles) {
        return (IBlockAccess) Proxy.newProxyInstance(
            IBlockAccess.class.getClassLoader(),
            new Class<?>[] { IBlockAccess.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getTileEntity")) return tiles.get(args[0] + "," + args[1] + "," + args[2]);
                throw new AssertionError("Unexpected world query: " + method.getName());
            });
    }

    @Test
    public void clickedBareHostHasPriorityWithoutAnySelectedPartRequirement() {
        Map<String, TileEntity> tiles = new HashMap<>();
        Bus clicked = new Bus();
        Bus adjacent = new Bus();
        tiles.put("4,5,6", clicked);
        tiles.put("5,5,6", adjacent);
        QuantumBusTarget target = QuantumBusTarget.resolve(world(tiles), 4, 5, 6, ForgeDirection.EAST.ordinal());
        assertSame(clicked, target.bus);
        assertEquals(4, target.x);
        assertEquals(5, target.y);
        assertEquals(6, target.z);
    }

    @Test
    public void supportingBlockFindsBareHostOnEachClickedFace() {
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            Map<String, TileEntity> tiles = new HashMap<>();
            Bus adjacent = new Bus();
            tiles.put(face.offsetX + "," + face.offsetY + "," + face.offsetZ, adjacent);
            QuantumBusTarget target = QuantumBusTarget.resolve(world(tiles), 0, 0, 0, face.ordinal());
            assertSame(adjacent, target.bus);
            assertEquals(face.offsetX, target.x);
            assertEquals(face.offsetY, target.y);
            assertEquals(face.offsetZ, target.z);
        }
    }

    @Test
    public void rejectsCenterEmptyAndInvalidFaces() {
        Map<String, TileEntity> tiles = new HashMap<>();
        Bus bus = new Bus();
        tiles.put("0,0,0", bus);
        bus.center = true;
        assertNull(QuantumBusTarget.resolve(world(tiles), 0, 0, 0, 1));
        bus.center = false;
        bus.empty = true;
        assertNull(QuantumBusTarget.resolve(world(tiles), 0, 0, 0, 1));
        for (int invalid : new int[] { -1, 6, 47 }) {
            assertNull(QuantumBusTarget.resolve(world(tiles), 0, 0, 0, invalid));
        }
    }
}
