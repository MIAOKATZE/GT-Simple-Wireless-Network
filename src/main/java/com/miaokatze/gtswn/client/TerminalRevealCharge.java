package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;

import org.lwjgl.input.Mouse;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestNodeReveal;
import com.miaokatze.gtswn.network.PacketRequestQuantumReveal;
import com.miaokatze.gtswn.network.PacketTerminalGesture;

/** 三种终端共享的 Alt+Shift+右击空气蓄力手势。 */
public final class TerminalRevealCharge {

    private static final TerminalChargeProgress PROGRESS = new TerminalChargeProgress();
    private static EntityPlayer chargingPlayer;
    private static ItemStack chargingStack;
    private static int chargingSlot;

    private TerminalRevealCharge() {}

    public static boolean isPointingAtAir(Minecraft mc) {
        MovingObjectPosition hit = mc.objectMouseOver;
        return hit == null || hit.typeOfHit == MovingObjectPosition.MovingObjectType.MISS;
    }

    public static void start(Minecraft mc) {
        cancel();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (mc.currentScreen != null || !isPointingAtAir(mc)
            || mc.thePlayer == null
            || !QuantumIncorporationClientHandler.isShiftDown()
            || !QuantumIncorporationClientHandler.isAltDown()
            || held == null
            || !(held.getItem() instanceof ItemNetworkQuantumTerminal || held.getItem() instanceof WirelessEnergyTap
                || held.getItem() instanceof ItemDeviceInfoTerminal))
            return;
        chargingPlayer = mc.thePlayer;
        chargingStack = held;
        chargingSlot = mc.thePlayer.inventory.currentItem;
        PROGRESS.start();
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
            || !QuantumIncorporationClientHandler.isShiftDown()
            || !Mouse.isCreated()
            || !Mouse.isButtonDown(1)
            || chargingPlayer.inventory.currentItem != chargingSlot
            || chargingPlayer.getHeldItem() != chargingStack
            || chargingPlayer.getItemInUse() != chargingStack
            || !isPointingAtAir(mc)) {
            cancel();
            return;
        }
        // END tick在玩家更新潜行包之后发开始意图，避免同帧Shift+右键被服务端拒绝。
        TerminalChargeProgress.Step step = PROGRESS.tick(true);
        if (step == TerminalChargeProgress.Step.BEGIN) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalGesture(PacketTerminalGesture.BEGIN_REVEAL));
            return;
        }
        if (step == TerminalChargeProgress.Step.COMPLETE) {
            if (chargingStack.getItem() instanceof ItemNetworkQuantumTerminal) {
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumReveal());
            } else if (chargingStack.getItem() instanceof WirelessEnergyTap) {
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestNodeReveal());
            } else {
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalGesture(PacketTerminalGesture.SCAN_DEVICES));
            }
            clear(false);
        }
    }

    public static void cancel() {
        clear(true);
    }

    private static void clear(boolean notifyServer) {
        if (chargingStack == null) return;
        if (notifyServer && Minecraft.getMinecraft().thePlayer == chargingPlayer)
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalGesture(PacketTerminalGesture.CANCEL_REVEAL));
        if (chargingPlayer.getItemInUse() == chargingStack) chargingPlayer.clearItemInUse();
        Minecraft mc = Minecraft.getMinecraft();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        chargingPlayer = null;
        chargingStack = null;
        PROGRESS.cancel();
    }
}
