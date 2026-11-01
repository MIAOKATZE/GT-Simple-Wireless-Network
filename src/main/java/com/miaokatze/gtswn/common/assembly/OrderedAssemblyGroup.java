package com.miaokatze.gtswn.common.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
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
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.security.MachineSource;
import appeng.me.GridAccessException;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchDataAccess;
import gregtech.api.util.AssemblyLineUtils;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/** One input-blocked batch. Physical members own all consumable resources; this object owns only facts. */
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
        if (tick % 20 == 0 && (group == null || !group.verified || group.master() == caller)) refreshTopology(caller);
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
                group.verified = group.checkTopology();
                if (group.verified && group.stage == Stage.IDLE) group.reason = "";
                group.updateConnections();
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
        for (MatrixAssemblyMember member : items) {
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
            if (items.contains(incumbent)) master = incumbent;
        }
        if (master == null) for (MatrixAssemblyMember member : items) if (member.matrixPort()
            .restoredLedger() != null) {
                master = member;
                break;
            }
        if (master == null) master = items.get(0);
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
    }

    public static void structureChecked(MatrixAssemblyMember caller, NativeAssemblySession session, boolean formed) {
        refreshTopology(caller);
        OrderedAssemblyGroup group = caller.matrixPort()
            .group();
        if (group == null) return;
        if (!formed) {
            if (group.session == session) group.revokeNativeBinding();
            return;
        }
        group.bindNative(session);
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
        if (controllerId != null
            && (!controllerId.equals(next.controllerId()) || !sameControllerPosition(
                next.controller()
                    .getBaseMetaTileEntity()))
            || pending && (controllerId == null || !items.equals(nextItems) || !fluids.equals(nextFluids))) {
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
        if (!items.equals(nextItems) || !fluids.equals(nextFluids)) {
            // Native recipe positions follow the actual scan, including its reversed direction. The UI master stays
            // put.
            items.clear();
            items.addAll(nextItems);
            fluids.clear();
            fluids.addAll(nextFluids);
            rememberMembers();
            diagnosticItemTable = diagnosticFluidTable = new int[0];
            diagnosticItemConsumed = diagnosticFluidConsumed = new long[0];
            diagnosticConsumptionObserved = false;
        }
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
        if (stage == Stage.IDLE && (!nativeBound || session == null)) return "structure_incomplete";
        if (stage == Stage.IDLE && !session.controller()
            .protectsExcessItem()) return "output_protection_required";
        if (stage == Stage.IDLE && !session.controller()
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
        return controllerId == null ? null : new int[] { controllerDimension, controllerX, controllerY, controllerZ };
    }

    /** Preview uses the current native authorization set without changing the group's rejection or lifecycle state. */
    public String previewReason(OrderedPatternLayout layout) {
        if (session == null || !nativeBound) return "structure_incomplete";
        try {
            AssemblyRecipeMatch.bind(layout, authorizedRecipes());
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
        return session != null && nativeBound
            && session.readyForNewWork()
            && verified
            && stage == Stage.IDLE
            && !refunding
            && allEmpty()
            && checkTopology()
            && checkNativeBinding()
            && session.controller().mMaxProgresstime == 0
            && session.controller()
                .protectsExcessItem()
            && session.controller()
                .getBaseMetaTileEntity()
                .isAllowedToWork();
    }

    public void invalidate(String message) {
        verified = false;
        reason = message;
        for (MatrixAssemblyMember member : members) member.matrixPort()
            .connectionMask(0);
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
        if (!NativeAssemblyGeometry.isOrderedColumn(positions(items), alignment, 5, true)) return reject("item_column");
        if (!fluids.isEmpty() && !NativeAssemblyGeometry.isOrderedColumn(positions(fluids), alignment, 1, false))
            return reject("fluid_column");
        return true;
    }

    static String topologyFault(List<MatrixAssemblyMember> items, List<MatrixAssemblyMember> fluids) {
        if (items.size() < 5) return "minimum_five";
        if (items.size() > 16) return "item_column";
        if (fluids.size() > 4) return "fluid_column";
        int[][] itemPositions = positions(items), fluidPositions = positions(fluids);
        Comparator<int[]> order = Comparator.comparingInt((int[] position) -> position[0])
            .thenComparingInt(position -> position[1])
            .thenComparingInt(position -> position[2]);
        Arrays.sort(itemPositions, order);
        Arrays.sort(fluidPositions, order);
        ForgeDirection axis = itemPositions[0][0] != itemPositions[1][0] ? ForgeDirection.EAST : ForgeDirection.SOUTH;
        if (!NativeAssemblyGeometry.isOrderedColumn(itemPositions, axis, 5, true)) return "item_column";
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

    public void provideCrafting(ICraftingProviderHelper helper) {
        if (session == null || !nativeBound
            || !session.readyForPatternAdvertisement()
            || !verified
            || !checkTopology()
            || !checkNativeBinding()) return;
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
                AssemblyRecipeMatch.bind(layout, authorizedRecipes());
                if (layout.items()
                    .size() <= items.size()
                    && layout.fluids()
                        .size() <= fluids.size())
                    helper.addCraftingOption((ICraftingProvider) master(), layout.details());
            } catch (PatternValidationException ignored) {
                // Invalid/unauthorized patterns remain visible in their physical management slots.
            }
        }
    }

    public boolean accept(ICraftingPatternDetails details, InventoryCrafting table) {
        if (!accepting()) return false;
        OrderedPatternLayout layout = null;
        OrderedPatternLayout.Payload payload;
        AssemblyRecipeMatch match;
        int slot = -1;
        try {
            OrderedPatternLayout.decode(details);
            for (int i = 0; i < MatrixPatternInventory.SIZE; i++) {
                ItemStack pattern = master().matrixPort()
                    .patterns()
                    .getStackInSlot(i);
                if (pattern != null && ItemStack.areItemStacksEqual(pattern, details.getPattern())) {
                    layout = OrderedPatternLayout.decode(
                        pattern,
                        master().getBaseMetaTileEntity()
                            .getWorld());
                    slot = i;
                    break;
                }
            }
            if (layout == null) return reject("pattern_not_installed");
            payload = layout.validateTable(table);
            match = AssemblyRecipeMatch.bind(layout, payload, authorizedRecipes());
            if (payload.items()
                .size() > items.size()
                || payload.fluids()
                    .size() > fluids.size())
                return reject("not_enough_members");
            for (OrderedPatternLayout.Input fluid : payload.fluids()) {
                if (fluid.amount()
                    > ((gregtech.api.metatileentity.implementations.MTEHatchInput) fluids.get(fluid.kindOrdinal()))
                        .getCapacity())
                    return reject("fluid_capacity");
            }
        } catch (PatternValidationException ex) {
            return reject(
                ex.reason()
                    .name()
                    .toLowerCase(java.util.Locale.ROOT));
        }
        // Allocate every detached stack before the first physical write. The setters below only assign fields.
        List<ItemStack> itemCopies = new ArrayList<>();
        List<FluidStack> fluidCopies = new ArrayList<>();
        for (OrderedPatternLayout.Input input : payload.items()) itemCopies.add(input.itemStack());
        for (OrderedPatternLayout.Input input : payload.fluids()) fluidCopies.add(input.fluidStack());
        if (!accepting()) return false;
        try {
            if (!match.key()
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
        UUID nextBatch = UUID.randomUUID();
        recordAcceptedDiagnostics(payload);
        for (int i = 0; i < itemCopies.size(); i++) items.get(i)
            .matrixSetItem(itemCopies.get(i));
        for (int i = 0; i < fluidCopies.size(); i++) fluids.get(i)
            .matrixSetFluid(fluidCopies.get(i));
        batch = nextBatch;
        recipeKey = match.key()
            .value();
        patternSlot = slot;
        stage = Stage.STAGED;
        reason = "";
        try {
            dirty();
            session.requestRecipeCheck();
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
            && !refunding
            && checkTopology()
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
                    .value())
            || !allEmpty()) {
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
            stage = Stage.LOCAL_FINISHED;
            reason = "local_output_only";
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
        if (stage == Stage.LOCAL_FINISHED && session != null
            && session.controller().mMaxProgresstime == 0
            && allEmpty()) {
            stage = Stage.IDLE;
            batch = null;
            recipeKey = "";
            patternSlot = -1;
            dirty();
        }
    }

    /** Transfer only actual unconsumed contents. AE CPU jobs are not cancelled by this action. */
    public void refund() {
        if (!verified || stage == Stage.RUNNING || stage == Stage.LOCAL_FINISHED || !checkTopology()) return;
        if (stage == Stage.IDLE && allEmpty()) return;
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
                    || !recipeKey.matches("v1:[0-9a-f]{64}")))
                throw new IllegalArgumentException("invalid_saved_data");
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
