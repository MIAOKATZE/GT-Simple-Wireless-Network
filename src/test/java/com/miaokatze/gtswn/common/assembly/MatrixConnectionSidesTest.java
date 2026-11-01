package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.util.EnumSet;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

public class MatrixConnectionSidesTest {

    @Test
    public void anUnverifiedMemberCannotBridgeNeighbouringNetworksThroughItsSides() {
        assertEquals(EnumSet.of(ForgeDirection.DOWN), MatrixAssemblyPort.connectionSides(ForgeDirection.DOWN, 0));
        assertFalse(
            MatrixAssemblyPort.connectionSides(ForgeDirection.DOWN, 0)
                .contains(ForgeDirection.EAST));
        assertFalse(
            MatrixAssemblyPort.connectionSides(ForgeDirection.DOWN, 0)
                .contains(ForgeDirection.NORTH));
    }

    @Test
    public void onlyVerifiedEdgesExtendTheExternalPortInEveryOrientation() {
        assertEquals(
            EnumSet.of(ForgeDirection.DOWN, ForgeDirection.WEST, ForgeDirection.EAST, ForgeDirection.NORTH),
            MatrixAssemblyPort.connectionSides(
                ForgeDirection.DOWN,
                ForgeDirection.WEST.flag | ForgeDirection.EAST.flag | ForgeDirection.NORTH.flag));
        assertEquals(
            EnumSet.of(ForgeDirection.EAST, ForgeDirection.UP, ForgeDirection.DOWN),
            MatrixAssemblyPort.connectionSides(ForgeDirection.EAST, ForgeDirection.UP.flag | ForgeDirection.DOWN.flag));
        assertFalse(
            MatrixAssemblyPort.connectionSides(ForgeDirection.EAST, 0)
                .contains(ForgeDirection.WEST));
    }
}
