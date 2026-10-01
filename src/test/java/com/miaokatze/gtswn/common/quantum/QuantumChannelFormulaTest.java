package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * {@link QuantumChannelFormula} 与 {@link Ae2PathingCompat} 纯逻辑单测
 * （计划 gtswn_ae2_face_channel_fix_plan_20261001124511 §S2.3，零 Minecraft 类加载）。
 *
 * <p>
 * 口径对拍：① bridgeFacesGranted=false → 原值不变（1050/beta-3 代际回归断言）
 * ② true → 原值 + N×32（1073/RC-1 代际）③ 0 桥 → 原值 ④ 桥接谓词：
 * 仅「UNKNOWN 且对端 TileController」计入。探针：无 LaunchClassLoader 环境
 * 默认 false（保守 = 现状），注入 resolver 覆盖正反两例；@After 必复位测试钩子。
 * </p>
 */
public class QuantumChannelFormulaTest {

    /** 物理面项复刻：6 面控制器暴露 6 面 × 32（computeTotalChannels 原式的典型值） */
    private static final int EXPOSED_6FACES = 6 * 32;

    @After
    public void resetProbeHooks() {
        // 测试后必须复位：强制真值与假 resolver 都不得泄漏到其他测试
        Ae2PathingCompat.setOverride(null);
        Ae2PathingCompat.setResolver(null);
    }

    // ==================== totalChannels ====================

    /** 1050 口径回归：bridgeFacesGranted=false 时严格返回原式，桥接数不影响结果 */
    @Test
    public void legacyFormulaWithoutBridgeFaces() {
        assertEquals("单控制器原式", EXPOSED_6FACES, QuantumChannelFormula.totalChannels(EXPOSED_6FACES, 0, false));
        assertEquals("5 桥但未授权面 → 原式不变", EXPOSED_6FACES, QuantumChannelFormula.totalChannels(EXPOSED_6FACES, 5, false));
        assertEquals("双控制器结构（12 面 8 共享 → 4 暴露面）", 4 * 32, QuantumChannelFormula.totalChannels(4 * 32, 17, false));
    }

    /** 1073 口径：授权面时原值 + N×32 */
    @Test
    public void bridgeFacesAdd32Each() {
        assertEquals(EXPOSED_6FACES + 5 * 32, QuantumChannelFormula.totalChannels(EXPOSED_6FACES, 5, true));
        assertEquals(2 * 32 + 3 * 32, QuantumChannelFormula.totalChannels(2 * 32, 3, true));
        assertEquals("0 暴露面 + 9 桥 → 仅桥接项", 9 * 32, QuantumChannelFormula.totalChannels(0, 9, true));
    }

    /** 0 桥：授权与否结果一致（mixin 门控开启但网络无桥接连接） */
    @Test
    public void zeroBridgesKeepsFormula() {
        assertEquals(EXPOSED_6FACES, QuantumChannelFormula.totalChannels(EXPOSED_6FACES, 0, true));
        assertEquals(
            QuantumChannelFormula.totalChannels(EXPOSED_6FACES, 0, false),
            QuantumChannelFormula.totalChannels(EXPOSED_6FACES, 0, true));
    }

    /** 常量口径：32 对应 AE2 CONTROLLER_FACE_CHANNELS / 单连接上限 */
    @Test
    public void bridgeFaceChannelConstant() {
        assertEquals(32, QuantumChannelFormula.BRIDGE_VIRTUAL_FACE_CHANNELS);
    }

    // ==================== isBridgeConnection（桥接谓词） ====================

    /** 仅「UNKNOWN 且对端 TileController」计入；UNKNOWN 非 TileController 对端不计入 */
    @Test
    public void bridgePredicateRequiresUnknownAndController() {
        assertTrue("UNKNOWN→控制器：计", QuantumChannelFormula.isBridgeConnection(true, true));
        assertFalse("方向确定→不计（即使对端是控制器）", QuantumChannelFormula.isBridgeConnection(false, true));
        assertFalse("UNKNOWN 但对端非控制器（gtswn 节点互连等）→ 不计", QuantumChannelFormula.isBridgeConnection(true, false));
        assertFalse(QuantumChannelFormula.isBridgeConnection(false, false));
    }

    // ==================== Ae2PathingCompat 探针 ====================

    /** 无 LaunchClassLoader 的 JUnit 环境：默认探测保守 false（= v1.8.14 现状） */
    @Test
    public void probeDefaultsFalseWithoutLaunchClassLoader() {
        assertFalse("JUnit 环境无 Launch.classLoader → 保守 false", Ae2PathingCompat.hasControllerFaceModel());
    }

    /** 测试钩子强制真值：true 侧（生产中由门控与公式共用同一谓词） */
    @Test
    public void probeOverrideControlsResult() {
        Ae2PathingCompat.setOverride(Boolean.TRUE);
        assertTrue(Ae2PathingCompat.hasControllerFaceModel());
        Ae2PathingCompat.setOverride(Boolean.FALSE);
        assertFalse(Ae2PathingCompat.hasControllerFaceModel());
    }

    /**
     * 正例（锚定真实 dev classpath）：dev 依赖即 AE2 rv3-beta-1073（dependencies.gradle:46），
     * $ControllerFace.class 应在 test classpath；注入按 classloader 资源实现的 resolver 后
     * 谓词为 true。
     */
    @Test
    public void probePositiveAgainstRealDevClasspath() {
        ClassLoader classLoader = getClass().getClassLoader();
        String resource = Ae2PathingCompat.CONTROLLER_FACE_CLASS.replace('.', '/') + ".class";
        assertTrue(
            "AE2 rv3-beta-1073 dev jar 应携带 PathingCalculation$ControllerFace（正例锚点）",
            classLoader.getResource(resource) != null);
        Ae2PathingCompat.setResolver(
            name -> classLoader.getResource(name.replace('.', '/') + ".class") != null ? new byte[1] : null);
        assertTrue(Ae2PathingCompat.hasControllerFaceModel());
    }

    /** 反例：空 resolver（类缺失 = beta-3 代际）→ false；resolver 抛异常同走保守 false */
    @Test
    public void probeNegativeWithEmptyResolver() {
        Ae2PathingCompat.setResolver(name -> null);
        assertFalse("类缺失必 false（1050 门控的结构性挡板）", Ae2PathingCompat.hasControllerFaceModel());
        Ae2PathingCompat.setResolver(name -> { throw new IllegalStateException("boom"); });
        assertFalse("探测异常保守 false，不崩溃", Ae2PathingCompat.hasControllerFaceModel());
    }

    /** 记忆化与复位：resolver 注入/复位都会清除记忆化，@After 复位后回到默认探测结果 */
    @Test
    public void probeMemoizationResetsWithHooks() {
        Ae2PathingCompat.setResolver(name -> new byte[1]);
        assertTrue(Ae2PathingCompat.hasControllerFaceModel());
        // 换成空 resolver：记忆化被清除，重探为 false（若记忆化未清除将残留 true）
        Ae2PathingCompat.setResolver(name -> null);
        assertFalse(Ae2PathingCompat.hasControllerFaceModel());
        // 复位为默认查找：JUnit 环境回到保守 false
        Ae2PathingCompat.setResolver(null);
        assertFalse(Ae2PathingCompat.hasControllerFaceModel());
    }
}
