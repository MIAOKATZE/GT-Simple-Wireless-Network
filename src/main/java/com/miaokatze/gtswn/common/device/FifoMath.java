package com.miaokatze.gtswn.common.device;

/**
 * 环形 FIFO 窗口 runningSum 增量均值纯数学（v0.8.0 G1 抽取，零 Minecraft 依赖）。
 * <p>
 * 窗口状态语义与 {@code DeviceTerminalDataStore.MachineRecord} 的 {@code fifo / idx / count}
 * 三元组一致（该类属 G5 切片，本切片不改其字段与 NBT 结构）：
 * <ul>
 * <li>{@code fifo} 长度恒为窗口容量（60），未采样位为 0</li>
 * <li>写入 {@code fifo[idx]} 后 {@code idx = (idx+1) % 容量}；{@code count} 未满前每写一次 +1</li>
 * <li><b>有效样本区间</b>：{@code count < 容量} 时为 {@code [0, count)}（首载从 0 顺序填充，
 * 未填位恒 0），{@code count = 容量} 时全窗口</li>
 * </ul>
 * 不变量：{@code runningSum == rebuildSum(fifo, count)}。滚动写入入队加新值、挤出减被覆盖旧值，
 * O(1) 推进且与全量重算对拍一致（含窗口回绕）。坏档防御（idx 落在有效区间外的脏位）：
 * 覆盖位在有效区间内才挤出旧值；写入位恰为区间扩容边界（{@code idx == count < 容量}）才计入
 * 新值，其余越界脏位不计入——与 {@link #rebuildSum} 的区间口径严格对齐。
 */
public final class FifoMath {

    private FifoMath() {}

    /**
     * 全量重算有效样本之和（首载重建 / 对拍基准）：累加区间 {@code [0, min(count, fifo.length))}。
     * <p>
     * {@code count} 钳到 {@code [0, fifo.length]}；旧实现按全数组求和，对合法存档两者相等
     * （未填位恒 0），对手改坏档（count 外脏位）本口径更严格且随窗口滚动自愈。
     */
    public static long rebuildSum(long[] fifo, int count) {
        int n = Math.min(Math.max(count, 0), fifo.length);
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            sum += fifo[i];
        }
        return sum;
    }

    /**
     * 环形写入一步的增量推进：按<b>写入前</b>状态 {@code (fifo, idx, count)} 计算写入
     * {@code value} 后的新有效和；不修改入参（{@code fifo[idx] = value} 与 idx/count 推进由调用方完成）。
     * <ul>
     * <li>覆盖位在有效区间内（含窗口已满）：挤出 {@code fifo[idx]} 旧值、计入新值</li>
     * <li>未满且 {@code idx == count}（正常首载路径）：空位扩入区间，只计入新值</li>
     * <li>其余（idx 越界 / 落在区间外的脏位）：不计入，保持与 {@link #rebuildSum} 一致</li>
     * </ul>
     */
    public static long push(long[] fifo, int idx, int count, long runningSum, long value) {
        if (idx < 0 || idx >= fifo.length) {
            // 防御：写位越界（正常路径不可达，NBT 读取侧已钳 idx ∈ [0, 容量)）
            return runningSum;
        }
        int n = Math.min(Math.max(count, 0), fifo.length);
        boolean covered = idx < n;
        boolean entering = !covered && idx == count && count < fifo.length;
        long evicted = covered ? fifo[idx] : 0L;
        long incoming = covered || entering ? value : 0L;
        return runningSum - evicted + incoming;
    }

    /**
     * 有效样本均值：{@code count > 0 ? (double) runningSum / count : 0}（与旧全量重扫口径一致）。
     */
    public static double avg(long runningSum, int count) {
        return count > 0 ? (double) runningSum / count : 0D;
    }

    /** Fractional interval rates must survive persistence and the rolling average. */
    public static double rebuildSum(double[] fifo, int count) {
        int n = Math.min(Math.max(count, 0), fifo.length);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += fifo[i];
        return sum;
    }

    public static double push(double[] fifo, int idx, int count, double sum, double value) {
        if (idx < 0 || idx >= fifo.length) return sum;
        int n = Math.min(Math.max(count, 0), fifo.length);
        boolean covered = idx < n;
        boolean entering = !covered && idx == count && count < fifo.length;
        return sum - (covered ? fifo[idx] : 0.0) + (covered || entering ? value : 0.0);
    }

    public static double avg(double sum, int count) {
        return count > 0 ? sum / count : 0.0;
    }

    /** Sum scaled by window capacity: sixty finite large samples cannot overflow their rolling mean. */
    public static double rebuildScaledSum(double[] fifo, int count) {
        return rebuildScaledSum(fifo, count, null, 0);
    }

    public static double rebuildScaledSum(double[] fifo, int count, int[] nonzero, int channel) {
        if (fifo.length == 0) return 0;
        int n = Math.min(Math.max(count, 0), fifo.length);
        double sum = 0;
        int nonzeroCount = 0;
        for (int i = 0; i < n; i++) {
            double value = finiteAmount(fifo[i]);
            if (value > 0) nonzeroCount++;
            sum = finiteAdd(sum, value / fifo.length);
        }
        if (nonzero != null) nonzero[channel] = nonzeroCount;
        return sum;
    }

    public static double pushScaled(double[] fifo, int idx, int count, double sum, double value) {
        if (idx < 0 || idx >= fifo.length) return sum;
        int n = Math.min(Math.max(count, 0), fifo.length);
        boolean covered = idx < n;
        boolean entering = !covered && idx == count && count < fifo.length;
        double next = Math.max(0, sum - (covered ? finiteAmount(fifo[idx]) / fifo.length : 0));
        return finiteAdd(next, covered || entering ? finiteAmount(value) / fifo.length : 0);
    }

    /** The cached nonzero count removes floating residuals exactly when the last real sample leaves the window. */
    public static double pushScaled(double[] fifo, int idx, int count, double sum, double value, int[] nonzero,
        int channel) {
        if (idx < 0 || idx >= fifo.length) return sum;
        int n = Math.min(Math.max(count, 0), fifo.length);
        boolean covered = idx < n;
        boolean entering = !covered && idx == count && count < fifo.length;
        if (covered && finiteAmount(fifo[idx]) > 0) nonzero[channel]--;
        if ((covered || entering) && finiteAmount(value) > 0) nonzero[channel]++;
        if (nonzero[channel] == 0) return 0;
        double next = pushScaled(fifo, idx, count, sum, value);
        double evicted = covered ? finiteAmount(fifo[idx]) / fifo.length : 0;
        if (evicted > 0 && next <= 4 * Math.ulp(evicted)) {
            // Removing a dominant sample can expose smaller values previously lost to rounding.
            // The caller has not written fifo[idx] yet, so rebuild its post-write state explicitly.
            next = 0;
            int nextCount = n + (entering ? 1 : 0);
            for (int i = 0; i < nextCount; i++) {
                double sample = i == idx ? value : fifo[i];
                next = finiteAdd(next, finiteAmount(sample) / fifo.length);
            }
        }
        return next;
    }

    public static double scaledAvg(double scaledSum, int count, int capacity) {
        return count > 0 && capacity > 0 ? finiteAmount((scaledSum / count) * capacity) : 0;
    }

    private static double finiteAmount(double amount) {
        return Double.isNaN(amount) || amount <= 0 ? 0 : Math.min(amount, Double.MAX_VALUE);
    }

    private static double finiteAdd(double a, double b) {
        return a >= Double.MAX_VALUE - b ? Double.MAX_VALUE : a + b;
    }
}
