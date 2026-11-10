package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;

import gregtech.api.GregTechAPI;
import gregtech.api.casing.Casings;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.recipe.metadata.CentrifugeRecipeKey;
import gregtech.api.structure.error.StructureError;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.GlassTier;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;
import gregtech.common.tileentities.machines.MTEHatchInputME;
import tectech.thing.metaTileEntity.hatch.MTEHatchEnergyMulti;

/**
 * Native, paused fixtures for the opt-in mixed-machine workload. Only setup places materials or configures machines;
 * the observing scenario supplies and collects everything through AE, and powers the exposed native energy sides.
 */
final class StressMachineFixtures {

    static final int SINGLE_COUNT = StressMachineLayout.SINGLE_COUNT, MULTI_COUNT = StressMachineLayout.MULTI_COUNT;
    static final int GENERATOR_COUNT = StressMachineLayout.GENERATOR_COUNT, COUNT = StressMachineLayout.COUNT;
    static final int RADIUS = StressMachineLayout.RADIUS;
    private static final String[] SINGLES = { "E_Furnace", "Macerator", "AlloySmelter", "Compressor", "Extractor",
        "Wiremill", "Bender", "Lathe", "Hammer", "Cutter", "Extruder", "Centrifuge", "Electrolyzer", "Mixer",
        "FluidExtractor", "ChemicalReactor" };
    private static final String[] TIERS = { "EV", "IV", "LuV", "ZPM", "UV", "UHV" };
    private final List<Machine> machines = new ArrayList<>();
    private final List<Endpoint> endpoints = new ArrayList<>();
    private final Map<String, ItemStack> templates = new LinkedHashMap<>();
    private final Map<String, GTRecipe> selectedRecipes = new LinkedHashMap<>();

    enum Kind {
        SINGLE,
        MULTI,
        GENERATOR
    }

    enum Role {
        ITEM_INPUT,
        ITEM_OUTPUT,
        FLUID_INPUT,
        FLUID_OUTPUT
    }

    static final class Endpoint {

        final BaseMetaTileEntity tile;
        /** Direction from the GT block towards its adjacent quantum node / native AE bus host. */
        final ForgeDirection side;
        final Role role;
        final boolean nativeMe;

        Endpoint(BaseMetaTileEntity tile, ForgeDirection side, Role role, boolean nativeMe) {
            this.tile = tile;
            this.side = side;
            this.role = role;
            this.nativeMe = nativeMe;
        }
    }

    static final class Machine {

        final int index;
        final Kind kind;
        final String type;
        final BaseMetaTileEntity controller;
        final List<Endpoint> endpoints = new ArrayList<>();
        /** Input energy hatches, or actual generator/dynamo outputs when kind == GENERATOR. */
        final List<BaseMetaTileEntity> energy = new ArrayList<>();
        final Map<BaseMetaTileEntity, ForgeDirection> energySides = new LinkedHashMap<>();
        final List<ItemStack> itemInputs = new ArrayList<>(), itemOutputs = new ArrayList<>(),
            catalysts = new ArrayList<>();
        final List<FluidStack> fluidInputs = new ArrayList<>(), fluidOutputs = new ArrayList<>();
        final GTRecipe recipe;
        final int energyTier;
        long itemStockLimit = Integer.MAX_VALUE;
        final Map<String, Long> fluidStockLimits = new LinkedHashMap<>();
        int nativeStructureBlocks;
        boolean creativeMaintenance;

        Machine(int index, Kind kind, String type, BaseMetaTileEntity controller, GTRecipe recipe, int energyTier) {
            this.index = index;
            this.kind = kind;
            this.type = type;
            this.controller = controller;
            this.recipe = recipe;
            this.energyTier = energyTier;
            if (recipe != null && kind != Kind.GENERATOR) {
                for (ItemStack item : recipe.mInputs) {
                    if (item == null) continue;
                    if (item.stackSize == 0) catalysts.add(GTUtility.copyAmount(1, item));
                    else itemInputs.add(item.copy());
                }
                for (ItemStack item : recipe.mOutputs) if (item != null) itemOutputs.add(item.copy());
                for (FluidStack fluid : recipe.mFluidInputs) if (fluid != null) fluidInputs.add(fluid.copy());
                for (FluidStack fluid : recipe.mFluidOutputs) if (fluid != null) fluidOutputs.add(fluid.copy());
            }
        }

        private void energy(BaseMetaTileEntity tile, ForgeDirection side) {
            energy.add(tile);
            energySides.put(tile, side);
        }
    }

    static final class Snapshot {

        final boolean active, formed;
        final int progress, maxProgress, parallel, nativeParallel, outputRecipeBatch;
        final long eut, storedEU, completedRecipes;
        final String recipeStatus;

