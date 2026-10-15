package com.miaokatze.gtswn.client;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketQuantumIncorporation;
import com.miaokatze.gtswn.network.PacketRequestQuantumInfo;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-only Alt gestures. No world scan: only the pointed block is tested. */
public final class QuantumIncorporationClientHandler {

    public static void receiveMigrationResult(long session, int result) {
        QuantumMigrationCharge.receiveResult(session, result);
    }

    public static boolean isAltDown() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU));
    }

    public static boolean isShiftDown() {
        return Keyboard.isCreated()
            && (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT));
    }

    private static boolean isHoldingTerminal(Minecraft mc) {
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        return mc.currentScreen == null && held != null && held.getItem() instanceof ItemNetworkQuantumTerminal;
    }

    public static void requestIncorporation(int x, int y, int z, boolean remove) {
        Minecraft mc = Minecraft.getMinecraft();
        // Client machine proxies, bounds and synchronization can lag. Eligibility and toggle state
        // are checked on the server, including removal while the anchor or machine is offline.
        if (mc.theWorld != null && isHoldingTerminal(mc)
            && TerminalGestureRules.canIncorporate(isAltDown(), isShiftDown(), true)) {
            if (QuantumMigrationCharge.request(mc, x, y, z)) return;
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketQuantumIncorporation(x, y, z, false));
        }
    }

    private static boolean requestEmptyHandInfo(Minecraft mc, int x, int y, int z) {
        if (mc.thePlayer == null || mc.currentScreen != null
            || mc.thePlayer.getHeldItem() != null
            || !isAltDown()
            || !QuantumIncorporationClientState.registered(mc.theWorld, x, y, z)) return false;
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumInfo(x, y, z));
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (event.button != 1) return;
        if (!event.buttonstate) {
            TerminalRevealCharge.cancel();
            QuantumMigrationCharge.release();
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        MovingObjectPosition pointed = mc.objectMouseOver;
        if (pointed != null && pointed.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && requestEmptyHandInfo(mc, pointed.blockX, pointed.blockY, pointed.blockZ)) {
            event.setCanceled(true);
            return;
        }
        if (!isAltDown() || !isHoldingTerminal(mc)) return;
        event.setCanceled(true);
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            if (TerminalGestureRules.canIncorporate(isAltDown(), isShiftDown(), true))
                requestIncorporation(hit.blockX, hit.blockY, hit.blockZ, false);
            TerminalRevealCharge.cancel();
        } else if (isShiftDown()) {
            // An entity is not air and cannot start the reveal gesture.
            TerminalRevealCharge.start(mc);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.world.isRemote || event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (requestEmptyHandInfo(Minecraft.getMinecraft(), event.x, event.y, event.z)) {
            event.setCanceled(true);
            return;
        }
        if (!isAltDown() || !isHoldingTerminal(Minecraft.getMinecraft())) return;
        event.setCanceled(true);
        if (!TerminalGestureRules.canIncorporate(isAltDown(), isShiftDown(), true)) {
            TerminalRevealCharge.cancel();
            return;
        }
        requestIncorporation(event.x, event.y, event.z, false);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TerminalRevealCharge.tick();
            QuantumMigrationCharge.tick();
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        QuantumMigrationCharge.draw(event);
    }

    @SubscribeEvent
    public void onHighlight(DrawBlockHighlightEvent event) {
        if (!isAltDown() || isShiftDown()
            || !isHoldingTerminal(Minecraft.getMinecraft())
            || event.target == null
            || event.target.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        int x = event.target.blockX, y = event.target.blockY, z = event.target.blockZ;
        if (QuantumIncorporationClientState.registered(event.player.worldObj, x, y, z)
            || (ItemNetworkQuantumTerminal.isBound(event.player.getHeldItem())
                && QuantumIncorporationRegistry.isEligible(event.player.worldObj, x, y, z))) {
            QuantumNodeHighlightRenderer.drawBox(event, x, y, z, true);
        }
    }
}
