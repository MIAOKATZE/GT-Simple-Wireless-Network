package com.miaokatze.gtswn.common.quantum;

import java.util.concurrent.atomic.AtomicLong;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;

/** Native membership/security events only; this never resolves a node or retains an authorization. */
public final class QuantumSecurityTopology {

    /** Implemented only by grids whose add/remove and security events emit this revision. */
    public interface TrackedGrid {
    }

    private static final AtomicLong REVISION = new AtomicLong();

    private QuantumSecurityTopology() {}

    public static long current() {
        return REVISION.get();
    }

    public static void changed() {
        REVISION.incrementAndGet();
    }

    /** Ordinary machines cannot change the quantum scopes or native provider inventory. */
    public static void membershipChanged(IGridNode node) {
        if (node == null) return;
        IGridHost host = node.getMachine();
        Object machine = host;
        if (machine instanceof TileController || host instanceof TileEntityNetworkQuantumNode
            || host instanceof QuantumNodeBridgeRelay
            || host instanceof ISecurityProvider
            || QuantumIncorporationRegistry.isBillingHost(host)) changed();
    }
}
