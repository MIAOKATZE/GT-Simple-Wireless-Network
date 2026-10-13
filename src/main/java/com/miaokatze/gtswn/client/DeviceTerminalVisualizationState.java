package com.miaokatze.gtswn.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;

/** Server ACK is the only source of an enabled travel session. */
public final class DeviceTerminalVisualizationState {

    private static UUID terminalId;
    private static ItemStack cachedHeld;
    private static String cachedHeldToken;
    private static UUID cachedHeldId;
    private static long lastClickTick = Long.MIN_VALUE;
    private static long lastTeleportTick = Long.MIN_VALUE;
    private static long lastFeedbackTick = Long.MIN_VALUE;
    private static long retryAtTick = Long.MIN_VALUE;

    private DeviceTerminalVisualizationState() {}

    public static void apply(UUID id, boolean enabled) {
        if (id == null) return;
        if (enabled) {
            if (!id.equals(terminalId)) {
                lastClickTick = lastTeleportTick = lastFeedbackTick = Long.MIN_VALUE;
                retryAtTick = Long.MIN_VALUE;
            }
            terminalId = id;
        } else if (id.equals(terminalId)) clear();
    }

    public static boolean active(UUID id) {
        return id != null && id.equals(terminalId);
    }

    public static UUID heldTerminalId() {
        Minecraft mc = Minecraft.getMinecraft();
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemDeviceInfoTerminal)) return null;
        String token = held.stackTagCompound == null ? ""
            : held.stackTagCompound.getString(ItemDeviceInfoTerminal.NBT_UUID);
        if (held != cachedHeld || !java.util.Objects.equals(token, cachedHeldToken)) {
            cachedHeld = held;
            cachedHeldToken = token;
            cachedHeldId = ItemDeviceInfoTerminal.readTerminalId(held);
        }
        return cachedHeldId;
    }

    public static boolean isActiveHeld() {
        return active(heldTerminalId());
    }

    public static String currentTargetKey() {
        return isActiveHeld() ? DeviceTerminalAnchorRenderer.INSTANCE.currentTargetKey() : null;
    }

    /** Used by MouseEvent, Forge interaction events and the item fallback; all ordinary mode clicks are consumed. */
    public static boolean handleRightClick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null
            || mc.currentScreen != null
            || !isActiveHeld()
            || QuantumIncorporationClientHandler.isShiftDown()
            || mc.thePlayer.isSneaking()
            || QuantumIncorporationClientHandler.isAltDown()) return false;
        // Consumed press events must not leave vanilla's use key held, which would re-enter block activation.
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        long now = mc.theWorld.getTotalWorldTime();
        if (lastClickTick == now) return true;
        lastClickTick = now;
        String target = currentTargetKey();
        if (target == null) {
            if (lastFeedbackTick == Long.MIN_VALUE || now - lastFeedbackTick >= 40L) {
                mc.thePlayer.addChatMessage(new ChatComponentTranslation("gtswn.device.visual.aim_first"));
                lastFeedbackTick = now;
            }
            return true;
        }
        if (retryAtTick != Long.MIN_VALUE && now < retryAtTick) return true;
        lastTeleportTick = now;
        retryAtTick = now + 60L;
        GTSWNPacketHandler.NETWORK.sendToServer(PacketDeviceTerminalVisualAction.teleport(terminalId, target));
        DeviceTerminalAnchorRenderer.INSTANCE.clearTarget();
        return true;
    }

    public static void clear() {
        terminalId = null;
        lastClickTick = lastTeleportTick = lastFeedbackTick = Long.MIN_VALUE;
        retryAtTick = Long.MIN_VALUE;
        cachedHeld = null;
        cachedHeldToken = null;
        cachedHeldId = null;
    }

    public static void handleResult(UUID id, int result, String key, int cooldownTicks, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || id == null || !id.equals(terminalId)) return;
        long now = mc.theWorld.getTotalWorldTime();
        retryAtTick = result == 0 || result == 8 ? now + Math.max(0, Math.min(1200, cooldownTicks)) : Long.MIN_VALUE;
        lastTeleportTick = result == 0 || result == 8 ? now : Long.MIN_VALUE;
        DeviceTerminalAnchorRenderer.INSTANCE.clearTarget();
        if (result == 0) DeviceTerminalAnchorRenderer.INSTANCE.invalidatePosition();
        if (result == 1 || result == 9) clear();
        // Failure explanation is supplied once by the server, which has the authoritative reason and XP balance.
    }
}
