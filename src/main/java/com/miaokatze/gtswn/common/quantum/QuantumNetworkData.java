package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridBlock;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IMachineSet;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.me.GridAccessException;
import appeng.me.cache.GridStorageCache;
import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/**
 * 量子终端网络数据装配器 + 数据载体 POJO（T5，规划 plan_20260722152445.md §6）。
 * <p>
 * 职责：在服务端把「锚点控制器所属 ME 网络」的四类运行数据装配为不可变快照，
 * 供 {@code PacketSyncQuantumTerminalData}（disc 6）序列化下发：
 * <ol>
 * <li>总频道数 =（控制器数量 × 6 − 同其他控制器相连的面数）× 32
 * ——公式实现复用 {@link QuantumControllerRegistry#computeTotalChannels(Set)}，
 * 输入集合由 {@code grid.getMachines(TileController.class)} 坐标打包而成（与公式语义一致：
 * 只在网格内控制器集合内计数相邻关系，每对相邻被两侧各计 1 次）</li>
 * <li>已消耗频道 = 网格内全部量子节点各自连接 usedChannels 的 max 之和
 * （节点桥接连接满载 32，普通邻接连接按实际分配）</li>
 * <li>能量四项 = {@link IEnergyGrid} 的 avgPowerUsage / avgPowerInjection / storedPower / maxStoredPower</li>
 * <li>设备列表 = {@code getMachinesClasses()} 逐类聚合 {图标, 数量}，按数量降序，≤ {@link #MAX_ENTRIES} 截断；
 * 图标取该类第一个节点的 {@code getGridBlock().getMachineRepresentation()}（仿 ContainerNetworkStatus）</li>
 * </ol>
 * <p>
 * 离线语义（D6/D7）：维度不存在 / 锚点区块未加载 / 锚点位置已不是控制器 / 网格未就绪
 * 时返回 {@code online=false} 的快照，仅锚点坐标有效，其余字段保持默认 0 / 空列表。
 * <p>
 * 字段为 public 的 POJO 风格（仿 {@code AEMonitorSample} 的 public final 字段先例），
 * 双端共享：服务端装配写入，客户端经包 6 反序列化后只读。
 */
public class QuantumNetworkData {

    /** 设备列表截断上限（规划 §6：entryCount ≤ 128，防包体积膨胀） */
    public static final int MAX_ENTRIES = 128;

    /** 完整终端快照缓存间隔：与客户端轮询间隔一致，避免同一轮请求重复枚举 AE 网络。 */
    private static final long FULL_CACHE_INTERVAL_TICKS = 10L;

    /** 完整快照缓存保留窗口（时间桶）。 */
    private static final long FULL_CACHE_RETAIN_BUCKETS = 4L;

    private static final Map<AnchorKey, FullCacheEntry> FULL_CACHE = new HashMap<>();
    private static long lastFullCachePruneBucket = Long.MIN_VALUE;
    private static long fullCacheHits;
    private static long fullCacheMisses;
    private static long fullAssemblies;
    private static long fullAssemblyNanos;

    // ==================== NBT 键名（与 ItemNetworkQuantumTerminal 私有常量同字符串，规划 §5.1） ====================
    // 为遵守「不改 T1-T4 已完成文件」的纪律，此处冗余定义同名字符串而非把终端的键名改 public

    /** 已绑定标记：1 = 已绑定（byte） */
    private static final String NBT_BOUND = "QT_Bound";

    /** 锚点控制器维度 ID */
    private static final String NBT_ANCHOR_DIM = "QT_AnchorDim";

    /** 锚点控制器坐标 */
    private static final String NBT_ANCHOR_X = "QT_AnchorX";
    private static final String NBT_ANCHOR_Y = "QT_AnchorY";
    private static final String NBT_ANCHOR_Z = "QT_AnchorZ";

    // ==================== 快照字段（与包 6 字段一一对应，规划 §6） ====================

    /** 锚点解析成功且网格可用 */
    public boolean online;

    /** 锚点控制器维度与坐标 */
    public int anchorDim;
    public int anchorX;
    public int anchorY;
    public int anchorZ;

    /** 总频道数 =（控制器数 × 6 − 相连面数）× 32 */
    public int totalChannels;
    public boolean channelsInfinite;

    /** 已消耗频道 = Σ 各量子节点连接 usedChannels 的 max */
    public int usedChannels;

    /** 网络中量子节点（TileEntityNetworkQuantumNode）数量（v1.6.9 新增，供终端 UI 紧凑显示） */
    public int quantumNodeCount;

    /** 网络平均能量消耗（AE/t，近 10 tick 平均） */
    public double avgPowerUsage;

    /** 网络平均能量注入（AE/t，近 10 tick 平均） */
    public double avgPowerInjection;

    /** 网络当前储能（AE） */
    public double storedPower;

