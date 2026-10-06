package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * {@link Ae2PathingCompat} 探针纯逻辑单测（零 Minecraft 类加载）。
 *
 * <p>
 * v1.8.19 起探针仅余 mixin 门控职责（v1.8.15 引入的总频道桥接补项公式已删除，
 * 总预算单一口径 = 结构暴露面数 × 32）；测试迁自已删除的频道公式测试类。
 * 探针口径：无 LaunchClassLoader 环境默认 false（保守 = 现状），注入 resolver
 * 覆盖正反两例；@After 必复位测试钩子。
 * </p>
 */
public class Ae2PathingCompatTest {

    @After
    public void resetProbeHooks() {
        // 测试后必须复位：强制真值与假 resolver 都不得泄漏到其他测试
        Ae2PathingCompat.setOverride(null);
        Ae2PathingCompat.setResolver(null);
    }

    /** 无 LaunchClassLoader 的 JUnit 环境：默认探测保守 false（= v1.8.14 现状） */
    @Test
    public void probeDefaultsFalseWithoutLaunchClassLoader() {
        assertFalse("JUnit 环境无 Launch.classLoader → 保守 false", Ae2PathingCompat.hasControllerFaceModel());
    }

    /** 测试钩子强制真值：true 侧（生产中该谓词仅服务 mixin 门控） */
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
