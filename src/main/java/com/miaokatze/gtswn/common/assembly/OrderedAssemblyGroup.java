// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyBinding;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyGeometry;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;
import com.miaokatze.gtswn.common.assembly.pattern.AssemblyRecipeMatch;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;
import com.miaokatze.gtswn.common.assembly.pattern.PatternValidationException;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.security.MachineSource;
import appeng.me.GridAccessException;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/** One active processing pattern. Physical members own all consumable resources; this object owns only facts. */
public final class OrderedAssemblyGroup implements NativeAssemblyBinding {

    public enum Stage {
        IDLE,
        STAGED,
        RUNNING,
        LOCAL_FINISHED,
        NATIVE_ABORTED,
        REFUNDING,
        QUARANTINED
    }

    private NativeAssemblySession session;
    private boolean nativeBound;
    private MatrixAssemblyMember master;
    private final List<MatrixAssemblyMember> items;
    private final List<MatrixAssemblyMember> fluids;
    private List<MatrixAssemblyMember> members;
    private final List<UUID> memberIds = new ArrayList<>();
    private int[][] memberPositions;
    private final int memberDimension;
    private UUID controllerId;
    private int controllerDimension, controllerX, controllerY, controllerZ;
    private com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing alignment;
    private Stage stage = Stage.IDLE;
    private boolean verified;
    private long epoch = 1;
    private UUID batch;
    private String recipeKey = "";
    private int patternSlot = -1;
    // Immutable pattern definitions only, never stock/amount snapshots. Existing schema-2 records
    // rebuild this transient cache from their locked pattern slot after reload.
    private OrderedPatternLayout acceptedLayout;
    private UUID acceptedLayoutBatch;
    private ItemStack acceptedPattern;
    private String reason = "unverified";
    private boolean refunding;
    private long nativeSequence;
    private boolean genericInput;
    private final Set<MTEMultiBlockBase> controllerClaims = java.util.Collections
        .newSetFromMap(new IdentityHashMap<>());
    private MTEMultiBlockBase inputController;
    private boolean controllerConflict;
    private int[] machinePosition;
    private UUID networkOrigin;
    private int externalConnections;
    private MatrixAssemblyMember networkEntry;
    private String networkReason = "no_external_connection";
    private long networkCheckedTick = Long.MIN_VALUE;
    private boolean networkDirty = true;
    private NBTTagCompound quarantinedLedger;
    private boolean recoverableLedger;
    private int[] diagnosticItemTable = new int[0];
    private int[] diagnosticFluidTable = new int[0];
    private long[] diagnosticItemConsumed = new long[0];
    private long[] diagnosticFluidConsumed = new long[0];
    private boolean diagnosticConsumptionObserved;
    private OrderedAssemblyGroup mirrorSource;
    private Map<MatrixAssemblyMember, MatrixAssemblyMember> mirrorMembers;
    private String mirrorReason = "mirror_unbound";
    private final Map<String, NBTTagCompound> sharedRuns = new HashMap<>();
    private String sharedFailure = "";
    private OrderedAssemblyGroup nativeOwner;
    private UUID nativeBatch;
    private final Set<OrderedAssemblyGroup> mirrorConsumers = java.util.Collections
        .newSetFromMap(new IdentityHashMap<>());
    private long mirrorCheckedTick = Long.MIN_VALUE;
    private long mirrorSourceEpoch;
    private long topologyCheckedTick = Long.MIN_VALUE;
    private boolean topologyCheckResult;
    private final java.util.concurrent.atomic.AtomicLong topologyRevision = new java.util.concurrent.atomic.AtomicLong();
    private long topologyCheckedRevision = Long.MIN_VALUE;

    private OrderedAssemblyGroup(List<MatrixAssemblyMember> items, List<MatrixAssemblyMember> fluids,
        MatrixAssemblyMember master, NBTTagCompound ledger) {
        this.master = master;
        this.items = ledgerOrder(items, ledger);
        this.fluids = ledgerOrder(fluids, ledger);
        memberDimension = master.getBaseMetaTileEntity()
            .getWorld().provider.dimensionId;
        rememberMembers();
        restore(ledger);
    }

    /** Only a bounded local component is inspected, once per second by its master. Never loads chunks. */
    public static void topologyTick(MatrixAssemblyMember caller, long tick) {
        OrderedAssemblyGroup group = caller.matrixPort()
            .group();
        if (tick % 20 != 0 || group != null && group.verified && group.master() != caller) return;
        refreshTopology(caller);
        group = caller.matrixPort()
            .group();
        if (group == null || group.master() != caller) return;

    }

