package com.miaokatze.gtswn.assembly.nativeintegration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.metatileentity.implementations.MTEHatchDataAccess;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.util.AssemblyLineUtils;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.api.util.GTUtility;
import gregtech.api.util.shutdown.ShutDownReasonRegistry;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;

/**
 * Native observation and access permit for one actual controller instance. It owns no materials and delegates every
 * recipe match, overclock, consumption and output to GT.
 */
public final class NativeAssemblySession {

    private static final String NBT_KEY = "gtswnOrderedAssemblyController";
    private static final int SCHEMA = 1;

    private final MTEAssemblyLine controller;
    private UUID controllerId = UUID.randomUUID();
    private long runSequence;
    private boolean identityValid = true;
    private NBTBase unknownIdentity;
    private List<OrderedAssemblyMember> previousMembers = Collections.emptyList();
    private NativeAssemblyBinding checkingBinding;
    private NativeAssemblyBinding runningBinding;
    private RecipeAssemblyLine candidate;
    private int[] candidateItemAmounts;
    private long[] beforeItems;
    private long[] beforeFluids;
    private int observedParallel;
    private boolean consumptionOpen;
    private boolean recordedRunning;
    private boolean lastOutputAccepted;
    private String lastNativeEvent = "IDLE";
    private String blockedReason = "";
    private String identityConflict = "";

    public NativeAssemblySession(MTEAssemblyLine controller) {
        this.controller = controller;
    }

    public MTEAssemblyLine controller() {
        return controller;
    }

    public UUID controllerId() {
        return controllerId;
    }

    public long runSequence() {
        return runSequence;
    }

    public String lastNativeEvent() {
        return lastNativeEvent;
    }

    public String blockedReason() {
        return blockedReason;
    }

    /** The native boolean is diagnostic only; it is not proof of output storage or AE delivery. */
    public boolean lastOutputAccepted() {
        return lastOutputAccepted;
    }

    public boolean nativeRunning() {
        return controller.mMaxProgresstime > 0;
    }

    /** Intake must also reject an unknown saved controller identity before accepting any resources. */
    public boolean readyForNewWork() {
        return readyForPatternAdvertisement() && controller.mMaxProgresstime == 0;
    }

    /** Busy media remain in AE's recipe catalogue so new jobs can wait for their turn. */
    public boolean readyForPatternAdvertisement() {
        return identityValid && NativeAssemblyHooks.ready()
            && NativeAssemblyAdapter.isSupportedController(controller)
            && NativeAssemblyAdapter.isLive(controller)
            && controller.mMachine
            && controller.protectsExcessItem()
            && NativeAssemblyIdentityIndex.verify(this);
    }

    String identityConflict() {
        return identityConflict;
    }

    void identityConflict(String reason) {
        identityConflict = reason;
        if (!reason.isEmpty()) blockedReason = reason;
    }

    public void requestRecipeCheck() {
        if (!NativeAssemblyAdapter.isLive(controller)) return;
        controller.markDirty();
        controller.scheduleRecipeCheckImmediate();
    }

    public boolean usesOrderedMembers() {
        for (MTEHatchInputBus bus : controller.mInputBusses) {
            if (bus instanceof OrderedAssemblyMember) return true;
        }
        for (MTEHatchInput hatch : controller.mInputHatches) {
            if (hatch instanceof OrderedAssemblyMember) return true;
        }
        return false;
    }

    boolean containsMember(OrderedAssemblyMember member) {
        for (MTEHatchInputBus bus : controller.mInputBusses) {
            if (bus == member) return true;
        }
        for (MTEHatchInput hatch : controller.mInputHatches) {
            if (hatch == member) return true;
        }
        return false;
    }

    /** Called before native ME maps or input callbacks can observe an unisolated table. */
    public boolean beginCheck() {
        checkingBinding = null;
        candidate = null;
        candidateItemAmounts = null;
        if (!usesOrderedMembers()) return true;
        return validateInputTable();
    }

    private boolean reject(String reason) {
        blockedReason = reason;
        return false;
    }

