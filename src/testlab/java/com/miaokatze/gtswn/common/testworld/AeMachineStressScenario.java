package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityHopper;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.IncludeExclude;
import appeng.api.implementations.tiles.ISegmentedInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.parts.IPart;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ICellWorkbenchItem;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.IStorageMonitorable;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.api.util.IOrientable;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;
import appeng.tile.AEBaseInvTile;
import appeng.tile.misc.TileInterface;
import appeng.tile.networking.TileController;
import appeng.tile.storage.TileChest;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.common.covers.Cover;
import gregtech.common.covers.CoverEnergyWirelessDebug;

/** Native AE stocking and bus traffic; the driver never transfers items during observation. */
final class AeMachineStressScenario implements TestScenario, IMEMonitorHandlerReceiver<IAEItemStack> {

    private static final int PLACE_BATCH = 8, VERIFY_BATCH = 64, CELL_BATCH = 4, MACHINE_BATCH = 64;
    private static final int CONFIG_BATCH = 64, WINDOW_TICKS = 1600, DISTURBED_NODES = 8;
    private static final long CELL_ITEMS = 520192;
    private final AeMachineStressLayout.Profile profile;
    private final AeMachineStressLayout layout;
    private final List<TileEntityNetworkQuantumNode> nodes = new ArrayList<>();
    // AE power tiles expose optional Mod interfaces: resolve members through their stable public views below.
    private final List<TileEntity> interfaces = new ArrayList<>();
    private final List<TileEntity> controllers = new ArrayList<>();
    private final List<TileEntity> drives = new ArrayList<>();
    private final List<TileEntity> chests = new ArrayList<>();
    private final List<TileEntityHopper> hoppers = new ArrayList<>();
    private final List<ChestObserver> chestObservers = new ArrayList<>();
    private final List<BaseMetaTileEntity> machines = new ArrayList<>();
    private final List<PartExportBus> exporters = new ArrayList<>();
    private final List<PartImportBus> importers = new ArrayList<>();
    private final List<ItemStack> palette = new ArrayList<>();
    private final List<ItemStack> cells = new ArrayList<>();
    private final List<IGridNode> endpointNodes = new ArrayList<>();
    private final Map<Object, Integer> interfaceSources = new IdentityHashMap<>();
    private final Map<Object, Integer> exportSources = new IdentityHashMap<>();
    private final Map<Object, Integer> importSources = new IdentityHashMap<>();
    private final long[] interfaceInserted, interfaceExtracted, interfaceWindowBefore, interfaceWindowInserted,
        interfaceWindowExtracted, machineWindowInput, machineWindowOutput, machineWindowChest;
    private final int[] requested;
    private final Object listenerToken = new Object();
    private TestWorldReport.Workload workload;
    private TestWorldReport.WorkloadPhase phase;
    private FakePlayer owner;
    private ItemStack terminal;
    private IMEMonitor<IAEItemStack> observedMonitor;
    private int stage, cursor, machineCursor, configCursor, window, verifyLinked, verifyActive, controllerAirFaces,
        partitionedOutputCells;
    private long stageTick, monitorCalls, insertedItems, extractedItems, inputExports, outputImports, listUpdates;
    private long phaseCalls, phaseInserted, phaseExtracted, phaseInput, phaseOutput, phaseChest, phaseConfig,
        configChanges, chestOutputs;
    private long lastChangeTick, currentTick, baselineInput, baselineOutput, finalInput, finalOutput, finalOther;
    private long snapshotCalls, quietAuditStartedTick, busConfigChanges, phaseBusConfig, phaseStartedNanos;
    private boolean listening, quiescing;

    AeMachineStressScenario() {
        this(AeMachineStressLayout.Profile.TEN_THOUSAND);
    }

    AeMachineStressScenario(AeMachineStressLayout.Profile profile) {
        layout = new AeMachineStressLayout(profile);
        this.profile = layout.profile;
        interfaceInserted = new long[profile.nodes];
        interfaceExtracted = new long[profile.nodes];
        interfaceWindowBefore = new long[profile.nodes];
        interfaceWindowInserted = new long[profile.nodes];
        interfaceWindowExtracted = new long[profile.nodes];
        machineWindowInput = new long[profile.machines];
        machineWindowOutput = new long[profile.machines];
        machineWindowChest = new long[profile.machines];
        requested = new int[profile.nodes];
    }

    @Override
    public String id() {
        return "ae.machine_stress_" + profile.suffix;
    }

