package com.miaokatze.gtswn.common.device;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.util.GTUtility;

/** Shared teleport transaction. Validation and a loaded, safe destination precede any experience charge. */
public final class DeviceTerminalTeleport {

    private static final Map<UUID, Long> LAST = new HashMap<>();
    private static final long COOLDOWN_TICKS = 60;

    private DeviceTerminalTeleport() {}

    public static boolean attempt(EntityPlayerMP player, UUID terminalId, String key, boolean visual) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || player == null || player.isDead || player.getHealth() <= 0 || player.isPlayerSleeping())
            return false;
        if (player.playerNetServerHandler == null || server.getConfigurationManager()
            .func_152612_a(player.getCommandSenderName()) != player) return false;
        if (!holdsTerminal(player, terminalId)) return false;
        if (visual && !com.miaokatze.gtswn.network.DeviceTerminalVisualActionQueue.isEnabled(player, terminalId))
            return false;
        DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(player.worldObj)
            .getTerminal(terminalId);
        if (data == null || !data.boundKeys.contains(key)) return false;
        DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
        int[] position = KeyFormat.parse(key);
        if (record == null || position == null
            || position[0] != record.dim
            || position[1] != record.x
            || position[2] != record.y
            || position[3] != record.z) return false;
        if (visual && player.dimension != record.dim) return false;
        if (record.y < 0 || Math.abs((long) record.x) >= 29999984 || Math.abs((long) record.z) >= 29999984)
            return false;
        WorldServer target = DimensionManager.getWorld(record.dim);
        if (target == null || record.y >= target.getActualHeight() || !loaded(target, record.x, record.z)) return false;
        TileEntity tile = target.getTileEntity(record.x, record.y, record.z);
        if (!(tile instanceof IGregTechTileEntity) || tile.isInvalid()
            || !DeviceMachineTypes.isWorkingMachine(((IGregTechTileEntity) tile).getMetaTileEntity())
            || (record.metaTileId >= 0 && record.metaTileId != ((IGregTechTileEntity) tile).getMetaTileID()))
            return false;
        if (visual) {
            if (player.dimension != record.dim) return false;
            Vec3 look = player.getLookVec();
            if (!DeviceTerminalVisualRules.isAimedAt(
                player.posX,
                player.posY + player.getEyeHeight(),
                player.posZ,
                look.xCoord,
                look.yCoord,
                look.zCoord,
                record.x + 0.5,
                record.y + DeviceTerminalVisualRules.MARKER_Y,
                record.z + 0.5)) return false;
        }
        double[] landing = safeLanding(target, (IGregTechTileEntity) tile, record, player);
        if (landing == null) return false;
        long now = server.worldServerForDimension(0)
            .getTotalWorldTime();
        Long last = LAST.get(player.getUniqueID());
        if (last != null && now >= last && now - last < COOLDOWN_TICKS) return false;
        int cost = DeviceTerminalSettings.get(player.worldObj)
            .teleportCost();
        if (player.experienceLevel < cost) {
            player.addChatMessage(new ChatComponentTranslation("gtswn.device.chat.teleport.no_xp", cost));
            return false;
        }
        boolean moved;
        if (player.dimension == record.dim) {
            player.mountEntity(null);
            player.playerNetServerHandler
                .setPlayerLocation(landing[0], landing[1], landing[2], player.rotationYaw, player.rotationPitch);
            moved = true;
        } else moved = GTUtility.moveEntityToDimensionAtCoords(player, record.dim, landing[0], landing[1], landing[2]);
        if (!moved) return false;
        player.addExperienceLevel(-cost);
        LAST.put(player.getUniqueID(), now);
        return true;
    }

    private static double[] safeLanding(World world, IGregTechTileEntity tile,
        DeviceTerminalDataStore.MachineRecord record, EntityPlayerMP player) {
        ForgeDirection front = tile.getFrontFacing();
        int[][] candidates = {
            { record.x + (front == null ? 0 : front.offsetX), record.y + (front == null ? 1 : front.offsetY),
                record.z + (front == null ? 0 : front.offsetZ) },
            { record.x, record.y + 1, record.z }, { record.x + 1, record.y, record.z },
            { record.x - 1, record.y, record.z }, { record.x, record.y, record.z + 1 },
            { record.x, record.y, record.z - 1 } };
        for (int[] position : candidates) {
            int x = position[0], y = position[1], z = position[2];
            if (y < 1 || y + player.height >= world.getActualHeight()
                || Math.abs((long) x) >= 29999984
                || Math.abs((long) z) >= 29999984
                || !loaded(world, x, z)) continue;
            double half = player.width / 2.0;
            AxisAlignedBB box = AxisAlignedBB
                .getBoundingBox(x + 0.5 - half, y, z + 0.5 - half, x + 0.5 + half, y + player.height, z + 0.5 + half);
            boolean chunksLoaded = true;
            int minChunkX = ((int) Math.floor(box.minX)) >> 4;
            int maxChunkX = ((int) Math.floor(box.maxX)) >> 4;
            int minChunkZ = ((int) Math.floor(box.minZ)) >> 4;
            int maxChunkZ = ((int) Math.floor(box.maxZ)) >> 4;
            if (maxChunkX - minChunkX > 4 || maxChunkZ - minChunkZ > 4) continue;
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!world.getChunkProvider()
                        .chunkExists(chunkX, chunkZ)) chunksLoaded = false;
                }
            }
            if (!chunksLoaded) continue;
            if (!world.getBlock(x, y - 1, z)
                .getMaterial()
                .isSolid() || world.getBlock(x, y - 1, z) == Blocks.cactus
                || world.getBlock(x, y, z) == Blocks.fire
                || world.getBlock(x, y + 1, z) == Blocks.fire
                || world.isAnyLiquid(box)
                || !world.getCollidingBoundingBoxes(player, box)
                    .isEmpty())
                continue;
            return new double[] { x + 0.5, y, z + 0.5 };
        }
        return null;
    }

    private static boolean holdsTerminal(EntityPlayerMP player, UUID terminalId) {
        if (terminalId == null) return false;
        for (net.minecraft.item.ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal
                && terminalId.equals(com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(stack)))
                return true;
        }
        return false;
    }

    private static boolean loaded(World world, int x, int z) {
        return world.getChunkProvider() != null && world.getChunkProvider()
            .chunkExists(x >> 4, z >> 4);
    }

    public static void onLogout(UUID player) {
        LAST.remove(player);
    }

    public static void clear() {
        LAST.clear();
    }
}
