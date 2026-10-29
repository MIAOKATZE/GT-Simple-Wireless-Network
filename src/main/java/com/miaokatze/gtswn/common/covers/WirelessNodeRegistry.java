package com.miaokatze.gtswn.common.covers;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

import com.miaokatze.gtswn.common.testworld.TestWorldProbes;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes.Type;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.SavedDataUtil;

/**
 * 无线节点注册表（WorldSavedData，每世界一份）。
 * <p>
 * 落地方式仿 {@code common/quantum/QuantumControllerRegistry}（WorldSavedData +
 * {@link SavedDataUtil#loadOrCreate} 挂载 {@code world.perWorldStorage}，每维度独立落盘——
 * 维度隔离由存储侧完成，条目不冗余存 dim）。内部索引为
 * {@code Map<Long, Byte>}（打包坐标 → 节点类型），双数组序列化编解码统一走
 * {@link WirelessNodeIndexCodec}（decode 坏档防御：长度不齐取短、未知 type 丢弃、空输入返回空）。
 * <p>
 * NBT 结构：{@code positions} int[2N] = 打包坐标 {高32位, 低32位} 扁平数组 +
 * {@code types} byte[N]。说明：1.7.10 无 NBTTagLongArray（NBTBase 类型表止于 11=int array，
 * NBTTagCompound 亦无 long 数组 API），故 Codec 层的 {@code long[]} 在落盘层以 int[2N] 承载，
 * 读写时无损还原，Codec 契约不变。
 * <p>
 * markDirty 时机与先例一致：register / unregister / prune 仅在索引确有变更时调用，
 * readFromNBT 不调用（读档不是变更）。
 */
public class WirelessNodeRegistry extends WorldSavedData {

    /** WorldSavedData 注册名（mapName），落盘文件 gtswn_wireless_nodes.dat */
    public static final String DATA_NAME = "gtswn_wireless_nodes";

    /** 节点类型：能量无线（接受端），值域单一来源为 {@link WirelessNodeIndexCodec#TYPE_ENERGY} */
    public static final byte TYPE_ENERGY = WirelessNodeIndexCodec.TYPE_ENERGY;

    /** 节点类型：动力无线（dynamo 输出端），值域单一来源为 {@link WirelessNodeIndexCodec#TYPE_DYNAMO} */
    public static final byte TYPE_DYNAMO = WirelessNodeIndexCodec.TYPE_DYNAMO;

    /** NBT 键名：int[2N] 打包坐标扁平数组 {高32位, 低32位} */
    private static final String NBT_POSITIONS = "positions";

    /** NBT 键名：byte[N] 节点类型数组 */
    private static final String NBT_TYPES = "types";

    /** 节点索引（打包坐标 → 类型） */
    private final NavigableMap<Long, Byte> nodes = new TreeMap<>();
    /** Runtime-only spatial index; the persisted schema remains the canonical positions/types arrays. */
    private final Map<Long, NavigableMap<Long, Byte>> chunkNodes = new HashMap<>();
    private Map<Long, Byte> cachedSnapshot;

    public WirelessNodeRegistry(String name) {
        super(name);
    }

    /**
     * 获取指定世界的注册表（不存在则创建并挂载到 perWorldStorage）。
     * 模板经 {@link SavedDataUtil#loadOrCreate} 单源化（与 QuantumControllerRegistry 同一模板）。
     */
    public static WirelessNodeRegistry get(World world) {
        return SavedDataUtil.loadOrCreate(world, DATA_NAME, WirelessNodeRegistry.class, WirelessNodeRegistry::new);
    }

    // ==================== 入册 / 出册 / 修剪 / 快照 ====================

    /**
     * 入册单个节点（幂等：坐标已册且类型相同时无效果）。未知 type 视为非法调用，直接忽略。
     * 仅在索引确有变更时 {@code markDirty()}。
     *
     * @param world 目标世界（维度隔离由 perWorldStorage 承担，此参数保留以对齐 API 形态）
     */
    public void register(World world, int x, int y, int z, byte type) {
        if (!WirelessNodeIndexCodec.isKnownType(type)) {
            return;
        }
        Long packed = WirelessNodeIndexCodec.pack(x, y, z);
        Byte existing = this.nodes.get(packed);
        if (existing != null && existing.byteValue() == type) {
            return;
        }
        this.nodes.put(packed, type);
        index(packed, type);
        cachedSnapshot = null;
        markDirty();
        if (DebugTestGate.probesActive())
            TestWorldProbes.at(world, x, y, z, Type.WIRELESS_NODE_ADD, "nodeType=" + type);
    }

