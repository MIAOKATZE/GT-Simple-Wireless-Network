package com.miaokatze.gtswn.common.quantum;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
    /** Main-thread, pending items only. Values never retain item stacks, players or worlds. */
    private static Map<ItemStack, PendingUpgrade> pendingUpgrades;

    private QuantumTerminalAccess() {}

    public static void clearPendingUpgrade(ItemStack stack) {
        if (pendingUpgrades != null) pendingUpgrades.remove(stack);
    }

    public static void upgradeLoadedItem(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || tag.getByte(ItemNetworkQuantumTerminal.NBT_BOUND) != 1) return;
        if (tag.getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA) == ItemNetworkQuantumTerminal.ANCHOR_SCHEMA
            && tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH) == QuantumTerminalSyncEpoch.current()) return;
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(player.worldObj);
        PendingUpgrade pending = pendingUpgrades == null ? null : pendingUpgrades.get(stack);
        if (pending != null) {
            WorldServer previousWorld = DimensionManager
                .getWorld(tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM));
            QuantumControllerRegistry previous = previousWorld == null ? null
                : QuantumControllerRegistry.get(previousWorld);
            if (pending.matches(tag, previous, journal)) return;
        }
        int replay = replayPendingMigrations(stack, journal);
        boolean changed = (replay & 1) != 0;
        boolean canonicalRequired = tag.getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA)
            != ItemNetworkQuantumTerminal.ANCHOR_SCHEMA || (replay & 2) != 0
            || (pending != null && pending.canonicalRequired);
        if (!canonicalRequired) {
            // A color/session notice does not repeat an already completed format/alias conversion.
            WorldServer world = DimensionManager.getWorld(tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM));
            if (world == null) deferUpgrade(stack, tag, null, journal, false);
            else {
                changed |= updateColor(stack, world);
                changed |= recordSyncEpoch(tag, journal);
                clearPendingUpgrade(stack);
            }
        } else {
            WorldServer world = DimensionManager.getWorld(tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM));
            QuantumControllerRegistry registry = world == null ? null : QuantumControllerRegistry.get(world);
            QuantumControllerRegistry.CanonicalAnchor anchor = registry == null ? null
                : registry.resolveAnchor(
                    bindingPosition(tag),
                    tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
            if (anchor != null) {
                changed |= applyCanonicalAnchor(tag, world, anchor);
                changed |= updateColor(stack, world);
                changed |= recordSyncEpoch(tag, journal);
                clearPendingUpgrade(stack);
            } else {
                deferUpgrade(stack, tag, registry, journal, true);
            }
        }
        if (changed) player.inventory.markDirty();
    }

    private static void deferUpgrade(ItemStack stack, NBTTagCompound tag, QuantumControllerRegistry registry,
        QuantumMigrationJournal journal, boolean canonicalRequired) {
        if (pendingUpgrades == null) pendingUpgrades = new WeakHashMap<>();
        pendingUpgrades.put(stack, new PendingUpgrade(tag, registry, journal, canonicalRequired));
    }

    private static long bindingPosition(NBTTagCompound tag) {
        return QuantumControllerRegistry.pack(
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X),
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y),
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z));
    }

    private static final class PendingUpgrade {

        private final WeakReference<QuantumControllerRegistry> registry;
        private final WeakReference<QuantumMigrationJournal> journal;
        private final long revision, sequence, position, cursor, epoch;
        private final int dimension;
        private final String generation;
        private final boolean canonicalRequired;

        PendingUpgrade(NBTTagCompound tag, QuantumControllerRegistry registry, QuantumMigrationJournal journal,
            boolean canonicalRequired) {
            this.registry = new WeakReference<>(registry);
            this.journal = new WeakReference<>(journal);
            revision = registry == null ? -1 : registry.getRevision();
            sequence = journal.currentSequence();
            position = bindingPosition(tag);
            cursor = tag.getLong(NBT_MIGRATION_CURSOR);
            dimension = tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM);
            generation = tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION);
            epoch = QuantumTerminalSyncEpoch.current();
            this.canonicalRequired = canonicalRequired;
        }

        boolean matches(NBTTagCompound tag, QuantumControllerRegistry current, QuantumMigrationJournal currentJournal) {
            return epoch == QuantumTerminalSyncEpoch.current() && registry.get() == current
                && (current == null || revision == current.getRevision())
                && journal.get() == currentJournal
                && sequence == currentJournal.currentSequence()
                && cursor >= currentJournal.currentSequence()
                && cursor == tag.getLong(NBT_MIGRATION_CURSOR)
                && position == bindingPosition(tag)
                && dimension == tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM)
                && generation.equals(tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
        }
    }

    /** Silent by design: polling and inventory refreshes must not produce chat spam. */
    public static boolean validate(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        synchronize(player, stack);
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return true;
        World world = DimensionManager.getWorld(anchor[0]);
        return world != null && currentBinding(stack, world, anchor)
            && permits(player, stack, world, anchor[1], anchor[2], anchor[3], false);
    }

    public static boolean canManage(EntityPlayer player, ItemStack stack) {
        if (!isTerminal(stack) || player == null || player.worldObj == null || player.worldObj.isRemote) return false;
        synchronize(player, stack);
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return true;
        World world = DimensionManager.getWorld(anchor[0]);
        return world != null && currentBinding(stack, world, anchor)
            && permits(player, stack, world, anchor[1], anchor[2], anchor[3], true);
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
        if (world == null || !currentBinding(stack, world, anchor)) return false;
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
        boolean changed = stack.getTagCompound() == null;
        if (changed) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound tag = stack.getTagCompound();
        changed |= setString(
            tag,
            NBT_OWNER_UUID,
            player.getUniqueID()
                .toString());
        changed |= setString(tag, NBT_OWNER_NAME, player.getCommandSenderName());
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(player.worldObj);
        int replay = replayPendingMigrations(stack, journal);
        changed |= (replay & 1) != 0;
        // This is format state only. validate/canManage still call currentBinding and native permissions below.
        boolean formatted = tag.getByte(ItemNetworkQuantumTerminal.NBT_BOUND) == 1
            && tag.getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA) == ItemNetworkQuantumTerminal.ANCHOR_SCHEMA
            && (replay & 2) == 0;
        if (!formatted) {
            int normalized = normalizeAnchor(stack);
            changed |= (normalized & 1) != 0;
            formatted = (normalized & 2) != 0;
        }
        World colorWorld = tag.getByte(ItemNetworkQuantumTerminal.NBT_BOUND) == 1
            ? DimensionManager.getWorld(tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM))
            : null;
        changed |= updateColor(stack, colorWorld);
        PendingUpgrade pending = pendingUpgrades == null ? null : pendingUpgrades.get(stack);
        if (formatted && colorWorld != null && (pending == null || !pending.canonicalRequired)) {
            changed |= recordSyncEpoch(tag, journal);
            clearPendingUpgrade(stack);
        }
        if (changed) player.inventory.markDirty();
        return changed;
    }

    static boolean applyPendingMigrations(ItemStack stack, QuantumMigrationJournal journal) {
        return (replayPendingMigrations(stack, journal) & 1) != 0;
    }

    /** Bit 0: cursor/NBT changed; bit 1: an actual binding changed, requiring canonical validation. */
    private static int replayPendingMigrations(ItemStack stack, QuantumMigrationJournal journal) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || tag.getByte(ItemNetworkQuantumTerminal.NBT_BOUND) != 1
            || tag.getLong(NBT_MIGRATION_CURSOR) >= journal.currentSequence()) return 0;
        AnchorKey original = new AnchorKey(
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM),
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X),
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y),
            tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z));
        String generation = tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION);
        QuantumMigrationJournal.Resolution resolved = journal
            .resolve(original, tag.getLong(NBT_MIGRATION_CURSOR), generation);
        boolean bindingChanged = !original.equals(resolved.anchor) || !generation.equals(resolved.generation);
        setInteger(tag, ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, resolved.anchor.getDimension());
        setInteger(tag, ItemNetworkQuantumTerminal.NBT_ANCHOR_X, resolved.anchor.getX());
        setInteger(tag, ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, resolved.anchor.getY());
        setInteger(tag, ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, resolved.anchor.getZ());
        tag.setLong(NBT_MIGRATION_CURSOR, resolved.sequence);
        setString(tag, ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, resolved.generation);
        WorldServer world = DimensionManager.getWorld(resolved.anchor.getDimension());
        if (world != null) setString(tag, "QT_BoundName", world.provider.getDimensionName());
        return 1 | (bindingChanged ? 2 : 0);
    }

    private static boolean currentBinding(ItemStack stack, World world, int[] coordinates) {
        QuantumControllerRegistry.CanonicalAnchor anchor = QuantumControllerRegistry.get(world)
            .resolveAnchor(
                QuantumControllerRegistry.pack(coordinates[1], coordinates[2], coordinates[3]),
                stack.getTagCompound()
                    .getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
        return anchor != null
            && anchor.position == QuantumControllerRegistry.pack(coordinates[1], coordinates[2], coordinates[3]);
    }

    /** Bit 0: changed; bit 1: a valid canonical binding was established. */
    private static int normalizeAnchor(ItemStack stack) {
        int[] coordinates = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (coordinates == null) return 0;
        World world = DimensionManager.getWorld(coordinates[0]);
        if (world == null) return 0;
        NBTTagCompound tag = stack.getTagCompound();
        QuantumControllerRegistry.CanonicalAnchor anchor = QuantumControllerRegistry.get(world)
            .resolveAnchor(
                QuantumControllerRegistry.pack(coordinates[1], coordinates[2], coordinates[3]),
                tag.getString(ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION));
        if (anchor == null) return 0;
        boolean changed = applyCanonicalAnchor(tag, world, anchor);
        clearPendingUpgrade(stack);
        return 2 | (changed ? 1 : 0);
    }

    private static boolean updateColor(ItemStack stack, World world) {
        NBTTagCompound tag = stack.getTagCompound();
        int color = world == null ? QuantumNetworkColor.get(stack)
            : QuantumNetworkColorRegistry.get(world)
                .color(
                    tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X),
                    tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y),
                    tag.getInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z));
        if (tag.hasKey(QuantumNetworkColor.NBT_COLOR) && tag.getInteger(QuantumNetworkColor.NBT_COLOR) == color)
            return false;
        QuantumNetworkColor.set(stack, color);
        return true;
    }

    private static boolean recordSyncEpoch(NBTTagCompound tag, QuantumMigrationJournal journal) {
        // A lazy source read may emit a color epoch. A newer journal entry still needs another item pull.
        if (tag.getLong(NBT_MIGRATION_CURSOR) < journal.currentSequence()) return false;
        long epoch = QuantumTerminalSyncEpoch.current();
        if (tag.getLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH) == epoch) return false;
        tag.setLong(ItemNetworkQuantumTerminal.NBT_SYNC_EPOCH, epoch);
        return true;
    }

    private static boolean applyCanonicalAnchor(NBTTagCompound tag, World world,
        QuantumControllerRegistry.CanonicalAnchor anchor) {
        boolean changed = setInteger(
            tag,
            ItemNetworkQuantumTerminal.NBT_ANCHOR_X,
            QuantumControllerRegistry.unpackX(anchor.position));
        changed |= setInteger(
            tag,
            ItemNetworkQuantumTerminal.NBT_ANCHOR_Y,
            QuantumControllerRegistry.unpackY(anchor.position));
        changed |= setInteger(
            tag,
            ItemNetworkQuantumTerminal.NBT_ANCHOR_Z,
            QuantumControllerRegistry.unpackZ(anchor.position));
        changed |= setString(tag, ItemNetworkQuantumTerminal.NBT_ANCHOR_GENERATION, anchor.generation);
        changed |= setString(tag, "QT_BoundName", world.provider.getDimensionName());
        if (tag.getByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA) != ItemNetworkQuantumTerminal.ANCHOR_SCHEMA) {
            tag.setByte(ItemNetworkQuantumTerminal.NBT_ANCHOR_SCHEMA, ItemNetworkQuantumTerminal.ANCHOR_SCHEMA);
            changed = true;
        }
        return changed;
    }

    private static boolean setString(NBTTagCompound tag, String key, String value) {
        if (value.equals(tag.getString(key))) return false;
        tag.setString(key, value);
        return true;
    }

    private static boolean setInteger(NBTTagCompound tag, String key, int value) {
        if (tag.getInteger(key) == value) return false;
        tag.setInteger(key, value);
        return true;
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
