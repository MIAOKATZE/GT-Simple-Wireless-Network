package com.miaokatze.gtswn.common.device;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.miaokatze.gtswn.common.util.SavedDataUtil;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalSettings;

/** Per-save, server-authoritative settings; local client configuration cannot change teleport cost. */
public final class DeviceTerminalSettings extends WorldSavedData {

    public static final String DATA_NAME = "gtswn_device_terminal_settings";
    public static final int MAX_TELEPORT_COST = 1000000;
    private static final Map<UUID, String> SENT = new HashMap<>();
    private int teleportCost = Math.max(0, Math.min(MAX_TELEPORT_COST, Config.deviceTeleportXPCost));

    public DeviceTerminalSettings(String name) {
        super(name);
    }

    public static DeviceTerminalSettings get(World world) {
        MinecraftServer server = MinecraftServer.getServer();
        World global = server == null ? world : server.worldServerForDimension(0);
        return SavedDataUtil.loadOrCreate(
            global == null ? world : global,
            DATA_NAME,
            DeviceTerminalSettings.class,
            DeviceTerminalSettings::new);
    }

    public int teleportCost() {
        return teleportCost;
    }

    public static boolean canEdit(EntityPlayerMP player) {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.getConfigurationManager()
            .func_152596_g(player.getGameProfile());
    }

    public boolean setTeleportCost(EntityPlayerMP player, int value) {
        if (!canEdit(player) || value < 0 || value > MAX_TELEPORT_COST) return false;
        if (teleportCost != value) {
            teleportCost = value;
            markDirty();
            MinecraftServer server = MinecraftServer.getServer();
            for (Object candidate : server.getConfigurationManager().playerEntityList) {
                if (candidate instanceof EntityPlayerMP) sync((EntityPlayerMP) candidate);
            }
        }
        return true;
    }

    /** Metadata changes are sent independently of machine pages; stable polling adds no packets. */
    public void sync(EntityPlayerMP player) {
        boolean editable = canEdit(player);
        int seconds = Math.max(1, Config.deviceSampleIntervalSeconds);
        String signature = teleportCost + ":" + editable + ":" + seconds + ":" + player.dimension;
        if (signature.equals(SENT.get(player.getUniqueID()))) return;
        SENT.put(player.getUniqueID(), signature);
        GTSWNPacketHandler.NETWORK
            .sendTo(new PacketSyncDeviceTerminalSettings(teleportCost, editable, seconds), player);
    }

    public static void onLogout(UUID player) {
        SENT.remove(player);
    }

    public static void clear() {
        SENT.clear();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        if (tag.hasKey("teleportCost")) {
            teleportCost = Math.max(0, Math.min(MAX_TELEPORT_COST, tag.getInteger("teleportCost")));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setInteger("teleportCost", teleportCost);
    }
}
