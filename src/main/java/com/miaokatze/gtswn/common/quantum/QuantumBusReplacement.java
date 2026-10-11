package com.miaokatze.gtswn.common.quantum;

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
}
