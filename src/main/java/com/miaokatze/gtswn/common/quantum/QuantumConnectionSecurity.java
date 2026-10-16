package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;

/** Limits the administrator's safety override to connections owned by actual GTSWN quantum hosts. */
public final class QuantumConnectionSecurity {

    private static final Set<IGridConnection> BYPASSED = Collections.newSetFromMap(new WeakHashMap<>());

    private QuantumConnectionSecurity() {}

    public static boolean bypassesNativeCheck(IGridNode first, IGridNode second) {
        if (!isQuantumHost(first) && !isQuantumHost(second)) return false;
        return !Config.quantumSafetyEnabled || localOverride(first, second, new Context());
    }

    public static void clearConnections() {
        BYPASSED.clear();
    }

    private static boolean isQuantumHost(IGridNode node) {
        if (node == null) return false;
        IGridHost host = node.getMachine();
        return host instanceof TileEntityNetworkQuantumNode
            || host instanceof QuantumNodeBridgeRelay && ((QuantumNodeBridgeRelay) host).isOwned()
            || QuantumIncorporationRegistry.isBillingHost(host);
    }

    public static boolean permitsNativeConnection(IGridNode first, IGridNode second) {
        if (bypassesNativeCheck(first, second)) return true;
        for (IGridNode endpoint : new IGridNode[] { first, second }) {
            if (endpoint == null) continue;
            IGridHost host = endpoint.getMachine();
            if (host instanceof TileEntityNetworkQuantumNode
                && !((TileEntityNetworkQuantumNode) host).isNativeConnectionAuthorized(first, second)) return false;
            if (host instanceof QuantumNodeBridgeRelay) {
                QuantumNodeBridgeRelay relay = (QuantumNodeBridgeRelay) host;
                if (!relay.isOwned() || !relay.owner()
                    .isNativeConnectionAuthorized(
                        relay.owner()
                            .getCoreGridNode(),
                        endpoint == first ? second : first))
                    return false;
            }
            if (!QuantumIncorporationRegistry.permitsBillingConnection(host, first, second)) return false;
        }
        return true;
    }

    /** Native provider configuration remains authoritative even while its powered cache is booting. */
    public static boolean mayBridge(World anchorWorld, int x, int y, int z, IGridNode first, IGridNode second,
        IntSupplier actor) {
        if (!Config.quantumSafetyEnabled) return true;
        Context context = new Context();
        if (localOverride(first, second, context)) return true;
        Set<ISecurityProvider> providers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IGrid> grids = Collections.newSetFromMap(new IdentityHashMap<>());
        collectProviders(first, grids, providers, context);
        collectProviders(second, grids, providers, context);
        Scope anchor = new Scope(anchorWorld, anchorWorld == null ? 0 : anchorWorld.provider.dimensionId, x, y, z);
        collectAnchorProviders(anchor, grids, providers, context);
        boolean required;
        long checkedRevision;
        int checkedGridCount;
        do {
            checkedRevision = context.nativeRevision;
            checkedGridCount = grids.size();
            required = anchorWorld == null || context.safetyEnabled(anchor) || !providers.isEmpty();
            for (Scope scope : context.scopes(grids)) {
                if (scope.world == null) return false;
                if (!context.safetyEnabled(scope)) continue;
                required = true;
                Set<ISecurityProvider> known = Collections.newSetFromMap(new IdentityHashMap<>());
                collectAnchorProviders(scope, Collections.newSetFromMap(new IdentityHashMap<>()), known, context);
                if (known.isEmpty() && !sameAnchor(scope, anchorWorld, x, y, z)) return false;
                collectAnchorProviders(scope, grids, providers, context);
            }
            // A normal anchor lookup can merge/split grids or replace a provider while checking a scope.
            // Revisit the current scopes as well as rebuilding the provider inventory before authorizing.
        } while (checkedRevision != context.nativeRevision || checkedGridCount != grids.size());
        if (!required) return true;
        if (providers.size() != 1) return false;
        return permitsProvider(
            providers.iterator()
                .next(),
            actor.getAsInt());
    }

