package com.miaokatze.gtswn.common.covers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * {@link WirelessNodeIndexCodec} 纯逻辑单测（零 Minecraft 类加载）。
 * <p>
 * 覆盖：坐标打包位布局（与 common/quantum/QuantumControllerRegistry 先例一致）、
 * 双数组 round-trip、坏档防御（长度不齐取短 / 未知 type 丢弃 / 空输入）、
 * 半径闭边界、距离升序与 limit 截断、探测三态（VALID 入选 / INVALID 修剪 / UNLOADED 跳过不修剪）。
 */
public class WirelessNodeIndexCodecTest {

    /** 全部判 VALID 的探测器 */
    private static final WirelessNodeIndexCodec.Probe ALL_VALID = (packed, x, y, z,
        type) -> WirelessNodeIndexCodec.ProbeResult.VALID;

    private static Map<Long, Byte> newIndex() {
        return new HashMap<>();
    }

    private static void put(Map<Long, Byte> index, int x, int y, int z, byte type) {
        index.put(Long.valueOf(WirelessNodeIndexCodec.pack(x, y, z)), Byte.valueOf(type));
    }

    /** 按打包坐标查表裁决（未登记的坐标判 VALID） */
    private static WirelessNodeIndexCodec.Probe probeByPacked(
        final Map<Long, WirelessNodeIndexCodec.ProbeResult> decisions) {
        return (packed, x, y, z, type) -> {
            WirelessNodeIndexCodec.ProbeResult result = decisions.get(Long.valueOf(packed));
            return result != null ? result : WirelessNodeIndexCodec.ProbeResult.VALID;
        };
    }

    /** 打包位布局与先例一致：单位向量落位 + 负坐标符号位（非仅自洽往返） */
    @Test
    public void packLayoutMatchesPrecedent() {
        assertEquals(1L, WirelessNodeIndexCodec.pack(0, 0, 1));
        assertEquals(1L << 26, WirelessNodeIndexCodec.pack(0, 1, 0));
        assertEquals(1L << 38, WirelessNodeIndexCodec.pack(1, 0, 0));
        assertEquals(0x3FFFFFFL << 38, WirelessNodeIndexCodec.pack(-1, 0, 0));
        assertEquals(-1, WirelessNodeIndexCodec.unpackX(WirelessNodeIndexCodec.pack(-1, 0, 0)));
        assertEquals(-1, WirelessNodeIndexCodec.unpackZ(WirelessNodeIndexCodec.pack(0, 0, -1)));
    }