    @Override
    public String description() {
        return "Explicit " + profile.nodes
            + " real quantum nodes and native stock/return interfaces, "
            + profile.machines
            + " native GT electric furnaces, "
            + profile.machines * 2
            + " native buses, "
            + profile.storagePairs
            + " drives/"
            + profile.storagePairs
            + " ME chests; three sustained 1600-tick material-traffic windows";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int footprintRadius() {
        return AeMachineStressLayout.RADIUS;
    }

    @Override
    public int timeoutTicks() {
        return 30000;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        clearReferences();
        workload = new TestWorldReport.Workload();
        workload.kind = "native-ae-gt-machine-" + profile.suffix;
        workload.retention = "PASS leaves native debug-powered GT machines, enabled buses and " + profile.pairs
            + " unlocked vanilla-hopper "
            + "stock/return loops running when chunks are loaded. No driver, observer or config-storm callback survives. "
            + "Stop/failure preserves the exact reached state; a stop during the material audit leaves source hoppers "
            + "redstone-locked and some machines paused, as reported. Remove source lock blocks and enable machines "
            + "to resume manually, or rerun the explicit scenario to rebuild. Runner tickets are released; continued "
            + "whole-field ticking needs external chunk loading. build/reset clears the persisted 160x160 footprint.";
        workload.coverageLimitations.add("Server-only: no client rendering, GUI input, full restart or FPS claim.");
        workload.coverageLimitations.add(
            "Observed AE stock, bus, storage/channel/reconnect events; no crafting jobs, "
                + "security revocation, whole-grid outage, disk failure or every AE event kind is claimed.");
        workload.coverageLimitations.add(
            "Real GT Cover_Wireless_Energy_Debug covers supply native raw EU every 20 ticks; "
                + "this is a material/AE workload, not a survival-energy distribution benchmark.");
        workload.coverageLimitations.add(
            "Global monitor item counters describe gross change notifications, including refunds; "
                + "machine input/output counters are signed net transactions. The separate physical inventory audit proves conservation.");
        workload.coverageLimitations.add(
            "Material counters stop at the exact quiet inventory audit, before native machines "
                + "and hopper loops are resumed. Later inventories continue changing naturally; materialAuditTick identifies the audited snapshot.");
        workload.coverageLimitations.add(
            "Empty ME-chest output cells use native stone-only partitions to reserve local GT output capacity. "
                + "Workbench configuration entries are filters, not stored material.");
        ctx.result.workload = workload;
        expected("quantumNodes", profile.nodes);
        expected("configuredInterfaces", profile.nodes);
        expected("stockingInterfaces", profile.pairs);
        expected("returnInterfaces", profile.pairs);
        expected("gtMachines", profile.machines);
        expected("exportBuses", profile.machines);
        expected("importBuses", profile.machines);
        expected("drives", profile.storagePairs);
        expected("meChests", profile.storagePairs);
        expected("controllers", AeMachineStressLayout.CONTROLLERS);
        expected("activeEndpoints", profile.channels);
        expected("controllerChannelBudget", AeMachineStressLayout.CHANNEL_BUDGET);
        expected("controllerNonControllerFaces", AeStressControllerLayout.EXPOSED_FACES);
        expected("controllerAirFacesAfterBuild", AeStressControllerLayout.EXPOSED_FACES - 1);
        expected("fullCells", profile.fullCells);
        expected("emptyOutputCells", profile.emptyCells);
        expected("stoneOnlyOutputCells", profile.emptyCells);
        expected("itemTypes", AeMachineStressLayout.ITEM_TYPES);
        expected("initialCellItems", profile.fullCells * CELL_ITEMS);
        expected("initialInterfaceItems", profile.pairs * 64L);
        expected("vanillaHoppers", profile.pairs * 3L);
        expected("nativeDebugEnergyCovers", profile.machines);
        expected("nativeMonitorObservers", profile.storagePairs + 1);
        expected("fixtureEUInjected", 0);
        expected("chestsReceivingMachineOutput", profile.storagePairs);
        expected("loadWindows", 3);
        expected("ticksPerLoadWindow", WINDOW_TICKS);
        expected("interfacesTransferringPerWindow", profile.nodes);
        expected("machinesWithImportedOutput", profile.machines);
        expected("disturbedNodes", DISTURBED_NODES);
        expected("recipeInputPerOutput", 1);
        expected("forcedLoadedChunks", 100);
        selectItems(ctx);
        owner = TestActors.player(ctx.world(), "owner");
        owner.capabilities.allowEdit = true;
        owner.capabilities.isCreativeMode = false;
        owner.setSneaking(false);
        owner.setPosition(-2.5, 65, -.5);
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        owner.inventory.setInventorySlotContents(0, terminal);
        for (int first = 0; first < profile.nodes; first += 250)
            workload.transferGroups.add(new TestWorldReport.TransferGroup(first, 250));
        stage = cursor = machineCursor = configCursor = window = verifyLinked = verifyActive = controllerAirFaces = partitionedOutputCells = 0;
        monitorCalls = insertedItems = extractedItems = inputExports = outputImports = listUpdates = configChanges = chestOutputs = busConfigChanges = 0;
        quiescing = false;
        next(ctx, 0, "build");
        ctx.event(
            "workload_contract",
            description() + "; max 8 block placements, 4 cell fills, 64 config changes, "
                + "64 native progress samples or 64 endpoint checks per tick; only native AE algorithms move items. "
                + "196 naturally valid controllers supply a 24704-channel registered non-controller-face budget for "
                + profile.channels
                + " active endpoints.");
    }

    private void expected(String key, long value) {
        workload.expected.put(key, value);
    }

    private void selectItems(TestContext ctx) {
        palette.add(new ItemStack(Blocks.cobblestone));
        palette.add(new ItemStack(Blocks.stone));
        List<String> names = new ArrayList<>();
        for (Object key : Item.itemRegistry.getKeys()) {
            String name = key.toString();
            if (name.startsWith("minecraft:") && !name.equals("minecraft:air")) names.add(name);
        }
        Collections.sort(names);
        for (String name : names) {
            Item item = (Item) Item.itemRegistry.getObject(name);
            if (item == null || item == palette.get(0)
                .getItem()
                || item == palette.get(1)
                    .getItem())
                continue;
            ItemStack stack = new ItemStack(item, 1, 0);
            if (stack.getMaxStackSize() > 0 && AEItemStack.create(stack) != null) palette.add(stack);
            if (palette.size() == AeMachineStressLayout.ITEM_TYPES) break;
        }
        require(
            ctx,
            palette.size() == AeMachineStressLayout.ITEM_TYPES,
            "256 distinct registered native item identities");
        for (ItemStack item : palette)
            workload.itemTypes.add(Item.itemRegistry.getNameForObject(item.getItem()) + "@0");
        for (int i = 0; i < requested.length; i++) {
            requested[i] = i % 2 == 0 ? 64 : 0;
            interfaceInserted[i] = interfaceExtracted[i] = interfaceWindowBefore[i] = 0;
        }
    }

    @Override
    public void tick(TestContext ctx) {
        currentTick = ctx.tick();
        long started = ctx.operationStart();
        String operation = phase == null ? "cleanup" : phase.name;
        try {
            if (stage >= 6) sampleMachines();
            switch (stage) {
                case 0:
                    build(ctx);
                    break;
                case 1:
                    bindController(ctx);
                    break;
                case 2:
                    seedInterfaces(ctx);
                    break;
                case 3:
                    fillCells(ctx);
                    break;
                case 4:
                    bindNodes(ctx);
                    break;
                case 5:
                    settleAndVerify(ctx);
                    break;
                case 6:
                    installBuses(ctx);
                    break;
                case 7:
                    settleBuses(ctx);
                    break;
                case 8:
                    loadWindow(ctx);
                    break;
                case 9:
                    disturb(ctx);
                    break;
                case 10:
                    recover(ctx);
                    break;
                case 11:
                    quiesce(ctx);
                    break;
                case 12:
                    waitQuiet(ctx);
                    break;
                case 13:
                    scanInventory(ctx);
                    break;
                case 14:
                    resumeNative(ctx);
                    break;
                case 15:
                    idleBackground(ctx);
                    break;
                case 16:
                    unlockLoops(ctx);
                    break;
                default:
                    throw new IllegalStateException("Unknown machine workload stage " + stage);
            }
        } finally {
            ctx.operationEnd("ae_machine." + operation, started);
        }
    }

    private void build(TestContext ctx) {
        int end = Math.min(layout.placements.size(), cursor + PLACE_BATCH);
        for (; cursor < end; cursor++) {
            AeMachineStressLayout.Placement p = layout.placements.get(cursor);
            if (p.kind == AeMachineStressLayout.Kind.NODE) {
                TestWorldFixtures.place(ctx.world(), p.x, p.y, p.z, BlockRegistrar.networkQuantumNode, 0);
                TileEntityNetworkQuantumNode tile = (TileEntityNetworkQuantumNode) ctx.world()
                    .getTileEntity(p.x, p.y, p.z);
                tile.setPlacer(owner);
                nodes.add(tile);
            } else if (p.kind == AeMachineStressLayout.Kind.MACHINE) {
                ItemStack item = ItemList.Machine_LV_E_Furnace.get(1);
                BaseMetaTileEntity tile = TestWorldFixtures
                    .machine(ctx.world(), p.x, p.y, p.z, item, owner.getUniqueID());
                MTEBasicMachine machine = (MTEBasicMachine) tile.getMetaTileEntity();
                machine.setMainFacing(ForgeDirection.NORTH);
                tile.setFrontFacing(ForgeDirection.DOWN);
                tile.disableWorking();
                Cover cover = CoverRegistry
                    .buildCover(ItemList.Cover_Wireless_Energy_Debug.get(1), ForgeDirection.UP, tile);
                tile.attachCover(cover);
                demand(
                    tile.getCoverAtSide(ForgeDirection.UP) instanceof CoverEnergyWirelessDebug,
                    "Native GT debug energy cover did not install");
                machines.add(tile);
                TestWorldReport.Machine fact = new TestWorldReport.Machine(
                    p.index,
                    p.x,
                    p.y,
                    p.z,
                    item.getItemDamage());
                fact.nativeDebugEnergyCover = true;
                workload.machines.add(fact);
            } else if (p.kind == AeMachineStressLayout.Kind.SOURCE_LOCK) {
                TestWorldFixtures.place(ctx.world(), p.x, p.y, p.z, Blocks.redstone_block, 0);
            } else if (p.kind == AeMachineStressLayout.Kind.HOPPER_EAST
                || p.kind == AeMachineStressLayout.Kind.HOPPER_NORTH) {
                    TestWorldFixtures.place(
                        ctx.world(),
                        p.x,
                        p.y,
                        p.z,
                        Blocks.hopper,
                        p.kind == AeMachineStressLayout.Kind.HOPPER_EAST ? ForgeDirection.EAST.ordinal()
                            : ForgeDirection.NORTH.ordinal());
                    hoppers.add(
                        (TileEntityHopper) ctx.world()
                            .getTileEntity(p.x, p.y, p.z));
                } else if (p.kind == AeMachineStressLayout.Kind.ENTRY) {
                    TestWorldFixtures.place(ctx.world(), p.x, p.y, p.z, Blocks.chest, 2);
                } else {
                    TileEntity tile = TestWorldFixtures.aeOwnedBlock(ctx.world(), p.x, p.y, p.z, block(p.kind), owner);
                    if (tile instanceof TileController) controllers.add(tile);
                    if (tile instanceof TileDrive) drives.add(tile);
                    if (tile instanceof TileChest) chests.add(tile);
                    if (tile instanceof TileInterface) {
                        ((IOrientable) tile).setOrientation(ForgeDirection.EAST, ForgeDirection.UP);
                        interfaceSources.put(tile, interfaces.size());
                        interfaces.add(tile);
                    }
                }
        }
        workload.observed.put("placedBlocks", (long) cursor);
        if (cursor == layout.placements.size()) next(ctx, 1, "bind_controller");
    }

    private static ItemStack block(AeMachineStressLayout.Kind kind) {
        switch (kind) {
            case CONTROLLER:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .controller()
                    .maybeStack(1)
                    .get();
            case POWER:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .energyCellCreative()
                    .maybeStack(1)
                    .get();
            case DRIVE:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .drive()
                    .maybeStack(1)
                    .get();
            case CHEST:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .chest()
                    .maybeStack(1)
                    .get();
            case INTERFACE:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .iface()
                    .maybeStack(1)
                    .get();
            default:
                throw new IllegalArgumentException("Not an AE block: " + kind);
        }
    }

    private void bindController(TestContext ctx) {
        checkDeadline(ctx, 1200, "196-block native controller structure comes online");
        if (!ItemNetworkQuantumTerminal.isControllerActive((TileController) controllers.get(0))) return;
        TileEntity tile = controllers.get(0);
        ((ItemNetworkQuantumTerminal) terminal.getItem())
            .onItemUseFirst(terminal, owner, ctx.world(), tile.xCoord, tile.yCoord, tile.zCoord, 1, .5f, .5f, .5f);
        require(
            ctx,
            ItemNetworkQuantumTerminal.isBound(terminal),
            "Real first-click terminal binds controller generation");
        TileEntityChest entry = (TileEntityChest) ctx.world()
            .getTileEntity(AeMachineStressLayout.ENTRY_X, AeMachineStressLayout.ENTRY_Y, AeMachineStressLayout.ENTRY_Z);
        entry.setInventorySlotContents(0, terminal.copy());
        entry.markDirty();
        workload.observed.put("retainedBoundTerminals", 1L);
        next(ctx, 2, "seed_interfaces");
    }

    private void seedInterfaces(TestContext ctx) {
        int end = Math.min(interfaces.size(), cursor + VERIFY_BATCH);
        for (; cursor < end; cursor++) {
            TileEntity iface = interfaces.get(cursor);
            ItemStack stock = palette.get(0)
                .copy();
            stock.stackSize = 64;
            if (cursor % 2 == 0) {
                ((ISegmentedInventory) iface).getInventoryByName("storage")
                    .setInventorySlotContents(0, stock.copy());
                ((ISegmentedInventory) iface).getInventoryByName("config")
                    .setInventorySlotContents(0, stock.copy());
            }
            iface.markDirty();
        }
        if (cursor == interfaces.size()) next(ctx, 3, "initialize_cells");
    }

    private void fillCells(TestContext ctx) {
        int total = profile.fullCells + profile.emptyCells;
        int end = Math.min(total, cursor + CELL_BATCH);
        for (; cursor < end; cursor++) {
            int module = cursor / 11, position = cursor % 11;
            boolean empty = position == 10;
            TileEntity host = position < 10 ? drives.get(module) : chests.get(module);
            int slot = position < 10 ? position : 1;
            int type = (module * 10 + position) % palette.size();
            ItemStack cell = AEApi.instance()
                .definitions()
                .items()
                .cell64k()
                .maybeStack(1)
                .get();
            ICellInventory<IAEItemStack> inv = cellInventory(cell);
            long capacity = (inv.getTotalBytes() - inv.getBytesPerType()) * 8;
            demand(capacity == CELL_ITEMS, "Unexpected native single-type 64k capacity");
            if (empty) cell = reserveOutputCell(cell, capacity);
            IAEItemStack extra = null;
            if (!empty) extra = inv.injectItems(
                AEItemStack.create(palette.get(type))
                    .setStackSize(capacity + 1),
                Actionable.MODULATE,
                new PlayerSource(owner, null));
            inv = cellInventory(cell);
            TestWorldReport.Cell fact = new TestWorldReport.Cell(
                host.xCoord,
                host.yCoord,
                host.zCoord,
                slot,
                empty ? -1 : type,
                inv.getTotalBytes(),
                capacity);
            fact.initializedCount = inv.getStoredItemCount();
            fact.initializedTypes = inv.getStoredItemTypes();
            fact.initiallyFull = !empty && fact.initializedCount == capacity
                && fact.initializedTypes == 1
                && inv.getRemainingItemCount() == 0;
            fact.rejectedExtra = extra != null && extra.getStackSize() == 1;
            demand(
                empty ? fact.initializedCount == 0 : fact.initiallyFull && fact.rejectedExtra,
                "Native cell initialization rejected: " + cursor);
            if (!empty) {
                IItemList<IAEItemStack> contents = inv.getAvailableItems(
                    AEApi.instance()
                        .storage()
                        .createItemList());
                IAEItemStack actual = contents.findPrecise(AEItemStack.create(palette.get(type)));
                demand(
                    contents.size() == 1 && actual != null && actual.getStackSize() == capacity,
                    "Persisted native full cell item identity differs from its declared palette type: " + cursor);
            }
            ((AEBaseInvTile) host).getInternalInventory()
                .setInventorySlotContents(slot, cell);
            host.markDirty();
            cells.add(cell);
            workload.cells.add(fact);
            if (!empty && type == 0) baselineInput += capacity;
            if (!empty && type == 1) baselineOutput += capacity;
        }
        workload.observed.put("initializedCells", (long) cursor);
        if (cursor == total) {
            demand(
                partitionedOutputCells == profile.emptyCells,
                "Every output cell needs its native stone-only partition");
            baselineInput += profile.pairs * 64L;
            workload.observed.put("baselineCobblestone", baselineInput);
            workload.observed.put("baselineStone", baselineOutput);
            workload.observed.put("fullCells", (long) profile.fullCells);
            workload.observed.put("emptyOutputCells", (long) profile.emptyCells);
            next(ctx, 4, "bind_nodes");
        }
    }

    private ItemStack reserveOutputCell(ItemStack cell, long capacity) {
        demand(cell.getItem() instanceof ICellWorkbenchItem, "Native output cell is not workbench-configurable");
        ((ICellWorkbenchItem) cell.getItem()).getConfigAEInventory(cell)
            .putAEStackInSlot(
                0,
                AEItemStack.create(palette.get(1))
                    .setStackSize(1));
        // Rebuild from a deep NBT copy, as a freshly loaded chest does; never reuse an unfiltered raw inventory.
        ItemStack persisted = cell.copy();
        ICellInventoryHandler<IAEItemStack> handler = cellHandler(persisted);
        ICellInventory<IAEItemStack> inventory = handler.getCellInv();
        demand(
            handler.isPreformatted() && handler.getIncludeExcludeMode() == IncludeExclude.WHITELIST
                && !handler.isFuzzy(),
            "Persisted output cell lost its precise native whitelist");
        IAEItemStack stoneExtra = handler.injectItems(
            AEItemStack.create(palette.get(1))
                .setStackSize(capacity + 1),
            Actionable.SIMULATE,
            new PlayerSource(owner, null));
        IAEItemStack cobblestoneExtra = handler.injectItems(
            AEItemStack.create(palette.get(0))
                .setStackSize(1),
            Actionable.SIMULATE,
            new PlayerSource(owner, null));
        demand(
            stoneExtra != null && stoneExtra.getStackSize() == 1
                && cobblestoneExtra != null
                && cobblestoneExtra.getStackSize() == 1,
            "Native output partition must accept a full stone type and reject every cobblestone item");
        demand(
            inventory.getStoredItemCount() == 0 && inventory.getStoredItemTypes() == 0
                && inventory.getRemainingItemCount() > 0
                && inventory.getRemainingItemTypes() > 0,
            "Output partition configuration or simulation created material or consumed native capacity");
        workload.observed.put("stoneOnlyOutputCells", (long) ++partitionedOutputCells);
        workload.observed.put(
            "minimumOutputCellRemainingItems",
            Math.min(
                workload.observed.getOrDefault("minimumOutputCellRemainingItems", Long.MAX_VALUE),
                inventory.getRemainingItemCount()));
        workload.observed.put(
            "minimumOutputCellRemainingTypes",
            Math.min(
                workload.observed.getOrDefault("minimumOutputCellRemainingTypes", Long.MAX_VALUE),
                inventory.getRemainingItemTypes()));
        return persisted;
    }

    private void bindNodes(TestContext ctx) {
        int end = Math.min(nodes.size(), cursor + 4);
        for (; cursor < end; cursor++) {
            // Both genuine member coordinates are canonicalized by the production binding entrypoint.
            nodes.get(cursor)
                .setAnchor(
                    ctx.world().provider.dimensionId,
                    AeStressControllerLayout.X,
                    AeStressControllerLayout.Y,
                    AeStressControllerLayout.Z + cursor % 2);
        }
        if (cursor == nodes.size()) {
            endpointNodes.clear();
            for (TileEntity iface : interfaces) endpointNodes.add(node(iface));
            for (TileEntity drive : drives) endpointNodes.add(node(drive));
            for (TileEntity chest : chests) endpointNodes.add(node(chest));
            next(ctx, 5, "verify_native_network");
        }
    }

    private void settleAndVerify(TestContext ctx) {
        checkDeadline(ctx, 4000, "All " + profile.nodes + " bridges and native channels settle");
        if (ctx.tick() - stageTick < 200) return;
        int total = nodes.size() + endpointNodes.size() + controllers.size();
        int end = Math.min(total, cursor + VERIFY_BATCH);
        IGrid grid = grid();
        for (; cursor < end; cursor++) {
            boolean ready;
            if (cursor < nodes.size()) {
                TileEntityNetworkQuantumNode tile = nodes.get(cursor);
                IGridNode core = tile.getCoreGridNode();
                ready = tile.isLinked() && core != null
                    && core.isActive()
                    && core.getGrid() == grid
                    && tile.getAnchorX() == AeStressControllerLayout.X
                    && tile.getAnchorY() == AeStressControllerLayout.Y
                    && tile.getAnchorZ() == AeStressControllerLayout.Z;
                if (ready) verifyLinked++;
            } else if (cursor < nodes.size() + endpointNodes.size()) {
                IGridNode endpoint = endpointNodes.get(cursor - nodes.size());
                ready = activeOn(endpoint, grid);
                if (ready) verifyActive++;
            } else {
                TileEntity tile = controllers.get(cursor - nodes.size() - endpointNodes.size());
                ready = activeOn(node(tile), grid);
                for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) if (ctx.world()
                    .isAirBlock(
                        tile.xCoord + direction.offsetX,
                        tile.yCoord + direction.offsetY,
                        tile.zCoord + direction.offsetZ))
                    controllerAirFaces++;
            }
            if (!ready) {
                cursor = verifyLinked = verifyActive = controllerAirFaces = 0;
                return;
            }
        }
        if (cursor != total) return;
        require(ctx, powered(), "Native creative AE power persists without fixture energy injection");
        int forced = TestWorldManager.verifiedForcedChunkCount(ctx.world(), footprintRadius());
        require(ctx, forced == 100, "All 100 footprint chunks are actually loaded and forced by active native tickets");
        workload.observed.put("forcedLoadedChunks", (long) forced);
        int budget = QuantumControllerRegistry.computeTotalChannels(
            QuantumControllerRegistry.get(ctx.world())
                .registeredStructure(
                    QuantumControllerRegistry
                        .pack(AeStressControllerLayout.X, AeStressControllerLayout.Y, AeStressControllerLayout.Z)));
        require(
            ctx,
            budget == AeMachineStressLayout.CHANNEL_BUDGET,
            "Real registered controller non-controller-face budget is 24704");
        workload.observed.put("linkedQuantumNodes", (long) verifyLinked);
        workload.observed.put("activeControllers", (long) controllers.size());
        workload.observed.put("controllerChannelBudget", (long) budget);
        workload.observed.put("controllerNonControllerFaces", (long) budget / 32);
        workload.observed.put("controllerAirFacesAfterBuild", (long) controllerAirFaces);
        require(
            ctx,
            controllerAirFaces == AeStressControllerLayout.EXPOSED_FACES - 1,
            "Native controller has 772 non-controller faces and 771 actual air faces; creative power occupies one");
        workload.observed.put("configuredInterfaces", (long) interfaces.size());
        workload.observed.put("stockingInterfaces", (long) interfaces.size() / 2);
        workload.observed.put("returnInterfaces", (long) interfaces.size() / 2);
        workload.observed.put("drives", (long) drives.size());
        workload.observed.put("meChests", (long) chests.size());
        observedMonitor = monitor();
        listening = true;
        observedMonitor.addListener(this, listenerToken);
        for (int i = 0; i < chests.size(); i++) {
            // Native placement checked TileChest; this is its same local cell monitor, not a grid monitor.
            IMEMonitor<IAEItemStack> local = ((IStorageMonitorable) chests.get(i)).getItemInventory();
            demand(local != null, "Native chest local item monitor unavailable: " + i);
            ChestObserver observer = new ChestObserver(i, chests.get(i), local);
            chestObservers.add(observer);
            local.addListener(observer, listenerToken);
        }
        workload.observed.put("nativeMonitorObservers", (long) chestObservers.size() + 1);
        next(ctx, 15, "native_idle_background");
    }

