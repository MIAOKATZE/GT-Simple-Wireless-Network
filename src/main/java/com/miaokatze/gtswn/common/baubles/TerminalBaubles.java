package com.miaokatze.gtswn.common.baubles;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.gui.GTSWNGuiHandler;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.util.PlayerRequestQueue;
import com.miaokatze.gtswn.network.DeviceTerminalVisualActionQueue;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.NodeRevealRequestQueue;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;
import com.miaokatze.gtswn.network.PacketRequestQuantumReveal;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;
import com.miaokatze.gtswn.network.PacketTerminalBaubleState;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Server-authoritative terminal selection and optional Baubles integration. */
public final class TerminalBaubles {

    public static final int SIZE = 8;
    public static final String[] TYPES = { "gtswn_monitor", "gtswn_link", "gtswn_device", "gtswn_quantum" };
    private static final Map<UUID, Request> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, WheelReceipt> WHEEL_RECEIPTS = new HashMap<>();
    private static boolean initialized;
    private static boolean baublesAvailable;

    private TerminalBaubles() {}

    public static void init() {
        if (initialized) return;
        baublesAvailable = Loader.isModLoaded("Baubles");
        initialized = true;
        if (baublesAvailable) Integration.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new Events());
    }

    /** Optional integrations are resolved during mod initialization, before any player inventory ticks. */
    static boolean isBaublesAvailable() {
        return baublesAvailable;
    }

    public static int category(ItemStack stack) {
        if (stack == null || stack.stackSize != 1) return -1;
        if (stack.getItem() instanceof PortableWirelessNetworkMonitor) return 0;
        if (stack.getItem() instanceof WirelessEnergyTap) return 1;
        if (stack.getItem() instanceof ItemDeviceInfoTerminal) return 2;
        if (stack.getItem() instanceof ItemNetworkQuantumTerminal) return 3;
        return -1;
    }

    public static void addSlotTooltip(java.util.List<String> list, int category) {
        String[] names = { "monitor", "link", "device", "quantum" };
        String slot = net.minecraft.util.StatCollector.translateToLocal("gtswn.baubles.slot." + names[category]);
        list.add(net.minecraft.util.StatCollector.translateToLocalFormatted("gtswn.baubles.tooltip", slot));
    }

    public static boolean isTerminal(ItemStack stack) {
        return category(stack) >= 0;
    }

    public static IInventory inventory(EntityPlayer player) {
        return player != null && baublesAvailable ? Integration.inventory(player) : null;
    }

    /** Server-side in-place NBT changes need Baubles' native slot packet, not only markDirty. */
    public static void syncChangedStack(EntityPlayerMP player, ItemStack stack) {
        IInventory inventory = inventory(player);
        if (inventory != null && stack != null) Integration.syncChangedStack(inventory, stack);
    }

    private static int[] selections(IInventory inventory) {
        int[] categories = new int[inventory.getSizeInventory()];
        for (int i = 0; i < categories.length; i++) categories[i] = category(inventory.getStackInSlot(i));
        return TerminalInventoryRules.select(categories);
    }

    public static ItemStack[] terminals(EntityPlayer player) {
        ItemStack[] result = new ItemStack[SIZE];
        IInventory inventory = inventory(player);
        if (inventory == null) return result;
        int[] selected = selections(inventory);
        for (int i = 0; i < SIZE; i++) if (selected[i] >= 0) result[i] = inventory.getStackInSlot(selected[i]);
        return result;
    }

    public static void enqueue(EntityPlayerMP player, int action, int selection) {
        if (player == null || !PacketTerminalBaubleAction.isValid(action, selection)
            || action == PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE) return;
        PENDING.compute(player.getUniqueID(), (id, previous) -> {
            boolean cancel = action == PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE;
            if (previous != null && previous.player == player && !cancel) return previous;
            return new Request(player, action, selection);
        });
    }

    public static void onEquipped(ItemStack stack, EntityLivingBase entity) {
        if (!(entity instanceof EntityPlayerMP player) || category(stack) < 0) return;
        TerminalAcquisitionHint.observe(stack, entity);
        initialize(stack, player);
        NBTTagCompound root = player.getEntityData();
        NBTTagCompound persisted = root.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        String key = "GTSWNTerminalHint" + category(stack);
        if (!persisted.getBoolean(key)) {
            persisted.setBoolean(key, true);
            root.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
            send(player, PacketTerminalBaubleState.EQUIPPED_HINT, category(stack), -1, null);
        }
    }

    private static void initialize(ItemStack stack, EntityPlayerMP player) {
        if (category(stack) == 0) {
            ((PortableWirelessNetworkMonitor) stack.getItem()).ensureBaubleBinding(stack, player);
            MonitorBindings.ensureId(stack);
        } else if (category(stack) == 2 && ItemDeviceInfoTerminal.readTerminalId(stack) == null) {
            if (stack.stackTagCompound != null) stack.stackTagCompound.removeTag(ItemDeviceInfoTerminal.NBT_UUID);
            ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        }
    }

    /** Main server thread only; packet handlers must use enqueue instead. */
    public static void service(EntityPlayerMP player, int action, int selection) {
        if (player != null && PacketTerminalBaubleAction.isValid(action, selection))
            handle(new Request(player, action, selection));
    }

    public static void clear() {
        PENDING.clear();
        SESSIONS.clear();
        WHEEL_RECEIPTS.clear();
    }

    public static void clear(EntityPlayer player) {
        PENDING.remove(player.getUniqueID());
        SESSIONS.remove(player.getUniqueID());
        clearWheelReceipt(player);
    }

    private static void clearWheelReceipt(EntityPlayer player) {
        UUID id = player.getUniqueID();
        WheelReceipt receipt = WHEEL_RECEIPTS.get(id);
        if (receipt != null && receipt.player == player) WHEEL_RECEIPTS.remove(id, receipt);
    }

    private static void handle(Request request) {
        EntityPlayerMP player = request.player;
        if (request.action == PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE) return;
        if (request.action == PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE) {
            clearWheelReceipt(player);
            return;
        }
        if (player.isDead || player.playerNetServerHandler == null
            || player.openContainer != player.inventoryContainer
            || player.inventory.getItemStack() != null) return;
        IInventory baubles = inventory(player);
        if (baubles == null) return;
        TerminalInventoryRules.Inventory<ItemStack> main = adapt(player.inventory), accessory = adapt(baubles);
        Session session = SESSIONS.get(player.getUniqueID());
        if (request.action == PacketTerminalBaubleAction.RETURN) {
            if (session != null && session.valid(player)
                && baubles.isItemValidForSlot(session.borrow.origin, session.borrow.stack)
                && session.borrow.restore(main, player.inventory.currentItem, accessory)) {
                SESSIONS.remove(player.getUniqueID());
                sync(player, baubles);
            } else if (session != null && !session.valid(player)) SESSIONS.remove(player.getUniqueID());
            Session remaining = SESSIONS.get(player.getUniqueID());
            send(
                player,
                PacketTerminalBaubleState.SESSION,
                Math.max(0, request.selection),
                remaining == null ? -1 : remaining.borrow.held,
                remaining == null ? null : remaining.borrow.stack);
            return;
        }
        if (request.action == PacketTerminalBaubleAction.EQUIP) {
            ItemStack held = player.getHeldItem();
            int category = category(held);
            if (category < 0) return;
            int slot = Integration.emptyDedicatedSlot(baubles, held, category);
            if (slot < 0) return;
            player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
            baubles.setInventorySlotContents(slot, held);
            SESSIONS.remove(player.getUniqueID());
            sync(player, baubles);
            send(player, PacketTerminalBaubleState.SESSION, Math.max(0, request.selection), -1, null);
            return;
        }
        int slot = selections(baubles)[request.selection];
        if (slot < 0) return;
        ItemStack stack = baubles.getStackInSlot(slot);
        int category = category(stack);
        if (request.action >= PacketTerminalBaubleAction.WHEEL_OPEN) {
            handleWheel(player, request, baubles, slot, stack, category, session, main, accessory);
            return;
        }
        initialize(stack, player);
        if (request.action == PacketTerminalBaubleAction.SELECT && (category == 0 || category == 2)) {
            sync(player, baubles);
            send(
                player,
                category == 0 ? PacketTerminalBaubleState.OPEN_MONITOR : PacketTerminalBaubleState.OPEN_DEVICE,
                request.selection,
                -1,
                stack);
            return;
        }
        // Viewing a monitor/device is also allowed while another terminal is borrowed.
        if (session != null && session.valid(player)) return;
        if (!Integration.canUnequip(stack, player)) return;
        TerminalInventoryRules.Borrow<ItemStack> borrow = TerminalInventoryRules
            .take(main, player.inventory.currentItem, accessory, slot);
        if (borrow == null) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.baubles.inventory.full"));
            return;
        }
        SESSIONS.put(player.getUniqueID(), new Session(player, borrow));
        sync(player, baubles);
        send(player, PacketTerminalBaubleState.SESSION, request.selection, borrow.held, borrow.stack);
    }

    /** Fixed category actions; quantum's inner node-map entry stays on the client. */
    static boolean wheelActionAllowed(int category, int action) {
        return category >= 0 && category <= 3
            && action >= PacketTerminalBaubleAction.WHEEL_OPEN
            && action <= PacketTerminalBaubleAction.WHEEL_INNER
            && !(category == 3 && action == PacketTerminalBaubleAction.WHEEL_INNER);
    }

    static int immediateUtilityAction(int category) {
        return category == 1 ? PacketTerminalBaubleAction.WHEEL_INNER
            : category == 2 || category == 3 ? PacketTerminalBaubleAction.WHEEL_OUTER_SECOND : -1;
    }

    private static void handleWheel(EntityPlayerMP player, Request request, IInventory baubles, int slot,
        ItemStack stack, int category, Session session, TerminalInventoryRules.Inventory<ItemStack> main,
        TerminalInventoryRules.Inventory<ItemStack> accessory) {
        if (category != Math.min(request.selection, 3)) return;
        if (!wheelActionAllowed(category, request.action)) return;
        initialize(stack, player);
        int action = request.action;
        if (action == PacketTerminalBaubleAction.WHEEL_OPEN && (category == 0 || category == 2)) {
            syncChangedStack(player, stack);
            send(
                player,
                category == 0 ? PacketTerminalBaubleState.OPEN_MONITOR : PacketTerminalBaubleState.OPEN_DEVICE,
                request.selection,
                -1,
                stack);
            return;
        }
        if (category == 0 && (action == PacketTerminalBaubleAction.WHEEL_OUTER_SECOND
            || action == PacketTerminalBaubleAction.WHEEL_INNER)) {
            PortableWirelessNetworkMonitor monitor = (PortableWirelessNetworkMonitor) stack.getItem();
            if (!monitor.canUseBaubleHud(stack, player)) return;
            if (action == PacketTerminalBaubleAction.WHEEL_OUTER_SECOND) monitor.toggleBaubleHud(stack, player);
            syncChangedStack(player, stack);
            if (action == PacketTerminalBaubleAction.WHEEL_INNER)
                send(player, PacketTerminalBaubleState.OPEN_HUD_SETTINGS, request.selection, -1, stack);
            return;
        }
        if (action == immediateUtilityAction(category)) {
            if (!PlayerRequestQueue.isOnline(player)) return;
            if (category == 2) ItemDeviceInfoTerminal.startChargedScan(player, stack);
            else {
                WHEEL_RECEIPTS.put(player.getUniqueID(), new WheelReceipt(player, request.selection, slot, stack));
                if (category == 1) NodeRevealRequestQueue.enqueueWheel(player, stack);
                else PacketRequestQuantumReveal.enqueueWheel(player, stack);
            }
            return;
        }
        if (category == 2 && action == PacketTerminalBaubleAction.WHEEL_INNER) {
            DeviceTerminalVisualActionQueue
                .enqueue(player, PacketDeviceTerminalVisualAction.toggle(ItemDeviceInfoTerminal.readTerminalId(stack)));
            return;
        }
        // All remaining actions borrow the exact original stack. Mode changes happen only after take succeeds.
        if (session != null && session.valid(player) || !Integration.canUnequip(stack, player)) return;
        TerminalInventoryRules.Borrow<ItemStack> borrow = TerminalInventoryRules
            .take(main, player.inventory.currentItem, accessory, slot);
        if (borrow == null) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.baubles.inventory.full"));
            return;
        }
        if (category == 1 && (action == PacketTerminalBaubleAction.WHEEL_OUTER_FIRST
            || action == PacketTerminalBaubleAction.WHEEL_OUTER_SECOND)) {
            if (stack.stackTagCompound == null) stack.stackTagCompound = new NBTTagCompound();
            boolean output = action == PacketTerminalBaubleAction.WHEEL_OUTER_SECOND;
            stack.stackTagCompound.setBoolean("OutputMode", output);
            stack.setItemDamage(output ? 1 : 0);
        }
        SESSIONS.put(player.getUniqueID(), new Session(player, borrow));
        WHEEL_RECEIPTS.remove(player.getUniqueID());
        sync(player, baubles);
        send(player, PacketTerminalBaubleState.SESSION, request.selection, borrow.held, borrow.stack);
        if (action == PacketTerminalBaubleAction.WHEEL_OPEN) GTSWNGuiHandler.openTerminal(player, category == 3);
    }

    /** Consumed on the server thread by the original reveal queues, after their ordinary queue budget. */
    public static boolean consumeWheelReceipt(EntityPlayerMP player, ItemStack stack) {
        WheelReceipt receipt = WHEEL_RECEIPTS.get(player.getUniqueID());
        if (receipt == null || receipt.player != player || receipt.stack != stack) return false;
        WHEEL_RECEIPTS.remove(player.getUniqueID(), receipt);
        Request pending = PENDING.get(player.getUniqueID());
        return !(pending != null && pending.player == player
            && pending.action == PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE) && receipt.valid()
            && validWheelReceiptAge(tickNow(), receipt.started);
    }

    static boolean validWheelReceiptAge(long now, long started) {
        return now >= started && now - started <= 60;
    }

    private static long tickNow() {
        return MinecraftServer.getServer()
            .getTickCounter();
    }

    private static final class WheelReceipt {

        final EntityPlayerMP player;
        final ItemStack stack;
        final World world;
        final int selection, slot, dimension;
        final long started;

        WheelReceipt(EntityPlayerMP player, int selection, int slot, ItemStack stack) {
            this.player = player;
            this.selection = selection;
            this.slot = slot;
            this.stack = stack;
            world = player.worldObj;
            dimension = player.dimension;
            started = tickNow();
        }

        boolean valid() {
            if (!PlayerRequestQueue.isOnline(player) || player.worldObj != world
                || player.dimension != dimension
                || player.openContainer != player.inventoryContainer
                || player.inventory.getItemStack() != null) return false;
            IInventory baubles = inventory(player);
            return baubles != null && slot < baubles.getSizeInventory()
                && baubles.getStackInSlot(slot) == stack
                && selections(baubles)[selection] == slot;
        }
    }

    private static void sync(EntityPlayerMP player, IInventory baubles) {
        baubles.markDirty();
        player.inventory.markDirty();
        player.inventoryContainer.detectAndSendChanges();
    }

    private static void send(EntityPlayerMP player, int action, int selection, int held, ItemStack stack) {
        // Fake players and disconnected actors can own inventories without an open FML transport.
        if (player.playerNetServerHandler == null || !player.playerNetServerHandler.netManager.isChannelOpen()) return;
        GTSWNPacketHandler.NETWORK.sendTo(new PacketTerminalBaubleState(action, selection, held, stack), player);
    }

    private static TerminalInventoryRules.Inventory<ItemStack> adapt(final IInventory inventory) {
        return new TerminalInventoryRules.Inventory<>() {

            public int size() {
                return inventory instanceof net.minecraft.entity.player.InventoryPlayer ? 36
                    : inventory.getSizeInventory();
            }

            public ItemStack get(int slot) {
                return inventory.getStackInSlot(slot);
            }

            public void set(int slot, ItemStack stack) {
                inventory.setInventorySlotContents(slot, stack);
            }
        };
    }

    private static final class Request {

        final EntityPlayerMP player;
        final int action, selection;

        Request(EntityPlayerMP player, int action, int selection) {
            this.player = player;
            this.action = action;
            this.selection = selection;
        }
    }

    private static final class Session {

        final EntityPlayerMP player;
        final int dimension;
        final TerminalInventoryRules.Borrow<ItemStack> borrow;

        Session(EntityPlayerMP player, TerminalInventoryRules.Borrow<ItemStack> borrow) {
            this.player = player;
            dimension = player.dimension;
            this.borrow = borrow;
        }

        boolean valid(EntityPlayerMP current) {
            return current == player && !player.isDead
                && dimension == player.dimension
                && borrow.present(adapt(player.inventory));
        }
    }

    public static final class Events {

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            WHEEL_RECEIPTS.values()
                .removeIf(receipt -> !receipt.valid() || tickNow() - receipt.started > 60);
            SESSIONS.entrySet()
                .removeIf(entry -> {
                    Session session = entry.getValue();
                    if (session.valid(session.player)) return false;
                    send(session.player, PacketTerminalBaubleState.SESSION, 0, -1, null);
                    return true;
                });
            for (Map.Entry<UUID, Request> entry : PENDING.entrySet())
                if (PENDING.remove(entry.getKey(), entry.getValue())) handle(entry.getValue());
        }

        @SubscribeEvent
        public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
            UUID id = event.player.getUniqueID();
            PENDING.computeIfPresent(id, (key, request) -> request.player == event.player ? null : request);
            SESSIONS.computeIfPresent(id, (key, session) -> session.player == event.player ? null : session);
            WHEEL_RECEIPTS.computeIfPresent(id, (key, receipt) -> receipt.player == event.player ? null : receipt);
        }
    }

    /** Loaded only when the optional mod is installed. */
    private static final class Integration {

        static void register() {
            for (int i = 0; i < TYPES.length; i++) {
                baubles.api.expanded.BaubleExpandedSlots.tryRegisterType(TYPES[i]);
                baubles.api.expanded.BaubleExpandedSlots.tryAssignSlotsUpToMinimum(TYPES[i], i == 3 ? 5 : 1);
            }
        }

        static IInventory inventory(EntityPlayer player) {
            return baubles.api.BaublesApi.getBaubles(player);
        }

        static boolean syncChangedStack(IInventory inventory, ItemStack stack) {
            if (!(inventory instanceof baubles.common.container.InventoryBaubles baubles) || stack == null)
                return false;
            for (int slot = 0; slot < baubles.getSizeInventory(); slot++) if (baubles.getStackInSlot(slot) == stack) {
                baubles.markDirty();
                baubles.syncSlotToClients(slot);
                return true;
            }
            return false;
        }

        static int emptyDedicatedSlot(IInventory inventory, ItemStack stack, int category) {
            for (int slot : baubles.api.expanded.BaubleExpandedSlots.getIndexesOfAssignedSlotsOfType(TYPES[category]))
                if (slot < inventory.getSizeInventory() && inventory.getStackInSlot(slot) == null
                    && inventory.isItemValidForSlot(slot, stack)) return slot;
            return -1;
        }

        static boolean canUnequip(ItemStack stack, EntityPlayer player) {
            return ((baubles.api.IBauble) stack.getItem()).canUnequip(stack, player);
        }
    }
}
