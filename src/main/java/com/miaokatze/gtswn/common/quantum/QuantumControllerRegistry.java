package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.core.AEConfig;
import appeng.core.features.AEFeature;
import appeng.tile.networking.TileController;

/**
 * 量子化控制器注册表（WorldSavedData，每世界一份）。
 * <p>
 * 职责（见 plan_20260722152445.md §5.3）：
 * <ul>
 * <li>记录本世界全部「已量子化」的 ME 控制器方块坐标</li>
 * <li>量子化 = 该控制器被 {@code AENetworkProxy.setValidSides} 过滤为
 * 仅允许同伴控制器 / 能量接收器 / 石英纤维朝向面连接（能量交互白名单）</li>
 * <li>perWorldStorage 每维度独立存储，NBT 结构按 §5.3 保留 dim 字段以便未来扩展</li>
 * </ul>
 * <p>
 * 坐标存储：内部用 long 打包（仿 1.8+ BlockPos 位布局），NBT 落盘为 int[N][4] = {dim, x, y, z}。
 * 量子化状态只存世界，不存终端/节点——终端 NBT 仅是绑定指针。
 */
public class QuantumControllerRegistry extends WorldSavedData {

    /** WorldSavedData 注册名（mapName），落盘文件 gtswn_quantum_controllers.dat */
    public static final String DATA_NAME = "gtswn_quantum_controllers";

    /** NBT 键名：int[N][4] 坐标数组 */
    private static final String NBT_CONTROLLERS = "controllers";

    /** 洪泛安全上限：AE2 控制器结构理论最大 7x7x7=343，留余量防异常数据死循环 */
    private static final int FLOOD_LIMIT = 512;

    /** 已量子化控制器坐标集（long 打包值） */
    private final Set<Long> controllers = new HashSet<>();

    /** 运行期结构 revision；不持久化，仅用于让统计缓存感知量子化结构变化。 */
    private long revision;

    /** 全部坐标集快照（懒构建，revision 变化时失效重建，供 tick 巡检复用）。 */
    private Set<Long> snapshot = null;

    /** 快照对应的 revision（-1 = 快照未构建/已失效）。 */
    private long snapshotRevision = -1L;

    /**
     * 本世界维度 ID（运行时字段，不持久化到独立 tag）。
     * 由 {@link #get(World)} 赋值，仅用于 writeToNBT 时按 §5.3 结构填 dim 冗余字段。
     */
    private int dimensionId = 0;

    public QuantumControllerRegistry(String name) {
        super(name);
    }

    /**
     * 获取指定世界的注册表（不存在则创建并挂载到 perWorldStorage）。
     * 模式仿 {@code NetworkInfoDataStore#get(World)}。
     */
    public static QuantumControllerRegistry get(World world) {
        MapStorage storage = world.perWorldStorage;
        QuantumControllerRegistry data = (QuantumControllerRegistry) storage
            .loadData(QuantumControllerRegistry.class, DATA_NAME);
        if (data == null) {
            data = new QuantumControllerRegistry(DATA_NAME);
            storage.setData(DATA_NAME, data);
        }
        data.dimensionId = world.provider.dimensionId;
        return data;
    }

    // ==================== 坐标打包工具（long） ====================
    // 位布局仿 1.8+ BlockPos：x 占高 26bit | y 占中 12bit | z 占低 26bit。
    // 1.7.10 世界边界 ±30,000,000 < 2^25，26bit（含 1 符号位）足够；y ∈ [0,255]，12bit 足够。
    // 负数处理：打包时按掩码取低 N 位（补码含符号位），解包时左移把符号位顶到 long 最高位后算术右移还原。

