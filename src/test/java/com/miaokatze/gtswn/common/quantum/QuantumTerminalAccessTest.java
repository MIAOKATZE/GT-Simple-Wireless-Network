package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.util.QualityHintCounter;
import com.miaokatze.gtswn.common.util.QuantumTerminalSecurityHints;

import appeng.api.config.SecurityPermissions;
import appeng.api.features.INetworkEncodable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.networking.security.ISecurityProvider;
import appeng.api.util.AECableType;
import appeng.api.util.IReadOnlyCollection;

public class QuantumTerminalAccessTest {

    @Test
    public void openNetworkHintsArePerPlayerAndSurviveCompressedPersistentDataReload() throws Exception {
        FixturePlayer first = player(world(), "first");
        for (int count = 0; count < 4; count++) QuantumTerminalSecurityHints.show(first, true);
        assertEquals(4, first.messages.size());
        for (IChatComponent message : first.messages) {
            assertEquals("gtswn.chat.quantum.open_network_hint", ((ChatComponentTranslation) message).getKey());
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(first.data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG), bytes);
        FixturePlayer restored = player(first.worldObj, "first");
        restored.data.setTag(
            EntityPlayer.PERSISTED_NBT_TAG,
            CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        for (int count = 4; count < 10; count++) QuantumTerminalSecurityHints.show(restored, true);
        assertEquals(6, restored.messages.size());
        QuantumTerminalSecurityHints.show(restored, true);
        assertEquals(6, restored.messages.size());
        FixturePlayer second = player(first.worldObj, "second");
        QuantumTerminalSecurityHints.show(second, true);
        assertEquals(1, second.messages.size());
        assertEquals(
            9,
            QualityHintCounter
                .consume(restored.data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG), QualityHintCounter.Kind.NODE));
    }

