package com.miaokatze.gtswn.common.covers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 无线节点索引编解码与半径选择（纯逻辑类，零 Minecraft / GregTech 依赖，保证单测不加载 MC 类）。
 * <p>
 * 职责：
 * <ul>
 * <li>坐标打包/解包 long：位布局与既有先例 {@code common/quantum/QuantumControllerRegistry} 完全一致
 * （x 高 26bit | y 中 12bit | z 低 26bit，仿 1.8+ BlockPos）</li>
 * <li>{@code Map<Long,Byte>}（打包坐标 → 节点类型）与 {@code (long[] positions, byte[] types)} 双数组互转，
 * decode 全程防御（长度不齐取短、未知 type 丢弃、空/异常输入返回空 Map，不抛异常）</li>
 * <li>半径选择 {@link #selectWithinRadius}：distance² ≤ radiusSq 闭边界，按距离升序排序并截断 limit，
 * 探测三态 {@link ProbeResult} 决定入选 / 修剪 / 跳过</li>
 * </ul>
 * <p>
 * 类型常量 {@link #TYPE_ENERGY}=0 / {@link #TYPE_DYNAMO}=1 为编解码两侧的合法值域单一来源。
 */
public final class WirelessNodeIndexCodec {

    /** 节点类型：能量无线（接受端） */
    public static final byte TYPE_ENERGY = 0;

    /** 节点类型：动力无线（dynamo 输出端） */
    public static final byte TYPE_DYNAMO = 1;

    private WirelessNodeIndexCodec() {}

    // ==================== 坐标打包工具（long，布局同 QuantumControllerRegistry） ====================
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

    /** 两打包坐标的欧氏距离平方（分量差 ≤ 2^26，平方和远小于 long 溢出界，全程 long 运算） */
    public static long distanceSq(long packedA, long packedB) {
        long dx = unpackX(packedA) - unpackX(packedB);
        long dy = unpackY(packedA) - unpackY(packedB);
        long dz = unpackZ(packedA) - unpackZ(packedB);
        return dx * dx + dy * dy + dz * dz;
    }

    /** 该类型字节是否为合法节点类型（TYPE_ENERGY / TYPE_DYNAMO） */
    public static boolean isKnownType(byte type) {
        return type == TYPE_ENERGY || type == TYPE_DYNAMO;
    }

    // ==================== Map <-> 双数组编解码 ====================

    /**
     * 编码打包坐标数组：按打包坐标升序输出（NBT 落盘字节稳定，重复保存 diff 最小化）。
     * null / 空 / 含未知 type 或空键空值条目均跳过，不抛异常；返回长度 ≥ 0 数组。
     */
    public static long[] encodePositions(Map<Long, Byte> index) {
        List<Long> keys = sortedKnownKeys(index);
        long[] positions = new long[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            positions[i] = keys.get(i);
        }
        return positions;
    }

    /**
     * 编码类型数组：与 {@link #encodePositions} 对同一 Map 输出，下标一一对应（同一升序迭代 + 同一过滤）。
     */
    public static byte[] encodeTypes(Map<Long, Byte> index) {
        List<Long> keys = sortedKnownKeys(index);
        byte[] types = new byte[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            types[i] = index.get(keys.get(i));
        }
        return types;
    }

    /**
     * 防御解码双数组为索引 Map（调用方持有可变副本）。
     * <ul>
     * <li>任一数组为 null / 空 → 返回空 Map</li>
     * <li>两数组长度不齐 → 取短者，多余尾段丢弃</li>
     * <li>未知 type（非 0/1）→ 丢弃该条</li>
     * <li>全程不抛异常</li>
     * </ul>
     */
    public static Map<Long, Byte> decode(long[] positions, byte[] types) {
        Map<Long, Byte> decoded = new HashMap<>();
        if (positions == null || types == null) {
            return decoded;
        }
        int count = Math.min(positions.length, types.length);
        for (int i = 0; i < count; i++) {
            byte type = types[i];
            if (!isKnownType(type)) {
                continue;
            }
            decoded.put(positions[i], type);
        }
        return decoded;
    }

    /** 升序键列表（唯一过滤口径：非空键 + 非空值 + 合法 type），保证 positions/types 双数组对齐 */
    private static List<Long> sortedKnownKeys(Map<Long, Byte> index) {
        if (index == null || index.isEmpty()) {
            return Collections.emptyList();
        }
        TreeMap<Long, Byte> sorted = new TreeMap<>();
        for (Map.Entry<Long, Byte> entry : index.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && isKnownType(entry.getValue())) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        return new ArrayList<>(sorted.keySet());
    }

    // ==================== 半径选择（探测三态） ====================

    /**
     * 节点有效性探测接口：由调用方注入（世界/方块访问全部留在调用侧，本类保持零 MC 依赖）。
     * 返回 {@code null} 按 {@link ProbeResult#UNLOADED} 处理（防御：跳过且不修剪）。
     */
    public interface Probe {

        ProbeResult probe(long packed, int x, int y, int z, byte type);
    }

    /** 探测结果三态：VALID 入选 / INVALID 进入修剪集 / UNLOADED 跳过且不修剪 */
    public enum ProbeResult {

        VALID,
        INVALID,
        UNLOADED
    }

    /** 入选节点（坐标 + 类型 + 距离平方，不可变值对象） */
    public static final class SelectedNode {

        /** 打包坐标 */
        public final long packed;

        /** 方块 X */
        public final int x;

        /** 方块 Y */
        public final int y;

        /** 方块 Z */
        public final int z;

        /** 节点类型（TYPE_ENERGY / TYPE_DYNAMO） */
        public final byte type;

        /** 到中心点的距离平方（long） */
        public final long distSq;

        public SelectedNode(long packed, int x, int y, int z, byte type, long distSq) {
            this.packed = packed;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.distSq = distSq;
        }
    }

    /** 半径选择结果（不可变）：入选列表 + 修剪集 */
    public static final class RadiusSelection {

        /** 空结果常量（null / 空索引 / null 探测器时的防御返回值） */
        public static final RadiusSelection EMPTY = new RadiusSelection(
            Collections.<SelectedNode>emptyList(),
            Collections.<Long>emptySet());

        /** 入选节点：已按 distance² 升序（同距离按打包坐标升序）排序，并截断至 limit */
        public final List<SelectedNode> selected;

        /** 半径内被探测为 INVALID 的打包坐标集（UNLOADED 与半径外不进入；可整体回传 Registry.prune） */
        public final Set<Long> pruned;

        public RadiusSelection(List<SelectedNode> selected, Set<Long> pruned) {
            this.selected = selected;
            this.pruned = pruned;
        }
    }

    /** 入选排序：distance² 升序，同距离按打包坐标升序（确定性输出） */
    private static final Comparator<SelectedNode> SELECTION_ORDER = (a, b) -> {
        int byDist = Long.compare(a.distSq, b.distSq);
        return byDist != 0 ? byDist : Long.compare(a.packed, b.packed);
    };

    /**
     * 半径选择：遍历索引，distance² ≤ radiusSq（闭边界）的条目经 {@link Probe} 裁决——
     * VALID 入选、INVALID 进入修剪集、UNLOADED（含 null 返回）跳过且不修剪；
     * 入选集按 distance² 升序排序后截断 limit（limit ≤ 0 时入选集为空，修剪集照常产出）。
     *
     * @param index        节点索引（打包坐标 → 类型），null 视为空索引
     * @param centerPacked 中心点打包坐标
     * @param radiusSq     半径平方（闭边界，等于时保留）
     * @param limit        入选上限（≤ 0 表示不入选，仅产出修剪集）
     * @param probe        节点有效性探测器，null 直接返回 {@link RadiusSelection#EMPTY}
     * @return 入选列表 + 修剪集（均不可变）
     */
    public static RadiusSelection selectWithinRadius(Map<Long, Byte> index, long centerPacked, long radiusSq, int limit,
        Probe probe) {
        if (index == null || index.isEmpty() || probe == null) {
            return RadiusSelection.EMPTY;
        }
        List<SelectedNode> candidates = new ArrayList<>();
        Set<Long> pruned = new LinkedHashSet<>();
        for (Map.Entry<Long, Byte> entry : index.entrySet()) {
            Long packedKey = entry.getKey();
            Byte type = entry.getValue();
            if (packedKey == null || type == null || !isKnownType(type)) {
                continue;
            }
            long packed = packedKey;
            long distSq = distanceSq(packed, centerPacked);
            if (distSq > radiusSq) {
                continue;
            }
            ProbeResult result = probe.probe(packed, unpackX(packed), unpackY(packed), unpackZ(packed), type);
            if (result == ProbeResult.INVALID) {
                pruned.add(packedKey);
            } else if (result == ProbeResult.VALID) {
                candidates
                    .add(new SelectedNode(packed, unpackX(packed), unpackY(packed), unpackZ(packed), type, distSq));
            }
        }
        Collections.sort(candidates, SELECTION_ORDER);
        List<SelectedNode> selected;
        if (limit <= 0 || candidates.isEmpty()) {
            selected = Collections.emptyList();
        } else if (candidates.size() <= limit) {
            selected = candidates;
        } else {
            selected = candidates.subList(0, limit);
        }
        return new RadiusSelection(Collections.unmodifiableList(selected), Collections.unmodifiableSet(pruned));
    }
}