        Snapshot(Machine machine) {
            BaseMetaTileEntity tile = machine.controller;
            IMetaTileEntity meta = tile.getMetaTileEntity();
            active = tile.isActive();
            progress = tile.getProgress();
            maxProgress = tile.getMaxProgress();
            storedEU = tile.getStoredEU();
            if (meta instanceof MTEMultiBlockBase) {
                MTEMultiBlockBase multi = (MTEMultiBlockBase) meta;
                formed = multi.mMachine;
                eut = multi.getEUtForDamageCalc();
                completedRecipes = multi.recipesDone;
                nativeParallel = multi.lastParallel;
                outputRecipeBatch = pendingRecipeBatch(machine, multi);
                // Compatibility field; reports must distinguish the native counter from inferred output batches.
                parallel = Math.max(nativeParallel, outputRecipeBatch);
                recipeStatus = multi.getCheckRecipeResult()
                    .getID();
            } else {
                formed = true;
                eut = meta instanceof MTEBasicMachine ? ((MTEBasicMachine) meta).mEUt
                    : active ? tile.getOutputVoltage() : 0;
                completedRecipes = 0;
                nativeParallel = 0;
                outputRecipeBatch = 0;
                parallel = active ? 1 : 0;
                recipeStatus = active ? "native_active" : "native_idle";
            }
        }
    }

    List<Machine> machines() {
        return Collections.unmodifiableList(machines);
    }

    List<Endpoint> endpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    static Snapshot sampleMachine(Machine machine) {
        return new Snapshot(machine);
    }

