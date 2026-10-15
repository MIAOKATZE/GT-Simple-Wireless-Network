package com.miaokatze.gtswn.common.quantum;

import java.util.Collections;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.config.Config;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.features.ILocatable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.tile.networking.TileController;

/** Server authority for every quantum-terminal action. Item ownership is descriptive, never authorization. */
public final class QuantumTerminalAccess {

    public static final String NBT_MIGRATION_CURSOR = "QT_LastAppliedMigration";
    public static final String NBT_OWNER_UUID = "QT_OwnerUUID";
    public static final String NBT_OWNER_NAME = "QT_OwnerName";
    public static final String NBT_ENCRYPTION_KEY = "QT_EncryptionKey";

    private QuantumTerminalAccess() {}

    /** Silent by design: polling and inventory refreshes must not produce chat spam. */
    public static boolean validate(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        synchronize(player, stack);
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return true;
        World world = DimensionManager.getWorld(anchor[0]);
        return world != null && permits(player, stack, world, anchor[1], anchor[2], anchor[3], false);
    }

    public static boolean canManage(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        synchronize(player, stack);
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return true;
        World world = DimensionManager.getWorld(anchor[0]);
        return world != null && permits(player, stack, world, anchor[1], anchor[2], anchor[3], true);
    }

    /** Binding and controller interactions must check the clicked network, including unbound terminals. */
    public static boolean canAccessAnchor(EntityPlayer player, ItemStack stack, World world, int x, int y, int z) {
        if (!isTerminal(stack) || player == null || world == null || world.isRemote) return false;
        synchronize(player, stack);
        return permits(player, stack, world, x, y, z, false);
    }

    public static boolean canManageAnchor(EntityPlayer player, ItemStack stack, World world, int x, int y, int z) {
        if (!isTerminal(stack) || player == null || world == null || world.isRemote) return false;
        synchronize(player, stack);
        return permits(player, stack, world, x, y, z, true);
    }

    public static boolean isSecurityRequired(World world, int x, int y, int z) {
        return Config.quantumSafetyEnabled && isNetworkSafetyEnabled(world, x, y, z);
    }

    public static boolean isNetworkSafetyEnabled(World world, int x, int y, int z) {
        return world != null && QuantumNetworkSecurityRegistry.get(world)
            .isSafetyEnabled(world, members(world, x, y, z));
    }

    public static String networkSafetyIdentity(World world, int x, int y, int z) {
        if (world == null || world.isRemote || !world.blockExists(x, y, z)) return "";
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        long position = QuantumControllerRegistry.pack(x, y, z);
        if (!controllers.isQuantized(position) || !controllers.isCurrentController(world, position)) return "";
        TileEntity tile = world.getTileEntity(x, y, z);
        return tile instanceof QuantumControllerIdentity
            ? ((QuantumControllerIdentity) tile).gtswn$getControllerIdentity()
            : "";
    }

    /** Both directions require native network authority, independently of terminal pairing and the safety switches. */
    public static boolean canChangeNetworkSafety(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        synchronize(player, stack);
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return false;
        World world = DimensionManager.getWorld(anchor[0]);
        if (networkSafetyIdentity(world, anchor[1], anchor[2], anchor[3]).isEmpty()) return false;
        return permitsSafetyManagement(player, world, anchor[1], anchor[2], anchor[3]);
    }

    public static boolean setNetworkSafety(EntityPlayer player, ItemStack stack, boolean enabled) {
        if (!Config.quantumSafetyEnabled || !canChangeNetworkSafety(player, stack)) return false;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        World world = DimensionManager.getWorld(anchor[0]);
        QuantumNetworkSecurityRegistry.get(world)
            .setSafety(world, members(world, anchor[1], anchor[2], anchor[3]), enabled);
        QuantumNetworkData.clearCache();
        QuantumNetworkStatsCache.clear();
        QuantumConnectionSecurity.restoreNativeChecks();
        return true;
    }

    static Set<Long> networkMembers(World world, int x, int y, int z) {
        return members(world, x, y, z);
    }