    /** 打包往返：x/z 全 26bit 有符号域、y 全 12bit 有符号域边界值均无损 */
    @Test
    public void packUnpackRoundTripBounds() {
        int[] xs = { -0x2000000, -1, 0, 1, 0x1FFFFFF };
        int[] ys = { -2048, -1, 0, 1, 255, 2047 };
        int[] zs = { -0x2000000, -1, 0, 1, 0x1FFFFFF };
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    long packed = WirelessNodeIndexCodec.pack(x, y, z);
                    assertEquals("x", x, WirelessNodeIndexCodec.unpackX(packed));
                    assertEquals("y", y, WirelessNodeIndexCodec.unpackY(packed));
                    assertEquals("z", z, WirelessNodeIndexCodec.unpackZ(packed));
                }
            }
        }
    }

    /** round-trip：encode → decode 复原原索引，且 positions 数组按打包坐标严格升序 */
    @Test
    public void encodeDecodeRoundTrip() {
        Map<Long, Byte> index = newIndex();
        put(index, -30000000, 0, -12345, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, 30000000, 255, 6789, WirelessNodeIndexCodec.TYPE_DYNAMO);
        put(index, 42, -2048, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, -42, 2047, -1, WirelessNodeIndexCodec.TYPE_DYNAMO);
        long[] positions = WirelessNodeIndexCodec.encodePositions(index);
        byte[] types = WirelessNodeIndexCodec.encodeTypes(index);
        assertEquals(index.size(), positions.length);
        assertEquals(index.size(), types.length);
        for (int i = 1; i < positions.length; i++) {
            assertTrue("positions 应按打包坐标升序", positions[i - 1] < positions[i]);
        }
        Map<Long, Byte> decoded = WirelessNodeIndexCodec.decode(positions, types);
        assertEquals(index, decoded);
    }

    /** 坏档防御：null / 空数组返回空 Map；长度不齐取短；未知 type（非 0/1）丢弃该条 */
    @Test
    public void decodeDefendsBadArrays() {
        assertTrue(
            WirelessNodeIndexCodec.decode(null, null)
                .isEmpty());
        assertTrue(
            WirelessNodeIndexCodec.decode(new long[0], new byte[0])
                .isEmpty());

        long a = WirelessNodeIndexCodec.pack(1, 0, 0);
        long b = WirelessNodeIndexCodec.pack(0, 1, 0);
        long c = WirelessNodeIndexCodec.pack(0, 0, 1);

        // positions 多于 types：取短
        Map<Long, Byte> decoded = WirelessNodeIndexCodec.decode(new long[] { a, b, c }, new byte[] { 0, 1 });
        assertEquals(2, decoded.size());
        assertEquals(Byte.valueOf(WirelessNodeIndexCodec.TYPE_ENERGY), decoded.get(Long.valueOf(a)));
        assertEquals(Byte.valueOf(WirelessNodeIndexCodec.TYPE_DYNAMO), decoded.get(Long.valueOf(b)));
        // types 多于 positions：同样取短
        decoded = WirelessNodeIndexCodec.decode(new long[] { a, b }, new byte[] { 0, 1, 0 });
        assertEquals(2, decoded.size());
        // 未知 type 丢弃：2 与 -1 两条不入选，0 与 1 保留
        decoded = WirelessNodeIndexCodec.decode(new long[] { a, b, c, Long.valueOf(4L) }, new byte[] { 0, 2, 1, -1 });
        assertEquals(2, decoded.size());
        assertTrue(decoded.containsKey(Long.valueOf(a)));
        assertFalse(decoded.containsKey(Long.valueOf(b)));
        assertTrue(decoded.containsKey(Long.valueOf(c)));
    }

    /** encode 侧过滤：未知 type / 空键空值条目跳过，双数组保持对齐；null Map 返回空数组 */
    @Test
    public void encodeFiltersUnknownTypes() {
        Map<Long, Byte> index = newIndex();
        put(index, 1, 2, 3, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, 4, 5, 6, WirelessNodeIndexCodec.TYPE_DYNAMO);
        put(index, 7, 8, 9, (byte) 7);
        index.put(Long.valueOf(WirelessNodeIndexCodec.pack(10, 11, 12)), null);
        long[] positions = WirelessNodeIndexCodec.encodePositions(index);
        byte[] types = WirelessNodeIndexCodec.encodeTypes(index);
        assertEquals(2, positions.length);
        assertEquals(2, types.length);
        Map<Long, Byte> decoded = WirelessNodeIndexCodec.decode(positions, types);
        assertEquals(2, decoded.size());
        assertTrue(decoded.containsValue(Byte.valueOf(WirelessNodeIndexCodec.TYPE_ENERGY)));
        assertTrue(decoded.containsValue(Byte.valueOf(WirelessNodeIndexCodec.TYPE_DYNAMO)));

        assertEquals(0, WirelessNodeIndexCodec.encodePositions(null).length);
        assertEquals(0, WirelessNodeIndexCodec.encodeTypes(null).length);
        assertEquals(0, WirelessNodeIndexCodec.encodePositions(newIndex()).length);
    }

    /** 半径闭边界：distance² == 64² 保留（含 y 轴），+1（65²）剔除且不进入修剪集 */
    @Test
    public void radiusClosedBoundary() {
        Map<Long, Byte> index = newIndex();
        put(index, 64, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, 0, 64, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, 65, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, 0, 65, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        put(index, 0, 0, -64, WirelessNodeIndexCodec.TYPE_DYNAMO);
        put(index, 0, 0, -65, WirelessNodeIndexCodec.TYPE_ENERGY);
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec
            .selectWithinRadius(index, WirelessNodeIndexCodec.pack(0, 0, 0), 64L * 64L, 16, ALL_VALID);
        assertEquals(3, selection.selected.size());
        for (WirelessNodeIndexCodec.SelectedNode node : selection.selected) {
            assertTrue("边界内 distance² 应恰 ≤ 4096", node.distSq <= 4096L);
            assertEquals(4096L, node.distSq);
        }
        assertTrue("闭边界（=radiusSq）必须保留", !selection.selected.isEmpty());
        assertTrue("半径外剔除不等于修剪", selection.pruned.isEmpty());
    }

    /** 排序与截断：>256 条在半径内时仅取最近 256 条（distance² 严格升序），pruned 不受影响 */
    @Test
    public void sortsByDistanceAndTruncatesToLimit() {
        Map<Long, Byte> index = newIndex();
        for (int i = 1; i <= 300; i++) {
            put(index, i, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        }
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec
            .selectWithinRadius(index, WirelessNodeIndexCodec.pack(0, 0, 0), 300L * 300L, 256, ALL_VALID);
        assertEquals(256, selection.selected.size());
        assertEquals(1, selection.selected.get(0).x);
        assertEquals(256, selection.selected.get(255).x);
        for (int i = 1; i < selection.selected.size(); i++) {
            assertTrue("入选应按 distance² 严格升序", selection.selected.get(i - 1).distSq < selection.selected.get(i).distSq);
        }
        assertTrue(selection.pruned.isEmpty());
    }

    /** 同距离 tie-break：按打包坐标升序（确定性输出） */
    @Test
    public void equalDistanceBreaksTieByPackedOrder() {
        Map<Long, Byte> index = newIndex();
        put(index, 0, 0, 1, WirelessNodeIndexCodec.TYPE_ENERGY); // packed = 1
        put(index, 0, 0, -1, WirelessNodeIndexCodec.TYPE_ENERGY); // packed = 0x3FFFFFF
        put(index, 0, 1, 0, WirelessNodeIndexCodec.TYPE_ENERGY); // packed = 1 << 26
        put(index, 1, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY); // packed = 1 << 38
        put(index, -1, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY); // packed = 0x3FFFFFF << 38 = -2^38（符号位在第 63 位，升序最小）
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec
            .selectWithinRadius(index, WirelessNodeIndexCodec.pack(0, 0, 0), 1L, 3, ALL_VALID);
        assertEquals(3, selection.selected.size());
        assertEquals(-1, selection.selected.get(0).x);
        assertEquals(0, selection.selected.get(0).y);
        assertEquals(0, selection.selected.get(0).z);
        assertEquals(0, selection.selected.get(1).x);
        assertEquals(0, selection.selected.get(1).y);
        assertEquals(1, selection.selected.get(1).z);
        assertEquals(0, selection.selected.get(2).x);
        assertEquals(0, selection.selected.get(2).y);
        assertEquals(-1, selection.selected.get(2).z);
    }

    /** 探测三态：VALID 入选 / INVALID 修剪 / UNLOADED 跳过且不修剪；半径外 INVALID 不修剪 */
    @Test
    public void probeThreeStatesSemantics() {
        Map<Long, Byte> index = newIndex();
        put(index, 3, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY); // VALID
        put(index, 4, 0, 0, WirelessNodeIndexCodec.TYPE_DYNAMO); // INVALID
        put(index, 5, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY); // UNLOADED
        put(index, 100, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY); // 半径外且 INVALID
        long valid = WirelessNodeIndexCodec.pack(3, 0, 0);
        long invalid = WirelessNodeIndexCodec.pack(4, 0, 0);
        long unloaded = WirelessNodeIndexCodec.pack(5, 0, 0);
        Map<Long, WirelessNodeIndexCodec.ProbeResult> decisions = new HashMap<>();
        decisions.put(Long.valueOf(valid), WirelessNodeIndexCodec.ProbeResult.VALID);
        decisions.put(Long.valueOf(invalid), WirelessNodeIndexCodec.ProbeResult.INVALID);
        decisions.put(Long.valueOf(unloaded), WirelessNodeIndexCodec.ProbeResult.UNLOADED);
        decisions.put(Long.valueOf(WirelessNodeIndexCodec.pack(100, 0, 0)), WirelessNodeIndexCodec.ProbeResult.INVALID);

        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec
            .selectWithinRadius(index, WirelessNodeIndexCodec.pack(0, 0, 0), 100L, 16, probeByPacked(decisions));
        assertEquals(1, selection.selected.size());
        assertEquals(Long.valueOf(valid), Long.valueOf(selection.selected.get(0).packed));
        assertEquals(1, selection.pruned.size());
        assertTrue("INVALID 应进入修剪集", selection.pruned.contains(Long.valueOf(invalid)));
        assertFalse("UNLOADED 跳过且不修剪", selection.pruned.contains(Long.valueOf(unloaded)));
        assertFalse(
            "半径外 INVALID 不进入修剪集",
            selection.pruned.contains(Long.valueOf(WirelessNodeIndexCodec.pack(100, 0, 0))));
    }

    /** 防御入参：null 索引 / 空索引 / null 探测器均返回共享 EMPTY；limit ≤ 0 不入选但照常修剪 */
    @Test
    public void defensiveInputsReturnEmptySelection() {
        assertSame(
            WirelessNodeIndexCodec.RadiusSelection.EMPTY,
            WirelessNodeIndexCodec.selectWithinRadius(null, 0L, 100L, 10, ALL_VALID));
        assertSame(
            WirelessNodeIndexCodec.RadiusSelection.EMPTY,
            WirelessNodeIndexCodec.selectWithinRadius(newIndex(), 0L, 100L, 10, ALL_VALID));
        assertSame(
            WirelessNodeIndexCodec.RadiusSelection.EMPTY,
            WirelessNodeIndexCodec.selectWithinRadius(newIndex(), 0L, 100L, 10, null));

        Map<Long, Byte> index = newIndex();
        put(index, 1, 0, 0, WirelessNodeIndexCodec.TYPE_ENERGY);
        long invalid = WirelessNodeIndexCodec.pack(2, 0, 0);
        put(index, 2, 0, 0, WirelessNodeIndexCodec.TYPE_DYNAMO);
        Map<Long, WirelessNodeIndexCodec.ProbeResult> decisions = new HashMap<>();
        decisions.put(Long.valueOf(invalid), WirelessNodeIndexCodec.ProbeResult.INVALID);
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec
            .selectWithinRadius(index, WirelessNodeIndexCodec.pack(0, 0, 0), 100L, 0, probeByPacked(decisions));
        assertTrue("limit=0 不入选", selection.selected.isEmpty());
        assertTrue("limit=0 仍照常产出修剪集", selection.pruned.contains(Long.valueOf(invalid)));
    }

    /** 零半径：仅中心点自身（distance²=0 ≤ 0）入选 */
    @Test
    public void zeroRadiusKeepsCenterOnly() {
        Map<Long, Byte> index = newIndex();
        put(index, 7, 7, 7, WirelessNodeIndexCodec.TYPE_DYNAMO);
        put(index, 8, 7, 7, WirelessNodeIndexCodec.TYPE_DYNAMO);
        WirelessNodeIndexCodec.RadiusSelection selection = WirelessNodeIndexCodec
            .selectWithinRadius(index, WirelessNodeIndexCodec.pack(7, 7, 7), 0L, 16, ALL_VALID);
        assertEquals(1, selection.selected.size());
        assertEquals(
            Long.valueOf(WirelessNodeIndexCodec.pack(7, 7, 7)),
            Long.valueOf(selection.selected.get(0).packed));
        assertEquals(0L, selection.selected.get(0).distSq);
    }
}
