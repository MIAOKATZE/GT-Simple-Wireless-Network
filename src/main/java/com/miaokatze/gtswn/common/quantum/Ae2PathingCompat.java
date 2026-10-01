package com.miaokatze.gtswn.common.quantum;

/**
 * AE2 寻路代际兼容探针（计划 gtswn_ae2_face_channel_fix_plan_20261001124511 §3.2）。
 *
 * <p>
 * 判据：AE2 rv3-beta-1073+ 的 {@code appeng.me.pathfinding.PathingCalculation} 引入了嵌套类
 * {@code $ControllerFace}（每方向 32 通道的 face 分桶模型）；beta-3 代际（≤1050）无此类。
 * 该单一谓词同时供 mixin 门控（{@code GtswnMixinPlugin.shouldApplyMixin}）与总频道公式
 * 桥接项（{@code QuantumNetworkStatsCache} 调用侧）使用，保证「mixin 生效」与「公式补项」
 * 天然同侧，杜绝口径漂移。
 * </p>
 *
 * <p>
 * 探测走 {@code Launch.classLoader.getClassBytes}：只查类路径资源，不定义类、不触发目标
 * 静态初始化，mixin config plugin 阶段安全。{@code Launch.classLoader} 为 null 或探测抛出
 * 任何异常时保守返回 false（等于 v1.8.14 现状：32 上限 + 原公式），不会崩溃。
 * </p>
 */
public final class Ae2PathingCompat {

    /** AE2 face 模型的存在性判据类（rv3-beta-1073+ 新增的 PathingCalculation 嵌套类）。 */
    public static final String CONTROLLER_FACE_CLASS = "appeng.me.pathfinding.PathingCalculation$ControllerFace";

    /** 可注入的字节码查找器：默认走 LaunchClassLoader，单测注入假实现覆盖正反两例。 */
    interface ClassBytesResolver {

        byte[] getClassBytes(String className) throws Exception;
    }

    /** 测试钩子：非 null 时直接作为谓词结果（绕过探测与记忆化）。生产恒为 null。 */
    private static volatile Boolean overrideForTest;
    /** 测试钩子：非 null 时替代默认 LaunchClassLoader 查找。生产恒为 null。 */
    private static volatile ClassBytesResolver resolverForTest;
    /** 单次记忆化：volatile 保证跨线程安全发布（竞态下至多重复探测一次，结果幂等）。 */
    private static volatile boolean probeDone;
    private static boolean probeResult;

    private Ae2PathingCompat() {}

    /**
     * 当前 AE2 是否具备 controller face 分桶模型（rv3-beta-1073+）。
     * 结果在进程生命周期内记忆化；mixin 门控与公式调用侧共用本谓词。
     */
    public static boolean hasControllerFaceModel() {
        Boolean override = overrideForTest;
        if (override != null) {
            return override;
        }
        if (!probeDone) {
            probeResult = probeControllerFaceClass();
            probeDone = true;
        }
        return probeResult;
    }

    private static boolean probeControllerFaceClass() {
        ClassBytesResolver resolver = resolverForTest;
        if (resolver == null) {
            resolver = Ae2PathingCompat::launchClassLoaderBytes;
        }
        try {
            return resolver.getClassBytes(CONTROLLER_FACE_CLASS) != null;
        } catch (Throwable t) {
            // 保守：探测异常按旧代际处理（= 现状 32 上限，无崩溃）
            return false;
        }
    }

    private static byte[] launchClassLoaderBytes(String className) throws Exception {
        Object classLoader = net.minecraft.launchwrapper.Launch.classLoader;
        if (classLoader == null) {
            return null;
        }
        return ((net.minecraft.launchwrapper.LaunchClassLoader) classLoader).getClassBytes(className);
    }

    /**
     * 测试钩子（同效 @VisibleForTesting）：强制谓词真值并清除记忆化；null 解除强制。
     * 测试后必须复位（@After）。
     */
    static void setOverride(Boolean value) {
        overrideForTest = value;
        probeDone = false;
        probeResult = false;
    }

    /** 测试钩子：注入假 resolver 并清除记忆化；null 恢复默认 LaunchClassLoader 查找。 */
    static void setResolver(ClassBytesResolver resolver) {
        resolverForTest = resolver;
        probeDone = false;
        probeResult = false;
    }
}
