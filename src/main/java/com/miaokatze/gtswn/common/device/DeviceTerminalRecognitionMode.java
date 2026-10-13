package com.miaokatze.gtswn.common.device;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Terminal-local classification overrides never change machine behavior or convert liters to electricity. */
public final class DeviceTerminalRecognitionMode {

    public static final byte AUTO = 0;
    public static final byte OTHER = 1;
    public static final byte IGNORE = 2;
    public static final int MAX_OVERRIDES = 1024;

    private DeviceTerminalRecognitionMode() {}

    public static byte next(byte mode) {
        return mode == AUTO ? OTHER : mode == OTHER ? IGNORE : AUTO;
    }

    public static byte effectiveCapabilities(byte mode, byte original, byte powerType) {
        if (mode == IGNORE) return 0;
        if (mode != OTHER) return original;
        if (DeviceSteamAdapter.energyKind(original) == DeviceSteamAdapter.STEAM)
            return powerType == 1 ? DeviceSteamAdapter.EU_OUT : DeviceSteamAdapter.EU_IN;
        int steam = original & (DeviceSteamAdapter.STEAM_IN | DeviceSteamAdapter.STEAM_OUT);
        return steam != 0 ? (byte) steam : powerType == 1 ? DeviceSteamAdapter.STEAM_OUT : DeviceSteamAdapter.STEAM_IN;
    }

    /** Validates a loaded working machine. Unbound world targets must be within reach of this player. */
    public static byte cycle(EntityPlayerMP player, ItemStack terminal, String key) {
        if (player == null || terminal == null || !(terminal.getItem() instanceof ItemDeviceInfoTerminal)) return -1;
        int[] pos = KeyFormat.parse(key);
        if (pos == null) return -1;
        UUID existingId = ItemDeviceInfoTerminal.readTerminalId(terminal);
        DeviceTerminalDataStore store = DeviceTerminalDataStore.get(player.worldObj);
        DeviceTerminalDataStore.TerminalData data = existingId == null ? null : store.getTerminal(existingId);
        boolean bound = data != null && data.boundKeys.contains(key);
        if (!bound && (player.dimension != pos[0] || player.getDistanceSq(pos[1] + .5, pos[2] + .5, pos[3] + .5) > 64))
            return -1;
        World world = DimensionManager.getWorld(pos[0]);
        boolean loaded = world != null && world.getChunkProvider() != null
            && world.getChunkProvider()
                .chunkExists(pos[1] >> 4, pos[3] >> 4);
        IMetaTileEntity machine;
        int metaTileId;
        if (loaded) {
            TileEntity tile = world.getTileEntity(pos[1], pos[2], pos[3]);
            if (!(tile instanceof IGregTechTileEntity)) return -1;
            IGregTechTileEntity gt = (IGregTechTileEntity) tile;
            machine = gt.getMetaTileEntity();
            metaTileId = gt.getMetaTileID();
        } else {
            if (!bound) return -1;
            DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
            metaTileId = record == null ? -1 : record.metaTileId;
            if (metaTileId < 0 || metaTileId >= gregtech.api.GregTechAPI.METATILEENTITIES.length) return -1;
            machine = gregtech.api.GregTechAPI.METATILEENTITIES[metaTileId];
        }
        if (!DeviceMachineTypes.isWorkingMachine(machine)) return -1;
        UUID terminalId = ItemDeviceInfoTerminal.ensureTerminalIdSynced(player, terminal);
        byte result = store.cycleRecognition(terminalId, key, metaTileId, DeviceSteamAdapter.capabilityMask(machine));
        if (result >= 0) {
            DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
            boolean watchedElsewhere = false;
            for (java.util.Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getActiveTerminals()) {
                if (entry.getValue().boundKeys.contains(key)
                    && store.recognitionMode(entry.getKey(), key, metaTileId) != IGNORE) watchedElsewhere = true;
            }
            if (!watchedElsewhere) DeviceFlowMonitor.forget(machine);
            DeviceTerminalRequestQueue.invalidate(player);
            String modeKey = result == AUTO ? "auto"
                : result == IGNORE ? "ignored"
                    : DeviceSteamAdapter.energyKind(DeviceSteamAdapter.capabilityMask(machine))
                        == DeviceSteamAdapter.STEAM ? "eu" : "steam";
            player.addChatMessage(
                new net.minecraft.util.ChatComponentTranslation(
                    "gtswn.device.chat.recognition_mode",
                    new net.minecraft.util.ChatComponentTranslation("gtswn.device.recognition." + modeKey)));
        }
        return result;
    }
}
