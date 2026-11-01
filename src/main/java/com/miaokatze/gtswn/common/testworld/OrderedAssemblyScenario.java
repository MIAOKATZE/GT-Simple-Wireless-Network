package com.miaokatze.gtswn.common.testworld;

import static appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;
import static appeng.util.item.AEItemStackType.ITEM_STACK_TYPE;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyHooks;
import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblySession;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.assembly.MatrixAssemblyMember;
import com.miaokatze.gtswn.common.assembly.MatrixFluidInputHatch;
import com.miaokatze.gtswn.common.assembly.MatrixItemInputBus;
import com.miaokatze.gtswn.common.assembly.OrderedAssemblyGroup;
import com.miaokatze.gtswn.common.assembly.pattern.AssemblyRecipeKey;
import com.miaokatze.gtswn.common.assembly.pattern.AssemblyRecipeMatch;
import com.miaokatze.gtswn.common.assembly.pattern.OrderedPatternLayout;
import com.miaokatze.gtswn.common.assembly.pattern.PatternValidationException;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.parts.IPartHost;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.util.AEColor;
import appeng.api.util.IInterfaceViewable;
import appeng.container.implementations.ContainerInterfaceTerminal;
import appeng.core.sync.packets.PacketInterfaceTerminalUpdate;
import appeng.helpers.InventoryAction;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.reporting.PartInterfaceTerminal;
import appeng.tile.storage.TileDrive;
import appeng.util.Platform;
import appeng.util.inv.MEInventoryCrafting;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.network.handshake.NetworkDispatcher;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.VoidingMode;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.AssemblyLineUtils;
import gregtech.api.util.GTRecipe.RecipeAssemblyLine;
import gregtech.common.tileentities.machines.multi.MTEAssemblyLine;
import io.netty.channel.embedded.EmbeddedChannel;

/** One explicit real-world probe. It creates no recipes and never advances a machine or CPU clock by hand. */
final class OrderedAssemblyScenario implements TestScenario {

    private static final int X = -4, Y = 69, Z = 12;
    private static final int INPUTS = 16, COPIES = 16;
    private BaseMetaTileEntity controllerTile, energyTile, outputTile;
    private MTEAssemblyLine controller;
    private MTEHatchOutputBus output;
    private NativeAssemblySession session;
    private OrderedAssemblyGroup group;
    private MatrixAssemblyMember patternAnchor;
    private FakePlayer owner;
    private TileEntity aeController;
    private TileDrive drive;
    private AENetworkProxy proxy;
    private MachineSource source;
    private RecipeAssemblyLine recipe;
    private ItemStack stick, pattern;
    private OrderedPatternLayout layout;
    private Future<ICraftingJob> planning;
    private ICraftingLink link;
    private ICraftingCPU cpu;
    private PartInterfaceTerminal interfacePart;
    private ContainerInterfaceTerminal interfaceContainer;
    private Object masterTracker;
    private long masterTrackerId;
    private EmbeddedChannel actorChannel;
    private long[] seededTotals;
    private int blockedSamples;
    private String firstLink;
    private int stage, batch, maxProgress;
    private long stageTick, firstRun;
    private String stagedStock;
    private boolean progressObserved, powerObserved;
    private int pausedProgress;
    private long pausedSequence;
    private String pausedStock;

    @Override
    public String id() {
        return "ordered_assembly.native_probe";
    }

