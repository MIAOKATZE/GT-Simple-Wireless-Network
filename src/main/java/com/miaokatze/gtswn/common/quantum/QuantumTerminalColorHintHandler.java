package com.miaokatze.gtswn.common.quantum;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.util.InventoryHintReminder;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Reminds players carrying several identically colored terminals about the color picker. */
public final class QuantumTerminalColorHintHandler {

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent event) {
        EntityPlayer player = event.player;
        if (event.phase != TickEvent.Phase.END || player.worldObj.isRemote || player.ticksExisted % 20 != 0) return;
        int firstColor = -1;
        int terminals = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemNetworkQuantumTerminal))
                continue;
            int color = QuantumNetworkColor.resolve(stack);
            if (firstColor == -1) firstColor = color;
            else if (color != firstColor) return;
            terminals += stack.stackSize;
        }
        if (terminals < 2) return;
        InventoryHintReminder.remind(
            player,
            "GTSWNQuantumColorHintCount",
            "GTSWNQuantumColorHeldTicks",
            "gtswn.chat.quantum.color_hint");
    }
}
