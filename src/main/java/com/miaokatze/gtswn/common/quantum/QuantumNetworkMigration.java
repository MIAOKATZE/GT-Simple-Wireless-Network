package com.miaokatze.gtswn.common.quantum;

import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.util.QuantumTerminalSecurityHints;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.api.config.SecurityPermissions;
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
        if (plan == null) return false;
        boolean wasOpen = QuantumTerminalSecurityHints
            .isOpen(plan.sourceWorld, plan.source[1], plan.source[2], plan.source[3]);
        QuantumMigrationJournal journal = QuantumMigrationJournal.get(targetWorld);
        QuantumControllerRegistry sourceRegistry = QuantumControllerRegistry.get(plan.sourceWorld);
        QuantumControllerRegistry targetRegistry = QuantumControllerRegistry.get(targetWorld);
        int color = QuantumNetworkColorRegistry.get(plan.sourceWorld)
            .color(plan.source[1], plan.source[2], plan.source[3]);

        // Commit durable binding intent before disconnecting bridges or updating terminal cursors.
        targetRegistry.quantizeAll(plan.targetMembers);
        QuantumNetworkColorRegistry.get(targetWorld)
            .recolor(plan.targetMembers, color);
        journal.append(plan.source[0], plan.sourceMembers, new AnchorKey(targetWorld.provider.dimensionId, x, y, z));
        QuantumNetworkSecurityRegistry.transfer(plan.sourceWorld, plan.sourceMembers, targetWorld, plan.targetMembers);
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
        QuantumNetworkColorSync.migrateNodes(journal, plan.source[0], plan.sourceMembers);
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
        if (!QuantumTerminalAccess.canManage(player, terminal))
            return fail(player, "gtswn.chat.quantum.manage_permission_required");
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
        // Source management was authorized above; SECURITY grants do not also require BUILD.
        // The separate physical destination still requires its native BUILD permission.
        if (!mayEdit(player, terminal, sourceWorld, sourceMembers, false)
            || !mayEdit(player, terminal, targetWorld, targetMembers, true))
            return fail(player, "gtswn.chat.quantum.no_permission");
        return new Plan(sourceWorld, source, sourceMembers, targetMembers);
    }

    private static boolean mayEdit(EntityPlayerMP player, ItemStack terminal, World world, Set<Long> members,
        boolean enforceSecurity) {
        if (!player.capabilities.allowEdit) return false;
        for (long member : members) {
            int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                z = QuantumControllerRegistry.unpackZ(member);
            if (!world.canMineBlock(player, x, y, z)) return false;
            if (!world.blockExists(x, y, z)) continue;
            if (!player.canPlayerEdit(x, y, z, 1, terminal)) return false;
            TileEntity tile = world.getTileEntity(x, y, z);
            if (!(tile instanceof IGridHost)) return false;
            IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
            if (node == null || node.getGrid() == null) return false;
            ISecurityGrid security = node.getGrid()
                .getCache(ISecurityGrid.class);
            if (enforceSecurity && !QuantumConnectionSecurity.bypassesNetworkSafety(terminal, node)
                && security != null
                && !security.hasPermission(player, SecurityPermissions.BUILD)) return false;
        }
        return true;
    }

    private static Plan fail(EntityPlayerMP player, String key) {
        player.addChatMessage(new ChatComponentTranslation(key));
        return null;
    }

    private static final class Plan {

        final WorldServer sourceWorld;
        final int[] source;
        final Set<Long> sourceMembers, targetMembers;

        Plan(WorldServer sourceWorld, int[] source, Set<Long> sourceMembers, Set<Long> targetMembers) {
            this.sourceWorld = sourceWorld;
            this.source = source;
            this.sourceMembers = sourceMembers;
            this.targetMembers = targetMembers;
        }
    }
}