    private void idleBackground(TestContext ctx) {
        if (ctx.tick() - stageTick < 200) return;
        phase.observed.put("ticks", ctx.tick() - stageTick);
        workload.observed.put("idleBackgroundTicks", ctx.tick() - stageTick);
        next(ctx, 6, "install_native_machine_buses");
    }

    private void unlockLoops(TestContext ctx) {
        int end = Math.min(profile.pairs, cursor + VERIFY_BATCH);
        for (; cursor < end; cursor++) lockSource(ctx, cursor, false);
        if (cursor == profile.pairs) startWindow(ctx, 0);
    }

    private void lockSource(TestContext ctx, int pair, boolean locked) {
        int x = AeMachineStressLayout.x(pair), y = AeMachineStressLayout.y(pair), z = AeMachineStressLayout.z(pair);
        if (locked) TestWorldFixtures.place(ctx.world(), x, y - 2, z + 1, Blocks.redstone_block, 0);
        else ctx.world()
            .setBlockToAir(x, y - 2, z + 1);
        // BlockHopper uses metadata bit 8 as the native redstone lock, updated by neighbor notifications.
        demand(
            ((ctx.world()
                .getBlockMetadata(x, y - 1, z + 1) & 8) != 0) == locked,
            "Native source hopper redstone state did not follow its lock: " + pair);
    }

