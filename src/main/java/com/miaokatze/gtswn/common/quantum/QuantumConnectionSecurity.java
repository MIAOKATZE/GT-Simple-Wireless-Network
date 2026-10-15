package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
        return !Config.quantumSafetyEnabled || localOverride(first, second);
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
        if (localOverride(first, second)) return true;
        Set<ISecurityProvider> providers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IGrid> grids = Collections.newSetFromMap(new IdentityHashMap<>());
        collectProviders(first, grids, providers);
        collectProviders(second, grids, providers);
        if (anchorWorld != null && anchorWorld.blockExists(x, y, z)) {
            TileEntity anchor = anchorWorld.getTileEntity(x, y, z);
            if (anchor instanceof IGridHost)
                collectProviders(((IGridHost) anchor).getGridNode(ForgeDirection.UNKNOWN), grids, providers);
        }
        boolean required = anchorWorld == null || !QuantumTerminalAccess.isNetworkSafetyEnabled(anchorWorld, x, y, z)
            || QuantumTerminalAccess.isSecurityRequired(anchorWorld, x, y, z);
        if (!required && anchorWorld != null) {
            QuantumNetworkSecurityRegistry history = QuantumNetworkSecurityRegistry.providerRegistry(anchorWorld);
            for (ISecurityProvider provider : providers)
                if (history.isProviderEnrolled(provider.getSecurityKey())) required = true;
        }
        if (!required) return true;
        for (Scope scope : scopes(grids)) {
            if (scope.world == null) return false;
            if (!QuantumTerminalAccess.isSecurityRequired(scope.world, scope.x, scope.y, scope.z)) continue;
            Set<ISecurityProvider> known = Collections.newSetFromMap(new IdentityHashMap<>());
            collectAnchorProviders(scope, Collections.newSetFromMap(new IdentityHashMap<>()), known);
            if (known.isEmpty() && !sameAnchor(scope, anchorWorld, x, y, z)) return false;
        }
        if (providers.size() != 1) return false;
        return permitsProvider(
            providers.iterator()
                .next(),
            actor.getAsInt());
    }

    private static void collectProviders(IGridNode node, Set<IGrid> grids, Set<ISecurityProvider> providers) {
        // A provider's first FindConnections runs before GridNode.updateState creates its grid.
        // Its real host and persisted ACL are already authoritative at this point; omitting it
        // prevents the physical link needed to obtain power from the quantum anchor.
        if (node != null && node.getMachine() instanceof ISecurityProvider)
            providers.add((ISecurityProvider) node.getMachine());
        IGrid grid = node == null ? null : node.getGrid();
        if (grid == null || !grids.add(grid)) return;
        for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!ISecurityProvider.class.isAssignableFrom(type)) continue;
            for (IGridNode providerNode : grid.getMachines(type)) {
                IGridHost machine = providerNode.getMachine();
                if (machine instanceof ISecurityProvider) providers.add((ISecurityProvider) machine);
            }
        }
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
            if (edge.a()
                .getConnections()
                .contains(edge)
                || edge.b()
                    .getConnections()
                    .contains(edge))
                edge.destroy();
        }
        for (TileEntityNetworkQuantumNode node : refresh) {
            IGridNode core = node.getCoreGridNode();
            if (core != null) core.updateState();
        }
    }

    public static boolean bypassesNetworkSafety(ItemStack terminal, IGridNode other) {
        if (!Config.quantumSafetyEnabled) return true;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(terminal);
        if (anchor == null) return false;
        Scope scope = new Scope(DimensionManager.getWorld(anchor[0]), anchor[0], anchor[1], anchor[2], anchor[3]);
        return allowsLocalScope(scope, anchorNode(scope), other);
    }

    static boolean bypassesNetworkSafety(World world, int x, int y, int z, IGridNode first, IGridNode second) {
        return !Config.quantumSafetyEnabled
            || allowsLocalScope(new Scope(world, world.provider.dimensionId, x, y, z), first, second);
    }

    private static boolean localOverride(IGridNode first, IGridNode second) {
        Scope scope = first == null ? null : scope(first.getMachine());
        if (scope != null && allowsLocalScope(scope, first, second)) return true;
        scope = second == null ? null : scope(second.getMachine());
        return scope != null && allowsLocalScope(scope, first, second);
    }

    private static boolean allowsLocalScope(Scope own, IGridNode first, IGridNode second) {
        if (own.world == null || QuantumTerminalAccess.networkSafetyIdentity(own.world, own.x, own.y, own.z)
            .isEmpty() || QuantumTerminalAccess.isNetworkSafetyEnabled(own.world, own.x, own.y, own.z)) return false;
        Set<Long> trusted = QuantumNetworkSecurityRegistry.get(own.world)
            .trustedProviderKeys(own.world, own.members);
        if (trusted.isEmpty()) return false;
        Set<IGrid> grids = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<ISecurityProvider> providers = Collections.newSetFromMap(new IdentityHashMap<>());
        collectProviders(first, grids, providers);
        collectProviders(second, grids, providers);
        collectAnchorProviders(own, grids, providers);
        if (providers.size() > 1) return false;
        for (ISecurityProvider provider : providers) if (!trusted.contains(provider.getSecurityKey())) return false;
        for (Scope other : scopes(grids)) {
            if (own.sameNetwork(other)) continue;
            if (other.world == null || QuantumTerminalAccess.isSecurityRequired(other.world, other.x, other.y, other.z))
                return false;
        }
        return true;
    }

    private static void collectAnchorProviders(Scope scope, Set<IGrid> grids, Set<ISecurityProvider> providers) {
        collectProviders(anchorNode(scope), grids, providers);
    }

    private static IGridNode anchorNode(Scope scope) {
        if (scope.world == null || !scope.world.blockExists(scope.x, scope.y, scope.z)) return null;
        TileEntity tile = scope.world.getTileEntity(scope.x, scope.y, scope.z);
        return tile instanceof IGridHost ? ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN) : null;
    }

    private static Set<Scope> scopes(Set<IGrid> grids) {
        Set<Scope> found = new HashSet<>();
        for (IGrid grid : grids) for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!TileController.class.isAssignableFrom(type)
                && !TileEntityNetworkQuantumNode.class.isAssignableFrom(type)
                && !QuantumNodeBridgeRelay.class.isAssignableFrom(type)
                && !QuantumIncorporationRegistry.isBillingClass(type)) continue;
            for (IGridNode node : grid.getMachines(type)) {
                Scope scope = scope(node.getMachine());
                if (scope != null) found.add(scope);
            }
        }
        return found;
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
