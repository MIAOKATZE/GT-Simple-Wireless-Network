package com.miaokatze.gtswn.common.engineering;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore.MachineRecord;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore.TerminalData;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Bound-machine evidence only; never enumerates chunks, serializes native NBT or consumes flow windows. */
public final class EngineeringBoundPowerCollector {

    private final Map<TileEntity, Long> identities = new WeakHashMap<>();
    private long sequence;

    public EngineeringBoundPowerCollector() {}

    public EngineeringPowerResult collect(EntityPlayerMP player, ItemStack terminal, PacketEngineeringSubscribe view,
        boolean axisPattern) {
        TerminalData data = DeviceTerminalDataStore.get(player.worldObj)
            .getTerminal(ItemDeviceInfoTerminal.readTerminalId(terminal));
        Map<Long, Accumulator> cells = new LinkedHashMap<>();
        for (int x = view.minCellX; x <= view.maxCellX; x++)
            for (int z = view.minCellZ; z <= view.maxCellZ; z++) cells.put(key(x, z), new Accumulator(x, z));
        Map<String, Power> samples = new LinkedHashMap<>();
        int unknown = 0, inspected = 0, eligible = 0;
        Power total = Power.ZERO;
        boolean truncated = false;
        if (data != null) for (String key : data.boundKeys) {
            if (++inspected > 1024) {
                truncated = true;
                break;
            }
            int[] pos = parse(key);
            if (pos == null || pos[0] != view.dim) continue;
            int x = EngineeringGrid.cellOfBlock(pos[1], view.gtGrid, axisPattern);
            int z = EngineeringGrid.cellOfBlock(pos[3], view.gtGrid, axisPattern);
            Accumulator cell = cells.get(key(x, z));
            if (cell == null) continue;
            eligible++;
            MachineRecord record = data.records.get(key);
            World world = DimensionManager.getWorld(pos[0]);
            if (!usable(record, world, pos[1], pos[2], pos[3])) {
                unknown++;
                cell.unknown++;
                continue;
            }
            TileEntity tile = world.getTileEntity(pos[1], pos[2], pos[3]);
            String sampleKey = key + "@" + identities.computeIfAbsent(tile, ignored -> ++sequence);
            if (samples.containsKey(sampleKey)) continue;
            Power power = new Power(record.instIn, record.instOut, record.inAvg, record.outAvg);
            samples.put(sampleKey, power);
            cell.power = cell.power.plus(power);
            cell.monitored++;
            total = total.plus(power);
        }
        List<Cell> result = new ArrayList<>();
        for (Accumulator cell : cells.values())
            result.add(new Cell(cell.x, cell.z, cell.monitored, cell.unknown, cell.power));
        return new EngineeringPowerResult(
            total,
            result,
            samples,
            unknown,
            eligible,
            truncated,
            true,
            0,
            0,
            0,
            0,
            false);
    }

    static boolean usable(MachineRecord record, World world, int x, int y, int z) {
        if (record == null || world == null
            || world.getChunkProvider() == null
            || !world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4))
            return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof IGregTechTileEntity gt) || tile.isInvalid()
            || !record.sampledFrom(tile)
            || gt.getMetaTileID() != record.metaTileId
            || gt.getMetaTileEntity() == null) return false;
        long age = world.getTotalWorldTime() - record.lastSampleTick;
        long freshness = Math.max(200L, (long) Config.deviceSampleIntervalSeconds * 40L + 100L);
        return record.lastSampleTick >= 0 && age >= 0
            && age <= freshness
            && record.evidenceKind <= 1
            && record.count > 0
            && !record.recognitionWarmup
            && record.state != DeviceTerminalDataStore.STATE_IGNORED
            && Double.isFinite(record.inAvg)
            && Double.isFinite(record.outAvg);
    }

    static int[] parse(String key) {
        if (key == null || key.length() > 64) return null;
        String[] parts = key.split(":", -1);
        if (parts.length != 4) return null;
        try {
            int[] pos = new int[4];
            for (int i = 0; i < 4; i++) pos[i] = Integer.parseInt(parts[i]);
            return pos;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static final class Accumulator {

        final int x, z;
        int monitored, unknown;
        Power power = Power.ZERO;

        Accumulator(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