    private static void collectProviders(IGridNode node, Set<IGrid> grids, Set<ISecurityProvider> providers,
        Context context) {
        context.collect(node, grids, providers);
    }

    static boolean permitsProvider(ISecurityProvider provider, int actor) {
        if (provider == null || actor < 0 || provider.getOwner() < 0) return false;
        HashMap<Integer, EnumSet<SecurityPermissions>> permissions = new HashMap<>();
        provider.readPermissions(permissions);
        EnumSet<SecurityPermissions> grants = permissions.get(actor);
        if (grants == null) grants = permissions.get(-1);
        return grants != null && grants.contains(SecurityPermissions.BUILD);
    }

    public static void recordConnection(IGridConnection connection) {
        recordActual(connection, bypassesNativeCheck(connection.a(), connection.b()));
    }

    public static void recordActual(IGridConnection connection, boolean bypassed) {
        if (!bypassed) return;
        // The verdict belongs to the pre-merge constructor invocation. Only recheck immutable host
        // types here: grid membership and security enrollment can change during mergeGrids.
        for (IGridNode endpoint : new IGridNode[] { connection.a(), connection.b() }) {
            IGridHost host = endpoint.getMachine();
            if (host instanceof TileEntityNetworkQuantumNode || host instanceof QuantumNodeBridgeRelay
                || QuantumIncorporationRegistry.isBillingHost(host)) {
                BYPASSED.add(connection);
                return;
            }
        }
    }