    /**
     * 出册单个节点（幂等：坐标未册时无效果）。仅在实际移除时 {@code markDirty()}。
     */
    public void unregister(World world, int x, int y, int z) {
        long packed = WirelessNodeIndexCodec.pack(x, y, z);
        if (this.nodes.remove(packed) != null) {
            unindex(packed);
            cachedSnapshot = null;
            markDirty();
            if (DebugTestGate.probesActive()) TestWorldProbes.at(world, x, y, z, Type.WIRELESS_NODE_REMOVE, "");
        }
    }

    /**
     * 批量修剪（通常回传 {@link WirelessNodeIndexCodec.RadiusSelection#pruned}）。
     * 整批最多一次 {@code markDirty()}；null / 空集合为无操作。
     */
    public void prune(World world, Collection<Long> packedPositions) {
        if (packedPositions == null || packedPositions.isEmpty()) {
            return;
        }
        Set<Long> removed = null;
        if (DebugTestGate.probesActive()) {
            removed = new HashSet<>(packedPositions);
            removed.retainAll(this.nodes.keySet());
        }
        boolean changed = false;
        for (Long packed : packedPositions) {
            if (packed != null && this.nodes.remove(packed) != null) {
                unindex(packed);
                changed = true;
            }
        }
        if (changed) {
            cachedSnapshot = null;
            markDirty();
            if (removed != null) for (long packed : removed) TestWorldProbes.at(
                world,
                WirelessNodeIndexCodec.unpackX(packed),
                WirelessNodeIndexCodec.unpackY(packed),
                WirelessNodeIndexCodec.unpackZ(packed),
                Type.WIRELESS_NODE_PRUNE,
                "");
        }
    }

    /**
     * 当前内容版本的共享不可变副本；有效变更后创建新副本，已返回的旧副本保持不变。
     */
    public Map<Long, Byte> snapshot(World world) {
        if (cachedSnapshot == null) cachedSnapshot = Collections.unmodifiableMap(new HashMap<>(this.nodes));
        return cachedSnapshot;
    }

    /**
     * Detached candidate snapshot for a closed X interval. Packed coordinates sort by signed X first;
     * callers still apply the exact radius and native probe. Copy before probing: a probe may repair the index.
     */
    public Map<Long, Byte> snapshotInXRange(int minX, int maxX) {
        long lowerX = Math.max(-33_554_432L, (long) minX);
        long upperX = Math.min(33_554_431L, (long) maxX);
        if (lowerX > upperX) return Collections.emptyMap();
        long lower = lowerX << 38;
        long upper = (upperX << 38) | ((1L << 38) - 1);
        if (nodes.isEmpty()) return Collections.emptyMap();
        // A dimension concentrated on one X slab should retain the existing shared full snapshot.
        if (lower <= nodes.firstKey() && upper >= nodes.lastKey()) return snapshot(null);
        return Collections.unmodifiableMap(new HashMap<>(nodes.subMap(lower, true, upper, true)));
    }

    /** Main-thread, short-lived read view for bounded dashboard queries; never retain across ticks. */
    public Map<Long, Byte> readView() {
        return Collections.unmodifiableMap(nodes);
    }