    private void installBuses(TestContext ctx) {
        int end = Math.min(machines.size(), cursor + 4);
        for (; cursor < end; cursor++) {
            TileEntityNetworkQuantumNode from = nodes.get(cursor * 2), to = nodes.get(cursor * 2 + 1);
            demand(
                from.addPart(
                    AEApi.instance()
                        .definitions()
                        .parts()
                        .exportBus()
                        .maybeStack(1)
                        .get(),
                    ForgeDirection.EAST,
                    owner) == ForgeDirection.EAST,
                "Native export bus placement failed");
            demand(
                to.addPart(
                    AEApi.instance()
                        .definitions()
                        .parts()
                        .importBus()
                        .maybeStack(1)
                        .get(),
                    ForgeDirection.WEST,
                    owner) == ForgeDirection.WEST,
                "Native import bus placement failed");
            IPart export = from.getPart(ForgeDirection.EAST), imported = to.getPart(ForgeDirection.WEST);
            demand(export instanceof PartExportBus && imported instanceof PartImportBus, "Native bus types missing");
            PartExportBus exporter = (PartExportBus) export;
            PartImportBus importer = (PartImportBus) imported;
            exporters.add(exporter);
            importers.add(importer);
            exportSources.put(exporter, cursor);
            importSources.put(importer, cursor);
            setExportFilter(exporter, true);
            importer.getAEInventoryByName(StorageName.CONFIG)
                .putAEStackInSlot(0, AEItemStack.create(palette.get(1)));
            endpointNodes.add(exporter.getGridNode());
            endpointNodes.add(importer.getGridNode());
            machines.get(cursor)
                .enableWorking();
        }
        if (cursor == machines.size()) next(ctx, 7, "verify_machine_channels");
    }