    public static void refreshTopology(MatrixAssemblyMember caller) {
        if (caller.matrixPort()
            .group() != null)
            caller.matrixPort()
                .group()
                .topologyChanged();
        IGregTechTileEntity origin = caller.getBaseMetaTileEntity();
        if (origin == null || origin.getWorld() == null || origin.isClientSide() || !isPresent(caller)) return;
        List<MatrixAssemblyMember> found = new ArrayList<>();
        found.add(caller);
        for (int i = 0; i < found.size() && found.size() <= 20; i++) {
            IGregTechTileEntity tile = found.get(i)
                .getBaseMetaTileEntity();
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                MatrixAssemblyMember neighbour = neighbour(tile, face);
                if (neighbour != null && !found.contains(neighbour)) found.add(neighbour);
            }
        }
        Set<OrderedAssemblyGroup> previous = new HashSet<>();
        for (MatrixAssemblyMember member : found) {
            OrderedAssemblyGroup group = member.matrixPort()
                .group();
            if (group != null) previous.add(group);
        }
        for (OrderedAssemblyGroup group : previous) group.topologyChanged();
        List<MatrixAssemblyMember> items = new ArrayList<>();
        List<MatrixAssemblyMember> fluids = new ArrayList<>();
        for (MatrixAssemblyMember member : found) (member.matrixFluid() ? fluids : items).add(member);
        Comparator<MatrixAssemblyMember> order = Comparator
            .comparingInt(
                (MatrixAssemblyMember member) -> member.getBaseMetaTileEntity()
                    .getXCoord())
            .thenComparingInt(
                member -> member.getBaseMetaTileEntity()
                    .getYCoord())
            .thenComparingInt(
                member -> member.getBaseMetaTileEntity()
                    .getZCoord());
        items.sort(order);
        fluids.sort(order);
        String topologyFault = topologyFault(items, fluids);
        if (found.size() > 20 || !topologyFault.isEmpty()) {
            for (OrderedAssemblyGroup group : previous)
                group.invalidate(topologyFault.isEmpty() ? "topology_changed" : topologyFault);
            return;
        }
        if (previous.size() == 1) {
            OrderedAssemblyGroup group = previous.iterator()
                .next();
            if (group.samePersistentMembers(items, fluids)) {
                if (!group.members.containsAll(found)) group.replaceReloadedMembers(found);
                boolean wasVerified = group.verified;
                group.verified = group.checkTopology();
                if (group.verified && group.stage == Stage.IDLE) group.reason = "";
                group.updateConnections();
                group.networkChanged();
                group.refreshNetwork();
                if (wasVerified != group.verified) group.dirty();
                return;
            }
        }
        // An in-flight group cannot change membership, master, or the physical order of its resources.
        for (OrderedAssemblyGroup group : previous) {
            if (group.stage != Stage.IDLE || !group.allEmpty() || group.hasUnloadedMember(origin.getWorld())) {
                group.invalidate("topology_changed");
                return;
            }
        }
        MatrixAssemblyMember master = null;
        for (MatrixAssemblyMember member : found) if (member.matrixMirror()) {
            if (master != null) {
                for (OrderedAssemblyGroup old : previous) old.invalidate("mirror_multiple_anchors");
                return;
            }
            master = member;
        }
        for (MatrixAssemblyMember member : found) {
            NBTTagCompound saved = member.matrixPort()
                .group() == null ? member.matrixPort()
                    .restoredLedger() : null;
            if (hasPatterns(member) || saved != null && !"IDLE".equals(saved.getString("stage"))) {
                if (master != null && master != member) {
                    for (OrderedAssemblyGroup group : previous) group.invalidate("controller_conflict");
                    return;
                }
                master = member;
            }
        }
        if (master == null && previous.size() == 1) {
            MatrixAssemblyMember incumbent = previous.iterator()
                .next()
                .master();
            if (found.contains(incumbent)) master = incumbent;
        }
        if (master == null) for (MatrixAssemblyMember member : found) if (member.matrixPort()
            .restoredLedger() != null) {
                master = member;
                break;
            }
        if (master == null) master = items.isEmpty() ? fluids.get(0) : items.get(0);
        NBTTagCompound ledger = master.matrixPort()
            .group() == null ? master.matrixPort()
                .restoredLedger() : null;
        for (OrderedAssemblyGroup old : previous) {
            if (old.master() == master && old.members.size() == found.size()) ledger = old.saveLedger();
            old.invalidate("topology_changed");
        }
        OrderedAssemblyGroup group = new OrderedAssemblyGroup(items, fluids, master, ledger);
        for (OrderedAssemblyGroup old : previous) group.epoch = Math.max(group.epoch, old.epoch + 1);
        for (MatrixAssemblyMember member : group.members) member.matrixPort()
            .bind(group);
        group.verified = group.checkTopology();
        if (group.verified && group.stage == Stage.IDLE) group.reason = "";
        group.updateConnections();
        group.refreshNetwork();
    }

    public static void structureChecked(MatrixAssemblyMember caller, NativeAssemblySession session, boolean formed) {
        refreshTopology(caller);
        OrderedAssemblyGroup group = caller.matrixPort()
            .group();
        if (group == null) return;
        if (!formed) {
            if (group.session == null && group.inputController == session.controller()) group.session = session;
            if (group.session == session) group.revokeNativeBinding();
            return;
        }
        group.bindNative(session);
    }

    /** The existing native structure callback supplies controller ownership, including unformed assembly lines. */
    public static void controllerChecked(MatrixAssemblyMember caller, MTEMultiBlockBase controller, boolean attached) {
        OrderedAssemblyGroup group = caller.matrixPort()
            .group();
        if (group == null) {
            refreshTopology(caller);
            group = caller.matrixPort()
                .group();
        }
        if (group == null) return;
        boolean changed = attached ? group.controllerClaims.add(controller) : group.controllerClaims.remove(controller);
        group.refreshController();
        if (changed) group.dirty();
    }

    private void refreshController() {
        // Controller attachment is optional diagnostic information, never a storage permit.
        controllerClaims.removeIf(controller -> !NativeAssemblyAdapter.isLive(controller));
        inputController = controllerClaims.size() == 1 ? controllerClaims.iterator()
            .next() : null;
        controllerConflict = false;
        if (inputController != null) {
            IGregTechTileEntity tile = inputController.getBaseMetaTileEntity();
            machinePosition = new int[] { tile.getWorld().provider.dimensionId, tile.getXCoord(), tile.getYCoord(),
                tile.getZCoord(), tile.getMetaTileID() };
        } else machinePosition = null;
    }

    private void bindNative(NativeAssemblySession next) {
        if (!verified) {
            revokeNativeBinding();
            return;
        }
        // This optional binding only arranges this controller's inputs. It cannot reject the storage group.
        String previousReason = reason;
        nativeBound = false;
        if (nativeLayoutMatches(next)) {
            session = next;
            nativeBound = true;
            alignment = next.controller()
                .getExtendedFacing();
        }
        reason = previousReason;
    }

    private void revokeNativeBinding() {
        nativeBound = false;
    }

    private boolean nativeLayoutMatches(NativeAssemblySession next) {
        MTEAssemblyLine controller = next.controller();
        if (controller == null || !NativeAssemblyAdapter.isLive(controller)
            || controller.mInputBusses.size() != items.size()
            || !controller.mInputBusses.containsAll(items)
            || controller.mInputHatches.size() != fluids.size()
            || !controller.mInputHatches.containsAll(fluids)) return false;
        if (!checkNativeLayout(controller.getExtendedFacing())) return false;
        orderNativeInputs(controller);
        return true;
    }

    private void rememberMembers() {
        topologyChanged();
        // Updating AE connection sides can synchronously rebuild crafting and reorder the ME intake here.
        // Keep an in-progress traversal of the same physical members stable while publishing the new order.
        List<MatrixAssemblyMember> nextMembers = new ArrayList<>(items);
        nextMembers.addAll(fluids);
        members = nextMembers;
        memberIds.clear();
        for (MatrixAssemblyMember member : members) memberIds.add(member.orderedMemberId());
        memberPositions = positions(members);
    }

    /** Reloaded instances may resume only the same UUIDs at the same positions; live duplicates never take over. */
    private boolean samePersistentMembers(List<MatrixAssemblyMember> nextItems, List<MatrixAssemblyMember> nextFluids) {
        if (nextItems.size() != items.size() || nextFluids.size() != fluids.size()) return false;
        for (int i = 0; i < members.size(); i++) {
            MatrixAssemblyMember before = members.get(i);
            MatrixAssemblyMember after = null;
            for (MatrixAssemblyMember candidate : before.matrixFluid() ? nextFluids : nextItems) if (memberIds.get(i)
                .equals(candidate.orderedMemberId())) {
                    after = candidate;
                    break;
                }
            if (after == null || before != after && isPresent(before)) return false;
            IGregTechTileEntity tile = after.getBaseMetaTileEntity();
            if (tile.getWorld().provider.dimensionId != memberDimension || memberPositions[i][0] != tile.getXCoord()
                || memberPositions[i][1] != tile.getYCoord()
                || memberPositions[i][2] != tile.getZCoord()) return false;
        }
        return true;
    }

    private void replaceReloadedMembers(List<MatrixAssemblyMember> found) {
        String previousReason = reason;
        invalidate("member_unloaded");
        for (int i = 0; i < members.size(); i++) {
            MatrixAssemblyMember before = members.get(i);
            for (MatrixAssemblyMember after : found) if (memberIds.get(i)
                .equals(after.orderedMemberId())) {
                    if (master == before) master = after;
                    if (i < items.size()) items.set(i, after);
                    else fluids.set(i - items.size(), after);
                    break;
                }
        }
        rememberMembers();
        for (MatrixAssemblyMember member : members) member.matrixPort()
            .bind(this);
        reason = previousReason;
    }

    public MatrixAssemblyMember master() {
        return master;
    }

    public boolean isMirror() {
        for (MatrixAssemblyMember member : members) if (member.matrixMirror()) return true;
        return false;
    }

    List<MatrixAssemblyMember> physicalMembers() {
        return members;
    }

    boolean topologyValid() {
        return verified && checkTopology();
    }

    boolean canChangeMirror() {
        if (!verified || stage != Stage.IDLE || !allEmpty()) return false;
        for (MatrixAssemblyMember member : members) if (hasPatterns(member)) return false;
        MatrixMirrorLink link = master.matrixPort()
            .mirrorLink();
        OrderedAssemblyGroup source = link == null ? null
            : link.source(
                master.getBaseMetaTileEntity()
                    .getWorld());
        // An unavailable old source may still own in-flight material. Keep its binding until it can be checked.
        return link == null || source != null && source.editable();
    }

    void mirrorChanged() {
        topologyChanged();
        mirrorSource = null;
        mirrorMembers = null;
        epoch++;
        for (MatrixAssemblyMember member : members) member.matrixPort()
            .refreshConnectionSides();
        dirty();
    }

    void memberChanged() {
        mirrorCheckedTick = Long.MIN_VALUE;
    }

    /** Geometry/identity changes invalidate read certificates; ordinary stock decrements do not. */
    void topologyChanged() {
        topologyRevision.incrementAndGet();
        topologyCheckedTick = Long.MIN_VALUE;
        mirrorCheckedTick = Long.MIN_VALUE;
    }

    /** Public writes and native consumption recheck every physical member, even within the same tick. */
    boolean prepareMutationAccess() {
        topologyChanged();
        if (!verified || !checkTopology()) return false;
        if (isMirror()) {
            if (!refreshMirror()) return false;
            mirrorSource.topologyChanged();
            if (!mirrorSource.verified || !mirrorSource.checkTopology()) return false;
        }
        return permitsMachineWork();
    }

    public int[] mirrorSourcePosition() {
        MatrixMirrorLink link = master.matrixPort()
            .mirrorLink();
        return link == null ? null : link.address();
    }

    private boolean refreshMirror() {
        if (!isMirror()) return true;
        long tick = master.getBaseMetaTileEntity()
            .getWorld()
            .getTotalWorldTime();
        if (mirrorCheckedTick == tick && verified
            && !networkDirty
            && mirrorSource != null
            && mirrorSource.verified
            && !mirrorSource.networkDirty
            && mirrorSource.epoch == mirrorSourceEpoch
            && isPresent(mirrorSource.master)) return true;
        mirrorMembers = null;
        mirrorSource = null;
        if (!verified || !checkTopology()) return false;
        refreshNetwork();
        if (externalConnections != 0) {
            mirrorReason = "mirror_external_connection";
            return false;
        }
        if (!allEmpty()) {
            mirrorReason = "mirror_local_materials";
            return false;
        }
        for (MatrixAssemblyMember member : members) if (hasPatterns(member)) {
            mirrorReason = "mirror_local_patterns";
            return false;
        }
        MatrixMirrorLink link = master.matrixPort()
            .mirrorLink();
        if (link == null) {
            mirrorReason = "mirror_unbound";
            return false;
        }
        OrderedAssemblyGroup source = link.source(
            master.getBaseMetaTileEntity()
                .getWorld());
        if (source == null || source == this) {
            mirrorReason = "mirror_source_unavailable";
            return false;
        }
        Map<MatrixAssemblyMember, MatrixAssemblyMember> mapping = link.mapping(source, this, master);
        if (mapping == null) {
            mirrorReason = "mirror_layout_mismatch";
            return false;
        }
        mirrorSource = source;
        mirrorMembers = mapping;
        if (source.mirrorConsumers.add(this)) source.dirty();
        source.refreshNetwork();
        // The intake's ME-origin ordering is the one recipe ordering at every destination.
        List<MatrixAssemblyMember> nextItems = mappedOrder(source.items, mapping);
        List<MatrixAssemblyMember> nextFluids = mappedOrder(source.fluids, mapping);
        if (!items.equals(nextItems) || !fluids.equals(nextFluids)) {
            items.clear();
            items.addAll(nextItems);
            fluids.clear();
            fluids.addAll(nextFluids);
            rememberMembers();
            if (nativeBound) orderNativeInputs();
            dirty();
        }
        mirrorReason = "";
        mirrorSourceEpoch = source.epoch;
        mirrorCheckedTick = tick;
        return true;
    }

    private static List<MatrixAssemblyMember> mappedOrder(List<MatrixAssemblyMember> source,
        Map<MatrixAssemblyMember, MatrixAssemblyMember> mapping) {
        List<MatrixAssemblyMember> result = new ArrayList<>();
        for (MatrixAssemblyMember original : source)
            for (Map.Entry<MatrixAssemblyMember, MatrixAssemblyMember> entry : mapping.entrySet())
                if (entry.getValue() == original) result.add(entry.getKey());
        return result;
    }

    MatrixAssemblyMember materialMember(MatrixAssemblyMember member) {
        return isMirror() ? mirrorMembers == null ? null : mirrorMembers.get(member) : member;
    }

    public MatrixAssemblyMember diagnosticMaterialMember(MatrixAssemblyMember member) {
        if (isMirror() && !refreshMirror()) return member;
        return materialMember(member);
    }

    MatrixAssemblyMember patternOwner() {
        return isMirror() && refreshMirror() ? mirrorSource.master : master;
    }

    public int externalConnectionCount() {
        refreshNetwork();
        return externalConnections;
    }

    public int[] networkEntryPosition() {
        refreshNetwork();
        if (networkEntry == null) return null;
        IGregTechTileEntity tile = networkEntry.getBaseMetaTileEntity();
        return new int[] { tile.getWorld().provider.dimensionId, tile.getXCoord(), tile.getYCoord(), tile.getZCoord() };
    }

    private void refreshNetwork() {
        if (!isPresent(master)) {
            externalConnections = 0;
            networkEntry = null;
            networkReason = "member_unloaded";
            return;
        }
        long tick = master.getBaseMetaTileEntity()
            .getWorld()
            .getTotalWorldTime();
        if (!networkDirty && networkCheckedTick == tick) return;
        networkCheckedTick = tick;
        networkDirty = false;
        Set<IGridNode> nodes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (MatrixAssemblyMember member : members) {
            if (!isPresent(member)) continue;
            IGridNode node = member.matrixPort()
                .node();
            if (node != null) nodes.add(node);
        }
        int count = 0;
        MatrixAssemblyMember entry = null;
        for (MatrixAssemblyMember member : members) {
            if (!isPresent(member)) continue;
            IGridNode node = member.matrixPort()
                .node();
            if (node == null) continue;
            for (IGridConnection connection : node.getConnections()) {
                if (!nodes.contains(connection.getOtherSide(node))) {
                    count++;
                    entry = member;
                }
            }
        }
        externalConnections = count;
        networkEntry = count == 1 ? entry : null;
        String fault = count == 0 ? "no_external_connection" : count > 1 ? "multiple_external_connections" : "";
        if (fault.isEmpty()) {
            UUID nextOrigin = entry.orderedMemberId();
            boolean pending = stage != Stage.IDLE || !allEmpty();
            if (pending && (networkOrigin == null || !networkOrigin.equals(nextOrigin))) {
                // Legacy in-flight ledgers have no origin. Adopt only when their saved physical allocation is
                // already identical; otherwise refund can recover them without guessing a new recipe order.
                if (networkOrigin == null && orderedFrom(entry, items).equals(items)
                    && orderedFrom(entry, fluids).equals(fluids)) networkOrigin = nextOrigin;
                else fault = "entry_changed";
            } else if (!pending && !nextOrigin.equals(networkOrigin)) {
                List<MatrixAssemblyMember> nextItems = orderedFrom(entry, items);
                List<MatrixAssemblyMember> nextFluids = orderedFrom(entry, fluids);
                boolean orderChanged = !items.equals(nextItems) || !fluids.equals(nextFluids);
                items.clear();
                items.addAll(nextItems);
                fluids.clear();
                fluids.addAll(nextFluids);
                networkOrigin = nextOrigin;
                rememberMembers();
                if (orderChanged) epoch++;
                if (nativeBound) orderNativeInputs();
                dirty();
            }
            if (fault.isEmpty() && !master.matrixPort()
                .getProxy()
                .isActive()) fault = "network_offline";
        }
        if (!fault.equals(networkReason)) {
            networkReason = fault;
            dirty();
        }
    }

    private static List<MatrixAssemblyMember> orderedFrom(MatrixAssemblyMember origin,
        List<MatrixAssemblyMember> input) {
        IGregTechTileEntity start = origin.getBaseMetaTileEntity();
        List<MatrixAssemblyMember> result = new ArrayList<>(input);
        result.sort(Comparator.comparingLong((MatrixAssemblyMember member) -> {
            IGregTechTileEntity tile = member.getBaseMetaTileEntity();
            return Math.abs((long) tile.getXCoord() - start.getXCoord())
                + Math.abs((long) tile.getYCoord() - start.getYCoord())
                + Math.abs((long) tile.getZCoord() - start.getZCoord());
        })
            .thenComparingInt(
                member -> member.getBaseMetaTileEntity()
                    .getXCoord())
            .thenComparingInt(
                member -> member.getBaseMetaTileEntity()
                    .getYCoord())
            .thenComparingInt(
                member -> member.getBaseMetaTileEntity()
                    .getZCoord()));
        return result;
    }

    private void orderNativeInputs() {
        if (session == null) return;
        orderNativeInputs(session.controller());
    }

    private void orderNativeInputs(MTEAssemblyLine controller) {
        if (controller.mInputBusses.size() != items.size() || !controller.mInputBusses.containsAll(items)
            || controller.mInputHatches.size() != fluids.size()
            || !controller.mInputHatches.containsAll(fluids)) return;
        boolean ordered = true;
        for (int i = 0; i < items.size(); i++) ordered &= controller.mInputBusses.get(i) == items.get(i);
        for (int i = 0; i < fluids.size(); i++) ordered &= controller.mInputHatches.get(i) == fluids.get(i);
        if (ordered) return;
        controller.mInputBusses.clear();
        for (MatrixAssemblyMember member : items)
            controller.mInputBusses.add((gregtech.api.metatileentity.implementations.MTEHatchInputBus) member);
        controller.mInputHatches.clear();
        for (MatrixAssemblyMember member : fluids)
            controller.mInputHatches.add((gregtech.api.metatileentity.implementations.MTEHatchInput) member);
    }

    void networkChanged() {
        topologyChanged();
        networkDirty = true;
        mirrorCheckedTick = Long.MIN_VALUE;
    }

    /** Storage access is independent of every downstream machine and its research/progress. */
    public boolean permitsMachineWork() {
        if (!verified || !checkTopology()
            || refunding
            || stage == Stage.QUARANTINED
            || stage == Stage.REFUNDING
            || stage == Stage.NATIVE_ABORTED) return false;
        return isMirror() ? refreshMirror() && mirrorSource.supplyReady() : supplyReady();
    }

    boolean permitsOrdinaryInput() {
        if (!permitsMachineWork()) return false;
        OrderedAssemblyGroup owner = isMirror() ? mirrorSource : this;
        return owner.stage == Stage.STAGED && owner.sharedFailure.isEmpty();
    }

    private boolean supplyReady() {
        if (!verified || !checkTopology()
            || refunding
            || stage == Stage.QUARANTINED
            || stage == Stage.REFUNDING
            || stage == Stage.NATIVE_ABORTED) return false;
        refreshNetwork();
        return networkReason.isEmpty();
    }

    public List<MatrixAssemblyMember> itemMembers() {
        return java.util.Collections.unmodifiableList(items);
    }

    public List<MatrixAssemblyMember> fluidMembers() {
        return java.util.Collections.unmodifiableList(fluids);
    }

    public Stage stage() {
        return isMirror() && refreshMirror() ? mirrorSource.stage : stage;
    }

    public String reason() {
        if (!verified) return reason;
        if (isMirror()) return refreshMirror() ? mirrorSource.reason() : mirrorReason;
        refreshNetwork();
        return networkReason.isEmpty() ? reason : networkReason;
    }

    public String recipeKey() {
        return isMirror() && refreshMirror() ? mirrorSource.recipeKey : recipeKey;
    }

    public long epoch() {
        return epoch;
    }

    public NativeAssemblySession session() {
        return session;
    }

    public int patternSlot() {
        return isMirror() && refreshMirror() ? mirrorSource.patternSlot : patternSlot;
    }

    /** Intake diagnostics only. The validated payload bounds these arrays to 16 item and 4 fluid positions. */
    void recordAcceptedDiagnostics(OrderedPatternLayout.Payload payload) {
        diagnosticItemTable = new int[payload.items()
            .size()];
        diagnosticFluidTable = new int[payload.fluids()
            .size()];
        for (int i = 0; i < diagnosticItemTable.length; i++) diagnosticItemTable[i] = payload.items()
            .get(i)
            .tableIndex();
        for (int i = 0; i < diagnosticFluidTable.length; i++) diagnosticFluidTable[i] = payload.fluids()
            .get(i)
            .tableIndex();
        diagnosticItemConsumed = new long[0];
        diagnosticFluidConsumed = new long[0];
        diagnosticConsumptionObserved = false;
    }

    public int diagnosticTableIndex(boolean fluid, int ordinal) {
        int[] indices = fluid ? diagnosticFluidTable : diagnosticItemTable;
        return ordinal < indices.length ? indices[ordinal] : -1;
    }

    public boolean diagnosticConsumptionObserved() {
        return diagnosticConsumptionObserved;
    }

    public long diagnosticConsumed(boolean fluid, int ordinal) {
        long[] amounts = fluid ? diagnosticFluidConsumed : diagnosticItemConsumed;
        return ordinal < amounts.length ? amounts[ordinal] : 0;
    }

    public int[] diagnosticMemberPosition(boolean fluid, int ordinal) {
        return memberPositions[(fluid ? items.size() : 0) + ordinal].clone();
    }

    public int[] diagnosticControllerPosition() {
        if (machinePosition != null) return Arrays.copyOf(machinePosition, 4);
        return controllerId == null ? null : new int[] { controllerDimension, controllerX, controllerY, controllerZ };
    }

    public int[] diagnosticProgress() {
        MTEMultiBlockBase machine = inputController != null ? inputController
            : session == null ? null : session.controller();
        return machine == null || !NativeAssemblyAdapter.isLive(machine) ? null
            : new int[] { machine.mProgresstime, machine.mMaxProgresstime };
    }

    /** A preview validates only the physical allocation, never a downstream recipe or research permit. */
    public String previewReason(OrderedPatternLayout layout) {
        if (isMirror()) return refreshMirror() ? mirrorSource.previewReason(layout) : mirrorReason;
        return capacityReason(layout);
    }

    private void recordConsumptionDiagnostics(long[] itemConsumed, long[] fluidConsumed) {
        diagnosticItemConsumed = Arrays.copyOf(itemConsumed, Math.min(itemConsumed.length, 16));
        diagnosticFluidConsumed = Arrays.copyOf(fluidConsumed, Math.min(fluidConsumed.length, 4));
        diagnosticConsumptionObserved = true;
    }

    public boolean editable() {
        refreshBatch();
        return !isMirror() && verified && checkTopology() && stage == Stage.IDLE && !refunding && allEmpty();
    }

    public boolean accepting() {
        refreshBatch();
        if (isMirror()) return false;
        if (!sharedFailure.isEmpty()) return false;
        if (stage != Stage.IDLE && stage != Stage.STAGED && stage != Stage.RUNNING && stage != Stage.LOCAL_FINISHED)
            return false;
        if (!supplyReady()) return false;
        if (stage == Stage.IDLE && !allEmpty()) return reject("untracked_materials");
        return true;
    }

    public void invalidate(String message) {
        topologyChanged();
        boolean wasVerified = verified;
        verified = false;
        reason = message;
        networkChanged();
        for (MatrixAssemblyMember member : members) member.matrixPort()
            .connectionMask(0);
        if (wasVerified) dirty();
    }

    private boolean allEmpty() {
        for (MatrixAssemblyMember member : members) {
            ItemStack item = member.matrixItem();
            FluidStack fluid = member.matrixFluidStack();
            if (item != null && item.stackSize > 0 || fluid != null && fluid.amount > 0) return false;
        }
        return true;
    }

    private boolean checkTopology() {
        if (master.getBaseMetaTileEntity() == null || master.getBaseMetaTileEntity()
            .getWorld() == null) return false;
        long tick = master.getBaseMetaTileEntity()
            .getWorld()
            .getTotalWorldTime();
        long revision = topologyRevision.get();
        if (topologyCheckedTick == tick && topologyCheckedRevision == revision) return topologyCheckResult;
        boolean valid = checkTopologyNow();
        // A GT notification during validation must not be overwritten by a newly cached result.
        if (topologyRevision.get() != revision) return false;
        topologyCheckedTick = tick;
        topologyCheckedRevision = revision;
        topologyCheckResult = valid;
        return valid;
    }

    /** Full bounded geometry/neighbour check, shared by stable reads in this world tick. */
    private boolean checkTopologyNow() {
        String topologyFault = topologyFault(items, fluids);
        if (!topologyFault.isEmpty()) return reject(topologyFault);
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < members.size(); i++) {
            MatrixAssemblyMember member = members.get(i);
            IGregTechTileEntity tile = member.getBaseMetaTileEntity();
            if (!isPresent(member)) return reject("member_unloaded");
            if (tile.getWorld().provider.dimensionId != memberDimension || !Arrays
                .equals(memberPositions[i], new int[] { tile.getXCoord(), tile.getYCoord(), tile.getZCoord() }))
                return reject("member_replaced");
            if (!ids.add(member.orderedMemberId())) return reject("duplicate_identity");
            if (!memberIds.get(i)
                .equals(member.orderedMemberId())) return reject("member_replaced");
            if (member.matrixPort()
                .quarantined())
                return reject(
                    member.matrixPort()
                        .fault());
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                MatrixAssemblyMember other = neighbour(tile, face);
                if (other != null && !members.contains(other)) return reject("topology_changed");
            }
        }
        return true;
    }

    /** Native order/facing constrain only this consumer, never the independent storage provider. */
    private boolean checkNativeBinding() {
        return session != null && nativeLayoutMatches(session);
    }

    private boolean checkNativeLayout(com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing facing) {
        for (MatrixAssemblyMember member : members) {
            if (member.matrixPort()
                .group() != this) return reject("controller_conflict");
            if (member.getBaseMetaTileEntity()
                .getFrontFacing() != facing.getRelativeDownInWorld()) return reject("member_facing");
        }
        int[][] physicalItems = sortedPositions(items), physicalFluids = sortedPositions(fluids);
        if (!items.isEmpty() && !NativeAssemblyGeometry.isOrderedColumn(physicalItems, facing, 1, true))
            return reject("item_column");
        if (!fluids.isEmpty() && !NativeAssemblyGeometry.isOrderedColumn(physicalFluids, facing, 1, false))
            return reject("fluid_column");
        return true;
    }

    static String topologyFault(List<MatrixAssemblyMember> items, List<MatrixAssemblyMember> fluids) {
        int mirrors = 0;
        for (MatrixAssemblyMember member : items) if (member.matrixMirror()) mirrors++;
        for (MatrixAssemblyMember member : fluids) if (member.matrixMirror()) mirrors++;
        if (mirrors > 1) return "mirror_multiple_anchors";
        if (items.size() < 2 && fluids.size() < 2) return "minimum_two";
        if (items.size() > 16) return "item_column";
        if (fluids.size() > 4) return "fluid_column";
        int[][] itemPositions = sortedPositions(items), fluidPositions = sortedPositions(fluids);
        int[][] longest = itemPositions.length >= 2 ? itemPositions : fluidPositions;
        ForgeDirection axis = longest[0][0] != longest[1][0] ? ForgeDirection.EAST
            : longest[0][1] != longest[1][1] ? ForgeDirection.UP : ForgeDirection.SOUTH;
        if (itemPositions.length != 0 && !NativeAssemblyGeometry.isOrderedColumn(itemPositions, axis, 1, true))
            return "item_column";
        if (fluidPositions.length != 0 && !NativeAssemblyGeometry.isOrderedColumn(fluidPositions, axis, 1, false))
            return "fluid_column";
        List<MatrixAssemblyMember> all = new ArrayList<>(items);
        all.addAll(fluids);
        return NativeAssemblyGeometry.isFaceConnected(positions(all)) ? "" : "disconnected_members";
    }

    private static MatrixAssemblyMember neighbour(IGregTechTileEntity tile, ForgeDirection face) {
        int x = tile.getXCoord() + face.offsetX, y = tile.getYCoord() + face.offsetY,
            z = tile.getZCoord() + face.offsetZ;
        if (!tile.getWorld()
            .blockExists(x, y, z)) return null;
        TileEntity neighbour = tile.getWorld()
            .getTileEntity(x, y, z);
        return neighbour instanceof IGregTechTileEntity gt
            && gt.getMetaTileEntity() instanceof MatrixAssemblyMember member
            && member.getBaseMetaTileEntity() == gt ? member : null;
    }

    static boolean isPresent(MatrixAssemblyMember member) {
        IGregTechTileEntity tile = member.getBaseMetaTileEntity();
        return tile != null && tile.getWorld() != null
            && tile.getWorld()
                .blockExists(tile.getXCoord(), tile.getYCoord(), tile.getZCoord())
            && tile.getWorld()
                .getTileEntity(tile.getXCoord(), tile.getYCoord(), tile.getZCoord()) == tile
            && tile.getMetaTileEntity() == member;
    }

    private boolean hasUnloadedMember(net.minecraft.world.World world) {
        for (int[] position : memberPositions)
            if (!world.blockExists(position[0], position[1], position[2])) return true;
        return false;
    }

    private static boolean hasPatterns(MatrixAssemblyMember member) {
        for (int i = 0; i < MatrixPatternInventory.SIZE; i++) if (member.matrixPort()
            .patterns()
            .getStackInSlot(i) != null) return true;
        return false;
    }

    private static List<MatrixAssemblyMember> ledgerOrder(List<MatrixAssemblyMember> members, NBTTagCompound ledger) {
        List<MatrixAssemblyMember> ordered = new ArrayList<>();
        if (ledger != null) {
            NBTTagList saved = ledger.getTagList("members", 10);
            for (int i = 0; i < saved.tagCount(); i++) {
                String id = saved.getCompoundTagAt(i)
                    .getString("id");
                for (MatrixAssemblyMember member : members) if (member.orderedMemberId()
                    .toString()
                    .equals(id) && !ordered.contains(member)) ordered.add(member);
            }
        }
        return ordered.size() == members.size() ? ordered : new ArrayList<>(members);
    }

    private static int[][] positions(List<MatrixAssemblyMember> members) {
        int[][] result = new int[members.size()][3];
        for (int i = 0; i < members.size(); i++) {
            IGregTechTileEntity tile = members.get(i)
                .getBaseMetaTileEntity();
            result[i] = new int[] { tile.getXCoord(), tile.getYCoord(), tile.getZCoord() };
        }
        return result;
    }

    private static int[][] sortedPositions(List<MatrixAssemblyMember> members) {
        int[][] positions = positions(members);
        Arrays.sort(
            positions,
            Comparator.comparingInt((int[] position) -> position[0])
                .thenComparingInt(position -> position[1])
                .thenComparingInt(position -> position[2]));
        return positions;
    }

    private boolean reject(String message) {
        reason = message;
        return false;
    }

    /** Independent storage advertises valid installed layouts, including while the current pattern is busy. */
    public void provideCrafting(ICraftingProviderHelper helper) {
        if (isMirror() || !supplyReady()) return;
        for (int i = 0; i < MatrixPatternInventory.SIZE; i++) {
            ItemStack pattern = master().matrixPort()
                .patterns()
                .getStackInSlot(i);
            if (pattern == null) continue;
            try {
                OrderedPatternLayout layout = OrderedPatternLayout.decode(
                    pattern,
                    master().getBaseMetaTileEntity()
                        .getWorld());
                if (capacityReason(layout).isEmpty())
                    helper.addCraftingOption((ICraftingProvider) master(), layout.details());
            } catch (PatternValidationException ignored) {
                // Invalid/unauthorized patterns remain visible in their physical management slots.
            }
        }
    }

    private String capacityReason(OrderedPatternLayout layout) {
        if (layout.items()
            .size() > items.size()
            || layout.fluids()
                .size() > fluids.size())
            return "not_enough_members";
        for (OrderedPatternLayout.Input fluid : layout.fluids()) {
            if (fluid.amount()
                > ((gregtech.api.metatileentity.implementations.MTEHatchInput) fluids.get(fluid.kindOrdinal()))
                    .getCapacity())
                return "fluid_capacity";
        }
        return "";
    }

    public boolean accept(ICraftingPatternDetails details, InventoryCrafting table) {
        if (!accepting()) return false;
        try {
            OrderedPatternLayout.decode(details);
            for (int i = 0; i < MatrixPatternInventory.SIZE; i++) {
                ItemStack pattern = master().matrixPort()
                    .patterns()
                    .getStackInSlot(i);
                if (pattern != null && ItemStack.areItemStacksEqual(pattern, details.getPattern())) {
                    OrderedPatternLayout layout = OrderedPatternLayout.decode(
                        pattern,
                        master().getBaseMetaTileEntity()
                            .getWorld());
                    return acceptInstalled(layout, table, i);
                }
            }
            return reject("pattern_not_installed");
        } catch (PatternValidationException ex) {
            return reject(
                ex.reason()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT));
        }
    }

    private boolean acceptInstalled(OrderedPatternLayout layout, InventoryCrafting table, int slot) {
        if (!accepting()) return false;
        long intakeEpoch = epoch;
        OrderedPatternLayout.Payload payload;
        try {
            if (stage != Stage.IDLE && (patternSlot < 0 || !ItemStack.areItemStacksEqual(
                master.matrixPort()
                    .patterns()
                    .getStackInSlot(patternSlot),
                layout.pattern()))) return reject("different_pattern");
            payload = layout.validateTable(table);
            String capacityReason = capacityReason(layout);
            if (!capacityReason.isEmpty()) return reject(capacityReason);
        } catch (PatternValidationException ex) {
            return reject(
                ex.reason()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT));
        }
        // Check every physical position in long space and allocate detached results before the first write.
        // Equal ingredients in distinct pattern positions deliberately remain in distinct members.
        List<ItemStack> itemCopies = new ArrayList<>();
        List<FluidStack> fluidCopies = new ArrayList<>();
        for (OrderedPatternLayout.Input input : payload.items()) {
            ItemStack next = input.itemStack(), stored = items.get(input.kindOrdinal())
                .matrixItem();
            if (stored != null && stored.stackSize > 0) {
                if (stored.getItem() != next.getItem() || stored.getItemDamage() != next.getItemDamage()
                    || !ItemStack.areItemStackTagsEqual(stored, next)) return reject("member_resource_conflict");
                long amount = (long) stored.stackSize + next.stackSize;
                if (amount > Integer.MAX_VALUE) return reject("amount_overflow");
                next.stackSize = (int) amount;
            }
            itemCopies.add(next);
        }
        for (OrderedPatternLayout.Input input : payload.fluids()) {
            FluidStack next = input.fluidStack(), stored = fluids.get(input.kindOrdinal())
                .matrixFluidStack();
            if (stored != null && stored.amount > 0) {
                if (!stored.isFluidEqual(next)) return reject("member_resource_conflict");
                long amount = (long) stored.amount + next.amount;
                if (amount > Integer.MAX_VALUE) return reject("amount_overflow");
                next.amount = (int) amount;
            }
            fluidCopies.add(next);
        }
        if (!prepareMutationAccess() || !accepting() || intakeEpoch != epoch) return false;
        UUID nextBatch = batch == null ? UUID.randomUUID() : batch;
        if (acceptedLayout == null || !nextBatch.equals(acceptedLayoutBatch)) {
            acceptedLayout = layout;
            acceptedLayoutBatch = nextBatch;
            acceptedPattern = layout.pattern()
                .copy();
        }
        if (stage == Stage.IDLE) recordAcceptedDiagnostics(payload);
        for (int i = 0; i < itemCopies.size(); i++) items.get(i)
            .matrixSetItem(itemCopies.get(i));
        for (int i = 0; i < fluidCopies.size(); i++) fluids.get(i)
            .matrixSetFluid(fluidCopies.get(i));
        batch = nextBatch;
        genericInput = true;
        recipeKey = "";
        patternSlot = slot;
        stage = Stage.STAGED;
        reason = "";
        try {
            dirty();
            requestConsumerChecks();
        } catch (RuntimeException ex) {
            stage = Stage.QUARANTINED;
            reason = "notification_failed";
            GTSimpleWirelessNetwork.LOG
                .error("Matrix input committed; materials retained after notification failure", ex);
        }
        return true;
    }

    private void requestConsumerChecks() {
        refreshController();
        for (MTEMultiBlockBase controller : controllerClaims)
            if (controller.mMachine) controller.scheduleRecipeCheckImmediate();
        mirrorConsumers.removeIf(
            consumer -> !isPresent(consumer.master) || consumer.master.matrixPort()
                .group() != consumer);
        for (OrderedAssemblyGroup consumer : mirrorConsumers) {
            if (consumer.refreshMirror() && consumer.mirrorSource == this) {
                consumer.refreshController();
                for (MTEMultiBlockBase controller : consumer.controllerClaims)
                    if (controller.mMachine) controller.scheduleRecipeCheckImmediate();
            }
        }
    }

    @Override
    public boolean validateNativeState(NativeAssemblySession nativeSession) {
        if (!permitsMachineWork()) return false;
        String previousReason = reason;
        boolean layout = nativeLayoutMatches(nativeSession);
        reason = previousReason;
        OrderedAssemblyGroup owner = isMirror() ? mirrorSource : this;
        return layout && owner.stage == Stage.STAGED && owner.sharedFailure.isEmpty();
    }

    @Override
    public boolean acceptsNativeCandidate(NativeAssemblySession nativeSession, RecipeAssemblyLine recipe) {
        if (!validateNativeState(nativeSession)) return false;
        OrderedAssemblyGroup owner = isMirror() ? mirrorSource : this;
        try {
            // GT already selected this candidate from its own research sources. The independent
            // provider checks only its accepted pattern, not the research catalogue or uniqueness.
            if (!AssemblyRecipeMatch.matchesCandidate(owner.acceptedLayout(), recipe)) return false;
            // Only the immediate synchronous consumption callback may affect this exact batch.
            nativeOwner = owner;
            nativeBatch = owner.batch;
            return true;
        } catch (PatternValidationException | IllegalStateException rejected) {
            return false;
        }
    }

    private OrderedPatternLayout acceptedLayout() {
        if (acceptedLayout == null || !java.util.Objects.equals(batch, acceptedLayoutBatch)) {
            if (batch == null || patternSlot < 0 || patternSlot >= MatrixPatternInventory.SIZE)
                throw new IllegalStateException("Missing accepted matrix pattern identity");
            ItemStack pattern = master.matrixPort()
                .patterns()
                .getStackInSlot(patternSlot);
            acceptedLayout = OrderedPatternLayout.decode(
                pattern,
                master.getBaseMetaTileEntity()
                    .getWorld());
            acceptedLayoutBatch = batch;
            acceptedPattern = pattern.copy();
        }
        return acceptedLayout;
    }

    @Override
    public boolean validateNativeConsumption(NativeAssemblySession nativeSession) {
        if (!prepareMutationAccess() || !validateNativeState(nativeSession)) return false;
        OrderedAssemblyGroup owner = isMirror() ? mirrorSource : this;
        return nativeOwner == owner && nativeBatch != null
            && nativeBatch.equals(owner.batch)
            && owner.acceptedLayout != null
            && ItemStack.areItemStacksEqual(
                owner.acceptedPattern,
                owner.master.matrixPort()
                    .patterns()
                    .getStackInSlot(owner.patternSlot));
    }

    @Override
    public void onNativeConsumption(NativeAssemblySession nativeSession, RecipeAssemblyLine recipe, int parallel,
        long[] itemConsumed, long[] fluidConsumed, boolean complete) {
        OrderedAssemblyGroup owner = nativeOwner;
        if (owner == null || nativeBatch == null || !nativeBatch.equals(owner.batch)) return;
        nativeOwner = null;
        nativeBatch = null;
        owner.recordConsumptionDiagnostics(itemConsumed, fluidConsumed);
        if (!complete || parallel != 1) {
            owner.sharedFailure = "partial_consumption";
            owner.stage = Stage.QUARANTINED;
            owner.reason = "partial_consumption";
        }
        // Do not settle here: GT may still inspect real inputs before its synchronous consume sequence returns.
        owner.dirty();
    }

    @Override
    public void onNativeFinished(NativeAssemblySession nativeSession) {
        // Materials already left storage. Completion must not touch a later batch.
    }

    @Override
    public void onNativeStopped(NativeAssemblySession nativeSession, String message) {
        // A downstream stop cannot change the identity or ownership of stock still in storage.
    }

    public void ordinaryStarted(MTEMultiBlockBase controller) {}

    public void ordinaryFinished(MTEMultiBlockBase controller, String failure) {}

    /** Refresh before intake/edit as native consumers may decrement real objects without a setter callback. */
    private void refreshBatch() {
        if (isMirror() || !verified || refunding || !checkTopology()) return;
        if ((stage == Stage.STAGED || stage == Stage.RUNNING || stage == Stage.LOCAL_FINISHED)
            && sharedFailure.isEmpty()
            && allEmpty()) {
            stage = Stage.IDLE;
            batch = null;
            acceptedLayout = null;
            acceptedLayoutBatch = null;
            acceptedPattern = null;
            recipeKey = "";
            patternSlot = -1;
            genericInput = false;
            sharedRuns.clear();
            reason = "";
            epoch++;
            dirty();
        }
    }

    public void tick() {
        if (!verified) return;
        if (isMirror()) refreshMirror();
        else {
            refreshBatch();
            refreshNetwork();
        }
    }

    /** Transfer only actual unconsumed contents. AE CPU jobs are not cancelled by this action. */
    public void refund() {
        // Recovery/refund has its own quarantine policy; refresh topology without applying the
        // normal supply gate, which deliberately rejects these recoverable states.
        topologyChanged();
        if (isMirror()) {
            if (refreshMirror()) mirrorSource.refund();
            return;
        }
        if (!verified || !checkTopology() || quarantinedLedger != null && !recoverableLedger) return;
        if (stage == Stage.IDLE && allEmpty()) return;
        refreshNetwork();
        if (externalConnections != 1 || !master.matrixPort()
            .getProxy()
            .isActive()) return;
        stage = Stage.REFUNDING;
        refunding = true;
        try {
            MatrixAssemblyPort port = master().matrixPort();
            MachineSource source = new MachineSource(
                (appeng.api.networking.security.IActionHost) master().getBaseMetaTileEntity());
            for (MatrixAssemblyMember member : members) {
                ItemStack item = member.matrixItem();
                if (item != null && item.stackSize > 0) {
                    var remainder = port.getProxy()
                        .getStorage()
                        .getItemInventory()
                        .injectItems(AEItemStack.create(item.copy()), Actionable.MODULATE, source);
                    member.matrixSetItem(remainder == null ? null : remainder.getItemStack());
                    member.matrixPort()
                        .changed();
                }
                FluidStack fluid = member.matrixFluidStack();
                if (fluid != null && fluid.amount > 0) {
                    var remainder = port.getProxy()
                        .getStorage()
                        .getFluidInventory()
                        .injectItems(AEFluidStack.create(fluid.copy()), Actionable.MODULATE, source);
                    member.matrixSetFluid(remainder == null ? null : remainder.getFluidStack());
                    member.matrixPort()
                        .changed();
                }
            }
            reason = allEmpty() ? "refund_cpu_still_waiting" : "refund_network_full";
            if (allEmpty()) {
                quarantinedLedger = null;
                recoverableLedger = false;
                stage = Stage.IDLE;
                batch = null;
                recipeKey = "";
                patternSlot = -1;
                genericInput = false;
                sharedFailure = "";
            }
        } catch (GridAccessException ex) {
            reason = "refund_network_offline";
        } finally {
            refunding = false;
            dirty();
        }
    }

    public NBTTagCompound saveLedger() {
        if (quarantinedLedger != null) return (NBTTagCompound) quarantinedLedger.copy();
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("distributionSchema", 2);
        if (controllerId != null) {
            tag.setIntArray(
                "controllerPosition",
                new int[] { controllerDimension, controllerX, controllerY, controllerZ });
            tag.setString("controller", controllerId.toString());
        }
        tag.setString(
            "master",
            master.orderedMemberId()
                .toString());
        tag.setString("stage", stage.name());
        tag.setLong("epoch", epoch);
        tag.setLong("nativeSequence", nativeSequence);
        tag.setBoolean("genericInput", genericInput);
        tag.setString("sharedFailure", sharedFailure);
        NBTTagList runs = new NBTTagList();
        for (Map.Entry<String, NBTTagCompound> entry : sharedRuns.entrySet()) {
            NBTTagCompound run = (NBTTagCompound) entry.getValue()
                .copy();
            run.setString("key", entry.getKey());
            runs.appendTag(run);
        }
        tag.setTag("sharedRuns", runs);
        if (machinePosition != null) tag.setIntArray("machinePosition", machinePosition.clone());
        if (networkOrigin != null) tag.setString("networkOrigin", networkOrigin.toString());
        tag.setIntArray("diagnosticItemTable", diagnosticItemTable.clone());
        tag.setIntArray("diagnosticFluidTable", diagnosticFluidTable.clone());
        tag.setTag("diagnosticItemConsumed", saveDiagnosticAmounts(diagnosticItemConsumed));
        tag.setTag("diagnosticFluidConsumed", saveDiagnosticAmounts(diagnosticFluidConsumed));
        tag.setBoolean("diagnosticConsumptionObserved", diagnosticConsumptionObserved);
        if (batch != null) tag.setString("batch", batch.toString());
        tag.setString("recipe", recipeKey);
        tag.setInteger("patternSlot", patternSlot);
        tag.setString("reason", reason);
        NBTTagList ids = new NBTTagList();
        for (int i = 0; i < memberIds.size(); i++) {
            NBTTagCompound member = new NBTTagCompound();
            member.setString(
                "id",
                memberIds.get(i)
                    .toString());
            member.setIntArray("position", memberPositions[i].clone());
            ids.appendTag(member);
        }
        tag.setTag("members", ids);
        return tag;
    }

    private void restore(NBTTagCompound tag) {
        acceptedLayout = null;
        acceptedLayoutBatch = null;
        acceptedPattern = null;
        topologyChanged();
        if (tag == null) return;
        try {
            if (tag.hasKey("distributionSchema")
                && (!tag.hasKey("distributionSchema", 3) || tag.getInteger("distributionSchema") != 2))
                throw new IllegalArgumentException("unknown_schema");
            boolean legacy = !tag.hasKey("distributionSchema");
            quarantinedLedger = null;
            recoverableLedger = false;
            sharedRuns.clear();
            if (tag.hasKey("controller") || tag.hasKey("controllerPosition")) {
                int[] position = tag.getIntArray("controllerPosition");
                if (position.length != 4 || position[0] != memberDimension)
                    throw new IllegalArgumentException("controller_replaced");
                controllerId = UUID.fromString(tag.getString("controller"));
                controllerDimension = position[0];
                controllerX = position[1];
                controllerY = position[2];
                controllerZ = position[3];
            }
            if (tag.hasKey("master") && !master.orderedMemberId()
                .toString()
                .equals(tag.getString("master"))) throw new IllegalArgumentException("member_replaced");
            NBTTagList ids = tag.getTagList("members", 10);
            if (ids.tagCount() != memberIds.size()) throw new IllegalArgumentException("topology_changed");
            for (int i = 0; i < ids.tagCount(); i++) {
                NBTTagCompound member = ids.getCompoundTagAt(i);
                if (!memberIds.get(i)
                    .toString()
                    .equals(member.getString("id"))
                    || !java.util.Arrays.equals(memberPositions[i], member.getIntArray("position")))
                    throw new IllegalArgumentException("member_replaced");
            }
            stage = Stage.valueOf(tag.getString("stage"));
            epoch = tag.getLong("epoch");
            nativeSequence = tag.getLong("nativeSequence");
            genericInput = tag.getBoolean("genericInput");
            sharedFailure = tag.getString("sharedFailure");
            if (tag.hasKey("sharedRuns") && !tag.hasKey("sharedRuns", 9))
                throw new IllegalArgumentException("invalid_saved_data");
            NBTTagList runs = tag.getTagList("sharedRuns", 10);
            if (runs.tagCount() > 0 && runs.func_150303_d() != 10)
                throw new IllegalArgumentException("invalid_saved_data");
            for (int i = 0; i < runs.tagCount(); i++) {
                NBTTagCompound run = runs.getCompoundTagAt(i);
                if (run.getIntArray("position").length != 5 || run.getIntArray("position")[0] != memberDimension
                    || !run.hasKey("key", 8)
                    || sharedRuns.put(run.getString("key"), (NBTTagCompound) run.copy()) != null)
                    throw new IllegalArgumentException("invalid_saved_data");
            }
            if (tag.hasKey("machinePosition")) {
                machinePosition = tag.getIntArray("machinePosition");
                if (machinePosition.length != 5 || machinePosition[0] != memberDimension)
                    throw new IllegalArgumentException("controller_replaced");
            }
            if (tag.hasKey("networkOrigin")) networkOrigin = UUID.fromString(tag.getString("networkOrigin"));
            diagnosticItemTable = loadDiagnosticIndices(tag, "diagnosticItemTable", 16);
            diagnosticFluidTable = loadDiagnosticIndices(tag, "diagnosticFluidTable", 4);
            diagnosticItemConsumed = loadDiagnosticAmounts(tag, "diagnosticItemConsumed", 16);
            diagnosticFluidConsumed = loadDiagnosticAmounts(tag, "diagnosticFluidConsumed", 4);
            diagnosticConsumptionObserved = tag.getBoolean("diagnosticConsumptionObserved")
                && diagnosticItemConsumed.length == items.size()
                && diagnosticFluidConsumed.length == fluids.size();
            recipeKey = tag.getString("recipe");
            patternSlot = tag.getInteger("patternSlot");
            reason = tag.getString("reason");
            if (tag.hasKey("batch")) batch = UUID.fromString(tag.getString("batch"));
            if ((stage == Stage.STAGED || stage == Stage.RUNNING
                || stage == Stage.LOCAL_FINISHED
                || stage == Stage.NATIVE_ABORTED)
                && (batch == null || patternSlot < 0
                    || patternSlot >= MatrixPatternInventory.SIZE
                    || legacy && !genericInput && !isMirror() && !recipeKey.matches("v1:[0-9a-f]{64}")))
                throw new IllegalArgumentException("invalid_saved_data");
            if (legacy && genericInput
                && !tag.hasKey("sharedRuns")
                && (machinePosition == null || controllerId != null
                    || stage == Stage.RUNNING && sharedRuns.isEmpty()
                    || stage == Stage.LOCAL_FINISHED && !tag.hasKey("sharedRuns")
                    || stage == Stage.NATIVE_ABORTED))
                throw new IllegalArgumentException("invalid_saved_data");
            // Known legacy running records describe downstream work, not ownership of remaining stock.
            // Preserve abnormal/partial states and their original recovery evidence.
            if (stage == Stage.QUARANTINED || stage == Stage.NATIVE_ABORTED || !sharedFailure.isEmpty()) {
                quarantinedLedger = (NBTTagCompound) tag.copy();
                recoverableLedger = true;
                if (!sharedFailure.isEmpty()) stage = Stage.QUARANTINED;
            } else {
                sharedRuns.clear();
                controllerId = null;
                machinePosition = null;
                recipeKey = "";
                if (stage != Stage.REFUNDING) reason = "";
                if (stage == Stage.RUNNING || stage == Stage.LOCAL_FINISHED) stage = Stage.STAGED;
            }
        } catch (IllegalArgumentException ex) {
            recoverableLedger = false;
            quarantinedLedger = (NBTTagCompound) tag.copy();
            if (patternSlot < 0 || patternSlot >= MatrixPatternInventory.SIZE) patternSlot = -1;
            stage = Stage.QUARANTINED;
            reason = ex.getMessage();
        }
    }

    private void dirty() {
        for (MatrixAssemblyMember member : members) if (isPresent(member)) member.matrixPort()
            .changed();
        if (isMirror() && mirrorSource != null && isPresent(mirrorSource.master)) mirrorSource.master.matrixPort()
            .changed();
    }

    private static NBTTagList saveDiagnosticAmounts(long[] amounts) {
        NBTTagList list = new NBTTagList();
        for (long amount : amounts) list.appendTag(new NBTTagLong(amount));
        return list;
    }

    /** Optional display data never changes the restored batch's authorization or state. */
    private static int[] loadDiagnosticIndices(NBTTagCompound tag, String key, int limit) {
        if (!tag.hasKey(key, 11)) return new int[0];
        int[] values = tag.getIntArray(key);
        if (values.length > limit) return new int[0];
        for (int value : values) if (value < -1) return new int[0];
        return values.clone();
    }

    private static long[] loadDiagnosticAmounts(NBTTagCompound tag, String key, int limit) {
        if (!tag.hasKey(key, 9)) return new long[0];
        NBTTagList list = (NBTTagList) tag.getTag(key);
        if (list.tagCount() > limit || list.tagCount() > 0 && list.func_150303_d() != 4) return new long[0];
        NBTTagList copy = (NBTTagList) list.copy();
        long[] values = new long[copy.tagCount()];
        for (int i = values.length - 1; i >= 0; i--)
            values[i] = ((NBTBase.NBTPrimitive) copy.removeTag(i)).func_150291_c();
        return values;
    }

    private void updateConnections() {
        for (MatrixAssemblyMember member : members) {
            OrderedAssemblyGroup owner = member.matrixPort()
                .group();
            if (owner != null && owner != this) continue;
            int mask = 0;
            if (verified && member.matrixPort()
                .group() == this && isPresent(member)) {
                IGregTechTileEntity tile = member.getBaseMetaTileEntity();
                for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                    MatrixAssemblyMember other = neighbour(tile, face);
                    if (other != null && other.matrixPort()
                        .group() == this && members.contains(other)) mask |= face.flag;
                }
            }
            member.matrixPort()
                .connectionMask(mask);
        }
    }
}
