package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
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
import com.miaokatze.gtswn.config.Config;

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
import appeng.tile.networking.TileController;

public class QuantumTerminalAccessTest {

    @Test
    public void sourceMigrationChecksEveryNativeGridOnButKeepsGlobalAndLocalOffShortCircuits() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            FixtureWorld world = world();
            GridFixture active = new GridFixture(), paused = new GridFixture();
            IdentityTile left = identityTile(active.node), right = identityTile(paused.node);
            long a = QuantumControllerRegistry.pack(0, 64, 0), b = QuantumControllerRegistry.pack(1, 64, 0);
            world.tiles = new HashMap<>();
            world.tiles.put(a, left);
            world.tiles.put(b, right);
            NBTTagCompound saved = new NBTTagCompound();
            saved.setInteger("anchorSchema", 3);
            saved.setIntArray("controllers", new int[] { 0, 0, 64, 0, 0, 1, 64, 0 });
            NBTTagList generations = new NBTTagList(), anchors = new NBTTagList(), suspended = new NBTTagList();
            for (long position : new long[] { a, b }) {
                NBTTagCompound member = new NBTTagCompound(), anchor = new NBTTagCompound();
                String generation = position == a ? "active" : "paused";
                member.setLong("pos", position);
                member.setString("generation", generation);
                generations.appendTag(member);
                anchor.setLong("pos", position);
                anchor.setString("generation", generation);
                anchors.appendTag(anchor);
                if (position == b) {
                    NBTTagCompound pause = new NBTTagCompound();
                    pause.setString("generation", generation);
                    suspended.appendTag(pause);
                }
            }
            saved.setTag("memberGenerations", generations);
            saved.setTag("canonicalAnchors", anchors);
            saved.setTag("suspendedGenerations", suspended);
            QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
            controllers.readFromNBT(saved);
            Set<Long> source = new LinkedHashSet<>();
            source.add(a);
            source.add(b);
            QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
            policy.setSafety(world, source, true);
            int[] checks = { 0 };
            assertFalse(QuantumNetworkMigration.canManageSourceComponents(world, source, position -> {
                checks[0]++;
                return position == a;
            }));
            assertEquals(2, checks[0]);
            assertTrue(controllers.isQuantized(a));
            assertTrue(controllers.isSuspendedController(b));
            right.node = active.node;
            checks[0] = 0;
            assertTrue(QuantumNetworkMigration.canManageSourceComponents(world, source, position -> {
                checks[0]++;
                return true;
            }));
            assertEquals(1, checks[0]);
            Config.quantumSafetyEnabled = false;
            assertTrue(
                QuantumNetworkMigration.canManageSourceComponents(
                    world,
                    source,
                    position -> { throw new AssertionError("Global OFF must not ask for source permissions"); }));
            Config.quantumSafetyEnabled = true;
            policy.setSafety(world, source, false);
            left.node = null;
            right.node = null;
            assertTrue(
                QuantumNetworkMigration.canManageSourceComponents(
                    world,
                    source,
                    position -> {
                        throw new AssertionError("Local OFF must not inspect source native grids or permissions");
                    }));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    @Test
    public void coordinateRecoveryKeepsTheOriginalActorAndStillRejectsRevokedNativePermission() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            FixtureWorld world = world();
            GridFixture grid = new GridFixture();
            world.tile = identityTile(grid.node);
            QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
            long coordinate = QuantumControllerRegistry.pack(1, 64, 2);
            registry.quantize(1, 64, 2);
            String retired = registry.canonicalAnchor(coordinate).generation;
            registry.dequantize(coordinate);
            registry.quantize(1, 64, 2);
            assertNotEquals(retired, registry.resolveAnchor(coordinate, retired).generation);
            QuantumNetworkSecurityRegistry.get(world)
                .setSafety(world, members(1, 64, 2), true);
            grid.hasProvider = true;
            grid.grants.put(43, EnumSet.of(SecurityPermissions.BUILD));
            assertTrue(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
            grid.grants.clear();
            assertFalse(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
            Config.quantumSafetyEnabled = false;
            assertTrue(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    @Test
    public void liveBridgeAclDefaultsOffAndExplicitOnFailsClosedWithoutAProvider() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            FixtureWorld world = world();
            GridFixture grid = new GridFixture();
            world.tile = identityTile(grid.node);
            grid.hasProvider = false;
            assertTrue(
                QuantumConnectionSecurity.mayBridge(
                    world,
                    1,
                    64,
                    2,
                    grid.node,
                    grid.node,
                    () -> { throw new AssertionError("OFF without providers must not invent an actor ID"); }));
            grid.hasProvider = true;
            QuantumNetworkSecurityRegistry.get(world)
                .setSafety(world, members(1, 64, 2), true);
            assertFalse(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
            grid.grants.put(43, EnumSet.of(SecurityPermissions.BUILD));
            grid.booting = true;
            assertTrue(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
            assertFalse(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> -1));
            grid.hasProvider = false;
            assertFalse(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
            Config.quantumSafetyEnabled = false;
            assertTrue(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> -1));
            Config.quantumSafetyEnabled = true;
            grid.hasProvider = true;
            grid.grants.clear();
            assertFalse(QuantumConnectionSecurity.mayBridge(world, 1, 64, 2, grid.node, grid.node, () -> 43));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    @Test
    public void safetyOffDoesNotSpendOpenReminderQuotaAndOnResumesAtTheSameCount() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            FixturePlayer actor = player(world(), "actor");
            Config.quantumSafetyEnabled = true;
            QuantumTerminalSecurityHints.show(actor, true);
            NBTTagCompound before = (NBTTagCompound) actor.data.copy();
            Config.quantumSafetyEnabled = false;
            for (int i = 0; i < 12; i++) QuantumTerminalSecurityHints.show(actor, true);
            assertEquals(1, actor.messages.size());
            assertEquals(before, actor.data);
            Config.quantumSafetyEnabled = true;
            for (int i = 1; i < 10; i++) QuantumTerminalSecurityHints.show(actor, true);
            assertEquals(10, actor.messages.size());
            QuantumTerminalSecurityHints.show(actor, true);
            assertEquals(10, actor.messages.size());
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

    @Test
    public void safetySwitchImmediatelyBypassesOldEnrollmentAndUnknownGridWithoutErasingPolicy() throws Exception {
        boolean enabled = Config.quantumSafetyEnabled;
        try {
            Config.quantumSafetyEnabled = true;
            FixtureWorld world = world();
            world.tile = identityTile(null);
            FixturePlayer actor = player(world, "actor");
            ItemStack terminal = terminal(0, 1, 64, 2);
            terminal.getTagCompound()
                .setString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY, "unresolvable-old-key");
            QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
            policy.enroll(world, members(1, 64, 2));
            policy.setSafety(world, members(1, 64, 2), true);
            policy.enrollProvider(Long.MIN_VALUE);
            NBTTagCompound saved = new NBTTagCompound();
            policy.writeToNBT(saved);
            policy.readFromNBT(saved);
            assertFalse(QuantumTerminalAccess.canAccessAnchor(actor, terminal, world, 1, 64, 2));
            assertFalse(QuantumTerminalAccess.canManageAnchor(actor, terminal, world, 1, 64, 2));
            Config.quantumSafetyEnabled = false;
            assertTrue(QuantumTerminalAccess.canAccessAnchor(actor, terminal, world, 1, 64, 2));
            assertTrue(QuantumTerminalAccess.canManageAnchor(actor, terminal, world, 1, 64, 2));
            assertFalse(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
            NBTTagCompound after = new NBTTagCompound();
            policy.writeToNBT(after);
            assertEquals(saved, after);
            assertEquals(
                "unresolvable-old-key",
                terminal.getTagCompound()
                    .getString(QuantumTerminalAccess.NBT_ENCRYPTION_KEY));
            // Terminal actor metadata cannot grant access when enforcement is restored.
            Config.quantumSafetyEnabled = true;
            assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
            assertFalse(QuantumTerminalAccess.canAccessAnchor(actor, terminal, world, 1, 64, 2));
            assertFalse(QuantumTerminalAccess.canManageAnchor(actor, terminal, world, 1, 64, 2));
            policy.remove(members(1, 64, 2));
            Config.quantumSafetyEnabled = false;
            assertTrue(QuantumTerminalAccess.canAccessAnchor(actor, terminal, world, 1, 64, 2));
            Config.quantumSafetyEnabled = true;
            assertFalse(QuantumTerminalAccess.canAccessAnchor(actor, terminal, world, 1, 64, 2));
            assertFalse(policy.isEnrolled(members(1, 64, 2)));
        } finally {
            Config.quantumSafetyEnabled = enabled;
        }
    }

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
    public void onlySafetyOffNetworksQualifyRegardlessOfPairingHistoryOrBooting() throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = identityTile(grid.node);
        FixturePlayer player = player(world, "actor");
        assertTrue(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        grid.booting = true;
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        policy.enroll(world, members(1, 64, 2));
        assertTrue(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        policy.setSafety(world, members(1, 64, 2), true);
        assertFalse(QuantumTerminalSecurityHints.isOpen(world, 1, 64, 2));
        QuantumTerminalSecurityHints.show(player, false);
        assertTrue(player.data.hasNoTags());
        assertTrue(player.messages.isEmpty());
        assertFalse(QuantumTerminalSecurityHints.isOpen((World) null, 1, 64, 2));
        assertFalse(QuantumTerminalSecurityHints.isOpen(new ItemStack(new ItemNetworkQuantumTerminal())));
    }

    @Test
    public void controllerSafetySurvivesDequantizationAndRequantization() throws Exception {
        FixtureWorld world = world();
        world.tile = identityTile(new GridFixture().node);
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        controllers.quantizeAll(members(1, 64, 2));
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        policy.setSafety(world, members(1, 64, 2), true);
        controllers.dequantize(1, 64, 2);
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        controllers.quantizeAll(members(1, 64, 2));
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
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
        QuantumControllerRegistry.get(world)
            .quantize(1, 64, 2);
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
        assertFalse(
            QuantumTerminalAccess.permitsEnrolled(
                security(true, borrower, -1, SecurityPermissions.SECURITY, SecurityPermissions.BUILD),
                true,
                borrower,
                true,
                43));
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
        world.tile = identityTile(null);
        QuantumNetworkSecurityRegistry registry = QuantumNetworkSecurityRegistry.get(world);
        Set<Long> members = members(1, 64, 2);
        registry.enroll(world, members);
        assertTrue(registry.isEnrolled(world, members));
        NBTTagCompound saved = new NBTTagCompound();
        registry.writeToNBT(saved);
        registry.readFromNBT(saved);
        world.tile = identityTile(null);
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
    public void pairingHistoryNeverEnablesSafetyAndExplicitOnNeedsNoPriorEnrollment() throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = identityTile(grid.node);
        QuantumNetworkSecurityRegistry policy = QuantumNetworkSecurityRegistry.get(world);
        policy.enrollProvider(123L);
        policy.enroll(world, members(1, 64, 2));
        FixturePlayer guest = player(world, "guest");
        ItemStack terminal = terminal(0, 1, 64, 2);
        assertFalse(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        assertTrue(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        policy.remove(members(1, 64, 2));
        policy.setSafety(world, members(1, 64, 2), true);
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        assertFalse(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        grid.hasProvider = false;
        assertTrue(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
        world.tile = identityTile(grid.node);
        assertFalse(QuantumTerminalAccess.isSecurityRequired(world, 1, 64, 2));
    }

    @Test
    public void defaultOffStaysOpenWithBootingOrUnknownGrid() throws Exception {
        FixtureWorld world = world();
        GridFixture grid = new GridFixture();
        world.tile = identityTile(grid.node);
        FixturePlayer guest = player(world, "guest");
        ItemStack terminal = terminal(0, 1, 64, 2);
        assertTrue(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        grid.booting = true;
        assertTrue(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
        world.tile = identityTile(null);
        assertTrue(QuantumTerminalAccess.canAccessAnchor(guest, terminal, world, 1, 64, 2));
    }

    @Test
    public void safetyManagementUsesRealProviderOwnerOrSecurityWithoutPairingOrBuild() {
        HashMap<Integer, EnumSet<SecurityPermissions>> grants = new HashMap<>();
        ISecurityProvider provider = (ISecurityProvider) Proxy.newProxyInstance(
            ISecurityProvider.class.getClassLoader(),
            new Class<?>[] { ISecurityProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("getOwner")) return 42;
                if (method.getName()
                    .equals("readPermissions"))
                    ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0]).putAll(grants);
                return null;
            });
        assertTrue(QuantumTerminalAccess.permitsSafetyProvider(provider, 42));
        assertFalse(QuantumTerminalAccess.permitsSafetyProvider(provider, 43));
        grants.put(43, EnumSet.of(SecurityPermissions.BUILD));
        assertFalse(QuantumTerminalAccess.permitsSafetyProvider(provider, 43));
        grants.put(43, EnumSet.of(SecurityPermissions.SECURITY));
        assertTrue(QuantumTerminalAccess.permitsSafetyProvider(provider, 43));
        assertFalse(QuantumTerminalAccess.permitsSafetyProvider(provider, -1));
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
        private Map<Long, TileEntity> tiles;

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
            if (tiles != null) return tiles.containsKey(QuantumControllerRegistry.pack(x, y, z));
            return tile != null;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (tiles != null) return tiles.get(QuantumControllerRegistry.pack(x, y, z));
            if (tile != null) return tile;
            throw new AssertionError("Terminal synchronization must never load a chunk");
        }
    }

    private static IdentityTile identityTile(IGridNode node) throws Exception {
        IdentityTile tile = (IdentityTile) allocate(IdentityTile.class);
        tile.identity = "";
        tile.node = node;
        return tile;
    }

    private static class IdentityTile extends TileController implements QuantumControllerIdentity {

        private String identity = "";
        private IGridNode node;

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

        private boolean networkSafetyEnabled;

        @Override
        public boolean gtswn$isNetworkSafetyEnabled() {
            return networkSafetyEnabled;
        }

        @Override
        public void gtswn$setNetworkSafetyEnabled(boolean enabled) {
            networkSafetyEnabled = enabled;
        }

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
        private final Map<Integer, EnumSet<SecurityPermissions>> grants = new HashMap<>();
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
                    if (method.getName()
                        .equals("getOwner")) return 42;
                    if (method.getName()
                        .equals("readPermissions"))
                        ((HashMap<Integer, EnumSet<SecurityPermissions>>) args[0]).putAll(grants);
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