    Machine place(TestContext ctx, int index, FakePlayer owner) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (index != machines.size())
            throw new IllegalArgumentException("Stress machines must be built in index order");
        StressMachineLayout.Slot slot = StressMachineLayout.slot(index);
        Machine machine;
        if (index < SINGLE_COUNT) machine = single(ctx, index, owner, slot);
        else if (index < SINGLE_COUNT + MULTI_COUNT) machine = multiblock(ctx, index, owner, slot);
        else machine = generator(ctx, index, owner, slot);
        machines.add(machine);
        endpoints.addAll(machine.endpoints);
        return machine;
    }

    private Machine single(TestContext ctx, int index, FakePlayer owner, StressMachineLayout.Slot slot) {
        String tier = index / SINGLES.length % 2 == 0 ? "EV" : "IV";
        String type = "Machine_" + tier + "_" + SINGLES[index % SINGLES.length];
        BaseMetaTileEntity tile = machine(
            ctx,
            owner,
            slot.x,
            slot.y,
            slot.z,
            ItemList.valueOf(type)
                .get(1));
        MTEBasicMachine nativeMachine = (MTEBasicMachine) tile.getMetaTileEntity();
        nativeMachine.setMainFacing(ForgeDirection.NORTH);
        tile.setFrontFacing(ForgeDirection.DOWN);
        GTRecipe recipe = selectRecipe(
            nativeMachine.getRecipeMap(),
            nativeMachine.mInputSlotCount,
            nativeMachine.mOutputItems.length,
            1,
            1,
            false);
        Machine machine = new Machine(index, Kind.SINGLE, type, tile, recipe, tier.equals("EV") ? 4 : 5);
        int toolSlot = nativeMachine.getInputSlot();
        for (ItemStack catalyst : machine.catalysts) {
            if (isCircuit(catalyst))
                nativeMachine.setInventorySlotContents(nativeMachine.getCircuitSlot(), catalyst.copy());
            else nativeMachine.setInventorySlotContents(toolSlot++, catalyst.copy());
        }
        if (!machine.itemInputs.isEmpty()) endpoint(machine, tile, ForgeDirection.WEST, Role.ITEM_INPUT, false);
        if (!machine.itemOutputs.isEmpty()) endpoint(machine, tile, ForgeDirection.EAST, Role.ITEM_OUTPUT, false);
        if (!machine.fluidInputs.isEmpty()) endpoint(machine, tile, ForgeDirection.SOUTH, Role.FLUID_INPUT, false);
        if (!machine.fluidOutputs.isEmpty()) endpoint(machine, tile, ForgeDirection.UP, Role.FLUID_OUTPUT, false);
        machine.energy(tile, ForgeDirection.DOWN);
        return machine;
    }

    private Machine multiblock(TestContext ctx, int index, FakePlayer owner, StressMachineLayout.Slot slot) {
        StressMachineLayout.Definition definition = slot.definition;
        if (definition == StressMachineLayout.ORE_FACTORY) return oreFactory(ctx, index, owner, slot);
        BaseMetaTileEntity tile = machine(ctx, owner, slot.x, slot.y, slot.z, template(definition.type));
        MTEEnhancedMultiBlockBase<?> multi = (MTEEnhancedMultiBlockBase<?>) tile.getMetaTileEntity();
        multi.setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
        GTRecipe recipe = selectRecipe(
            multi.getRecipeMap(),
            16,
            16,
            definition.fluidInput ? 16 : 0,
            definition.fluidOutput ? 16 : 0,
            true);
        int local = index - SINGLE_COUNT;
        int tier = local >= 224 ? 8 + local % 2 : 4 + (local / StressMachineLayout.MULTIS.length) % TIERS.length;
        Machine machine = new Machine(index, Kind.MULTI, definition.type, tile, recipe, tier);
        ItemStack trigger = ItemList.Tool_DataStick.get(1);
        // Channels select native materials. Stack size stays one so the macerator remains its matching T1 structure.
        // Native primary glass indices are HV..UXV; larger values select unrelated low-tier glass variants.
        ChannelDataAccessor.setChannelData(trigger, "glass", GlassTier.getMaxTierIndex());
        ChannelDataAccessor.setChannelData(
            trigger,
            "coil",
            definition == StressMachineLayout.PARALLEL_FURNACE ? local % 2 == 0 ? 10 : 14 : 8);
        ChannelDataAccessor.setChannelData(trigger, "solenoid", 8);
        ChannelDataAccessor.setChannelData(trigger, "item_pipe", 8);
        ((IConstructable) multi).construct(trigger, false);
        List<HatchPosition> slots = hatchPositions(ctx, machine, slot, primaryCasing(definition.type));
        int position = 0;
        BaseMetaTileEntity itemInput = hatch(ctx, owner, slots.get(position++), ItemList.Hatch_Input_Bus_ME.get(1));
        MTEHatchInputBusME itemBus = (MTEHatchInputBusME) itemInput.getMetaTileEntity();
        for (int i = 0; i < machine.itemInputs.size(); i++) itemBus.setSlotConfig(
            i,
            machine.itemInputs.get(i)
                .copy());
        List<ItemStack> nonCircuitTools = new ArrayList<>();
        for (ItemStack catalyst : machine.catalysts) {
            if (isCircuit(catalyst)) itemBus.setInventorySlotContents(itemBus.getCircuitSlot(), catalyst.copy());
            else nonCircuitTools.add(catalyst);
        }
        endpoint(machine, itemInput, slots.get(position - 1).side, Role.ITEM_INPUT, true);
        BaseMetaTileEntity itemOutput = hatch(ctx, owner, slots.get(position++), ItemList.Hatch_Output_Bus_ME.get(1));
        endpoint(machine, itemOutput, slots.get(position - 1).side, Role.ITEM_OUTPUT, true);
        if (definition.fluidInput) {
            BaseMetaTileEntity fluidInput = hatch(ctx, owner, slots.get(position++), ItemList.Hatch_Input_ME.get(1));
            MTEHatchInputME fluidHatch = (MTEHatchInputME) fluidInput.getMetaTileEntity();
            for (int i = 0; i < machine.fluidInputs.size(); i++) fluidHatch.setSlotConfig(
                i,
                machine.fluidInputs.get(i)
                    .copy());
            endpoint(machine, fluidInput, slots.get(position - 1).side, Role.FLUID_INPUT, true);
        }
        if (definition.fluidOutput) {
            BaseMetaTileEntity fluidOutput = hatch(ctx, owner, slots.get(position++), ItemList.Hatch_Output_ME.get(1));
            endpoint(machine, fluidOutput, slots.get(position - 1).side, Role.FLUID_OUTPUT, true);
        }
        if (!nonCircuitTools.isEmpty()) {
            BaseMetaTileEntity tools = hatch(ctx, owner, slots.get(position++), ItemList.Hatch_Input_Bus_IV.get(1));
            MTEHatchInputBus toolBus = (MTEHatchInputBus) tools.getMetaTileEntity();
            for (int i = 0; i < nonCircuitTools.size(); i++) toolBus.setInventorySlotContents(
                i,
                nonCircuitTools.get(i)
                    .copy());
            // The physical tool bus and the ME material bus must participate in the same native recipe check.
            multi.setInputSeparation(false);
        }
        BaseMetaTileEntity maintenance = hatch(
            ctx,
            owner,
            slots.get(position++),
            template("MTEHatchCreativeMaintenance"));
        machine.creativeMaintenance = maintenance.getMetaTileEntity()
            .getClass()
            .getSimpleName()
            .equals("MTEHatchCreativeMaintenance");
        if (definition == StressMachineLayout.PARALLEL_FURNACE) {
            BaseMetaTileEntity muffler = TestWorldFixtures.machine(
                ctx.world(),
                tile.xCoord,
                slot.maxY,
                tile.zCoord + 1,
                ItemList.Hatch_Muffler_UV.get(1),
                owner.getUniqueID());
            muffler.setFrontFacing(ForgeDirection.UP);
        } else if (needsMuffler(definition.type))
            hatch(ctx, owner, slots.get(position++), ItemList.Hatch_Muffler_UV.get(1));
        // Mega reactors accept an actual exotic 64 A hatch; ordinary structures retain their supported 2 x 2 A.
        int energyHatches = definition == StressMachineLayout.MEGA_CHEMICAL ? 1 : 2;
        for (int i = 0; i < energyHatches; i++) {
            HatchPosition p = slots.get(position++);
            ItemStack energyItem = definition == StressMachineLayout.MEGA_CHEMICAL ? multiAmpEnergy(tier)
                : ItemList.valueOf("Hatch_Energy_" + TIERS[tier - 4])
                    .get(1);
            BaseMetaTileEntity energy = hatch(ctx, owner, p, energyItem);
            machine.energy(energy, p.side);
        }
        formed(ctx, machine, multi);
        machine.nativeStructureBlocks = registerNativeBlocks(ctx, slot);
        return machine;
    }

    private Machine generator(TestContext ctx, int index, FakePlayer owner, StressMachineLayout.Slot slot) {
        int local = index - SINGLE_COUNT - MULTI_COUNT;
        if (local % 4 == 0) return largeGenerator(ctx, index, owner, slot);
        ItemList item = local % 4 == 1 ? ItemList.Generator_Diesel_HV
            : local % 4 == 2 ? ItemList.Generator_Gas_Turbine_IV : ItemList.Generator_Steam_Turbine_HV;
        BaseMetaTileEntity tile = machine(ctx, owner, slot.x, slot.y, slot.z, item.get(1));
        MTEBasicGenerator generator = (MTEBasicGenerator) tile.getMetaTileEntity();
        GTRecipe recipe = generator.getRecipeMap() == null ? null : fuelRecipe(generator.getRecipeMap());
        Machine machine = new Machine(
            index,
            Kind.GENERATOR,
            generator.getClass()
                .getSimpleName(),
            tile,
            recipe,
            local % 4 == 2 ? 5 : 3);
        FluidStack fuel = recipe == null ? GTModHandler.getSteam(1) : fuel(recipe);
        demand(generator.getFuelValue(fuel) > 0, "Native generator rejected selected fuel: " + machine.type);
        machine.fluidInputs.add(fuel);
        endpoint(machine, tile, ForgeDirection.SOUTH, Role.FLUID_INPUT, false);
        machine.energy(tile, ForgeDirection.NORTH);
        return machine;
    }

    private Machine oreFactory(TestContext ctx, int index, FakePlayer owner, StressMachineLayout.Slot slot) {
        BaseMetaTileEntity tile = machine(ctx, owner, slot.x, slot.y, slot.z, template(slot.definition.type));
        MTEEnhancedMultiBlockBase<?> multi = (MTEEnhancedMultiBlockBase<?>) tile.getMetaTileEntity();
        multi.setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
        Machine machine = new Machine(index, Kind.MULTI, slot.definition.type, tile, null, 9);
        // The factory performs its native wash/thermal/maceration pipeline, rather than one recipe-map lookup.
        machine.itemInputs.add(GTOreDictUnificator.get(OrePrefixes.crushedPurified, Materials.Iron, 1));
        machine.itemOutputs.add(Materials.Iron.getDust(1));
        machine.fluidInputs.add(Materials.Lubricant.getFluid(2));
        machine.fluidInputs.add(GTModHandler.getDistilledWater(200));
        machine.itemStockLimit = 4096;
        machine.fluidStockLimits.put(
            Materials.Lubricant.getFluid(1)
                .getFluid()
                .getName(),
            8192L);
        machine.fluidStockLimits.put(
            GTModHandler.getDistilledWater(1)
                .getFluid()
                .getName(),
            819200L);
        ItemStack trigger = ItemList.Tool_DataStick.get(1);
        ChannelDataAccessor.setChannelData(trigger, "glass", GlassTier.getMaxTierIndex());
        ((IConstructable) multi).construct(trigger, false);
        List<HatchPosition> positions = hatchPositions(ctx, machine, slot, Casings.CleanStainlessSteelMachineCasing);
        BaseMetaTileEntity input = hatch(ctx, owner, positions.get(0), ItemList.Hatch_Input_Bus_ME.get(1));
        ((MTEHatchInputBusME) input.getMetaTileEntity()).setSlotConfig(
            0,
            machine.itemInputs.get(0)
                .copy());
        endpoint(machine, input, positions.get(0).side, Role.ITEM_INPUT, true);
        BaseMetaTileEntity output = hatch(ctx, owner, positions.get(1), ItemList.Hatch_Output_Bus_ME.get(1));
        endpoint(machine, output, positions.get(1).side, Role.ITEM_OUTPUT, true);
        BaseMetaTileEntity fluids = hatch(ctx, owner, positions.get(2), ItemList.Hatch_Input_ME.get(1));
        MTEHatchInputME fluidHatch = (MTEHatchInputME) fluids.getMetaTileEntity();
        for (int i = 0; i < machine.fluidInputs.size(); i++) fluidHatch.setSlotConfig(
            i,
            machine.fluidInputs.get(i)
                .copy());
        endpoint(machine, fluids, positions.get(2).side, Role.FLUID_INPUT, true);
        hatch(ctx, owner, positions.get(3), ItemList.Hatch_Muffler_UV.get(1));
        HatchPosition energyPosition = positions.get(4);
        machine.energy(hatch(ctx, owner, energyPosition, multiAmpEnergy(9)), energyPosition.side);
        // IOF natively has no maintenance checks and accepts no maintenance hatch in its structure.
        formed(ctx, machine, multi);
        machine.nativeStructureBlocks = registerNativeBlocks(ctx, slot);
        return machine;
    }

    private Machine largeGenerator(TestContext ctx, int index, FakePlayer owner, StressMachineLayout.Slot slot) {
        BaseMetaTileEntity tile = machine(ctx, owner, slot.x, slot.y, slot.z, template(slot.definition.type));
        MTEEnhancedMultiBlockBase<?> multi = (MTEEnhancedMultiBlockBase<?>) tile.getMetaTileEntity();
        multi.setExtendedFacing(ExtendedFacing.NORTH_NORMAL_NONE);
        GTRecipe recipe = fuelRecipe(RecipeMaps.dieselFuels);
        Machine machine = new Machine(index, Kind.GENERATOR, slot.definition.type, tile, recipe, 5);
        machine.fluidInputs.add(fuel(recipe));
        machine.fluidInputs.add(Materials.Lubricant.getFluid(1));
        machine.fluidInputs.add(Materials.Oxygen.getGas(2));
        ((IConstructable) multi).construct(ItemList.Tool_DataStick.get(1), false);
        // Native B and F positions are deliberately distinct, even though both fall back to titanium casing.
        nativeHatch(ctx, owner, multi, 1, 0, 1, template("MTEHatchCreativeMaintenance"), ForgeDirection.UP);
        nativeHatch(ctx, owner, multi, 1, 0, 3, ItemList.Hatch_Muffler_UV.get(1), ForgeDirection.UP);
        BaseMetaTileEntity input = nativeHatch(
            ctx,
            owner,
            multi,
            3,
            0,
            2,
            ItemList.Hatch_Input_ME.get(1),
            ForgeDirection.UP);
        MTEHatchInputME fluidHatch = (MTEHatchInputME) input.getMetaTileEntity();
        for (int i = 0; i < machine.fluidInputs.size(); i++) fluidHatch.setSlotConfig(
            i,
            machine.fluidInputs.get(i)
                .copy());
        endpoint(machine, input, ForgeDirection.UP, Role.FLUID_INPUT, true);
        BaseMetaTileEntity dynamo = nativeHatch(
            ctx,
            owner,
            multi,
            7,
            1,
            2,
            ItemList.Hatch_Dynamo_IV.get(1),
            ForgeDirection.WEST);
        machine.energy(dynamo, ForgeDirection.WEST);
        machine.creativeMaintenance = true;
        formed(ctx, machine, multi);
        machine.nativeStructureBlocks = registerNativeBlocks(ctx, slot);
        return machine;
    }

    private static BaseMetaTileEntity nativeHatch(TestContext ctx, FakePlayer owner, MTEEnhancedMultiBlockBase<?> multi,
        int a, int b, int c, ItemStack item, ForgeDirection side) {
        int[] offset = new int[3];
        StressMachineLayout.Definition d = StressMachineLayout.LARGE_GENERATOR;
        multi.getExtendedFacing()
            .getWorldOffset(new int[] { a - d.offsetX, b - d.offsetY, c - d.offsetZ }, offset);
        BaseMetaTileEntity controller = (BaseMetaTileEntity) multi.getBaseMetaTileEntity();
        BaseMetaTileEntity tile = TestWorldFixtures.machine(
            ctx.world(),
            controller.xCoord + offset[0],
            controller.yCoord + offset[1],
            controller.zCoord + offset[2],
            item,
            owner.getUniqueID());
        tile.setFrontFacing(side);
        return tile;
    }

    private static BaseMetaTileEntity machine(TestContext ctx, FakePlayer owner, int x, int y, int z, ItemStack item) {
        BaseMetaTileEntity tile = TestWorldFixtures.machine(ctx.world(), x, y, z, item, owner.getUniqueID());
        tile.disableWorking();
        return tile;
    }

    private static BaseMetaTileEntity hatch(TestContext ctx, FakePlayer owner, HatchPosition p, ItemStack item) {
        BaseMetaTileEntity tile = TestWorldFixtures.machine(ctx.world(), p.x, p.y, p.z, item, owner.getUniqueID());
        tile.setFrontFacing(p.side);
        return tile;
    }

    private static void endpoint(Machine machine, BaseMetaTileEntity tile, ForgeDirection side, Role role,
        boolean nativeMe) {
        machine.endpoints.add(new Endpoint(tile, side, role, nativeMe));
    }

    private ItemStack template(String type) {
        ItemStack existing = templates.get(type);
        if (existing != null) return existing.copy();
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity meta = GregTechAPI.METATILEENTITIES[id];
            if (meta != null && meta.getClass()
                .getSimpleName()
                .equals(type)) {
                ItemStack stack = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
                templates.put(type, stack);
                return stack.copy();
            }
        }
        throw new IllegalStateException("Required native GT machine is not registered: " + type);
    }

    private ItemStack multiAmpEnergy(int tier) {
        String key = "native64A:" + tier;
        ItemStack existing = templates.get(key);
        if (existing != null) return existing.copy();
        for (int id = 1; id < GregTechAPI.METATILEENTITIES.length; id++) {
            IMetaTileEntity meta = GregTechAPI.METATILEENTITIES[id];
            if (meta == null || meta.getClass() != MTEHatchEnergyMulti.class) continue;
            MTEHatchEnergyMulti hatch = (MTEHatchEnergyMulti) meta;
            if (hatch.getTierForStructure() == tier && hatch.maxAmperes == 64) {
                ItemStack stack = new ItemStack(GregTechAPI.sBlockMachines, 1, id);
                templates.put(key, stack);
                return stack.copy();
            }
        }
        throw new IllegalStateException("Required native 64 A energy hatch is not registered: tier " + tier);
    }

    private GTRecipe selectRecipe(RecipeMap<?> map, int itemsIn, int itemsOut, int fluidsIn, int fluidsOut,
        boolean multi) {
        demand(map != null, "Native stress machine has no recipe map");
        String key = map.unlocalizedName + ":"
            + itemsIn
            + ":"
            + itemsOut
            + ":"
            + fluidsIn
            + ":"
            + fluidsOut
            + ":"
            + multi;
        GTRecipe cached = selectedRecipes.get(key);
        if (cached != null) return cached;
        if (map == RecipeMaps.furnaceRecipes) {
            GTRecipe recipe = map.findRecipeQuery()
                .items(new ItemStack(Blocks.cobblestone))
                .find();
            demand(recipe != null, "Native furnace backend rejected cobblestone smelting");
            selectedRecipes.put(key, recipe);
            return recipe;
        }
        List<GTRecipe> candidates = new ArrayList<>();
        for (GTRecipe recipe : map.getAllRecipes()) {
            if (!recipe.mEnabled || recipe.mHidden
                || recipe.mFakeRecipe
                || recipe.mSpecialValue != 0
                || recipe.mSpecialItems != null
                || recipe.mEUt <= 0
                || recipe.mEUt > 128
                || recipe.mDuration < 20
                || recipe.mDuration > 1200
                || inputSlots(recipe) > itemsIn
                || count(recipe.mOutputs) > itemsOut
                || count(recipe.mFluidInputs) > fluidsIn
                || count(recipe.mFluidOutputs) > fluidsOut
                || count(recipe.mOutputs) + count(recipe.mFluidOutputs) == 0
                || recipe.getMetadataOrDefault(CentrifugeRecipeKey.INSTANCE, Boolean.FALSE)) continue;
            candidates.add(recipe);
        }
        candidates.sort(
            Comparator.comparingInt((GTRecipe r) -> count(r.mInputs) + count(r.mFluidInputs))
                .thenComparingInt(r -> r.mEUt)
                .thenComparingInt(r -> r.mDuration)
                .thenComparing(StressMachineFixtures::recipeIdentity));
        demand(!candidates.isEmpty(), "No bounded, native, special-condition-free recipe in " + map.unlocalizedName);
        GTRecipe recipe = candidates.get(0);
        selectedRecipes.put(key, recipe);
        return recipe;
    }

    private static GTRecipe fuelRecipe(RecipeMap<?> map) {
        List<GTRecipe> fuels = new ArrayList<>();
        for (GTRecipe recipe : map.getAllRecipes()) {
            if (recipe.mEnabled && !recipe.mFakeRecipe
                && !recipe.mHidden
                && recipe.mSpecialValue > 0
                && recipe.mSpecialValue <= 2048
                && fuel(recipe) != null) fuels.add(recipe);
        }
        fuels.sort(
            Comparator.comparingInt((GTRecipe r) -> -r.mSpecialValue)
                .thenComparing(StressMachineFixtures::recipeIdentity));
        demand(!fuels.isEmpty(), "No native combustible fluid in " + map.unlocalizedName);
        return fuels.get(0);
    }

    private static FluidStack fuel(GTRecipe recipe) {
        if (recipe.mFluidInputs.length > 0 && recipe.mFluidInputs[0] != null)
            return GTUtility.copyAmount(1, recipe.mFluidInputs[0]);
        if (recipe.mInputs.length > 0 && recipe.mInputs[0] != null) {
            FluidStack fluid = GTUtility.getFluidForFilledItem(recipe.mInputs[0], true);
            if (fluid != null) return GTUtility.copyAmount(1, fluid);
        }
        return null;
    }

    static String recipeIdentity(GTRecipe recipe) {
        StringBuilder id = new StringBuilder();
        for (ItemStack item : recipe.mInputs) if (item != null) id.append(itemIdentity(item))
            .append(';');
        for (FluidStack fluid : recipe.mFluidInputs) if (fluid != null) id.append(
            fluid.getFluid()
                .getName())
            .append('@')
            .append(fluid.amount)
            .append(';');
        id.append("->");
        for (ItemStack item : recipe.mOutputs) if (item != null) id.append(itemIdentity(item))
            .append(';');
        for (FluidStack fluid : recipe.mFluidOutputs) if (fluid != null) id.append(
            fluid.getFluid()
                .getName())
            .append('@')
            .append(fluid.amount)
            .append(';');
        return id.toString();
    }

    private static int pendingRecipeBatch(Machine machine, MTEMultiBlockBase multi) {
        if (machine.recipe == null) return 0;
        long batch = -1;
        GTRecipe recipe = machine.recipe;
        for (int i = 0; i < recipe.mOutputs.length; i++) {
            ItemStack expected = recipe.mOutputs[i];
            if (expected == null || expected.stackSize <= 0) continue;
            if (recipe.mOutputChances != null && recipe.mOutputChances[i] < 10000) return 0;
            long amount = 0, expectedAmount = 0;
            for (ItemStack output : recipe.mOutputs)
                if (output != null && GTUtility.areStacksEqual(expected, output)) expectedAmount += output.stackSize;
            if (multi.mOutputItems != null) for (ItemStack pending : multi.mOutputItems)
                if (pending != null && GTUtility.areStacksEqual(expected, pending)) amount += pending.stackSize;
            if (amount == 0 || amount % expectedAmount != 0) return 0;
            long current = amount / expectedAmount;
            if (batch != -1 && batch != current) return 0;
            batch = current;
        }
        for (int i = 0; i < recipe.mFluidOutputs.length; i++) {
            FluidStack expected = recipe.mFluidOutputs[i];
            if (expected == null || expected.amount <= 0) continue;
            if (recipe.mFluidOutputChances != null && recipe.mFluidOutputChances[i] < 10000) return 0;
            long amount = 0, expectedAmount = 0;
            for (FluidStack output : recipe.mFluidOutputs)
                if (output != null && expected.isFluidEqual(output)) expectedAmount += output.amount;
            if (multi.mOutputFluids != null) for (FluidStack pending : multi.mOutputFluids)
                if (pending != null && expected.isFluidEqual(pending)) amount += pending.amount;
            if (amount == 0 || amount % expectedAmount != 0) return 0;
            long current = amount / expectedAmount;
            if (batch != -1 && batch != current) return 0;
            batch = current;
        }
        if (multi.mOutputItems != null) for (ItemStack pending : multi.mOutputItems) {
            if (pending == null || pending.stackSize <= 0) continue;
            boolean matched = false;
            for (ItemStack expected : recipe.mOutputs)
                if (expected != null && GTUtility.areStacksEqual(expected, pending)) matched = true;
            if (!matched) return 0;
        }
        if (multi.mOutputFluids != null) for (FluidStack pending : multi.mOutputFluids) {
            if (pending == null || pending.amount <= 0) continue;
            boolean matched = false;
            for (FluidStack expected : recipe.mFluidOutputs)
                if (expected != null && expected.isFluidEqual(pending)) matched = true;
            if (!matched) return 0;
        }
        return batch > 0 && batch <= Integer.MAX_VALUE ? (int) batch : 0;
    }

    private static String itemIdentity(ItemStack item) {
        return Item.itemRegistry.getNameForObject(item.getItem()) + "@"
            + item.getItemDamage()
            + "x"
            + item.stackSize
            + (item.hasTagCompound() ? item.getTagCompound()
                .toString() : "");
    }

    private static int count(Object[] values) {
        int count = 0;
        for (Object value : values) if (value != null) count++;
        return count;
    }

    private static int inputSlots(GTRecipe recipe) {
        int slots = 0;
        for (ItemStack item : recipe.mInputs) if (item != null && !(item.stackSize == 0 && isCircuit(item))) slots++;
        return slots;
    }

    private static boolean isCircuit(ItemStack item) {
        return item.getItem() == ItemList.Circuit_Integrated.get(1)
            .getItem();
    }

    private static boolean needsMuffler(String type) {
        return !type.equals("MTEVacuumFreezer") && !type.equals("MTELargeChemicalReactor")
            && !type.equals("MTEIndustrialExtractor")
            && !type.equals("MTEIndustrialBrewery")
            && !type.equals("MTELargeFluidExtractor")
            && !type.equals("MTEMegaChemicalReactor");
    }

    private static Casings primaryCasing(String type) {
        switch (type) {
            case "MTEVacuumFreezer":
                return Casings.FrostProofMachineCasing;
            case "MTEIndustrialCentrifuge":
                return Casings.CentrifugeCasing;
            case "MTEIndustrialElectrolyzer":
                return Casings.ElectrolyzerCasing;
            case "MTEIndustrialMixer":
                return Casings.MixerCasing;
            case "MTEIndustrialSifter":
                return Casings.IndustrialSieveCasing;
            case "MTEIndustrialWireMill":
                return Casings.WireFactoryCasing;
            case "MTEIndustrialThermalCentrifuge":
                return Casings.ThermalProcessingCasing;
            case "MTEIndustrialForgeHammer":
                return Casings.ForgeCasing;
            case "MTEIndustrialBendingMachine":
            case "MTEIndustrialFormingPress":
                return Casings.MaterialPressCasing;
            case "MTEIndustrialExtractor":
                return Casings.CleanStainlessSteelMachineCasing;
            case "MTEIndustrialBrewery":
                return Casings.ReinforcedWoodenCasing;
            case "MTEIndustrialChemicalBath":
                return Casings.WashPlantCasing;
            case "MTELargeFluidExtractor":
                return Casings.RobustTungstenSteelMachineCasing;
            case "MTEIndustrialMacerator":
                return Casings.StableTitaniumMachineCasing;
            case "MTELargeChemicalReactor":
                return Casings.ChemicallyInertMachineCasing;
            case "MTEMegaChemicalReactor":
                return Casings.ChemicallyInertMachineCasing;
            case "MTEMultiFurnace":
                return Casings.HeatProofMachineCasing;
            case "MTEIntegratedOreFactory":
                return Casings.CleanStainlessSteelMachineCasing;
            default:
                throw new IllegalArgumentException("No native casing for " + type);
        }
    }

    private static List<HatchPosition> hatchPositions(TestContext ctx, Machine machine, StressMachineLayout.Slot slot,
        Casings casing) {
        List<HatchPosition> positions = new ArrayList<>();
        for (int z = slot.minZ; z <= slot.maxZ; z++)
            for (int y = slot.minY; y <= slot.maxY; y++) for (int x = slot.minX; x <= slot.maxX; x++) {
                if (ctx.world()
                    .getBlock(x, y, z) != casing.getBlock()
                    || ctx.world()
                        .getBlockMetadata(x, y, z) != casing.getBlockMeta())
                    continue;
                // Forge hammer's decorative upper E blocks use the same casing but cannot accept hatches.
                if (machine.type.equals("MTEIndustrialForgeHammer") && y > machine.controller.yCoord) continue;
                if (machine.type.equals("MTEMultiFurnace") && y != slot.minY) continue;
                ForgeDirection side;
                if (z == slot.minZ) side = ForgeDirection.NORTH;
                else if (machine.type.equals("MTEIndustrialMacerator")) continue; // Native allowOnly(NORTH).
                else if (y == slot.maxY) side = ForgeDirection.UP;
                else if (x == slot.minX) side = ForgeDirection.WEST;
                else if (x == slot.maxX) side = ForgeDirection.EAST;
                else if (z == slot.maxZ) side = ForgeDirection.SOUTH;
                else continue;
                if (slot.containsConnection(x + side.offsetX, y + side.offsetY, z + side.offsetZ))
                    positions.add(new HatchPosition(x, y, z, side));
            }
        demand(
            positions.size() >= (machine.type.equals("MTEMultiFurnace") ? 5 : 10),
            "Insufficient legal casing surfaces for " + machine.type + ": " + positions.size());
        return positions;
    }

    private static final class HatchPosition {

        final int x, y, z;
        final ForgeDirection side;

        HatchPosition(int x, int y, int z, ForgeDirection side) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.side = side;
        }
    }

    private static void formed(TestContext ctx, Machine machine, MTEMultiBlockBase multi) {
        if (!multi.checkStructure(true, machine.controller)) {
            List<StructureError> errors = new ArrayList<>();
            multi.clearHatches();
            multi.checkMachine(machine.controller, null, errors);
            StringBuilder detail = new StringBuilder();
            for (StructureError error : errors) detail.append(" [")
                .append(error.getId())
                .append("] ")
                .append(error.getDisplayString());
            throw new IllegalStateException(
                "Native structure rejected machine " + machine.index
                    + " "
                    + machine.type
                    + " at "
                    + machine.controller.xCoord
                    + ","
                    + machine.controller.yCoord
                    + ","
                    + machine.controller.zCoord
                    + ": "
                    + detail);
        }
        ctx.event(
            "native_structure",
            machine.index + ":"
                + machine.type
                + "; formed=true; energyHatches="
                + machine.energy.size()
                + "; recipe="
                + (machine.recipe == null ? "native_ore_processing_pipeline" : recipeIdentity(machine.recipe)));
    }

    private static int registerNativeBlocks(TestContext ctx, StressMachineLayout.Slot slot) {
        int blocks = 0;
        for (int x = slot.minX; x <= slot.maxX; x++)
            for (int y = slot.minY; y <= slot.maxY; y++) for (int z = slot.minZ; z <= slot.maxZ; z++) if (!ctx.world()
                .isAirBlock(x, y, z)) {
                    TestWorldProbes.addPosition(x, y, z);
                    blocks++;
                }
        return blocks;
    }

    private static void demand(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