    @Test
    public void onlyActualOpenNetworksQualifyAndFalseSnapshotsNeverConsumeHints() throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = new IdentityTile(grid.node);
        FixturePlayer player = player(world, "actor");
        ItemStack encodedForAnotherNetwork = terminal(0, 1, 64, 2);
        encodedForAnotherNetwork.getTagCompound()
            .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, "456");
        assertTrue(QuantumTerminalAccess.canAccessAnchor(player, encodedForAnotherNetwork, world, 1, 64, 2));
        assertTrue(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        grid.booting = true;
        assertFalse(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        QuantumTerminalSecurityHints.show(player, QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        world.tile = new IdentityTile();
        assertFalse(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        QuantumTerminalSecurityHints.show(player, QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        assertFalse(QuantumTerminalSecurityHints.isOpen((World) null, 1, 64, 2));
        assertFalse(QuantumTerminalSecurityHints.isOpen(new ItemStack(new ItemNetworkQuantumTerminal())));
        assertTrue(player.data.hasNoTags());
        assertTrue(player.messages.isEmpty());
        grid.booting = false;
        world.tile = new IdentityTile(grid.node);
        QuantumNetworkSecurityRegistry.get(world)
            .enroll(world, members(1, 64, 2));
        assertFalse(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        QuantumTerminalSecurityHints.show(player, QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        assertTrue(player.data.hasNoTags());
        QuantumTerminalSecurityHints.show(player, true);
        assertEquals(1, player.messages.size());
        assertEquals(
            1,
            player.data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
                .getCompoundTag("gtswn")
                .getCompoundTag("qualityHints")
                .getInteger("openNetwork"));
    }

    @Test
    public void securitySnapshotSurvivesControllerDequantizationWithoutFalseOpenHint() throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = new IdentityTile(grid.node);
        FixturePlayer player = player(world, "actor");
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        controllers.quantizeAll(members(1, 64, 2));
        QuantumNetworkSecurityRegistry.get(world)
            .enroll(world, members(1, 64, 2));
        boolean wasOpen = QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2);
        assertFalse(wasOpen);
        controllers.dequantize(1, 64, 2);
        assertTrue(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        QuantumTerminalSecurityHints.show(player, wasOpen);
        assertTrue(player.messages.isEmpty());
        assertTrue(player.data.hasNoTags());
        wasOpen = QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2);
        world.tile = null;
        assertFalse(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        QuantumTerminalSecurityHints.show(player, wasOpen);
        assertEquals(1, player.messages.size());
    }

    @Test
    public void nativeEncodableInterfacePreservesOtherTerminalNbt() {
        ItemNetworkQuantumTerminal item = new ItemNetworkQuantumTerminal();
        assertTrue(item instanceof INetworkEncodable);
        ItemStack terminal = terminal(7, 1, 64, 2);
        terminal.getTagCompound()
            .setString("customName", "keep");
        item.setEncryptionKey(terminal, "malformed-key", "ignored");
        assertEquals("malformed-key", item.getEncryptionKey(terminal));
        assertEquals(
            "keep",
            terminal.getTagCompound()
                .getString("customName"));
        assertArrayEquals(new int[] { 7, 1, 64, 2 }, ItemNetworkQuantumTerminal.getAnchor(terminal));
    }

    @Test
    public void legacyFiveKeyTerminalReplaysChainedMovesAfterSaveReload() {
        ItemStack terminal = terminal(7, 1, 64, 2);
        QuantumMigrationJournal journal = new QuantumMigrationJournal("fixture");
        journal.append(7, members(1, 64, 2), new AnchorKey(8, 3, 70, 4));
        journal.append(8, members(3, 70, 4), new AnchorKey(9, 5, 80, 6));
        NBTTagCompound saved = new NBTTagCompound();
        journal.writeToNBT(saved);
        QuantumMigrationJournal restored = new QuantumMigrationJournal("fixture");
        restored.readFromNBT(saved);
        QuantumTerminalAccess.applyPendingMigrations(terminal, restored);
        assertArrayEquals(new int[] { 9, 5, 80, 6 }, ItemNetworkQuantumTerminal.getAnchor(terminal));
        assertEquals(
            2L,
            terminal.getTagCompound()
                .getLong(QuantumTerminalAccess.NBT_MIGRATION_CURSOR));
        QuantumTerminalAccess.applyPendingMigrations(terminal, restored);
        assertArrayEquals(new int[] { 9, 5, 80, 6 }, ItemNetworkQuantumTerminal.getAnchor(terminal));
    }

    @Test
    public void freshBindingAtRebuiltOldPositionSkipsHistoryButFollowsNextMove() throws Exception {
        FixtureWorld world = world();
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(world);
        journal.append(0, members(1, 64, 2), new AnchorKey(8, 3, 70, 4));
        ItemStack terminal = new ItemStack(new ItemNetworkQuantumTerminal());
        ItemNetworkQuantumTerminal.writeAnchor(terminal, world, 1, 64, 2);
        QuantumTerminalAccess.applyPendingMigrations(terminal, journal);
        assertArrayEquals(new int[] { 0, 1, 64, 2 }, ItemNetworkQuantumTerminal.getAnchor(terminal));
        journal.append(0, members(1, 64, 2), new AnchorKey(9, 5, 80, 6));
        QuantumTerminalAccess.applyPendingMigrations(terminal, journal);
        assertArrayEquals(new int[] { 9, 5, 80, 6 }, ItemNetworkQuantumTerminal.getAnchor(terminal));
    }

    @Test
    public void borrowingTerminalRefreshesActorEvenBeforePermissionDenial() throws Exception {
        FixtureWorld world = world();
        FixturePlayer first = player(world, "first");
        FixturePlayer borrower = player(world, "borrower");
        ItemStack terminal = terminal(7, 1, 64, 2);
        assertFalse(QuantumTerminalAccess.validate(first, terminal));
        assertEquals(
            first.id.toString(),
            terminal.getTagCompound()
                .getString(QuantumTerminalAccess.NBT_OWNER_UUID));
        assertFalse(QuantumTerminalAccess.validate(borrower, terminal));
        assertEquals(
            borrower.id.toString(),
            terminal.getTagCompound()
                .getString(QuantumTerminalAccess.NBT_OWNER_UUID));
        assertEquals(
            "borrower",
            terminal.getTagCompound()
                .getString(QuantumTerminalAccess.NBT_OWNER_NAME));
    }

    @Test
    public void enrolledPermissionsUseCurrentActorAndNativeOwnerOrSecurityRatherThanBuildGrant() throws Exception {
        FixturePlayer owner = player(world(), "owner");
        FixturePlayer borrower = player(owner.worldObj, "borrower");
        ISecurityGrid security = security(true, owner, 42);
        assertTrue(QuantumTerminalAccess.permitsEnrolled(security, true, owner, true, 42));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(security, true, borrower, false, 43));
        ISecurityGrid sharedBuild = security(true, borrower, 42);
        assertTrue(QuantumTerminalAccess.permitsEnrolled(sharedBuild, true, borrower, false, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(sharedBuild, true, borrower, true, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(sharedBuild, false, borrower, false, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(security(false, borrower, 42), true, borrower, false, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(null, true, borrower, false, 43));
        ISecurityGrid securityOnly = security(true, borrower, 42, SecurityPermissions.SECURITY);
        assertTrue(QuantumTerminalAccess.permitsEnrolled(securityOnly, true, borrower, true, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(securityOnly, true, borrower, false, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(securityOnly, true, owner, true, 43));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(securityOnly, false, borrower, true, 43));
        assertFalse(
            QuantumTerminalAccess.permitsEnrolled(
                security(false, borrower, 42, SecurityPermissions.SECURITY),
                true,
                borrower,
                true,
                43));
        ISecurityGrid noGrants = security(true, borrower, 42, new SecurityPermissions[0]);
        assertTrue(QuantumTerminalAccess.permitsEnrolled(noGrants, true, owner, true, 42));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(noGrants, true, owner, false, 42));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(noGrants, true, borrower, true, 43));
    }

    @Test
    public void sharedEnrollmentPersistsAndTransfersWithoutUnlockingAnOfflineTarget() throws Exception {
        FixtureWorld sourceWorld = world();
        FixtureWorld targetWorld = world();
        Set<Long> source = members(1, 64, 2);
        Set<Long> target = members(3, 70, 4);
        QuantumNetworkSecurityRegistry registry = QuantumNetworkSecurityRegistry.get(sourceWorld);
        assertFalse(registry.isEnrolled(source));
        registry.enroll(source);
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        QuantumNetworkSecurityRegistry restored = new QuantumNetworkSecurityRegistry("restored");
        restored.readFromNBT(saved);
        assertTrue(restored.isEnrolled(source));
        QuantumNetworkSecurityRegistry.transfer(sourceWorld, source, targetWorld, target);
        assertFalse(registry.isEnrolled(source));
        assertTrue(
            QuantumNetworkSecurityRegistry.get(targetWorld)
                .isEnrolled(target));
        assertFalse(QuantumTerminalAccess.permitsEnrolled(null, false, null, true, 42));
        QuantumNetworkSecurityRegistry.transfer(sourceWorld, source, targetWorld, target);
        assertTrue(
            QuantumNetworkSecurityRegistry.get(targetWorld)
                .isEnrolled(target));
    }

    @Test
    public void removingControllerClearsItsEnrollmentBeforeCoordinateReuse() throws Exception {
        FixtureWorld world = world();
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        controllers.quantizeAll(members(1, 64, 2));
        QuantumNetworkSecurityRegistry registry = QuantumNetworkSecurityRegistry.get(world);
        registry.enroll(members(1, 64, 2));
        controllers.dequantize(1, 64, 2);
        controllers.quantizeAll(members(1, 64, 2));
        assertFalse(registry.isEnrolled(members(1, 64, 2)));
    }

    @Test
    public void replacingControllerEnrolledBeforeQuantizationCannotInheritSavedSecurity() throws Exception {
        FixtureWorld world = world();
        world.tile = new IdentityTile();
        QuantumNetworkSecurityRegistry registry = QuantumNetworkSecurityRegistry.get(world);
        Set<Long> members = members(1, 64, 2);
        registry.enroll(world, members);
        assertTrue(registry.isEnrolled(world, members));
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        registry.readFromNBT(saved);
        world.tile = new IdentityTile();
        assertFalse(registry.isEnrolled(world, members));
    }

    @Test
    public void nativeProviderHistoryPersistsWithoutControllersAndDoesNotCopyToDifferentKeys() {
        QuantumNetworkSecurityRegistry providers = new QuantumNetworkSecurityRegistry("fixture");
        for (long key : new long[] { Long.MIN_VALUE, -1L, 0L, 123L, Long.MAX_VALUE }) providers.enrollProvider(key);
        NBTTagCompound saved = new NBTTagCompound();
        providers.writeToNBT(saved);
        QuantumNetworkSecurityRegistry restored = new QuantumNetworkSecurityRegistry("restored");
        restored.readFromNBT(saved);
        for (long key : new long[] { Long.MIN_VALUE, -1L, 0L, 123L, Long.MAX_VALUE })
            assertTrue(restored.isProviderEnrolled(key));
        assertFalse(restored.isProviderEnrolled(124L));
        assertFalse(restored.isEnrolled(members(1, 64, 2)));
        restored.enroll(members(1, 64, 2));
        restored.remove(members(1, 64, 2));
        assertTrue(restored.isProviderEnrolled(123L));
        restored.readFromNBT(new NBTTagCompound());
        assertFalse(restored.isProviderEnrolled(123L));
    }

    @Test
    public void pairedProviderPromotesNewControllerGenerationAndRemovalKeepsItLocked() throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = new IdentityTile(grid.node);
        QuantumNetworkSecurityRegistry.providerRegistry(world)
            .enrollProvider(123L);
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        assertTrue(
            QuantumNetworkSecurityRegistry.get(world)
                .isEnrolled(members(1, 64, 2)));
        assertFalse(
            QuantumTerminalAccess.canAccessAnchor(player(world, "guest"), terminal(0, 1, 64, 2), world, 1, 64, 2));
        grid.hasProvider = false;
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        world.tile = new IdentityTile(grid.node);
        assertFalse(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        grid.hasProvider = true;
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
    }

    @Test
    public void stableUnpairedProviderStaysOpenButUnknownOrBootingGridDoesNotBecomePermanentlyLocked()
        throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = new IdentityTile(grid.node);
        FixturePlayer guest = player(world, "guest");
        ItemStack terminal = terminal(0, 1, 64, 2);
        assertTrue(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        grid.booting = true;
        assertFalse(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        assertFalse(
            QuantumNetworkSecurityRegistry.get(world)
                .isEnrolled(members(1, 64, 2)));
        world.tile = new IdentityTile();
        assertFalse(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        assertFalse(
            QuantumNetworkSecurityRegistry.get(world)
                .isEnrolled(members(1, 64, 2)));
        grid.booting = false;
        world.tile = new IdentityTile(grid.node);
        assertTrue(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
    }

    private static ISecurityGrid security(boolean available, EntityPlayer allowedActor, int owner) {
        return security(available, allowedActor, owner, SecurityPermissions.BUILD);
    }

    private static ISecurityGrid security(boolean available, EntityPlayer allowedActor, int owner,
        SecurityPermissions... grants) {
        return (ISecurityGrid) Proxy.newProxyInstance(
            ISecurityGrid.class.getClassLoader(),
            new Class<?>[] { ISecurityGrid.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("isAvailable")) return available;
                if (method.getName()
                    .equals("getOwner")) return owner;
                if (method.getName()
                    .equals("hasPermission"))
                    return args[0] == allowedActor && java.util.Arrays.asList(grants)
                        .contains(args[1]);
                return null;
            });
    }

    private static Set<Long> members(int x, int y, int z) {
        return Collections.singleton(QuantumControllerRegistry.pack(x, y, z));
    }

    private static ItemStack terminal(int dimension, int x, int y, int z) {
        ItemStack stack = new ItemStack(new ItemNetworkQuantumTerminal());
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte(ItemNetworkQuantumTerminal.NBT_BOUND, (byte) 1);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_DIM, dimension);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_X, x);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Y, y);
        tag.setInteger(ItemNetworkQuantumTerminal.NBT_ANCHOR_Z, z);
        stack.setTagCompound(tag);
        return stack;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), type);
    }

    private static FixtureWorld world() throws Exception {
        FixtureWorld world = (FixtureWorld) allocate(FixtureWorld.class);
        Field provider = World.class.getDeclaredField("provider");
        provider.setAccessible(true);
        provider.set(world, new WorldProviderSurface());
        Field storage = World.class.getDeclaredField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new MapStorage(null));
        return world;
    }

    private static FixturePlayer player(World world, String name) throws Exception {
        FixturePlayer player = (FixturePlayer) allocate(FixturePlayer.class);
        player.worldObj = world;
        player.inventory = new InventoryPlayer(player);
        player.id = UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        player.name = name;
        player.data = new NBTTagCompound();
        player.messages = new ArrayList<>();
        return player;
    }

    private static class FixturePlayer extends EntityPlayerMP {

        private UUID id;
        private String name;
        private NBTTagCompound data;
        private List<IChatComponent> messages;

        private FixturePlayer() {
            super(null, null, null, null);
        }

        @Override
        public UUID getUniqueID() {
            return id;
        }

        @Override
        public String getCommandSenderName() {
            return name;
        }

        @Override
        public NBTTagCompound getEntityData() {
            return data;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message);
        }
    }

    private static class FixtureWorld extends World {

        private TileEntity tile;

        private FixtureWorld() {
            super((ISaveHandler) null, "fixture", (WorldProvider) null, (WorldSettings) null, (Profiler) null);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int id) {
            return null;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return tile != null;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (tile != null) return tile;
            throw new AssertionError("Terminal synchronization must never load a chunk");
        }
    }

    private static class IdentityTile extends TileEntity implements QuantumControllerIdentity, IGridHost {

        private String identity = "";
        private IGridNode node;

        private IdentityTile() {}

        private IdentityTile(IGridNode node) {
            this.node = node;
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
        public void securityBreak() {}

        @Override
        public String gtswn$getControllerIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setControllerIdentity(String value) {
            identity = value;
        }

        @Override
        public void markDirty() {}
    }

    private static class GridFixture {

        private boolean booting;
        private boolean hasProvider = true;
        private final IGrid grid;
        private final IGridNode node;
        private final IGridNode providerNode;

        private GridFixture() {
            IGridHost provider = (IGridHost) Proxy.newProxyInstance(
                IGridHost.class.getClassLoader(),
                new Class<?>[] { IGridHost.class, ISecurityProvider.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getSecurityKey")) return 123L;
                    return null;
                });
            providerNode = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> method.getName()
                    .equals("getMachine") ? provider : null);
            IPathingGrid path = (IPathingGrid) Proxy.newProxyInstance(
                IPathingGrid.class.getClassLoader(),
                new Class<?>[] { IPathingGrid.class },
                (proxy, method, args) -> method.getName()
                    .equals("isNetworkBooting") ? booting : null);
            grid = (IGrid) Proxy.newProxyInstance(
                IGrid.class.getClassLoader(),
                new Class<?>[] { IGrid.class },
                (proxy, method, args) -> {
                    if (method.getName()
                        .equals("getMachinesClasses"))
                        return new CollectionView<>(
                            hasProvider ? Collections.singletonList(provider.getClass()) : Collections.emptyList());
                    if (method.getName()
                        .equals("getMachines")) return new MachineSet(provider.getClass(), providerNode);
                    if (method.getName()
                        .equals("getCache")) return args[0] == IPathingGrid.class ? path : null;
                    return null;
                });
            node = (IGridNode) Proxy.newProxyInstance(
                IGridNode.class.getClassLoader(),
                new Class<?>[] { IGridNode.class },
                (proxy, method, args) -> method.getName()
                    .equals("getGrid") ? grid : null);
        }
    }

    private static class CollectionView<T> implements IReadOnlyCollection<T> {

        private final List<T> values;

        private CollectionView(List<T> values) {
            this.values = values;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public boolean isEmpty() {
            return values.isEmpty();
        }

        @Override
        public boolean contains(Object value) {
            return values.contains(value);
        }

        @Override
        public Iterator<T> iterator() {
            return values.iterator();
        }
    }

    private static class MachineSet extends CollectionView<IGridNode> implements IMachineSet {

        private final Class<? extends IGridHost> type;

        @SuppressWarnings("unchecked")
        private MachineSet(Class<?> type, IGridNode node) {
            super(Collections.singletonList(node));
            this.type = (Class<? extends IGridHost>) type;
        }

        @Override
        public Class<? extends IGridHost> getMachineClass() {
            return type;
        }
    }
}
