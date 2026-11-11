// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.quantum;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.LongPredicate;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.diagnostics.BusinessDiagnostics;
import com.miaokatze.gtswn.common.diagnostics.BusinessDiagnostics.Type;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.monitoring.QuantumProfileAdapter;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.QuantumTerminalSecurityHints;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.ISecurityGrid;
import appeng.tile.networking.TileController;

/** Server-side migration transaction. Gesture duration, ray tracing and distance belong to the packet handler. */
public final class QuantumNetworkMigration {

    private QuantumNetworkMigration() {}

    public static boolean isCandidate(World world, int x, int y, int z) {
        return world != null && world.blockExists(x, y, z)
            && world.getTileEntity(x, y, z) instanceof TileController
            && !QuantumControllerRegistry.get(world)
                .isQuantized(x, y, z);
    }

    public static boolean canStart(EntityPlayerMP player, ItemStack terminal, World targetWorld, int x, int y, int z) {
        return validate(player, terminal, targetWorld, x, y, z) != null;
    }

    public static boolean migrate(EntityPlayerMP player, ItemStack terminal, World targetWorld, int x, int y, int z) {
        Plan plan = validate(player, terminal, targetWorld, x, y, z);
        if (plan == null) {
            if (DebugTestGate.probesActive())
                BusinessDiagnostics.at(targetWorld, x, y, z, Type.QUANTUM_MIGRATE_REJECT, "");
            return false;
        }
        boolean wasOpen = QuantumTerminalSecurityHints
            .isOpen(plan.sourceWorld, plan.source[1], plan.source[2], plan.source[3]);
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(targetWorld);
        QuantumControllerRegistry sourceRegistry = QuantumControllerRegistry.get(plan.sourceWorld);
        QuantumControllerRegistry targetRegistry = QuantumControllerRegistry.get(targetWorld);
        int color = QuantumNetworkColorRegistry.get(plan.sourceWorld)
            .color(plan.source[1], plan.source[2], plan.source[3]);
        QuantumControllerRegistry.CanonicalAnchor sourceAnchor = sourceRegistry
            .canonicalAnchor(QuantumControllerRegistry.pack(plan.source[1], plan.source[2], plan.source[3]));
        if (sourceAnchor == null) return false;
        Set<Long> bindingMembers = sourceRegistry.migrationBindingMembers(plan.sourceMembers);

        // Commit durable binding intent before disconnecting bridges or updating terminal cursors.
        if (!targetRegistry
            .quantizeTransferred(plan.targetMembers, QuantumControllerRegistry.pack(x, y, z), sourceAnchor.generation))
            return false;
        QuantumNetworkColorRegistry.get(targetWorld)
            .recolor(plan.targetMembers, color);
        AnchorKey destination = new AnchorKey(targetWorld.provider.dimensionId, x, y, z);
        if (plan.fromKey == null)
            journal.appendClusterMigration(plan.source[0], bindingMembers, destination, sourceAnchor.generation);
        else journal.appendClusterMigration(
            plan.source[0],
            bindingMembers,
            destination,
            sourceAnchor.generation,
            plan.fromKey,
            plan.toKey);
        QuantumNetworkSecurityRegistry.transfer(plan.sourceWorld, plan.sourceMembers, targetWorld, plan.targetMembers);
        // Replay while the initiating item still carries its original anchor/cursor; writeAnchor advances the cursor.
        QuantumTerminalAccess.applyPendingMigrations(terminal, journal);
        // An explicit OFF-to-ON move may encode only this held item; its unpaired copies receive no mapping.
        if (plan.fromKey == null && plan.toKey != null)
            ((ItemNetworkQuantumTerminal) terminal.getItem()).setEncryptionKey(terminal, plan.toKey, "");
        ItemNetworkQuantumTerminal.writeAnchor(terminal, targetWorld, x, y, z);
        player.inventory.markDirty();
        for (long member : plan.sourceMembers) {
            sourceRegistry.dequantize(member);
            QuantumOverloadCountdown.check(
                new AnchorKey(
                    plan.source[0],
                    QuantumControllerRegistry.unpackX(member),
                    QuantumControllerRegistry.unpackY(member),
                    QuantumControllerRegistry.unpackZ(member)),
                0L,
                false);
        }

        // Registries include unloaded local chunks. Unloaded dimensions replay the same journal on first access.
        for (WorldServer world : DimensionManager.getWorlds()) {
            finishStep(
                "incorporations in dimension " + world.provider.dimensionId,
                () -> QuantumIncorporationRegistry.get(world)
                    .applyPendingMigrations(world, journal));
        }
        QuantumNetworkColorSync.migrateNodes(journal, plan.source[0], bindingMembers);
        for (long member : plan.sourceMembers) {
            int sx = QuantumControllerRegistry.unpackX(member), sy = QuantumControllerRegistry.unpackY(member),
                sz = QuantumControllerRegistry.unpackZ(member);
            if (!plan.sourceWorld.blockExists(sx, sy, sz)) continue;
            TileEntity tile = plan.sourceWorld.getTileEntity(sx, sy, sz);
            if (tile instanceof QuantumControllerIdentity) {
                ((QuantumControllerIdentity) tile).gtswn$setControllerIdentity("");
                tile.markDirty();
            }
            finishStep(
                "source controller " + member,
                () -> QuantumControllerEventHandler.restoreAllSides(plan.sourceWorld, sx, sy, sz));
        }
        for (long member : plan.targetMembers) finishStep(
            "target controller " + member,
            () -> QuantumControllerEventHandler.applyConnectionFilter(
                targetWorld,
                QuantumControllerRegistry.unpackX(member),
                QuantumControllerRegistry.unpackY(member),
                QuantumControllerRegistry.unpackZ(member)));
        QuantumNetworkStatsCache.clear();
        QuantumNetworkData.clearCache();
        if (Config.quantumMigrationSuccessAnnouncement) MinecraftServer.getServer()
            .getConfigurationManager()
            .sendChatMsg(
                new ChatComponentTranslation(
                    "gtswn.chat.quantum.migrated",
                    plan.sourceWorld.provider.getDimensionName(),
                    plan.source[0],
                    plan.source[1],
                    plan.source[2],
                    plan.source[3],
                    targetWorld.provider.getDimensionName(),
                    targetWorld.provider.dimensionId,
                    x,
                    y,
                    z));
        QuantumTerminalSecurityHints.show(player, wasOpen);
        if (DebugTestGate.probesActive()) BusinessDiagnostics.at(targetWorld, x, y, z, Type.QUANTUM_MIGRATE, "");
        return true;
    }

