package com.miaokatze.gtswn.common.covers;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;

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
    private final Map<Long, Byte> nodes = new HashMap<>();

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
        markDirty();
    }

    /**
     * 出册单个节点（幂等：坐标未册时无效果）。仅在实际移除时 {@code markDirty()}。
     */
    public void unregister(World world, int x, int y, int z) {
        if (this.nodes.remove(Long.valueOf(WirelessNodeIndexCodec.pack(x, y, z))) != null) {
            markDirty();
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
        if (this.nodes.keySet()
            .removeAll(packedPositions)) {
            markDirty();
        }
    }

    /**
     * 当前索引的不可变副本（调用方可安全遍历/持有，写操作不回灌注册表）。
     */
    public Map<Long, Byte> snapshot(World world) {
        return Collections.unmodifiableMap(new HashMap<>(this.nodes));
    }

    // ==================== NBT 持久化（int[2N] positions + byte[N] types，经 Codec 编解码） ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        this.nodes.clear();
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
