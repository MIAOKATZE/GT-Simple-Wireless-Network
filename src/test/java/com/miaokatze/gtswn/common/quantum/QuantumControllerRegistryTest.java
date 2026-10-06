package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * {@link QuantumControllerRegistry#computeTotalChannels(Set)} 纯算术单测
 * （纯 JDK 参数，打包坐标用同源 {@link QuantumControllerRegistry#pack} 构造）。
 *
 * <p>
 * 守卫语义（v1.8.19 口径回归断言）：总频道预算是控制器结构暴露面数的纯函数
 * （(块数×6 − 相邻面数)×32，每个相邻对两侧各计 1 面），量子节点数 / 桥接连接数
 * 不进入公式——v1.8.15 的「每桥接 +32」镜像项已删除，本测试防止其回归。
 * </p>
 */
public class QuantumControllerRegistryTest {

    /** ①单控制器：6 面全暴露 → 6×32 = 192 */
    @Test
    public void singleControllerSixFaces() {
        Set<Long> structure = new HashSet<>();
        structure.add(QuantumControllerRegistry.pack(0, 64, 0));
        assertEquals(6 * 32, QuantumControllerRegistry.computeTotalChannels(structure));
    }

    /** ②纵向相邻 2 块：1 个相邻对计 2 面 → (2×6−2)×32 = 320 */
    @Test
    public void verticalPairDeductsSharedFaces() {
        Set<Long> structure = new HashSet<>();
        structure.add(QuantumControllerRegistry.pack(0, 64, 0));
        structure.add(QuantumControllerRegistry.pack(0, 65, 0));
        assertEquals((2 * 6 - 2) * 32, QuantumControllerRegistry.computeTotalChannels(structure));
    }

    /** ③2×2 平面 4 块：4 个相邻对计 8 面 → (4×6−8)×32 = 512 */
    @Test
    public void twoByTwoPlaneDeductsAllAdjacencies() {
        Set<Long> structure = new HashSet<>();
        structure.add(QuantumControllerRegistry.pack(0, 64, 0));
        structure.add(QuantumControllerRegistry.pack(1, 64, 0));
        structure.add(QuantumControllerRegistry.pack(0, 64, 1));
        structure.add(QuantumControllerRegistry.pack(1, 64, 1));
        assertEquals((4 * 6 - 8) * 32, QuantumControllerRegistry.computeTotalChannels(structure));
    }

    /** ④空结构 → 0（无控制器即无预算，调用方按结构不存在处理） */
    @Test
    public void emptyStructureYieldsZero() {
        assertEquals(0, QuantumControllerRegistry.computeTotalChannels(Collections.<Long>emptySet()));
    }
}
