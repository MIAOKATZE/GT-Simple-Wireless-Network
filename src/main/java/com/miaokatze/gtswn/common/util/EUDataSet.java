package com.miaokatze.gtswn.common.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.util.FormatUtil.Measurement;

/**
 * EU 测量数据集
 * <p>
 * 用于无线电网监视器（MTE / HUD）的 EU 测量历史管理，替代原 {@link FormatUtil} 中的
 * {@code recordMeasurement} / {@code purgeExpired} 窗口管理方法。
 * <p>
 * 设计要点：
 * <ul>
 * <li>实例类（非 static）：每个 MTE 实例持有自己的 dataSet；HUD 持有 static 单例。</li>
 * <li>内部使用 {@link ArrayList}，<b>index 0 = 最旧，index size-1 = 最新</b>，
 * O(1) 追加，NBT 序列化时顺序自然。</li>
 * <li>固定容量 {@value #CAPACITY}（0s 首检 + 60 次 100t 检测 = 300s）。</li>
 * <li>满载时新数据淘汰最旧数据（FIFO 老化），保持窗口恒定。</li>
 * <li>使用 {@link BigDecimal} 精确计算 EU/t 斜率，避免 double 精度损失。</li>
 * </ul>
 * <p>
 * NBT 格式兼容原 {@code measurementHistory} 结构，旧存档可直接加载。
 */
public class EUDataSet {

    /** 数据集固定容量（61 个采样点 = 0s 首检 + 60 次 100t 检测 = 300s） */
    public static final int CAPACITY = 61;

    /** 长期静默阈值（tick 差值）：300s = 6000 ticks，与满载容量等价 */
    public static final long LONG_SILENT_THRESHOLD_TICKS = 6000L;

    /** 内部存储：index 0 = 最旧，index size-1 = 最新 */
    private final List<Measurement> data = new ArrayList<>(CAPACITY);

    /**
     * 长期静默状态标志。
     * <p>
     * 触发条件：静默模式（所有 value 相同）持续 ≥ 300s（tickDiff ≥ 6000）。
     * <p>
     * 进入长期静默后，数据集只保留首末两个数据点，每次 add 更新末位 tick。
     * 当新值与旧值不同时，清空数据集冷启动，重置此标志。
     * <p>
     * MTE 持久化此字段；HUD（便携式）不持久化（随退出重置）。
     */
    private boolean longTermSilent = false;

    /**
     * 添加新测量值（最新）。
     * <p>
     * 三种模式：
     * <ol>
     * <li>长期静默模式：数据集只保留 2 个数据点（首末），新值相同则更新末位 tick，
     * 新值不同则清空冷启动。</li>
     * <li>静默模式（未达长期）：所有 value 相同时只保留首末 2 个数据点；
     * 当 tickDiff ≥ 6000 时进入长期静默。</li>
     * <li>正常模式：满载时淘汰最旧数据（FIFO 老化），保持窗口恒定。</li>
     * </ol>
     *
     * @param value 当前 EU 测量值（BigInteger，不可为 null）
     * @param tick  当前游戏 tick
     */
    public void add(BigInteger value, long tick) {
        if (value == null) {
            // null 值不记录，与原 recordMeasurement 行为一致
            return;
        }

        // === 长期静默模式：数据集只有 2 个数据点 ===
        if (longTermSilent) {
            Measurement last = data.get(data.size() - 1);
            if (value.equals(last.value)) {
                // 新值相同：更新末位 tick，保持 2 个数据点
                data.set(1, new Measurement(tick, value));
            } else {
                // 新值不同：退出长期静默
                // v1.3.2 修正：保留 last 点作为新数据集起点，避免 size 回到 1 触发冷启动
                // 旧逻辑 clear()+add(newPoint) 导致 size=1，MTE 显示"暂无变化/计算中"，
                // 且若后续值短暂稳定会陷入"静默↔暂无变化"循环，无法积累正确 eut
                // 新逻辑：data = [last, newPoint]，size=2，立即可计算 eut = (newValue-lastValue)/(newTick-lastTick)
                Measurement prev = last; // last 已在第 77 行获取，clear 前持有引用
                data.clear();
                longTermSilent = false;
                data.add(prev);
                data.add(new Measurement(tick, value));
            }
            return;
        }

        // === 静默模式检测（所有 value 相同且 size >= 2） ===
        if (data.size() >= 2 && isAllSameValue()) {
            Measurement last = data.get(data.size() - 1);
            if (value.equals(last.value)) {
                // 新值相同：压缩为 2 个数据点（首位保留最早的，末位更新为当前）
                Measurement oldest = data.get(0);
                data.clear();
                data.add(oldest);
                data.add(new Measurement(tick, value));

                // 检查是否达到长期静默阈值（300s = 6000 ticks）
                if (tick - oldest.tick >= LONG_SILENT_THRESHOLD_TICKS) {
                    longTermSilent = true;
                }
            } else {
                // 新值不同：脱离静默
                // v1.3.2 修正：保留 last 点作为新数据集起点，避免 size=1 冷启动（同修改点 A）
                // data = [last, newPoint]，size=2，立即可计算 eut
                Measurement prev = last; // last 已在第 92 行获取，clear 前持有引用
                data.clear();
                data.add(prev);
                data.add(new Measurement(tick, value));
            }
            return;
        }

        // === 正常模式：满载时淘汰最旧数据（FIFO 老化） ===
        if (data.size() >= CAPACITY) {
            data.remove(0);
        }
        data.add(new Measurement(tick, value));
    }