    private void settleBuses(TestContext ctx) {
        checkDeadline(ctx, 4000, "Native machine bus channels and recipes become active");
        if (ctx.tick() - stageTick < 200) return;
        int end = Math.min(endpointNodes.size(), cursor + VERIFY_BATCH);
        for (; cursor < end; cursor++) if (!activeOn(endpointNodes.get(cursor), grid())) {
            cursor = 0;
            return;
        }
        if (cursor != endpointNodes.size()) return;
        workload.observed.put("activeEndpoints", (long) endpointNodes.size());
        workload.observed.put("exportBuses", (long) exporters.size());
        workload.observed.put("importBuses", (long) importers.size());
        workload.observed.put("gtMachines", (long) machines.size());
        next(ctx, 16, "unlock_native_hopper_loops");
    }

    private void sampleMachines() {
        for (int count = 0; count < MACHINE_BATCH; count++) {
            int index = machineCursor++ % machines.size();
            BaseMetaTileEntity tile = machines.get(index);
            MTEBasicMachine machine = (MTEBasicMachine) tile.getMetaTileEntity();
            TestWorldReport.Machine fact = workload.machines.get(index);
            fact.maxObservedRawEU = Math.max(fact.maxObservedRawEU, tile.getStoredEUuncapped());
            demand(
                tile.getCoverAtSide(ForgeDirection.UP) instanceof CoverEnergyWirelessDebug,
                "Native persistent energy cover disappeared: " + index);
            if (machine.mMaxProgresstime > 0 && machine.mProgresstime > 0) fact.progressSamples++;
            if (tile.isActive()) fact.activeSamples++;
        }
    }

    private void startWindow(TestContext ctx, int index) {
        window = index;
        for (int i = 0; i < machines.size(); i++) {
            TestWorldReport.Machine fact = workload.machines.get(i);
            machineWindowInput[i] = fact.exportedInput;
            machineWindowOutput[i] = fact.importedOutput;
            machineWindowChest[i] = fact.chestInsertedOutput;
            ((MTEBasicMachine) machines.get(i)
                .getMetaTileEntity()).mItemTransfer = false;
            if (i < chests.size()) setImportFilter(importers.get(i), true);
        }
        for (int i = 0; i < nodes.size(); i++) {
            interfaceWindowBefore[i] = interfaceInserted[i] + interfaceExtracted[i];
            interfaceWindowInserted[i] = interfaceInserted[i];
            interfaceWindowExtracted[i] = interfaceExtracted[i];
        }
        next(ctx, 8, index == 0 ? "sustained_load" : index == 1 ? "load_after_config_events" : "load_after_reconnect");
    }

    private void loadWindow(TestContext ctx) {
        // Rolling real stock requests keep every native source interface busy for many complete cycles.
        for (int changed = 0; changed < CONFIG_BATCH; changed++) {
            int index = configCursor++ % profile.pairs * 2;
            int amount = requested[index] == 64 ? 32 : 64;
            setInterfaceRequest(index, amount);
            configChanges++;
        }
        long age = ctx.tick() - stageTick;
        if (age >= WINDOW_TICKS / 2 && age < WINDOW_TICKS / 2 + (chests.size() + MACHINE_BATCH - 1) / MACHINE_BATCH) {
            int first = (int) (age - WINDOW_TICKS / 2) * MACHINE_BATCH;
            for (int i = first; i < Math.min(chests.size(), first + MACHINE_BATCH); i++) {
                // Native filter configuration prevents the bus competing with the physical GT -> ME chest path.
                setImportFilter(importers.get(i), false);
                ((MTEBasicMachine) machines.get(i)
                    .getMetaTileEntity()).mItemTransfer = true;
            }
        }
        if (age < WINDOW_TICKS) return;
        int transferred = 0, sourcesExtracting = 0, targetsInserting = 0;
        for (int i = 0; i < interfaces.size(); i++)
            if (interfaceInserted[i] + interfaceExtracted[i] > interfaceWindowBefore[i]) {
                transferred++;
                if (i % 2 == 0 && interfaceExtracted[i] > interfaceWindowExtracted[i]) sourcesExtracting++;
                if (i % 2 == 1 && interfaceInserted[i] > interfaceWindowInserted[i]) targetsInserting++;
            }
        phase.observed.put("interfacesWithNativeTransfers", (long) transferred);
        phase.observed.put("ticks", ctx.tick() - stageTick);
        phase.observed.put("configChanges", configChanges - phaseConfig);
        phase.observed.put("sourceInterfacesExtracting", (long) sourcesExtracting);
        phase.observed.put("returnInterfacesInserting", (long) targetsInserting);
        require(
            ctx,
            sourcesExtracting == profile.pairs && targetsInserting == profile.pairs,
            "All " + profile.pairs + " source and return paths exercise the correct native ME transfer direction");
        int machinesFlowing = 0, chestsFlowing = 0;
        for (int i = 0; i < machines.size(); i++) {
            TestWorldReport.Machine fact = workload.machines.get(i);
            long inputs = fact.exportedInput - machineWindowInput[i];
            long outputs = fact.importedOutput - machineWindowOutput[i];
            long chest = fact.chestInsertedOutput - machineWindowChest[i];
            fact.windowExportedInput.add(inputs);
            fact.windowImportedOutput.add(outputs);
            fact.windowChestInsertedOutput.add(chest);
            if (inputs > 0 && outputs > 0) machinesFlowing++;
            if (i < chests.size() && chest > 0) chestsFlowing++;
        }
        phase.observed.put("machinesWithNativeBusFlow", (long) machinesFlowing);
        phase.observed.put("chestsWithPhysicalMachineOutput", (long) chestsFlowing);
        require(
            ctx,
            machinesFlowing == machines.size(),
            "All " + profile.machines + " machines return actual native bus output in window " + window);
        require(
            ctx,
            chestsFlowing == chests.size(),
            "All " + profile.storagePairs + " ME chests receive actual native GT output in window " + window);
        capturePhaseTraffic();
        require(
            ctx,
            transferred == interfaces.size(),
            "Every one of " + profile.nodes + " native interfaces transfers real stock in window " + window);
        require(
            ctx,
            insertedItems > phaseInserted && extractedItems > phaseExtracted
                && inputExports > phaseInput
                && outputImports > phaseOutput,
            "Both native stocking directions and GT material flow remain active throughout window " + window);
        if (window == 0) startWindow(ctx, 1);
        else if (window == 1) next(ctx, 9, "bounded_native_reconnect");
        else {
            quiescing = true;
            next(ctx, 11, "quiesce_for_material_audit");
        }
    }

