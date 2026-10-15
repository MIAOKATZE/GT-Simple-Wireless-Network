package com.miaokatze.gtswn.common.quantum;

import java.util.EnumSet;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridNotification;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;

/** A runtime bridge endpoint represents the quantum node's actor without changing the controller's owner. */
public final class QuantumNodeBridgeRelay implements IGridBlock, IGridHost {

    private final TileEntityNetworkQuantumNode owner;
    private final DimensionalCoord location;
    private IGridNode node;

    public QuantumNodeBridgeRelay(TileEntityNetworkQuantumNode owner, DimensionalCoord location) {
        this.owner = owner;
        this.location = location;
    }

    public void attach(IGridNode node) {
        this.node = node;
    }

    public TileEntityNetworkQuantumNode owner() {
        return owner;
    }

    public boolean isOwned() {
        return node != null && owner.ownsBridgeRelay(node);
    }

    @Override
    public double getIdlePowerUsage() {
        return 0;
    }

    @Override
    public EnumSet<GridFlags> getFlags() {
        return EnumSet.of(GridFlags.DENSE_CAPACITY);
    }

    @Override
    public boolean isWorldAccessible() {
        return false;
    }

    @Override
    public DimensionalCoord getLocation() {
        return location;
    }

    @Override
    public AEColor getGridColor() {
        return AEColor.Transparent;
    }

    @Override
    public void onGridNotification(GridNotification notification) {}

    @Override
    public void setNetworkStatus(IGrid grid, int channelsInUse) {}

    @Override
    public EnumSet<ForgeDirection> getConnectableSides() {
        return EnumSet.noneOf(ForgeDirection.class);
    }

    @Override
    public IGridHost getMachine() {
        return this;
    }

    @Override
    public void gridChanged() {}

    @Override
    public ItemStack getMachineRepresentation() {
        return null;
    }

    @Override
    public IGridNode getGridNode(ForgeDirection direction) {
        return node;
    }

    @Override
    public AECableType getCableConnectionType(ForgeDirection direction) {
        return AECableType.NONE;
    }

    @Override
    public void securityBreak() {
        owner.bridgeSecurityBreak(node);
        node = null;
    }
}
