package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes.Type;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.SavedDataUtil;
import com.miaokatze.gtswn.config.Config;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.PowerMultiplier;
import appeng.api.features.IPlayerRegistry;
import appeng.api.implementations.parts.IPartCable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridNotification;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPartHost;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.tile.networking.TileController;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Original devices remain intact; a single dense bridge also accounts for their idle surcharge. */
public class QuantumIncorporationRegistry extends WorldSavedData {

    public static final int MAX_CHANNELS = 32;

    private static final String DATA_NAME = "gtswn_quantum_incorporation";

    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, Map<Long, Entry>> chunks = new HashMap<>();
    private final Map<AnchorKey, Map<Long, Entry>> sources = new HashMap<>();
    private ArrayList<Entry> maintenanceOrder;
    private int cursor;
    private long migrationRevision = -1L;
    private static long budgetTick = Long.MIN_VALUE;
    private static int connectionsThisTick;
    /** Only live bridges, indexed across dimensions by their controller anchor. */
    private static final Map<AnchorKey, Set<Entry>> ACTIVE = new HashMap<>();

    static boolean isBillingHost(IGridHost host) {
        return host instanceof BillingLeaf;
    }

    static boolean isBillingClass(Class<? extends IGridHost> type) {
        return type == BillingLeaf.class;
    }

    static AnchorKey billingAnchor(IGridHost host) {
        return host instanceof BillingLeaf ? ((BillingLeaf) host).entry.anchorKey() : null;
    }

    static boolean permitsBillingConnection(IGridHost host, IGridNode first, IGridNode second) {
        if (!(host instanceof BillingLeaf)) return true;
        Entry entry = ((BillingLeaf) host).entry;
        return QuantumConnectionSecurity.mayBridge(
            DimensionManager.getWorld(entry.dim),
            entry.ax,
            entry.ay,
            entry.az,
            entry.local == null ? first : entry.local,
            entry.target == null ? second : entry.target,
            entry::nativeActorID);
    }

    private static void invalidateStats() {
        QuantumNetworkStatsCache.clear();
        QuantumNetworkData.clearCache();
    }

    /** Bounded by the controller structure and its connected incorporated blocks. */
    static int[] incorporationStats(int dimension, Set<Long> structure, IGrid grid,
        Set<IGridConnection> quantumConnections) {
        int count = 0, channels = 0, additionalChannels = 0;
        Set<IGridConnection> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (long position : structure) {
            Set<Entry> bridges = ACTIVE.get(
                new AnchorKey(
                    dimension,
                    QuantumControllerRegistry.unpackX(position),
                    QuantumControllerRegistry.unpackY(position),
                    QuantumControllerRegistry.unpackZ(position)));
            if (bridges == null) continue;
            for (Entry entry : bridges) {
                IGridConnection edge = entry.connection;
                if (!entry.hasLiveConnections() || entry.target.getGrid() != grid || !seen.add(edge)) continue;
                count++;
                int used = edge.getUsedChannels();
                channels += used;
                if (!quantumConnections.contains(edge)) additionalChannels += used;
            }
        }
        return new int[] { count, channels, additionalChannels };
    }

    public QuantumIncorporationRegistry(String name) {
        super(name);
    }

    public static QuantumIncorporationRegistry get(World world) {
        QuantumIncorporationRegistry data = SavedDataUtil
            .loadOrCreate(world, DATA_NAME, QuantumIncorporationRegistry.class, QuantumIncorporationRegistry::new);
        if (!world.isRemote) data.applyPendingMigrations(world, QuantumMigrationJournal.get(world));
        return data;
    }

