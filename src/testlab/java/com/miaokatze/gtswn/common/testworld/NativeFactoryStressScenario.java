// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.testworld;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceMachineIdentity;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.SchedulingMode;
import appeng.api.config.Settings;
import appeng.api.implementations.IUpgradeableHost;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IPart;
import appeng.api.storage.StorageName;
import appeng.parts.automation.PartSharedItemBus;
import appeng.tile.networking.TileController;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.misc.WirelessNetworkManager;

/** A reusable, explicit-only production-shaped factory. The driver never advances recipes or transports material. */
final class NativeFactoryStressScenario implements TestScenario, StressSupplyTile.Observer {

    static final String ID = "factory.native_stress";
    static final int STABLE_TICKS = 3600;
    static final int EVIDENCE_OUTPUT = 1, EVIDENCE_FUEL = 2, EVIDENCE_RECIPES = 4, EVIDENCE_PROGRESS = 8;
    private static final int CONTROLLER_Y_OFFSET = 25, SAMPLE_BATCH = 32;
    private static final int SOURCE_X = 0, SOURCE_Y = 90, SOURCE_Z = 4;
    private static final int ENTRY_X = 0, ENTRY_Y = 90, ENTRY_Z = 8;
    private final List<TileEntity> controllers = new ArrayList<>();
    private final List<TileEntityNetworkQuantumNode> nodes = new ArrayList<>();
    private final List<Endpoint> endpoints = new ArrayList<>();
    private final List<Power> power = new ArrayList<>();
    private final Map<Object, Endpoint> sources = new IdentityHashMap<>();
    private final List<UUID> terminalIds = new ArrayList<>();
    private final List<ItemStack> terminals = new ArrayList<>();
    private StressMachineFixtures fixtures;
    private StressSupplyTile supply;
    private TestWorldReport.Workload workload;
    private TestWorldReport.WorkloadPhase phase;
    private DeviceTerminalDataStore store;
    private FakePlayer owner;
    private ItemStack quantumTerminal, linkTerminal;
    private int stage, cursor, sampleCursor, readyMachines, clampProbe;
    private long phaseTick, stableTick, lastHealthTick, lastHeartbeatTick, lastReadinessTick, lastDiagnosticTick;
    private long lowerClampBaseline, upperClampBaseline;
    private long[] lastInput, lastOutput, lastFluidInput, lastFluidOutput;
    private long[] sampledInput, sampledOutput, sampledFluidInput, sampledFluidOutput;
    private int[] lastProgress;
    private boolean completed;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Explicit native factory: 128 single machines, 256 multiblocks and 64 generators; "
            + "wireless ME raw materials/output, real link-terminal power and DIT monitoring; 3600 stable ticks";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int footprintRadius() {
        return 80;
    }

    @Override
    public int timeoutTicks() {
        return 40000;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        controllers.clear();
        nodes.clear();
        endpoints.clear();
        power.clear();
        sources.clear();
        terminalIds.clear();
        terminals.clear();
        supply = null;
        completed = false;
        stage = cursor = sampleCursor = readyMachines = clampProbe = 0;
        lastHealthTick = stableTick = 0;
        lastHeartbeatTick = lastReadinessTick = lastDiagnosticTick = 0;
        fixtures = new StressMachineFixtures();
        lastInput = new long[StressMachineFixtures.COUNT];
        lastOutput = new long[StressMachineFixtures.COUNT];
        lastFluidInput = new long[StressMachineFixtures.COUNT];
        lastFluidOutput = new long[StressMachineFixtures.COUNT];
        sampledInput = new long[StressMachineFixtures.COUNT];
        sampledOutput = new long[StressMachineFixtures.COUNT];
        sampledFluidInput = new long[StressMachineFixtures.COUNT];
        sampledFluidOutput = new long[StressMachineFixtures.COUNT];
        lastProgress = new int[StressMachineFixtures.COUNT];
        if (Config.deviceTerminalMaxMachines < 16 || Config.deviceSampleIntervalSeconds > 150) {
            ctx.skip(
                "Native factory requires >=16 bindings per DIT and <=150-second native sampling cadence; configuration unchanged");
            return;
        }
        owner = TestWorldFixtures.player(ctx.world(), TestWorldFixtures.owner(ID + ":owner"));
        owner.capabilities.isCreativeMode = true;
        owner.capabilities.allowEdit = true;
        owner.setSneaking(false);
        store = DeviceTerminalDataStore.get(ctx.world());
        int count = (StressMachineFixtures.COUNT + Config.deviceTerminalMaxMachines - 1)
            / Config.deviceTerminalMaxMachines;
        for (int i = 0; i < count; i++) {
            UUID id = TestWorldFixtures.owner(ID + ":terminal:" + i);
            terminalIds.add(id);
            TestFixtureAccess.restoreTerminal(store, ctx.world(), id, null);
            ItemStack item = GTSWNItemList.Device_Info_Terminal.get(1);
            NBTTagCompound data = new NBTTagCompound();
            data.setString(ItemDeviceInfoTerminal.NBT_UUID, id.toString());
            item.setTagCompound(data);
            terminals.add(item);
        }
        quantumTerminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        linkTerminal = GTSWNItemList.Wireless_Energy_Tap.get(1);
        workload = new TestWorldReport.Workload();
        workload.kind = "native-factory-stress";
        workload.retention = "Native machines, ME source/sink, quantum nodes, link covers, DIT bindings and entry chest remain. "
            + "The test-only source tile supplies through the real AE grid, maintains its isolated wireless owner balance "
            + "and keeps the fixture DIT actor enrolled every 100 ticks, including after restart. "
            + "Runner chunk tickets are released on completion/stop; use a player or external chunk loader to keep the whole field loaded. "
            + "build/reset removes the persisted 160x160 footprint. Harness and test dimension are required.";
        workload.coverageLimitations.add(
            "Creative central material source/sink and an isolated synthetic EU balance are test fixtures, not survival resource accounting.");
        workload.coverageLimitations.add(
            "All recipe checks, structure validation, overclocking, hatch work and AE material transport are native. No Waila or production recipe listener.");
        workload.coverageLimitations.add(
            "Material counters are signed native AE transactions; input refunds are subtracted. Progress samples are bounded observations, not invented completion counts.");
        workload.coverageLimitations.add(
            "nativeRecipeCounter is a raw GT counter: some machines increment it when reserving a recipe batch. "
                + "Successful ME output transactions provide actual output evidence. Native parallel counters and "
                + "deterministic output-to-recipe batch ratios are reported separately; neither is a completed-work count.");
        workload.coverageLimitations.add(
            "Timing includes the complete running modpack. Spark is controlled externally only after the READY marker; no performance threshold or causal attribution is claimed.");
        ctx.result.workload = workload;
        workload.expected.put("singleMachines", 128L);
        workload.expected.put("multiblockMachines", 256L);
        workload.expected.put("generators", 64L);
        workload.expected.put("totalMachines", 448L);
        workload.expected.put("stableTicks", (long) STABLE_TICKS);
        workload.expected.put("forcedLoadedChunks", 100L);
        workload.expected.put("maxMachinesSampledPerTick", (long) SAMPLE_BATCH);
        workload.expected.put("maxEndpointsPlacedPerTick", 4L);
        workload.expected.put("driverMaterialTransfers", 0L);
        workload.expected.put("driverRecipeProgressWrites", 0L);
        begin(ctx, 0, "BUILD_CONTROLLERS");
    }