    /** 网络储能上限（AE） */
    public double maxStoredPower;

    /** 能量网络是否拥有无限储能（创造能量单元等） */
    public boolean powerInfinite;

    /** 物品存储字节：已用 / 总计 */
    public double itemBytesUsed;
    public double itemBytesTotal;

    /** 流体存储字节：已用 / 总计 */
    public double fluidBytesUsed;
    public double fluidBytesTotal;

    /** 源质存储字节：已用 / 总计（无 Thaumcraft 时恒为 0） */
    public double essentiaBytesUsed;
    public double essentiaBytesTotal;

    /** 设备总数（全部机器类的节点数之和） */
    public int totalMachines;

    /** 聚合后的设备条目（按数量降序，≤ {@link #MAX_ENTRIES} 条） */
    public final List<DeviceEntry> entries = new ArrayList<>();

    /**
     * 设备条目：一类机器的图标与数量。
     */
    public static class DeviceEntry {

        /** 设备图标（machineRepresentation 的副本，stackSize 固定 1） */
        public final ItemStack icon;

        /** 该类设备在网络中的节点数量 */
        public final int count;

        public DeviceEntry(ItemStack icon, int count) {
            this.icon = icon;
            this.count = count;
        }
    }

    /**
     * 从玩家手持的已绑定量子终端装配（包 5 Handler 入口）。
     *
     * @param player 请求方玩家（当前未使用，保留以便未来做权限校验）
     * @param held   玩家手持物品
     * @return 装配完成的快照；手持不是已绑定量子终端时返回 null（调用方静默丢弃请求）
     */
    public static QuantumNetworkData assemble(EntityPlayer player, ItemStack held) {
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return null;
        }
        NBTTagCompound tag = held.stackTagCompound;
        if (tag == null || tag.getByte(NBT_BOUND) != 1) {
            return null;
        }
        return assemble(
            tag.getInteger(NBT_ANCHOR_DIM),
            tag.getInteger(NBT_ANCHOR_X),
            tag.getInteger(NBT_ANCHOR_Y),
            tag.getInteger(NBT_ANCHOR_Z));
    }

    /**
     * 从手持已绑定终端构造「离线快照」（v1.6.1 问题 4b 兜底）：
     * 仅填充锚点坐标，{@link #online} 保持 false（离线语义），其余字段保持默认 0 / 空列表，
     * GUI 据此显示离线面板而非「...」。
     * 手持不是已绑定量子终端时返回 null。
     *
     * @param held 玩家手持物品
     * @return 离线快照；手持不是已绑定量子终端时返回 null
     */
    public static QuantumNetworkData offlineFromStack(ItemStack held) {
        if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)) {
            return null;
        }
        NBTTagCompound tag = held.stackTagCompound;
        if (tag == null || tag.getByte(NBT_BOUND) != 1) {
            return null;
        }
        QuantumNetworkData data = new QuantumNetworkData();
        // online 默认 false = 离线语义；仅锚点四维标有效，供 GUI 离线面板显示绑定目标
        data.anchorDim = tag.getInteger(NBT_ANCHOR_DIM);
        data.anchorX = tag.getInteger(NBT_ANCHOR_X);
        data.anchorY = tag.getInteger(NBT_ANCHOR_Y);
        data.anchorZ = tag.getInteger(NBT_ANCHOR_Z);
        return data;
    }

    /**
     * 按锚点四维标装配网络快照（仅服务端调用）。
     * <p>
     * 离线判定全部走「不触发区块加载」的路径：worldServerForDimension 只取已加载维度，
     * blockExists 不触发锚点区块加载（与 TileEntityNetworkQuantumNode 重连循环同一手法）。
     */
    public static QuantumNetworkData assemble(int anchorDim, int anchorX, int anchorY, int anchorZ) {
        QuantumNetworkData data = new QuantumNetworkData();
        data.anchorDim = anchorDim;
        data.anchorX = anchorX;
        data.anchorY = anchorY;
        data.anchorZ = anchorZ;
        data.channelsInfinite = QuantumControllerRegistry.isChannelsInfinite();

        // 1. 维度解析：维度不存在或未加载 → 离线
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return data;
        }
        World world = server.worldServerForDimension(anchorDim);
        if (world == null) {
            return data;
        }
        // 2. 锚点区块未加载 → 离线（blockExists 不触发区块加载）
        if (!world.blockExists(anchorX, anchorY, anchorZ)) {
            return data;
        }
        // 3. 锚点位置已不是 ME 控制器（D7：锚点被拆 → 离线保留绑定）→ 离线
        TileEntity te = world.getTileEntity(anchorX, anchorY, anchorZ);
        if (!(te instanceof TileController)) {
            return data;
        }
        // 4. 获取锚点所属网格；GridAccessException（节点/网格未就绪）→ 离线
        // 编译坑规避：必须经 IGridProxyable 接口调 getProxy()，且源表达式静态类型为 TileEntity——
        // 直接从 TileController 调用会触发 javac 解析 AEPowerTile 类层次上挂的
        // Mekanism/CoFH/RotaryCraft 可选接口（不在编译 classpath，报「无法访问」）
        AENetworkProxy proxy = ((IGridProxyable) te).getProxy();
        final IGrid grid;
        try {
            grid = proxy.getGrid();
        } catch (GridAccessException e) {
            return data;
        }

        QuantumControllerRegistry registry = QuantumControllerRegistry.get(world);
        long bucket = world.getTotalWorldTime() / FULL_CACHE_INTERVAL_TICKS;
        pruneFullCache(bucket);
        AnchorKey cacheKey = new AnchorKey(anchorDim, anchorX, anchorY, anchorZ);
        FullCacheEntry cached = FULL_CACHE.get(cacheKey);
        if (cached != null && cached.bucket == bucket
            && cached.revision == registry.getRevision()
            && cached.grid == grid) {
            fullCacheHits++;
            cached.lastAccessBucket = bucket;
            // v1.6.20：缓存与命中返回共享同一对象（装配完成后无服务端修改点，客户端反序列化自建副本）
            return cached.data;
        }
        fullCacheMisses++;
        long assemblyStarted = System.nanoTime();
        data.online = true;

        // 5-6. 总频道、已用频道和量子节点数共享同一份主线程统计快照。
        QuantumNetworkStatsCache.Snapshot stats = QuantumNetworkStatsCache.getOrCompute(cacheKey, world, grid);
        if (stats == null) {
            return data;
        }
        data.totalChannels = stats.totalChannels;
        data.usedChannels = stats.usedChannels;
        data.quantumNodeCount = stats.quantumNodeCount;

        // 7. 能量四项（IEnergyGrid 缓存，AE2 保证该缓存恒存在，仍做 null 防御）
        IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        if (energy != null) {
            data.avgPowerUsage = energy.getAvgPowerUsage();
            data.avgPowerInjection = energy.getAvgPowerInjection();
            data.storedPower = energy.getStoredPower();
            data.maxStoredPower = energy.getMaxStoredPower();
            data.powerInfinite = energy.getHasInfiniteStore();
        }

        // 8. 物品 / 流体 / 源质 存储字节统计（IStorageGrid 实际实现为 GridStorageCache）
        IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        if (storageGrid instanceof GridStorageCache) {
            GridStorageCache storage = (GridStorageCache) storageGrid;
            data.itemBytesUsed = storage.getItemBytesUsed();
            data.itemBytesTotal = storage.getItemBytesTotal();
            data.fluidBytesUsed = storage.getFluidBytesUsed();
            data.fluidBytesTotal = storage.getFluidBytesTotal();
            // 源质方法在部分 AE2 版本可能不存在：反射探测，失败则保持默认 0
            try {
                data.essentiaBytesUsed = storage.getEssentiaBytesUsed();
                data.essentiaBytesTotal = storage.getEssentiaBytesTotal();
            } catch (NoSuchMethodError ignored) {
                // 旧版 AE2 无源质存储：保持 0
            }
        }

        // 9. 设备列表聚合：逐机器类统计数量，图标取该类第一个有效 machineRepresentation
        // （仿 ContainerNetworkStatus 的遍历方式；getMachines 对未知类返回空集而非 null，已核实 Grid.java:214-220）
        int total = 0;
        List<DeviceEntry> aggregated = new ArrayList<>();
        for (Class<? extends IGridHost> machineClass : grid.getMachinesClasses()) {
            IMachineSet machines = grid.getMachines(machineClass);
            if (machines.isEmpty()) {
                continue;
            }
            int count = machines.size();
            total += count;
            ItemStack icon = null;
            for (IGridNode node : machines) {
                IGridBlock gridBlock = node.getGridBlock();
                if (gridBlock == null) {
                    continue;
                }
                ItemStack rep = gridBlock.getMachineRepresentation();
                if (rep != null && rep.getItem() != null) {
                    // 复制防外部修改；stackSize 固定 1，真实数量由 count 字段表达
                    icon = rep.copy();
                    icon.stackSize = 1;
                    break;
                }
            }
            // 无有效图标的类不进入列表（数量仍计入 totalMachines）
            if (icon != null) {
                aggregated.add(new DeviceEntry(icon, count));
            }
        }
        data.totalMachines = total;
        // 按数量降序排序，超出 MAX_ENTRIES 截断（规划 §6 防包体积膨胀）
        aggregated.sort((a, b) -> Integer.compare(b.count, a.count));
        if (aggregated.size() > MAX_ENTRIES) {
            aggregated = new ArrayList<>(aggregated.subList(0, MAX_ENTRIES));
        }
        data.entries.addAll(aggregated);

        // v1.6.20：直接缓存装配结果对象（免 copy）；返回路径与缓存共享同一实例，
        // 装配完成后服务端无修改点（PacketSyncQuantumTerminalData.toBytes 只读）
        FULL_CACHE.put(cacheKey, new FullCacheEntry(grid, bucket, registry.getRevision(), data));
        fullAssemblies++;
        fullAssemblyNanos += System.nanoTime() - assemblyStarted;
        return data;
    }

    /** 清理服务器切换或测试之间的完整快照缓存。 */
    public static void clearCache() {
        FULL_CACHE.clear();
        lastFullCachePruneBucket = Long.MIN_VALUE;
        fullCacheHits = 0L;
        fullCacheMisses = 0L;
        fullAssemblies = 0L;
        fullAssemblyNanos = 0L;
    }

    /** 每秒由 ServerTick 低频输出一次 DEBUG 统计并归零。 */
    public static String consumeDebugStats() {
        String result = "fullHit=" + fullCacheHits
            + ", fullMiss="
            + fullCacheMisses
            + ", fullAssemble="
            + fullAssemblies
            + ", fullAssembleMs="
            + (fullAssemblyNanos / 1000000L);
        fullCacheHits = 0L;
        fullCacheMisses = 0L;
        fullAssemblies = 0L;
        fullAssemblyNanos = 0L;
        return result;
    }

    private static void pruneFullCache(long currentBucket) {
        if (lastFullCachePruneBucket == currentBucket) {
            return;
        }
        lastFullCachePruneBucket = currentBucket;
        Iterator<Map.Entry<AnchorKey, FullCacheEntry>> iterator = FULL_CACHE.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            FullCacheEntry entry = iterator.next()
                .getValue();
            if (entry.lastAccessBucket < currentBucket - FULL_CACHE_RETAIN_BUCKETS) {
                iterator.remove();
            }
        }
    }

    private QuantumNetworkData copy() {
        QuantumNetworkData copy = new QuantumNetworkData();
        copy.online = this.online;
        copy.anchorDim = this.anchorDim;
        copy.anchorX = this.anchorX;
        copy.anchorY = this.anchorY;
        copy.anchorZ = this.anchorZ;
        copy.totalChannels = this.totalChannels;
        copy.channelsInfinite = this.channelsInfinite;
        copy.usedChannels = this.usedChannels;
        copy.quantumNodeCount = this.quantumNodeCount;
        copy.avgPowerUsage = this.avgPowerUsage;
        copy.avgPowerInjection = this.avgPowerInjection;
        copy.storedPower = this.storedPower;
        copy.maxStoredPower = this.maxStoredPower;
        copy.powerInfinite = this.powerInfinite;
        copy.itemBytesUsed = this.itemBytesUsed;
        copy.itemBytesTotal = this.itemBytesTotal;
        copy.fluidBytesUsed = this.fluidBytesUsed;
        copy.fluidBytesTotal = this.fluidBytesTotal;
        copy.essentiaBytesUsed = this.essentiaBytesUsed;
        copy.essentiaBytesTotal = this.essentiaBytesTotal;
        copy.totalMachines = this.totalMachines;
        for (DeviceEntry entry : this.entries) {
            copy.entries.add(new DeviceEntry(entry.icon == null ? null : entry.icon.copy(), entry.count));
        }
        return copy;
    }

    /**
     * 计算网格内所有量子节点桥接连接承载的频道总数（v1.6.8 抽取，供 TileEntityNetworkQuantumNode 复用）。
     * <p>
     * 口径：遍历 {@code grid.getMachines(TileEntityNetworkQuantumNode.class)}，
     * 每节点取其所有连接 usedChannels 的 max，求和。
     * <p>
     * max 口径拓扑上等价于"桥接连接 usedChannels"（桥接连接恒≥邻接连接）。
     *
     * @param grid 锚点控制器所属网格
     * @return 全部量子节点已用频道总和
     */
    public static int computeUsedChannels(IGrid grid) {
        int used = 0;
        for (IGridNode node : grid.getMachines(TileEntityNetworkQuantumNode.class)) {
            int nodeMax = 0;
            for (IGridConnection connection : node.getConnections()) {
                nodeMax = Math.max(nodeMax, connection.getUsedChannels());
            }
            used += nodeMax;
        }
        return used;
    }

    private static final class FullCacheEntry {

        private final IGrid grid;
        private final long bucket;
        private final long revision;
        private final QuantumNetworkData data;
        private long lastAccessBucket;

        private FullCacheEntry(IGrid grid, long bucket, long revision, QuantumNetworkData data) {
            this.grid = grid;
            this.bucket = bucket;
            this.revision = revision;
            this.data = data;
            this.lastAccessBucket = bucket;
        }
    }
}
