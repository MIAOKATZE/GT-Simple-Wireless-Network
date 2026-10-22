package com.miaokatze.gtswn.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.device.DeviceRegistryData;
import com.miaokatze.gtswn.common.device.DeviceTerminalTeleport;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalAction;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Server ACK is the only source of an enabled travel session. */
public final class DeviceTerminalVisualizationState {

    private static UUID terminalId;
    private static String terminalToken;
    private static int inventorySlot = -1;
    private static int lastInventoryScanTick = Integer.MIN_VALUE;
    private static ItemStack cachedHeld;
    private static String cachedHeldToken;
    private static UUID cachedHeldId;
    private static long lastClickTick = Long.MIN_VALUE;
    private static World lastClickWorld;
    private static EntityPlayer lastClickPlayer;
    private static ItemStack lastClickStack;
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
                inventorySlot = -1;
                lastInventoryScanTick = Integer.MIN_VALUE;
            }
            terminalId = id;
            terminalToken = id.toString();
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
        return active(heldTerminalId()) && ItemDeviceInfoTerminal.isVisualizationEnabled(cachedHeld)
            && isActiveInventory();
    }

    /** Rendering uses the ACK-selected carried terminal; travel still requires that terminal in hand. */
    public static UUID visualizationTerminalId() {
        Minecraft mc = Minecraft.getMinecraft();
        if (terminalId == null || mc.thePlayer == null
            || mc.theWorld == null
            || mc.thePlayer.isDead
            || mc.thePlayer.getHealth() <= 0) return null;
        ItemStack[] inventory = mc.thePlayer.inventory.mainInventory;
        IInventory baubles = TerminalBaubles.inventory(mc.thePlayer);
        int baubleSlot = -inventorySlot - 2;
        if (inventorySlot < -1 && baubles != null
            && baubleSlot < baubles.getSizeInventory()
            && matchesActive(baubles.getStackInSlot(baubleSlot))) return terminalId;
        if (inventorySlot >= 0 && inventorySlot < inventory.length && matchesActive(inventory[inventorySlot]))
            return terminalId;
        inventorySlot = -1;
        int now = mc.thePlayer.ticksExisted;
        if (lastInventoryScanTick != Integer.MIN_VALUE && now >= lastInventoryScanTick
            && now - lastInventoryScanTick < 20) return null;
        lastInventoryScanTick = now;
        if (baubles != null)
            for (int i = 0; i < baubles.getSizeInventory(); i++) if (matchesActive(baubles.getStackInSlot(i))) {
                inventorySlot = -i - 2;
                return terminalId;
            }
        // Only a slot replacement/move performs this bounded scan; unchanged frames do one slot check.
        for (int i = 0; i < inventory.length; i++) if (matchesActive(inventory[i])) {
            inventorySlot = i;
            return terminalId;
        }
        inventorySlot = -1;
        return null;
    }

    private static boolean matchesActive(ItemStack stack) {
        return ItemDeviceInfoTerminal.isVisualizationEnabled(stack)
            && terminalToken.equals(stack.stackTagCompound.getString(ItemDeviceInfoTerminal.NBT_UUID));
    }

    public static boolean isActiveInventory() {
        return visualizationTerminalId() != null;
    }

    public static String currentTargetKey() {
        return isActiveHeld() ? DeviceTerminalAnchorRenderer.INSTANCE.currentTargetKey() : null;
    }

    /** Mouse, Forge block/air events and item callbacks share one intent for each native right-click tick. */
    public static boolean handleRightClick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemDeviceInfoTerminal)) return false;
        long now = mc.theWorld.getTotalWorldTime();
        // Native rightClickMouse continues to RIGHT_CLICK_AIR after a canceled block event, even if we opened a GUI.
        if (lastClickTick == now && lastClickWorld == mc.theWorld
            && lastClickPlayer == mc.thePlayer
            && lastClickStack == held) return true;
        if (mc.currentScreen != null) return false;
        boolean alt = QuantumIncorporationClientHandler.isAltDown();
        boolean shift = QuantumIncorporationClientHandler.isShiftDown() || (!alt && mc.thePlayer.isSneaking());
        if (!alt && !shift && !ItemDeviceInfoTerminal.isVisualizationEnabled(held)) return false;
        lastClickTick = now;
        lastClickWorld = mc.theWorld;
        lastClickPlayer = mc.thePlayer;
        lastClickStack = held;
        TerminalRevealCharge.cancel();
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        if (TerminalGestureRules.canToggleVisualization(true, alt, shift)) {
            UUID id = heldTerminalId();
            GTSWNPacketHandler.NETWORK
                .sendToServer(PacketDeviceTerminalVisualAction.toggle(id == null ? new UUID(0L, 0L) : id));
            return true;
        }
        if (TerminalGestureRules.canCharge(true, alt, shift, TerminalRevealCharge.isPointingAtAir(mc))) {
            TerminalRevealCharge.start(mc);
            return true;
        }
        if (shift) {
            String machine = aimedWorkingMachineKey(mc);
            if (machine == null) GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalSettingsGui();
            else {
                UUID id = heldTerminalId();
                GTSWNPacketHandler.NETWORK.sendToServer(
                    PacketDeviceTerminalAction.cycleRecognition(id == null ? new UUID(0L, 0L) : id, machine));
            }
            return true;
        }
        UUID heldId = heldTerminalId();
        if (heldId == null || !isActiveHeld()) {
            // List polling restores missing metadata/ACK while this press remains a single GUI intent.
            if (lastFeedbackTick == Long.MIN_VALUE || now - lastFeedbackTick >= 20L) {
                GTSWNPacketHandler.NETWORK.sendToServer(DeviceTerminalClientCache.requestMetadata(heldId));
                lastFeedbackTick = now;
            }
            GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalGui();
            return true;
        }
        String target = currentTargetKey();
        if (target == null) {
            GTSimpleWirelessNetwork.proxy.openDeviceInfoTerminalGui();
            return true;
        }
        if (retryAtTick != Long.MIN_VALUE && now < retryAtTick) return true;
        lastTeleportTick = now;
        retryAtTick = now + DeviceTerminalTeleport.COOLDOWN_TICKS;
        GTSWNPacketHandler.NETWORK.sendToServer(PacketDeviceTerminalVisualAction.teleport(terminalId, target));
        DeviceTerminalAnchorRenderer.INSTANCE.clearTarget();
        return true;
    }

    private static String aimedWorkingMachineKey(Minecraft mc) {
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
            || !mc.theWorld.blockExists(hit.blockX, hit.blockY, hit.blockZ)) return null;
        TileEntity tile = mc.theWorld.getTileEntity(hit.blockX, hit.blockY, hit.blockZ);
        if (!(tile instanceof IGregTechTileEntity)
            || !DeviceMachineTypes.isWorkingMachine(((IGregTechTileEntity) tile).getMetaTileEntity())) return null;
        return DeviceRegistryData.makeKey(mc.theWorld.provider.dimensionId, hit.blockX, hit.blockY, hit.blockZ);
    }

    public static void clear() {
        terminalId = null;
        terminalToken = null;
        inventorySlot = -1;
        lastInventoryScanTick = Integer.MIN_VALUE;
        lastClickTick = lastTeleportTick = lastFeedbackTick = Long.MIN_VALUE;
        lastClickWorld = null;
        lastClickPlayer = null;
        lastClickStack = null;
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
