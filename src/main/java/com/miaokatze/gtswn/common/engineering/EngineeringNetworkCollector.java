package com.miaokatze.gtswn.common.engineering;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.AnchorKey;
import com.miaokatze.gtswn.common.quantum.AnchorReachability;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkStatsCache;
import com.miaokatze.gtswn.common.quantum.QuantumSecurityScopeIndex;
import com.miaokatze.gtswn.common.quantum.QuantumTerminalAccess;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

import appeng.api.networking.IGrid;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.covers.Cover;

/** Server-only, bounded read of existing registries and live covers. No machine inventory traversal. */
final class EngineeringNetworkCollector {

    final List<Node> nodes = new ArrayList<>();
    QuantumNetworkData quantum;
    String status = "ok";
    boolean truncated;
    private final long deadline = System.nanoTime() + 3_000_000L;
    private int inspected;

    private boolean budget() {
        if (nodes.size() >= EngineeringSnapshot.MAX_NODES || ++inspected > 4096 || System.nanoTime() > deadline) {
            truncated = true;
            return false;
        }
        return true;
    }

    void link(EntityPlayerMP player, PacketEngineeringSubscribe view, boolean axis) {
        // Link terminals never grant a remote-world read; only naturally loaded, visible indexed positions.
        if (view.dim != player.dimension) {
            status = "denied";
            return;
        }
        World world = player.worldObj;
        for (Map.Entry<Long, Byte> entry : WirelessNodeRegistry.get(world)
            .readViewInXRange(
                EngineeringGrid.minBlock(view.minCellX, view.gtGrid, axis),
                EngineeringGrid.maxBlockExclusive(view.maxCellX, view.gtGrid, axis) - 1)
            .entrySet()) {
            if (!budget()) break;
            int x = WirelessNodeIndexCodec.unpackX(entry.getKey()), y = WirelessNodeIndexCodec.unpackY(entry.getKey());
            int z = WirelessNodeIndexCodec.unpackZ(entry.getKey());
            int cellX = EngineeringGrid.cellOfBlock(x, view.gtGrid, axis);
            int cellZ = EngineeringGrid.cellOfBlock(z, view.gtGrid, axis);
            if (cellX < view.minCellX || cellX > view.maxCellX
                || cellZ < view.minCellZ
                || cellZ > view.maxCellZ
                || !world.blockExists(x, y, z)) continue;
            TileEntity tile = world.getTileEntity(x, y, z);
            if (!(tile instanceof IGregTechTileEntity gt)) continue;
            UUID owner = gt.getOwnerUuid();
            String ownerId = owner == null ? "" : owner.toString();
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                Cover cover = gt.getCoverAtSide(side);
                if (!(cover instanceof GTswnCoverWirelessBase wireless)) continue;
                if (nodes.size() >= EngineeringSnapshot.MAX_NODES) {
                    truncated = true;
                    break;
                }
                nodes.add(
                    new Node(
                        1,
                        view.dim,
                        x,
                        y,
                        z,
                        ownerId,
                        gt.getOwnerName(),
                        side.name(),
                        true,
                        wireless.nodeTypeId(),
                        wireless.dashboardVoltage(),
                        wireless.getAmperage(),
                        wireless.dashboardStored(),
                        wireless.dashboardCapacity(),
                        0,
                        0,
                        0,
                        0,
                        0));
            }
        }
        nodes.sort(NodePresentation.ORDER);
    }

    void quantum(EntityPlayerMP player, ItemStack stack) {
        // Management-only safety access does not expose a native network's read metrics or node locations.
        if (!QuantumTerminalAccess.validate(player, stack) && !QuantumTerminalAccess.canManage(player, stack)) {
            status = "denied";
            return;
        }
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) {
            status = "offline";
            return;
        }
        quantum = new QuantumNetworkData();
        quantum.anchorDim = anchor[0];
        quantum.anchorX = anchor[1];
        quantum.anchorY = anchor[2];
        quantum.anchorZ = anchor[3];
        WorldServer world = DimensionManager.getWorld(anchor[0]);
        if (world == null) {
            status = "offline";
            return;
        }
        AnchorReachability.Result reach = AnchorReachability
            .resolve(world, anchor[1], anchor[2], anchor[3], true, true);
        if (reach.status != AnchorReachability.Status.OK) {
            status = "offline";
            return;
        }
        try {
            IGrid grid = ((IGridProxyable) reach.anchorTE).getProxy()
                .getGrid();
            QuantumNetworkStatsCache.Snapshot stats = QuantumNetworkStatsCache
                .getOrCompute(new AnchorKey(anchor[0], anchor[1], anchor[2], anchor[3]), world, grid);
            if (stats == null) {
                status = "offline";
                return;
            }
            quantum.online = true;
            quantum.totalChannels = stats.totalChannels;
            quantum.usedChannels = stats.usedChannels;
            quantum.quantumNodeCount = stats.quantumNodeCount;
            quantum.incorporationCount = stats.incorporationCount;
            quantum.incorporationChannels = stats.incorporationChannels;
            quantum.channelsInfinite = QuantumControllerRegistry.isChannelsInfinite();
            Set<Long> members = stats.getStructure();
            // Weak live membership index; no loadedTileEntityList copy or TE scans.
            for (TileEntityNetworkQuantumNode node : QuantumSecurityScopeIndex.loadedOwners()) {
                if (!budget()) break;
                if (node == null || node.isInvalid()
                    || node.getWorldObj() == null
                    || node.getWorldObj().isRemote
                    || !node.hasAnchor()
                    || node.getAnchorDim() != anchor[0]
                    || !members.contains(
                        QuantumControllerRegistry.pack(node.getAnchorX(), node.getAnchorY(), node.getAnchorZ())))
                    continue;
                nodes.add(
                    new Node(
                        2,
                        node.getWorldObj().provider.dimensionId,
                        node.xCoord,
                        node.yCoord,
                        node.zCoord,
                        node.getPlacerUUID(),
                        node.getPlacerName(),
                        node.getOfflineReason()
                            .name(),
                        node.isLinked(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        node.getAnchorDim(),
                        node.getAnchorX(),
                        node.getAnchorY(),
                        node.getAnchorZ(),
                        node.getBridgeUsedChannels()));
            }
            WorldServer[] targets = DimensionManager.getWorlds();
            Arrays.sort(targets, Comparator.comparingInt(target -> target.provider.dimensionId));
            for (WorldServer target : targets) {
                int remaining = EngineeringSnapshot.MAX_NODES - nodes.size();
                if (remaining <= 0 || !budget()) break;
                for (QuantumIncorporationRegistry.Entry entry : QuantumIncorporationRegistry.get(target)
                    .snapshotSource(anchor[0], members, remaining + 1)) {
                    if (!budget()) break;
                    nodes.add(
                        new Node(
                            3,
                            target.provider.dimensionId,
                            QuantumControllerRegistry.unpackX(entry.position),
                            QuantumControllerRegistry.unpackY(entry.position),
                            QuantumControllerRegistry.unpackZ(entry.position),
                            entry.owner(),
                            entry.ownerName(),
                            "",
                            entry.isConnected(),
                            0,
                            0,
                            0,
                            0,
                            0,
                            entry.dim,
                            entry.ax,
                            entry.ay,
                            entry.az,
                            entry.usedChannels()));
                }
            }
        } catch (appeng.me.GridAccessException offline) {
            status = "offline";
        }
        nodes.sort(NodePresentation.ORDER);
    }
}
