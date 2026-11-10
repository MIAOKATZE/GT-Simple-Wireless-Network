package com.miaokatze.gtswn.common.testworld;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.baubles.TerminalBaubleLayout;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;

import baubles.api.BaubleType;
import baubles.api.IBauble;
import baubles.api.expanded.BaubleExpandedSlots;
import baubles.common.BaublesConfig;
import baubles.common.container.ContainerPlayerExpanded;
import baubles.common.container.InventoryBaubles;
import baubles.common.container.SlotBauble;
import cpw.mods.fml.common.Loader;

/** Exercises actual optional inventory, item callbacks and authoritative server transactions. */
final class TerminalBaublesScenario implements TestScenario {

    private FakePlayer player;
    private IInventory baubles;
    private ItemStack[] saved;

    public String id() {
        return "terminal.baubles";
    }

    public String description() {
        return "Real terminal Baubles slots, identity, full inventory atomicity and return credentials";
    }

    public void start(TestContext ctx) {
        if (!Loader.isModLoaded("Baubles")) {
            ctx.skip("Optional Baubles mod is absent");
            return;
        }
        player = TestWorldFixtures.player(ctx.world(), TestWorldFixtures.owner(id()));
        player.openContainer = player.inventoryContainer;
        baubles = TerminalBaubles.inventory(player);
        require(ctx, baubles != null, "Actual Baubles inventory available");
        ctx.event(
            "baubles-config",
            "Actual startup manualSlotSelection=" + BaublesConfig.manualSlotSelection
                + "; configured legacy slots="
                + java.util.Arrays.toString(BaublesConfig.overrideSlotTypes)
                + "; final slots="
                + java.util.Arrays.toString(BaubleExpandedSlots.getCurrentSlotAssignments()));
        java.util.List<SlotOverrideAudit.Observation> overrides = SlotOverrideAudit.snapshot();
        require(ctx, SlotOverrideAudit.dropped() == 0, "All startup slot override observations retained");
        if (BaublesConfig.manualSlotSelection)
            require(ctx, !overrides.isEmpty(), "Actual manual startup override was observed");
        for (int call = 0; call < overrides.size(); call++) {
            SlotOverrideAudit.Observation observed = overrides.get(call);
            ctx.event(
                "baubles-override",
                "call=" + call
                    + "; input="
                    + java.util.Arrays.toString(observed.before)
                    + "; output="
                    + java.util.Arrays.toString(observed.after));
            require(
                ctx,
                observed.after.length >= observed.before.length,
                "Override " + call + " retains every incoming slot");
            for (int index = 0; index < observed.before.length; index++) {
                require(
                    ctx,
                    java.util.Objects.equals(observed.before[index], observed.after[index]),
                    "Override " + call
                        + " preserves incoming slot "
                        + index
                        + ": "
                        + observed.before[index]
                        + " -> "
                        + observed.after[index]);
            }
        }
        saved = new ItemStack[baubles.getSizeInventory()];
        for (int i = 0; i < saved.length; i++) {
            saved[i] = baubles.getStackInSlot(i);
            baubles.setInventorySlotContents(i, null);
        }
        for (int category = 0; category < 4; category++) {
            String type = TerminalBaubles.TYPES[category];
            int[] slots = dedicated(type);
            require(ctx, slots.length >= (category == 3 ? 5 : 1), "Dedicated slot count for " + type);
        }
        GTSWNItemList[] items = { GTSWNItemList.Portable_Wireless_Network_Monitor, GTSWNItemList.Wireless_Energy_Tap,
            GTSWNItemList.Device_Info_Terminal, GTSWNItemList.ME_Network_Quantum_Terminal };
        verifySlotIsolation(ctx, items);
        for (int index = 0; index < 8; index++) {
            int category = Math.min(index, 3);
            ItemStack stack = items[category].get(1);
            player.inventory.setInventorySlotContents(0, stack);
            TerminalBaubles.service(player, PacketTerminalBaubleAction.EQUIP, 0);
            require(ctx, player.getHeldItem() == null, "Equip removes exact held instance " + index);
            require(
                ctx,
                TerminalBaubles.terminals(player)[index] == stack,
                "Ordered selection includes exact terminal " + index);
        }
        ItemStack monitor = TerminalBaubles.terminals(player)[0], device = TerminalBaubles.terminals(player)[2];
        require(ctx, MonitorBindings.id(monitor) != null, "Monitor equip authors binding and stable monitor identity");
        require(ctx, ItemDeviceInfoTerminal.readTerminalId(device) != null, "Device equip authors terminal identity");
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 0);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 2);
        require(
            ctx,
            player.getHeldItem() == null && TerminalBaubles.terminals(player)[0] == monitor
                && TerminalBaubles.terminals(player)[2] == device,
            "Monitor and device UI selection leave accessories equipped");
        verifyForcedTake(ctx);
        verifyDisplayLayout(ctx);
        ItemStack link = TerminalBaubles.terminals(player)[1];
        for (int i = 0; i < 36; i++) player.inventory.setInventorySlotContents(i, new ItemStack(Items.stick));
        ItemStack held = player.getHeldItem();
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 1);
        require(
            ctx,
            player.getHeldItem() == held && TerminalBaubles.terminals(player)[1] == link,
            "Full inventory refusal preserves both original stacks");
        player.inventory.setInventorySlotContents(9, null);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 1);
        require(
            ctx,
            player.getHeldItem() == link && player.inventory.getStackInSlot(9) == held,
            "Real borrow moves displaced item into free main slot");
        player.inventory.currentItem = 2;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, 1);
        require(
            ctx,
            player.inventory.getStackInSlot(0) == link && TerminalBaubles.terminals(player)[1] == null,
            "Changing selected hotbar slot cannot return another held stack");
        player.inventory.currentItem = 0;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, 1);
        require(
            ctx,
            player.getHeldItem() == null && TerminalBaubles.terminals(player)[1] == link,
            "Return restores original exact terminal once");
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, 1);
        require(ctx, TerminalBaubles.terminals(player)[1] == link, "Duplicate return cannot create another terminal");
        player.inventory.setInventorySlotContents(1, null);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.SELECT, 1);
        player.inventory.setInventorySlotContents(0, null);
        player.inventory.setInventorySlotContents(1, link);
        TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, 1);
        require(
            ctx,
            player.inventory.getStackInSlot(1) == link && TerminalBaubles.terminals(player)[1] == null,
            "Moved borrowed instance cannot be tracked or returned from another slot");
        player.inventory.currentItem = 1;
        TerminalBaubles.service(player, PacketTerminalBaubleAction.EQUIP, 1);
        require(
            ctx,
            player.getHeldItem() == null && TerminalBaubles.terminals(player)[1] == link,
            "Moved terminal can still be equipped normally as a fresh operation");
    }

    private void verifyForcedTake(TestContext ctx) {
        for (int selection = 0; selection < 4; selection++) {
            ItemStack terminal = TerminalBaubles.terminals(player)[selection];
            int origin = -1;
            for (int i = 0; i < baubles.getSizeInventory(); i++) if (baubles.getStackInSlot(i) == terminal) origin = i;
            ItemStack displaced = new ItemStack(Items.stick);
            player.inventory.setInventorySlotContents(0, displaced);
            TerminalBaubles.service(player, PacketTerminalBaubleAction.TAKE, selection);
            require(
                ctx,
                player.getHeldItem() == terminal && baubles.getStackInSlot(origin) == null
                    && player.inventory.getStackInSlot(1) == displaced,
                "Forced TAKE moves category " + selection + " with normal displacement");
            TerminalBaubles.service(player, PacketTerminalBaubleAction.RETURN, -1);
            require(
                ctx,
                player.getHeldItem() == null && baubles.getStackInSlot(origin) == terminal,
                "Forced TAKE category " + selection + " returns exact original instance to its original slot");
            player.inventory.setInventorySlotContents(1, null);
        }
        for (int i = 0; i < 36; i++) player.inventory.setInventorySlotContents(i, new ItemStack(Items.stick));
        for (int selection : new int[] { 0, 2 }) {
            ItemStack terminal = TerminalBaubles.terminals(player)[selection], held = player.getHeldItem();
            TerminalBaubles.service(player, PacketTerminalBaubleAction.TAKE, selection);
            require(
                ctx,
                player.getHeldItem() == held && TerminalBaubles.terminals(player)[selection] == terminal,
                "Full backpack forced TAKE leaves monitor/device unchanged: " + selection);
        }
        player.inventory.clearInventory(null, -1);
    }

    private void verifyDisplayLayout(TestContext ctx) {
        int savedColumns = BaublesConfig.maxColumns;
        boolean savedOld = BaublesConfig.useOldGuiRendering;
        InventoryBaubles actualInventory = (InventoryBaubles) baubles;
        Container previousHandler = actualInventory.getEventHandler();
        ItemStack[] before = new ItemStack[baubles.getSizeInventory()];
        for (int i = 0; i < before.length; i++) before[i] = baubles.getStackInSlot(i);
        try {
            for (boolean old : new boolean[] { false, true }) for (int maximum : new int[] { savedColumns, 1, 2, 4 }) {
                BaublesConfig.useOldGuiRendering = old;
                BaublesConfig.maxColumns = Math.max(1, maximum);
                ContainerPlayerExpanded container = new ContainerPlayerExpanded(player.inventory, false, player);
                try {
                    for (float scroll : new float[] { 0, 1 }) {
                        container.scrollTo(scroll);
                        int columns = container.getColumns();
                        int scrolled = TerminalBaubleLayout.scrollRows(
                            BaubleExpandedSlots.slotsCurrentlyUsed(),
                            columns,
                            scroll,
                            container.canScroll());
                        java.util.List<SlotBauble> terminals = new java.util.ArrayList<>();
                        boolean oldCoordinates = true, identities = true;
                        for (int i = 0; i < container.getBaubleSlotCount(); i++) {
                            SlotBauble slot = container.getBaubleSlot(i);
                            int index = slot.getSlotIndex();
                            identities &= slot.inventory == baubles && index == i
                                && slot.getStack() == before[index]
                                && container.inventorySlots.get(slot.slotNumber) == slot;
                            if (TerminalBaubleLayout.category(slot.getSlotType()) >= 0) {
                                if (slot.yDisplayPosition != -2000) terminals.add(slot);
                            } else {
                                int x = old ? 80 + 18 * (i / 4) : -18 - 18 * (i % columns);
                                int y = old ? 8 + 18 * (i % 4) : 12 + 18 * (i / columns - scrolled);
                                if (!old && container.canScroll() && (y < 12 || y > 144)) y = -2000;
                                oldCoordinates &= slot.xDisplayPosition == x && slot.yDisplayPosition == y;
                            }
                        }
                        terminals.sort(
                            java.util.Comparator.comparingInt((SlotBauble slot) -> slot.yDisplayPosition)
                                .thenComparingInt(slot -> slot.xDisplayPosition));
                        boolean ordered = true;
                        int previous = -1;
                        for (SlotBauble slot : terminals) {
                            int category = TerminalBaubleLayout.category(slot.getSlotType());
                            ordered &= category >= previous;
                            previous = category;
                        }
                        String mode = "old=" + old + ",cols=" + columns + ",scroll=" + scroll;
                        require(
                            ctx,
                            identities,
                            "Display sorting preserves real container/inventory identities: " + mode);
                        require(
                            ctx,
                            oldCoordinates,
                            "Display sorting leaves ordinary slot coordinates unchanged: " + mode);
                        require(ctx, ordered, "Real terminal cells read monitor/link/device/quantum: " + mode);
                    }
                } finally {
                    container.onContainerClosed(player);
                }
            }
        } finally {
            BaublesConfig.maxColumns = savedColumns;
            BaublesConfig.useOldGuiRendering = savedOld;
            actualInventory.setEventHandler(previousHandler);
        }
        for (int i = 0; i < before.length; i++) require(
            ctx,
            baubles.getStackInSlot(i) == before[i],
            "Display permutations leave actual accessory item at physical index " + i);
    }

    private void verifySlotIsolation(TestContext ctx, GTSWNItemList[] items) {
        String[] ordinaryTypes = { "universal", "ring", "amulet", "belt", "head", "body", "charm", "cape", "shield",
            "quiver", "gauntlet", "earring", "wings", "unknown", "external", "gtswn_future" };
        for (int target = 0; target < TerminalBaubles.TYPES.length; target++) {
            String slotType = TerminalBaubles.TYPES[target];
            for (String itemType : ordinaryTypes) require(
                ctx,
                !BaublesConfig.canTypeFitSlot(itemType, slotType),
                "Transformed Baubles rejects " + itemType + " in " + slotType);
            for (int source = 0; source < TerminalBaubles.TYPES.length; source++) {
                require(
                    ctx,
                    BaublesConfig.canTypeFitSlot(TerminalBaubles.TYPES[source], slotType) == (source == target),
                    "Transformed Baubles enforces category isolation in " + slotType);
                ItemStack candidate = items[source].get(1);
                for (int slot : dedicated(slotType)) {
                    require(
                        ctx,
                        baubles.isItemValidForSlot(slot, candidate) == (source == target),
                        "Actual inventory enforces terminal category " + source + " -> " + target);
                    require(
                        ctx,
                        new SlotBauble(baubles, slotType, slot, 0, 0).isItemValid(candidate) == (source == target),
                        "Actual GUI slot enforces terminal category " + source + " -> " + target);
                }
            }
            for (BaubleType legacyType : BaubleType.values()) {
                ItemStack ordinary = new ItemStack(new LegacyBauble(legacyType));
                for (int slot : dedicated(slotType)) {
                    require(
                        ctx,
                        !baubles.isItemValidForSlot(slot, ordinary),
                        "Actual inventory rejects legacy " + legacyType + " in " + slotType);
                    require(
                        ctx,
                        !new SlotBauble(baubles, slotType, slot, 0, 0).isItemValid(ordinary),
                        "Actual GUI slot rejects legacy " + legacyType + " in " + slotType);
                }
            }
        }
        require(
            ctx,
            BaublesConfig.canTypeFitSlot("universal", "ring") && BaublesConfig.canTypeFitSlot("ring", "ring")
                && !BaublesConfig.canTypeFitSlot("amulet", "ring"),
            "Ordinary Baubles matching rules remain unchanged");
        require(
            ctx,
            BaublesConfig.canTypeFitSlot("universal", "gtswn_future"),
            "Future gtswn-prefixed slots retain ordinary universal behavior");
        for (String type : TerminalBaubles.TYPES) {
            require(
                ctx,
                !BaublesConfig.canTypeFitSlot(type, "ring") && !BaublesConfig.canTypeFitSlot(type, "external"),
                "Terminal custom types keep ordinary nonmatching slot behavior");
            boolean configuredUniversal = java.util.Arrays
                .asList(BaublesConfig.getDisplayTypesWithUniversalOverrides(new String[] { type }))
                .contains("universal");
            require(
                ctx,
                BaublesConfig.canTypeFitSlot(type, "universal") == configuredUniversal,
                "Terminal custom types preserve configured universal-slot opt-in");
        }
    }

    /** A real legacy-only item exercises the enum-to-string compatibility route, including UNIVERSAL. */
    private static final class LegacyBauble extends Item implements IBauble {

        private final BaubleType type;

        LegacyBauble(BaubleType type) {
            this.type = type;
        }

        public BaubleType getBaubleType(ItemStack stack) {
            return type;
        }

        public void onWornTick(ItemStack stack, EntityLivingBase player) {}

        public void onEquipped(ItemStack stack, EntityLivingBase player) {}

        public void onUnequipped(ItemStack stack, EntityLivingBase player) {}

        public boolean canEquip(ItemStack stack, EntityLivingBase player) {
            return true;
        }

        public boolean canUnequip(ItemStack stack, EntityLivingBase player) {
            return true;
        }
    }

    private static int[] dedicated(String type) {
        return BaubleExpandedSlots.getIndexesOfAssignedSlotsOfType(type);
    }

    private static void require(TestContext ctx, boolean condition, String message) {
        TestWorldFixtures.require(ctx, condition, message);
    }

    public void tick(TestContext ctx) {
        ctx.pass(
            "Real 8-slot registration, initialization, nonmoving UI, full-pack refusal and exact-stack return passed");
    }

    public void cleanup(TestContext ctx) {
        if (player != null) {
            TerminalBaubles.clear(player);
            player.inventory.clearInventory(null, -1);
        }
        if (baubles != null && saved != null)
            for (int i = 0; i < saved.length; i++) baubles.setInventorySlotContents(i, saved[i]);
        player = null;
        baubles = null;
        saved = null;
    }
}