    /**
     * Main-thread read-only X slab, without allocating a copy of every candidate. Like {@link #readView()},
     * this view must not be retained across ticks or used while a caller repairs/mutates the index.
     */
    public Map<Long, Byte> readViewInXRange(int minX, int maxX) {
        long lowerX = Math.max(-33_554_432L, (long) minX);
        long upperX = Math.min(33_554_431L, (long) maxX);
        if (lowerX > upperX) return Collections.emptyMap();
        long lower = lowerX << 38;
        long upper = (upperX << 38) | ((1L << 38) - 1);
        return Collections.unmodifiableMap(nodes.subMap(lower, true, upper, true));
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static long positionChunk(long packed) {
        return chunkKey(WirelessNodeIndexCodec.unpackX(packed) >> 4, WirelessNodeIndexCodec.unpackZ(packed) >> 4);
    }

    private void index(long packed, byte type) {
        chunkNodes.computeIfAbsent(positionChunk(packed), ignored -> new TreeMap<>()).put(packed, type);
    }

    private void unindex(long packed) {
        long chunk = positionChunk(packed);
        NavigableMap<Long, Byte> bucket = chunkNodes.get(chunk);
        if (bucket == null) return;
        bucket.remove(packed);
        if (bucket.isEmpty()) chunkNodes.remove(chunk);
    }

    /**
     * Lazy main-thread view of a closed chunk rectangle. At most 64 GT cells (9 chunks each), with no
     * dimension-wide scan/copy and no off-view Z prefix consuming the caller's node budget. Both iteration
     * and entries are read-only; do not retain the view across ticks or mutate the registry while reading.
     */
    public Iterable<Map.Entry<Long, Byte>> readViewInChunkRectangle(int minX, int minZ, int maxX, int maxZ) {
        long width = (long) maxX - minX + 1, height = (long) maxZ - minZ + 1;
        if (width <= 0 || height <= 0 || width > 576 || height > 576 || width * height > 576
            || minX < -2_097_152 || minZ < -2_097_152 || maxX > 2_097_151 || maxZ > 2_097_151)
            return Collections.emptyList();
        return () -> new Iterator<Map.Entry<Long, Byte>>() {

            private int x = minX, z = minZ;
            private boolean exhausted;
            private Iterator<Map.Entry<Long, Byte>> bucket = Collections.<Map.Entry<Long, Byte>>emptyList().iterator();

            @Override
            public boolean hasNext() {
                while (!bucket.hasNext() && !exhausted) {
                    NavigableMap<Long, Byte> next = chunkNodes.get(chunkKey(x, z));
                    if (z < maxZ) z++;
                    else if (x < maxX) {
                        x++;
                        z = minZ;
                    } else exhausted = true;
                    bucket = next == null ? Collections.<Map.Entry<Long, Byte>>emptyList().iterator()
                        : Collections.unmodifiableMap(next).entrySet().iterator();
                }
                return bucket.hasNext();
            }

            @Override
            public Map.Entry<Long, Byte> next() {
                if (!hasNext()) throw new NoSuchElementException();
                return bucket.next();
            }
        };
    }

    // ==================== NBT 持久化（int[2N] positions + byte[N] types，经 Codec 编解码） ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        cachedSnapshot = null;
        this.nodes.clear();
        this.chunkNodes.clear();
        if (!tag.hasKey(NBT_POSITIONS) || !tag.hasKey(NBT_TYPES)) {
            return;
        }
        // 1.7.10 无 long 数组 NBT：int[2N] {hi,lo} 扁平数组还原为 long[]，坏档防御交 Codec
        int[] flat = tag.getIntArray(NBT_POSITIONS);
        long[] positions = new long[flat.length / 2];
        for (int i = 0; i < positions.length; i++) {
            positions[i] = ((long) flat[2 * i] << 32) | (flat[2 * i + 1] & 0xFFFFFFFFL);
        }
        this.nodes.putAll(WirelessNodeIndexCodec.decode(positions, tag.getByteArray(NBT_TYPES)));
        for (Map.Entry<Long, Byte> entry : nodes.entrySet()) index(entry.getKey(), entry.getValue());
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        // encodePositions / encodeTypes 对同一 Map 以同一升序迭代 + 同一 type 过滤输出，下标一一对应
        long[] positions = WirelessNodeIndexCodec.encodePositions(this.nodes);
        byte[] types = WirelessNodeIndexCodec.encodeTypes(this.nodes);
        int[] flat = new int[positions.length * 2];
        for (int i = 0; i < positions.length; i++) {
            flat[2 * i] = (int) (positions[i] >> 32);
            flat[2 * i + 1] = (int) positions[i];
        }
        tag.setIntArray(NBT_POSITIONS, flat);
        tag.setByteArray(NBT_TYPES, types);
    }
}
