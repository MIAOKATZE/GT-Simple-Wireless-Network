package com.miaokatze.gtswn.common.panel;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AE 推送装配缓存（G4 = v1.7.14）：{@code AEMonitorController.pushNow} 重建产物的
 * 进程级共享缓存。
 * <p>
 * 缓存键 =（panelKey, trackingWindow, 绑定集版本, dataSet revision）——任一变化即失配重建；
 * 条目另持 dataSet 弱引用并以<b>引用相等</b>校验：数据集被 store remove / 方块破坏重放 /
 * 世界重载后引用即断（AE dataSet 与坐标一一对应、全部变更经控制器本地 revision 推进，
 * 同键新数据集不会读到旧装配）；同数据集多屏（同 owner/dataset）命中共享装配结果。
 * <p>
 * 条目有界（{@link #MAX_ENTRIES} 插入序淘汰，新条目优先保留），仅服务端访问面；
 * 访问加锁只为防御网络线程误入，常态单线程无争用。装配产物以不可变视图封存，
 * 消费端（PacketSyncAEMonitorData 构造）自会深拷贝，无共享可变态。
 */
final class AEAssemblyCache {

    /** 装配产物：走势段 chartKey/chartSamples + 实时监控双 Map 的不可变快照 */
    static final class Assembly {

        final String chartKey;
        final List<AEMonitorSample> chartSamples;
        final Map<String, AEMonitorSample> monitorLatest;
        final Map<String, Double> monitorAvg300s;

        Assembly(String chartKey, List<AEMonitorSample> chartSamples, Map<String, AEMonitorSample> monitorLatest,
            Map<String, Double> monitorAvg300s) {
            this.chartKey = chartKey;
            this.chartSamples = chartSamples;
            this.monitorLatest = monitorLatest;
            this.monitorAvg300s = monitorAvg300s;
        }
    }

    /** 缓存条目上限（每条 ≈ 61 点走势 + ≤2×aeMaxMonitoredItems 双 Map，越界按插入序淘汰最旧） */
    private static final int MAX_ENTRIES = 32;

    /** 单条目类（键字符串 → 弱引用数据集 + 装配产物） */
    private static final class CachedEntry {

        final WeakReference<AEMonitorDataSet> dataSetRef;
        final Assembly assembly;

        CachedEntry(AEMonitorDataSet dataSet, Assembly assembly) {
            this.dataSetRef = new WeakReference<AEMonitorDataSet>(dataSet);
            this.assembly = assembly;
        }
    }

    /** 有界插入序淘汰缓存（匿名子类不可用菱形推断，泛型显式标注） */
    private static final Map<String, CachedEntry> CACHE = Collections
        .synchronizedMap(new LinkedHashMap<String, CachedEntry>(32, 0.75f, false) {

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    private AEAssemblyCache() {}

    /**
     * 查缓存（命中路径）。
     *
     * @param dataSet  当前数据集（引用相等校验——弱引用失配 = 数据集已被移除或重载）
     * @param cacheKey 调用方拼装的复合键（panelKey + 窗口 + 绑定集版本 + revision）
     * @return 命中返回装配产物；未命中或引用失配返回 null（失配条目就地淘汰）
     */
    static Assembly lookup(AEMonitorDataSet dataSet, String cacheKey) {
        CachedEntry entry = CACHE.get(cacheKey);
        if (entry == null) {
            return null;
        }
        if (entry.dataSetRef.get() != dataSet) {
            CACHE.remove(cacheKey);
            return null;
        }
        return entry.assembly;
    }

    /**
     * 存缓存（失效路径的重建落点）。
     *
     * @param dataSet  当前数据集（弱引用持有，不阻止其回收）
     * @param cacheKey 复合键
     * @param assembly 不可变装配产物
     */
    static void store(AEMonitorDataSet dataSet, String cacheKey, Assembly assembly) {
        CACHE.put(cacheKey, new CachedEntry(dataSet, assembly));
    }
}
