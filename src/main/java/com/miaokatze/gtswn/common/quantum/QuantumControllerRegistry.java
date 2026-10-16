package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.testworld.TestWorldProbes;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes.Type;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.SavedDataUtil;

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

    private final Map<Long, String> identities = new HashMap<>();
    private final Map<Long, TileEntity> loadedTiles = new HashMap<>();
    private static final int ANCHOR_SCHEMA = 1;
    private final Map<Long, String> memberGenerations = new HashMap<>();
    private final Map<String, CanonicalAnchor> canonicalAnchors = new HashMap<>();
    private final Map<String, Integer> generationCounts = new HashMap<>();
    private final Map<Long, String> legacyAnchorAliases = new HashMap<>();
    private World world;

    /** 运行期结构 revision；不持久化，仅用于让统计缓存感知量子化结构变化。 */
    private long revision;

    /** 全部坐标集快照（懒构建，revision 变化时失效重建，供 tick 巡检复用）。 */
    private Set<Long> snapshot = null;

    /** 快照对应的 revision（-1 = 快照未构建/已失效）。 */
    private long snapshotRevision = -1L;

    /** Each saved graph component shares one immutable membership snapshot until the revision changes. */
    private final Map<Long, Set<Long>> structureByMember = new HashMap<>();

    private long structureRevision = -1L;

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
     * 模板经 {@link SavedDataUtil#loadOrCreate} 单源化（O2-15，原三份逐字符相同模板之一）。
     */
    public static QuantumControllerRegistry get(World world) {
        QuantumControllerRegistry data = SavedDataUtil
            .loadOrCreate(world, DATA_NAME, QuantumControllerRegistry.class, QuantumControllerRegistry::new);
        data.dimensionId = world.provider.dimensionId;
        data.world = world;
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
        long position = pack(x, y, z);
        quantizeAll(Collections.singleton(position), position);
    }

    /** 批量入册（打包坐标集，通常为 floodControllers 的洪泛结果） */
    public void quantizeAll(Set<Long> packedCoords) {
        Set<Long> pending = new HashSet<>(packedCoords);
        while (!pending.isEmpty()) {
            long first = pending.iterator()
                .next();
            Set<Long> component = coordinateComponent(packedCoords, first);
            pending.removeAll(component);
            quantizeAll(component, first);
        }
    }

    public boolean canExtendStructure(Set<Long> members) {
        return inheritedGeneration(members) != null;
    }

    /** Empty means a new structure; null means two existing generations would be joined. */
    private String inheritedGeneration(Set<Long> members) {
        String inherited = "";
        for (long member : members) {
            for (int side = -1; side < ForgeDirection.VALID_DIRECTIONS.length; side++) {
                long position = member;
                if (side >= 0) {
                    ForgeDirection direction = ForgeDirection.VALID_DIRECTIONS[side];
                    position = pack(
                        unpackX(member) + direction.offsetX,
                        unpackY(member) + direction.offsetY,
                        unpackZ(member) + direction.offsetZ);
                }
                if (!controllers.contains(position)) continue;
                String generation = memberGenerations.get(position);
                if (generation == null || (!inherited.isEmpty() && !inherited.equals(generation))) return null;
                inherited = generation;
            }
        }
        return inherited;
    }

    public boolean quantizeAll(Set<Long> packedCoords, long firstClick) {
        if (packedCoords.isEmpty() || !packedCoords.contains(firstClick)) return false;
        String generation = inheritedGeneration(packedCoords);
        if (generation == null) return false;
        if (generation.isEmpty()) generation = UUID.randomUUID()
            .toString();
        return quantizeAs(packedCoords, firstClick, generation, false);
    }

    /** Explicit migration is the sole operation allowed to move a live generation's canonical position. */
    public boolean quantizeTransferred(Set<Long> members, long firstClick, String generation) {
        if (members.isEmpty() || !members.contains(firstClick) || generation == null || generation.isEmpty())
            return false;
        for (long member : members) if (controllers.contains(member)) return false;
        String inherited = inheritedGeneration(members);
        if (inherited == null || !inherited.isEmpty()) return false;
        return quantizeAs(members, firstClick, generation, true);
    }

    private boolean quantizeAs(Set<Long> packedCoords, long firstClick, String generation, boolean move) {
        // Expansion cannot adopt a replacement or an in-place identity mutation before the periodic sweep.
        // Check the old evidence before mutating any membership and never refresh it while adding companions.
        for (long packed : packedCoords)
            if (controllers.contains(packed) && world != null && !isCurrentController(world, packed)) return false;
        Set<Long> added = new HashSet<>(packedCoords);
        added.removeAll(this.controllers);
        if (this.controllers.addAll(packedCoords)) {
            if (move || !canonicalAnchors.containsKey(generation))
                canonicalAnchors.put(generation, new CanonicalAnchor(firstClick, generation));
            for (long packed : packedCoords) {
                if (!memberGenerations.containsKey(packed)) {
                    memberGenerations.put(packed, generation);
                    generationCounts.put(generation, generationCounts.getOrDefault(generation, 0) + 1);
                }
            }
            for (long packed : added) rememberIdentity(packed);
            this.revision++;
            markDirty();
            if (DebugTestGate.probesActive()) for (long packed : added) TestWorldProbes
                .at(world, unpackX(packed), unpackY(packed), unpackZ(packed), Type.QUANTUM_CONTROLLER_ADD, "");
        }
        return true;
    }

    public static final class CanonicalAnchor {

        public final long position;
        public final String generation;

        private CanonicalAnchor(long position, String generation) {
            this.position = position;
            this.generation = generation;
        }
    }

    public CanonicalAnchor canonicalAnchor(long member) {
        String generation = memberGenerations.get(member);
        return controllers.contains(member) ? canonicalGeneration(generation) : null;
    }

    public boolean sameGeneration(long first, long second) {
        String generation = memberGenerations.get(first);
        return generation != null && generation.equals(memberGenerations.get(second));
    }

    /** Only coordinates captured from the pre-generation save may adopt a typed migration without a generation. */
    public Set<Long> legacyMembers(Set<Long> members, String generation) {
        Set<Long> result = new HashSet<>();
        for (long member : members) if (generation.equals(legacyAnchorAliases.get(member))) result.add(member);
        return result;
    }

    /** Coalesce active member aliases without changing the caller's actual native grid guard. */
    public AnchorKey canonicalKey(AnchorKey key) {
        CanonicalAnchor anchor = canonicalAnchor(pack(key.getX(), key.getY(), key.getZ()));
        if (anchor == null || anchor.position == pack(key.getX(), key.getY(), key.getZ())) return key;
        return new AnchorKey(
            key.getDimension(),
            unpackX(anchor.position),
            unpackY(anchor.position),
            unpackZ(anchor.position));
    }

    public CanonicalAnchor resolveAnchor(long member, String expectedGeneration) {
        String generation = expectedGeneration == null || expectedGeneration.isEmpty() ? legacyAnchorAliases.get(member)
            : expectedGeneration;
        if (generation == null) return null;
        if (expectedGeneration != null && !expectedGeneration.isEmpty()
            && !generation.equals(memberGenerations.get(member))) return null;
        return canonicalGeneration(generation);
    }

    private CanonicalAnchor canonicalGeneration(String generation) {
        CanonicalAnchor anchor = canonicalAnchors.get(generation);
        return anchor != null && controllers.contains(anchor.position)
            && generation.equals(memberGenerations.get(anchor.position)) ? anchor : null;
    }

    /** 出册单个坐标；不存在则无效果 */
    public void dequantize(int x, int y, int z) {
        dequantize(pack(x, y, z));
    }

    /** 出册打包坐标（供 tick 巡检遍历打包值时直接使用） */
    public void dequantize(long packed) {
        if (this.controllers.remove(packed)) {
            if (world != null && !world.isRemote) QuantumNetworkSecurityRegistry.get(world)
                .remove(Collections.singleton(packed));
            identities.remove(packed);
            loadedTiles.remove(packed);
            String generation = memberGenerations.remove(packed);
            if (generation != null) {
                int remaining = generationCounts.getOrDefault(generation, 1) - 1;
                if (remaining <= 0) {
                    generationCounts.remove(generation);
                    canonicalAnchors.remove(generation);
                } else generationCounts.put(generation, remaining);
            }
            this.revision++;
            markDirty();
            if (DebugTestGate.probesActive()) TestWorldProbes
                .at(world, unpackX(packed), unpackY(packed), unpackZ(packed), Type.QUANTUM_CONTROLLER_REMOVE, "");
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
     * <p>
     * B2-13：快照冻结为 {@link Collections#unmodifiableSet}（与 QuantumNetworkStatsCache /
     * QuantumNetworkData 缓存口径对齐）——复用窗口内任何持引用的写入都会污染共享实例，
     * 冻结后此类误用立即抛异常而非静默漂移；调用方只读遍历，零改动。
     */
    public Set<Long> getAll() {
        if (this.snapshot == null || this.snapshotRevision != this.revision) {
            this.snapshot = Collections.unmodifiableSet(new HashSet<>(this.controllers));
            this.snapshotRevision = this.revision;
        }
        return this.snapshot;
    }

    /**
     * Use the saved coordinate graph, including the missing member and unloaded chunks.
     * All members share an immutable snapshot until the registry's structural revision changes.
     */
    public Set<Long> registeredStructure(long start) {
        if (structureRevision != revision) {
            structureByMember.clear();
            structureRevision = revision;
        }
        Set<Long> cached = structureByMember.get(start);
        if (cached != null) return cached;
        if (!controllers.contains(start)) return Collections.emptySet();
        Set<Long> result = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        result.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long current = queue.remove();
            for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
                long next = pack(
                    unpackX(current) + direction.offsetX,
                    unpackY(current) + direction.offsetY,
                    unpackZ(current) + direction.offsetZ);
                if (controllers.contains(next) && result.add(next)) queue.add(next);
            }
        }
        // Never mutate a published component: removal and migration retain the old snapshot.
        Set<Long> component = Collections.unmodifiableSet(result);
        for (long member : result) structureByMember.put(member, component);
        return component;
    }

    private void rememberIdentity(long position) {
        if (world == null || !world.blockExists(unpackX(position), unpackY(position), unpackZ(position))) return;
        TileEntity tile = world.getTileEntity(unpackX(position), unpackY(position), unpackZ(position));
        if (!(tile instanceof TileController) || !(tile instanceof QuantumControllerIdentity)) return;
        QuantumControllerIdentity identity = (QuantumControllerIdentity) tile;
        if (identity.gtswn$getControllerIdentity()
            .isEmpty()) {
            identity.gtswn$setControllerIdentity(
                UUID.randomUUID()
                    .toString());
            tile.markDirty();
        }
        identities.put(position, identity.gtswn$getControllerIdentity());
        loadedTiles.put(position, tile);
    }

    /** A missing identity in an old save is adopted once, without loading any chunks. */
    public boolean isCurrentController(World world, long position) {
        if (!controllers.contains(position)) return false;
        int x = unpackX(position), y = unpackY(position), z = unpackZ(position);
        if (!world.blockExists(x, y, z)) {
            loadedTiles.remove(position);
            return true;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileController) || tile.isInvalid()) return false;
        TileEntity previous = loadedTiles.get(position);
        if (previous != null && previous != tile) return false;
        String expected = identities.get(position);
        if (expected == null) {
            this.world = world;
            rememberIdentity(position);
            markDirty();
            return true;
        }
        if (!(tile instanceof QuantumControllerIdentity)
            || !expected.equals(((QuantumControllerIdentity) tile).gtswn$getControllerIdentity())) return false;
        loadedTiles.put(position, tile);
        return true;
    }

    public void unloadTile(TileEntity tile) {
        long position = pack(tile.xCoord, tile.yCoord, tile.zCoord);
        if (loadedTiles.get(position) == tile) loadedTiles.remove(position);
    }

    /** Late invalidation of an old object must not cancel a newly registered replacement. */
    public boolean ownsController(TileEntity tile) {
        long position = pack(tile.xCoord, tile.yCoord, tile.zCoord);
        if (!controllers.contains(position)) return false;
        TileEntity remembered = loadedTiles.get(position);
        if (remembered != null) return remembered == tile;
        String identity = identities.get(position);
        if (identity != null) return tile instanceof QuantumControllerIdentity
            && identity.equals(((QuantumControllerIdentity) tile).gtswn$getControllerIdentity());
        return world != null && world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
            && world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    public void unload() {
        loadedTiles.clear();
    }

    private static Set<Long> coordinateComponent(Set<Long> coordinates, long start) {
        Set<Long> members = new HashSet<>();
        Queue<Long> pending = new ArrayDeque<>();
        members.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            long current = pending.remove();
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                long next = pack(
                    unpackX(current) + side.offsetX,
                    unpackY(current) + side.offsetY,
                    unpackZ(current) + side.offsetZ);
                if (coordinates.contains(next) && members.add(next)) pending.add(next);
            }
        }
        return members;
    }

    private void adoptLegacyAnchors() {
        Set<Long> pending = new HashSet<>(controllers);
        while (!pending.isEmpty()) {
            long first = pending.iterator()
                .next();
            Set<Long> members = coordinateComponent(controllers, first);
            pending.removeAll(members);
            String generation = UUID.randomUUID()
                .toString();
            canonicalAnchors.put(generation, new CanonicalAnchor(first, generation));
            generationCounts.put(generation, members.size());
            for (long member : members) {
                memberGenerations.put(member, generation);
                legacyAnchorAliases.put(member, generation);
            }
        }
        markDirty();
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
        if (!world.blockExists(x, y, z)) return visited;
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
                if (!world.blockExists(nx, ny, nz)) continue;
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
        this.identities.clear();
        this.loadedTiles.clear();
        memberGenerations.clear();
        canonicalAnchors.clear();
        generationCounts.clear();
        legacyAnchorAliases.clear();
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
        NBTTagList list = tag.getTagList("identities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound value = list.getCompoundTagAt(i);
            long position = value.getLong("pos");
            String identity = value.getString("identity");
            if (controllers.contains(position) && !identity.isEmpty()) identities.put(position, identity);
        }
        if (tag.getInteger("anchorSchema") < ANCHOR_SCHEMA) {
            adoptLegacyAnchors();
        } else {
            NBTTagList members = tag.getTagList("memberGenerations", 10);
            for (int i = 0; i < members.tagCount(); i++) {
                NBTTagCompound entry = members.getCompoundTagAt(i);
                long position = entry.getLong("pos");
                String generation = entry.getString("generation");
                if (controllers.contains(position) && !generation.isEmpty()) {
                    memberGenerations.put(position, generation);
                    generationCounts.put(generation, generationCounts.getOrDefault(generation, 0) + 1);
                }
            }
            NBTTagList anchors = tag.getTagList("canonicalAnchors", 10);
            for (int i = 0; i < anchors.tagCount(); i++) {
                NBTTagCompound entry = anchors.getCompoundTagAt(i);
                String generation = entry.getString("generation");
                long position = entry.getLong("pos");
                if (generation.equals(memberGenerations.get(position)))
                    canonicalAnchors.put(generation, new CanonicalAnchor(position, generation));
            }
            NBTTagList aliases = tag.getTagList("legacyAnchorAliases", 10);
            for (int i = 0; i < aliases.tagCount(); i++) {
                NBTTagCompound entry = aliases.getCompoundTagAt(i);
                if (!entry.getString("generation")
                    .isEmpty()) legacyAnchorAliases.put(entry.getLong("pos"), entry.getString("generation"));
            }
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
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, String> entry : identities.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setLong("pos", entry.getKey());
            value.setString("identity", entry.getValue());
            list.appendTag(value);
        }
        tag.setTag("identities", list);
        tag.setInteger("anchorSchema", ANCHOR_SCHEMA);
        tag.setTag("memberGenerations", generationTags(memberGenerations));
        tag.setTag("legacyAnchorAliases", generationTags(legacyAnchorAliases));
        NBTTagList anchors = new NBTTagList();
        for (Map.Entry<String, CanonicalAnchor> entry : canonicalAnchors.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setString("generation", entry.getKey());
            value.setLong("pos", entry.getValue().position);
            anchors.appendTag(value);
        }
        tag.setTag("canonicalAnchors", anchors);
    }

    private static NBTTagList generationTags(Map<Long, String> values) {
        NBTTagList result = new NBTTagList();
        for (Map.Entry<Long, String> entry : values.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setLong("pos", entry.getKey());
            value.setString("generation", entry.getValue());
            result.appendTag(value);
        }
        return result;
    }
}