    private boolean validateInputTable() {
        if (!NativeAssemblyHooks.ready()) return reject("missing_hooks: " + NativeAssemblyHooks.missing());
        if (!identityValid) return reject("unknown_controller_identity");
        if (!NativeAssemblyAdapter.isSupportedController(controller) || !NativeAssemblyAdapter.isLive(controller))
            return reject("unsupported_or_unloaded_controller");
        if (!controller.mMachine) return reject("structure_incomplete");
        if (!controller.protectsExcessItem()) return reject("native_item_output_protection_required");
        if (!NativeAssemblyIdentityIndex.verify(this)) return reject(identityConflict);
        if (!controller.mDualInputHatches.isEmpty()) return reject("dual_inputs_not_supported");
        if (controller.mInputBusses.size() < 5 || controller.mInputBusses.size() > 16
            || controller.mInputHatches.size() > 4) return reject("native_input_count");

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Set<UUID> ids = new java.util.HashSet<>();
        NativeAssemblyBinding binding = null;
        for (MTEHatchInputBus bus : controller.mInputBusses) {
            if (!(bus instanceof OrderedAssemblyMember) || !NativeAssemblyAdapter.isLive(bus)
                || bus.getSizeInventory() != 1) return reject("uncontrolled_item_input");
            OrderedAssemblyMember member = (OrderedAssemblyMember) bus;
            if (!seen.add(bus) || member.orderedMemberId() == null || !ids.add(member.orderedMemberId()))
                return reject("duplicate_member");
            NativeAssemblyBinding owner = member.orderedAssemblyBinding();
            if (owner == null || (binding != null && owner != binding)) return reject("input_owner_conflict");
            binding = owner;
        }
        for (MTEHatchInput hatch : controller.mInputHatches) {
            if (!(hatch instanceof OrderedAssemblyMember) || !NativeAssemblyAdapter.isLive(hatch))
                return reject("uncontrolled_fluid_input");
            OrderedAssemblyMember member = (OrderedAssemblyMember) hatch;
            if (!seen.add(hatch) || member.orderedMemberId() == null || !ids.add(member.orderedMemberId()))
                return reject("duplicate_member");
            if (member.orderedAssemblyBinding() != binding) return reject("input_owner_conflict");
            for (ItemStack container : hatch.getRealInventory()) {
                if (container != null) return reject("fluid_container_inventory_not_empty");
            }
        }
        checkingBinding = binding;
        if (!binding.validateNativeState(this)) return reject("assembly_not_ready");
        blockedReason = "";
        return true;
    }

    /** The one native candidate gate. A null result retains the original candidate-loop continue. */
    public int[] matchCandidate(ArrayList<MTEHatchInputBus> buses, RecipeAssemblyLine recipe) {
        if (!usesOrderedMembers()) return RecipeAssemblyLine.getItemConsumptionAmountArray(buses, recipe);
        candidate = null;
        if (buses != controller.mInputBusses || !validateInputTable()
            || !currentlyAuthorized(recipe)
            || !checkingBinding.acceptsNativeCandidate(this, recipe)) return null;
        NativeAssemblySession previous = NativeAssemblyAdapter.enter(this);
        try {
            int[] requirements = RecipeAssemblyLine.getItemConsumptionAmountArray(buses, recipe);
            if (requirements == null || !enoughForOneCopy(recipe, requirements)) return null;
            candidate = recipe;
            candidateItemAmounts = requirements.clone();
            return requirements;
        } finally {
            NativeAssemblyAdapter.leave(previous);
        }
    }

    private boolean currentlyAuthorized(RecipeAssemblyLine recipe) {
        ItemStack stick = controller.getStackInSlot(1);
        if (AssemblyLineUtils.isItemDataStick(stick)) {
            for (RecipeAssemblyLine authorized : AssemblyLineUtils.findALRecipeFromDataStick(stick)) {
                if (authorized == recipe) return true;
            }
        }
        for (MTEHatchDataAccess hatch : controller.mDataAccessHatches) {
            if (!NativeAssemblyAdapter.isLive(hatch)) return false;
            for (RecipeAssemblyLine authorized : hatch.getAssemblyLineRecipes()) {
                if (authorized == recipe) return true;
            }
        }
        return reject("recipe_no_longer_authorized");
    }

    private boolean enoughForOneCopy(RecipeAssemblyLine recipe, int[] requirements) {
        for (int i = 0; i < controller.mInputBusses.size(); i++) {
            ItemStack actual = controller.mInputBusses.get(i)
                .getFirstStack();
            if (i < requirements.length) {
                if (requirements[i] <= 0 || actual == null || actual.stackSize < requirements[i])
                    return reject("item_quantity_below_one_copy");
            } else if (actual != null) return reject("extra_item_input_not_empty");
        }
        for (int i = 0; i < controller.mInputHatches.size(); i++) {
            FluidStack actual = controller.mInputHatches.get(i)
                .getFillableStack();
            if (i < recipe.mFluidInputs.length) {
                FluidStack required = recipe.mFluidInputs[i];
                if (required == null || required.amount <= 0
                    || actual == null
                    || !GTUtility.areFluidsEqual(actual, required)
                    || actual.amount < required.amount) return reject("fluid_quantity_below_one_copy");
            } else if (actual != null) return reject("extra_fluid_input_not_empty");
        }
        return true;
    }

