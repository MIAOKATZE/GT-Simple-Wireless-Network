package com.miaokatze.gtswn.common.gui;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Arrays;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;

import gregtech.common.misc.WirelessNetworkManager;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;

/** Supply clicks use the ordinary window transaction protocol, with server-only integer mutations. */
public class ContainerTerminal extends Container {

    public final boolean quantum;
    public final TerminalSupplyStore.Kind kind;
    private final EntityPlayer owner;
    private final ItemStack terminal;
    private final int heldIndex;
    private final int[] values = new int[80];
    private final int[] sent = new int[80];
    private long snapshotTick = Long.MIN_VALUE;
    public int supplyCount;

    public ContainerTerminal(EntityPlayer player, boolean quantum) {
        this.owner = player;
        this.quantum = quantum;
        kind = quantum ? TerminalSupplyStore.Kind.ANCHOR : TerminalSupplyStore.Kind.TUBE;
        terminal = player.getHeldItem();
        heldIndex = player.inventory.currentItem;
        Arrays.fill(sent, -1);
        addSlotToContainer(new Slot(new InventoryBasic("supply", false, 1), 0, 263, 161) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return false;
            }

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return false;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++)
                addPlayerSlot(column + row * 9 + 9, 10 + column * 18, 150 + row * 18);
        }
        for (int column = 0; column < 9; column++) addPlayerSlot(column, 10 + column * 18, 208);
    }

    private void addPlayerSlot(int index, int x, int y) {
        addSlotToContainer(new Slot(owner.inventory, index, x, y) {

            @Override
            public boolean canTakeStack(EntityPlayer player) {
                return index != heldIndex;
            }

            @Override
            public boolean isItemValid(ItemStack stack) {
                return index != heldIndex;
            }
        });
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        ItemStack held = player.getHeldItem();
        return player == owner && player.isEntityAlive()
            && held != null
            && player.inventory.currentItem == heldIndex
            && (player.worldObj.isRemote || held == terminal)
            && (quantum ? held.getItem() instanceof ItemNetworkQuantumTerminal
                : held.getItem() instanceof WirelessEnergyTap);
    }

    @Override
    public ItemStack slotClick(int slot, int button, int mode, EntityPlayer player) {
        if (!canInteractWith(player) || (mode == 2 && button == heldIndex)) return null;
        if (slot > 0 && slot < inventorySlots.size() && getSlot(slot).getSlotIndex() == heldIndex) return null;
        if (slot != 0) return super.slotClick(slot, button, mode, player);
        if (player.worldObj.isRemote || (mode != 0 && mode != 1) || (button != 0 && button != 1)) return null;
        int count = TerminalSupplyStore.count(player, kind);
        ItemStack cursor = player.inventory.getItemStack();
        if (mode == 0 && cursor != null) {
            if (!kind.matches(cursor)) return null;
            int moved = Math
                .min(Math.min(button == 1 ? 1 : 64, cursor.stackSize), TerminalSupplyStore.CAPACITY - count);
            if (moved <= 0) return null;
            cursor.stackSize -= moved;
            if (cursor.stackSize == 0) player.inventory.setItemStack(null);
            TerminalSupplyStore.set(player, kind, count + moved);
        } else if (count > 0 && cursor == null) {
            ItemStack withdrawn = kind.reference();
            if (withdrawn == null) return null;
            withdrawn.stackSize = Math.min(count, Math.min(64, withdrawn.getMaxStackSize()));
            if (mode == 0) {
                if (button == 1) withdrawn.stackSize = 1;
                player.inventory.setItemStack(withdrawn);
                TerminalSupplyStore.set(player, kind, count - withdrawn.stackSize);
            } else {
                TerminalSupplyStore.set(
                    player,
                    kind,
                    SupplyInventoryTransfer.withdraw(player.inventory, kind.reference(), count, heldIndex));
            }
        }
        player.inventory.markDirty();
        detectAndSendChanges();
        return null;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slot) {
        if (player.worldObj.isRemote || !canInteractWith(player) || slot <= 0 || slot >= inventorySlots.size())
            return null;
        Slot source = getSlot(slot);
        ItemStack stack = source.getStack();
        if (!source.canTakeStack(player) || !kind.matches(stack)) return null;
        int count = TerminalSupplyStore.count(player, kind);
        TerminalSupplyStore.set(
            player,
            kind,
            SupplyInventoryTransfer
                .deposit(player.inventory, source.getSlotIndex(), kind.reference(), count, heldIndex));
        return null;
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (owner.worldObj.isRemote) return;
        values[0] = TerminalSupplyStore.count(owner, kind);
        long tick = owner.worldObj.getTotalWorldTime();
        if (!quantum && (snapshotTick == Long.MIN_VALUE || tick - snapshotTick >= 20 || tick < snapshotTick)) {
            snapshotTick = tick;
            String leader = SpaceProjectManager.getLeader(owner.getUniqueID())
                .toString();
            BigInteger eu = WirelessNetworkManager.getUserEU(owner.getUniqueID());
            String balance = eu == null ? "0"
                : eu.bitLength() > 60 ? new BigDecimal(eu).round(new MathContext(4))
                    .toString() : eu.toString();
            for (int i = 0; i < 36; i++) values[1 + i] = leader.charAt(i);
            for (int i = 0; i < 40; i++) values[37 + i] = i < balance.length() ? balance.charAt(i) : 0;
        }
        for (int i = 0; i < values.length; i++) {
            if (sent[i] == values[i]) continue;
            for (Object listener : crafters) ((ICrafting) listener).sendProgressBarUpdate(this, i, values[i]);
            sent[i] = values[i];
        }
    }

    @Override
    public void addCraftingToCrafters(ICrafting listener) {
        super.addCraftingToCrafters(listener);
        Arrays.fill(sent, -1);
        detectAndSendChanges();
    }

    @Override
    public void updateProgressBar(int id, int value) {
        if (id < 0 || id >= values.length) return;
        values[id] = value;
        if (id == 0) supplyCount = Math.max(0, Math.min(TerminalSupplyStore.CAPACITY, value));
    }

    public String team() {
        return text(1, 36);
    }

    public String balance() {
        return text(37, 40);
    }

    private String text(int start, int length) {
        StringBuilder text = new StringBuilder();
        for (int i = start; i < start + length && values[i] != 0; i++) text.append((char) values[i]);
        return text.toString();
    }
}
