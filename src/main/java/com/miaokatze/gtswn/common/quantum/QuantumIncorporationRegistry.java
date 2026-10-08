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
import com.miaokatze.gtswn.common.util.SavedDataUtil;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.PowerMultiplier;
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

/** Original devices remain intact; a direct bridge and a separate idle-power leaf are added. */
public class QuantumIncorporationRegistry extends WorldSavedData {

    private static final String DATA_NAME = "gtswn_quantum_incorporation";

    private final Map<Long, Entry> entries = new HashMap<>();
    private final Map<Long, Map<Long, Entry>> chunks = new HashMap<>();
    private final Map<AnchorKey, Map<Long, Entry>> sources = new HashMap<>();
    private ArrayList<Entry> maintenanceOrder;
    private int cursor;
    private static long budgetTick = Long.MIN_VALUE;
    private static int connectionsThisTick;
    /** Only live bridges, indexed across dimensions by their controller anchor. */
    private static final Map<AnchorKey, Set<Entry>> ACTIVE = new HashMap<>();

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
        return SavedDataUtil
            .loadOrCreate(world, DATA_NAME, QuantumIncorporationRegistry.class, QuantumIncorporationRegistry::new);
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
        return tile != null
            && entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity());
    }

    /** Called by the AE directional gate; UNKNOWN internal and quantum edges stay untouched. */
    public static boolean blocksPhysicalConnection(IGridNode node) {
        TileEntity tile = nodeTile(node);
        if (tile == null) return false;
        World world = tile.getWorldObj();
        if (world == null || world.isRemote) return false;
        Entry entry = get(world).entries.get(QuantumControllerRegistry.pack(tile.xCoord, tile.yCoord, tile.zCoord));
        return entry != null
            && entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity());
    }

    public boolean add(World world, int x, int y, int z, NBTTagCompound terminal, EntityPlayer owner) {
        if (!isEligible(world, x, y, z)) return false;
        long key = QuantumControllerRegistry.pack(x, y, z);
        if (entries.containsKey(key)) return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        Entry entry = new Entry();
        entry.position = key;
        entry.block = Block.blockRegistry.getNameForObject(world.getBlock(x, y, z));
        entry.tileClass = tile.getClass()
            .getName();
        entry.identity = UUID.randomUUID()
            .toString();
        entry.owner = owner.getUniqueID()
            .toString();
        entry.dim = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM);
        entry.ax = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X);
        entry.ay = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y);
        entry.az = terminal.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z);
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
        return true;
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

    public Iterable<Entry> snapshot() {
        return new ArrayList<>(entries.values());
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
        sources.computeIfAbsent(new AnchorKey(entry.dim, entry.ax, entry.ay, entry.az), ignored -> new HashMap<>())
            .put(entry.position, entry);
        chunks.computeIfAbsent(entryChunk(entry), ignored -> new HashMap<>())
            .put(entry.position, entry);
    }

    public void sweep(World world) {
        for (Entry entry : snapshot()) {
            inspect(world, entry);
        }
    }

    /** Bounded round-robin inspection; sharing a two-bridge-per-tick budget across every dimension. */
    public void tick(World world) {
        if (entries.isEmpty()) return;
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
            return;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null || !entry.tileClass.equals(
            tile.getClass()
                .getName())
            || !entry.block.equals(Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)))
            || !entry.identity.equals(((QuantumIncorporationIdentity) tile).gtswn$getIncorporationIdentity())) {
            forget(world, entry, false);
            return;
        }
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
        WorldServer anchor = DimensionManager.getWorld(entry.dim);
        // Never load worlds or chunks just to keep an incorporated machine connected.
        if (anchor == null || !anchor.blockExists(entry.ax, entry.ay, entry.az)) {
            entry.disconnect();
            return;
        }
        if (!QuantumControllerRegistry.get(anchor)
            .isQuantized(entry.ax, entry.ay, entry.az)) {
            forget(world, entry, true);
            return;
        }
        TileEntity controller = anchor.getTileEntity(entry.ax, entry.ay, entry.az);
        if (!(controller instanceof TileController)) {
            entry.disconnect();
            return;
        }
        IGridNode target = ((IGridHost) controller).getGridNode(ForgeDirection.UNKNOWN);
        ArrayList<IGridNode> local = nodes((IGridHost) tile);
        if (target == null || local.isEmpty()) {
            entry.disconnect();
            return;
        }
        IGridNode node = local.get(0);
        if (entry.local == node && entry.target == target && entry.hasLiveConnections()) {
            return;
        }
        // An incomplete bridge must never stay online while waiting for a billed retry.
        entry.disconnect();
        long now = net.minecraft.server.MinecraftServer.getServer()
            .getTickCounter();
        if (now < entry.nextAttempt) return;
        if (budgetTick != now) {
            budgetTick = now;
            connectionsThisTick = 0;
        }
        if (connectionsThisTick >= 2) return;
        connectionsThisTick++;
        disconnectExternal(tile, null);
        boolean connected = false;
        try {
            entry.local = node;
            entry.target = target;
            BillingLeaf billing = new BillingLeaf(entry, new DimensionalCoord(controller));
            entry.billingNode = AEApi.instance()
                .createGridNode(billing);
            billing.node = entry.billingNode;
            entry.billingNode.setPlayerID(target.getPlayerID());
            entry.billingNode.updateState();
            // Connecting to the controller keeps isolation of the original machine unchanged.
            entry.billingConnection = AEApi.instance()
                .createGridConnection(entry.billingNode, target);
            entry.connection = AEApi.instance()
                .createGridConnection(node, target);
            entry.backoff = 20;
            ACTIVE.computeIfAbsent(entry.anchorKey(), ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(entry);
            invalidateStats();
            connected = true;
        } catch (appeng.api.exceptions.FailedConnection failure) {
            // Security failures remain isolated and offline with bounded exponential retry.
            entry.nextAttempt = now + entry.backoff;
            entry.backoff = Math.min(200, entry.backoff * 2);
        } finally {
            if (!connected) entry.disconnect();
        }
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
                if (connection != preserve
                    && (connection.getDirection(node) != ForgeDirection.UNKNOWN || connection.getOtherSide(node) == null
                        || nodeTile(connection.getOtherSide(node)) != tile))
                    connection.destroy();
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

    public void unload() {
        for (Entry entry : snapshot()) entry.disconnect();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        for (Entry entry : entries.values()) entry.disconnect();
        entries.clear();
        chunks.clear();
        sources.clear();
        maintenanceOrder = null;
        NBTTagList list = tag.getTagList("entries", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound n = list.getCompoundTagAt(i);
            Entry e = new Entry();
            e.position = n.getLong("pos");
            e.block = n.getString("block");
            e.tileClass = n.getString("tile");
            e.identity = n.getString("identity");
            e.owner = n.getString("owner");
            e.dim = n.getInteger("dim");
            e.ax = n.getInteger("x");
            e.ay = n.getInteger("y");
            e.az = n.getInteger("z");
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
            n.setString("block", e.block);
            n.setString("tile", e.tileClass);
            n.setString("identity", e.identity);
            n.setString("owner", e.owner);
            n.setInteger("dim", e.dim);
            n.setInteger("x", e.ax);
            n.setInteger("y", e.ay);
            n.setInteger("z", e.az);
            n.setInteger("color", e.color);
            list.appendTag(n);
        }
        tag.setTag("entries", list);
    }

    public static final class Entry {

        public long position;
        public int dim, ax, ay, az;
        private int color;

        public int getColorIndex() {
            WorldServer anchor = DimensionManager.getWorld(dim);
            return anchor == null ? color
                : QuantumNetworkColorRegistry.get(anchor)
                    .color(ax, ay, az);
        }

        private String block, tileClass, identity, owner;
        private IGridConnection connection;
        private IGridConnection billingConnection;
        private IGridNode billingNode;
        private IGridNode local, target;
        private long nextAttempt;
        private long lastIsolation = Long.MIN_VALUE;
        private int backoff = 20;

        private AnchorKey anchorKey() {
            return new AnchorKey(dim, ax, ay, az);
        }

        public String identity() {
            return identity;
        }

        private boolean hasLiveConnections() {
            return connection != null && billingConnection != null
                && local != null
                && target != null
                && billingNode != null
                && local.getConnections()
                    .contains(connection)
                && target.getConnections()
                    .contains(connection)
                && billingNode.getConnections()
                    .contains(billingConnection)
                && target.getConnections()
                    .contains(billingConnection)
                && target.getGrid() != null
                && local.getGrid() == target.getGrid()
                && billingNode.getGrid() == target.getGrid();
        }

        private void disconnect() {
            Set<Entry> active = ACTIVE.get(anchorKey());
            boolean changed = active != null && active.remove(this);
            if (active != null && active.isEmpty()) ACTIVE.remove(anchorKey());
            if (connection != null) connection.destroy();
            if (billingConnection != null) billingConnection.destroy();
            if (billingNode != null) billingNode.destroy();
            connection = null;
            billingConnection = null;
            billingNode = null;
            local = null;
            target = null;
            if (changed) invalidateStats();
        }
    }

    /** Runtime-only, channel-free surcharge; AE's energy cache owns all accounting. */
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
            return EnumSet.noneOf(GridFlags.class);
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