    /** 打包方块坐标为 long */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
    }

    /** 解包 X（算术右移自动补符号） */
    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    /** 解包 Y（先左移 26 位把符号位顶到最高位，再算术右移 52 位） */
    public static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    /** 解包 Z（先左移 38 位把符号位顶到最高位，再算术右移 38 位） */
    public static int unpackZ(long packed) {
        return (int) ((packed << 38) >> 38);
    }

    // ==================== 入册 / 出册 / 查询 ====================

    /** 入册单个坐标（已存在则无效果）；修改后标记 dirty 触发落盘 */
    public void quantize(int x, int y, int z) {
        if (this.controllers.add(pack(x, y, z))) {
            this.revision++;
            markDirty();
        }
    }

    /** 批量入册（打包坐标集，通常为 floodControllers 的洪泛结果） */
    public void quantizeAll(Set<Long> packedCoords) {
        if (this.controllers.addAll(packedCoords)) {
            this.revision++;
            markDirty();
        }
    }

    /** 出册单个坐标；不存在则无效果 */
    public void dequantize(int x, int y, int z) {
        if (this.controllers.remove(pack(x, y, z))) {
            this.revision++;
            markDirty();
        }
    }

    /** 出册打包坐标（供 tick 巡检遍历打包值时直接使用） */
    public void dequantize(long packed) {
        if (this.controllers.remove(packed)) {
            this.revision++;
            markDirty();
        }
    }

    /** 获取本次运行期结构 revision（不参与存档）。 */
    public long getRevision() {
        return this.revision;
    }

    /** 查询指定坐标是否已量子化 */
    public boolean isQuantized(int x, int y, int z) {
        return this.controllers.contains(pack(x, y, z));
    }

    /** 查询打包坐标是否已量子化 */
    public boolean isQuantized(long packed) {
        return this.controllers.contains(packed);
    }

    /**
     * 获取全部已量子化坐标的快照（打包值集合）。
     * <p>
     * 返回副本以避免遍历期间被修改（tick 巡检中可能并发出册）。
     * v1.6.20：revision 未变化时复用上次拷贝，避免每 20t 每世界重复分配；
     * revision 变化的路径（quantize / quantizeAll / dequantize / readFromNBT）全部递增，
     * 快照惰性重建——巡检循环内的自修改只影响下一轮，与逐点拷贝语义等价。
     */
    public Set<Long> getAll() {
        if (this.snapshot == null || this.snapshotRevision != this.revision) {
            this.snapshot = new HashSet<>(this.controllers);
            this.snapshotRevision = this.revision;
        }
        return this.snapshot;
    }

    // ==================== 洪泛与频道公式（静态工具） ====================

    /**
     * 从起点 BFS 洪泛 6 邻接的全部 ME 控制器方块（D10 整结构语义）。
     *
     * @param world 世界
     * @param x     起点 X（本身必须是 TileController，否则返回空集）
     * @param y     起点 Y
     * @param z     起点 Z
     * @return 整结构控制器坐标集（打包值，含起点）
     */
    public static Set<Long> floodControllers(World world, int x, int y, int z) {
        Set<Long> visited = new HashSet<>();
        TileEntity start = world.getTileEntity(x, y, z);
        if (!(start instanceof TileController)) {
            return visited;
        }
        Queue<Long> queue = new ArrayDeque<>();
        long startPacked = pack(x, y, z);
        visited.add(startPacked);
        queue.add(startPacked);
        while (!queue.isEmpty() && visited.size() < FLOOD_LIMIT) {
            long current = queue.poll();
            int cx = unpackX(current);
            int cy = unpackY(current);
            int cz = unpackZ(current);
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                int nx = cx + d.offsetX;
                int ny = cy + d.offsetY;
                int nz = cz + d.offsetZ;
                long np = pack(nx, ny, nz);
                if (visited.contains(np)) {
                    continue;
                }
                TileEntity te = world.getTileEntity(nx, ny, nz);
                if (te instanceof TileController) {
                    visited.add(np);
                    queue.add(np);
                }
            }
        }
        return visited;
    }

    /**
     * Returns whether AE2 has disabled channel accounting globally.
     *
     * AE2's NetworkFeatures.Channels=false setting is represented by the absence
     * of AEFeature.Channels from its feature flags. If AE2 has not finished
     * initializing, retain the finite-channel behavior.
     */
    public static boolean isChannelsInfinite() {
        return AEConfig.instance != null && !AEConfig.instance.isFeatureEnabled(AEFeature.Channels);
    }

    /**
     * 计算结构总频道数（规划 §1 需求 2 / §6 公式）。
     * <p>
     * 总频道 =（控制器数量 × 6 − 与其他控制器相连的面数）× 32
     * = 控制器暴露于非控制器面的面数 × 32。
     * 实现：对每块统计 6 邻接同属集合的次数 sharedFaces——每个相邻关系恰被计 2 次
     * （每侧各 1 次），与公式中「相连面数」语义一致。
     *
     * @param structure 洪泛得到的结构坐标集（打包值）
     * @return 总频道数
     */
    public static int computeTotalChannels(Set<Long> structure) {
        int n = structure.size();
        int sharedFaces = 0;
        for (long packed : structure) {
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                if (structure.contains(pack(x + d.offsetX, y + d.offsetY, z + d.offsetZ))) {
                    sharedFaces++;
                }
            }
        }
        return (n * 6 - sharedFaces) * 32;
    }

    // ==================== NBT 持久化（§5.3：int[N][4] = {dim, x, y, z}） ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        this.controllers.clear();
        this.revision++;
        if (!tag.hasKey(NBT_CONTROLLERS)) {
            return;
        }
        // 扁平 int 数组：每 4 个连续 int 为一条 {dim, x, y, z}
        // （1.7.10 NBTTagList 无 get(i)/getIntArray 访问 API，故弃用 int[N][4] 的列表嵌套写法）
        int[] flat = tag.getIntArray(NBT_CONTROLLERS);
        for (int i = 0; i + 3 < flat.length; i += 4) {
            // perWorldStorage 已按维度隔离，dim（flat[i]）仅作冗余记录，读取时忽略
            this.controllers.add(pack(flat[i + 1], flat[i + 2], flat[i + 3]));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        // dim 字段按 §5.3 结构保留（perWorldStorage 已按维度隔离存储，本字段为冗余信息）
        int[] flat = new int[this.controllers.size() * 4];
        int i = 0;
        for (long packed : this.controllers) {
            flat[i++] = this.dimensionId;
            flat[i++] = unpackX(packed);
            flat[i++] = unpackY(packed);
            flat[i++] = unpackZ(packed);
        }
        tag.setIntArray(NBT_CONTROLLERS, flat);
    }
}
