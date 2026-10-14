package com.miaokatze.gtswn.common.quantum;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.mixins.ae2.TileCableBusAccessor;

import appeng.api.parts.IPart;
import appeng.parts.CableBusContainer;
import appeng.tile.networking.TileCableBus;

/** A block replacement transaction that retains the original AE2 container and all its parts. */
public final class QuantumBusReplacement {

    private QuantumBusReplacement() {}

    public interface Placement {

        boolean install();

        void restore();
    }

    public static boolean replace(TileCableBus original, TileEntityNetworkQuantumNode node, Placement placement) {
        CableBusContainer container = original.getCableBus();
        if (container.isEmpty() || container.getPart(ForgeDirection.UNKNOWN) != null) {
            return false;
        }
        // Check the rollback capability before detaching anything.
        TileCableBusAccessor restorer = (TileCableBusAccessor) (Object) original;
        boolean committed = false;
        try {
            node.adoptPartContainer(container);
            committed = placement.install();
            return committed;
        } finally {
            if (!committed) {
                try {
                    // setHost clears the previous host before rebinding. Assign cb only afterwards.
                    container.setHost(original);
                    for (ForgeDirection side : ForgeDirection.values()) {
                        IPart part = container.getPart(side);
                        if (part != null) {
                            part.setPartHostInfo(side, container, original);
                        }
                    }
                } finally {
                    restorer.gtswn$setCableBus(container);
                    placement.restore();
                }
            }
        }
    }

    /** Remove only the quantum core; the original parts stay live in a native AE2 host. */
    public static boolean removeCore(TileEntityNetworkQuantumNode original, TileCableBus bus, Placement placement) {
        CableBusContainer container = original.getPartContainer();
        if (!original.hasParts() || (container.getPart(ForgeDirection.UNKNOWN) != null
            && !(container.getPart(ForgeDirection.UNKNOWN) instanceof QuantumNodeCablePart))) {
            return false;
        }
        TileCableBusAccessor target = (TileCableBusAccessor) (Object) bus;
        NBTTagCompound proxyState = original.snapshotProxyForReplacement();
        boolean committed = false;
        try {
            try {
                container.setHost(bus);
                for (ForgeDirection side : ForgeDirection.values()) {
                    IPart part = container.getPart(side);
                    if (part != null) {
                        part.setPartHostInfo(side, container, bus);
                    }
                }
            } finally {
                target.gtswn$setCableBus(container);
            }
            committed = placement.install();
            return committed;
        } finally {
            if (!committed) {
                try {
                    original.adoptPartContainer(container);
                } finally {
                    original.restoreProxyAfterReplacement(proxyState);
                    placement.restore();
                }
            }
        }
    }
}
