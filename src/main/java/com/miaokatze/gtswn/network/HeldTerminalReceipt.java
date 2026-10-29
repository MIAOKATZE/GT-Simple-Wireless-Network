package com.miaokatze.gtswn.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * Receipt-time physical context for a deferred held-item action. Captures references and the selected slot only:
 * no NBT, world lookup or authorization runs on Netty. Native permissions must still be checked at execution.
 */
final class HeldTerminalReceipt {

    private final EntityPlayerMP player;
    private final World world;
    private final InventoryPlayer inventory;
    private final ItemStack stack;
    private final int dimension, slot;

    HeldTerminalReceipt(EntityPlayerMP player) {
        this.player = player;
        world = player == null ? null : player.worldObj;
        dimension = player == null ? 0 : player.dimension;
        inventory = player == null ? null : player.inventory;
        slot = inventory == null ? -1 : inventory.currentItem;
        stack = inventory != null && inventory.mainInventory != null && slot >= 0 && slot < 9
            && slot < inventory.mainInventory.length ? inventory.mainInventory[slot] : null;
    }

    boolean matches(EntityPlayerMP current) {
        return current != null && current == player && world != null
            && current.worldObj == world
            && current.dimension == dimension
            && inventory != null
            && current.inventory == inventory
            && inventory.currentItem == slot
            && inventory.mainInventory != null
            && slot >= 0
            && slot < inventory.mainInventory.length
            && stack != null
            && stack.stackSize > 0
            && inventory.mainInventory[slot] == stack;
    }
}
