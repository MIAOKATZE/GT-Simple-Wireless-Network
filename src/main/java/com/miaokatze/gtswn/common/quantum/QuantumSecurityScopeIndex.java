package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.tile.networking.TileController;

/** Per-native-grid source facts, updated at membership boundaries. Never retains a world or permission verdict. */
public final class QuantumSecurityScopeIndex {

    private static final Map<TileEntityNetworkQuantumNode, Map<QuantumSecurityScopeIndex, Boolean>> OWNERS = new WeakHashMap<>();

    /** Existing live membership index only. Server main-thread iteration must be bounded by its caller. */
    public static Iterable<TileEntityNetworkQuantumNode> loadedOwners() {
        return Collections.unmodifiableSet(OWNERS.keySet());
    }

    private final Map<IGridNode, AnchorKey> nodes = new IdentityHashMap<>();
    private final Map<AnchorKey, Integer> anchors = new HashMap<>();
    private final Set<IGridNode> controllers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<TileEntityNetworkQuantumNode, Set<IGridNode>> owners = new IdentityHashMap<>();

    public void add(IGridNode node) {
        IGridHost host = node.getMachine();
        if ((Object) host instanceof TileController) controllers.add(node);
        TileEntityNetworkQuantumNode owner = owner(host);
        if (owner != null) {
            owners.computeIfAbsent(owner, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(node);
            OWNERS.computeIfAbsent(owner, ignored -> new WeakHashMap<>())
                .put(this, Boolean.TRUE);
        }
        replace(node, anchor(host));
    }

    public void remove(IGridNode node) {
        controllers.remove(node);
        TileEntityNetworkQuantumNode owner = owner(node.getMachine());
        if (owner != null) {
            Set<IGridNode> entries = owners.get(owner);
            if (entries != null && entries.remove(node) && entries.isEmpty()) {
                owners.remove(owner);
                Map<QuantumSecurityScopeIndex, Boolean> indexes = OWNERS.get(owner);
                if (indexes != null) {
                    indexes.remove(this);
                    if (indexes.isEmpty()) OWNERS.remove(owner);
                }
            }
        }
        replace(node, null);
    }

    /** Binding edits also affect an owner's relay, even when its native grid differs from the core grid. */
    public static void anchorChanged(TileEntityNetworkQuantumNode owner) {
        if (owner.getWorldObj() == null || owner.getWorldObj().isRemote) return;
        Map<QuantumSecurityScopeIndex, Boolean> indexes = OWNERS.get(owner);
        if (indexes == null) return;
        for (QuantumSecurityScopeIndex index : new ArrayList<>(indexes.keySet())) {
            Set<IGridNode> entries = index.owners.get(owner);
            if (entries != null) for (IGridNode node : entries) index.replace(node, anchor(owner));
        }
        QuantumSecurityTopology.changed();
    }

    private void replace(IGridNode node, AnchorKey next) {
        AnchorKey previous = nodes.get(node);
        if (previous == null ? next == null : previous.equals(next)) return;
        if (previous != null) {
            int count = anchors.get(previous);
            if (count == 1) anchors.remove(previous);
            else anchors.put(previous, count - 1);
        }
        if (next == null) nodes.remove(node);
        else {
            nodes.put(node, next);
            anchors.merge(next, 1, Integer::sum);
        }
    }

    Iterable<AnchorKey> anchors() {
        return new ArrayList<>(anchors.keySet());
    }

    Iterable<IGridNode> controllers() {
        return new ArrayList<>(controllers);
    }

    private static TileEntityNetworkQuantumNode owner(IGridHost host) {
        return host instanceof TileEntityNetworkQuantumNode ? (TileEntityNetworkQuantumNode) host
            : host instanceof QuantumNodeBridgeRelay ? ((QuantumNodeBridgeRelay) host).owner() : null;
    }

    private static AnchorKey anchor(IGridHost host) {
        TileEntityNetworkQuantumNode owner = owner(host);
        if (owner != null) return owner.hasAnchor()
            ? new AnchorKey(owner.getAnchorDim(), owner.getAnchorX(), owner.getAnchorY(), owner.getAnchorZ())
            : null;
        // Billing bindings are immutable while live; their existing rebind path destroys the native node first.
        return QuantumIncorporationRegistry.billingAnchor(host);
    }
}