    public double maxParallelItems(ArrayList<MTEHatchInputBus> buses, int maximum, int[] requirements,
        Map<GTUtility.ItemId, ItemStack> meInputs) {
        NativeAssemblySession previous = NativeAssemblyAdapter.enter(this);
        try {
            return RecipeAssemblyLine.maxParallelCalculatedByInputItems(
                buses,
                usesOrderedMembers() ? Math.min(maximum, 1) : maximum,
                requirements,
                meInputs);
        } finally {
            NativeAssemblyAdapter.leave(previous);
        }
    }

    public double maxParallelFluids(ArrayList<MTEHatchInput> hatches, int maximum, FluidStack[] requirements,
        Map<Fluid, FluidStack> meInputs) {
        NativeAssemblySession previous = NativeAssemblyAdapter.enter(this);
        try {
            return RecipeAssemblyLine.maxParallelCalculatedByInputFluids(
                hatches,
                usesOrderedMembers() ? Math.min(maximum, 1) : maximum,
                requirements,
                meInputs);
        } finally {
            NativeAssemblyAdapter.leave(previous);
        }
    }

    public void consumeItems(ArrayList<MTEHatchInputBus> buses, int parallel, int[] requirements,
        Map<GTUtility.ItemId, ItemStack> meInputs) {
        NativeAssemblySession previous = NativeAssemblyAdapter.enter(this);
        try {
            if (usesOrderedMembers()) {
                if (candidate == null || parallel != 1 || checkingBinding == null) {
                    controller.stopMachine(ShutDownReasonRegistry.CRITICAL_NONE);
                    throw new IllegalStateException("Ordered assembly native consumption without its unique gate");
                }
                beforeItems = itemAmounts();
                beforeFluids = fluidAmounts();
                observedParallel = parallel;
                consumptionOpen = true;
                runSequence++;
                runningBinding = checkingBinding;
            }
            RecipeAssemblyLine.consumeInputItems(buses, parallel, requirements, meInputs);
        } catch (RuntimeException | Error failure) {
            finishConsumption(false);
            throw failure;
        } finally {
            NativeAssemblyAdapter.leave(previous);
        }
    }

    public void consumeFluids(ArrayList<MTEHatchInput> hatches, int parallel, FluidStack[] requirements,
        Map<Fluid, FluidStack> meInputs) {
        NativeAssemblySession previous = NativeAssemblyAdapter.enter(this);
        try {
            RecipeAssemblyLine.consumeInputFluids(hatches, parallel, requirements, meInputs);
            finishConsumption(true);
        } catch (RuntimeException | Error failure) {
            finishConsumption(false);
            throw failure;
        } finally {
            NativeAssemblyAdapter.leave(previous);
        }
    }

    private void finishConsumption(boolean returned) {
        if (!consumptionOpen) return;
        consumptionOpen = false;
        long[] items = difference(beforeItems, itemAmounts());
        long[] fluids = difference(beforeFluids, fluidAmounts());
        boolean complete = returned && matchesConsumption(items, fluids);
        recordedRunning = complete;
        lastNativeEvent = complete ? "RUNNING" : "CONSUMPTION_FAILED";
        controller.markDirty();
        try {
            runningBinding.onNativeConsumption(this, candidate, observedParallel, items, fluids, complete);
        } finally {
            // Native processing fields already exist here. A failed/partial consumption must not leave them runnable.
            if (!complete) controller.stopMachine(ShutDownReasonRegistry.CRITICAL_NONE);
        }
        if (returned && !complete)
            throw new IllegalStateException("Ordered assembly native consumption differs from its observed inputs");
    }

    private boolean matchesConsumption(long[] items, long[] fluids) {
        for (int i = 0; i < items.length; i++) {
            long required = i < candidateItemAmounts.length ? candidateItemAmounts[i] : 0;
            if (items[i] != required) return false;
        }
        for (int i = 0; i < fluids.length; i++) {
            long required = i < candidate.mFluidInputs.length ? candidate.mFluidInputs[i].amount : 0;
            if (fluids[i] != required) return false;
        }
        return true;
    }