    /** Destroy every live edge admitted under the override before native authorization resumes. */
    public static void restoreNativeChecks() {
        if (!Config.quantumSafetyEnabled || BYPASSED.isEmpty()) return;
        Set<TileEntityNetworkQuantumNode> refresh = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayList<IGridConnection> previous = new ArrayList<>();
        for (IGridConnection edge : new ArrayList<>(BYPASSED)) {
            if (!isLive(edge)) {
                BYPASSED.remove(edge);
                continue;
            }
            if (!bypassesNativeCheck(edge.a(), edge.b())) {
                previous.add(edge);
                BYPASSED.remove(edge);
            }
        }
        for (IGridConnection edge : previous) {
            for (IGridNode endpoint : new IGridNode[] { edge.a(), edge.b() }) {
                if (endpoint != null && endpoint.getMachine() instanceof TileEntityNetworkQuantumNode) {
                    TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) endpoint.getMachine();
                    if (node.isNativeConnectionAuthorized(edge.a(), edge.b())) refresh.add(node);
                }
            }
        }
        for (IGridConnection edge : previous) {
            if (isLive(edge)) edge.destroy();
        }
        for (TileEntityNetworkQuantumNode node : refresh) {
            IGridNode core = node.getCoreGridNode();
            if (core != null) core.updateState();
        }
    }

    private static boolean isLive(IGridConnection edge) {
        return edge.a()
            .getConnections()
            .contains(edge)
            || edge.b()
                .getConnections()
                .contains(edge);
    }

    public static boolean bypassesNetworkSafety(ItemStack terminal, IGridNode other) {
        if (!Config.quantumSafetyEnabled) return true;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(terminal);
        if (anchor == null) return false;
        Scope scope = new Scope(DimensionManager.getWorld(anchor[0]), anchor[0], anchor[1], anchor[2], anchor[3]);
        Context context = new Context();
        return allowsLocalScope(scope, context.anchorNode(scope), other, context);
    }

    static boolean bypassesNetworkSafety(World world, int x, int y, int z, IGridNode first, IGridNode second) {
        return !Config.quantumSafetyEnabled
            || allowsLocalScope(new Scope(world, world.provider.dimensionId, x, y, z), first, second, new Context());
    }

    private static boolean localOverride(IGridNode first, IGridNode second, Context context) {
        Scope scope = first == null ? null : scope(first.getMachine());
        if (scope != null && allowsLocalScope(scope, first, second, context)) return true;
        scope = second == null ? null : scope(second.getMachine());
        return scope != null && allowsLocalScope(scope, first, second, context);
    }

    private static boolean allowsLocalScope(Scope own, IGridNode first, IGridNode second, Context context) {
        if (own.world == null || QuantumTerminalAccess.networkSafetyIdentity(own.world, own.x, own.y, own.z)
            .isEmpty() || context.safetyEnabled(own)) return false;
        Set<IGrid> grids = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ISecurityProvider> providers = Collections.newSetFromMap(new IdentityHashMap<>());
        collectProviders(first, grids, providers, context);
        collectProviders(second, grids, providers, context);
        collectAnchorProviders(own, grids, providers, context);
        Set<ISecurityProvider> ownProviders = Collections.newSetFromMap(new IdentityHashMap<>());
        collectAnchorProviders(own, Collections.newSetFromMap(new IdentityHashMap<>()), ownProviders, context);
        // OFF exempts this anchor's providers; it cannot grant entry into a separate protected AE grid.
        if (!ownProviders.containsAll(providers)) return false;
        for (Scope other : context.scopes(grids)) {
            if (own.sameNetwork(other)) continue;
            if (other.world == null || context.safetyEnabled(other)) return false;
        }
        return !QuantumTerminalAccess.networkSafetyIdentity(own.world, own.x, own.y, own.z)
            .isEmpty() && !context.safetyEnabled(own);
    }

    private static void collectAnchorProviders(Scope scope, Set<IGrid> grids, Set<ISecurityProvider> providers,
        Context context) {
        context.collectAnchor(scope, grids, providers);
    }

    private static Scope scope(IGridHost host) {
        TileEntityNetworkQuantumNode node = host instanceof TileEntityNetworkQuantumNode
            ? (TileEntityNetworkQuantumNode) host
            : host instanceof QuantumNodeBridgeRelay ? ((QuantumNodeBridgeRelay) host).owner() : null;
        if (node != null && node.hasAnchor()) return new Scope(
            DimensionManager.getWorld(node.getAnchorDim()),
            node.getAnchorDim(),
            node.getAnchorX(),
            node.getAnchorY(),
            node.getAnchorZ());
        AnchorKey anchor = QuantumIncorporationRegistry.billingAnchor(host);
        if (anchor != null) return new Scope(
            DimensionManager.getWorld(anchor.getDimension()),
            anchor.getDimension(),
            anchor.getX(),
            anchor.getY(),
            anchor.getZ());
        Object machine = host;
        if (machine instanceof TileController) {
            TileEntity tile = (TileEntity) machine;
            World world = tile.getWorldObj();
            if (world != null && QuantumControllerRegistry.get(world)
                .isQuantized(tile.xCoord, tile.yCoord, tile.zCoord))
                return new Scope(world, world.provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord);
        }
        return null;
    }

    private static boolean sameAnchor(Scope scope, World world, int x, int y, int z) {
        return world != null && scope.dimension == world.provider.dimensionId
            && scope.members.contains(QuantumControllerRegistry.pack(x, y, z));
    }

    /** One synchronous authorization only. Never retain a policy, provider inventory or verdict across calls. */
    private static final class Context {

        private final Map<IGrid, Set<ISecurityProvider>> providersByGrid = new IdentityHashMap<>();
        private final Map<IGrid, Map<ScopeKey, Scope>> scopesByGrid = new IdentityHashMap<>();
        private final Map<World, Map<Set<Long>, Boolean>> policies = new IdentityHashMap<>();
        private final Map<ScopeKey, AnchorObservation> anchors = new HashMap<>();
        private final Map<Set<ISecurityProvider>, Inventory> inventories = new IdentityHashMap<>();
        private long policyEpoch = Long.MIN_VALUE;
        private long nativeRevision;

        void collect(IGridNode node, Set<IGrid> grids, Set<ISecurityProvider> providers) {
            Inventory inventory = inventories.computeIfAbsent(providers, ignored -> new Inventory(grids, providers));
            if (node != null && inventory.sources.add(node)) collectSource(node, inventory);
        }

        void collectAnchor(Scope scope, Set<IGrid> grids, Set<ISecurityProvider> providers) {
            anchorNode(scope);
            ScopeKey key = new ScopeKey(scope, null);
            Inventory inventory = inventories.computeIfAbsent(providers, ignored -> new Inventory(grids, providers));
            inventory.anchors.add(key);
            collectSource(anchorSource(key), inventory);
        }

        private IGridNode anchorSource(ScopeKey key) {
            AnchorObservation known = anchors.get(key);
            int x = QuantumControllerRegistry.unpackX(key.position);
            int y = QuantumControllerRegistry.unpackY(key.position);
            int z = QuantumControllerRegistry.unpackZ(key.position);
            if (known == null || known.tile.isInvalid()
                || key.world == null
                || !key.world.blockExists(x, y, z)
                || key.world.getTileEntity(x, y, z) != known.tile) return null;
            return known.node;
        }

        private void collectSource(IGridNode node, Inventory inventory) {
            if (node == null) return;
            // A pending provider has an authoritative host and ACL even before its grid exists.
            if (node.getMachine() instanceof ISecurityProvider)
                inventory.providers.add((ISecurityProvider) node.getMachine());
            IGrid grid = node.getGrid();
            if (grid != null && inventory.grids.add(grid)) inventory.providers.addAll(providers(grid));
        }

        boolean safetyEnabled(Scope scope) {
            if (scope.world == null) return false;
            long currentEpoch = QuantumPolicyEpoch.current();
            if (policyEpoch != currentEpoch) {
                policies.clear();
                policyEpoch = currentEpoch;
            }
            Set<Long> members = QuantumTerminalAccess.networkMembers(scope.world, scope.x, scope.y, scope.z);
            Map<Set<Long>, Boolean> worldPolicies = policies
                .computeIfAbsent(scope.world, ignored -> new IdentityHashMap<>());
            Boolean enabled = worldPolicies.get(members);
            if (enabled == null) {
                enabled = QuantumNetworkSecurityRegistry.get(scope.world)
                    .isSafetyEnabled(scope.world, members);
                // Cold synchronization may assign identities or propagate the component's explicit policy.
                long synchronizedEpoch = QuantumPolicyEpoch.current();
                if (policyEpoch != synchronizedEpoch) {
                    policies.clear();
                    policyEpoch = synchronizedEpoch;
                    worldPolicies = policies.computeIfAbsent(scope.world, ignored -> new IdentityHashMap<>());
                }
                worldPolicies.put(members, enabled);
            }
            return enabled;
        }

        Set<ISecurityProvider> providers(IGrid grid) {
            Set<ISecurityProvider> found = providersByGrid.get(grid);
            if (found != null) return found;
            found = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
                if (!ISecurityProvider.class.isAssignableFrom(type)) continue;
                for (IGridNode node : grid.getMachines(type)) {
                    IGridHost machine = node.getMachine();
                    if (machine instanceof ISecurityProvider) found.add((ISecurityProvider) machine);
                }
            }
            providersByGrid.put(grid, found);
            return found;
        }

        Iterable<Scope> scopes(Set<IGrid> grids) {
            Map<ScopeKey, Scope> found = new LinkedHashMap<>();
            for (IGrid grid : grids) {
                Map<ScopeKey, Scope> cached = scopesByGrid.get(grid);
                if (cached == null) {
                    cached = new LinkedHashMap<>();
                    for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
                        if (!TileController.class.isAssignableFrom(type)
                            && !TileEntityNetworkQuantumNode.class.isAssignableFrom(type)
                            && !QuantumNodeBridgeRelay.class.isAssignableFrom(type)
                            && !QuantumIncorporationRegistry.isBillingClass(type)) continue;
                        for (IGridNode node : grid.getMachines(type)) {
                            IGridHost host = node.getMachine();
                            Scope scope = scope(host);
                            if (scope == null) continue;
                            // The machine-set node already exists. Never resolve a remote anchor to build a key.
                            Object machine = host;
                            IGrid actual = machine instanceof TileController && scope.world != null
                                && scope.world.blockExists(scope.x, scope.y, scope.z)
                                && scope.world.getTileEntity(scope.x, scope.y, scope.z) == host ? node.getGrid() : null;
                            cached.putIfAbsent(new ScopeKey(scope, actual), scope);
                        }
                    }
                    scopesByGrid.put(grid, cached);
                }
                for (Map.Entry<ScopeKey, Scope> entry : cached.entrySet())
                    found.putIfAbsent(entry.getKey(), entry.getValue());
            }
            return found.values();
        }

        IGridNode anchorNode(Scope scope) {
            if (scope.world == null) return null;
            ScopeKey key = new ScopeKey(scope, null);
            if (!scope.world.blockExists(scope.x, scope.y, scope.z)) {
                if (anchors.remove(key) != null) clearSnapshots();
                return null;
            }
            TileEntity tile = scope.world.getTileEntity(scope.x, scope.y, scope.z);
            if (!(tile instanceof IGridHost) || tile.isInvalid()) {
                if (anchors.remove(key) != null) clearSnapshots();
                return null;
            }
            AnchorObservation known = anchors.get(key);
            IGrid currentGrid = known == null || known.node == null ? null : known.node.getGrid();
            if (known != null && known.tile == tile) {
                if (known.grid != currentGrid) {
                    clearSnapshots();
                    known.grid = currentGrid;
                }
                return known.node;
            }
            // This normal lookup may lazily create a node and merge native grids. Do not reuse earlier inventories.
            discardSnapshots();
            IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            // Booting null results are stable until a natural tick/readiness callback, not advanced by this query.
            anchors.put(key, new AnchorObservation(tile, node));
            clearSnapshots();
            return anchorSource(key);
        }

        private void clearSnapshots() {
            discardSnapshots();
            // Every accumulator must reflect the same current inventories. A union would retain revoked providers.
            for (Inventory inventory : inventories.values()) {
                inventory.grids.clear();
                inventory.providers.clear();
                for (IGridNode source : inventory.sources) collectSource(source, inventory);
                for (ScopeKey anchor : inventory.anchors) collectSource(anchorSource(anchor), inventory);
            }
        }

        private void discardSnapshots() {
            nativeRevision++;
            providersByGrid.clear();
            scopesByGrid.clear();
            policies.clear();
        }
    }

    private static final class Inventory {

        final Set<IGrid> grids;
        final Set<ISecurityProvider> providers;
        final Set<IGridNode> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ScopeKey> anchors = new HashSet<>();

        Inventory(Set<IGrid> grids, Set<ISecurityProvider> providers) {
            this.grids = grids;
            this.providers = providers;
        }
    }

    private static final class AnchorObservation {

        final TileEntity tile;
        final IGridNode node;
        IGrid grid;

        AnchorObservation(TileEntity tile, IGridNode node) {
            this.tile = tile;
            this.node = node;
            grid = node == null ? null : node.getGrid();
        }
    }

    /** Loaded controllers may share a component only inside the same actual native grid. */
    private static final class ScopeKey {

        final World world;
        final int dimension;
        final long position;
        final Set<Long> members;
        final IGrid grid;

        ScopeKey(Scope scope, IGrid grid) {
            world = scope.world;
            dimension = scope.dimension;
            position = grid == null ? QuantumControllerRegistry.pack(scope.x, scope.y, scope.z) : 0L;
            members = grid == null ? null : scope.members;
            this.grid = grid;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(world) * 31 + dimension;
            hash = hash * 31 + System.identityHashCode(members);
            hash = hash * 31 + System.identityHashCode(grid);
            return hash * 31 + Long.hashCode(position);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ScopeKey)) return false;
            ScopeKey key = (ScopeKey) other;
            return world == key.world && dimension == key.dimension
                && position == key.position
                && members == key.members
                && grid == key.grid;
        }
    }

    private static final class Scope {

        final World world;
        final int dimension, x, y, z;
        final Set<Long> members;

        Scope(World world, int dimension, int x, int y, int z) {
            this.world = world;
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            members = world == null ? Collections.singleton(QuantumControllerRegistry.pack(x, y, z))
                : QuantumTerminalAccess.networkMembers(world, x, y, z);
        }

        boolean sameNetwork(Scope other) {
            return dimension == other.dimension && !Collections.disjoint(members, other.members);
        }
    }
}