    @Override
    public String description() {
        return "Sixteen real AE CPU requests cached by ordered position, reversed ME entry, and native pause/recovery";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int timeoutTicks() {
        return 7200;
    }

    @Override
    public void start(TestContext ctx) throws Exception {
        stage = batch = maxProgress = 0;
        firstRun = 0;
        patternAnchor = null;
        actorChannel = null;
        progressObserved = powerObserved = false;
        owner = TestActors.player(ctx.world(), "owner");
        controllerTile = TestWorldFixtures
            .machine(ctx.world(), X, Y, Z, ItemList.Machine_Multi_Assemblyline.get(1), owner.getUniqueID());
        controller = (MTEAssemblyLine) controllerTile.getMetaTileEntity();
        controllerTile.disableWorking();
        controller.setVoidingMode(VoidingMode.VOID_NONE);
        session = NativeAssemblyAdapter.session(controller);
        require(
            ctx,
            NativeAssemblyHooks.ready() && session != null,
            "All three required ordered-assembly Mixins are applied to the actual runtime controller");
        ctx.event(
            "runtime_hooks",
            "ready=" + NativeAssemblyHooks.ready()
                + "; controller="
                + controller.getClass()
                    .getName()
                + "; identity="
                + session.controllerId());
        buildLine(ctx);
        pureFluidColumn(ctx);
        checkStructure(ctx);
        require(
            ctx,
            controller.mMachine && controller.mInputBusses.size() == INPUTS,
            "The actual sixteen-slice native structure is formed with sixteen item inputs");
        group = master().matrixPort()
            .group();
        require(
            ctx,
            group != null && group.itemMembers()
                .size() == INPUTS
                && group.fluidMembers()
                    .size() == 3,
            "Successful native registration binds sixteen item owners and three fluid owners");
        otherControllerCounterexample(ctx);
        controllerTile.enableWorking();
        BaseMetaTileEntity branch = machine(
            ctx,
            1,
            3,
            1,
            GTSWNItemList.Matrix_Grouped_Item_Input_ME.get(1),
            ForgeDirection.DOWN);
        checkStructure(ctx);
        OrderedAssemblyGroup cachedGroup = group;
        group = master().matrixPort()
            .group();
        require(ctx, group != null, "The branch refusal is checked on the current physical members' group");
        boolean nativeBegin = session.beginCheck();
        boolean ownersBound = true, connectionsCleared = true;
        for (MTEHatchInputBus input : controller.mInputBusses) {
            MatrixAssemblyMember member = (MatrixAssemblyMember) input;
            ownersBound &= member.matrixPort()
                .group() == group;
            connectionsCleared &= member.matrixPort()
                .connectionMask() == 0;
        }
        for (var input : controller.mInputHatches) {
            MatrixAssemblyMember member = (MatrixAssemblyMember) input;
            ownersBound &= member.matrixPort()
                .group() == group;
            connectionsCleared &= member.matrixPort()
                .connectionMask() == 0;
        }
        ctx.event(
            "branch_rejection",
            "cachedIsCurrent=" + (cachedGroup == group)
                + "; reason="
                + group.reason()
                + "; editable="
                + group.editable()
                + "; accepting="
                + group.accepting()
                + "; ownersBound="
                + ownersBound
                + "; connectionsCleared="
                + connectionsCleared
                + "; branchBound="
                + (((MatrixAssemblyMember) branch.getMetaTileEntity()).matrixPort()
                    .group() != null)
                + "; nativeBegin="
                + nativeBegin
                + "; blocked="
                + session.blockedReason());
        require(
            ctx,
            !group.accepting() && !group.editable()
                && "item_column".equals(group.reason())
                && ownersBound
                && connectionsCleared
                && ((MatrixAssemblyMember) branch.getMetaTileEntity()).matrixPort()
                    .group() == null
                && !nativeBegin,
            "A physically adjacent same-kind branch cannot form a second row");
        ctx.world()
            .setBlockToAir(branch.xCoord, branch.yCoord, branch.zCoord);
        checkStructure(ctx);
        group = master().matrixPort()
            .group();
        require(
            ctx,
            group != null && group.editable() && group.session() == session,
            "Removing the real branch restores the current members' configurable native binding");
        long epoch = group.epoch();
        checkStructure(ctx);
        require(
            ctx,
            master().matrixPort()
                .group() == group && group.epoch() == epoch,
            "Identical native structure rechecks retain the current group and topology epoch");
        selectRecipe(ctx);
        duplicateRawPositions(ctx);
        patternAnchor = group.master();
        controller.setInventorySlotContents(1, null);
        fillOutput();
        buildAe(ctx);
        // Remove a real casing without changing the physical input column or its pattern identity.
        int[] casing = position(2, 0, 1);
        require(
            ctx,
            !ctx.world()
                .isAirBlock(casing[0], casing[1], casing[2]),
            "The selected AL casing really exists");
        ctx.world()
            .setBlockToAir(casing[0], casing[1], casing[2]);
        require(
            ctx,
            ctx.world()
                .isAirBlock(casing[0], casing[1], casing[2]),
            "The real AL casing was removed");
        checkStructure(ctx);
        require(
            ctx,
            !controller.mMachine && group.editable() && !group.accepting(),
            "A legal bare input column remains configurable while the actual AL is unformed");
        controllerTile.enableWorking();
        transition(ctx, 1, "Wait for physical AE grid, real cells and 2x2x2 CPU to become active");
    }

    @Override
    public void tick(TestContext ctx) throws Exception {
        if (controller.mMaxProgresstime > 0) {
            maxProgress = Math.max(maxProgress, controller.mProgresstime);
            progressObserved |= controller.mProgresstime > 0;
            powerObserved |= controller.lEUt < 0;
        }
        if (ctx.tick() % 20 == 0 && stage >= 4) ctx.event("native_sample", nativeState());
        if (stage == 7 && !published(ctx))
            throw new IllegalStateException("The processing output disappeared from native craftable lists while busy");
        switch (stage) {
            case 1:
                if (ctx.tick() - stageTick < 40) return;
                proxy = ((IGridProxyable) aeController).getProxy();
                if (!proxy.isActive() || !master().matrixPort()
                    .getProxy()
                    .isActive()
                    || proxy.getCrafting()
                        .getCpus()
                        .isEmpty()
                    || interfacePart.getGridNode() == null
                    || !interfacePart.getGridNode()
                        .isActive()) {
                    if (ctx.tick() - stageTick > 600) throw new IllegalStateException(
                        "Physical AE grid/CPU did not activate; master=" + master().matrixPort()
                            .getProxy()
                            .isActive() + "; reason=" + group.reason());
                    return;
                }
                require(
                    ctx,
                    proxy.getCrafting()
                        .getCpus()
                        .size() == 1,
                    "Exactly one real CPU belongs to this isolated fixture grid");
                cpu = proxy.getCrafting()
                    .getCpus()
                    .iterator()
                    .next();
                source = new MachineSource((IActionHost) aeController);
                inspectInterfaceTerminal(ctx);
                installPattern(ctx);
                seedCells(ctx);
                seededTotals = materialTotals();
                firstRun = session.runSequence();
                blockedSamples = 0;
                transition(ctx, 16, "Verify no pattern publication while AL is unformed and has no research");
                break;
            case 16:
                observeBlockedCatalogue(ctx);
                if (ctx.tick() - stageTick < 40) return;
                require(
                    ctx,
                    !controller.mMachine && !group.accepting() && !session.beginCheck(),
                    "An unformed native AL neither advertises nor accepts its configured processing pattern");
                logBlockedCatalogue(ctx, "unformed");
                block(ctx, 2, 0, 1, GregTechAPI.sBlockCasings2, 9);
                checkStructure(ctx);
                clearOutput();
                require(
                    ctx,
                    controller.mMachine && controller.getStackInSlot(1) == null,
                    "Restoring the actual casing forms the AL while its research slot stays empty");
                session.requestRecipeCheck();
                blockedSamples = 0;
                transition(ctx, 14, "Verify the formed AL still withholds patterns without research authorization");
                break;
            case 2:
                if (!planning.isDone()) return;
                ICraftingJob job = planning.get();
                require(ctx, !job.isSimulation(), "Actual cell stock satisfies the AE crafting calculation");
                link = proxy.getCrafting()
                    .submitJob(job, null, cpu, false, source);
                require(ctx, link != null, "An actual AE CPU accepts the standalone crafting job");
                require(
                    ctx,
                    cpu.isBusy() && cpu.getUsedStorage() > 0,
                    "The selected real CPU holds the newly submitted job");
                ctx.event(
                    "cpu_job",
                    "batch=" + batch + "; id=" + link.getCraftingID() + "; byteTotal=" + job.getByteTotal());
                firstLink = link.getCraftingID();
                transition(ctx, 3, "Wait for all sixteen real CPU deliveries to accumulate behind output protection");
                break;
            case 14:
                observeBlockedCatalogue(ctx);
                if (ctx.tick() - stageTick < 40) return;
                require(
                    ctx,
                    controller.mMachine && controller.getStackInSlot(1) == null
                        && "no_authorized_recipe".equals(group.previewReason(layout)),
                    "Absent current research keeps the formed AL's processing pattern out of the recipe catalogue");
                logBlockedCatalogue(ctx, "formed-no-research");
                fillOutput();
                controller.setInventorySlotContents(1, stick.copy());
                session.requestRecipeCheck();
                transition(ctx, 15, "Wait for actual research authorization to publish the processing pattern");
                break;
            case 15:
                if (ctx.tick() - stageTick < 20) return;
                if (!published(ctx)) {
                    if (ctx.tick() - stageTick > 200) throw new IllegalStateException(
                        "Native crafting cache/ME craftable flag did not publish after research authorization");
                    return;
                }
                require(
                    ctx,
                    controller.mMachine && controller.getStackInSlot(1) != null
                        && !cpu.isBusy()
                        && cpu.getUsedStorage() == 0,
                    "Formation and current research publish the pattern before the first CPU job is submitted");
                logPublication(ctx, "formed-researched-before-job");
                extraConnection(ctx, true);
                transition(ctx, 18, "Verify multiple real external ME connections stop the empty group");
                break;
            case 3:
                if (group.stage() != OrderedAssemblyGroup.Stage.STAGED || !allCopiesStaged()) return;
                stagedStock = stock();
                assertStaged(ctx);
                assertLockedInterface(ctx);
                require(ctx, published(ctx), "Staged native work retains its native ME craftable output flag");
                MEInventoryCrafting retry = table(layout);
                retry.getAEStackInSlot(0)
                    .setStackSize(
                        retry.getAEStackInSlot(0)
                            .getStackSize() + 1);
                String before = tableSnapshot(retry);
                require(
                    ctx,
                    !master().matrixPort()
                        .busy(),
                    "The medium remains available for the active pattern while sixteen real copies are cached");
                require(
                    ctx,
                    !master().matrixPort()
                        .pushPattern(layout.details(), retry),
                    "An inconsistent whole delivery is rejected without injecting synthetic probe materials");
                require(
                    ctx,
                    before.equals(tableSnapshot(retry)) && stagedStock.equals(stock()),
                    "Whole-package rejection preserves the caller table and all sixteen physical owner quantities");
                ctx.event("cpu_delivery", "batch=1; stock=" + stagedStock + "; ledger=" + group.saveLedger());
                session.requestRecipeCheck();
                transition(ctx, 4, "Observe native output protection without consuming staged materials");
                break;
            case 4:
                if (ctx.tick() - stageTick < 30) return;
                require(
                    ctx,
                    group.stage() == OrderedAssemblyGroup.Stage.STAGED && controller.mMaxProgresstime == 0
                        && stagedStock.equals(stock()),
                    "Native output protection leaves the full staged batch unconsumed");
                controller.setInventorySlotContents(1, null);
                clearOutput();
                session.requestRecipeCheck();
                transition(ctx, 5, "Remove the actual research stick before native start");
                break;
            case 5:
                if (ctx.tick() - stageTick < 30) return;
                require(
                    ctx,
                    group.stage() == OrderedAssemblyGroup.Stage.STAGED && controller.mMaxProgresstime == 0
                        && stagedStock.equals(stock())
                        && session.runSequence() == firstRun
                        && !published(ctx),
                    "Removing current research withdraws the pattern, prevents start and preserves real materials");
                ctx.event("research_removed", nativeState() + "; stock=" + stock());
                fillOutput();
                controller.setInventorySlotContents(1, stick.copy());
                session.requestRecipeCheck();
                transition(ctx, 6, "Restore the same actual research stick and wait for pattern republication");
                break;
            case 6:
                if (!published(ctx)) return;
                clearOutput();
                session.requestRecipeCheck();
                transition(ctx, 17, "Allow native production after research republishes the pattern");
                break;
            case 17:
                if (session.runSequence() == firstRun) return;
                assertRunning(ctx, firstRun + 1);
                extraConnection(ctx, true);
                transition(ctx, 20, "Wait for a second actual ME attachment during powered production");
                break;
            case 7:
                assertRemainingPositions();
                if (controller.mMaxProgresstime != 0 || outputAmount() != recipe.mOutput.stackSize * COPIES) return;
                require(
                    ctx,
                    "LOCAL_FINISHED".equals(session.lastNativeEvent()),
                    "Native lifecycle records completion alongside physical output in its real bus");
                require(
                    ctx,
                    progressObserved && powerObserved && maxProgress > 0,
                    "Natural native ticks advanced real progress with negative recipe EU/t");
                ctx.event(
                    "native_local_output",
                    "batch=" + batch
                        + "; actual="
                        + outputAmount()
                        + "; output="
                        + stack(recipe.mOutput)
                        + "; maxProgress="
                        + maxProgress
                        + "; nativeBoolean="
                        + session.lastOutputAccepted()
                        + "; "
                        + nativeState());
                outputTile.enableWorking();
                transition(ctx, 8, "Let the native bus and physical ME Interface return all sixteen outputs to AE");
                break;
            case 8:
                long expected = (long) COPIES * recipe.mOutput.stackSize;
                if (cpu.isBusy() || cpu.getUsedStorage() != 0 || itemAmount(recipe.mOutput) != expected) return;
                require(
                    ctx,
                    !link.isCanceled() && outputAmount() == 0,
                    "Actual CPU completion coincides with exact crafted output in AE storage and an empty native bus");
                require(
                    ctx,
                    firstLink.equals(link.getCraftingID()),
                    "The first authorized CPU job completes without resubmission after staged research recovery");
                ctx.event(
                    "ae_delivered",
                    "batch=" + batch
                        + "; tick="
                        + ctx.tick()
                        + "; link="
                        + link.getCraftingID()
                        + "; standaloneLinkDoneDiagnostic="
                        + link.isDone()
                        + "; cpuBusy="
                        + cpu.isBusy()
                        + "; cpuUsedStorage="
                        + cpu.getUsedStorage()
                        + "; actualStored="
                        + itemAmount(recipe.mOutput));
                if (!group.accepting()) return;
                outputDeclarationCounterexample(ctx);
                for (ItemStack input : recipe.mInputs) require(
                    ctx,
                    itemAmount(input) == 0,
                    "Sixteen native runs consumed the exact seeded material stock: " + stack(input));
                for (FluidStack input : recipe.mFluidInputs)
                    require(ctx, fluidAmount(input) == 0, "Sixteen native runs consumed the exact seeded fluid stock");
                require(
                    ctx,
                    session.runSequence() == firstRun + COPIES,
                    "Exactly sixteen native single-copy consumption sequences completed from the one real CPU job");
                assertRemainingPositions();
                ctx.pass(
                    "Sixteen-position physical caching, reverse-end ordering, empty/in-flight multi-entry pause "
                        + "and recovery, pure-fluid grouping, native safety gates and exact real CPU return passed");
                break;
            case 18:
                if (group.externalConnectionCount() != 2 || ctx.tick() - stageTick < 40) return;
                require(
                    ctx,
                    !group.accepting() && !group.permitsMachineWork()
                        && !published(ctx)
                        && "multiple_external_connections".equals(group.reason())
                        && !cpu.isBusy()
                        && Arrays.equals(seededTotals, materialTotals()),
                    "Two external connections withdraw the empty group's catalogue and prohibit native work");
                ctx.event("empty_multiple_connections", nativeState());
                extraConnection(ctx, false);
                transition(ctx, 19, "Restore the same unique reverse-end ME entry before submitting real work");
                break;
            case 19:
                if (group.externalConnectionCount() != 1 || !published(ctx)) return;
                beginJob(ctx);
                transition(ctx, 2, "Calculate one real AE CPU request for sixteen newly registered matrix buses");
                break;
            case 20:
                if (group.externalConnectionCount() != 2) return;
                pausedProgress = controller.mProgresstime;
                pausedSequence = session.runSequence();
                pausedStock = stock();
                transition(ctx, 21, "Observe forty natural ticks with in-flight processing paused by multiple entries");
                break;
            case 21:
                if (ctx.tick() - stageTick < 40) return;
                require(
                    ctx,
                    group.externalConnectionCount() == 2 && !group.permitsMachineWork()
                        && controller.mProgresstime == pausedProgress
                        && session.runSequence() == pausedSequence
                        && pausedStock.equals(stock())
                        && controller.mMaxProgresstime > 0,
                    "Multiple external ME attachments pause actual native progress and retain all cached materials");
                ctx.event("inflight_multiple_connections", nativeState() + "; stock=" + stock());
                extraConnection(ctx, false);
                transition(ctx, 22, "Restore the original unique ME entry and resume the same native run");
                break;
            case 22:
                if (group.externalConnectionCount() != 1 || !published(ctx)) return;
                require(ctx, group.permitsMachineWork(), "The original unique ME entry restores the same group");
                transition(ctx, 7, "Observe all sixteen original native runs and exact per-position consumption");
                break;
            default:
                throw new IllegalStateException("Unknown ordered assembly probe stage " + stage);
        }
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (planning != null && !planning.isDone()) planning.cancel(false);
        if (link != null && cpu.isBusy() && !link.isCanceled()) link.cancel();
        if (controllerTile != null) controllerTile.disableWorking();
        if (outputTile != null) outputTile.disableWorking();
        if (controller != null) ctx.event("final_native_state", nativeState() + "; stock=" + stock());
        if (actorChannel != null) {
            actorChannel.close();
            ctx.event("interface_actor_transport_closed", "open=" + actorChannel.isOpen());
        }
    }

    private void buildLine(TestContext ctx) {
        for (int i = 0; i < INPUTS; i++) {
            block(ctx, i, -1, 1, GregTechAPI.sBlockCasings2, 0);
            if (i != 0) block(ctx, i, 0, 0, GregTechAPI.sBlockCasings3, 10);
            block(ctx, i, 0, 1, GregTechAPI.sBlockCasings2, 9);
            block(ctx, i, 0, 2, GregTechAPI.sBlockCasings3, 10);
            block(ctx, i, 1, 0, Blocks.glass, 0);
            block(ctx, i, 1, 1, GregTechAPI.sBlockCasings2, 5);
            block(ctx, i, 1, 2, Blocks.glass, 0);
            block(ctx, i, 2, 0, GregTechAPI.sBlockCasings2, 0);
            block(ctx, i, 2, 2, GregTechAPI.sBlockCasings2, 0);
            machine(ctx, i, 2, 1, GTSWNItemList.Matrix_Grouped_Item_Input_ME.get(1), ForgeDirection.DOWN);
        }
        for (int i = 0; i < 3; i++)
            machine(ctx, i, 2, 0, GTSWNItemList.Matrix_Grouped_Fluid_Input_ME.get(1), ForgeDirection.DOWN);
        outputTile = machine(ctx, INPUTS - 1, 2, 0, ItemList.Hatch_Output_Bus_LV.get(1), ForgeDirection.DOWN);
        outputTile.disableWorking();
        output = (MTEHatchOutputBus) outputTile.getMetaTileEntity();
        machine(ctx, 0, 2, 2, ItemList.Hatch_Maintenance.get(1), ForgeDirection.DOWN);
        energyTile = machine(ctx, 0, -1, 1, ItemList.Hatch_Energy_UHV.get(1), ForgeDirection.UP);
        energyTile.attachCover(
            CoverRegistry.buildCover(ItemList.Cover_Wireless_Energy_Debug.get(1), ForgeDirection.UP, energyTile));
        ctx.event(
            "power_fixture",
            "Native GT wireless energy debug cover on a real UHV energy hatch; "
                + "no per-tick material insertion, manual progress advance or manual native completion");
    }

    private void checkStructure(TestContext ctx) {
        controller.mWrench = controller.mScrewdriver = controller.mSoftMallet = controller.mHardHammer = controller.mSolderingTool = controller.mCrowbar = true;
        controller.mEfficiency = 10000;
        boolean formed = controller.checkStructure(true, controllerTile);
        ctx.event(
            "structure_scan",
            "formed=" + formed
                + "; item="
                + controller.mInputBusses.size()
                + "; fluid="
                + controller.mInputHatches.size()
                + "; output="
                + controller.mOutputBusses.size());
    }

    private void otherControllerCounterexample(TestContext ctx) {
        BaseMetaTileEntity other = TestWorldFixtures
            .machine(ctx.world(), 12, Y, 20, ItemList.Machine_Multi_BlastFurnace.get(1), owner.getUniqueID());
        other.disableWorking();
        MTEMultiBlockBase machine = (MTEMultiBlockBase) other.getMetaTileEntity();
        BaseMetaTileEntity item = (BaseMetaTileEntity) master().getBaseMetaTileEntity();
        BaseMetaTileEntity fluid = (BaseMetaTileEntity) group.fluidMembers()
            .get(0)
            .getBaseMetaTileEntity();
        require(
            ctx,
            machine.addInputBusToMachineList(item, 16) && machine.addInputHatchToMachineList(fluid, 16)
                && machine.mInputBusses.contains(item.getMetaTileEntity())
                && machine.mInputHatches.contains(fluid.getMetaTileEntity()),
            "A normal native GT controller accepts ordinary item/fluid input registration without bypassing formation");
        machine.mInputBusses.clear();
        machine.mInputHatches.clear();
    }

    private void pureFluidColumn(TestContext ctx) {
        BaseMetaTileEntity first = TestWorldFixtures
            .machine(ctx.world(), 12, Y, 24, GTSWNItemList.Matrix_Grouped_Fluid_Input_ME.get(1), owner.getUniqueID());
        BaseMetaTileEntity second = TestWorldFixtures
            .machine(ctx.world(), 13, Y, 24, GTSWNItemList.Matrix_Grouped_Fluid_Input_ME.get(1), owner.getUniqueID());
        MatrixAssemblyMember member = (MatrixAssemblyMember) first.getMetaTileEntity();
        OrderedAssemblyGroup.refreshTopology(member);
        OrderedAssemblyGroup fluidGroup = member.matrixPort()
            .group();
        require(
            ctx,
            fluidGroup != null && fluidGroup.itemMembers()
                .isEmpty()
                && fluidGroup.fluidMembers()
                    .size() == 2
                && fluidGroup.editable()
                && fluidGroup.master()
                    .matrixFluid()
                && !fluidGroup.accepting(),
            "Two real horizontal fluid members form an independently configurable pure-fluid group");
        ctx.world()
            .setBlockToAir(first.xCoord, first.yCoord, first.zCoord);
        ctx.world()
            .setBlockToAir(second.xCoord, second.yCoord, second.zCoord);
    }

    private void selectRecipe(TestContext ctx) {
        List<RecipeAssemblyLine> candidates = new ArrayList<>();
        for (RecipeAssemblyLine candidate : RecipeAssemblyLine.sAssemblylineRecipes) {
            if (candidate.mInputs.length != INPUTS || candidate.mFluidInputs.length != 3
                || candidate.mEUt <= 0
                || candidate.mEUt > 131072
                || candidate.mDuration < 40
                || candidate.mDuration > 2400
                || candidate.mOutput == null
                || !candidate.mOutput.isItemEqual(GTSWNItemList.Matrix_Grouped_Item_Input_ME.get(1))
                || candidate.mOutput.stackSize <= 0
                || candidate.mOutput.stackSize > 64) continue;
            boolean valid = true;
            for (FluidStack fluid : candidate.mFluidInputs)
                valid &= fluid != null && fluid.amount == 576 && fluid.tag == null;
            for (ItemStack input : candidate.mInputs)
                valid &= input != null && input.stackSize > 0 && input.stackSize <= 64;
            if (valid) candidates.add(candidate);
        }
        candidates.sort(
            Comparator.comparingInt((RecipeAssemblyLine r) -> r.mDuration)
                .thenComparing(
                    r -> AssemblyRecipeKey.of(r)
                        .value()));
        for (RecipeAssemblyLine candidate : candidates) {
            ItemStack encoded = encode(candidate.mInputs, candidate.mFluidInputs, candidate.mOutput, true);
            OrderedPatternLayout decoded = OrderedPatternLayout.decode(encoded, ctx.world());
            ItemStack data = ItemList.Tool_DataStick.get(1);
            if (!AssemblyLineUtils.setAssemblyLineRecipeOnDataStick(data, candidate)) continue;
            try {
                AssemblyRecipeMatch match = AssemblyRecipeMatch
                    .bind(decoded, new ArrayList<>(AssemblyLineUtils.findALRecipeFromDataStick(data)));
                if (!match.key()
                    .value()
                    .equals(
                        AssemblyRecipeKey.of(candidate)
                            .value()))
                    continue;
            } catch (PatternValidationException rejected) {
                continue;
            }
            recipe = candidate;
            pattern = encoded;
            layout = decoded;
            stick = data;
            break;
        }
        require(
            ctx,
            recipe != null,
            "The actual newly registered sixteen-position, three-fluid matrix item-bus recipe is selected");
        ctx.event(
            "registry_recipe",
            "registrySize=" + RecipeAssemblyLine.sAssemblylineRecipes.size()
                + "; eligible="
                + candidates.size()
                + "; key="
                + AssemblyRecipeKey.of(recipe)
                    .value()
                + "; output="
                + stack(recipe.mOutput)
                + "; duration="
                + recipe.mDuration
                + "; EUt="
                + recipe.mEUt
                + "; input="
                + recipe
                + "; researchStick="
                + stack(stick));
        ctx.event(
            "coverage_scope",
            "Actual registry recipe is used unchanged. Same-input/different-output native "
                + "candidate-pair coverage is not asserted by this bounded run; no synthetic recipe is registered.");
    }

    private void inspectInterfaceTerminal(TestContext ctx) throws Exception {
        var supported = AEApi.instance()
            .registries()
            .interfaceTerminal()
            .getSupportedClasses();
        require(
            ctx,
            supported.contains(MatrixItemInputBus.class) && supported.contains(MatrixFluidInputHatch.class),
            "The official interface-terminal registry contains both real matrix MTE classes");
        if (owner.playerNetServerHandler.netManager.channel() == null)
            actorChannel = new EmbeddedChannel(owner.playerNetServerHandler.netManager);
        require(
            ctx,
            owner.playerNetServerHandler.netManager.channel()
                .attr(NetworkDispatcher.FML_DISPATCHER)
                .get() == null,
            "The synthetic terminal actor has only an in-memory transport with no logged-in FML dispatcher");
        ctx.event(
            "interface_actor_transport",
            "nativeNetworkManager=true; socket=false; loggedInDispatcher=false; outboundAck=emptyTargets");
        interfaceContainer = new ContainerInterfaceTerminal(owner.inventory, interfacePart);
        Object packet = terminalField(interfaceContainer, "dirty");
        require(
            ctx,
            packet instanceof PacketInterfaceTerminalUpdate,
            "The real interface-terminal constructor obtains a native updateList packet");
        Map<?, ?> tracked = (Map<?, ?>) terminalField(interfaceContainer, "tracked");
        Map<?, ?> byId = (Map<?, ?>) terminalField(interfaceContainer, "trackedById");
        int count = 0, visible = 0;
        boolean correct = true;
        for (var entry : tracked.entrySet()) {
            if (!(entry.getKey() instanceof MatrixAssemblyMember member)) continue;
            IInterfaceViewable view = (IInterfaceViewable) member;
            Object tracker = entry.getValue();
            long id = (Long) terminalField(tracker, "id");
            boolean displayed = (Boolean) terminalField(tracker, "shouldDisplay");
            NBTTagList inventory = (NBTTagList) terminalField(tracker, "invNbt");
            count++;
            if (displayed) visible++;
            correct &= byId.get(id) == tracker && displayed == (member == group.master())
                && (Integer) terminalField(tracker, "rows") == 4
                && (Integer) terminalField(tracker, "rowSize") == 9
                && (Integer) terminalField(tracker, "numSlots") == 36
                && inventory.tagCount() == 36
                && !view.allowsPatternOptimization()
                && Arrays.asList(view.getSupportedStackTypes())
                    .contains(ITEM_STACK_TYPE)
                && Arrays.asList(view.getSupportedStackTypes())
                    .contains(FLUID_STACK_TYPE);
            if (member == group.master()) {
                masterTracker = tracker;
                masterTrackerId = id;
                correct &= terminalField(tracker, "patterns") == view.getPatterns();
            }
            ctx.event(
                "interface_tracker",
                "id=" + id
                    + "; type="
                    + member.getClass()
                        .getSimpleName()
                    + "; master="
                    + (member == group.master())
                    + "; defaultVisible="
                    + displayed
                    + "; online="
                    + terminalField(tracker, "online")
                    + "; rows=4; rowSize=9; slots="
                    + terminalField(tracker, "numSlots")
                    + "; invNbt="
                    + inventory);
        }
        require(
            ctx,
            count == INPUTS + 3 && visible == 1 && masterTracker != null && correct,
            "Native tracked maps expose one default-visible master and eighteen hidden 4x9 matrix entries");
        ctx.event(
            "interface_update_packet",
            "origin=constructor.updateList; class=" + packet.getClass()
                .getName()
                + "; commands="
                + terminalCommands()
                + "; matrixEntries="
                + count
                + "; defaultVisible="
                + visible);
        Method update = ContainerInterfaceTerminal.class.getDeclaredMethod("updateList");
        update.setAccessible(true);
        Object unchanged = update.invoke(interfaceContainer);
        require(
            ctx,
            unchanged == null || unchanged instanceof PacketInterfaceTerminalUpdate,
            "The native incremental updateList is observed without replacing its real tracking maps");
    }

    private void installPattern(TestContext ctx) throws Exception {
        ctx.event("installed_pattern_nbt", stack(pattern));
        require(
            ctx,
            owner.inventory.getItemStack() == null,
            "The synthetic actor starts the native terminal action with an empty cursor");
        int commands = terminalCommands();
        owner.inventory.setItemStack(pattern.copy());
        interfaceContainer.doAction(owner, InventoryAction.PICKUP_OR_SET_DOWN, 0, masterTrackerId);
        require(
            ctx,
            ItemStack.areItemStacksEqual(
                pattern,
                master().matrixPort()
                    .patterns()
                    .getStackInSlot(0)),
            "Native interface-terminal doAction installs the unchanged processing pattern in the actual master bank");
        NBTTagList inventory = (NBTTagList) terminalField(masterTracker, "invNbt");
        require(
            ctx,
            owner.inventory.getItemStack() == null && inventory.tagCount() == 36
                && ItemStack.areItemStacksEqual(pattern, ItemStack.loadItemStackFromNBT(inventory.getCompoundTagAt(0)))
                && terminalCommands() > commands
                && (Boolean) terminalField(interfaceContainer, "isDirty"),
            "Native doAction consumes one cursor pattern and updates the actual tracker NBT and packet");
        ((PacketInterfaceTerminalUpdate) terminalField(interfaceContainer, "dirty")).encode();
        ctx.event(
            "interface_pattern_action",
            "action=PICKUP_OR_SET_DOWN; tracker=" + masterTrackerId
                + "; cursor=empty; commandsBefore="
                + commands
                + "; commandsAfter="
                + terminalCommands()
                + "; invNbt="
                + inventory
                + "; actualBank="
                + stack(
                    master().matrixPort()
                        .patterns()
                        .getStackInSlot(0)));
        require(
            ctx,
            ((MTEHatchInputBus) master()).getSizeInventory() == 1
                && ((MTEHatchInputBus) master()).mInventory.length == 1
                && ((MTEHatchInputBus) master()).getRealInventory().length == 0,
            "The pattern bank is absent from the one physical input slot, whose external inventory view is sealed");
        for (OrderedPatternLayout.Input input : layout.rawInputs()) ctx.event(
            "raw_mapping",
            "raw=" + input.rawIndex()
                + "; transport="
                + input.transportIndex()
                + "; kind="
                + input.kind()
                + "; member="
                + input.kindOrdinal()
                + "; amount="
                + input.amount());
    }

    private static Object terminalField(Object instance, String name) throws Exception {
        Field field = instance.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private int terminalCommands() throws Exception {
        return ((List<?>) terminalField(terminalField(interfaceContainer, "dirty"), "commands")).size();
    }

    private void assertLockedInterface(TestContext ctx) throws Exception {
        NBTTagList playerBefore = owner.inventory.writeToNBT(new NBTTagList());
        ItemStack installed = master().matrixPort()
            .patterns()
            .getStackInSlot(0)
            .copy();
        require(
            ctx,
            ((IInterfaceViewable) master()).getPatterns()
                .getSizeInventory() == 0,
            "The actual interface inventory closes player transfers while a batch is staged");
        interfaceContainer.doAction(owner, InventoryAction.PICKUP_OR_SET_DOWN, 0, masterTrackerId);
        interfaceContainer.doAction(owner, InventoryAction.SHIFT_CLICK, 0, masterTrackerId);
        require(
            ctx,
            owner.inventory.getItemStack() == null && playerBefore.equals(owner.inventory.writeToNBT(new NBTTagList()))
                && ItemStack.areItemStacksEqual(
                    installed,
                    master().matrixPort()
                        .patterns()
                        .getStackInSlot(0)),
            "Native terminal pickup and shift-click cannot copy or extract a locked processing pattern");
        ctx.event(
            "interface_locked_actions",
            "pickupAndShift=refused; cursor=empty; playerInventoryUnchanged=true; bank=" + stack(installed));
    }

    private boolean published(TestContext ctx) throws Exception {
        IAEItemStack listed = proxy.getStorage()
            .getItemInventory()
            .getStorageList()
            .findPrecise(AEItemStack.create(recipe.mOutput));
        return listed != null && listed.isCraftable()
            && !proxy.getCrafting()
                .getCraftingFor(AEItemStack.create(recipe.mOutput), null, -1, ctx.world())
                .isEmpty();
    }

    private void logPublication(TestContext ctx, String phase) throws Exception {
        IAEItemStack listed = proxy.getStorage()
            .getItemInventory()
            .getStorageList()
            .findPrecise(AEItemStack.create(recipe.mOutput));
        ctx.event(
            "native_pattern_publication",
            "phase=" + phase
                + "; formed="
                + controller.mMachine
                + "; research="
                + stack(controller.getStackInSlot(1))
                + "; output="
                + stack(recipe.mOutput)
                + "; craftingFor="
                + proxy.getCrafting()
                    .getCraftingFor(AEItemStack.create(recipe.mOutput), null, -1, ctx.world())
                    .size()
                + "; monitorIsCraftable="
                + (listed != null && listed.isCraftable())
                + "; monitorEntry="
                + (listed == null ? "absent" : Platform.writeStackNBT(listed, new NBTTagCompound())));
    }

    private long[] materialTotals() throws Exception {
        long[] totals = new long[recipe.mInputs.length + recipe.mFluidInputs.length];
        var reserved = ((CraftingCPUCluster) cpu).getInventory();
        for (int i = 0; i < recipe.mInputs.length; i++) {
            IAEItemStack pending = reserved.findPrecise(AEItemStack.create(recipe.mInputs[i]));
            totals[i] = itemAmount(recipe.mInputs[i]) + (pending == null ? 0 : pending.getStackSize());
        }
        for (int i = 0; i < recipe.mFluidInputs.length; i++) {
            IAEFluidStack pending = reserved.findPrecise(AEFluidStack.create(recipe.mFluidInputs[i]));
            totals[recipe.mInputs.length + i] = fluidAmount(recipe.mFluidInputs[i])
                + (pending == null ? 0 : pending.getStackSize());
        }
        return totals;
    }

    private void observeBlockedCatalogue(TestContext ctx) throws Exception {
        boolean empty = true;
        for (MatrixAssemblyMember member : group.itemMembers()) empty &= member.matrixItem() == null;
        for (MatrixAssemblyMember member : group.fluidMembers()) empty &= member.matrixFluidStack() == null;
        if (cpu.isBusy() || cpu.getUsedStorage() != 0
            || group.stage() != OrderedAssemblyGroup.Stage.IDLE
            || !empty
            || controller.mMaxProgresstime != 0
            || session.runSequence() != firstRun
            || !Arrays.equals(seededTotals, materialTotals())
            || published(ctx))
            throw new IllegalStateException(
                "Blocked catalogue changed CPU, publication, physical stock, material totals or native sequence: "
                    + nativeState()
                    + "; stock="
                    + stock()
                    + "; totals="
                    + Arrays.toString(materialTotals()));
        blockedSamples++;
    }

    private void logBlockedCatalogue(TestContext ctx, String phase) throws Exception {
        require(
            ctx,
            blockedSamples >= 40,
            "At least forty natural ticks withhold the recipe and leave the CPU idle without physical intake or consumption");
        logPublication(ctx, phase);
        ctx.event(
            "catalogue_blocked",
            "phase=" + phase
                + "; samples="
                + blockedSamples
                + "; busy="
                + cpu.isBusy()
                + "; cpuUsedStorage="
                + cpu.getUsedStorage()
                + "; nativeConsumed=0; physicalSlotsAndTank=empty; totalMEPlusCPU="
                + Arrays.toString(materialTotals())
                + "; cpuInventory="
                + ((CraftingCPUCluster) cpu).getInventory()
                    .writeInventory()
                + "; state="
                + nativeState());
    }

    private void duplicateRawPositions(TestContext ctx) {
        ItemStack repeated = recipe.mInputs[0].copy();
        ItemStack specimen = encode(
            new ItemStack[] { repeated, repeated.copy() },
            recipe.mFluidInputs,
            recipe.mOutput,
            true);
        OrderedPatternLayout decoded = OrderedPatternLayout.decode(specimen, ctx.world());
        require(
            ctx,
            decoded.items()
                .size() == 2
                && decoded.items()
                    .get(0)
                    .rawIndex() == 0
                && decoded.items()
                    .get(1)
                    .rawIndex() == 2
                && decoded.items()
                    .get(0)
                    .kindOrdinal() == 0
                && decoded.items()
                    .get(1)
                    .kindOrdinal() == 1,
            "The official encoded-pattern decoder preserves two repeated positions across a raw empty slot");
        decoded.validateTable(table(decoded));
        ctx.event(
            "parser_fixture",
            "Repeated raw input specimen is a decoder-only counterexample; "
                + "it is not advertised as a native recipe or counted as production coverage");
    }

    private void outputDeclarationCounterexample(TestContext ctx) {
        ItemStack otherOutput = null;
        for (RecipeAssemblyLine existing : RecipeAssemblyLine.sAssemblylineRecipes) {
            if (existing.mOutput != null && (!existing.mOutput.isItemEqual(recipe.mOutput)
                || !ItemStack.areItemStackTagsEqual(existing.mOutput, recipe.mOutput))) {
                otherOutput = existing.mOutput.copy();
                break;
            }
        }
        require(
            ctx,
            otherOutput != null && group.accepting(),
            "The output-binding refusal is tested against an otherwise available real medium");
        ItemStack wrong = encode(recipe.mInputs, recipe.mFluidInputs, otherOutput, true);
        OrderedPatternLayout wrongLayout = OrderedPatternLayout.decode(wrong, ctx.world());
        require(ctx, owner.inventory.getItemStack() == null, "The terminal counterexample starts with an empty cursor");
        owner.inventory.setItemStack(wrong.copy());
        interfaceContainer.doAction(owner, InventoryAction.PICKUP_OR_SET_DOWN, 1, masterTrackerId);
        require(
            ctx,
            owner.inventory.getItemStack() == null && ItemStack.areItemStacksEqual(
                wrong,
                master().matrixPort()
                    .patterns()
                    .getStackInSlot(1)),
            "The declared-output counterexample uses native interface-terminal insertion");
        MEInventoryCrafting delivery = table(wrongLayout);
        String tableBefore = tableSnapshot(delivery);
        String stockBefore = stock();
        require(
            ctx,
            !master().matrixPort()
                .pushPattern(wrongLayout.details(), delivery),
            "Identical declared inputs cannot authorize a different declared output");
        require(
            ctx,
            tableBefore.equals(tableSnapshot(delivery)) && stockBefore.equals(stock())
                && group.stage() == OrderedAssemblyGroup.Stage.IDLE,
            "Output-binding refusal changes neither the caller table nor any real member stock");
        ctx.event(
            "declared_output_refusal",
            "same inputs; selected=" + stack(recipe.mOutput)
                + "; declaredOtherExistingOutput="
                + stack(otherOutput)
                + "; reason="
                + group.reason()
                + "; table="
                + tableBefore
                + "; stock="
                + stockBefore
                + "; scope=declared-output binding, not two actual same-input native recipe candidates");
        interfaceContainer.doAction(owner, InventoryAction.PICKUP_OR_SET_DOWN, 1, masterTrackerId);
        require(
            ctx,
            master().matrixPort()
                .patterns()
                .getStackInSlot(1) == null && ItemStack.areItemStacksEqual(wrong, owner.inventory.getItemStack()),
            "Native terminal extraction returns the owned counterexample pattern exactly once");
        owner.inventory.setInventorySlotContents(2, owner.inventory.getItemStack());
        owner.inventory.setItemStack(null);
    }

    private static ItemStack encode(ItemStack[] items, FluidStack[] fluids, ItemStack out, boolean gap) {
        ItemStack encoded = AEApi.instance()
            .definitions()
            .items()
            .encodedUltimatePattern()
            .maybeStack(1)
            .get();
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList input = new NBTTagList();
        for (int i = 0; i < items.length; i++) {
            input.appendTag(Platform.writeStackNBT(AEItemStack.create(items[i]), new NBTTagCompound()));
            if (gap && i == 0) input.appendTag(new NBTTagCompound());
        }
        if (gap) input.appendTag(new NBTTagCompound());
        for (FluidStack fluid : fluids)
            input.appendTag(Platform.writeStackNBT(AEFluidStack.create(fluid), new NBTTagCompound()));
        NBTTagList output = new NBTTagList();
        output.appendTag(Platform.writeStackNBT(AEItemStack.create(out), new NBTTagCompound()));
        tag.setTag("in", input);
        tag.setTag("out", output);
        tag.setBoolean("crafting", false);
        tag.setBoolean("substitute", false);
        encoded.setTagCompound(tag);
        return encoded;
    }

    private void buildAe(TestContext ctx) throws Exception {
        int[] bus = position(INPUTS - 1, 2, 1);
        int ax = bus[0], ay = bus[1] - 1, az = bus[2];
        int stepX = position(INPUTS - 2, 2, 1)[0] - ax;
        // Native adjacent blocks carry the network; no fake grid, direct medium call or inventory mirror is used.
        aeController = ae(
            ctx,
            ax - stepX,
            ay,
            az,
            AEApi.instance()
                .definitions()
                .blocks()
                .controller()
                .maybeStack(1)
                .get());
        ae(
            ctx,
            ax - stepX,
            ay - 1,
            az,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCellCreative()
                .maybeStack(1)
                .get());
        drive = (TileDrive) ae(
            ctx,
            ax,
            ay,
            az,
            AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeStack(1)
                .get());
        ae(
            ctx,
            outputTile.xCoord,
            outputTile.yCoord - 1,
            outputTile.zCoord,
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeStack(1)
                .get());
        for (int dx = -3; dx <= -2; dx++) for (int dy = -1; dy <= 0; dy++) for (int dz = 0; dz < 2; dz++) ae(
            ctx,
            ax + stepX * dx,
            ay + dy,
            az + dz,
            dx == -2 && dy == 0 && dz == 0 ? AEApi.instance()
                .definitions()
                .blocks()
                .craftingStorage64k()
                .maybeStack(1)
                .get()
                : AEApi.instance()
                    .definitions()
                    .blocks()
                    .craftingUnit()
                    .maybeStack(1)
                    .get());
        drive.getInternalInventory()
            .setInventorySlotContents(
                0,
                AEApi.instance()
                    .definitions()
                    .items()
                    .cell64k()
                    .maybeStack(1)
                    .get());
        Item fluidCell = (Item) Class.forName("com.glodblock.github.loader.ItemAndBlockHolder")
            .getField("CELL1K")
            .get(null);
        // AE2FC basic cells each hold one fluid type; this recipe has three distinct fluid positions.
        for (int i = 0; i < recipe.mFluidInputs.length; i++) drive.getInternalInventory()
            .setInventorySlotContents(i + 1, new ItemStack(fluidCell));
        TestWorldFixtures.place(
            ctx.world(),
            ax,
            ay - 1,
            az,
            AEApi.instance()
                .definitions()
                .blocks()
                .multiPart()
                .maybeBlock()
                .get(),
            0);
        IPartHost terminalHost = (IPartHost) ctx.world()
            .getTileEntity(ax, ay - 1, az);
        require(
            ctx,
            terminalHost.addPart(
                AEApi.instance()
                    .definitions()
                    .parts()
                    .cableGlass()
                    .stack(AEColor.Transparent, 1),
                ForgeDirection.UNKNOWN,
                owner) == ForgeDirection.UNKNOWN,
            "A real cable-bus center joins the terminal to the physical AE fixture");
        require(
            ctx,
            terminalHost.addPart(
                AEApi.instance()
                    .definitions()
                    .parts()
                    .interfaceTerminal()
                    .maybeStack(1)
                    .get(),
                ForgeDirection.SOUTH,
                owner) == ForgeDirection.SOUTH,
            "A real PartInterfaceTerminal is installed in the native cable-bus host");
        interfacePart = (PartInterfaceTerminal) terminalHost.getPart(ForgeDirection.SOUTH);
        for (MatrixAssemblyMember member : group.itemMembers()) member.matrixPort()
            .getProxy()
            .setOwner(owner);
        for (MatrixAssemblyMember member : group.fluidMembers()) member.matrixPort()
            .getProxy()
            .setOwner(owner);
    }

    private TileEntity ae(TestContext ctx, int x, int y, int z, ItemStack item) {
        return TestWorldFixtures.aeOwnedBlock(ctx.world(), x, y, z, item, owner);
    }

    private void seedCells(TestContext ctx) throws Exception {
        for (ItemStack input : recipe.mInputs) {
            ItemStack seeded = input.copy();
            seeded.stackSize *= COPIES;
            require(
                ctx,
                proxy.getStorage()
                    .getItemInventory()
                    .injectItems(AEItemStack.create(seeded), Actionable.MODULATE, source) == null,
                "Real item storage accepts sixteen copies of the declared position's ingredient");
        }
        for (FluidStack required : recipe.mFluidInputs) {
            FluidStack fluid = required.copy();
            fluid.amount *= COPIES;
            require(
                ctx,
                proxy.getStorage()
                    .getFluidInventory()
                    .injectItems(AEFluidStack.create(fluid), Actionable.MODULATE, source) == null,
                "Real fluid storage accepts exactly 9216 mB for each of the three fluid positions");
        }
        require(ctx, itemAmount(recipe.mOutput) == 0, "The output type is absent before real CPU production");
        ctx.event(
            "seeded_cells",
            "itemCell=" + stack(
                drive.getInternalInventory()
                    .getStackInSlot(0))
                + "; fluidCell="
                + stack(
                    drive.getInternalInventory()
                        .getStackInSlot(1)));
    }

    private void beginJob(TestContext ctx) throws Exception {
        batch++;
        ICraftingGrid crafting = proxy.getCrafting();
        planning = crafting.beginCraftingJob(
            ctx.world(),
            proxy.getGrid(),
            source,
            AEItemStack.create(recipe.mOutput)
                .setStackSize((long) recipe.mOutput.stackSize * COPIES),
            null);
    }

    private void assertStaged(TestContext ctx) throws Exception {
        require(
            ctx,
            group.stage() == OrderedAssemblyGroup.Stage.STAGED && controller.mMaxProgresstime == 0,
            "Real CPU acceptance stages materials without announcing native start");
        // Bounded read-only inspection uses the existing native access scope, then restores it immediately.
        Method enter = NativeAssemblyAdapter.class.getDeclaredMethod("enter", NativeAssemblySession.class);
        Method leave = NativeAssemblyAdapter.class.getDeclaredMethod("leave", NativeAssemblySession.class);
        enter.setAccessible(true);
        leave.setAccessible(true);
        Object previous = enter.invoke(null, session);
        try {
            for (int i = 0; i < recipe.mInputs.length; i++) {
                MTEHatchInputBus member = (MTEHatchInputBus) group.itemMembers()
                    .get(i);
                ItemStack actual = member.mInventory[0];
                require(
                    ctx,
                    actual != null && actual.isItemEqual(recipe.mInputs[i])
                        && ItemStack.areItemStackTagsEqual(actual, recipe.mInputs[i])
                        && actual.stackSize == recipe.mInputs[i].stackSize * COPIES
                        && member.getRealInventory() == member.mInventory
                        && member.getFirstStack() == actual,
                    "Native item position " + i
                        + " exposes the actual physical inventory reference with the exact payload");
            }
        } finally {
            leave.invoke(null, previous);
        }
        for (int i = 0; i < recipe.mFluidInputs.length; i++) {
            FluidStack actual = group.fluidMembers()
                .get(i)
                .matrixFluidStack();
            require(
                ctx,
                actual != null && actual.isFluidEqual(recipe.mFluidInputs[i]) && actual.amount == 9216,
                "Physical fluid position " + i + " stores exactly 9216 mB");
        }
        require(
            ctx,
            Arrays.equals(group.diagnosticMemberPosition(false, 0), position(INPUTS - 1, 2, 1))
                && Arrays.equals(group.diagnosticMemberPosition(false, INPUTS - 1), position(0, 2, 1))
                && controller.mInputBusses.equals(group.itemMembers())
                && controller.mInputHatches.equals(group.fluidMembers()),
            "Reverse-end ME entry determines physical intake, diagnostics and actual native input ordering");
    }

    private void assertRunning(TestContext ctx, long sequence) {
        require(
            ctx,
            session.runSequence() == sequence && group.stage() == OrderedAssemblyGroup.Stage.RUNNING,
            "A unique native consumption sequence changes the batch to RUNNING");
        assertRemainingPositions();
        require(
            ctx,
            controller.mMaxProgresstime > 0 && controller.lEUt < 0,
            "The original AL wrote real progress and negative EU/t only after native authorization");
        ctx.event(
            "native_started",
            "batch=" + batch + "; tick=" + ctx.tick() + "; " + nativeState() + "; ledger=" + group.saveLedger());
    }

    private boolean allCopiesStaged() {
        for (int i = 0; i < recipe.mInputs.length; i++) {
            ItemStack stack = group.itemMembers()
                .get(i)
                .matrixItem();
            if (stack == null || stack.stackSize != recipe.mInputs[i].stackSize * COPIES) return false;
        }
        for (int i = 0; i < recipe.mFluidInputs.length; i++) {
            FluidStack fluid = group.fluidMembers()
                .get(i)
                .matrixFluidStack();
            if (fluid == null || fluid.amount != recipe.mFluidInputs[i].amount * COPIES) return false;
        }
        return true;
    }

    private void assertRemainingPositions() {
        long remaining = COPIES - (session.runSequence() - firstRun);
        if (remaining < 0 || remaining > COPIES)
            throw new IllegalStateException("Unexpected native consumption sequence");
        for (int i = 0; i < recipe.mInputs.length; i++) {
            ItemStack stored = group.itemMembers()
                .get(i)
                .matrixItem();
            long amount = stored == null ? 0 : stored.stackSize;
            if (amount != remaining * recipe.mInputs[i].stackSize
                || stored != null && (!stored.isItemEqual(recipe.mInputs[i])
                    || !ItemStack.areItemStackTagsEqual(stored, recipe.mInputs[i])))
                throw new IllegalStateException("Native per-copy item consumption differs at physical position " + i);
        }
        for (int i = 0; i < recipe.mFluidInputs.length; i++) {
            FluidStack stored = group.fluidMembers()
                .get(i)
                .matrixFluidStack();
            long amount = stored == null ? 0 : stored.amount;
            if (amount != remaining * recipe.mFluidInputs[i].amount
                || stored != null && !stored.isFluidEqual(recipe.mFluidInputs[i]))
                throw new IllegalStateException("Native per-copy fluid consumption differs at physical position " + i);
        }
    }

    private void extraConnection(TestContext ctx, boolean connect) {
        int[] input = position(0, 2, 1);
        if (connect) ae(
            ctx,
            input[0],
            input[1] - 1,
            input[2],
            AEApi.instance()
                .definitions()
                .blocks()
                .iface()
                .maybeStack(1)
                .get());
        else ctx.world()
            .setBlockToAir(input[0], input[1] - 1, input[2]);
    }

    private MatrixAssemblyMember master() {
        MatrixAssemblyMember firstNativeInput = patternAnchor == null
            ? (MatrixAssemblyMember) controller.mInputBusses.get(0)
            : patternAnchor;
        OrderedAssemblyGroup current = firstNativeInput.matrixPort()
            .group();
        return current == null ? firstNativeInput : current.master();
    }

    private int[] position(int a, int b, int c) {
        int[] relative = new int[3];
        controller.getExtendedFacing()
            .getWorldOffset(new int[] { a, b, c }, relative);
        return new int[] { X + relative[0], Y + relative[1], Z + relative[2] };
    }

    private BaseMetaTileEntity machine(TestContext ctx, int a, int b, int c, ItemStack item, ForgeDirection facing) {
        int[] p = position(a, b, c);
        BaseMetaTileEntity tile = TestWorldFixtures.machine(ctx.world(), p[0], p[1], p[2], item, owner.getUniqueID());
        tile.setFrontFacing(facing);
        return tile;
    }

    private void block(TestContext ctx, int a, int b, int c, Block block, int metadata) {
        int[] p = position(a, b, c);
        TestWorldFixtures.place(ctx.world(), p[0], p[1], p[2], block, metadata);
    }

    private long itemAmount(ItemStack item) throws Exception {
        IAEItemStack stored = proxy.getStorage()
            .getItemInventory()
            .getAvailableItem(AEItemStack.create(item));
        return stored == null ? 0 : stored.getStackSize();
    }

    private long fluidAmount(FluidStack fluid) throws Exception {
        IAEFluidStack stored = proxy.getStorage()
            .getFluidInventory()
            .getAvailableItem(AEFluidStack.create(fluid));
        return stored == null ? 0 : stored.getStackSize();
    }

    private int outputAmount() {
        int total = 0;
        for (ItemStack actual : output.getRealInventory()) {
            if (actual != null && actual.isItemEqual(recipe.mOutput)
                && ItemStack.areItemStackTagsEqual(actual, recipe.mOutput)) total += actual.stackSize;
        }
        return total;
    }

    private void fillOutput() {
        for (int i = 0; i < output.getSizeInventory(); i++)
            output.setInventorySlotContents(i, new ItemStack(Blocks.dirt, 64));
    }

    private void clearOutput() {
        for (int i = 0; i < output.getSizeInventory(); i++) output.setInventorySlotContents(i, null);
        outputTile.markDirty();
    }

    private String stock() {
        if (group == null) return "unbound";
        StringBuilder stock = new StringBuilder();
        for (MatrixAssemblyMember member : group.itemMembers()) stock.append(member.orderedMemberId())
            .append('=')
            .append(stack(member.matrixItem()))
            .append(';');
        for (MatrixAssemblyMember member : group.fluidMembers()) stock.append(member.orderedMemberId())
            .append('=')
            .append(
                member.matrixFluidStack() == null ? "empty"
                    : member.matrixFluidStack()
                        .writeToNBT(new NBTTagCompound()))
            .append(';');
        return stock.toString();
    }

    private String nativeState() {
        return "stage=" + (group == null ? "unbound" : group.stage())
            + "; reason="
            + (group == null ? "" : group.reason())
            + "; native="
            + session.lastNativeEvent()
            + "; blocked="
            + session.blockedReason()
            + "; run="
            + session.runSequence()
            + "; progress="
            + controller.mProgresstime
            + '/'
            + controller.mMaxProgresstime
            + "; EUt="
            + controller.lEUt
            + "; hatchStoredEU="
            + (energyTile == null ? -1 : energyTile.getStoredEU())
            + "; check="
            + controller.getCheckRecipeResult()
                .getID()
            + "; formed="
            + controller.mMachine
            + "; allowed="
            + controllerTile.isAllowedToWork()
            + "; repair="
            + controller.getRepairStatus()
            + '/'
            + controller.getIdealStatus();
    }

    private static MEInventoryCrafting table(OrderedPatternLayout decoded) {
        List<IAEStack<?>> compact = new ArrayList<>();
        for (IAEStack<?> input : decoded.details()
            .getAEInputs()) if (input != null) compact.add(input.copy());
        MEInventoryCrafting table = new MEInventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return false;
            }
        }, compact.size(), 1);
        for (int i = 0; i < compact.size(); i++) table.setInventorySlotContents(i, compact.get(i));
        return table;
    }

    private static String tableSnapshot(MEInventoryCrafting table) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < table.getSizeInventory(); i++)
            result.appendTag(Platform.writeStackNBT(table.getAEStackInSlot(i), new NBTTagCompound()));
        return result.toString();
    }

    private static String stack(ItemStack item) {
        return item == null ? "empty"
            : item.writeToNBT(new NBTTagCompound())
                .toString();
    }

    private void transition(TestContext ctx, int next, String message) {
        stage = next;
        stageTick = ctx.tick();
        ctx.event("phase", next + ": " + message);
    }

    private static void require(TestContext ctx, boolean condition, String message) {
        TestWorldFixtures.require(ctx, condition, message);
    }
}