    public static boolean isEligible(World world, int x, int y, int z) {
        if (!world.blockExists(x, y, z)) return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGridHost) || tile instanceof IPartHost || tile instanceof TileController) return false;
        // GT's base tile implements IGridHost even for machines without any ME capability.
        // Inspect the actual machine class; querying its proxy on the client can create one.
        if (tile instanceof IGregTechTileEntity
            && !(((IGregTechTileEntity) tile).getMetaTileEntity() instanceof IGridHost)) return false;
        Block block = world.getBlock(x, y, z);
        block.setBlockBoundsBasedOnState(world, x, y, z);
        // Full cubes include transparent machines; opaqueCube would reject valid AE machines.
        return block.getBlockBoundsMinX() == 0 && block.getBlockBoundsMinY() == 0
            && block.getBlockBoundsMinZ() == 0
            && block.getBlockBoundsMaxX() == 1
            && block.getBlockBoundsMaxY() == 1
            && block.getBlockBoundsMaxZ() == 1;
    }

    public static boolean isIncorporated(World world, int x, int y, int z) {
        if (world == null || world.isRemote || !world.blockExists(x, y, z)) return false;
        Entry entry = get(world).entries.get(QuantumControllerRegistry.pack(x, y, z));
        if (entry == null) return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        // A replacement must never inherit protection while waiting for the bounded maintenance sweep.
        return tile != null && !tile.isInvalid()
            && (entry.loadedTile == null || entry.loadedTile == tile)
            && entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity());
    }

    /** Whether this node belongs to a currently registered incorporated tile. */
    public static boolean blocksPhysicalConnection(IGridNode node) {
        TileEntity tile = nodeTile(node);
        if (tile == null) return false;
        World world = tile.getWorldObj();
        if (world == null || world.isRemote) return false;
        Entry entry = get(world).entries.get(QuantumControllerRegistry.pack(tile.xCoord, tile.yCoord, tile.zCoord));
        return entry != null && !tile.isInvalid()
            && (entry.loadedTile == null || entry.loadedTile == tile)
            && entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity());
    }

    /** Additional directional restriction only; native direction, color, capacity and security checks still run. */
    public static boolean blocksCableConnection(IGridNode first, IGridNode second) {
        return (isCableNode(first) && blocksPhysicalConnection(second))
            || (isCableNode(second) && blocksPhysicalConnection(first));
    }

    private static boolean isCableNode(IGridNode node) {
        if (node == null) return false;
        Object machine = node.getMachine();
        // The quantum block's virtual cable shares its TE-owned proxy and is not an ordinary ME cable.
        return machine instanceof IPartCable && !(machine instanceof QuantumNodeCablePart);
    }

    public boolean add(World world, int x, int y, int z, NBTTagCompound terminal, EntityPlayer owner) {
        if (!isEligible(world, x, y, z)) return false;
        long key = QuantumControllerRegistry.pack(x, y, z);
        if (entries.containsKey(key)) return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        Entry entry = new Entry();
        entry.loadedTile = tile;
        entry.position = key;
        entry.lastAppliedMigration = QuantumMigrationJournal.get(world)
            .currentSequence();
        entry.block = Block.blockRegistry.getNameForObject(world.getBlock(x, y, z));
        entry.tileClass = tile.getClass()
            .getName();
        entry.identity = UUID.randomUUID()
            .toString();
        entry.owner = owner.getUniqueID()
            .toString();
        entry.ownerName = owner.getCommandSenderName();
        entry.dim = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM);
        entry.ax = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X);
        entry.ay = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y);
        entry.az = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z);
        entry.anchorGeneration = terminal.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION);
        WorldServer anchorWorld = DimensionManager.getWorld(entry.dim);
        if (anchorWorld != null && !normalizeAnchor(entry, anchorWorld)) return false;
        entry.color = QuantumNetworkColor.resolve(entry.dim, entry.ax, entry.ay, entry.az);
        ((QuantumIncorporationIdentity) tile).gtswn$setIncorporationIdentity(entry.identity);
        tile.markDirty();
        entries.put(key, entry);
        index(entry);
        maintenanceOrder = null;
        markDirty();
        QuantumIncorporationVisualSync.changed(world, x, y, z);
        disconnectExternal(tile, null);
        maintain(world, entry);
        if (DebugTestGate.probesActive()) TestWorldProbes.at(world, x, y, z, Type.INCORPORATION_ADD, "");
        return true;
    }

    /** Read one live registration without scanning chunks or changing its binding. */
    public Entry find(World world, int x, int y, int z) {
        return isIncorporated(world, x, y, z) ? entries.get(QuantumControllerRegistry.pack(x, y, z)) : null;
    }

    /** Blueprint snapshot is bounded to one live registration and excludes identity/owner/runtime edges. */
    public NBTTagCompound copyBinding(World world, int x, int y, int z) {
        if (!isIncorporated(world, x, y, z)) return null;
        Entry entry = entries.get(QuantumControllerRegistry.pack(x, y, z));
        NBTTagCompound binding = new NBTTagCompound();
        binding.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, entry.dim);
        binding.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, entry.ax);
        binding.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, entry.ay);
        binding.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, entry.az);
        binding.setString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, entry.anchorGeneration);
        return binding;
    }

    /** Copying onto an existing incorporation is idempotent and never takes over its owner. */
    public boolean restoreCopiedBinding(World world, int x, int y, int z, NBTTagCompound binding, EntityPlayer placer) {
        if (world == null || world.isRemote || placer == null || !isEligible(world, x, y, z)) return false;
        if (isIncorporated(world, x, y, z)) return true;
        Entry stale = entries.get(QuantumControllerRegistry.pack(x, y, z));
        if (stale != null) forget(world, stale, false);
        if (!binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, 3)
            || !binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, 3)
            || !binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, 3)
            || !binding.hasKey(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, 3)) return false;
        return add(world, x, y, z, binding, placer);
    }

    public boolean remove(World world, int x, int y, int z, EntityPlayer player) {
        Entry entry = entries.get(QuantumControllerRegistry.pack(x, y, z));
        if (entry == null || (!player.capabilities.isCreativeMode && !entry.owner.equals(
            player.getUniqueID()
                .toString())))
            return false;
        forget(world, entry, true);
        return true;
    }

    /** Called after a real tile removal, including tools that bypass player break events. */
    public void removed(World world, TileEntity tile) {
        Entry entry = entries.get(QuantumControllerRegistry.pack(tile.xCoord, tile.yCoord, tile.zCoord));
        if (entry == null || !entry.owns(tile)) return;
        forget(world, entry, false);
        ((QuantumIncorporationIdentity) tile).gtswn$setIncorporationIdentity("");
    }

    public void unloadTile(TileEntity tile) {
        Entry entry = entries.get(QuantumControllerRegistry.pack(tile.xCoord, tile.yCoord, tile.zCoord));
        if (entry == null || !entry.owns(tile)) return;
        entry.disconnect();
        entry.loadedTile = null;
    }

    /** Controller loss disconnects devices; their own identity/owner/binding survives for coordinate recovery. */
    public void controllerRemoved(World world, int dimension, Set<Long> members) {
        controllerSuspended(dimension, members);
    }

    /** Indexed explicit disable: retain device identity, ownership and binding for later resumption. */
    public void controllerSuspended(int dimension, Set<Long> members) {
        for (long position : members) {
            Map<Long, Entry> anchored = sources.get(
                new AnchorKey(
                    dimension,
                    QuantumControllerRegistry.unpackX(position),
                    QuantumControllerRegistry.unpackY(position),
                    QuantumControllerRegistry.unpackZ(position)));
            if (anchored != null) for (Entry entry : anchored.values()) entry.disconnect();
        }
    }

    public Iterable<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    /** Bounded, source-indexed read for one authorized controller structure, without a world-wide copy. */
    public java.util.List<Entry> snapshotSource(int dimension, Set<Long> members, int limit) {
        java.util.List<Entry> result = new ArrayList<>();
        if (members == null || limit <= 0) return result;
        for (long member : new java.util.TreeSet<>(members)) {
            Map<Long, Entry> indexed = sources.get(
                new AnchorKey(
                    dimension,
                    QuantumControllerRegistry.unpackX(member),
                    QuantumControllerRegistry.unpackY(member),
                    QuantumControllerRegistry.unpackZ(member)));
            if (indexed == null) continue;
            for (Entry entry : indexed.values()) {
                result.add(entry);
                if (result.size() >= limit) return result;
            }
        }
        return result;
    }

    public Iterable<Entry> snapshotChunk(int chunkX, int chunkZ) {
        Map<Long, Entry> chunk = chunks.get(chunkKey(chunkX, chunkZ));
        return chunk == null ? Collections.emptyList() : new ArrayList<>(chunk.values());
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    private static long entryChunk(Entry entry) {
        return chunkKey(
            QuantumControllerRegistry.unpackX(entry.position) >> 4,
            QuantumControllerRegistry.unpackZ(entry.position) >> 4);
    }

    private void index(Entry entry) {
        sources.computeIfAbsent(entry.anchorKey(), ignored -> new java.util.TreeMap<>())
            .put(entry.position, entry);
        chunks.computeIfAbsent(entryChunk(entry), ignored -> new HashMap<>())
            .put(entry.position, entry);
    }

    public void sweep(World world) {
        applyPendingMigrations(world, QuantumMigrationJournal.get(world));
        for (Entry entry : snapshot()) {
            inspect(world, entry);
        }
    }

    /** Bounded round-robin inspection; sharing a two-bridge-per-tick budget across every dimension. */
    public void tick(World world) {
        if (entries.isEmpty()) return;
        applyPendingMigrations(world, QuantumMigrationJournal.get(world));
        if (maintenanceOrder == null) maintenanceOrder = new ArrayList<>(entries.values());
        ArrayList<Entry> order = maintenanceOrder;
        for (int i = 0, limit = Math.min(16, order.size()); i < limit; i++) {
            if (cursor >= order.size()) cursor = 0;
            Entry entry = order.get(cursor++);
            if (entries.get(entry.position) == entry) inspect(world, entry);
        }
    }

    private void inspect(World world, Entry entry) {
        int x = QuantumControllerRegistry.unpackX(entry.position),
            y = QuantumControllerRegistry.unpackY(entry.position),
            z = QuantumControllerRegistry.unpackZ(entry.position);
        if (!world.blockExists(x, y, z)) {
            entry.disconnect();
            entry.loadedTile = null;
            return;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null || tile.isInvalid()
            || (entry.loadedTile != null && entry.loadedTile != tile)
            || !entry.tileClass.equals(
                tile.getClass()
                    .getName())
            || !entry.block.equals(Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)))
            || !entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity())) {
            forget(world, entry, false);
            return;
        }
        entry.loadedTile = tile;
        int latestColor = entry.getColorIndex();
        if (entry.color != latestColor) {
            entry.color = latestColor;
            markDirty();
            QuantumIncorporationVisualSync.changed(world, x, y, z);
        }
        maintain(world, entry);
    }

    private void forget(World world, Entry entry, boolean restore) {
        entry.disconnect();
        entries.remove(entry.position);
        AnchorKey source = new AnchorKey(entry.dim, entry.ax, entry.ay, entry.az);
        Map<Long, Entry> indexed = sources.get(source);
        if (indexed != null) {
            indexed.remove(entry.position);
            if (indexed.isEmpty()) sources.remove(source);
        }
        long chunkKey = entryChunk(entry);
        Map<Long, Entry> chunk = chunks.get(chunkKey);
        if (chunk != null) {
            chunk.remove(entry.position);
            if (chunk.isEmpty()) chunks.remove(chunkKey);
        }
        maintenanceOrder = null;
        markDirty();
        int x = QuantumControllerRegistry.unpackX(entry.position),
            y = QuantumControllerRegistry.unpackY(entry.position),
            z = QuantumControllerRegistry.unpackZ(entry.position);
        QuantumIncorporationVisualSync.changed(world, x, y, z);
        if (DebugTestGate.probesActive()) TestWorldProbes.at(world, x, y, z, Type.INCORPORATION_REMOVE, "");
        if (!world.blockExists(x, y, z)) return;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null
            && entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity())) {
            ((QuantumIncorporationIdentity) tile).gtswn$setIncorporationIdentity("");
            tile.markDirty();
            if (restore && tile instanceof IGridHost) for (IGridNode node : nodes((IGridHost) tile)) node.updateState();
        }
    }

    private void maintain(World world, Entry entry) {
        int x = QuantumControllerRegistry.unpackX(entry.position),
            y = QuantumControllerRegistry.unpackY(entry.position),
            z = QuantumControllerRegistry.unpackZ(entry.position);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGridHost)) return;
        long isolationTick = world.getTotalWorldTime();
        if (entry.lastIsolation == Long.MIN_VALUE || isolationTick - entry.lastIsolation >= 20) {
            disconnectExternal(tile, entry.connection);
            entry.lastIsolation = isolationTick;
        }
        // A disconnected device cannot lose further bridge permissions. Wait before asking native
        // hosts for nodes (which may create/merge grids), rather than only gating the constructors.
        boolean attemptReserved = false;
        if (!entry.hasLiveConnections()) {
            entry.disconnect();
            if (isolationTick < entry.nextAttempt) return;
        }
        WorldServer anchor = DimensionManager.getWorld(entry.dim);
        if (anchor != null) {
            if (QuantumControllerRegistry.get(anchor)
                .isSuspendedAnchor(
                    QuantumControllerRegistry.pack(entry.ax, entry.ay, entry.az),
                    entry.anchorGeneration)) {
                entry.disconnect();
                entry.deferReconnect(isolationTick);
                return;
            }
            AnchorKey previous = entry.anchorKey();
            String previousGeneration = entry.anchorGeneration;
            if (!normalizeAnchor(entry, anchor)) {
                entry.disconnect();
                entry.deferReconnect(isolationTick);
                return;
            }
            if (!previous.equals(entry.anchorKey()) || !previousGeneration.equals(entry.anchorGeneration)) {
                Map<Long, Entry> indexed = sources.get(previous);
                if (indexed != null) {
                    indexed.remove(entry.position);
                    if (indexed.isEmpty()) sources.remove(previous);
                }
                index(entry);
                markDirty();
                invalidateStats();
            }
        }
        // Never load worlds or chunks just to keep an incorporated machine connected.
        if (anchor == null || !anchor.blockExists(entry.ax, entry.ay, entry.az)) {
            entry.disconnect();
            entry.deferReconnect(isolationTick);
            return;
        }
        TileEntity controller = anchor.getTileEntity(entry.ax, entry.ay, entry.az);
        if (!(controller instanceof TileController)) {
            entry.disconnect();
            entry.deferReconnect(isolationTick);
            return;
        }
        // The audited OFF override belongs to these same live endpoints. Native/policy changes
        // invalidate it synchronously; ON networks continue through fresh native authorization.
        if (entry.hasLiveConnections() && entry.target.getMachine() == controller
            && !controller.isInvalid()
            && QuantumConnectionSecurity.hasUnchangedOverride(entry.connection, entry.billingConnection)) return;
        if (!entry.hasLiveConnections()) {
            if (!reserveConnectionAttempt()) return;
            attemptReserved = true;
        }
        IGridNode target = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN);
        ArrayList<IGridNode> local = nodes((IGridHost) tile);
        if (target == null || local.isEmpty()) {
            entry.disconnect();
            entry.deferReconnect(isolationTick);
            return;
        }
        IGridNode node = local.get(0);
        if (!QuantumConnectionSecurity
            .mayBridge(anchor, entry.ax, entry.ay, entry.az, node, target, entry::nativeActorID)) {
            entry.disconnect();
            entry.deferReconnect(isolationTick);
            if (DebugTestGate.probesActive())
                TestWorldProbes.at(world, x, y, z, Type.INCORPORATION_REJECT, "reason=NO_PERMISSION");
            return;
        }
        if (entry.local == node && entry.target == target && entry.hasLiveConnections()) {
            return;
        }
        // An incomplete bridge must never stay online while waiting for a billed retry.
        entry.disconnect();
        if (!attemptReserved && !reserveConnectionAttempt()) return;
        disconnectExternal(tile, null);
        boolean connected = false;
        try {
            entry.local = node;
            entry.target = target;
            BillingLeaf billing = new BillingLeaf(entry, new DimensionalCoord(controller));
            entry.billingNode = AEApi.instance()
                .createGridNode(billing);
            billing.node = entry.billingNode;
            int actor = entry.nativeActorID();
            // Legacy OPEN registrations with no provable actor keep their prior native endpoint behavior.
            entry.billingNode.setPlayerID(actor >= 0 ? actor : target.getPlayerID());
            entry.billingNode.updateState();
            // A single DENSE_CAPACITY node caps the complete quantum route at 32 channels,
            // shared by every downstream side/device. Original native flags and owner are untouched.
            // Attach the bridge to the secured controller first: the second native connection still
            // checks the original device's own player ID against that network's security grid.
            entry.billingConnection = AEApi.instance()
                .createGridConnection(entry.billingNode, target);
            entry.connection = AEApi.instance()
                .createGridConnection(node, entry.billingNode);
            entry.backoff = 20;
            entry.nextAttempt = 0L;
            ACTIVE.computeIfAbsent(entry.anchorKey(), ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(entry);
            invalidateStats();
            connected = true;
            if (DebugTestGate.probesActive()) TestWorldProbes.at(world, x, y, z, Type.INCORPORATION_CONNECT, "");
        } catch (appeng.api.exceptions.FailedConnection failure) {
            // Security failures remain isolated and offline with bounded exponential retry.
            entry.deferReconnect(isolationTick);
            if (DebugTestGate.probesActive()) TestWorldProbes.at(
                world,
                x,
                y,
                z,
                Type.INCORPORATION_REJECT,
                "reason=" + failure.getClass()
                    .getSimpleName());
        } finally {
            if (!connected) entry.disconnect();
        }
    }

    private static boolean reserveConnectionAttempt() {
        long now = net.minecraft.server.MinecraftServer.getServer()
            .getTickCounter();
        if (budgetTick != now) {
            budgetTick = now;
            connectionsThisTick = 0;
        }
        if (connectionsThisTick >= 2) return false;
        connectionsThisTick++;
        return true;
    }

    private static ArrayList<IGridNode> nodes(IGridHost host) {
        ArrayList<IGridNode> result = new ArrayList<>();
        IGridNode primary = host.getGridNode(ForgeDirection.UNKNOWN);
        if (primary != null) result.add(primary);
        for (ForgeDirection direction : ForgeDirection.values()) {
            IGridNode node = host.getGridNode(direction);
            if (node != null && !result.contains(node)) result.add(node);
        }
        return result;
    }

    /** AE proxies owned by GT machines expose the MTE, rather than its tile, as their machine. */
    private static TileEntity nodeTile(IGridNode node) {
        if (node == null) return null;
        Object machine = node.getMachine();
        TileEntity tile;
        boolean located = false;
        if (machine instanceof TileEntity) {
            tile = (TileEntity) machine;
        } else if (machine instanceof IMetaTileEntity) {
            IGregTechTileEntity base = ((IMetaTileEntity) machine).getBaseMetaTileEntity();
            if (!(base instanceof TileEntity) || base.getMetaTileEntity() != machine) return null;
            tile = (TileEntity) base;
        } else {
            // Other proxy owners may locate their tile through the grid block. Coordinates alone
            // are insufficient: only a node actually exposed by this tile belongs to it.
            if (node.getGridBlock() == null) return null;
            appeng.api.util.DimensionalCoord location = node.getGridBlock()
                .getLocation();
            World world = location == null ? null : location.getWorld();
            if (world == null || !world.blockExists(location.x, location.y, location.z)) return null;
            tile = world.getTileEntity(location.x, location.y, location.z);
            located = true;
        }
        if (tile == null || !(tile instanceof IGridHost)) return null;
        World world = tile.getWorldObj();
        if (world == null || world.isRemote
            || !world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
            || world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) != tile) return null;
        // A tile or its currently attached MTE proves ownership, including private internal nodes.
        if (!located) return tile;
        for (IGridNode owned : nodes((IGridHost) tile)) {
            if (owned == node) return tile;
        }
        return null;
    }

    private static void disconnectExternal(TileEntity tile, IGridConnection preserve) {
        for (IGridNode node : nodes((IGridHost) tile)) {
            ArrayList<IGridConnection> connections = new ArrayList<>();
            for (IGridConnection connection : node.getConnections()) connections.add(connection);
            for (IGridConnection connection : connections) {
                if (connection == preserve) continue;
                IGridNode peer = connection.getOtherSide(node);
                boolean forbidden = connection.getDirection(node) == ForgeDirection.UNKNOWN
                    ? peer == null || nodeTile(peer) != tile
                    : blocksCableConnection(node, peer);
                if (forbidden) connection.destroy();
            }
        }
    }

    public void recolor(World world, int dimension, java.util.Set<Long> members, int color) {
        java.util.Set<Long> changedChunks = new java.util.HashSet<>();
        for (long pos : members) {
            Map<Long, Entry> indexed = sources.get(
                new AnchorKey(
                    dimension,
                    QuantumControllerRegistry.unpackX(pos),
                    QuantumControllerRegistry.unpackY(pos),
                    QuantumControllerRegistry.unpackZ(pos)));
            if (indexed == null) continue;
            for (Entry entry : indexed.values()) {
                entry.color = color;
                changedChunks.add(entryChunk(entry));
            }
        }
        if (changedChunks.isEmpty()) return;
        markDirty();
        for (long chunk : changedChunks)
            QuantumIncorporationVisualSync.changed(world, (int) (chunk >> 32) << 4, 0, (int) chunk << 4);
    }

    /** Includes registrations whose local chunks are unloaded; identity and owner never change. */
    public void applyPendingMigrations(World world, QuantumMigrationJournal journal) {
        if (migrationRevision == journal.currentSequence()) return;
        if (journal.currentSequence() == 0) {
            migrationRevision = 0;
            return; // Old aliases upgrade only when their own device is normally maintained, never by a load-time scan.
        }
        Set<Long> changedChunks = new java.util.HashSet<>();
        boolean dirty = false;
        for (Entry entry : entries.values()) {
            if (entry.lastAppliedMigration >= journal.currentSequence()) continue;
            AnchorKey previous = entry.anchorKey();
            String previousGeneration = entry.anchorGeneration;
            QuantumMigrationJournal.Resolution result = journal
                .resolve(previous, entry.lastAppliedMigration, entry.anchorGeneration);
            if (!result.anchor.equals(previous) || !result.generation.equals(entry.anchorGeneration)) {
                entry.disconnect();
                entry.dim = result.anchor.getDimension();
                entry.ax = result.anchor.getX();
                entry.ay = result.anchor.getY();
                entry.az = result.anchor.getZ();
                entry.anchorGeneration = result.generation;
                entry.nextAttempt = 0L;
                entry.backoff = 20;
                entry.color = entry.getColorIndex();
            }
            WorldServer anchor = DimensionManager.getWorld(entry.dim);
            if (anchor != null) normalizeAnchor(entry, anchor);
            if (!previous.equals(entry.anchorKey()) || !previousGeneration.equals(entry.anchorGeneration))
                changedChunks.add(entryChunk(entry));
            entry.lastAppliedMigration = result.sequence;
            dirty = true;
        }
        if (dirty) markDirty();
        if (!changedChunks.isEmpty()) {
            sources.clear();
            for (Entry entry : entries.values()) index(entry);
            invalidateStats();
        }
        migrationRevision = journal.currentSequence();
        if (!changedChunks.isEmpty()) migrationVisualsChanged(world, changedChunks);
    }

    private boolean normalizeAnchor(Entry entry, WorldServer anchorWorld) {
        QuantumControllerRegistry registry = QuantumControllerRegistry.get(anchorWorld);
        long revision = registry.getRevision();
        if (entry.normalizedRegistry == registry && entry.normalizedRevision == revision
            && entry.normalizedKey == entry.anchorKey()
            && entry.anchorGeneration.equals(entry.normalizedGeneration)) return entry.normalizedValid;
        QuantumControllerRegistry.CanonicalAnchor anchor = registry
            .resolveAnchor(QuantumControllerRegistry.pack(entry.ax, entry.ay, entry.az), entry.anchorGeneration);
        if (anchor == null) {
            entry.normalizedRegistry = registry;
            entry.normalizedRevision = registry.getRevision();
            entry.normalizedKey = entry.anchorKey();
            entry.normalizedGeneration = entry.anchorGeneration;
            entry.normalizedValid = false;
            return false;
        }
        if (anchor.position != QuantumControllerRegistry.pack(entry.ax, entry.ay, entry.az)
            || !anchor.generation.equals(entry.anchorGeneration)) {
            entry.disconnect();
            entry.ax = QuantumControllerRegistry.unpackX(anchor.position);
            entry.ay = QuantumControllerRegistry.unpackY(anchor.position);
            entry.az = QuantumControllerRegistry.unpackZ(anchor.position);
            entry.anchorGeneration = anchor.generation;
            entry.nextAttempt = 0L;
            entry.backoff = 20;
        }
        entry.normalizedRegistry = registry;
        entry.normalizedRevision = registry.getRevision();
        entry.normalizedKey = entry.anchorKey();
        entry.normalizedGeneration = entry.anchorGeneration;
        entry.normalizedValid = true;
        return true;
    }

    /** Visual snapshots may reenter get(); every binding and index must already reflect the committed revision. */
    void migrationVisualsChanged(World world, Set<Long> changedChunks) {
        for (long chunk : changedChunks)
            QuantumIncorporationVisualSync.changed(world, (int) (chunk >> 32) << 4, 0, (int) chunk << 4);
    }

    public void unload() {
        for (Entry entry : snapshot()) {
            entry.disconnect();
            entry.loadedTile = null;
            entry.normalizedRegistry = null;
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        for (Entry entry : entries.values()) entry.disconnect();
        entries.clear();
        migrationRevision = -1L;
        chunks.clear();
        sources.clear();
        maintenanceOrder = null;
        NBTTagList list = tag.getTagList("entries", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i);
            Entry e = new Entry();
            e.position = n.getLong("pos");
            e.lastAppliedMigration = n.getLong("lastAppliedMigration");
            e.block = n.getString("block");
            e.tileClass = n.getString("tile");
            e.identity = n.getString("identity");
            e.owner = n.getString("owner");
            e.ownerName = n.getString("ownerName");
            e.nativeOwnerID = n.hasKey("nativeOwnerID", 3) ? n.getInteger("nativeOwnerID") : -1;
            e.dim = n.getInteger("dim");
            e.ax = n.getInteger("x");
            e.ay = n.getInteger("y");
            e.az = n.getInteger("z");
            e.anchorGeneration = n.getString("anchorGeneration");
            e.color = QuantumNetworkColor.normalize(n.getInteger("color"));
            if (!e.identity.isEmpty()) {
                entries.put(e.position, e);
                index(e);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Entry e : entries.values()) {
            NBTTagCompound n = new NBTTagCompound();
            n.setLong("pos", e.position);
            n.setLong("lastAppliedMigration", e.lastAppliedMigration);
            n.setString("block", e.block);
            n.setString("tile", e.tileClass);
            n.setString("identity", e.identity);
            n.setString("owner", e.owner);
            n.setString("ownerName", e.ownerName);
            if (e.nativeOwnerID >= 0) n.setInteger("nativeOwnerID", e.nativeOwnerID);
            n.setInteger("dim", e.dim);
            n.setInteger("x", e.ax);
            n.setInteger("y", e.ay);
            n.setInteger("z", e.az);
            n.setString("anchorGeneration", e.anchorGeneration);
            n.setInteger("color", e.color);
            list.appendTag(n);
        }
        tag.setTag("entries", list);
    }

    public static final class Entry {

        public long position;
        public int dim, ax, ay, az;
        public String anchorGeneration = "";
        private int color;
        private long lastAppliedMigration;

        public int getColorIndex() {
            WorldServer anchor = DimensionManager.getWorld(dim);
            return anchor == null ? color
                : QuantumNetworkColorRegistry.get(anchor)
                    .color(ax, ay, az);
        }

        private String block, tileClass, identity, owner;
        private String ownerName = "";
        private int nativeOwnerID = -1;
        private TileEntity loadedTile;
        private IGridConnection connection;
        private IGridConnection billingConnection;
        private IGridNode billingNode;
        private IGridNode local, target;
        private long nextAttempt;
        private long lastIsolation = Long.MIN_VALUE;
        private int backoff = 20;
        private AnchorKey cachedAnchorKey, normalizedKey;
        private QuantumControllerRegistry normalizedRegistry;
        private long normalizedRevision;
        private boolean normalizedValid;
        private String normalizedGeneration;

        private void deferReconnect(long now) {
            nextAttempt = now + backoff;
            backoff = Math.min(200, backoff * 2);
        }

        private AnchorKey anchorKey() {
            if (cachedAnchorKey == null || cachedAnchorKey.getDimension() != dim
                || cachedAnchorKey.getX() != ax
                || cachedAnchorKey.getY() != ay
                || cachedAnchorKey.getZ() != az) cachedAnchorKey = new AnchorKey(dim, ax, ay, az);
            return cachedAnchorKey;
        }

        public String identity() {
            return identity;
        }

        public String owner() {
            return owner;
        }

        public String ownerName() {
            return ownerName;
        }

        private int nativeActorID() {
            if (nativeOwnerID >= 0) return nativeOwnerID;
            return resolveNativeActor(
                AEApi.instance()
                    .registries()
                    .players());
        }

        int resolveNativeActor(IPlayerRegistry players) {
            if (nativeOwnerID >= 0) return nativeOwnerID;
            try {
                UUID uuid = UUID.fromString(owner);
                nativeOwnerID = players.getID(new GameProfile(uuid, ownerName.isEmpty() ? owner : ownerName));
                if (nativeOwnerID >= 0 && loadedTile != null && loadedTile.getWorldObj() != null)
                    get(loadedTile.getWorldObj()).markDirty();
                return nativeOwnerID;
            } catch (IllegalArgumentException invalidIdentity) {
                return -1;
            }
        }

        public boolean isConnected() {
            return hasLiveConnections();
        }

        public int usedChannels() {
            return hasLiveConnections() ? connection.getUsedChannels() : 0;
        }

        private boolean owns(TileEntity tile) {
            return loadedTile != null ? loadedTile == tile
                : tile instanceof QuantumIncorporationIdentity
                    && identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity());
        }

        private boolean hasLiveConnections() {
            return connection != null && billingConnection != null
                && local != null
                && target != null
                && billingNode != null
                && QuantumConnectionMembership.contains(local, connection)
                && QuantumConnectionMembership.contains(billingNode, connection)
                && QuantumConnectionMembership.contains(billingNode, billingConnection)
                && QuantumConnectionMembership.contains(target, billingConnection)
                && target.getGrid() != null
                && local.getGrid() == target.getGrid()
                && billingNode.getGrid() == target.getGrid();
        }

        private void disconnect() {
            Set<Entry> active = ACTIVE.get(anchorKey());
            boolean changed = active != null && active.remove(this);
            if (active != null && active.isEmpty()) ACTIVE.remove(anchorKey());
            // An overridden tile invalidate may have destroyed its AE node before reaching our base hook.
            if (connection != null
                && (containsConnection(local, connection) || containsConnection(billingNode, connection)))
                connection.destroy();
            if (billingConnection != null && (containsConnection(billingNode, billingConnection)
                || containsConnection(target, billingConnection))) billingConnection.destroy();
            if (billingNode != null) billingNode.destroy();
            if (changed && DebugTestGate.probesActive())
                TestWorldProbes.at(loadedTile, Type.INCORPORATION_DISCONNECT, "");
            connection = null;
            billingConnection = null;
            billingNode = null;
            local = null;
            target = null;
            if (changed) invalidateStats();
        }

        private static boolean containsConnection(IGridNode node, IGridConnection connection) {
            return node != null && QuantumConnectionMembership.contains(node, connection);
        }
    }

    /** Runtime-only shared 32-channel bottleneck and idle surcharge; AE owns pathing and accounting. */
    private static final class BillingLeaf implements IGridBlock, IGridHost {

        private final DimensionalCoord location;
        private final Entry entry;
        // Compensate only our surcharge; the original device and channel draw retain AE's multiplier.
        private final double idlePower = PowerMultiplier.CONFIG.divide(Config.quantumIncorporationIdlePowerUsage);
        private IGridNode node;

        private BillingLeaf(Entry entry, DimensionalCoord location) {
            this.entry = entry;
            this.location = location;
        }

        @Override
        public double getIdlePowerUsage() {
            return idlePower;
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
            // No physical block exists; tear down both edges through the single lifecycle owner.
            if (entry.billingNode == node) entry.disconnect();
            node = null;
        }
    }
}
