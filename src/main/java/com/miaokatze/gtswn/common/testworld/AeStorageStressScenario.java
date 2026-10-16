package com.miaokatze.gtswn.common.testworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.register.BlockRegistrar;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ICellInventory;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.tile.AEBaseInvTile;
import appeng.tile.misc.TileInterface;
import appeng.tile.networking.TileController;
import appeng.tile.storage.TileChest;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;

/** Explicit-only native workload. All work is bounded and the authored network remains inspectable. */
final class AeStorageStressScenario implements TestScenario {

    private static final int BLOCKS_PER_TICK = 8, CELLS_PER_TICK = 4, VERIFY_PER_TICK = 16;
    private static final int WINDOW_TICKS = 200;
    private final AeStorageStressLayout layout;
    private final List<TileEntityNetworkQuantumNode> nodes = new ArrayList<>();
    private final List<TileEntity> endpoints = new ArrayList<>();
    private final List<TileDrive> drives = new ArrayList<>();
    private final List<TileChest> chests = new ArrayList<>();
    private final List<TileInterface> interfaces = new ArrayList<>();
    private final List<ItemStack> palette = new ArrayList<>();
    private final List<ItemStack> cells = new ArrayList<>();
    private TestWorldReport.Workload workload;
    private TestWorldReport.WorkloadPhase phase;
    private FakePlayer owner;
    private ItemStack terminal;
    private TileEntity controller;
    private int cursor, stage, activeEndpoints, verifiedInterfaceSlots;
    private long stageTick, initializedItems, baselineItems, queryCount, roundTrips;

    AeStorageStressScenario(boolean large) {
        layout = new AeStorageStressLayout(large);
    }

    @Override
    public String id() {
        return layout.layers == 1 ? "ae.storage_stress" : "ae.storage_stress_large";
    }

    @Override
    public String description() {
        return "Explicit native AE network retained for inspection: " + layout.nodes
            + " quantum nodes, "
            + layout.modules
            + " drives, "
            + layout.modules * 2
            + " configured interfaces, "
            + layout.modules
            + " ME chests, "
            + layout.cpus
            + " crafting CPUs, "
            + layout.cells
            + " single-type full 64k cells";
    }

    @Override
    public boolean includeInAll() {
        return false;
    }

    @Override
    public int timeoutTicks() {
        return 12000;
    }

