package com.miaokatze.gtswn.api.monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个时间窗口的历史样本序列（不可变值对象，v1.7.9 新增）。
 * <p>
 * {@code samples} 按旧→新排序（index 0 最旧），每窗口固定最多 61 个采样点，
 * 超出时在构建时截尾保留最新 61 点（与内部 FIFO 窗口容量一致），
 * 并以不可变列表包装返回，调用方不可修改。
 */
public final class HistoryWindow {

    /** 窗口编号（0-7 依次为 5 分钟 / 1 小时 / 8 小时 / 24 小时 / 7 天 / 1 个月 / 3 个月 / 1 年） */
    public final int windowId;

    /** 窗口内样本（旧→新，最多 61 点，不可变） */
    public final List<HistorySample> samples;

    public HistoryWindow(int windowId, List<HistorySample> samples) {
        this.windowId = windowId;
        List<HistorySample> copy = new ArrayList<>(samples);
        if (copy.size() > 61) {
            copy = new ArrayList<>(copy.subList(copy.size() - 61, copy.size()));
        }
        this.samples = Collections.unmodifiableList(copy);
    }
}