    @Override
    public void tick(TestContext ctx) {
        long started = ctx.operationStart();
        try {
            switch (stage) {
                case 0:
                    buildControllers(ctx);
                    break;
                case 1:
                    buildMachines(ctx);
                    break;
                case 2:
                    buildEndpoints(ctx);
                    break;
                case 3:
                    bindAnchor(ctx);
                    break;
                case 4:
                    bindNodes(ctx);
                    break;
                case 5:
                    bindPower(ctx);
                    break;
                case 6:
                    bindDevices(ctx);
                    break;
                case 7:
                    warmup(ctx);
                    break;
                case 8:
                    stable(ctx);
                    break;
                default:
                    throw new IllegalStateException("Unknown factory stage " + stage);
            }
        } finally {
            ctx.operationEnd("factory." + workload.phase, started);
        }
    }

    private void buildControllers(TestContext ctx) {
        int[][] geometry = AeStressControllerLayout.coordinates();
        int end = Math.min(geometry.length, cursor + 8);
        for (; cursor < end; cursor++) {
            int[] pos = geometry[cursor];
            controllers.add(
                TestWorldFixtures.aeOwnedBlock(
                    ctx.world(),
                    pos[0],
                    pos[1] + CONTROLLER_Y_OFFSET,
                    pos[2],
                    AEApi.instance()
                        .definitions()
                        .blocks()
                        .controller()
                        .maybeStack(1)
                        .get(),
                    owner));
        }
        workload.observed.put("controllersBuilt", (long) cursor);
        if (cursor != geometry.length) return;
        TestWorldFixtures.aeOwnedBlock(
            ctx.world(),
            AeStressControllerLayout.X - 1,
            AeStressControllerLayout.Y + CONTROLLER_Y_OFFSET,
            AeStressControllerLayout.Z,
            AEApi.instance()
                .definitions()
                .blocks()
                .energyCellCreative()
                .maybeStack(1)
                .get(),
            owner);
        TestWorldFixtures.place(ctx.world(), SOURCE_X, SOURCE_Y, SOURCE_Z, StressSupplyTile.block, 0);
        supply = (StressSupplyTile) ctx.world()
            .getTileEntity(SOURCE_X, SOURCE_Y, SOURCE_Z);
        nodes.add(placeNode(ctx, SOURCE_X + 1, SOURCE_Y, SOURCE_Z));
        TestWorldFixtures.place(ctx.world(), ENTRY_X, ENTRY_Y, ENTRY_Z, Blocks.chest, 2);
        begin(ctx, 1, "BUILD_MACHINES");
    }

