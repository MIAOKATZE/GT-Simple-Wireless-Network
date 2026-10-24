package com.miaokatze.gtswn.common.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
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
    public static final int COOLDOWN_TICKS = 10;
    private static final int MAX_LANDING_BODY_BLOCKS = 256;

    private DeviceTerminalTeleport() {}

    public enum Status {

        SUCCESS(0),
        INACTIVE(1),
        INVALID_TARGET(2),
        UNLOADED(3),
        TOO_FAR(4),
        NOT_AIMED(5),
        UNSAFE(6),
        NO_XP(7),
        COOLDOWN(8),
        UNAVAILABLE_PLAYER(9),
        ERROR(10),
        NO_TARGET(11),
        STALE_TARGET(12);

        public final byte code;

        Status(int code) {
            this.code = (byte) code;
        }
    }

    public static final class Outcome {

        public final Status status;
        public final int cooldownTicks;

        public Outcome(Status status, int cooldownTicks) {
            this.status = status;
            this.cooldownTicks = Math.max(0, Math.min(60, cooldownTicks));
        }

        public boolean succeeded() {
            return status == Status.SUCCESS;
        }
    }

    private static Outcome outcome(Status status) {
        return new Outcome(status, 0);
    }

    public static boolean attempt(EntityPlayerMP player, UUID terminalId, String key, boolean visual) {
        Outcome result = attemptDetailed(player, terminalId, key, visual);
        if (!result.succeeded()) feedback(player, result);
        return result.succeeded();
    }

    public static Outcome attemptDetailed(EntityPlayerMP player, UUID terminalId, String key, boolean visual) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || player == null
            || player.isDead
            || player.getHealth() <= 0
            || player.isPlayerSleeping()
            || player.playerNetServerHandler == null
            || server.worldServerForDimension(0) == null
            || server.getConfigurationManager()
                .func_152612_a(player.getCommandSenderName()) != player)
            return outcome(Status.UNAVAILABLE_PLAYER);
        if (!holdsTerminal(player, terminalId, visual)) return outcome(Status.INVALID_TARGET);
        if (visual && !com.miaokatze.gtswn.network.DeviceTerminalVisualActionQueue.isEnabled(player, terminalId))
            return outcome(Status.INACTIVE);
        if (key == null || key.isEmpty()) return outcome(Status.NO_TARGET);
        DeviceTerminalDataStore.TerminalData data = DeviceTerminalDataStore.get(player.worldObj)
            .getTerminal(terminalId);
        if (data == null || !data.boundKeys.contains(key)) return outcome(Status.INVALID_TARGET);
        DeviceTerminalDataStore.MachineRecord record = data.records.get(key);
        int[] position = KeyFormat.parse(key);
        if (record == null || position == null
            || position[0] != record.dim
            || position[1] != record.x
            || position[2] != record.y
            || position[3] != record.z) return outcome(Status.STALE_TARGET);
        if (visual && player.dimension != record.dim) return outcome(Status.TOO_FAR);
        if (record.y < 0 || Math.abs((long) record.x) >= 29999984 || Math.abs((long) record.z) >= 29999984)
            return outcome(Status.INVALID_TARGET);
        WorldServer target = DimensionManager.getWorld(record.dim);
        if (target == null || !loaded(target, record.x, record.z)) return outcome(Status.UNLOADED);
        if (record.y >= target.getActualHeight()) return outcome(Status.STALE_TARGET);
        TileEntity tile = target.getTileEntity(record.x, record.y, record.z);
        if (!(tile instanceof IGregTechTileEntity) || tile.isInvalid()
            || !DeviceMachineTypes.isWorkingMachine(((IGregTechTileEntity) tile).getMetaTileEntity())
            || (record.metaTileId >= 0 && record.metaTileId != ((IGregTechTileEntity) tile).getMetaTileID()))
            return outcome(Status.STALE_TARGET);
        if (visual) {
            Vec3 look = player.getLookVec();
            double eyeY = DeviceTerminalVisualRules
                .eyeY(player.posY, player.yOffset, player.getEyeHeight(), player.getDefaultEyeHeight());
            double dx = record.x + 0.5 - player.posX, dy = record.y + DeviceTerminalVisualRules.MARKER_Y - eyeY,
                dz = record.z + 0.5 - player.posZ;
            double distance = dx * dx + dy * dy + dz * dz;
            if (!Double.isFinite(distance)
                || distance > DeviceTerminalVisualRules.MAX_RANGE * DeviceTerminalVisualRules.MAX_RANGE)
                return outcome(Status.TOO_FAR);
            if (!DeviceTerminalVisualRules.isAimedAtMachine(
                player.posX,
                eyeY,
                player.posZ,
                look.xCoord,
                look.yCoord,
                look.zCoord,
                record.x,
                record.y,
                record.z)) return outcome(Status.NOT_AIMED);
        }
        long now = server.worldServerForDimension(0)
            .getTotalWorldTime();
        Long last = LAST.get(player.getUniqueID());
        if (last != null && now >= last && now - last < COOLDOWN_TICKS)
            return new Outcome(Status.COOLDOWN, (int) (COOLDOWN_TICKS - (now - last)));
        double[] landing = safeLanding(target, ((IGregTechTileEntity) tile).getFrontFacing(), record, player);
        if (landing == null) return outcome(Status.UNSAFE);
        int cost = DeviceTerminalSettings.get(player.worldObj)
            .teleportCost();
        if (player.experienceLevel < cost) return outcome(Status.NO_XP);
        boolean moved;
        if (player.dimension == record.dim) {
            player.mountEntity(null);
            player.playerNetServerHandler
                .setPlayerLocation(landing[0], landing[1], landing[2], player.rotationYaw, player.rotationPitch);
            moved = true;
        } else moved = GTUtility.moveEntityToDimensionAtCoords(player, record.dim, landing[0], landing[1], landing[2]);
        if (!moved) return outcome(Status.ERROR);
        player.addExperienceLevel(-cost);
        LAST.put(player.getUniqueID(), now);
        return new Outcome(Status.SUCCESS, (int) COOLDOWN_TICKS);
    }

    public static void feedback(EntityPlayerMP player, Outcome result) {
        if (player == null || player.playerNetServerHandler == null || result.succeeded()) return;
        String key = "gtswn.device.visual.teleport." + result.status.name()
            .toLowerCase(java.util.Locale.ROOT);
        if (result.status == Status.NO_XP) player.addChatMessage(
            new ChatComponentTranslation(
                key,
                DeviceTerminalSettings.get(player.worldObj)
                    .teleportCost()));
        else if (result.status == Status.COOLDOWN)
            player.addChatMessage(new ChatComponentTranslation(key, (result.cooldownTicks + 19) / 20));
        else player.addChatMessage(new ChatComponentTranslation(key));
    }

    // At most 45 front cells and 75 cells above the controller; no structure/world scan.
    static double[] safeLanding(World world, ForgeDirection front, DeviceTerminalDataStore.MachineRecord record,
        EntityPlayerMP player) {
        double feetY = player.posY - player.yOffset;
        if (!Double.isFinite(player.posX) || !Double.isFinite(feetY)
            || !Double.isFinite(player.posZ)
            || !Float.isFinite(player.width)
            || !Float.isFinite(player.height)
            || player.width <= 0
            || player.height <= 0) return null;
        List<LandingCandidate> candidates = new ArrayList<>(120);
        if (front != null && front != ForgeDirection.UNKNOWN) {
            for (int normal = 1; normal <= 3; normal++) {
                for (int lateral = -2; lateral <= 2; lateral++) {
                    for (int vertical = -1; vertical <= 1; vertical++) {
                        int x = record.x + front.offsetX * normal;
                        int y = record.y + front.offsetY * normal;
                        int z = record.z + front.offsetZ * normal;
                        if (front.offsetY == 0) {
                            x -= front.offsetZ * lateral;
                            y += vertical;
                            z += front.offsetX * lateral;
                        } else {
                            x += lateral;
                            z += vertical;
                        }
                        candidates.add(new LandingCandidate(x, y, z, true, player.posX, feetY, player.posZ));
                    }
                }
            }
        }
        for (int height = 1; height <= 3; height++) {
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    candidates.add(
                        new LandingCandidate(
                            record.x + x,
                            record.y + height,
                            record.z + z,
                            false,
                            player.posX,
                            feetY,
                            player.posZ));
                }
            }
        }
        candidates.sort((left, right) -> {
            int distanceOrder = Double.compare(left.distanceSquared, right.distanceSquared);
            return distanceOrder != 0 ? distanceOrder : Boolean.compare(right.front, left.front);
        });
        for (LandingCandidate candidate : candidates) {
            int x = candidate.x, y = candidate.y, z = candidate.z;
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
            if ((!player.capabilities.isFlying && !world.getBlock(x, y - 1, z)
                .getMaterial()
                .isSolid()) || world.getBlock(x, y - 1, z) == Blocks.cactus
                || bodyIsHazardous(world, box)
                || !world.getCollidingBoundingBoxes(player, box)
                    .isEmpty())
                continue;
            return new double[] { x + 0.5, y, z + 0.5 };
        }
        return null;
    }

    private static boolean bodyIsHazardous(World world, AxisAlignedBB box) {
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
        long spanX = (long) maxX - minX + 1, spanY = (long) maxY - minY + 1, spanZ = (long) maxZ - minZ + 1;
        if (spanX <= 0 || spanY <= 0
            || spanZ <= 0
            || spanX > MAX_LANDING_BODY_BLOCKS
            || spanY > MAX_LANDING_BODY_BLOCKS
            || spanZ > MAX_LANDING_BODY_BLOCKS
            || spanX * spanY * spanZ > MAX_LANDING_BODY_BLOCKS) return true;
        // All horizontal chunks were checked before this read; avoid native isAnyLiquid's negative-coordinate margin.
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlock(x, y, z);
                    if (block == Blocks.fire || block == Blocks.cactus
                        || block.getMaterial()
                            .isLiquid()
                        || block.isBurning(world, x, y, z)) return true;
                }
            }
        }
        return false;
    }

    private static final class LandingCandidate {

        final int x, y, z;
        final boolean front;
        final double distanceSquared;

        LandingCandidate(int x, int y, int z, boolean front, double playerX, double playerY, double playerZ) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.front = front;
            double dx = x + 0.5 - playerX, dy = y - playerY, dz = z + 0.5 - playerZ;
            distanceSquared = dx * dx + dy * dy + dz * dz;
        }
    }

    public static boolean holdsTerminal(EntityPlayerMP player, UUID terminalId, boolean heldOnly) {
        if (terminalId == null) return false;
        if (heldOnly) {
            net.minecraft.item.ItemStack held = player.getHeldItem();
            return held != null && held.getItem() instanceof com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal
                && com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.isVisualizationEnabled(held)
                && terminalId.equals(com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(held));
        }
        for (net.minecraft.item.ItemStack stack : player.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal
                && terminalId.equals(com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(stack)))
                return true;
        }
        for (net.minecraft.item.ItemStack stack : com.miaokatze.gtswn.common.baubles.TerminalBaubles
            .terminals(player)) {
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
