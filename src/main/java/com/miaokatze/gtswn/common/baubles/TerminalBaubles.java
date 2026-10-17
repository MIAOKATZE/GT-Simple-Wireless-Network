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
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
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
    private static boolean initialized;

    private TerminalBaubles() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        if (Loader.isModLoaded("Baubles")) Integration.register();
        FMLCommonHandler.instance()
            .bus()
            .register(new Events());
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
        return player != null && Loader.isModLoaded("Baubles") ? Integration.inventory(player) : null;
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
        if (player != null && action >= 0
            && action <= 2
            && selection >= -1
            && selection < SIZE
            && (action != PacketTerminalBaubleAction.SELECT || selection >= 0))
            PENDING.putIfAbsent(player.getUniqueID(), new Request(player, action, selection));
    }

    public static void onEquipped(ItemStack stack, EntityLivingBase entity) {
        if (!(entity instanceof EntityPlayerMP player) || category(stack) < 0) return;
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
        if (action >= 0 && action <= 2
            && selection >= -1
            && selection < SIZE
            && (action != PacketTerminalBaubleAction.SELECT || selection >= 0))
            handle(new Request(player, action, selection));
    }

    public static void clear() {
        PENDING.clear();
        SESSIONS.clear();
    }

    public static void clear(EntityPlayer player) {
        PENDING.remove(player.getUniqueID());
        SESSIONS.remove(player.getUniqueID());
    }

    private static void handle(Request request) {
        EntityPlayerMP player = request.player;
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
        initialize(stack, player);
        if (category == 0 || category == 2) {
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
            PENDING.remove(event.player.getUniqueID());
            SESSIONS.remove(event.player.getUniqueID());
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
