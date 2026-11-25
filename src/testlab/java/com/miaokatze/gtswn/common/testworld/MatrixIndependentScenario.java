// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.assembly.MatrixAssemblyMember;
import com.miaokatze.gtswn.common.assembly.MatrixFluidInputHatch;
import com.miaokatze.gtswn.common.assembly.MatrixItemInputBus;
import com.miaokatze.gtswn.common.assembly.OrderedAssemblyGroup;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;

import appeng.api.AEApi;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.storage.data.IAEStack;
import appeng.container.ContainerNull;
import appeng.util.Platform;
import appeng.util.inv.MEInventoryCrafting;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/** Actual world ports and AE nodes; no processing controller, research stick or synthetic storage permission. */
final class MatrixIndependentScenario implements TestScenario {

    private final List<MatrixAssemblyMember> anchors = new ArrayList<>();

    @Override
    public String id() {
        return "ordered_assembly.independent_distribution";
    }

    @Override
    public String description() {
        return "Standalone horizontal/vertical mixed ports and item-only/fluid-only columns: real AE node publication, whole delivery and sided extraction";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public void start(TestContext ctx) {
        anchors.clear();
        UUID owner = TestWorldFixtures.owner("matrix-independent");
        for (int kind = 0; kind < 4; kind++) {
            int x = -20 + kind * 12, y = 70, z = 20;
            boolean fluidOnly = kind == 3, vertical = kind == 1;
            MatrixAssemblyMember first = member(ctx, x, y, z, fluidOnly, owner);
            member(ctx, x + (vertical ? 0 : 1), y + (vertical ? 1 : 0), z, fluidOnly, owner);
            if (kind < 2) member(ctx, x, y, z + 1, true, owner);
            anchors.add(first);
            TestWorldFixtures.aeOwnedBlock(
                ctx.world(),
                x,
                y - 1,
                z,
                AEApi.instance()
                    .definitions()
                    .blocks()
                    .controller()
                    .maybeStack(1)
                    .get(),
                TestActors.player(ctx.world(), "owner"));
            TestWorldFixtures.aeOwnedBlock(
                ctx.world(),
                x,
                y - 2,
                z,
                AEApi.instance()
                    .definitions()
                    .blocks()
                    .energyCellCreative()
                    .maybeStack(1)
                    .get(),
                TestActors.player(ctx.world(), "owner"));
            OrderedAssemblyGroup.refreshTopology(first);
            require(
                ctx,
                first.matrixPort()
                    .group() != null,
                "Independent physical group formed: kind=" + kind);
        }
    }

    private MatrixAssemblyMember member(TestContext ctx, int x, int y, int z, boolean fluid, UUID owner) {
        BaseMetaTileEntity tile = TestWorldFixtures.machine(
            ctx.world(),
            x,
            y,
            z,
            (fluid ? GTSWNItemList.Matrix_Grouped_Fluid_Input_ME : GTSWNItemList.Matrix_Grouped_Item_Input_ME).get(1),
            owner);
        tile.setFrontFacing(ForgeDirection.DOWN);
        return (MatrixAssemblyMember) tile.getMetaTileEntity();
    }

    @Override
    public void tick(TestContext ctx) {
        if (ctx.tick() < 60) return;
        for (MatrixAssemblyMember anchor : anchors) {
            OrderedAssemblyGroup.refreshTopology(anchor);
            if (!anchor.matrixPort()
                .getProxy()
                .isActive()
                || !anchor.matrixPort()
                    .group()
                    .accepting()) {
                if (ctx.tick() > 400) throw new IllegalStateException(
                    "Independent group not ready: " + anchor.matrixPort()
                        .group()
                        .reason());
                return;
            }
        }
        for (int kind = 0; kind < anchors.size(); kind++) exercise(ctx, anchors.get(kind), kind);
        ctx.pass(
            "Four real standalone layouts publish and accept without controllers/research, preserve pattern isolation, and unlock only after all resources are extracted");
    }

    private void exercise(TestContext ctx, MatrixAssemblyMember anchor, int kind) {
        OrderedAssemblyGroup group = anchor.matrixPort()
            .group();
        require(
            ctx,
            group.session() == null && group.diagnosticControllerPosition() == null,
            "No downstream controller or research owner exists: kind=" + kind);
        OrderedPatternLayout a = OrderedPatternLayout.decode(pattern(group, false), ctx.world());
        OrderedPatternLayout b = OrderedPatternLayout.decode(pattern(group, true), ctx.world());
        group.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(0, a.pattern());
        group.master()
            .matrixPort()
            .patterns()
            .setStackInSlot(1, b.pattern());
        int[] options = { 0 };
        group.provideCrafting(
            (ICraftingProviderHelper) Proxy.newProxyInstance(
                ICraftingProviderHelper.class.getClassLoader(),
                new Class<?>[] { ICraftingProviderHelper.class },
                (proxy, method, args) -> {
                    options[0]++;
                    return null;
                }));
        require(ctx, options[0] == 2, "Both patterns published without research: kind=" + kind);
        require(
            ctx,
            anchor.matrixPort()
                .pushPattern(a.details(), table(a)),
            "First complete A package accepted");
        require(
            ctx,
            anchor.matrixPort()
                .pushPattern(a.details(), table(a)),
            "Second identical A package accumulates");
        require(
            ctx,
            !anchor.matrixPort()
                .pushPattern(b.details(), table(b)),
            "B with identical inputs but different output is isolated from A");
        for (MatrixAssemblyMember member : group.itemMembers()) {
            MatrixItemInputBus bus = (MatrixItemInputBus) member;
            require(
                ctx,
                bus.getFirstStack() != null && bus.getFirstStack().stackSize == 4,
                "Repeated item positions retain separate quantities");
            require(
                ctx,
                bus.allowPullStack(bus.getBaseMetaTileEntity(), 0, ForgeDirection.EAST, bus.getFirstStack()),
                "Sided item extraction exposed without a machine");
            require(
                ctx,
                !bus.allowPutStack(bus.getBaseMetaTileEntity(), 0, ForgeDirection.EAST, bus.getFirstStack()),
                "External item insertion remains denied");
            bus.decrStackSize(0, 3);
            require(
                ctx,
                !anchor.matrixPort()
                    .pushPattern(b.details(), table(b)),
                "Partial item extraction keeps A locked");
            bus.decrStackSize(0, 1);
        }
        if (!group.fluidMembers()
            .isEmpty()) {
            require(
                ctx,
                !anchor.matrixPort()
                    .pushPattern(b.details(), table(b)),
                "Residual fluid keeps A locked after item extraction");
            for (MatrixAssemblyMember member : group.fluidMembers()) {
                MatrixFluidInputHatch hatch = (MatrixFluidInputHatch) member;
                require(
                    ctx,
                    hatch.canDrain(ForgeDirection.UP, FluidRegistry.WATER)
                        && !hatch.canFill(ForgeDirection.UP, FluidRegistry.WATER),
                    "Sided fluid drain and insertion contracts agree");
                require(
                    ctx,
                    hatch.drain(ForgeDirection.UP, 200, false).amount == 200 && hatch.getFluidAmount() == 200,
                    "Simulated fluid drain leaves actual stock intact");
                require(
                    ctx,
                    hatch.drain(ForgeDirection.UP, 199, true).amount == 199,
                    "Partial fluid extraction succeeds");
                require(
                    ctx,
                    !anchor.matrixPort()
                        .pushPattern(b.details(), table(b)),
                    "One remaining mB still holds A");
                hatch.drain(ForgeDirection.UP, 1, true);
            }
        }
        require(
            ctx,
            anchor.matrixPort()
                .pushPattern(b.details(), table(b)),
            "All-empty group accepts different B immediately");
        require(
            ctx,
            !anchor.matrixPort()
                .pushPattern(a.details(), table(a)),
            "New B batch is isolated from A");
        for (MatrixAssemblyMember member : group.itemMembers())
            ((MatrixItemInputBus) member).decrStackSize(0, Integer.MAX_VALUE);
        for (MatrixAssemblyMember member : group.fluidMembers())
            ((MatrixFluidInputHatch) member).drain(ForgeDirection.UP, Integer.MAX_VALUE, true);
        require(ctx, group.editable(), "Drained group unlocks pattern management");
        ctx.event("independent_layout", "kind=" + kind + "; ledger=" + group.saveLedger());
    }

    private ItemStack pattern(OrderedAssemblyGroup group, boolean alternate) {
        ItemStack encoded = AEApi.instance()
            .definitions()
            .items()
            .encodedUltimatePattern()
            .maybeStack(1)
            .get();
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList input = new NBTTagList(), output = new NBTTagList();
        for (MatrixAssemblyMember ignored : group.itemMembers()) input.appendTag(
            Platform.writeStackNBT(AEItemStack.create(new ItemStack(Items.iron_ingot, 2)), new NBTTagCompound()));
        for (MatrixAssemblyMember ignored : group.fluidMembers()) input.appendTag(
            Platform
                .writeStackNBT(AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 100)), new NBTTagCompound()));
        output.appendTag(
            Platform.writeStackNBT(
                AEItemStack.create(new ItemStack(alternate ? Items.apple : Items.stick)),
                new NBTTagCompound()));
        tag.setTag("in", input);
        tag.setTag("out", output);
        tag.setBoolean("crafting", false);
        tag.setBoolean("substitute", false);
        encoded.setTagCompound(tag);
        return encoded;
    }

    private MEInventoryCrafting table(OrderedPatternLayout layout) {
        IAEStack<?>[] inputs = layout.details()
            .getAEInputs();
        MEInventoryCrafting table = new MEInventoryCrafting(new ContainerNull(), inputs.length, 1);
        for (int i = 0; i < inputs.length; i++)
            table.setInventorySlotContents(i, inputs[i] == null ? null : inputs[i].copy());
        return table;
    }

    private static void require(TestContext ctx, boolean condition, String message) {
        ctx.check(condition, message);
        if (!condition) throw new IllegalStateException(message);
    }
}