    private long[] itemAmounts() {
        long[] amounts = new long[controller.mInputBusses.size()];
        for (int i = 0; i < amounts.length; i++) {
            ItemStack stack = controller.mInputBusses.get(i)
                .getFirstStack();
            amounts[i] = stack == null ? 0 : stack.stackSize;
        }
        return amounts;
    }

    private long[] fluidAmounts() {
        long[] amounts = new long[controller.mInputHatches.size()];
        for (int i = 0; i < amounts.length; i++) {
            FluidStack stack = controller.mInputHatches.get(i)
                .getFillableStack();
            amounts[i] = stack == null ? 0 : stack.amount;
        }
        return amounts;
    }

    static long[] difference(long[] before, long[] after) {
        long[] delta = new long[before.length];
        for (int i = 0; i < before.length; i++) delta[i] = before[i] - after[i];
        return delta;
    }

    public void structureChecked(boolean formed) {
        if (formed && identityValid && NativeAssemblyAdapter.isLive(controller)) {
            NativeAssemblyIdentityIndex.verify(this);
        }
        List<OrderedAssemblyMember> members = new ArrayList<>();
        for (MTEHatchInputBus bus : controller.mInputBusses) {
            if (bus instanceof OrderedAssemblyMember) members.add((OrderedAssemblyMember) bus);
        }
        for (MTEHatchInput hatch : controller.mInputHatches) {
            if (hatch instanceof OrderedAssemblyMember) members.add((OrderedAssemblyMember) hatch);
        }
        Set<OrderedAssemblyMember> notified = Collections.newSetFromMap(new IdentityHashMap<>());
        for (OrderedAssemblyMember member : previousMembers) {
            if (notified.add(member)) member.orderedAssemblyStructureChecked(this, formed);
        }
        for (OrderedAssemblyMember member : members) {
            if (notified.add(member)) member.orderedAssemblyStructureChecked(this, formed);
        }
        previousMembers = members;
        if (recordedRunning && runningBinding == null && !members.isEmpty()) {
            runningBinding = members.get(0)
                .orderedAssemblyBinding();
        }
    }

    public void outputAttempted(boolean accepted) {
        lastOutputAccepted = accepted;
    }

    public void nativeFinished() {
        if (!recordedRunning) return;
        recordedRunning = false;
        lastNativeEvent = "LOCAL_FINISHED";
        controller.markDirty();
        if (runningBinding != null) runningBinding.onNativeFinished(this);
        runningBinding = null;
    }

    public void nativeStopped(String reason) {
        NativeAssemblyBinding binding = runningBinding != null ? runningBinding : checkingBinding;
        if (recordedRunning || consumptionOpen) {
            lastNativeEvent = "NATIVE_ABORTED:" + reason;
            recordedRunning = false;
            controller.markDirty();
        }
        if (binding != null) binding.onNativeStopped(this, reason);
        runningBinding = null;
    }

    public void controllerRemoved() {
        nativeStopped("controller_removed");
        NativeAssemblyIdentityIndex.removed(this);
        structureChecked(false);
    }

    public void save(NBTTagCompound root) {
        if (unknownIdentity != null) {
            root.setTag(NBT_KEY, unknownIdentity.copy());
            return;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("schema", SCHEMA);
        tag.setLong("uuidMost", controllerId.getMostSignificantBits());
        tag.setLong("uuidLeast", controllerId.getLeastSignificantBits());
        tag.setLong("runSequence", runSequence);
        tag.setBoolean("recordedRunning", recordedRunning);
        tag.setString("lastNativeEvent", lastNativeEvent);
        root.setTag(NBT_KEY, tag);
    }

    public void load(NBTTagCompound root) {
        checkingBinding = null;
        runningBinding = null;
        previousMembers = Collections.emptyList();
        if (!root.hasKey(NBT_KEY)) return;
        if (!root.hasKey(NBT_KEY, 10)) {
            unknownIdentity = root.getTag(NBT_KEY)
                .copy();
            identityValid = false;
            return;
        }
        NBTTagCompound tag = root.getCompoundTag(NBT_KEY);
        if (tag.getInteger("schema") != SCHEMA || !tag.hasKey("uuidMost", 4) || !tag.hasKey("uuidLeast", 4)) {
            unknownIdentity = tag.copy();
            identityValid = false;
            return;
        }
        controllerId = new UUID(tag.getLong("uuidMost"), tag.getLong("uuidLeast"));
        runSequence = tag.getLong("runSequence");
        recordedRunning = tag.getBoolean("recordedRunning");
        lastNativeEvent = tag.getString("lastNativeEvent");
    }
}
