package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
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

import appeng.api.AEApi;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPartHost;
import appeng.tile.networking.TileController;

/** Original blocks and tile entities remain intact; only an AE grid edge is added. */
public class QuantumIncorporationRegistry extends WorldSavedData {

    private static final String DATA_NAME = "gtswn_quantum_incorporation";

    private final Map<Long, Entry> entries = new HashMap<>();
    private ArrayList<Entry> maintenanceOrder;
    private int cursor;
    private static long budgetTick = Long.MIN_VALUE;
    private static int connectionsThisTick;

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
        if (node == null || !(node.getMachine() instanceof TileEntity)) return false;
        TileEntity tile = (TileEntity) node.getMachine();
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
        ((QuantumIncorporationIdentity) tile).gtswn$setIncorporationIdentity(entry.identity);
        tile.markDirty();
        entries.put(key, entry);
        maintenanceOrder = null;
        markDirty();
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

    public void sweep(World world) {
        for (Entry entry : snapshot()) {
            inspect(world, entry);
        }
    }

    /** Bounded round-robin inspection; sharing a two-edge-per-tick budget across every dimension. */
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
        maintain(world, entry);
    }

    private void forget(World world, Entry entry, boolean restore) {
        entry.disconnect();
        entries.remove(entry.position);
        maintenanceOrder = null;
        markDirty();
        int x = QuantumControllerRegistry.unpackX(entry.position),
            y = QuantumControllerRegistry.unpackY(entry.position),
            z = QuantumControllerRegistry.unpackZ(entry.position);
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
        if (entry.connection != null && entry.local == node
            && entry.target == target
            && node.getConnections()
                .contains(entry.connection)) {
            long isolationTick = world.getTotalWorldTime();
            if (isolationTick - entry.lastIsolation >= 20) {
                disconnectExternal(tile, entry.connection);
                entry.lastIsolation = isolationTick;
            }
            return;
        }
        long now = net.minecraft.server.MinecraftServer.getServer()
            .getTickCounter();
        if (now < entry.nextAttempt) return;
        if (budgetTick != now) {
            budgetTick = now;
            connectionsThisTick = 0;
        }
        if (connectionsThisTick >= 2) return;
        connectionsThisTick++;
        entry.disconnect();
        disconnectExternal(tile, null);
        try {
            entry.connection = AEApi.instance()
                .createGridConnection(node, target);
            entry.local = node;
            entry.target = target;
            entry.backoff = 20;
        } catch (appeng.api.exceptions.FailedConnection failure) {
            // Security failures remain isolated and offline with bounded exponential retry.
            entry.nextAttempt = now + entry.backoff;
            entry.backoff = Math.min(200, entry.backoff * 2);
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

    private static void disconnectExternal(TileEntity tile, IGridConnection preserve) {
        for (IGridNode node : nodes((IGridHost) tile)) {
            ArrayList<IGridConnection> connections = new ArrayList<>();
            for (IGridConnection connection : node.getConnections()) connections.add(connection);
            for (IGridConnection connection : connections) {
                if (connection != preserve
                    && (connection.getDirection(node) != ForgeDirection.UNKNOWN || connection.getOtherSide(node)
                        .getMachine() != tile))
                    connection.destroy();
            }
        }
    }

    public void unload() {
        for (Entry entry : snapshot()) entry.disconnect();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        entries.clear();
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
            if (!e.identity.isEmpty()) entries.put(e.position, e);
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
            list.appendTag(n);
        }
        tag.setTag("entries", list);
    }

    public static final class Entry {

        public long position;
        public int dim, ax, ay, az;
        private String block, tileClass, identity, owner;
        private IGridConnection connection;
        private IGridNode local, target;
        private long nextAttempt;
        private long lastIsolation;
        private int backoff = 20;

        private void disconnect() {
            if (connection != null) connection.destroy();
            connection = null;
            local = null;
            target = null;
        }
    }
}
