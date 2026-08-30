package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link KeyFormat} 纯逻辑单测（v0.8.0 G1，零 Minecraft 类加载）。
 * <p>
 * 缓存命中口径：{@code KeyFormat.parseComputations()}（实算计数）在首次解析后不应随重复解析增长，
 * 即重复解析（含坏键）全部命中缓存、不再 split/parseInt。
 */
public class KeyFormatTest {

    /** 合法键：正负维度 / 正负坐标 / int 边界值（与原 parseKey 语义逐字一致） */
    @Test
    public void parseLegalWithNegativesAndBounds() {
        assertArrayEquals(new int[] { 0, 1, 2, 3 }, KeyFormat.parse("0:1:2:3"));
        assertArrayEquals(new int[] { -1, -200, 64, 32768 }, KeyFormat.parse("-1:-200:64:32768"));
        assertArrayEquals(
            new int[] { Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE },
            KeyFormat.parse(
                Integer.MIN_VALUE + ":" + Integer.MAX_VALUE + ":" + Integer.MIN_VALUE + ":" + Integer.MAX_VALUE));
    }

    /** 坏格式：段数不为 4 / 非整数 / 空段 / 溢出 / null / 空串（均返回 null 且同样走缓存） */
    @Test
    public void parseBadFormats() {
        assertNull(KeyFormat.parse(null));
        assertNull(KeyFormat.parse(""));
        assertNull(KeyFormat.parse("1:2:3"));
        assertNull(KeyFormat.parse("1:2:3:4:5"));
        assertNull(KeyFormat.parse("a:b:c:d"));
        assertNull(KeyFormat.parse("0:1:2:x"));
        assertNull(KeyFormat.parse("0::2:3"));
        assertNull(KeyFormat.parse("1 :2:3:4"));
        assertNull(KeyFormat.parse("0:1:2:99999999999"));
    }

    /** 缓存命中：重复解析 100 次零实算（无 split/parseInt） */
    @Test
    public void repeatedParseHitsCacheWithoutRecompute() {
        String key = "314159:26:5:35";
        int[] first = KeyFormat.parse(key);
        assertArrayEquals(new int[] { 314159, 26, 5, 35 }, first);
        int computed = KeyFormat.parseComputations();
        for (int i = 0; i < 100; i++) {
            assertArrayEquals(first, KeyFormat.parse(key));
        }
        assertEquals("重复解析应全部命中缓存", computed, KeyFormat.parseComputations());
    }

    /** 返回值为缓存条目克隆：调用方改写不污染缓存，且每次返回互不为同一数组 */
    @Test
    public void returnedArrayIsDefensiveClone() {
        String key = "123321:4:5:6";
        int[] arr = KeyFormat.parse(key);
        int[] again = KeyFormat.parse(key);
        assertNotSame("每次返回应为克隆", arr, again);
        arr[0] = 999999;
        arr[1] = -999999;
        assertArrayEquals("调用方改写不得污染缓存", new int[] { 123321, 4, 5, 6 }, KeyFormat.parse(key));
    }

    /** 坏键缓存：重复解析坏键零实算（避免每轮反复 split 抛 NumberFormatException） */
    @Test
    public void badKeyIsCachedWithoutRecompute() {
        String bad = "not:a:key!";
        assertNull(KeyFormat.parse(bad));
        int computed = KeyFormat.parseComputations();
        assertNull(KeyFormat.parse(bad));
        assertNull(KeyFormat.parse(bad));
        assertNull(KeyFormat.parse(bad));
        assertEquals("坏键应缓存后不再实算", computed, KeyFormat.parseComputations());
    }

    /**
     * 容量上限：灌入超过上限（4096）的不同键必然触发整体清空（否则条目数会超上限），
     * 清空后热点键重新解析仍正确。条目数不恒为 1（清空后剩余 = 先行用例的缓存数，与方法执行顺序有关），
     * 故断言"小于上限"而非精确值。
     */
    @Test
    public void cacheClearsAtCapacityLimit() {
        for (int i = 0; i < 4200; i++) {
            KeyFormat.parse("98765" + i + ":0:0:" + i);
        }
        assertTrue("超限应触发整体清空重建", KeyFormat.cacheSize() >= 1 && KeyFormat.cacheSize() < 4096);
        assertArrayEquals(new int[] { 314159, 26, 5, 35 }, KeyFormat.parse("314159:26:5:35"));
    }
}