    private void buildMachines(TestContext ctx) {
        StressMachineFixtures.Machine machine = fixtures.place(ctx, cursor, owner);
        if (clearFixtureRegistration(
            DeviceRegistryData.get(ctx.world()),
            ctx.world(),
            owner.getUniqueID(),
            machine.controller))
            workload.observed.put(
                "previousFixtureRegistrationsRemoved",
                workload.observed.getOrDefault("previousFixtureRegistrationsRemoved", 0L) + 1);
        TestWorldReport.Machine fact = new TestWorldReport.Machine(
            cursor,
            machine.controller.xCoord,
            machine.controller.yCoord,
            machine.controller.zCoord,
            machine.controller.getMetaTileID());
        fact.kind = machine.kind.name();
        fact.type = machine.type;
        fact.recipeInputs = ingredients(machine.itemInputs, machine.fluidInputs);
        fact.recipeOutputs = ingredients(machine.itemOutputs, machine.fluidOutputs);
        fact.fixedRecipe = machine.type + "|" + fact.recipeInputs + "->" + fact.recipeOutputs;
        fact.itemStockLimit = machine.itemStockLimit;
        fact.fluidStockLimits = machine.fluidStockLimits.toString();
        if (machine.recipe != null) {
            fact.recipeEUt = machine.recipe.mEUt;
            fact.recipeDuration = machine.recipe.mDuration;
        }
        for (BaseMetaTileEntity energy : machine.energy) {
            fact.maxInputVoltage = Math.max(fact.maxInputVoltage, energy.getInputVoltage());
            fact.maxInputAmperage += energy.getInputAmperage();
        }
        workload.machines.add(fact);
        for (StressMachineFixtures.Endpoint endpoint : machine.endpoints) {
            Endpoint entry = new Endpoint(cursor, endpoint);
            endpoints.add(entry);
            if (endpoint.nativeMe) registerNativeSource(sources, endpoint.tile, entry);
        }
        for (BaseMetaTileEntity tile : machine.energy)
            power.add(new Power(tile, machine.energySides.get(tile), cursor >= 384));
        workload.observed.put("machinesBuilt", (long) ++cursor);
        if (cursor != StressMachineFixtures.COUNT) return;
        List<ItemStack> items = new ArrayList<>();
        List<FluidStack> fluids = new ArrayList<>();
        Map<String, Long> types = new LinkedHashMap<>();
        for (StressMachineFixtures.Machine built : fixtures.machines()) {
            items.addAll(built.itemInputs);
            fluids.addAll(built.fluidInputs);
            types.put(built.kind + ":" + built.type, types.getOrDefault(built.kind + ":" + built.type, 0L) + 1);
        }
        workload.observed.putAll(types);
        supply.configure(owner.getUniqueID(), items, fluids, terminalIds);
        for (Endpoint endpoint : endpoints) if (endpoint.input && endpoint.fixture.nativeMe) {
            StressMachineFixtures.Machine machineForEndpoint = fixtures.machines()
                .get(endpoint.machine);
            supply.limit(endpoint.fixture.tile, machineForEndpoint.itemStockLimit, machineForEndpoint.fluidStockLimits);
        }
        supply.observe(this);
        // configure uses the same synthetic owner to set AE ownership; restore its actual held fixtures afterwards.
        owner.inventory.setInventorySlotContents(0, linkTerminal);
        for (int i = 0; i < terminals.size(); i++) owner.inventory.setInventorySlotContents(i + 1, terminals.get(i));
        begin(ctx, 2, "BUILD_ME_ENDPOINTS");
    }

    private void buildEndpoints(TestContext ctx) {
        int end = Math.min(endpoints.size(), cursor + 4);
        for (; cursor < end; cursor++) {
            Endpoint endpoint = endpoints.get(cursor);
            BaseMetaTileEntity tile = endpoint.fixture.tile;
            ForgeDirection side = endpoint.fixture.side;
            int x = tile.xCoord + side.offsetX, y = tile.yCoord + side.offsetY, z = tile.zCoord + side.offsetZ;
            TileEntity existing = ctx.world()
                .getTileEntity(x, y, z);
            TileEntityNetworkQuantumNode node;
            if (existing instanceof TileEntityNetworkQuantumNode) node = (TileEntityNetworkQuantumNode) existing;
            else {
                demand(
                    ctx.world()
                        .isAirBlock(x, y, z),
                    "ME endpoint overlaps native structure at " + x + "," + y + "," + z);
                node = placeNode(ctx, x, y, z);
                nodes.add(node);
            }
            endpoint.node = node;
            if (endpoint.fixture.nativeMe) continue;
            ItemStack bus = endpoint.fluid ? fluidBus(endpoint.input)
                : (endpoint.input ? AEApi.instance()
                    .definitions()
                    .parts()
                    .exportBus()
                    : AEApi.instance()
                        .definitions()
                        .parts()
                        .importBus()).maybeStack(1)
                            .get();
            ForgeDirection toward = side.getOpposite();
            demand(node.addPart(bus, toward, owner) == toward, "Native AE bus placement failed: " + cursor);
            IPart part = node.getPart(toward);
            demand(part instanceof PartSharedItemBus, "Unexpected native AE bus type: " + cursor);
            endpoint.part = part;
            sources.put(part, endpoint);
            configureBus((PartSharedItemBus<?>) part, endpoint);
        }
        workload.observed.put("endpointsBuilt", (long) cursor);
        if (cursor == endpoints.size()) begin(ctx, 3, "BIND_CONTROLLER");
    }

    private static ItemStack fluidBus(boolean input) {
        Item item = GameRegistry.findItem("ae2fc", input ? "part_fluid_export" : "part_fluid_import");
        demand(item != null, "Native AE2 Fluid Crafting fluid buses must be enabled for factory.native_stress");
        return new ItemStack(item);
    }

