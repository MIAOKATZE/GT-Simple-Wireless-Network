package com.miaokatze.gtswn.common.engineering.power;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.device.DeviceDefaultRecognitionPolicy;
import com.miaokatze.gtswn.common.device.DeviceFlowMonitor;
import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.device.DeviceSpecialPowerProvider;
import com.miaokatze.gtswn.common.engineering.EngineeringGrid;
import com.miaokatze.gtswn.common.engineering.EngineeringPowerResult;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Cell;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Power;
import com.miaokatze.gtswn.network.PacketEngineeringSubscribe;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 会话私有、只读的已加载区域观察器。只遍历原始区块 TE 表，不生成区块、不登记或绑定机器。
 * 每次 collect 的发现工作有双预算；大区域分批发现，未加载与未完成覆盖单独报告。
 * 功率始终取非消费 20 tick 窗口，不读取或清空 DIT 的持久采样累计量。
 */
public final class EngineeringRegionPowerCollector {

    public static final int MAX_MACHINES = 2048;
    public static final int CHUNK_BUDGET = 32;
    public static final int TILE_BUDGET = 1024;
    private static final long WATCH_TTL = 200;
    private final Map<String, Machine> machines = new LinkedHashMap<>();
    private final List<ChunkState> chunks = new ArrayList<>();
    private World observedWorld;
    private int dim, minX, minZ, maxX, maxZ;
    private boolean gtGrid, axis;
    private int cursor;
    private long lastTick = Long.MIN_VALUE;
    private long nextIdentity;
    private Chunk scanningChunk;
    private Iterator<?> tileIterator;
    private final Set<String> seen = new HashSet<>();

    private static final class ChunkState {

        final int x, z;
        WeakReference<Chunk> identity = new WeakReference<>(null);
        boolean scanned, truncated;

