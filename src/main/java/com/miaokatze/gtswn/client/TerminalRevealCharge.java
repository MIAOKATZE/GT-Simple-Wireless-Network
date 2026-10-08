package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Mouse;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestNodeReveal;
import com.miaokatze.gtswn.network.PacketRequestQuantumReveal;

/** Shared client-only charge gesture for both terminals. */
public final class TerminalRevealCharge {

    private static final int CHARGE_TICKS = 20;
    private static EntityPlayer chargingPlayer;
    private static ItemStack chargingStack;
    private static int chargingSlot;
    private static int heldTicks;

    private TerminalRevealCharge() {}

    public static boolean isPointingAtAir(Minecraft mc) {
        MovingObjectPosition hit = mc.objectMouseOver;
        return hit == null || hit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS;
    }

    public static void start(Minecraft mc) {
        cancel();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (mc.currentScreen != null || !isPointingAtAir(mc)
            || held == null
            || !(held.getItem() instanceof ItemNetworkQuantumTerminal || held.getItem() instanceof WirelessEnergyTap))
            return;
        chargingPlayer = mc.thePlayer;
        chargingStack = held;
        chargingSlot = mc.thePlayer.inventory.currentItem;
        chargingPlayer.setItemInUse(held, held.getMaxItemUseDuration());
        // The mouse press is consumed by Forge, so explicitly keep vanilla's use state alive.
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
    }

    public static void tick() {
        if (chargingStack == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != chargingPlayer || mc.theWorld == null
            || chargingPlayer.worldObj != mc.theWorld
            || mc.currentScreen != null
            || chargingPlayer.isDead
            || !QuantumIncorporationClientHandler.isAltDown()
            || !Mouse.isCreated()
            || !Mouse.isButtonDown(1)
            || chargingPlayer.inventory.currentItem != chargingSlot
            || chargingPlayer.getHeldItem() != chargingStack
            || chargingPlayer.getItemInUse() != chargingStack
            || !isPointingAtAir(mc)) {
            cancel();
            return;
        }
        if (++heldTicks >= CHARGE_TICKS) {
            if (chargingStack.getItem() instanceof ItemNetworkQuantumTerminal) {
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumReveal());
            } else {
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestNodeReveal());
            }
            cancel();
        }
    }

    public static void cancel() {
        if (chargingStack == null) return;
        if (chargingPlayer.getItemInUse() == chargingStack) chargingPlayer.clearItemInUse();
        Minecraft mc = Minecraft.getMinecraft();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        chargingPlayer = null;
        chargingStack = null;
        heldTicks = 0;
    }
}