    private void configureBus(PartSharedItemBus<?> bus, Endpoint endpoint) {
        StressMachineFixtures.Machine machine = fixtures.machines()
            .get(endpoint.machine);
        int slot = 0;
        if (endpoint.fluid) {
            List<FluidStack> filter = endpoint.input ? machine.fluidInputs : machine.fluidOutputs;
            for (FluidStack stack : filter) bus.getAEInventoryByName(StorageName.CONFIG)
                .putAEStackInSlot(slot++, AEFluidStack.create(stack));
        } else {
            List<ItemStack> filter = endpoint.input ? machine.itemInputs : machine.itemOutputs;
            for (ItemStack stack : filter) bus.getAEInventoryByName(StorageName.CONFIG)
                .putAEStackInSlot(slot++, AEItemStack.create(stack));
        }
        IInventory upgrades = ((IUpgradeableHost) bus).getInventoryByName("upgrades");
        if (slot > 1) upgrades.setInventorySlotContents(
            0,
            AEApi.instance()
                .definitions()
                .materials()
                .cardCapacity()
                .maybeStack(1)
                .get());
        for (int i = slot > 1 ? 1 : 0; i < upgrades.getSizeInventory(); i++) upgrades.setInventorySlotContents(
            i,
            (endpoint.fluid ? AEApi.instance()
                .definitions()
                .materials()
                .cardSuperSpeed()
                : AEApi.instance()
                    .definitions()
                    .materials()
                    .cardSuperluminalSpeed()).maybeStack(1)
                        .get());
        if (endpoint.input) bus.getConfigManager()
            .putSetting(Settings.SCHEDULING_MODE, SchedulingMode.ROUNDROBIN);
    }