        ChunkState(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class Machine {

        final TileEntity tile;
        final IMetaTileEntity controller;
        final int chunk;
        final String observationKey;

        Machine(TileEntity tile, IMetaTileEntity controller, int chunk, String observationKey) {
            this.tile = tile;
            this.controller = controller;
            this.chunk = chunk;
            this.observationKey = observationKey;
        }
    }

    public EngineeringPowerResult collect(EntityPlayerMP player, ItemStack actualDit,
        PacketEngineeringSubscribe request, boolean axisPattern, long serverTick) {
        // Terminal qualification and owner/dimension authorization belong to the session, before this call.
        if (!EngineeringGrid
            .valid(request.minCellX, request.minCellZ, request.maxCellX, request.maxCellZ, request.gtGrid)) {
            close();
            return empty(0);
        }
        World world = DimensionManager.getWorld(request.dim);
        if (!sameView(world, request, axisPattern) || serverTick < lastTick) configure(world, request, axisPattern);
        lastTick = serverTick;
        if (world == null || world.getChunkProvider() == null) return empty(chunks.size());

        Chunk[] loaded = loadedChunks(world);
        discardReplacedMachines(loaded);
        discover(loaded);
        Map<String, Power> samples = new LinkedHashMap<>();
        Map<Long, MutableCell> cells = cells(request);
        Power total = Power.ZERO;
        int unknown = 0, modeled = 0, loadedCount = 0, scanned = 0;
        boolean truncated = false;
        for (int i = 0; i < chunks.size(); i++) {
            if (loaded[i] != null) {
                loadedCount++;
                if (chunks.get(i).scanned) scanned++;
                truncated |= chunks.get(i).truncated;
            }
        }
        for (Map.Entry<String, Machine> entry : machines.entrySet()) {
            Machine machine = entry.getValue();
            MutableCell cell = cells.get(
                cellKey(
                    EngineeringGrid.cellOfBlock(machine.tile.xCoord, gtGrid, axis),
                    EngineeringGrid.cellOfBlock(machine.tile.zCoord, gtGrid, axis)));
            DeviceFlowMonitor.Reading reading = DeviceFlowMonitor.watch(machine.controller, WATCH_TTL);
            if (reading == null || !reading.windowReady()) {
                unknown++;
                cell.unknown++;
                continue;
            }
            double[] rates = reading.snapshotWindow();
            if (rates[0] == 0 && rates[1] == 0 && ((IGregTechTileEntity) machine.tile).isActive()) {
                long model = DeviceSpecialPowerProvider.readAuthoritativeEut(machine.controller);
                if (model != 0) {
                    if (model > 0) rates[1] = model;
                    else rates[0] = model == Long.MIN_VALUE ? (double) Long.MAX_VALUE : -model;
                    modeled++;
                }
            }
            // Both labels describe this actual twenty-tick window, not DIT's persistent sixty-point mean.
            Power power = new Power(rates[0], rates[1], rates[0], rates[1]);
            samples.put(machine.observationKey, power);
            total = total.plus(power);
            cell.power = cell.power.plus(power);
            cell.monitored++;
        }
        List<Cell> resultCells = new ArrayList<>();
        for (MutableCell cell : cells.values())
            resultCells.add(new Cell(cell.x, cell.z, cell.monitored, cell.unknown, cell.power));
        return new EngineeringPowerResult(
            total,
            resultCells,
            samples,
            unknown,
            machines.size(),
            truncated,
            false,
            loadedCount,
            chunks.size(),
            scanned,
            modeled,
            scanned < loadedCount);
    }

    /** Stop renewing shared watches; TTL removes abandoned observations without interrupting an active DIT sampler. */
    public void close() {
        machines.clear();
        chunks.clear();
        seen.clear();
        observedWorld = null;
        scanningChunk = null;
        tileIterator = null;
        cursor = 0;
        lastTick = Long.MIN_VALUE;
    }

    private boolean sameView(World world, PacketEngineeringSubscribe request, boolean axisPattern) {
        return !chunks.isEmpty() && observedWorld == world
            && dim == request.dim
            && gtGrid == request.gtGrid
            && axis == axisPattern
            && minX == request.minCellX
            && minZ == request.minCellZ
            && maxX == request.maxCellX
            && maxZ == request.maxCellZ;
    }

    private void configure(World world, PacketEngineeringSubscribe request, boolean axisPattern) {
        close();
        observedWorld = world;
        dim = request.dim;
        gtGrid = request.gtGrid;
        axis = axisPattern;
        minX = request.minCellX;
        minZ = request.minCellZ;
        maxX = request.maxCellX;
        maxZ = request.maxCellZ;
        int fromX = EngineeringGrid.minBlock(minX, gtGrid, axis) >> 4;
        int fromZ = EngineeringGrid.minBlock(minZ, gtGrid, axis) >> 4;
        int toX = EngineeringGrid.maxBlockExclusive(maxX, gtGrid, axis) >> 4;
        int toZ = EngineeringGrid.maxBlockExclusive(maxZ, gtGrid, axis) >> 4;
        for (int z = fromZ; z < toZ; z++) for (int x = fromX; x < toX; x++) chunks.add(new ChunkState(x, z));
    }

    private Chunk[] loadedChunks(World world) {
        Chunk[] loaded = new Chunk[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            ChunkState state = chunks.get(i);
            // chunkExists is checked on the same server thread before retrieving an already loaded chunk.
            Chunk chunk = world.getChunkProvider()
                .chunkExists(state.x, state.z) ? world.getChunkFromChunkCoords(state.x, state.z) : null;
            loaded[i] = chunk;
            if (state.identity.get() != chunk) {
                state.identity = new WeakReference<>(chunk);
                state.scanned = state.truncated = false;
                if (i == cursor) resetIterator();
            }
        }
        return loaded;
    }

    private void discardReplacedMachines(Chunk[] loaded) {
        Iterator<Machine> iterator = machines.values()
            .iterator();
        while (iterator.hasNext()) {
            Machine machine = iterator.next();
            Chunk chunk = loaded[machine.chunk];
            TileEntity tile = machine.tile;
            if (chunk == null || tile.isInvalid()
                || chunk.chunkTileEntityMap.get(new ChunkPosition(tile.xCoord & 15, tile.yCoord, tile.zCoord & 15))
                    != tile
                || ((IGregTechTileEntity) tile).getMetaTileEntity() != machine.controller) iterator.remove();
        }
    }

    private void discover(Chunk[] loaded) {
        int chunkWork = 0, tileWork = 0;
        while (chunkWork < Math.min(CHUNK_BUDGET, chunks.size()) && tileWork < TILE_BUDGET) {
            ChunkState state = chunks.get(cursor);
            Chunk chunk = loaded[cursor];
            if (chunk == null) {
                advance();
                chunkWork++;
                continue;
            }
            if (scanningChunk != chunk) {
                scanningChunk = chunk;
                tileIterator = chunk.chunkTileEntityMap.values()
                    .iterator();
                seen.clear();
                state.truncated = false;
            }
            try {
                while (tileWork < TILE_BUDGET && tileIterator.hasNext()) {
                    Object object = tileIterator.next();
                    tileWork++;
                    if (!(object instanceof TileEntity) || !(object instanceof IGregTechTileEntity)) continue;
                    TileEntity tile = (TileEntity) object;
                    IGregTechTileEntity base = (IGregTechTileEntity) object;
                    IMetaTileEntity controller = base.getMetaTileEntity();
                    if (tile.isInvalid() || base.isSteampowered()
                        || !DeviceMachineTypes.isWorkingMachine(controller)
                        || DeviceDefaultRecognitionPolicy.isPassiveFacility(controller)) continue;
                    String key = dim + ":" + tile.xCoord + ":" + tile.yCoord + ":" + tile.zCoord;
                    if (!machines.containsKey(key) && machines.size() >= MAX_MACHINES) {
                        state.truncated = true;
                        continue;
                    }
                    seen.add(key);
                    Machine existing = machines.get(key);
                    if (existing == null || existing.controller != controller || existing.tile != tile)
                        machines.put(key, new Machine(tile, controller, cursor, key + "@" + ++nextIdentity));
                }
                if (!tileIterator.hasNext()) {
                    int completed = cursor;
                    machines.entrySet()
                        .removeIf(entry -> entry.getValue().chunk == completed && !seen.contains(entry.getKey()));
                    state.scanned = true;
                    advance();
                    chunkWork++;
                }
            } catch (ConcurrentModificationException changedBetweenTicks) {
                // The raw map changed while a budgeted iteration was paused: restart this chunk next collection.
                state.scanned = false;
                resetIterator();
                break;
            }
        }
    }

    private void advance() {
        resetIterator();
        cursor = (cursor + 1) % chunks.size();
    }

    private void resetIterator() {
        scanningChunk = null;
        tileIterator = null;
        seen.clear();
    }

    private EngineeringPowerResult empty(int totalChunks) {
        return new EngineeringPowerResult(
            Power.ZERO,
            new ArrayList<>(),
            new HashMap<>(),
            0,
            0,
            false,
            false,
            0,
            totalChunks,
            0,
            0,
            true);
    }

    private static final class MutableCell {

        final int x, z;
        int monitored, unknown;
        Power power = Power.ZERO;

        MutableCell(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }

    private static Map<Long, MutableCell> cells(PacketEngineeringSubscribe request) {
        Map<Long, MutableCell> cells = new LinkedHashMap<>();
        for (int z = request.minCellZ; z <= request.maxCellZ; z++)
            for (int x = request.minCellX; x <= request.maxCellX; x++) cells.put(cellKey(x, z), new MutableCell(x, z));
        return cells;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

}
