package com.miaokatze.gtswn.common.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
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
import com.miaokatze.gtswn.common.assembly.pattern.AssemblyRecipeKey;
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
import gregtech.api.metatileentity.implementations.MTEHatchDataAccess;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.AssemblyLineUtils;
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
    private final List<MatrixAssemblyMember> members;
    private final List<UUID> memberIds = new ArrayList<>();
    private final Set<RecipeAssemblyLine> observedAuthorizedRecipes = new HashSet<>();
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
    private int[] diagnosticItemTable = new int[0];
    private int[] diagnosticFluidTable = new int[0];
    private long[] diagnosticItemConsumed = new long[0];
    private long[] diagnosticFluidConsumed = new long[0];
    private boolean diagnosticConsumptionObserved;

    private OrderedAssemblyGroup(List<MatrixAssemblyMember> items, List<MatrixAssemblyMember> fluids,
        MatrixAssemblyMember master, NBTTagCompound ledger) {
        this.master = master;
        this.items = ledgerOrder(items, ledger);
        this.fluids = ledgerOrder(fluids, ledger);
        members = new ArrayList<>();
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
        Set<RecipeAssemblyLine> authorized = new HashSet<>(group.authorizedRecipes());
        if (!group.observedAuthorizedRecipes.equals(authorized)) {
            group.observedAuthorizedRecipes.clear();
            group.observedAuthorizedRecipes.addAll(authorized);
            caller.matrixPort()
                .changed();
        }
    }

    public static void refreshTopology(MatrixAssemblyMember caller) {
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
        controllerClaims.removeIf(controller -> !NativeAssemblyAdapter.isLive(controller));
        MTEMultiBlockBase formed = null;
        for (MTEMultiBlockBase claim : controllerClaims) if (claim.mMachine) {
            if (formed != null && formed != claim) {
                controllerConflict = true;
                reject("controller_conflict");
                return;
            }
            formed = claim;
        }
        controllerConflict = false;
        MTEMultiBlockBase next = formed;
        if (next == null && inputController != null && controllerClaims.contains(inputController))
            next = inputController;
        if (next == null && controllerClaims.size() == 1) next = controllerClaims.iterator()
            .next();
        if (next == null) {
            inputController = null;
            return;
        }
        boolean pending = stage != Stage.IDLE || !allEmpty();
        IGregTechTileEntity tile = next.getBaseMetaTileEntity();
        if (pending && (inputController != null && inputController != next
            || machinePosition != null && !sameMachinePosition(tile))) {
            controllerConflict = true;
            reject("controller_conflict");
            return;
        }
        if (!pending && (controllerId != null || session != null)
            && (!(next instanceof MTEAssemblyLine) || controllerId != null && !sameControllerPosition(tile)
                || session != null && session.controller() != next)) {
            controllerId = null;
            session = null;
            nativeBound = false;
            alignment = null;
        }
        inputController = next;
        if ("controller_conflict".equals(reason)) reason = "";
        if (machinePosition == null || !sameMachinePosition(tile))
            machinePosition = new int[] { tile.getWorld().provider.dimensionId, tile.getXCoord(), tile.getYCoord(),
                tile.getZCoord(), tile.getMetaTileID() };
    }

    private boolean sameMachinePosition(IGregTechTileEntity tile) {
        return machinePosition[0] == tile.getWorld().provider.dimensionId && machinePosition[1] == tile.getXCoord()
            && machinePosition[2] == tile.getYCoord()
            && machinePosition[3] == tile.getZCoord()
            && machinePosition[4] == tile.getMetaTileID();
    }

    private void bindNative(NativeAssemblySession next) {
        if (!verified) {
            if (session == next) revokeNativeBinding();
            return;
        }
        List<MatrixAssemblyMember> nextItems = new ArrayList<>();
        List<MatrixAssemblyMember> nextFluids = new ArrayList<>();
        for (Object input : next.controller().mInputBusses) {
            if (!(input instanceof MatrixAssemblyMember member) || member.matrixFluid()) {
                revokeNativeBinding();
                reject("mixed_inputs");
                return;
            }
            nextItems.add(member);
        }
        for (Object input : next.controller().mInputHatches) {
            if (!(input instanceof MatrixAssemblyMember member) || !member.matrixFluid()) {
                revokeNativeBinding();
                reject("mixed_inputs");
                return;
            }
            nextFluids.add(member);
        }
        if (nextItems.size() != items.size() || nextFluids.size() != fluids.size()
            || !items.containsAll(nextItems)
            || !fluids.containsAll(nextFluids)) {
            if (session == next) revokeNativeBinding();
            reject("mixed_inputs");
            return;
        }
        if (session != null && session != next && nativeBound && NativeAssemblyAdapter.isLive(session.controller())) {
            reject("controller_conflict");
            return;
        }
        boolean pending = stage != Stage.IDLE || !allEmpty();
        if (controllerId != null && (!controllerId.equals(next.controllerId()) || !sameControllerPosition(
            next.controller()
                .getBaseMetaTileEntity()))
            || pending && controllerId == null) {
            revokeNativeBinding();
            reject("controller_conflict");
            return;
        }
        session = next;
        controllerId = next.controllerId();
        IGregTechTileEntity tile = next.controller()
            .getBaseMetaTileEntity();
        controllerDimension = tile.getWorld().provider.dimensionId;
        controllerX = tile.getXCoord();
        controllerY = tile.getYCoord();
        controllerZ = tile.getZCoord();
        alignment = next.controller()
            .getExtendedFacing();
        // Only references from this exact, fully registered group are reordered. Native recipe matching and
        // consumption then use the same ME-origin positions as the physical intake and diagnostics.
        orderNativeInputs();
        if (checkNativeBinding()) nativeBound = true;
        else revokeNativeBinding();
        if (nativeBound && stage == Stage.RUNNING
            && (next.controller().mMaxProgresstime == 0 || nativeSequence != next.runSequence()
                || !"RUNNING".equals(next.lastNativeEvent()))) {
            stage = Stage.NATIVE_ABORTED;
            reason = "restart_native_state_missing";
        }
        if (nativeBound && (stage == Stage.IDLE || stage == Stage.STAGED || stage == Stage.RUNNING)) reason = "";
        dirty();
    }

    private void revokeNativeBinding() {
        if (!nativeBound) return;
        nativeBound = false;
        if (isPresent(master)) master.matrixPort()
            .changed();
    }

    private boolean sameControllerPosition(IGregTechTileEntity next) {
        return controllerDimension == next.getWorld().provider.dimensionId && controllerX == next.getXCoord()
            && controllerY == next.getYCoord()
            && controllerZ == next.getZCoord();
    }

    private void rememberMembers() {
        members.clear();
        members.addAll(items);
        members.addAll(fluids);
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
        MTEAssemblyLine controller = session.controller();
        if (controller.mInputBusses.size() != items.size() || !controller.mInputBusses.containsAll(items)
            || controller.mInputHatches.size() != fluids.size()
            || !controller.mInputHatches.containsAll(fluids)) return;
        controller.mInputBusses.clear();
        for (MatrixAssemblyMember member : items)
            controller.mInputBusses.add((gregtech.api.metatileentity.implementations.MTEHatchInputBus) member);
        controller.mInputHatches.clear();
        for (MatrixAssemblyMember member : fluids)
            controller.mInputHatches.add((gregtech.api.metatileentity.implementations.MTEHatchInput) member);
    }

    private boolean assemblyLineMode() {
        return inputController instanceof MTEAssemblyLine || session != null || controllerId != null;
    }

    void networkChanged() {
        networkDirty = true;
    }

    /** A missing/ambiguous connection pauses existing native progress as well as future intake and consumption. */
    public boolean permitsMachineWork() {
        if (!verified || refunding
            || stage == Stage.QUARANTINED
            || stage == Stage.REFUNDING
            || stage == Stage.NATIVE_ABORTED) return false;
        refreshController();
        if (controllerConflict || inputController == null
            || !inputController.mMachine
            || !inputController.mInputBusses.containsAll(items)
            || !inputController.mInputHatches.containsAll(fluids)) return false;
        refreshNetwork();
        return networkReason.isEmpty();
    }

    boolean permitsOrdinaryInput() {
        return genericInput && !assemblyLineMode() && stage == Stage.STAGED && permitsMachineWork();
    }

    public List<MatrixAssemblyMember> itemMembers() {
        return java.util.Collections.unmodifiableList(items);
    }

    public List<MatrixAssemblyMember> fluidMembers() {
        return java.util.Collections.unmodifiableList(fluids);
    }

    public Stage stage() {
        return stage;
    }

    public String reason() {
        if (!verified) return reason;
        refreshNetwork();
        if (!networkReason.isEmpty()) return networkReason;
        refreshController();
        if (controllerConflict) return "controller_conflict";
        if (inputController == null || !inputController.mMachine) return "structure_incomplete";
        if (!inputController.mInputBusses.containsAll(items) || !inputController.mInputHatches.containsAll(fluids))
            return "mixed_inputs";
        if (assemblyLineMode() && (!nativeBound || session == null)) return "structure_incomplete";
        if (assemblyLineMode() && !session.controller()
            .protectsExcessItem()) return "output_protection_required";
        if (assemblyLineMode() && !session.controller()
            .getBaseMetaTileEntity()
            .isAllowedToWork()) return "native_disabled";
        return reason;
    }

    public String recipeKey() {
        return recipeKey;
    }

    public long epoch() {
        return epoch;
    }

    public NativeAssemblySession session() {
        return session;
    }

    public int patternSlot() {
        return patternSlot;
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

    /** Preview uses the current native authorization set without changing the group's rejection or lifecycle state. */
    public String previewReason(OrderedPatternLayout layout) {
        if (!assemblyLineMode()) return capacityReason(layout);
        if (session == null || !nativeBound) return "structure_incomplete";
        try {
            AssemblyRecipeMatch.bind(layout, authorizedRecipes());
            return capacityReason(layout);
        } catch (PatternValidationException rejected) {
            return rejected.reason()
                .name()
                .toLowerCase(java.util.Locale.ROOT);
        }
    }

    private void recordConsumptionDiagnostics(long[] itemConsumed, long[] fluidConsumed) {
        diagnosticItemConsumed = Arrays.copyOf(itemConsumed, Math.min(itemConsumed.length, 16));
        diagnosticFluidConsumed = Arrays.copyOf(fluidConsumed, Math.min(fluidConsumed.length, 4));
        diagnosticConsumptionObserved = true;
    }

    public boolean editable() {
        return verified && stage == Stage.IDLE && !refunding && allEmpty();
    }

    public boolean accepting() {
        if (stage != Stage.IDLE && stage != Stage.STAGED && stage != Stage.RUNNING && stage != Stage.LOCAL_FINISHED)
            return false;
        if (!permitsMachineWork()) return false;
        if (stage == Stage.IDLE && !allEmpty()) return reject("untracked_materials");
        if (!assemblyLineMode()) return true;
        return session != null && nativeBound
            && session.readyForPatternAdvertisement()
            && checkNativeBinding()
            && session.controller()
                .getBaseMetaTileEntity()
                .isAllowedToWork();
    }

    public void invalidate(String message) {
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

    /** Native order and facing remain a separate permit; a configurable topology alone grants no native access. */
    private boolean checkNativeBinding() {
        if (session == null || !NativeAssemblyAdapter.isLive(session.controller())) return false;
        if (session.controller()
            .getExtendedFacing() != alignment) return reject("topology_changed");
        ForgeDirection axis = NativeAssemblyGeometry.lengthAxis(alignment);
        if (axis.offsetY != 0) return reject("item_column");
        if (!session.controller().mInputBusses.equals(items) || !session.controller().mInputHatches.equals(fluids))
            return reject("mixed_inputs");
        for (MatrixAssemblyMember member : members) {
            if (member.matrixPort()
                .group() != this) return reject("controller_conflict");
            if (member.getBaseMetaTileEntity()
                .getFrontFacing() != alignment.getRelativeDownInWorld()) return reject("member_facing");
        }
        int[][] physicalItems = sortedPositions(items), physicalFluids = sortedPositions(fluids);
        if (!items.isEmpty() && !NativeAssemblyGeometry.isOrderedColumn(physicalItems, alignment, 1, true))
            return reject("item_column");
        if (!fluids.isEmpty() && !NativeAssemblyGeometry.isOrderedColumn(physicalFluids, alignment, 1, false))
            return reject("fluid_column");
        return true;
    }

    static String topologyFault(List<MatrixAssemblyMember> items, List<MatrixAssemblyMember> fluids) {
        if (items.size() < 2 && fluids.size() < 2) return "minimum_two";
        if (items.size() > 16) return "item_column";
        if (fluids.size() > 4) return "fluid_column";
        int[][] itemPositions = sortedPositions(items), fluidPositions = sortedPositions(fluids);
        int[][] longest = itemPositions.length >= 2 ? itemPositions : fluidPositions;
        ForgeDirection axis = longest[0][0] != longest[1][0] ? ForgeDirection.EAST : ForgeDirection.SOUTH;
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

    private static boolean isPresent(MatrixAssemblyMember member) {
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

    private List<RecipeAssemblyLine> authorizedRecipes() {
        List<RecipeAssemblyLine> result = new ArrayList<>();
        if (session == null || !nativeBound) return result;
        MTEAssemblyLine controller = session.controller();
        ItemStack stick = controller.getStackInSlot(1);
        if (AssemblyLineUtils.isItemDataStick(stick)) result.addAll(AssemblyLineUtils.findALRecipeFromDataStick(stick));
        for (MTEHatchDataAccess hatch : controller.mDataAccessHatches) {
            if (com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter.isLive(hatch))
                result.addAll(hatch.getAssemblyLineRecipes());
        }
        return result;
    }

    /** Only formed, currently authorized recipes are advertised; busy media retain their catalogue entries. */
    public void provideCrafting(ICraftingProviderHelper helper) {
        if (!permitsMachineWork()) return;
        if (assemblyLineMode()
            && (session == null || !nativeBound || !session.readyForPatternAdvertisement() || !checkNativeBinding()))
            return;
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
                if (assemblyLineMode()) AssemblyRecipeMatch.bind(layout, authorizedRecipes());
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
        AssemblyRecipeMatch match = null;
        try {
            if (stage != Stage.IDLE && (patternSlot < 0 || !ItemStack.areItemStacksEqual(
                master.matrixPort()
                    .patterns()
                    .getStackInSlot(patternSlot),
                layout.pattern()))) return reject("different_pattern");
            payload = layout.validateTable(table);
            if (assemblyLineMode()) match = AssemblyRecipeMatch.bind(layout, payload, authorizedRecipes());
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
        if (!accepting() || intakeEpoch != epoch) return false;
        try {
            if (match != null && !match.key()
                .equals(
                    AssemblyRecipeMatch.bind(layout, payload, authorizedRecipes())
                        .key()))
                return reject("no_authorized_recipe");
        } catch (PatternValidationException ex) {
            return reject(
                ex.reason()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT));
        }
        UUID nextBatch = batch == null ? UUID.randomUUID() : batch;
        if (stage == Stage.IDLE) recordAcceptedDiagnostics(payload);
        for (int i = 0; i < itemCopies.size(); i++) items.get(i)
            .matrixSetItem(itemCopies.get(i));
        for (int i = 0; i < fluidCopies.size(); i++) fluids.get(i)
            .matrixSetFluid(fluidCopies.get(i));
        batch = nextBatch;
        genericInput = !assemblyLineMode();
        recipeKey = match == null ? ""
            : match.key()
                .value();
        patternSlot = slot;
        if (stage != Stage.RUNNING) stage = Stage.STAGED;
        reason = "";
        try {
            dirty();
            if (session != null) session.requestRecipeCheck();
        } catch (RuntimeException ex) {
            stage = Stage.QUARANTINED;
            reason = "notification_failed";
            GTSimpleWirelessNetwork.LOG
                .error("Matrix input committed; materials retained after notification failure", ex);
        }
        return true;
    }

    @Override
    public boolean validateNativeState(NativeAssemblySession nativeSession) {
        return nativeSession == session && nativeBound
            && verified
            && stage == Stage.STAGED
            && permitsMachineWork()
            && checkNativeBinding()
            && session.controller()
                .protectsExcessItem();
    }

    @Override
    public boolean acceptsNativeCandidate(NativeAssemblySession nativeSession, RecipeAssemblyLine recipe) {
        return validateNativeState(nativeSession) && recipeKey.equals(
            AssemblyRecipeKey.of(recipe)
                .value());
    }

    @Override
    public void onNativeConsumption(NativeAssemblySession nativeSession, RecipeAssemblyLine recipe, int parallel,
        long[] itemConsumed, long[] fluidConsumed, boolean complete) {
        if (nativeSession != session) return;
        recordConsumptionDiagnostics(itemConsumed, fluidConsumed);
        if (!complete || parallel != 1
            || !recipeKey.equals(
                AssemblyRecipeKey.of(recipe)
                    .value())) {
            stage = Stage.QUARANTINED;
            reason = "partial_consumption";
        } else {
            stage = Stage.RUNNING;
            nativeSequence = nativeSession.runSequence();
            reason = "";
        }
        dirty();
    }

    @Override
    public void onNativeFinished(NativeAssemblySession nativeSession) {
        if (nativeSession == session && stage == Stage.RUNNING) {
            stage = allEmpty() ? Stage.LOCAL_FINISHED : Stage.STAGED;
            reason = stage == Stage.LOCAL_FINISHED ? "local_output_only" : "";
            dirty();
        }
    }

    @Override
    public void onNativeStopped(NativeAssemblySession nativeSession, String message) {
        if (nativeSession == session && stage == Stage.RUNNING) {
            stage = Stage.NATIVE_ABORTED;
            reason = message;
            dirty();
        }
    }

    public void tick() {
        if (!verified) return;
        refreshNetwork();
        if (allEmpty() && (genericInput && stage == Stage.STAGED
            && inputController != null
            && inputController.mMaxProgresstime == 0
            || stage == Stage.LOCAL_FINISHED && session != null && session.controller().mMaxProgresstime == 0)) {
            stage = Stage.IDLE;
            batch = null;
            recipeKey = "";
            patternSlot = -1;
            genericInput = false;
            reason = "";
            dirty();
        }
    }

    /** Transfer only actual unconsumed contents. AE CPU jobs are not cancelled by this action. */
    public void refund() {
        if (!verified || stage == Stage.RUNNING || stage == Stage.LOCAL_FINISHED || !checkTopology()) return;
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
                stage = Stage.IDLE;
                batch = null;
                recipeKey = "";
                patternSlot = -1;
                genericInput = false;
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
        if (tag == null) return;
        try {
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
                    || !genericInput && !recipeKey.matches("v1:[0-9a-f]{64}")))
                throw new IllegalArgumentException("invalid_saved_data");
            if (genericInput && (machinePosition == null || controllerId != null
                || stage == Stage.RUNNING
                || stage == Stage.LOCAL_FINISHED
                || stage == Stage.NATIVE_ABORTED)) throw new IllegalArgumentException("invalid_saved_data");
            if (stage == Stage.RUNNING && session != null
                && (session.controller().mMaxProgresstime == 0 || nativeSequence != session.runSequence()
                    || !"RUNNING".equals(session.lastNativeEvent()))) {
                stage = Stage.NATIVE_ABORTED;
                reason = "restart_native_state_missing";
            }
        } catch (IllegalArgumentException ex) {
            quarantinedLedger = (NBTTagCompound) tag.copy();
            if (patternSlot < 0 || patternSlot >= MatrixPatternInventory.SIZE) patternSlot = -1;
            stage = Stage.QUARANTINED;
            reason = ex.getMessage();
        }
    }

    private void dirty() {
        for (MatrixAssemblyMember member : members) if (isPresent(member)) member.matrixPort()
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
