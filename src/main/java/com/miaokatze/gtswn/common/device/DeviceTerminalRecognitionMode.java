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

/** Terminal-local tracking switches never change machine behavior. */
public final class DeviceTerminalRecognitionMode {

    public static final byte TRACK = 0;
    public static final byte IGNORE = 2;
    public static final int MAX_OVERRIDES = 1024;

    private DeviceTerminalRecognitionMode() {}

    public static byte next(byte mode) {
        return mode == IGNORE ? TRACK : IGNORE;
    }

    public static byte effectiveCapabilities(byte mode, byte original, byte powerType) {
        return mode == IGNORE ? 0 : (byte) (original & 3);
    }

    /** v1.8.59 stored AUTO=0, OTHER=1, IGNORE=2; legacy manual choices now track electricity. */
    public static byte migrate(byte mode) {
        return mode == IGNORE ? IGNORE : TRACK;
    }

    /** Validates a loaded working machine. Unbound world targets must be within reach of this player. */
    public static byte cycle(EntityPlayerMP player, ItemStack terminal, String key) {
        if (player == null || terminal == null || !(terminal.getItem() instanceof ItemDeviceInfoTerminal)) return -1;
        int[] pos = KeyFormat.parse(key);
        if (pos == null) return -1;
        UUID existingId = ItemDeviceInfoTerminal.readTerminalId(terminal);
        com.miaokatze.gtswn.common.monitoring.MonitoringProfiles profiles = com.miaokatze.gtswn.common.monitoring.MonitoringProfiles
            .get(player.worldObj);
        if (existingId != null && !profiles.canModify(player.getUniqueID(), existingId)) return -1;
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
        byte result = store
            .cycleRecognition(terminalId, key, metaTileId, DevicePowerCapabilities.capabilityMask(machine), machine);
        if (result >= 0) {
            DeviceTerminalDataStore.ensureActiveTerminal(player.getUniqueID(), terminalId);
            boolean watchedElsewhere = false;
            for (java.util.Map.Entry<UUID, DeviceTerminalDataStore.TerminalData> entry : store.getActiveTerminals()) {
                if (profiles.maySample(entry.getKey()) && entry.getValue().boundKeys.contains(key)
                    && store.recognitionMode(entry.getKey(), key, metaTileId, machine) != IGNORE)
                    watchedElsewhere = true;
            }
            if (!watchedElsewhere && !profiles.hasOfflineObservation(key)) DeviceFlowMonitor.forget(machine);
            DeviceTerminalRequestQueue.invalidate(player);
            String modeKey = result == IGNORE ? "ignored" : "track";
            player.addChatMessage(
                new net.minecraft.util.ChatComponentTranslation(
                    "gtswn.device.chat.recognition_mode",
                    new net.minecraft.util.ChatComponentTranslation("gtswn.device.recognition." + modeKey)));
        }
        return result;
    }
}