    private TileEntityNetworkQuantumNode placeNode(TestContext ctx, int x, int y, int z) {
        TestWorldFixtures.place(ctx.world(), x, y, z, BlockRegistrar.networkQuantumNode, 0);
        TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) ctx.world()
            .getTileEntity(x, y, z);
        node.setPlacer(owner);
        return node;
    }

    private void bindAnchor(TestContext ctx) {
        deadline(ctx, 2000, "Native 196-block controller did not become active");
        TileEntity controller = controllers.get(0);
        if (!ItemNetworkQuantumTerminal.isControllerActive((TileController) controller)) return;
        owner.inventory.setInventorySlotContents(0, quantumTerminal);
        ((ItemNetworkQuantumTerminal) quantumTerminal.getItem()).onItemUseFirst(
            quantumTerminal,
            owner,
            ctx.world(),
            controller.xCoord,
            controller.yCoord,
            controller.zCoord,
            1,
            .5f,
            .5f,
            .5f);
        demand(
            ItemNetworkQuantumTerminal.isBound(quantumTerminal),
            "Quantum terminal did not bind the native controller");
        begin(ctx, 4, "BIND_QUANTUM_NODES");
    }

    private void bindNodes(TestContext ctx) {
        int end = Math.min(nodes.size(), cursor + 4);
        for (; cursor < end; cursor++) nodes.get(cursor)
            .setAnchor(
                ctx.world().provider.dimensionId,
                AeStressControllerLayout.X,
                AeStressControllerLayout.Y + CONTROLLER_Y_OFFSET,
                AeStressControllerLayout.Z);
        workload.observed.put("nodesBound", (long) cursor);
        if (cursor == nodes.size()) {
            owner.inventory.setInventorySlotContents(0, linkTerminal);
            begin(ctx, 5, "BIND_WIRELESS_POWER");
        }
    }

    private void bindPower(TestContext ctx) {
        if (cursor == power.size()) {
            begin(ctx, 6, "REGISTER_DIT");
            return;
        }
        Power endpoint = power.get(cursor);
        WirelessEnergyTap.LinkResult result = ((WirelessEnergyTap) linkTerminal.getItem())
            .setLinkState(linkTerminal, owner, ctx.world(), endpoint.tile, endpoint.side, endpoint.dynamo, true);
        if (result == WirelessEnergyTap.LinkResult.COOLDOWN) return;
        demand(
            result == WirelessEnergyTap.LinkResult.APPLIED,
            "Native link terminal rejected power endpoint " + cursor);
        workload.observed.put("linkedPowerEndpoints", (long) ++cursor);
    }

    private void bindDevices(TestContext ctx) {
        int end = Math.min(StressMachineFixtures.COUNT, cursor + 8);
        for (; cursor < end; cursor++) {
            StressMachineFixtures.Machine machine = fixtures.machines()
                .get(cursor);
            int terminal = cursor / Config.deviceTerminalMaxMachines;
            ItemStack item = terminals.get(terminal);
            owner.inventory.setInventorySlotContents(0, item);
            BaseMetaTileEntity tile = machine.controller;
            ((ItemDeviceInfoTerminal) item.getItem())
                .onItemUseFirst(item, owner, ctx.world(), tile.xCoord, tile.yCoord, tile.zCoord, 1, .5f, .5f, .5f);
            String key = key(ctx, tile);
            DeviceTerminalDataStore.TerminalData data = store.getTerminal(terminalIds.get(terminal));
            demand(data != null && data.boundKeys.contains(key), "Real DIT callback did not bind " + key);
            demand(
                DeviceRegistryData.get(ctx.world())
                    .getEntry(key) != null,
                "Native device registry missing " + key);
            demand(
                DeviceFlowMonitor.watch(tile.getMetaTileEntity(), timeoutTicks() + 100L) != null,
                "Natural energy observer could not enroll " + key);
            tile.enableWorking();
        }
        workload.observed.put("ditBindings", (long) cursor);
        if (cursor != StressMachineFixtures.COUNT) return;
        for (int i = 0; i < terminals.size(); i++) {
            owner.inventory.setInventorySlotContents(i + 1, terminals.get(i));
            DeviceTerminalDataStore.ensureActiveTerminal(owner.getUniqueID(), terminalIds.get(i));
        }
        TileEntityChest chest = (TileEntityChest) ctx.world()
            .getTileEntity(ENTRY_X, ENTRY_Y, ENTRY_Z);
        chest.setInventorySlotContents(0, quantumTerminal.copy());
        chest.setInventorySlotContents(1, linkTerminal.copy());
        for (int i = 0; i < terminals.size(); i++) {
            if (i + 2 >= chest.getSizeInventory()) break;
            chest.setInventorySlotContents(
                i + 2,
                terminals.get(i)
                    .copy());
        }
        chest.markDirty();
        lowerClampBaseline = supply.lowerClamps;
        upperClampBaseline = supply.upperClamps;
        begin(ctx, 7, "WARMUP");
    }

    private void warmup(TestContext ctx) {
        deadline(
            ctx,
            14000,
            "Factory did not establish native structure, material flow and DIT energy evidence: " + readyMachines
                + "/448 ready");
        // Exercise both guards before profiling. Their repair is performed by the source's ordinary 100-tick cadence.
        if (clampProbe == 0) {
            setFixtureBalance(StressSupplyTile.EU_LOW.subtract(BigInteger.ONE));
            clampProbe = 1;
        } else if (clampProbe == 1 && supply.lowerClamps > lowerClampBaseline) {
            setFixtureBalance(StressSupplyTile.EU_HIGH.multiply(BigInteger.valueOf(2)));
            clampProbe = 2;
        } else if (clampProbe == 2 && supply.upperClamps > upperClampBaseline) clampProbe = 3;
        if (ctx.tick() - phaseTick < 200) return;
        sample(ctx);
        if (ctx.tick() - lastHeartbeatTick >= 200) heartbeat(ctx);
        if (sampleCursor != 0 || ctx.tick() - lastReadinessTick < 200) return;
        lastReadinessTick = ctx.tick();
        readyMachines = countReady(ctx);
        workload.observed.put("readyMachines", (long) readyMachines);
        if (ctx.tick() - lastDiagnosticTick >= 1000) {
            lastDiagnosticTick = ctx.tick();
            int reported = 0;
            for (TestWorldReport.Machine machine : workload.machines) {
                if ("READY".equals(machine.readiness)) continue;
                GTSimpleWirelessNetwork.LOG.info(
                    "GTSWN_NATIVE_STRESS_WAIT run={} id={} type={} reason={}",
                    ctx.runId(),
                    machine.id,
                    machine.type,
                    machine.readiness);
                if (++reported == 8) break;
            }
        }
        if (!networkReady()) return;
        if (readyMachines != StressMachineFixtures.COUNT || clampProbe != 3) return;
        demand(
            TestWorldManager.verifiedForcedChunkCount(ctx.world(), footprintRadius()) == 100,
            "Factory footprint was not fully loaded and forced");
        demand(
            TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_CONNECT) > 0
                && TestWorldProbes.count(TestWorldProbes.Type.DEVICE_REGISTER) >= StressMachineFixtures.COUNT
                && TestWorldProbes.count(TestWorldProbes.Type.DEVICE_FLOW) > 0
                && TestWorldProbes.count(TestWorldProbes.Type.EU_DOWNLINK) > 0,
            "Native quantum, DIT or wireless power events were not observed");
        stableTick = ctx.tick();
        snapshotHealth();
        begin(ctx, 8, "STABLE");
        workload.observed.put("sparkReadyTick", stableTick);
        ctx.event(
            "spark_ready",
            "All 448 native machines have structure, actual ME transport, energy and DIT evidence; external profiler may start");
        GTSimpleWirelessNetwork.LOG.info(
            "GTSWN_NATIVE_STRESS_READY run={} phase=STABLE windowTicks={} machines=448",
            ctx.runId(),
            STABLE_TICKS);
    }

    private void setFixtureBalance(BigInteger balance) {
        BigInteger current = WirelessNetworkManager.getUserEU(owner.getUniqueID());
        demand(
            WirelessNetworkManager.addEUToGlobalEnergyMap(owner.getUniqueID(), balance.subtract(current)),
            "Synthetic owner balance probe was rejected");
    }

    private void stable(TestContext ctx) {
        sample(ctx);
        long elapsed = ctx.tick() - stableTick;
        workload.observed.put("stableTicksObserved", elapsed);
        if (ctx.tick() - lastHeartbeatTick >= 200) heartbeat(ctx);
        if (elapsed - lastHealthTick >= 1000 && sampleCursor == 0) {
            demand(networkReady(), "Native AE/quantum endpoint lost its channel in the stable window");
            int ready = countReady(ctx);
            demand(ready == StressMachineFixtures.COUNT, "Stable factory lost native readiness: " + ready + "/448");
            for (int i = 0; i < StressMachineFixtures.COUNT; i++) {
                TestWorldReport.Machine fact = workload.machines.get(i);
                StressMachineFixtures.Machine machine = fixtures.machines()
                    .get(i);
                if (!machine.itemInputs.isEmpty())
                    demand(fact.exportedInput > lastInput[i], "No fresh native ME item input for machine " + i);
                if (!machine.fluidInputs.isEmpty())
                    demand(fact.fluidInput > lastFluidInput[i], "No fresh native ME fluid input for machine " + i);
                if (!machine.itemOutputs.isEmpty())
                    demand(fact.importedOutput > lastOutput[i], "No fresh native ME item output for machine " + i);
                if (!machine.fluidOutputs.isEmpty())
                    demand(fact.fluidOutput > lastFluidOutput[i], "No fresh native ME fluid output for machine " + i);
            }
            snapshotHealth();
            lastHealthTick = elapsed;
        }
        if (elapsed < STABLE_TICKS) return;
        updateTotals();
        completed = true;
        workload.phase = "COMPLETE";
        phase.endedTick = ctx.tick();
        workload.observed.put("stableMachines", 448L);
        workload.observed.put("retainedDITTerminals", (long) terminals.size());
        GTSimpleWirelessNetwork.LOG
            .info("GTSWN_NATIVE_STRESS_COMPLETE run={} stableTicks={} machines=448", ctx.runId(), elapsed);
        ctx.pass(
            "448 native machines sustained " + elapsed
                + " stable ticks with wireless ME material transport, native wireless energy and DIT monitoring; reusable field retained");
    }

    private void sample(TestContext ctx) {
        int end = Math.min(StressMachineFixtures.COUNT, sampleCursor + SAMPLE_BATCH);
        for (; sampleCursor < end; sampleCursor++) {
            StressMachineFixtures.Machine machine = fixtures.machines()
                .get(sampleCursor);
            StressMachineFixtures.Snapshot snapshot = fixtures.sampleMachine(machine);
            TestWorldReport.Machine fact = workload.machines.get(sampleCursor);
            fact.progressSamples++;
            if (snapshot.active) fact.activeSamples++;
            fact.nativeStructureFormed = snapshot.formed;
            fact.nativeActive = snapshot.active;
            fact.nativeProgress = snapshot.progress;
            fact.nativeMaxProgress = snapshot.maxProgress;
            fact.nativeRecipeStatus = snapshot.recipeStatus;
            observeMaterialWork(
                fact,
                sampleCursor >= 384,
                sampledInput[sampleCursor],
                sampledOutput[sampleCursor],
                sampledFluidInput[sampleCursor],
                sampledFluidOutput[sampleCursor],
                ctx.tick());
            sampledInput[sampleCursor] = fact.exportedInput;
            sampledOutput[sampleCursor] = fact.importedOutput;
            sampledFluidInput[sampleCursor] = fact.fluidInput;
            sampledFluidOutput[sampleCursor] = fact.fluidOutput;
            if (snapshot.completedRecipes > fact.nativeRecipeCounter)
                observeNativeWork(fact, ctx.tick(), EVIDENCE_RECIPES, "native_recipe_counter");
            fact.nativeRecipeCounter = snapshot.completedRecipes;
            fact.maxObservedNativeParallel = Math.max(fact.maxObservedNativeParallel, snapshot.nativeParallel);
            fact.maxObservedOutputRecipeBatch = Math.max(fact.maxObservedOutputRecipeBatch, snapshot.outputRecipeBatch);
            fact.maxObservedParallel = fact.maxObservedNativeParallel;
            fact.maxObservedRawEU = Math.max(fact.maxObservedRawEU, snapshot.storedEU);
            if (snapshot.progress != lastProgress[sampleCursor]) {
                observeNativeWork(fact, ctx.tick(), EVIDENCE_PROGRESS, "native_progress_change");
                if (snapshot.progress < lastProgress[sampleCursor]) fact.observedProgressResets++;
            }
            lastProgress[sampleCursor] = snapshot.progress;
        }
        if (sampleCursor == StressMachineFixtures.COUNT) sampleCursor = 0;
    }

    private int countReady(TestContext ctx) {
        int ready = 0;
        for (int i = 0; i < StressMachineFixtures.COUNT; i++) {
            StressMachineFixtures.Machine machine = fixtures.machines()
                .get(i);
            TestWorldReport.Machine fact = workload.machines.get(i);
            DeviceTerminalDataStore.TerminalData terminal = store
                .getTerminal(terminalIds.get(i / Config.deviceTerminalMaxMachines));
            DeviceTerminalDataStore.MachineRecord record = terminal.records.get(key(ctx, machine.controller));
            boolean input = machine.itemInputs.isEmpty() || fact.exportedInput > 0;
            input &= machine.fluidInputs.isEmpty() || fact.fluidInput > 0;
            boolean output = machine.itemOutputs.isEmpty() || fact.importedOutput > 0;
            output &= machine.fluidOutputs.isEmpty() || fact.fluidOutput > 0;
            boolean energy = record != null && record.count > 0
                && record.evidenceKind == 0
                && (i >= 384 ? record.outAvg > 0 : record.inAvg > 0);
            fact.ditSamples = record == null ? 0 : record.count;
            fact.ditInputEUt = record == null ? 0 : record.inAvg;
            fact.ditOutputEUt = record == null ? 0 : record.outAvg;
            boolean progressing = fact.nativeEvidenceMask != 0 && ctx.tick() - fact.lastNativeProgressTick < 1000;
            boolean linked = DeviceMachineIdentity.linkNodeType(machine.controller.getMetaTileEntity()) != 0;
            if (machineReady(fact, input, output, energy, linked, ctx.tick())) {
                ready++;
                fact.readiness = "READY";
            } else fact.readiness = "formed=" + fact.nativeStructureFormed
                + ";active="
                + fact.nativeActive
                + ";activeSamples="
                + fact.activeSamples
                + ";itemIn="
                + fact.exportedInput
                + ";fluidIn="
                + fact.fluidInput
                + ";itemOut="
                + fact.importedOutput
                + ";fluidOut="
                + fact.fluidOutput
                + ";DIT="
                + fact.ditSamples
                + ";euIn="
                + fact.ditInputEUt
                + ";euOut="
                + fact.ditOutputEUt
                + ";progress="
                + fact.nativeProgress
                + "/"
                + fact.nativeMaxProgress
                + ";recipeStatus="
                + fact.nativeRecipeStatus
                + ";linked="
                + linked
                + ";recentProgress="
                + progressing
                + ";recentEvidence="
                + fact.latestNativeEvidence;
        }
        return ready;
    }

    static <T> void registerNativeSource(Map<Object, T> registered, BaseMetaTileEntity tile, T endpoint) {
        // GT ME inputs use the base tile as MachineSource.via; native ME outputs use their MTE instance.
        registered.put(tile, endpoint);
        registered.put(tile.getMetaTileEntity(), endpoint);
    }

    static boolean clearFixtureRegistration(DeviceRegistryData registry, WorldServer world, UUID fixtureOwner,
        BaseMetaTileEntity controller) {
        demand(TestDimension.isTest(world) && !world.isRemote, "Factory registry reset requires the test dimension");
        demand(
            TestWorldFixtures.owner(ID + ":owner")
                .equals(fixtureOwner),
            "Foreign factory fixture owner");
        demand(controller.getWorldObj() == world, "Foreign factory controller world");
        String key = DeviceRegistryData
            .makeKey(world.provider.dimensionId, controller.xCoord, controller.yCoord, controller.zCoord);
        DeviceRegistryData.DeviceEntry previous = registry.getEntry(key);
        if (previous == null) return false;
        demand(fixtureOwner.equals(previous.owner), "Factory controller registry key belongs to another owner: " + key);
        return registry.unregister(key);
    }

    static void observeNativeWork(TestWorldReport.Machine fact, long tick, int evidence, String name) {
        fact.lastNativeProgressTick = tick;
        fact.latestNativeEvidence = name;
        fact.nativeEvidenceMask |= evidence;
    }

    static void observeMaterialWork(TestWorldReport.Machine fact, boolean generator, long inputBefore,
        long outputBefore, long fluidInputBefore, long fluidOutputBefore, long tick) {
        if (fact.importedOutput > outputBefore || fact.fluidOutput > fluidOutputBefore)
            observeNativeWork(fact, tick, EVIDENCE_OUTPUT, "ae_output_modulate");
        if (generator && (fact.exportedInput > inputBefore || fact.fluidInput > fluidInputBefore))
            observeNativeWork(fact, tick, EVIDENCE_FUEL, "ae_fuel_modulate");
    }

    static boolean machineReady(TestWorldReport.Machine fact, boolean input, boolean output, boolean energy,
        boolean linked, long tick) {
        return fact.nativeStructureFormed && input
            && output
            && energy
            && linked
            && fact.nativeEvidenceMask != 0
            && tick - fact.lastNativeProgressTick < 1000;
    }

    private boolean networkReady() {
        IGrid grid = grid();
        for (TileEntityNetworkQuantumNode node : nodes) {
            IGridNode nativeNode = node.getCoreGridNode();
            if (!node.isLinked() || nativeNode == null || !nativeNode.isActive() || nativeNode.getGrid() != grid)
                return false;
        }
        for (Endpoint endpoint : endpoints) {
            IGridNode node = endpoint.part != null ? endpoint.part.getGridNode()
                : ((IGridHost) endpoint.fixture.tile).getGridNode(endpoint.fixture.side);
            if (node == null || !node.isActive() || node.getGrid() != grid) return false;
        }
        IGridNode node = supply.getActionableNode();
        return node != null && node.isActive() && node.getGrid() == grid;
    }

    private IGrid grid() {
        IGridNode node = ((IGridHost) controllers.get(0)).getGridNode(ForgeDirection.UNKNOWN);
        demand(node != null, "Native controller grid unavailable");
        return node.getGrid();
    }

    @Override
    public void transferred(Object source, boolean fluid, boolean inserted, long amount) {
        Endpoint endpoint = sources.get(source);
        if (endpoint == null) return;
        TestWorldReport.Machine fact = workload.machines.get(endpoint.machine);
        long signed = endpoint.input == inserted ? -amount : amount;
        if (fluid) {
            if (endpoint.input) fact.fluidInput += signed;
            else fact.fluidOutput += signed;
        } else {
            if (endpoint.input) fact.exportedInput += signed;
            else fact.importedOutput += signed;
        }
    }

    private void snapshotHealth() {
        for (int i = 0; i < StressMachineFixtures.COUNT; i++) {
            TestWorldReport.Machine fact = workload.machines.get(i);
            lastInput[i] = fact.exportedInput;
            lastOutput[i] = fact.importedOutput;
            lastFluidInput[i] = fact.fluidInput;
            lastFluidOutput[i] = fact.fluidOutput;
        }
    }

    private void updateTotals() {
        workload.observed.put("nativeMEItemExtracted", supply.itemExtracted);
        workload.observed.put("nativeMEItemInserted", supply.itemInserted);
        workload.observed.put("nativeMEFluidExtractedMB", supply.fluidExtracted);
        workload.observed.put("nativeMEFluidInsertedMB", supply.fluidInserted);
        workload.observed.put("wirelessLowerClamps", supply.lowerClamps);
        workload.observed.put("wirelessUpperClamps", supply.upperClamps);
        workload.observed.put("quantumNodes", (long) nodes.size());
        workload.observed.put("meEndpoints", (long) endpoints.size());
        workload.observed.put("wirelessPowerEndpoints", (long) power.size());
        workload.observed.put(
            "wirelessBalanceDigits",
            (long) WirelessNetworkManager.getUserEU(owner.getUniqueID())
                .toString()
                .length());
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (TestWorldReport.Machine machine : workload.machines) {
            String prefix = machine.kind + ":" + machine.type + ":";
            aggregate(grouped, prefix + "itemInput", machine.exportedInput);
            aggregate(grouped, prefix + "itemOutput", machine.importedOutput);
            aggregate(grouped, prefix + "fluidInputMB", machine.fluidInput);
            aggregate(grouped, prefix + "fluidOutputMB", machine.fluidOutput);
            grouped.put(
                prefix + "maxObservedParallel",
                Math.max(grouped.getOrDefault(prefix + "maxObservedParallel", 0L), machine.maxObservedParallel));
            grouped.put(
                prefix + "maxObservedNativeParallel",
                Math.max(
                    grouped.getOrDefault(prefix + "maxObservedNativeParallel", 0L),
                    machine.maxObservedNativeParallel));
            grouped.put(
                prefix + "maxObservedOutputRecipeBatch",
                Math.max(
                    grouped.getOrDefault(prefix + "maxObservedOutputRecipeBatch", 0L),
                    machine.maxObservedOutputRecipeBatch));
        }
        workload.observed.putAll(grouped);
    }

    private static void aggregate(Map<String, Long> values, String key, long amount) {
        values.put(key, values.getOrDefault(key, 0L) + amount);
    }

    private static String ingredients(List<ItemStack> items, List<FluidStack> fluids) {
        StringBuilder text = new StringBuilder();
        for (ItemStack stack : items) {
            if (text.length() > 0) text.append(';');
            text.append(Item.itemRegistry.getNameForObject(stack.getItem()))
                .append('@')
                .append(stack.getItemDamage())
                .append('*')
                .append(stack.stackSize);
            if (stack.hasTagCompound()) text.append(stack.getTagCompound());
        }
        for (FluidStack stack : fluids) {
            if (text.length() > 0) text.append(';');
            text.append(
                stack.getFluid()
                    .getName())
                .append('*')
                .append(stack.amount)
                .append("mB");
            if (stack.tag != null) text.append(stack.tag);
        }
        return text.toString();
    }

    private void heartbeat(TestContext ctx) {
        lastHeartbeatTick = ctx.tick();
        updateTotals();
        ctx.event(
            "factory_heartbeat",
            "phase=" + workload.phase + ";ready=" + readyMachines + "/448;tick=" + ctx.tick());
        GTSimpleWirelessNetwork.LOG.info(
            "GTSWN_NATIVE_STRESS_HEARTBEAT run={} phase={} tick={} ready={}/448 stableTicks={}",
            ctx.runId(),
            workload.phase,
            ctx.tick(),
            readyMachines,
            stableTick == 0 ? 0 : ctx.tick() - stableTick);
    }

    private void begin(TestContext ctx, int nextStage, String name) {
        if (phase != null) phase.endedTick = ctx.tick();
        stage = nextStage;
        cursor = 0;
        phaseTick = ctx.tick();
        workload.phase = name;
        phase = new TestWorldReport.WorkloadPhase(name, ctx.tick());
        workload.phases.add(phase);
        ctx.beginWorkloadPhase(phase);
        ctx.event("factory_phase", name);
        GTSimpleWirelessNetwork.LOG
            .info("GTSWN_NATIVE_STRESS_PHASE run={} phase={} tick={}", ctx.runId(), name, ctx.tick());
    }

    private void deadline(TestContext ctx, long ticks, String message) {
        demand(ctx.tick() - phaseTick <= ticks, message);
    }

    private static String key(TestContext ctx, BaseMetaTileEntity tile) {
        return DeviceRegistryData.makeKey(ctx.world().provider.dimensionId, tile.xCoord, tile.yCoord, tile.zCoord);
    }

    private static void demand(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (supply != null) {
            updateTotals();
            supply.observe(null);
        }
        if (workload != null && !completed) workload.phase = "RETAINED_PARTIAL";
        if (phase != null) phase.endedTick = ctx.tick();
        // Native source, wireless covers, DIT actor and machine states deliberately survive PASS/stop/failure.
        ctx.event(
            "factory_retained",
            "Fixture state retained; entry chest=(0,90,8), source=(0,90,4); build/reset removes this test field");
    }

    private static final class Endpoint {

        final int machine;
        final StressMachineFixtures.Endpoint fixture;
        final boolean input, fluid;
        TileEntityNetworkQuantumNode node;
        IPart part;

        Endpoint(int machine, StressMachineFixtures.Endpoint fixture) {
            this.machine = machine;
            this.fixture = fixture;
            input = fixture.role.name()
                .endsWith("INPUT");
            fluid = fixture.role.name()
                .startsWith("FLUID");
        }
    }

    private static final class Power {

        final BaseMetaTileEntity tile;
        final ForgeDirection side;
        final boolean dynamo;

        Power(BaseMetaTileEntity tile, ForgeDirection side, boolean dynamo) {
            this.tile = tile;
            this.side = side;
            this.dynamo = dynamo;
        }
    }
}