    /** Durable intent is already committed; a failed runtime bridge must not prevent the remaining cleanup. */
    static void finishStep(String description, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            GTSimpleWirelessNetwork.LOG
                .error("[Quantum migration] Committed migration runtime update failed: " + description, failure);
        }
    }

    private static Plan validate(EntityPlayerMP player, ItemStack terminal, World targetWorld, int x, int y, int z) {
        if (player == null || targetWorld == null
            || targetWorld.isRemote
            || terminal == null
            || !(terminal.getItem() instanceof ItemNetworkQuantumTerminal)) return null;
        // Synchronize before the source snapshot, then require the native owner or SECURITY permission.
        if (!QuantumTerminalAccess.canManage(player, terminal)) {
            if (!QuantumProfileAdapter.profilePermits(player, terminal, true))
                return fail(player, QuantumProfileAdapter.profileDenialKey(player, terminal, true));
            int[] deniedAnchor = ItemNetworkQuantumTerminal.getAnchor(terminal);
            if (deniedAnchor != null) {
                World deniedWorld = DimensionManager.getWorld(deniedAnchor[0]);
                if (deniedWorld != null && deniedWorld.blockExists(deniedAnchor[1], deniedAnchor[2], deniedAnchor[3])) {
                    String denial = QuantumTerminalAccess
                        .nativeDenialKey(terminal, deniedWorld, deniedAnchor[1], deniedAnchor[2], deniedAnchor[3]);
                    if (!"gtswn.chat.quantum.no_permission".equals(denial)) return fail(player, denial);
                }
            }
            return fail(player, "gtswn.chat.quantum.manage_permission_required");
        }
        int[] source = ItemNetworkQuantumTerminal.getAnchor(terminal);
        if (source == null) return fail(player, "gtswn.chat.quantum.need_bind");
        WorldServer sourceWorld = DimensionManager.getWorld(source[0]);
        if (sourceWorld == null || !sourceWorld.blockExists(source[1], source[2], source[3]))
            return fail(player, "gtswn.chat.quantum.migration_source_invalid");
        QuantumControllerRegistry sourceRegistry = QuantumControllerRegistry.get(sourceWorld);
        long sourcePosition = QuantumControllerRegistry.pack(source[1], source[2], source[3]);
        if (!sourceRegistry.isQuantized(sourcePosition)
            || !sourceRegistry.isCurrentController(sourceWorld, sourcePosition))
            return fail(player, "gtswn.chat.quantum.migration_source_invalid");
        Set<Long> sourceMembers = sourceRegistry.registeredStructure(sourcePosition);
        if (!isCandidate(targetWorld, x, y, z)) return fail(player, "gtswn.chat.quantum.migration_target_invalid");
        TileEntity target = targetWorld.getTileEntity(x, y, z);
        if (target.isInvalid() || !ItemNetworkQuantumTerminal.isControllerActive((TileController) target))
            return fail(player, "gtswn.chat.quantum.not_active");
        Set<Long> targetMembers = QuantumControllerRegistry.floodControllers(targetWorld, x, y, z);
        QuantumControllerRegistry targetRegistry = QuantumControllerRegistry.get(targetWorld);
        for (long member : targetMembers) {
            if (targetRegistry.isQuantized(member)) return fail(player, "gtswn.chat.quantum.migration_target_invalid");
            int tx = QuantumControllerRegistry.unpackX(member), ty = QuantumControllerRegistry.unpackY(member),
                tz = QuantumControllerRegistry.unpackZ(member);
            // Unknown neighboring chunks could hide more controllers: never quantize a partial target structure.
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                if (ty + side.offsetY >= 0 && ty + side.offsetY < targetWorld.getHeight()
                    && !targetWorld.blockExists(tx + side.offsetX, ty + side.offsetY, tz + side.offsetZ))
                    return fail(player, "gtswn.chat.quantum.migration_target_invalid");
        }
        for (long member : sourceMembers) {
            if (!sourceRegistry.isCurrentController(sourceWorld, member))
                return fail(player, "gtswn.chat.quantum.migration_source_invalid");
        }
        if (!canManageSourceComponents(
            sourceWorld,
            sourceMembers,
            member -> QuantumTerminalAccess.canManageAnchor(
                player,
                terminal,
                sourceWorld,
                QuantumControllerRegistry.unpackX(member),
                QuantumControllerRegistry.unpackY(member),
                QuantumControllerRegistry.unpackZ(member))))
            return fail(player, "gtswn.chat.quantum.manage_permission_required");
        // Every actual source grid is authorized above; SECURITY grants do not also require BUILD.
        // The separate physical destination still requires its native BUILD permission.
        String editDenial = editDenial(player, terminal, sourceWorld, sourceMembers, false);
        if (editDenial == null) editDenial = editDenial(player, terminal, targetWorld, targetMembers, true);
        if (editDenial != null) return fail(player, editDenial);
        String fromKey = null, toKey = null;
        boolean protectedSource = Config.quantumSafetyEnabled
            && QuantumTerminalAccess.isNetworkSafetyEnabled(sourceWorld, source[1], source[2], source[3]);
        boolean protectedDestination = protectedSource
            || Config.quantumSafetyEnabled && QuantumTerminalAccess.isNetworkSafetyEnabled(targetWorld, x, y, z);
        if (protectedDestination) {
            boolean alreadyPairedDestination = !protectedSource
                && QuantumTerminalAccess.isPairedToNativeGrid(terminal, targetWorld, x, y, z)
                && QuantumTerminalAccess.canAccessAnchor(player, terminal, targetWorld, x, y, z)
                && QuantumSecurityPreflight.preservesNativeSecurity(targetWorld, targetMembers);
            if (!alreadyPairedDestination) {
                toKey = QuantumTerminalAccess.trustedPairingKey(player, targetWorld, targetMembers);
                if (toKey == null) return fail(player, "gtswn.chat.quantum.migration_pairing_required");
                boolean trustedSource = QuantumTerminalAccess
                    .isPairedToNativeGrid(terminal, sourceWorld, source[1], source[2], source[3])
                    && QuantumTerminalAccess.canChangeNetworkSafety(player, terminal);
                if (trustedSource)
                    fromKey = ((ItemNetworkQuantumTerminal) terminal.getItem()).getEncryptionKey(terminal);
                else if (protectedSource) return fail(player, "gtswn.chat.quantum.migration_pairing_required");
            }
        }
        if (QuantumMigrationJournal.get(targetWorld)
            .currentSequence() == Long.MAX_VALUE) return fail(player, "gtswn.chat.quantum.migration_target_invalid");
        return new Plan(sourceWorld, source, sourceMembers, targetMembers, fromKey, toKey);
    }

    /** OFF retains the existing short circuit; ON authorizes each actual source grid once before mutation. */
    static boolean canManageSourceComponents(World world, Set<Long> members, LongPredicate authority) {
        if (!Config.quantumSafetyEnabled || members.isEmpty()) return true;
        long first = members.iterator()
            .next();
        if (!QuantumTerminalAccess.isSecurityRequired(
            world,
            QuantumControllerRegistry.unpackX(first),
            QuantumControllerRegistry.unpackY(first),
            QuantumControllerRegistry.unpackZ(first))) return true;
        Set<IGrid> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (long member : members) {
            int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                z = QuantumControllerRegistry.unpackZ(member);
            if (!world.blockExists(x, y, z)) return false;
            TileEntity tile = world.getTileEntity(x, y, z);
            if (!(tile instanceof TileController)) return false;
            IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            IGrid grid = node == null ? null : node.getGrid();
            if (grid == null || (checked.add(grid) && !authority.test(member))) return false;
        }
        return true;
    }

    private static String editDenial(EntityPlayerMP player, ItemStack terminal, World world, Set<Long> members,
        boolean enforceSecurity) {
        if (!player.capabilities.allowEdit) return "gtswn.chat.quantum.world_edit_denied";
        for (long member : members) {
            int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                z = QuantumControllerRegistry.unpackZ(member);
            if (!world.canMineBlock(player, x, y, z)) return "gtswn.chat.quantum.world_edit_denied";
            if (!world.blockExists(x, y, z)) continue;
            if (!player.canPlayerEdit(x, y, z, 1, terminal)) return "gtswn.chat.quantum.world_edit_denied";
            TileEntity tile = world.getTileEntity(x, y, z);
            if (!(tile instanceof IGridHost)) return "gtswn.chat.quantum.security_unavailable";
            IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            if (node == null || node.getGrid() == null) return "gtswn.chat.quantum.security_unavailable";
            ISecurityGrid security = node.getGrid()
                .getCache(ISecurityGrid.class);
            if (enforceSecurity && !QuantumConnectionSecurity.bypassesNetworkSafety(terminal, node)
                && security != null
                && !security.hasPermission(player, SecurityPermissions.BUILD))
                return "gtswn.chat.quantum.no_permission";
        }
        return null;
    }

    private static Plan fail(EntityPlayerMP player, String key) {
        player.addChatMessage(new ChatComponentTranslation(key));
        return null;
    }

    private static final class Plan {

        final WorldServer sourceWorld;
        final int[] source;
        final Set<Long> sourceMembers, targetMembers;
        final String fromKey, toKey;

        Plan(WorldServer sourceWorld, int[] source, Set<Long> sourceMembers, Set<Long> targetMembers, String fromKey,
            String toKey) {
            this.sourceWorld = sourceWorld;
            this.source = source;
            this.sourceMembers = sourceMembers;
            this.targetMembers = targetMembers;
            this.fromKey = fromKey;
            this.toKey = toKey;
        }
    }
}
