package com.miaokatze.gtswn.common.assembly;

import java.util.ArrayList;
import java.util.Arrays;
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

    private final NativeAssemblySession session;
    private final List<MatrixAssemblyMember> items;
    private final List<MatrixAssemblyMember> fluids;
    private final List<MatrixAssemblyMember> members;
    private final List<UUID> memberIds = new ArrayList<>();
    private final int[][] memberPositions;
    private final int controllerDimension, controllerX, controllerY, controllerZ;
    private final com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing alignment;
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

    private OrderedAssemblyGroup(NativeAssemblySession session, List<MatrixAssemblyMember> items,
        List<MatrixAssemblyMember> fluids) {
        this(session, items, fluids, null);
    }

    private OrderedAssemblyGroup(NativeAssemblySession session, List<MatrixAssemblyMember> items,
        List<MatrixAssemblyMember> fluids, NBTTagCompound previousLedger) {
        this.session = session;
        this.items = items;
        this.fluids = fluids;
        members = new ArrayList<>(items);
        members.addAll(fluids);
        for (MatrixAssemblyMember member : members) memberIds.add(member.orderedMemberId());
        memberPositions = positions(members);
        IGregTechTileEntity controllerTile = session.controller()
            .getBaseMetaTileEntity();
        controllerDimension = controllerTile.getWorld().provider.dimensionId;
        controllerX = controllerTile.getXCoord();
        controllerY = controllerTile.getYCoord();
        controllerZ = controllerTile.getZCoord();
        alignment = session.controller()
            .getExtendedFacing();
        restore(
            previousLedger == null ? master().matrixPort()
                .restoredLedger() : previousLedger);
    }

    public static void structureChecked(MatrixAssemblyMember caller, NativeAssemblySession session, boolean formed) {
        OrderedAssemblyGroup previous = caller.matrixPort()
            .group();
        if (!formed) {
            if (previous != null) previous.invalidate("structure_incomplete");
            return;
        }
        MTEAssemblyLine controller = session.controller();
        List<MatrixAssemblyMember> items = new ArrayList<>();
        List<MatrixAssemblyMember> fluids = new ArrayList<>();
        for (Object input : controller.mInputBusses) {
            if (!(input instanceof MatrixAssemblyMember member) || member.matrixFluid()) {
                if (previous != null) previous.invalidate("mixed_inputs");
                return;
            }
            items.add(member);
        }
        for (Object input : controller.mInputHatches) {
            if (!(input instanceof MatrixAssemblyMember member) || !member.matrixFluid()) {
                if (previous != null) previous.invalidate("mixed_inputs");
                return;
            }
            fluids.add(member);
        }
        if (items.size() < 5) {
            if (previous != null) previous.invalidate("minimum_five");
            return;
        }
        OrderedAssemblyGroup group = items.get(0)
            .matrixPort()
            .group();
        if (group == null) {
            for (MatrixAssemblyMember member : items) if (member.matrixPort()
                .group() != null) {
                    group = member.matrixPort()
                        .group();
                    break;
                }
        }
        if (group == null) {
            for (MatrixAssemblyMember member : fluids) if (member.matrixPort()
                .group() != null) {
                    group = member.matrixPort()
                        .group();
                    break;
                }
        }
        OrderedAssemblyGroup replaced = null;
        if (group != null && group.session != session) {
            if (com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter.isLive(group.session.controller())
                || !group.session.controllerId()
                    .equals(session.controllerId())
                || !group.sameControllerPosition(controller.getBaseMetaTileEntity())
                || !group.samePersistentMembers(items, fluids)) {
                group.invalidate("controller_conflict");
                if (previous != null) previous.invalidate("controller_conflict");
                return;
            }
            replaced = group;
            group = new OrderedAssemblyGroup(session, items, fluids, replaced.saveLedger());
        }
        if (group == null) group = new OrderedAssemblyGroup(session, items, fluids);
        if (!group.items.equals(items) || !group.fluids.equals(fluids)) {
            if (!group.samePersistentMembers(items, fluids)) {
                group.epoch++;
                group.invalidate("topology_changed");
                return;
            }
            replaced = group;
            group = new OrderedAssemblyGroup(session, items, fluids, replaced.saveLedger());
        }
        for (MatrixAssemblyMember member : group.members) {
            OrderedAssemblyGroup existing = member.matrixPort()
                .group();
            if (existing != null && existing != group && existing != replaced) {
                existing.invalidate("controller_conflict");
                group.invalidate("controller_conflict");
                return;
            }
        }
        for (MatrixAssemblyMember member : group.members) member.matrixPort()
            .bind(group);
        group.verified = group.checkTopology();
        if (group.verified
            && (group.stage == Stage.IDLE || group.stage == Stage.STAGED || group.stage == Stage.RUNNING))
            group.reason = "";
        group.updateConnections();
    }

    private boolean sameControllerPosition(IGregTechTileEntity next) {
        return controllerDimension == next.getWorld().provider.dimensionId && controllerX == next.getXCoord()
            && controllerY == next.getYCoord()
            && controllerZ == next.getZCoord();
    }

    private boolean samePersistentMembers(List<MatrixAssemblyMember> nextItems, List<MatrixAssemblyMember> nextFluids) {
        List<MatrixAssemblyMember> next = new ArrayList<>(nextItems);
        next.addAll(nextFluids);
        if (nextItems.size() != items.size() || nextFluids.size() != fluids.size()) return false;
        for (int i = 0; i < members.size(); i++) {
            MatrixAssemblyMember before = members.get(i), after = next.get(i);
            IGregTechTileEntity tile = after.getBaseMetaTileEntity();
            if (!memberIds.get(i)
                .equals(after.orderedMemberId()) || tile.getWorld().provider.dimensionId != controllerDimension
                || memberPositions[i][0] != tile.getXCoord()
                || memberPositions[i][1] != tile.getYCoord()
                || memberPositions[i][2] != tile.getZCoord()) return false;
            if (before != after && com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter
                .isLive((gregtech.api.interfaces.metatileentity.IMetaTileEntity) before)) return false;
        }
        return true;
    }

    public MatrixAssemblyMember master() {
        return items.get(0);
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
        return new int[] { controllerDimension, controllerX, controllerY, controllerZ };
    }

    /** Preview uses the current native authorization set without changing the group's rejection or lifecycle state. */
    public String previewReason(OrderedPatternLayout layout) {
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
        return session.readyForNewWork() && verified
            && stage == Stage.IDLE
            && !refunding
            && allEmpty()
            && checkTopology()
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
        if (session.controller()
            .getExtendedFacing() != alignment) return reject("topology_changed");
        if (items.size() < 5 || items.size() > 16 || fluids.size() > 4) return reject("minimum_five");
        Set<UUID> ids = new HashSet<>();
        for (MatrixAssemblyMember member : members) {
            IGregTechTileEntity tile = member.getBaseMetaTileEntity();
            if (tile == null || !tile.getWorld()
                .blockExists(tile.getXCoord(), tile.getYCoord(), tile.getZCoord())
                || tile.getWorld()
                    .getTileEntity(tile.getXCoord(), tile.getYCoord(), tile.getZCoord()) != tile
                || tile.getMetaTileEntity() != member) return reject("member_unloaded");
            if (!ids.add(member.orderedMemberId())) return reject("duplicate_identity");
            if (!memberIds.get(members.indexOf(member))
                .equals(member.orderedMemberId())) return reject("member_replaced");
            if (member.matrixPort()
                .quarantined())
                return reject(
                    member.matrixPort()
                        .fault());
            if (tile.getFrontFacing() != session.controller()
                .getExtendedFacing()
                .getRelativeDownInWorld()) return reject("member_facing");
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                int x = tile.getXCoord() + face.offsetX;
                int y = tile.getYCoord() + face.offsetY;
                int z = tile.getZCoord() + face.offsetZ;
                if (!tile.getWorld()
                    .blockExists(x, y, z)) return reject("member_unloaded");
                TileEntity neighbour = tile.getWorld()
                    .getTileEntity(x, y, z);
                if (neighbour instanceof IGregTechTileEntity gt
                    && gt.getMetaTileEntity() instanceof MatrixAssemblyMember other
                    && other.matrixFluid() == member.matrixFluid()
                    && !members.contains(other)) {
                    OrderedAssemblyGroup otherGroup = other.matrixPort()
                        .group();
                    if (otherGroup == null || otherGroup.session == session
                        || !com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter
                            .isLive(otherGroup.session.controller()))
                        return reject("same_kind_branch");
                }
            }
        }
        if (!NativeAssemblyGeometry.isOrderedColumn(
            positions(items),
            session.controller()
                .getExtendedFacing(),
            5,
            true)) return reject("item_column");
        if (!fluids.isEmpty() && !NativeAssemblyGeometry.isOrderedColumn(
            positions(fluids),
            session.controller()
                .getExtendedFacing(),
            1,
            false)) return reject("fluid_column");
        if (!NativeAssemblyGeometry.isFaceConnected(positions(members))) return reject("disconnected_members");
        return true;
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
        if (!session.readyForPatternAdvertisement() || !verified || !checkTopology()) return;
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
        return nativeSession == session && verified
            && stage == Stage.STAGED
            && !refunding
            && checkTopology()
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
        if (stage == Stage.LOCAL_FINISHED && session.controller().mMaxProgresstime == 0 && allEmpty()) {
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
        tag.setIntArray("controllerPosition", new int[] { controllerDimension, controllerX, controllerY, controllerZ });
        tag.setString(
            "controller",
            session.controllerId()
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
            if (!java.util.Arrays.equals(
                tag.getIntArray("controllerPosition"),
                new int[] { controllerDimension, controllerX, controllerY, controllerZ }))
                throw new IllegalArgumentException("controller_replaced");
            if (!UUID.fromString(tag.getString("controller"))
                .equals(session.controllerId())) throw new IllegalArgumentException("controller_replaced");
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
            if (stage == Stage.RUNNING
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
        for (MatrixAssemblyMember member : members) member.matrixPort()
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
            if (verified) {
                IGregTechTileEntity tile = member.getBaseMetaTileEntity();
                for (MatrixAssemblyMember other : members) {
                    if (other == member) continue;
                    IGregTechTileEntity target = other.getBaseMetaTileEntity();
                    for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS)
                        if (tile.getXCoord() + face.offsetX == target.getXCoord()
                            && tile.getYCoord() + face.offsetY == target.getYCoord()
                            && tile.getZCoord() + face.offsetZ == target.getZCoord()) mask |= 1 << face.ordinal();
                }
            }
            member.matrixPort()
                .connectionMask(mask);
        }
    }
}