    /**
     * 检测数据集所有 value 是否完全一致。
     * <p>
     * 用于判断是否进入静默模式。size &lt; 2 时返回 false。
     *
     * @return 所有 value 相同返回 true；否则 false
     */
    public boolean isAllSameValue() {
        if (data.size() < 2) {
            return false;
        }
        BigInteger first = data.get(0).value;
        for (int i = 1; i < data.size(); i++) {
            if (!data.get(i).value.equals(first)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 是否处于长期静默状态。
     * <p>
     * 长期静默 = 静默模式持续 ≥ 300s。此时数据集只保留 2 个数据点，
     * 显示标签从"静默"切换为"长期静默"。
     *
     * @return 长期静默返回 true；否则 false
     */
    public boolean isLongTermSilent() {
        return longTermSilent;
    }

    /**
     * 获取最新测量点（index size-1）。
     *
     * @return 最新测量点；空集返回 null
     */
    public Measurement getNewest() {
        if (data.isEmpty()) {
            return null;
        }
        return data.get(data.size() - 1);
    }

    /**
     * 获取最旧测量点（index 0）。
     *
     * @return 最旧测量点；空集返回 null
     */
    public Measurement getOldest() {
        if (data.isEmpty()) {
            return null;
        }
        return data.get(0);
    }

    /**
     * 当前数据量。
     *
     * @return 数据点数量（0 ~ {@link #CAPACITY}）
     */
    public int size() {
        return data.size();
    }

    /**
     * 是否已满（size == {@link #CAPACITY}）。
     *
     * @return 已满返回 true
     */
    public boolean isFull() {
        return data.size() >= CAPACITY;
    }

    /**
     * 是否为空。
     *
     * @return 空集返回 true
     */
    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * 清空所有数据，并重置长期静默标志。
     * <p>
     * 用于 gap 检测清空、退出长期静默冷启动等场景。
     */
    public void clear() {
        data.clear();
        longTermSilent = false;
    }

    /**
     * 计算 EU/t 变化率（斜率法）。
     * <p>
     * 公式：{@code (newest.value - oldest.value) / (newest.tick - oldest.tick)}
     * <ul>
     * <li>正值 = 电网上升</li>
     * <li>负值 = 电网下降</li>
     * <li>0 = 绝对无变化</li>
     * </ul>
     * 使用 {@link BigDecimal} 进行精确除法，保留 6 位小数后转 double，
     * 避免 BigInteger 直接转 double 时大数精度丢失。
     *
     * @return EU/t 变化率；size &lt; 2 或 tickDiff &lt;= 0 时返回 0.0
     */
    public double calculateEUT() {
        // 数据点不足，无法计算斜率
        if (data.size() < 2) {
            return 0.0;
        }

        Measurement oldest = data.get(0);
        Measurement newest = data.get(data.size() - 1);

        // tick 差值（long 直接相减，无精度问题）
        long tickDiff = newest.tick - oldest.tick;
        if (tickDiff <= 0) {
            // tick 差非正（异常或回拨），返回 0
            return 0.0;
        }

        // value 差值（BigInteger.subtract，无精度问题）
        BigInteger diffValue = newest.value.subtract(oldest.value);

        // 使用 BigDecimal 精确除法，保留 6 位小数，HALF_UP 四舍五入
        // 注意：diffValue 可能为负（电网下降），结果保留符号
        return new BigDecimal(diffValue).divide(new BigDecimal(tickDiff), 6, RoundingMode.HALF_UP)
            .doubleValue();
    }

    /**
     * 序列化到 NBT（兼容旧 measurementHistory 格式）。
     * <p>
     * NBT 格式：
     * 
     * <pre>
     * measurementHistory: {
     *   count: &lt;int&gt;,
     *   m0: { tick: &lt;long&gt;, value: &lt;string&gt; },   // 最旧
     *   m1: { tick: &lt;long&gt;, value: &lt;string&gt; },
     *   ...
     *   m{count-1}: { tick: &lt;long&gt;, value: &lt;string&gt; }  // 最新
     * }
     * </pre>
     * <p>
     * value 字段使用 {@link BigInteger#toString()} 转字符串，避免 NBT 没有大整数原生类型。
     *
     * @param parent 父 NBT 标签
     * @param key    存储键名（通常为 "measurementHistory"）
     */
    public void saveToNBT(NBTTagCompound parent, String key) {
        NBTTagCompound historyTag = new NBTTagCompound();
        int count = data.size();

        // 写入数据点数量
        historyTag.setInteger("count", count);

        // 按顺序写入每个测量点：m0=最旧, m{count-1}=最新
        for (int i = 0; i < count; i++) {
            Measurement m = data.get(i);
            NBTTagCompound mTag = new NBTTagCompound();
            mTag.setLong("tick", m.tick);
            mTag.setString("value", m.value.toString());
            historyTag.setTag("m" + i, mTag);
        }

        // 持久化长期静默标志（v1.3.0 新增）
        // 旧存档无此键，加载时默认 false，兼容性 OK
        historyTag.setBoolean("longTermSilent", longTermSilent);

        // 挂到父标签
        parent.setTag(key, historyTag);
    }

    /**
     * 从 NBT 反序列化。
     * <p>
     * 加载流程：
     * <ol>
     * <li>清空 data</li>
     * <li>按 0..count-1 顺序 add（保持最旧在前、最新在后）</li>
     * <li>若 count &gt; {@link #CAPACITY}，只加载最后 CAPACITY 个（丢弃最旧的溢出部分）</li>
     * </ol>
     * 兼容性：旧存档的 measurementHistory 格式与本格式一致，可直接加载。
     *
     * @param parent 父 NBT 标签
     * @param key    存储键名（通常为 "measurementHistory"）
     */
    public void loadFromNBT(NBTTagCompound parent, String key) {
        // 先清空现有数据（含 longTermSilent 标志）
        data.clear();
        longTermSilent = false;

        // 检查键是否存在
        if (!parent.hasKey(key)) {
            return;
        }

        NBTTagCompound historyTag = parent.getCompoundTag(key);
        int count = historyTag.getInteger("count");

        // 防御性处理：count 异常时直接返回
        if (count <= 0) {
            return;
        }

        // 计算实际加载起点：若 count > CAPACITY，跳过前面溢出部分，只加载最后 CAPACITY 个
        int startIndex = 0;
        if (count > CAPACITY) {
            startIndex = count - CAPACITY;
        }

        // 按 0..count-1 顺序 add，保持最旧在前、最新在后
        for (int i = startIndex; i < count; i++) {
            String mKey = "m" + i;
            if (!historyTag.hasKey(mKey)) {
                // 数据不完整，跳过此条
                continue;
            }

            NBTTagCompound mTag = historyTag.getCompoundTag(mKey);
            long tick = mTag.getLong("tick");
            String valueStr = mTag.getString("value");

            // 解析 BigInteger（try-catch 防止异常 value 字符串导致崩溃）
            BigInteger value;
            try {
                value = new BigInteger(valueStr);
            } catch (NumberFormatException e) {
                // value 字符串非法，跳过此条数据
                continue;
            }

            // 直接 add，无需触发 FIFO 老化逻辑（加载时已限制 count <= CAPACITY）
            data.add(new Measurement(tick, value));
        }

        // 读取长期静默标志（v1.3.0 新增）
        // 旧存档无此键时 getBoolean 返回 false，兼容性 OK
        longTermSilent = historyTag.getBoolean("longTermSilent");
    }
}