    /** Managers can always reach the safety switch even when their terminal is not paired. */
    public static boolean canOpenTerminal(EntityPlayer player, ItemStack stack) {
        return validate(player, stack) || canManage(player, stack) || canChangeNetworkSafety(player, stack);
    }

    private static boolean permitsSafetyManagement(EntityPlayer player, World world, int x, int y, int z) {
        IGrid grid = gridAt(world, x, y, z);
        if (grid == null) return false;
        int actor = AEApi.instance()
            .registries()
            .players()
            .getID(player);
        if (actor < 0) return false;
        boolean hasProvider = false;
        for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!ISecurityProvider.class.isAssignableFrom(type)) continue;
            for (IGridNode node : grid.getMachines(type)) {
                if (!(node.getMachine() instanceof ISecurityProvider)) continue;
                hasProvider = true;
                ISecurityProvider provider = (ISecurityProvider) node.getMachine();
                if (!permitsSafetyProvider(provider, actor)) return false;
            }
        }
        if (hasProvider) return true;
        // ISecurityGrid's synthetic owner can come from any grid node. Use actual controllers only.
        boolean found = false;
        for (long member : members(world, x, y, z)) {
            int cx = QuantumControllerRegistry.unpackX(member), cy = QuantumControllerRegistry.unpackY(member),
                cz = QuantumControllerRegistry.unpackZ(member);
            if (!world.blockExists(cx, cy, cz)) return false;
            TileEntity tile = world.getTileEntity(cx, cy, cz);
            if (!(tile instanceof TileController)) return false;
            IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            if (node == null || node.getPlayerID() != actor) return false;
            found = true;
        }
        return found;
    }

    static boolean permitsSafetyProvider(ISecurityProvider provider, int actor) {
        if (provider == null || actor < 0 || provider.getOwner() < 0) return false;
        if (provider.getOwner() == actor) return true;
        java.util.HashMap<Integer, java.util.EnumSet<SecurityPermissions>> permissions = new java.util.HashMap<>();
        provider.readPermissions(permissions);
        java.util.EnumSet<SecurityPermissions> grants = permissions.get(actor);
        if (grants == null) grants = permissions.get(-1);
        return grants != null && grants.contains(SecurityPermissions.SECURITY);
    }

    private static boolean permits(EntityPlayer player, ItemStack stack, World world, int x, int y, int z,
        boolean management) {
        // This switch only suppresses GTSWN's AE authorization. Callers retain world,
        // anchor, structure, reach and edit checks, and enrollment data is left intact.
        if (!Config.quantumSafetyEnabled) return true;
        if (!isNetworkSafetyEnabled(world, x, y, z)) return true;
        IGrid grid = gridAt(world, x, y, z);
        return permitsNative(player, stack, grid, management);
    }

    private static boolean permitsNative(EntityPlayer player, ItemStack stack, IGrid grid, boolean management) {
        IGrid encoded = encodedGrid(
            stack.getTagCompound()
                .getString(NBT_ENCRYPTION_KEY));
        ISecurityGrid security = grid == null ? null : grid.getCache(ISecurityGrid.class);
        if (security == null || encoded == null || encoded != grid) return false;
        return permitsEnrolled(
            security,
            encoded != null && encoded == grid,
            player,
            management,
            management ? AEApi.instance()
                .registries()
                .players()
                .getID(player) : -1);
    }

    static boolean permitsEnrolled(ISecurityGrid security, boolean keyMatches, EntityPlayer player, boolean management,
        int playerId) {
        return security != null && keyMatches
            && security.getOwner() >= 0
            && security.isAvailable()
            && (management
                ? security.getOwner() == playerId || security.hasPermission(player, SecurityPermissions.SECURITY)
                : security.hasPermission(player, SecurityPermissions.BUILD));
    }

    /** Migration is resolved before color or permissions, even when the old coordinate has been rebuilt. */
    public static boolean synchronize(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        NBTTagCompound before = stack.getTagCompound() == null ? null
            : (NBTTagCompound) stack.getTagCompound()
                .copy();
        if (stack.getTagCompound() == null) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = stack.getTagCompound();
        tag.setString(
            NBT_OWNER_UUID,
            player.getUniqueID()
                .toString());
        tag.setString(NBT_OWNER_NAME, player.getCommandSenderName());
        applyPendingMigrations(stack, QuantumMigrationJournal.get(player.worldObj));
        QuantumNetworkColor.set(stack, QuantumNetworkColor.resolve(stack));
        boolean changed = !tag.equals(before);
        if (changed) player.inventory.markDirty();
        return changed;
    }

    static void applyPendingMigrations(ItemStack stack, QuantumMigrationJournal journal) {
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return;
        NBTTagCompound tag = stack.getTagCompound();
        QuantumMigrationJournal.Resolution resolved = journal
            .resolve(new AnchorKey(anchor[0], anchor[1], anchor[2], anchor[3]), tag.getLong(NBT_MIGRATION_CURSOR));
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, resolved.anchor.getDimension());
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, resolved.anchor.getX());
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, resolved.anchor.getY());
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, resolved.anchor.getZ());
        tag.setLong(NBT_MIGRATION_CURSOR, resolved.sequence);
        WorldServer world = DimensionManager.getWorld(resolved.anchor.getDimension());
        if (world != null) tag.setString("QT_BoundName", world.provider.getDimensionName());
    }

    /** Called directly by AE2's native security input/output slots through INetworkEncodable. */
    public static void onEncoded(String key) {
        ILocatable location = encodedLocation(key);
        if (!(location instanceof ISecurityProvider) || !(location instanceof TileEntity)) return;
        TileEntity providerTile = (TileEntity) location;
        World providerWorld = providerTile.getWorldObj();
        if (providerWorld == null || providerWorld.isRemote) return;
        long nativeKey = ((ISecurityProvider) location).getSecurityKey();
        if (location.getLocatableSerial() != nativeKey) return;
        // Persist the native station's enrollment even in an ad-hoc grid with no controllers or grid node yet.
        QuantumNetworkSecurityRegistry.providerRegistry(providerWorld)
            .enrollProvider(nativeKey);
        IGrid grid = gridForLocation(location);
        if (grid == null) return;
        for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
            if (!TileController.class.isAssignableFrom(type)) continue;
            for (IGridNode node : grid.getMachines(type)) {
                Object machine = node.getMachine();
                if (!(machine instanceof TileEntity)) continue;
                TileEntity tile = (TileEntity) machine;
                World world = tile.getWorldObj();
                if (world == null || world.isRemote) continue;
                QuantumNetworkSecurityRegistry.get(world)
                    .enroll(world, members(world, tile.xCoord, tile.yCoord, tile.zCoord));
            }
        }
    }

    private static Set<Long> members(World world, int x, int y, int z) {
        Set<Long> structure = QuantumControllerRegistry.get(world)
            .registeredStructure(QuantumControllerRegistry.pack(x, y, z));
        return structure.isEmpty() ? Collections.singleton(QuantumControllerRegistry.pack(x, y, z)) : structure;
    }

    private static ILocatable encodedLocation(String key) {
        if (key == null || key.isEmpty()) return null;
        try {
            long serial = Long.parseLong(key);
            ILocatable location = AEApi.instance()
                .registries()
                .locatable()
                .getLocatableBy(serial);
            return location != null && location.getLocatableSerial() == serial ? location : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static IGrid encodedGrid(String key) {
        ILocatable location = encodedLocation(key);
        if (!(location instanceof ISecurityProvider)
            || ((ISecurityProvider) location).getSecurityKey() != location.getLocatableSerial()) return null;
        return gridForLocation(location);
    }

    private static IGrid gridForLocation(ILocatable location) {
        if (!(location instanceof IGridHost)) return null;
        IGridNode node = ((IGridHost) location).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    private static IGrid gridAt(World world, int x, int y, int z) {
        if (world == null || !world.blockExists(x, y, z)) return null;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGridHost)) return null;
        IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    private static boolean isTerminal(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemNetworkQuantumTerminal;
    }
}
