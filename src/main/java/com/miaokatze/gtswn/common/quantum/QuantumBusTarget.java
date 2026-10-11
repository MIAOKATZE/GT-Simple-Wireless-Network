package com.miaokatze.gtswn.common.quantum;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.tile.networking.TileCableBus;
import appeng.tile.networking.TileController;

/** Mirrors AE2 placement: try the clicked host first, then the adjacent host on the clicked face. */
public final class QuantumBusTarget {

    public final int x;
    public final int y;
    public final int z;
    public final TileCableBus bus;

    private QuantumBusTarget(int x, int y, int z, TileCableBus bus) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.bus = bus;
    }

    public static QuantumBusTarget resolve(IBlockAccess world, int x, int y, int z, int side) {
        if (side < 0 || side >= ForgeDirection.VALID_DIRECTIONS.length) {
            return null;
        }
        TileEntity clicked = world.getTileEntity(x, y, z);
        if (!(clicked instanceof TileCableBus) && clicked instanceof TileController) {
            return null;
        }
        QuantumBusTarget target = at(clicked, x, y, z);
        if (target != null) {
            return target;
        }
        ForgeDirection face = ForgeDirection.getOrientation(side);
        int nx = x + face.offsetX;
        int ny = y + face.offsetY;
        int nz = z + face.offsetZ;
        return at(world.getTileEntity(nx, ny, nz), nx, ny, nz);
    }

    private static QuantumBusTarget at(TileEntity tile, int x, int y, int z) {
        if (!(tile instanceof TileCableBus)) {
            return null;
        }
        TileCableBus bus = (TileCableBus) tile;
        if (bus.getPart(ForgeDirection.UNKNOWN) != null || bus.isEmpty()) {
            return null;
        }
        return new QuantumBusTarget(x, y, z, bus);
    }
}
