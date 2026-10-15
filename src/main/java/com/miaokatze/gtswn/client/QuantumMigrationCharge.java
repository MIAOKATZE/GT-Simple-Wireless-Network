package com.miaokatze.gtswn.client;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.input.Mouse;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketQuantumMigration;

import appeng.tile.networking.TileController;

/** One physical press owns one migration attempt, including cancellation and rejection. */
final class QuantumMigrationCharge {

    private static long nextSession = System.currentTimeMillis();
    private static final QuantumMigrationGesture GESTURE = new QuantumMigrationGesture();
    private static EntityPlayer player;
    private static ItemStack stack;
    private static TileEntity target;
    private static int slot, dimension, x, y, z;
    private static int[] source;

    private QuantumMigrationCharge() {}

    /** Controllers are never forwarded to the ordinary incorporation packet. */
    static boolean request(Minecraft mc, int targetX, int targetY, int targetZ) {
        if (!(mc.theWorld.getTileEntity(targetX, targetY, targetZ) instanceof TileController)) return false;
        if (!GESTURE.press(++nextSession)) return true;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!ItemNetworkQuantumTerminal.isBound(held)) {
            GESTURE.cancel();
            return true;
        }
        TerminalRevealCharge.cancel();
        player = mc.thePlayer;
        stack = held;
        target = mc.theWorld.getTileEntity(targetX, targetY, targetZ);
        slot = player.inventory.currentItem;
        dimension = player.dimension;
        x = targetX;
        y = targetY;
        z = targetZ;
        source = ItemNetworkQuantumTerminal.getAnchor(stack);
        player.setItemInUse(stack, stack.getMaxItemUseDuration());
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), true);
        return true;
    }

    static void release() {
        cancel();
        GESTURE.release();
    }

    static void cancel() {
        clear(true);
    }

    static void receiveResult(long resultSession, int result) {
        if (stack != null && GESTURE.acknowledge(resultSession)) clear(false);
    }

    private static void clear(boolean notifyServer) {
        GESTURE.cancel();
        if (stack == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (notifyServer && mc.thePlayer == player && GESTURE.ticks() > 0) send(PacketQuantumMigration.CANCEL);
        if (player.getItemInUse() == stack) player.clearItemInUse();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        player = null;
        stack = null;
        target = null;
        source = null;
    }

    static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Mouse.isCreated() || !Mouse.isButtonDown(1)) {
            release();
            return;
        }
        if (stack == null) return;
        MovingObjectPosition hit = mc.objectMouseOver;
        if (mc.thePlayer != player || mc.theWorld == null
            || player.worldObj != mc.theWorld
            || !player.isEntityAlive()
            || mc.currentScreen != null
            || !QuantumIncorporationClientHandler.isAltDown()
            || QuantumIncorporationClientHandler.isShiftDown()
            || player.dimension != dimension
            || player.inventory.currentItem != slot
            || player.getHeldItem() != stack
            || player.getItemInUse() != stack
            || !Arrays.equals(source, ItemNetworkQuantumTerminal.getAnchor(stack))
            || hit == null
            || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || hit.blockX != x
            || hit.blockY != y
            || hit.blockZ != z
            || mc.theWorld.getTileEntity(x, y, z) != target) {
            cancel();
            return;
        }
        // Send BEGIN after vanilla updates the player's sneak state, and never restart it while held.
        QuantumMigrationGesture.Step step = GESTURE.tick();
        if (step == QuantumMigrationGesture.Step.BEGIN) send(PacketQuantumMigration.BEGIN);
        send(PacketQuantumMigration.HOLD);
        if (step == QuantumMigrationGesture.Step.COMPLETE) send(PacketQuantumMigration.COMPLETE);
        // Keep using and sending heartbeats after COMPLETE: a slow server still needs 600 real ticks.
    }

    static void draw(RenderGameOverlayEvent.Post event) {
        if (stack == null || event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        Minecraft mc = Minecraft.getMinecraft();
        String text = GESTURE.ticks() <= PacketQuantumMigration.REQUIRED_TICKS
            ? StatCollector.translateToLocalFormatted(
                "gtswn.hud.quantum.migration_charge",
                Math.min(GESTURE.ticks(), PacketQuantumMigration.REQUIRED_TICKS) * 100
                    / PacketQuantumMigration.REQUIRED_TICKS)
            : StatCollector.translateToLocal("gtswn.hud.quantum.migration_wait");
        mc.fontRenderer.drawStringWithShadow(
            text,
            (event.resolution.getScaledWidth() - mc.fontRenderer.getStringWidth(text)) / 2,
            event.resolution.getScaledHeight() / 2 + 24,
            0xff77ff);
    }

    private static void send(int action) {
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketQuantumMigration(action, GESTURE.session(), x, y, z));
    }
}