    private void setInterfaceRequest(int index, int amount) {
        ItemStack stock = palette.get(0)
            .copy();
        stock.stackSize = amount;
        ((ISegmentedInventory) interfaces.get(index)).getInventoryByName("config")
            .setInventorySlotContents(0, amount == 0 ? null : stock);
        requested[index] = amount;
    }

    private void disturb(TestContext ctx) {
        // One real bridge removal per tick; do not revoke the whole network or inject synthetic events.
        if (cursor < DISTURBED_NODES) {
            nodes.get(profile.disturbedFirst + cursor)
                .disconnectFromDequantizedAnchor();
            cursor++;
            return;
        }
        if (ctx.tick() - stageTick < 80) return;
        next(ctx, 10, "recover_native_reconnect");
    }

    private void recover(TestContext ctx) {
        checkDeadline(ctx, 1800, "Perturbed native quantum bridges recover naturally");
        int index = profile.disturbedFirst + cursor;
        if (cursor < DISTURBED_NODES) {
            if (!nodes.get(index)
                .isLinked() || !activeOn(node(interfaces.get(index)), grid())) return;
            cursor++;
            return;
        }
        workload.observed.put("recoveredDisturbedNodes", (long) DISTURBED_NODES);
        workload.observed
            .put("nativeDisconnectEvents", TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_DISCONNECT));
        require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_DISCONNECT) >= DISTURBED_NODES,
            "Production probes observed all bounded bridge disconnects");
        startWindow(ctx, 2);
    }

    private void quiesce(TestContext ctx) {
        int total = interfaces.size() + machines.size();
        int end = Math.min(total, cursor + VERIFY_BATCH);
        for (; cursor < end; cursor++) {
            if (cursor < interfaces.size()) {
                setInterfaceRequest(cursor, cursor % 2 == 0 ? 64 : 0);
                if (cursor % 2 == 0) lockSource(ctx, cursor / 2, true);
            } else {
                int machine = cursor - interfaces.size();
                setExportFilter(exporters.get(machine), false);
                machines.get(machine)
                    .disableWorking();
            }
        }
        if (cursor == total) next(ctx, 12, "await_native_stock_quiet");
    }

    private void waitQuiet(TestContext ctx) {
        checkDeadline(ctx, 3000, "Native queues and current recipes settle for an exact physical material audit");
        if (ctx.tick() - stageTick < 250 || ctx.tick() - lastChangeTick < 200) return;
        int total = machines.size() + hoppers.size();
        int end = Math.min(total, cursor + VERIFY_BATCH);
        for (; cursor < end; cursor++) {
            boolean ready;
            if (cursor < machines.size()) {
                MTEBasicMachine machine = (MTEBasicMachine) machines.get(cursor)
                    .getMetaTileEntity();
                ready = machine.mMaxProgresstime == 0;
            } else {
                int hopper = cursor - machines.size();
                // A powered source hopper freezes its current inventory; only the two downstream tails drain.
                ready = hopper % 3 == 0 || inventoryEmpty(hoppers.get(hopper));
            }
            if (!ready) {
                cursor = 0;
                return;
            }
        }
        if (cursor == total) {
            snapshotCalls = monitorCalls;
            quietAuditStartedTick = ctx.tick();
            finalInput = finalOutput = finalOther = 0;
            next(ctx, 13, "physical_material_audit");
        }
    }

    private void scanInventory(TestContext ctx) {
        if (monitorCalls != snapshotCalls) {
            next(ctx, 12, "await_native_stock_quiet");
            return;
        }
        int total = cells.size() + interfaces
            .size() + machines.size() + chests.size() + hoppers.size() + nodes.size() + endpointNodes.size();
        int end = Math.min(total, cursor + VERIFY_BATCH);
        for (; cursor < end; cursor++) {
            int index = cursor;
            if (index < cells.size()) {
                TestWorldReport.Cell fact = workload.cells.get(index);
                TileEntity tile = ctx.world()
                    .getTileEntity(fact.x, fact.y, fact.z);
                ItemStack cell = ((AEBaseInvTile) tile).getInternalInventory()
                    .getStackInSlot(fact.slot);
                ICellInventory<IAEItemStack> inv = cellInventory(cell);
                fact.steadyCount = inv.getStoredItemCount();
                fact.steadyObserved = true;
                for (IAEItemStack item : inv.getAvailableItems(
                    AEApi.instance()
                        .storage()
                        .createItemList()))
                    addPhysical(item.getItemStack(), item.getStackSize());
            } else if ((index -= cells.size()) < interfaces.size()) {
                TileEntity tile = interfaces.get(index);
                IInventory inv = ((ISegmentedInventory) tile).getInventoryByName("storage");
                if (index % 2 == 0) demand(
                    inv.getStackInSlot(0) != null && inv.getStackInSlot(0)
                        .isItemEqual(palette.get(0)) && inv.getStackInSlot(0).stackSize == 64,
                    "Native source interface stock did not converge: " + index);
                else demand(inventoryEmpty(inv), "Native return interface did not flush to ME: " + index);
                for (int slot = 0; slot < inv.getSizeInventory(); slot++) addPhysical(inv.getStackInSlot(slot));
            } else if ((index -= interfaces.size()) < machines.size()) {
                BaseMetaTileEntity tile = machines.get(index);
                MTEBasicMachine machine = (MTEBasicMachine) tile.getMetaTileEntity();
                demand(machine.mMaxProgresstime == 0, "Recipe remained pending during physical audit");
                for (int slot = 0; slot < tile.getSizeInventory(); slot++) addPhysical(tile.getStackInSlot(slot));
                for (ItemStack item : machine.mOutputItems)
                    demand(item == null, "Pending output during physical audit");
                TestWorldReport.Machine fact = workload.machines.get(index);
                fact.auditedInputItems = countMatching(tile.getStackInSlot(machine.getInputSlot()), palette.get(0));
                fact.auditedOutputItems = countMatching(tile.getStackInSlot(machine.getOutputSlot()), palette.get(1));
                demand(
                    fact.exportedInput == fact.importedOutput + fact.chestInsertedOutput
                        + fact.auditedInputItems
                        + fact.auditedOutputItems,
                    "Per-machine physical inventory and net native transactions disagree: " + index);
                demand(
                    fact.exportedInput > 0 && fact.importedOutput > 0 && fact.progressSamples > 0,
                    "A real machine lacks native input, completed output or sampled recipe progress: " + index);
            } else if ((index -= machines.size()) < chests.size()) {
                IInventory inventory = ((AEBaseInvTile) chests.get(index)).getInternalInventory();
                demand(
                    inventory.getStackInSlot(0) == null,
                    "Native ME chest input slot did not flush to its cell: " + index);
                addPhysical(inventory.getStackInSlot(0));
            } else if ((index -= chests.size()) < hoppers.size()) {
                TileEntityHopper hopper = hoppers.get(index);
                if (index % 3 != 0) demand(inventoryEmpty(hopper), "Native hopper tail was not drained: " + index);
                else demand(
                    (ctx.world()
                        .getBlockMetadata(hopper.xCoord, hopper.yCoord, hopper.zCoord) & 8) != 0,
                    "Source hopper was not natively redstone-locked during audit: " + index);
                for (int slot = 0; slot < hopper.getSizeInventory(); slot++) addPhysical(hopper.getStackInSlot(slot));
            } else if ((index -= hoppers.size()) < nodes.size()) {
                TileEntityNetworkQuantumNode tile = nodes.get(index);
                demand(
                    tile.isLinked() && activeOn(tile.getCoreGridNode(), grid()),
                    "Retained quantum node is not active: " + index);
            } else {
                index -= nodes.size();
                demand(
                    activeOn(endpointNodes.get(index), grid()),
                    "Retained native endpoint has no active channel: " + index);
            }
        }
        if (cursor != total) return;
        require(
            ctx,
            TestWorldManager.verifiedForcedChunkCount(ctx.world(), footprintRadius()) == 100,
            "All 100 native chunk leases persist through physical material verification");
        long consumed = baselineInput - finalInput, produced = finalOutput - baselineOutput;
        require(
            ctx,
            consumed > 0 && consumed == produced,
            "Physical native inventories conserve the exact 1 cobble -> 1 stone recipe");
        require(
            ctx,
            produced >= outputImports + chestOutputs && outputImports > 0 && chestOutputs > 0,
            "Native imported output is bounded by physical completed recipe production");
        require(
            ctx,
            finalOther == (profile.fullCells * CELL_ITEMS - (baselineInput - profile.pairs * 64L) - baselineOutput),
            "All other initialized native item types are conserved");
        workload.observed.put("finalCobblestone", finalInput);
        workload.observed.put("finalStone", finalOutput);
        workload.observed.put("cobblestoneConsumed", consumed);
        workload.observed.put("stoneProduced", produced);
        workload.observed.put("otherItemsConserved", finalOther);
        workload.observed.put("machinesWithImportedOutput", (long) machines.size());
        workload.observed.put("loadWindows", 3L);
        workload.observed.put("loadTicks", WINDOW_TICKS * 3L);
        workload.observed.put("finalSnapshotMonitorCalls", monitorCalls);
        workload.observed.put("materialAuditTick", ctx.tick());
        workload.observed.put("materialAuditQuietTicks", ctx.tick() - quietAuditStartedTick + 200);
        workload.observed.put("chestsReceivingMachineOutput", (long) chests.size());
        workload.observed.put("nativeDebugEnergyCovers", (long) machines.size());
        workload.observed.put("fixtureEUInjected", 0L);
        workload.observed.put("vanillaHoppers", (long) hoppers.size());
        workload.observed.put("activeEndpoints", (long) endpointNodes.size());
        workload.observed.put("itemTypes", (long) palette.size());
        publishTraffic();
        // Keep the physical inventory and per-machine transaction evidence on the same exact boundary.
        // Native processing resumes over many subsequent world ticks and must not rewrite those audit counters.
        detachObservers();
        next(ctx, 14, "resume_retained_native_machines");
    }

    private void resumeNative(TestContext ctx) {
        int total = machines.size() + profile.pairs;
        int end = Math.min(total, cursor + MACHINE_BATCH);
        for (; cursor < end; cursor++) {
            if (cursor < machines.size()) {
                setExportFilter(exporters.get(cursor), true);
                machines.get(cursor)
                    .enableWorking();
            } else lockSource(ctx, cursor - machines.size(), false);
        }
        if (cursor != total) return;
        quiescing = false;
        workload.observed.put("observationEndTick", ctx.tick());
        workload.phase = "completed";
        phase.endedTick = ctx.tick();
        ctx.pass(
            description() + "; all nodes/"
                + profile.channels
                + " endpoints naturally active, each of "
                + profile.machines
                + " machines imported actual "
                + "recipe output; exact physical 1:1 material audit passed. "
                + workload.retention);
    }

    private void addPhysical(ItemStack stack) {
        if (stack != null) addPhysical(stack, stack.stackSize);
    }

    private void addPhysical(ItemStack stack, long count) {
        if (stack.isItemEqual(palette.get(0))) finalInput += count;
        else if (stack.isItemEqual(palette.get(1))) finalOutput += count;
        else finalOther += count;
    }

    private void setExportFilter(PartExportBus bus, boolean enabled) {
        bus.getAEInventoryByName(StorageName.CONFIG)
            .putAEStackInSlot(0, enabled ? AEItemStack.create(palette.get(0)) : null);
    }

    private void setImportFilter(PartImportBus bus, boolean output) {
        bus.getAEInventoryByName(StorageName.CONFIG)
            .putAEStackInSlot(0, AEItemStack.create(palette.get(output ? 1 : 0)));
        busConfigChanges++;
    }

    private static boolean inventoryEmpty(IInventory inventory) {
        for (int slot = 0; slot < inventory.getSizeInventory(); slot++)
            if (inventory.getStackInSlot(slot) != null) return false;
        return true;
    }

    private static long countMatching(ItemStack stack, ItemStack expected) {
        return stack != null && stack.isItemEqual(expected) ? stack.stackSize : 0;
    }

    /** Only local chest insertion by its own source proves the adjacent GT machine used input slot 0. */
    private final class ChestObserver implements IMEMonitorHandlerReceiver<IAEItemStack> {

        final int machine;
        final Object chest;
        final IMEMonitor<IAEItemStack> monitor;

        ChestObserver(int machine, Object chest, IMEMonitor<IAEItemStack> monitor) {
            this.machine = machine;
            this.chest = chest;
            this.monitor = monitor;
        }

        @Override
        public boolean isValid(Object token) {
            return listening && token == listenerToken;
        }

        @Override
        public void postChange(IBaseMonitor<IAEItemStack> ignored, Iterable<IAEItemStack> changes,
            BaseActionSource source) {
            if (!listening || !(source instanceof MachineSource)) return;
            Object via = ((MachineSource) source).via;
            if (via != chest) return;
            for (IAEItemStack change : changes) {
                if (!change.getItemStack()
                    .isItemEqual(palette.get(1))) continue;
                long delta = change.getStackSize();
                chestOutputs += delta;
                workload.machines.get(machine).chestInsertedOutput += delta;
            }
        }

        @Override
        public void onListUpdate() {}
    }

    @Override
    public boolean isValid(Object verificationToken) {
        return listening && verificationToken == listenerToken;
    }

    @Override
    public void postChange(IBaseMonitor<IAEItemStack> ignored, Iterable<IAEItemStack> changes,
        BaseActionSource source) {
        if (!listening) return;
        monitorCalls++;
        lastChangeTick = currentTick;
        Object via = source instanceof MachineSource ? ((MachineSource) source).via : null;
        Integer iface = interfaceSources.get(via), exporter = exportSources.get(via), importer = importSources.get(via);
        for (IAEItemStack change : changes) {
            long delta = change.getStackSize();
            if (delta > 0) insertedItems += delta;
            else extractedItems -= delta;
            if (iface != null) {
                if (delta > 0) interfaceInserted[iface] += delta;
                else interfaceExtracted[iface] -= delta;
            }
            ItemStack item = change.getItemStack();
            if (exporter != null && item.isItemEqual(palette.get(0))) {
                // A native exporter may refund a failed target insertion. Count its signed net debit.
                inputExports -= delta;
                workload.machines.get(exporter).exportedInput -= delta;
            }
            if (importer != null && item.isItemEqual(palette.get(1))) {
                outputImports += delta;
                workload.machines.get(importer).importedOutput += delta;
            }
        }
    }

    @Override
    public void onListUpdate() {
        if (listening) listUpdates++;
    }

    private void publishTraffic() {
        workload.observed.put("monitorCalls", monitorCalls);
        workload.observed.put("nativeInsertedItems", insertedItems);
        workload.observed.put("nativeExtractedItems", extractedItems);
        workload.observed.put("nativeInputExports", inputExports);
        workload.observed.put("nativeOutputImports", outputImports);
        workload.observed.put("nativePhysicalChestOutputs", chestOutputs);
        workload.observed.put("nativeListUpdates", listUpdates);
        workload.observed.put("configChanges", configChanges);
        workload.observed.put("busConfigChanges", busConfigChanges);
        workload.observed.put("nativeConnectEvents", TestWorldProbes.count(TestWorldProbes.Type.QUANTUM_NODE_CONNECT));
        for (TestWorldReport.TransferGroup group : workload.transferGroups) {
            group.nativeInserted = group.nativeExtracted = group.interfacesTransferred = 0;
            for (int i = group.firstNode; i < group.firstNode + group.nodeCount; i++) {
                group.nativeInserted += interfaceInserted[i];
                group.nativeExtracted += interfaceExtracted[i];
                if (interfaceInserted[i] + interfaceExtracted[i] > 0) group.interfacesTransferred++;
            }
        }
    }

    private void capturePhaseTraffic() {
        if (phase == null) return;
        phase.observed.put("monitorCalls", monitorCalls - phaseCalls);
        phase.observed.put("nativeInsertedItems", insertedItems - phaseInserted);
        phase.observed.put("nativeExtractedItems", extractedItems - phaseExtracted);
        phase.observed.put("nativeInputExports", inputExports - phaseInput);
        phase.observed.put("nativeOutputImports", outputImports - phaseOutput);
        phase.observed.put("nativePhysicalChestOutputs", chestOutputs - phaseChest);
        phase.observed.put("busConfigChanges", busConfigChanges - phaseBusConfig);
        long ticks = currentTick - phase.startedTick;
        long elapsed = System.nanoTime() - phaseStartedNanos;
        long outputs = outputImports - phaseOutput + chestOutputs - phaseChest;
        phase.observed.put("completedOutputItems", outputs);
        if (ticks > 0) phase.observed.put("completedOutputItemsPer1000Ticks", outputs * 1000 / ticks);
        if (elapsed > 0)
            phase.observed.put("completedOutputMilliItemsPerWallSecond", outputs * 1000000000000L / elapsed);
    }

    private void next(TestContext ctx, int nextStage, String name) {
        if (phase != null) {
            capturePhaseTraffic();
            phase.endedTick = ctx.tick();
        }
        stage = nextStage;
        cursor = 0;
        stageTick = ctx.tick();
        workload.phase = name;
        phase = new TestWorldReport.WorkloadPhase(name, ctx.tick());
        workload.phases.add(phase);
        ctx.beginWorkloadPhase(phase);
        phaseCalls = monitorCalls;
        phaseInserted = insertedItems;
        phaseExtracted = extractedItems;
        phaseInput = inputExports;
        phaseOutput = outputImports;
        phaseChest = chestOutputs;
        phaseConfig = configChanges;
        phaseBusConfig = busConfigChanges;
        phaseStartedNanos = System.nanoTime();
        ctx.event("workload_phase", name);
    }

    private IGrid grid() {
        IGridNode node = node(controllers.get(0));
        if (node == null) throw new IllegalStateException("Native controller grid unavailable");
        return node.getGrid();
    }

    private IMEMonitor<IAEItemStack> monitor() {
        return ((IStorageGrid) grid().getCache(IStorageGrid.class)).getItemInventory();
    }

    private boolean powered() {
        return ((IEnergyGrid) grid().getCache(IEnergyGrid.class)).isNetworkPowered();
    }

    private static boolean activeOn(IGridNode node, IGrid grid) {
        return node != null && node.isActive() && node.getGrid() == grid;
    }

    private static IGridNode node(TileEntity tile) {
        return ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
    }

    private static ICellInventory<IAEItemStack> cellInventory(ItemStack cell) {
        return cellHandler(cell).getCellInv();
    }

    private static ICellInventoryHandler<IAEItemStack> cellHandler(ItemStack cell) {
        Object handler = AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(cell, null, StorageChannel.ITEMS);
        if (!(handler instanceof ICellInventoryHandler)) throw new IllegalStateException("Native item cell missing");
        @SuppressWarnings("unchecked")
        ICellInventoryHandler<IAEItemStack> typed = (ICellInventoryHandler<IAEItemStack>) handler;
        return typed;
    }

    private static void require(TestContext ctx, boolean condition, String detail) {
        TestWorldFixtures.require(ctx, condition, detail);
    }

    private void checkDeadline(TestContext ctx, int limit, String detail) {
        if (ctx.tick() - stageTick >= limit) require(ctx, false, detail + "; phase=" + phase.name);
    }

    private static void demand(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (phase != null) {
            capturePhaseTraffic();
            phase.endedTick = ctx.tick();
        }
        if (workload != null) {
            publishTraffic();
            if (!"completed".equals(workload.phase)) workload.phase = "stopped_or_failed:" + workload.phase;
            workload.observed.put("machinesPausedForAuditAtStop", quiescing ? 1L : 0L);
        }
        detachObservers();
        if (owner != null) owner.inventory.clearInventory(null, -1);
        clearReferences();
    }

    private void detachObservers() {
        listening = false;
        if (observedMonitor != null) observedMonitor.removeListener(this);
        for (ChestObserver observer : chestObservers) observer.monitor.removeListener(observer);
    }

    private void clearReferences() {
        listening = false;
        nodes.clear();
        interfaces.clear();
        controllers.clear();
        drives.clear();
        chests.clear();
        hoppers.clear();
        chestObservers.clear();
        machines.clear();
        exporters.clear();
        importers.clear();
        palette.clear();
        cells.clear();
        endpointNodes.clear();
        interfaceSources.clear();
        importSources.clear();
        exportSources.clear();
        owner = null;
        terminal = null;
        observedMonitor = null;
        workload = null;
        phase = null;
        baselineInput = baselineOutput = 0;
    }
}
