package com.miaokatze.gtswn.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache.Snapshot;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * {@link DeviceTerminalClientCache} 纯逻辑单测（v1.7.15，零 Minecraft 类加载：
 * Entry 为独立静态数据类，receivePage 为纯集合运算）。
 * <p>
 * 覆盖口径：整批到齐提交 / 连续前缀只增长提交 / 新旧版本批次切换，
 * 以及 v1.7.15 修复点——整批已提交后的<b>同版本迟到重复页必须丢弃</b>，
 * 不得重开只含本页的不完整 pending 批次（否则 hasMorePending 恒真触发无效追加轮询）。
 */
public class DeviceTerminalClientCacheTest {

    /** 构造最小 Entry（仅 key 参与断言，其余字段取代表性常量） */
    private static Entry entry(String key) {
        return new Entry(key, key, (byte) 1, (byte) 0, 40L, 100L, 20D, 50D, 0, 1, 64, 1, "", "");
    }

    /** 整批到齐整体替换 + 迟到重复页丢弃（v1.7.15 修复回归测试） */
    @Test
    public void completeBatchThenLateDuplicatePageDropped() {
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 1L, 0, 2, Arrays.asList(entry("a"), entry("b")));
        DeviceTerminalClientCache.receivePage(id, 1L, 1, 2, Arrays.asList(entry("c")));
        Snapshot snapshot = DeviceTerminalClientCache.getSnapshot(id);
        assertEquals(3, snapshot.entries.size());
        assertEquals(1L, snapshot.version);
        assertFalse(DeviceTerminalClientCache.hasMorePending(id));

        // 同版本迟到重复页：不得重开批次（修复前会重开只有本页的 pending 批次）
        DeviceTerminalClientCache.receivePage(id, 1L, 0, 2, Arrays.asList(entry("a"), entry("b")));
        assertFalse("迟到重复页不得重开 pending 批次", DeviceTerminalClientCache.hasMorePending(id));
        assertEquals("已提交快照不受迟到页影响", 3, DeviceTerminalClientCache.getSnapshot(id).entries.size());
    }

    /** 同版本但分页布局变化（服务端重新分页）：放行重开批次，不被迟到重复页丢弃误伤 */
    @Test
    public void sameVersionDifferentPageTotalReopensBatch() {
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 4L, 0, 1, Arrays.asList(entry("a"), entry("b")));
        assertFalse(DeviceTerminalClientCache.hasMorePending(id));

        // 同 version 但 pageTotal 1→2（重新分页）：必须重开批次接收
        DeviceTerminalClientCache.receivePage(id, 4L, 0, 2, Arrays.asList(entry("a")));
        assertTrue("重新分页应重开 pending 批次", DeviceTerminalClientCache.hasMorePending(id));
        DeviceTerminalClientCache.receivePage(id, 4L, 1, 2, Arrays.asList(entry("b")));
        assertFalse(DeviceTerminalClientCache.hasMorePending(id));
        assertEquals(2, DeviceTerminalClientCache.getSnapshot(id).entries.size());
    }

    /** 未到齐提交连续前缀（只增长不回缩）+ 到齐后整体替换 */
    @Test
    public void prefixGrowsThenCompletes() {
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 2L, 0, 2, Arrays.asList(entry("a")));
        Snapshot prefix = DeviceTerminalClientCache.getSnapshot(id);
        assertEquals(1, prefix.entries.size());
        assertTrue(DeviceTerminalClientCache.hasMorePending(id));

        DeviceTerminalClientCache.receivePage(id, 2L, 1, 2, Arrays.asList(entry("b"), entry("c")));
        Snapshot full = DeviceTerminalClientCache.getSnapshot(id);
        assertEquals(3, full.entries.size());
        assertEquals("a", full.entries.get(0).key);
        assertEquals("c", full.entries.get(2).key);
        assertFalse(DeviceTerminalClientCache.hasMorePending(id));
    }

    /** 缺口页（页 0 未到）不提交带洞前缀（允许提交空前缀）；页 0 到达后提交完整连续段 */
    @Test
    public void gapPageDoesNotCommitHoleyPrefix() {
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 3L, 1, 2, Arrays.asList(entry("b")));
        Snapshot gap = DeviceTerminalClientCache.getSnapshot(id);
        assertTrue("页 0 缺口只允许空前缀（绝不带洞）", gap == null || gap.entries.isEmpty());
        DeviceTerminalClientCache.receivePage(id, 3L, 0, 2, Arrays.asList(entry("a")));
        Snapshot filled = DeviceTerminalClientCache.getSnapshot(id);
        assertEquals(2, filled.entries.size());
        assertEquals("a", filled.entries.get(0).key);
        assertEquals("b", filled.entries.get(1).key);
    }

    /** 更高版本到达开启新批次；更低版本迟到整页丢弃 */
    @Test
    public void versionSwitchAndStaleDrop() {
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 5L, 0, 2, Arrays.asList(entry("a"), entry("b")));
        DeviceTerminalClientCache.receivePage(id, 5L, 1, 2, Arrays.asList(entry("c")));
        assertEquals(3, DeviceTerminalClientCache.getSnapshot(id).entries.size());

        // 新版本批次：旧批次整体作废，首页即前缀提交（只增长保护：新前缀 1 条 < 已显示 3 条不替换，
        // 但 pending 批次已开启，续页到齐后整体替换）
        DeviceTerminalClientCache.receivePage(id, 6L, 0, 2, Arrays.asList(entry("x"), entry("y")));
        assertTrue(DeviceTerminalClientCache.hasMorePending(id));
        DeviceTerminalClientCache.receivePage(id, 6L, 1, 2, Arrays.asList(entry("z")));
        assertEquals(3, DeviceTerminalClientCache.getSnapshot(id).entries.size());
        assertEquals("z", DeviceTerminalClientCache.getSnapshot(id).entries.get(2).key);

        // 旧版本（5）迟达：丢弃
        DeviceTerminalClientCache.receivePage(id, 5L, 0, 2, Collections.<Entry>emptyList());
        assertEquals("旧版本页丢弃", "z", DeviceTerminalClientCache.getSnapshot(id).entries.get(2).key);
    }

    /** 非法入参防御：null 终端 / 非法页码静默忽略 */
    @Test
    public void invalidArgumentsIgnored() {
        DeviceTerminalClientCache.receivePage(null, 1L, 0, 1, Collections.<Entry>emptyList());
        UUID id = UUID.randomUUID();
        DeviceTerminalClientCache.receivePage(id, 1L, -1, 1, Collections.<Entry>emptyList());
        DeviceTerminalClientCache.receivePage(id, 1L, 0, 0, Collections.<Entry>emptyList());
        DeviceTerminalClientCache.receivePage(id, 1L, 1, 1, Collections.<Entry>emptyList());
        assertNull(DeviceTerminalClientCache.getSnapshot(id));
        assertFalse(DeviceTerminalClientCache.hasMorePending(id));
    }
}