    @Override
    public void start(TestContext ctx) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        clearReferences();
        owner = TestActors.player(ctx.world(), "owner");
        owner.capabilities.allowEdit = true;
        owner.capabilities.isCreativeMode = false;
        owner.setSneaking(false);
        owner.setPosition(-2.5, 65, -.5);
        terminal = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        owner.inventory.setInventorySlotContents(0, terminal);
        workload = new TestWorldReport.Workload();
        ctx.result.workload = workload;
        workload.expected.put("quantumNodes", (long) layout.nodes);
        workload.expected.put("controllers", 24L);
        workload.expected.put("creativeEnergyCells", 1L);
        workload.expected.put("drives", (long) layout.modules);
        workload.expected.put("configuredInterfaces", (long) layout.modules * 2);
        workload.expected.put("configuredInterfaceSlots", (long) layout.modules * 2 * 9);
        workload.expected.put("meChests", (long) layout.modules);
        workload.expected.put("craftingCpus", (long) layout.cpus);
        workload.expected.put("cells", (long) layout.cells);
        workload.expected.put("itemTypes", (long) layout.itemTypes);
        workload.expected.put("itemsPerCell", 520192L);
        workload.expected.put("initialCellItems", layout.cells * 520192L);
        workload.expected.put("idleTicks", (long) WINDOW_TICKS);
        workload.expected.put("queryTicks", (long) WINDOW_TICKS);
        selectItems(ctx);
        stage = 0;
        initializedItems = baselineItems = queryCount = roundTrips = 0;
        verifiedInterfaceSlots = 0;
        next(ctx, 0, "build");
        ctx.event(
            "workload_contract",
            description() + "; max 8 placements/4 fills per tick; native creative power, "
                + "natural channels and CPUs; no performance threshold. "
                + workload.retention);
    }

    private void selectItems(TestContext ctx) {
        List<String> names = new ArrayList<>();
        for (Object key : Item.itemRegistry.getKeys()) {
            String name = key.toString();
            if (name.startsWith("minecraft:") && !name.equals("minecraft:air")) names.add(name);
        }
        Collections.sort(names);
        for (String name : names) {
            Item item = (Item) Item.itemRegistry.getObject(name);
            if (item == null || item == Item.getItemFromBlock(Blocks.air)) continue;
            ItemStack stack = new ItemStack(item, 1, 0);
            if (stack.getMaxStackSize() <= 0 || AEItemStack.create(stack) == null) continue;
            palette.add(stack);
            workload.itemTypes.add(name + "@0");
            if (palette.size() == layout.itemTypes) break;
        }
        require(ctx, palette.size() == layout.itemTypes, "Enough distinct real registered vanilla item identities");
    }

    @Override
    public void tick(TestContext ctx) {
        long started = ctx.operationStart();
        String operation = phase == null ? "cleanup" : phase.name;
        try {
            if (stage == 0) build(ctx);
            else if (stage == 1) activateController(ctx);
            else if (stage == 2) bindNodes(ctx);
            else if (stage == 3) configureInterfaces(ctx);
            else if (stage == 4) fillCells(ctx);
            else if (stage == 5) settle(ctx);
            else if (stage == 6) verifyEndpoints(ctx);
            else if (stage == 7) verifyCells(ctx);
            else if (stage == 8) idle(ctx);
            else if (stage == 9) queryAndTransfer(ctx);
            else if (stage == 10) finish(ctx);
        } finally {
            ctx.operationEnd("ae_storage." + operation, started);
        }
    }

    private void build(TestContext ctx) {
        int limit = Math.min(layout.placements.size(), cursor + BLOCKS_PER_TICK);
        for (; cursor < limit; cursor++) {
            AeStorageStressLayout.Placement p = layout.placements.get(cursor);
            if (p.kind == AeStorageStressLayout.Kind.NODE) {
                TestWorldFixtures.place(ctx.world(), p.x, p.y, p.z, BlockRegistrar.networkQuantumNode, 0);
                TileEntityNetworkQuantumNode node = (TileEntityNetworkQuantumNode) ctx.world()
                    .getTileEntity(p.x, p.y, p.z);
                node.setPlacer(owner);
                nodes.add(node);
            } else if (p.kind == AeStorageStressLayout.Kind.ENTRY_CHEST) {
                TestWorldFixtures.place(ctx.world(), p.x, p.y, p.z, Blocks.chest, 2);
            } else {
                TileEntity tile = TestWorldFixtures.aeOwnedBlock(ctx.world(), p.x, p.y, p.z, block(p.kind), owner);
                if (p.kind == AeStorageStressLayout.Kind.CONTROLLER && controller == null) controller = tile;
                if (tile instanceof TileDrive) drives.add((TileDrive) tile);
                if (tile instanceof TileChest) chests.add((TileChest) tile);
                if (tile instanceof TileInterface) {
                    TileInterface iface = (TileInterface) tile;
                    // Native pointAt is Up.opposite(): DOWN is air and leaves both horizontal node faces open.
                    iface.setOrientation(ForgeDirection.EAST, ForgeDirection.UP);
                    interfaces.add(iface);
                }
                if (tile instanceof TileDrive || tile instanceof TileChest || tile instanceof TileInterface)
                    endpoints.add(tile);
            }
        }
        workload.observed.put("placedBlocks", (long) cursor);
        if (cursor == layout.placements.size()) next(ctx, 1, "controller_settle");
    }

    private static ItemStack block(AeStorageStressLayout.Kind kind) {
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
            case INTERFACE:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .iface()
                    .maybeStack(1)
                    .get();
            case CHEST:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .chest()
                    .maybeStack(1)
                    .get();
            case CPU_STORAGE:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .craftingStorage64k()
                    .maybeStack(1)
                    .get();
            case CPU_ACCELERATOR:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .craftingAccelerator()
                    .maybeStack(1)
                    .get();
            case CPU_MONITOR:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .craftingMonitor()
                    .maybeStack(1)
                    .get();
            case CPU_UNIT:
                return AEApi.instance()
                    .definitions()
                    .blocks()
                    .craftingUnit()
                    .maybeStack(1)
                    .get();
            default:
                throw new IllegalArgumentException("Not an AE block: " + kind);
        }
    }

    private void activateController(TestContext ctx) {
        if (ctx.tick() - stageTick > 600) {
            require(ctx, false, "Native 7x7 controller ring becomes online using its creative energy cell");
        }
        if (!ItemNetworkQuantumTerminal.isControllerActive((TileController) controller)) return;
        ((ItemNetworkQuantumTerminal) terminal.getItem()).onItemUseFirst(
            terminal,
            owner,
            ctx.world(),
            controller.xCoord,
            controller.yCoord,
            controller.zCoord,
            1,
            .5f,
            .5f,
            .5f);
        require(ctx, ItemNetworkQuantumTerminal.isBound(terminal), "Native first controller click binds terminal");
        TileEntityChest entry = (TileEntityChest) ctx.world()
            .getTileEntity(AeStorageStressLayout.ENTRY_X, AeStorageStressLayout.ENTRY_Y, AeStorageStressLayout.ENTRY_Z);
        entry.setInventorySlotContents(0, terminal.copy());
        entry.markDirty();
        workload.observed.put("retainedBoundTerminals", 1L);
        next(ctx, 2, "bind_nodes");
    }

    private void bindNodes(TestContext ctx) {
        int limit = Math.min(nodes.size(), cursor + 4);
        for (; cursor < limit; cursor++) {
            // Alternate genuine members; production resolves both to the first-click canonical identity.
            nodes.get(cursor)
                .setAnchor(ctx.world().provider.dimensionId, -3, 65, cursor % 2 == 0 ? -3 : -2);
        }
        if (cursor == nodes.size()) next(ctx, 3, "configure_interfaces");
    }

    private void configureInterfaces(TestContext ctx) {
        int limit = Math.min(interfaces.size(), cursor + 4);
        for (; cursor < limit; cursor++) {
            TileInterface tile = interfaces.get(cursor);
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stock = interfaceStock(cursor, slot);
                // Balanced real stock prevents the stocking algorithm draining a just-filled cell during
                // initialization.
                tile.getInventoryByName("storage")
                    .setInventorySlotContents(slot, stock.copy());
                tile.getInventoryByName("config")
                    .setInventorySlotContents(slot, stock.copy());
            }
            tile.markDirty();
        }
        workload.observed.put("configuredInterfaces", (long) cursor);
        if (cursor == interfaces.size()) next(ctx, 4, "fill_cells");
    }

    private void fillCells(TestContext ctx) {
        int limit = Math.min(layout.cells, cursor + CELLS_PER_TICK);
        for (; cursor < limit; cursor++) {
            int module = cursor / 11, slot = cursor % 11, type = cursor % palette.size();
            TileEntity tile = slot < 10 ? drives.get(module) : chests.get(module);
            int inventorySlot = slot < 10 ? slot : 1;
            ItemStack cell = AEApi.instance()
                .definitions()
                .items()
                .cell64k()
                .maybeStack(1)
                .get();
            ICellInventory<IAEItemStack> inventory = cellInventory(cell);
            long capacity = (inventory.getTotalBytes() - inventory.getBytesPerType()) * 8;
            requireSilent(ctx, capacity == 520192L, "Native 64k single-item capacity", cursor);
            IAEItemStack request = AEItemStack.create(palette.get(type))
                .setStackSize(capacity + 1);
            IAEItemStack rejected = inventory.injectItems(request, Actionable.MODULATE, new PlayerSource(owner, null));
            // Re-open through the registry: verify actual persisted native storage rather than private NBT counters.
            ICellInventory<IAEItemStack> persisted = cellInventory(cell);
            TestWorldReport.Cell fact = new TestWorldReport.Cell(
                tile.xCoord,
                tile.yCoord,
                tile.zCoord,
                inventorySlot,
                type,
                persisted.getTotalBytes(),
                capacity);
            fact.initializedCount = persisted.getStoredItemCount();
            fact.initializedTypes = persisted.getStoredItemTypes();
            fact.initiallyFull = fact.initializedTypes == 1 && fact.initializedCount == capacity
                && persisted.getRemainingItemCount() == 0;
            IAEItemStack extra = persisted.injectItems(
                AEItemStack.create(palette.get(type))
                    .setStackSize(1),
                Actionable.SIMULATE,
                new PlayerSource(owner, null));
            fact.rejectedExtra = rejected != null && rejected.getStackSize() == 1
                && extra != null
                && extra.getStackSize() == 1;
            requireSilent(
                ctx,
                fact.initiallyFull && fact.rejectedExtra,
                "Single native type fills and rejects extra",
                cursor);
            requireSilent(
                ctx,
                matchingCell(persisted, type, capacity),
                "Native cell contents match selected real item",
                cursor);
            IInventory host = ((AEBaseInvTile) tile).getInternalInventory();
            host.setInventorySlotContents(inventorySlot, cell);
            tile.markDirty();
            cells.add(cell);
            workload.cells.add(fact);
            initializedItems += capacity;
        }
        workload.observed.put("initializedFullCells", (long) cursor);
        workload.observed.put("initializedCellItems", initializedItems);
        if (cursor == layout.cells) {
            require(
                ctx,
                initializedItems == layout.cells * 520192L,
                "Every installed 64k cell initialized to native capacity");
            next(ctx, 5, "network_settle");
        }
    }

    private void settle(TestContext ctx) {
        if (ctx.tick() - stageTick > 1800) {
            require(ctx, false, "Native quantum bridges, channels and CPU clusters settle within 1800 ticks");
        }
        if (ctx.tick() - stageTick < 100 || (ctx.tick() - stageTick) % 20 != 0) return;
        int linked = 0;
        for (TileEntityNetworkQuantumNode node : nodes) if (node.isLinked()) linked++;
        workload.observed.put("linkedQuantumNodes", (long) linked);
        if (linked != layout.nodes) return;
        IGrid grid = grid();
        ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        workload.observed.put(
            "craftingCpus",
            (long) crafting.getCpus()
                .size());
        if (!powered(grid) || crafting.getCpus()
            .size() != layout.cpus) return;
        activeEndpoints = 0;
        next(ctx, 6, "verify_endpoints");
    }

    private void verifyEndpoints(TestContext ctx) {
        int limit = Math.min(endpoints.size(), cursor + VERIFY_PER_TICK);
        IGrid expectedGrid = grid();
        for (; cursor < limit; cursor++) {
            IGridNode node = node(endpoints.get(cursor));
            requireSilent(
                ctx,
                node != null && node.isActive() && node.getGrid() == expectedGrid,
                "Endpoint has a natural active channel on canonical network",
                cursor);
            if (endpoints.get(cursor) instanceof TileInterface) {
                TileInterface iface = (TileInterface) endpoints.get(cursor);
                int index = interfaces.indexOf(iface);
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack expected = interfaceStock(index, slot);
                    ItemStack config = iface.getInventoryByName("config")
                        .getStackInSlot(slot);
                    ItemStack stock = iface.getInventoryByName("storage")
                        .getStackInSlot(slot);
                    requireSilent(
                        ctx,
                        equalStock(expected, config) && equalStock(expected, stock),
                        "Interface native config and real stock retain balanced item and count",
                        index * 9 + slot);
                    verifiedInterfaceSlots++;
                }
            }
            activeEndpoints++;
        }
        workload.observed.put("activeEndpoints", (long) activeEndpoints);
        workload.observed.put("configuredInterfaceSlots", (long) verifiedInterfaceSlots);
        if (cursor == endpoints.size()) {
            workload.observed.put("drives", (long) drives.size());
            workload.observed.put("meChests", (long) chests.size());
            require(
                ctx,
                activeEndpoints == layout.modules * 4,
                "All drives, ME chests and interfaces have real active channels");
            require(
                ctx,
                verifiedInterfaceSlots == layout.modules * 2 * 9,
                "All nine real configuration and stock slots per interface match their requested identities and counts");
            next(ctx, 7, "verify_cells");
        }
    }

    private void verifyCells(TestContext ctx) {
        int limit = Math.min(cells.size(), cursor + VERIFY_PER_TICK);
        for (; cursor < limit; cursor++) {
            TestWorldReport.Cell fact = workload.cells.get(cursor);
            TileEntity tile = ctx.world()
                .getTileEntity(fact.x, fact.y, fact.z);
            IInventory host = ((AEBaseInvTile) tile).getInternalInventory();
            ICellInventory<IAEItemStack> inventory = cellInventory(host.getStackInSlot(fact.slot));
            fact.steadyCount = inventory.getStoredItemCount();
            fact.steadyObserved = true;
            requireSilent(
                ctx,
                inventory.getStoredItemTypes() == 1 && matchingCell(inventory, fact.itemType, fact.steadyCount)
                    && fact.steadyCount == fact.capacity
                    && inventory.getRemainingItemCount() == 0,
                "Installed native cell retains its original item identity at full native capacity",
                cursor);
        }
        if (cursor == cells.size()) {
            IItemList<IAEItemStack> available = available();
            verifyGridItems(ctx, available);
            baselineItems = count(available);
            workload.observed.put("steadyGridItems", baselineItems);
            workload.observed.put("steadyCellItems", steadyCellItems());
            next(ctx, 8, "idle_observation");
        }
    }

    private void idle(TestContext ctx) {
        if (ctx.tick() - stageTick < WINDOW_TICKS) return;
        workload.observed.put("idleTicks", ctx.tick() - stageTick);
        require(ctx, powered(grid()), "Native creative power persists across 200 idle ticks without fixture injection");
        require(ctx, count(available()) == baselineItems, "Idle observation preserves native grid item count");
        next(ctx, 9, "query_transfer_observation");
    }

    private void queryAndTransfer(TestContext ctx) {
        IItemList<IAEItemStack> items = available();
        requireSilent(ctx, count(items) == baselineItems, "Query window preserves item count", (int) queryCount);
        queryCount++;
        if (queryCount % 20 == 0) {
            long started = ctx.operationStart();
            IMEMonitor<IAEItemStack> monitor = monitor();
            IAEItemStack request = AEItemStack.create(palette.get((int) roundTrips % palette.size()))
                .setStackSize(64);
            IAEItemStack extracted = monitor.extractItems(request, Actionable.MODULATE, new PlayerSource(owner, null));
            require(
                ctx,
                extracted != null && extracted.getStackSize() == 64,
                "Native grid extracts exact 64-item sample");
            IAEItemStack leftover = monitor.injectItems(extracted, Actionable.MODULATE, new PlayerSource(owner, null));
            require(
                ctx,
                leftover == null || leftover.getStackSize() == 0,
                "Native grid accepts entire extracted sample back");
            roundTrips++;
            ctx.operationEnd("ae_storage.extract_reinsert", started);
        }
        workload.observed.put("queries", queryCount);
        workload.observed.put("roundTrips", roundTrips);
        if (queryCount >= WINDOW_TICKS) {
            workload.observed.put("queryTicks", ctx.tick() - stageTick);
            next(ctx, 10, "final_verify");
        }
    }

    private void finish(TestContext ctx) {
        IItemList<IAEItemStack> items = available();
        verifyGridItems(ctx, items);
        require(ctx, count(items) == baselineItems, "Completed native transfers leak no items");
        int linked = 0, active = 0;
        for (TileEntityNetworkQuantumNode node : nodes) if (node.isLinked()) linked++;
        for (TileEntity tile : endpoints) if (node(tile) != null && node(tile).isActive()) active++;
        require(
            ctx,
            linked == layout.nodes && active == endpoints.size() && powered(grid()),
            "Retained network remains powered, linked and naturally channeled after both observations");
        require(
            ctx,
            ((ICraftingGrid) grid().getCache(ICraftingGrid.class)).getCpus()
                .size() == layout.cpus,
            "All complete native crafting CPU structures remain registered");
        workload.observed.put("linkedQuantumNodes", (long) linked);
        workload.observed.put("activeEndpoints", (long) active);
        workload.observed.put("finalGridItems", count(items));
        workload.phase = "completed";
        phase.endedTick = ctx.tick();
        ctx.pass(
            description() + "; initialized full, observed 200 idle and 200 query ticks, 10 exact native transfers. "
                + "Bound terminal: ("
                + AeStorageStressLayout.ENTRY_X
                + ","
                + AeStorageStressLayout.ENTRY_Y
                + ","
                + AeStorageStressLayout.ENTRY_Z
                + ") chest slot 0. "
                + workload.retention);
    }

    private void verifyGridItems(TestContext ctx, IItemList<IAEItemStack> available) {
        int observed = 0;
        for (ItemStack item : palette) {
            IAEItemStack stack = available.findPrecise(AEItemStack.create(item));
            if (stack != null && stack.getStackSize() > 0) observed++;
        }
        workload.observed.put("itemTypes", (long) observed);
        require(ctx, observed == layout.itemTypes, "Real grid storage enumerates every selected item identity");
        // Balanced interfaces retain their own stock; every native cell should still be represented in grid storage.
        require(
            ctx,
            count(available) >= initializedItems,
            "Real grid storage exposes the initialized full cell workload");
    }

    private static ICellInventory<IAEItemStack> cellInventory(ItemStack stack) {
        Object handler = AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(stack, null, StorageChannel.ITEMS);
        if (!(handler instanceof ICellInventoryHandler)) throw new IllegalStateException("Not a native storage cell");
        @SuppressWarnings("unchecked")
        ICellInventoryHandler<IAEItemStack> typed = (ICellInventoryHandler<IAEItemStack>) handler;
        return typed.getCellInv();
    }

    private ItemStack interfaceStock(int interfaceIndex, int slot) {
        ItemStack stock = palette.get((interfaceIndex * 9 + slot) % palette.size())
            .copy();
        stock.stackSize = Math.min(64, stock.getMaxStackSize());
        return stock;
    }

    private static boolean equalStock(ItemStack expected, ItemStack actual) {
        return actual != null && expected.isItemEqual(actual) && expected.stackSize == actual.stackSize;
    }

    private boolean matchingCell(ICellInventory<IAEItemStack> inventory, int type, long amount) {
        IItemList<IAEItemStack> contents = inventory.getAvailableItems(
            AEApi.instance()
                .storage()
                .createItemList());
        IAEItemStack actual = contents.findPrecise(AEItemStack.create(palette.get(type)));
        return contents.size() == 1 && actual != null && actual.getStackSize() == amount;
    }

    private long steadyCellItems() {
        long total = 0;
        for (TestWorldReport.Cell cell : workload.cells) total += cell.steadyCount;
        return total;
    }

    private static long count(IItemList<IAEItemStack> items) {
        long total = 0;
        for (IAEItemStack item : items) total += item.getStackSize();
        return total;
    }

    private IItemList<IAEItemStack> available() {
        return monitor().getAvailableItems(
            AEApi.instance()
                .storage()
                .createItemList());
    }

    private IMEMonitor<IAEItemStack> monitor() {
        return ((IStorageGrid) grid().getCache(IStorageGrid.class)).getItemInventory();
    }

    private IGrid grid() {
        IGridNode node = node(controller);
        if (node == null) throw new IllegalStateException("Native controller node unavailable");
        return node.getGrid();
    }

    private static boolean powered(IGrid grid) {
        return ((IEnergyGrid) grid.getCache(IEnergyGrid.class)).isNetworkPowered();
    }

    private static IGridNode node(TileEntity tile) {
        return tile instanceof IGridHost ? ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN) : null;
    }

    private static void require(TestContext ctx, boolean condition, String message) {
        TestWorldFixtures.require(ctx, condition, message);
    }

    private static void requireSilent(TestContext ctx, boolean condition, String message, int index) {
        if (!condition) require(ctx, false, message + "; index=" + index);
    }

    private void next(TestContext ctx, int nextStage, String name) {
        if (phase != null) phase.endedTick = ctx.tick();
        stage = nextStage;
        cursor = 0;
        stageTick = ctx.tick();
        workload.phase = name;
        phase = new TestWorldReport.WorkloadPhase(name, ctx.tick());
        workload.phases.add(phase);
        ctx.beginWorkloadPhase(phase);
        ctx.event("workload_phase", name);
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (phase != null && phase.endedTick == 0) phase.endedTick = ctx.tick();
        if (workload != null && !"completed".equals(workload.phase))
            workload.phase = "stopped_or_failed:" + workload.phase;
        if (owner != null) owner.inventory.clearInventory(null, -1);
        // No disconnect, dequantize, power callback or observer survives: world persistence owns the retained blocks.
        clearReferences();
    }

    private void clearReferences() {
        nodes.clear();
        endpoints.clear();
        drives.clear();
        chests.clear();
        interfaces.clear();
        palette.clear();
        cells.clear();
        owner = null;
        terminal = null;
        controller = null;
        workload = null;
        phase = null;
    }
}
