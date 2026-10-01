package com.miaokatze.gtswn.common.quantum;

/**
 * 量子网络总频道公式的纯算术工具（计划 gtswn_ae2_face_channel_fix_plan_20261001124511 §3.4）。
 *
 * <p>
 * 零 Minecraft/AE2 类依赖，供 JUnit 直测（{@code QuantumChannelFormulaTest}，零 MC 类加载）。
 * {@code QuantumControllerRegistry.computeTotalChannels} 本体与其 javadoc 不动（排除项），
 * 桥接项算术收敛在本类，由 {@code QuantumNetworkStatsCache} 调用侧组合。
 * </p>
 */
public final class QuantumChannelFormula {

    /**
     * 每条桥接连接在 AE2 face 模型（rv3-beta-1073+）下获得的虚拟 face 通道数，
     * 对应 AE2 {@code PathingCalculation.CONTROLLER_FACE_CHANNELS}（即 AE2 单连接上限，
     * 与控制器/稠密缆线的 32 通道口径一致）。
     */
    public static final int BRIDGE_VIRTUAL_FACE_CHANNELS = 32;

    private QuantumChannelFormula() {}

    /**
     * 总频道 = 暴露面项 + 桥接项。
     *
     * @param exposedFaceChannels 物理 face 项：控制器暴露于非控制器面的面数 × 32
     *                            （{@code QuantumControllerRegistry.computeTotalChannels} 原式）
     * @param bridgeFaceCount     无方向（UNKNOWN）且对端为 TileController 的桥接连接计数
     * @param bridgeFacesGranted  AE2 是否实际按「每连接独立 face」授容量（与 mixin 门控同侧：
     *                            {@code Ae2PathingCompat.hasControllerFaceModel()}）；false 时严格返回原式
     * @return 总频道数
     */
    public static int totalChannels(int exposedFaceChannels, int bridgeFaceCount, boolean bridgeFacesGranted) {
        if (!bridgeFacesGranted) {
            // 1050 口径（无 face 模型）：原式不变，桥接项 = 0
            return exposedFaceChannels;
        }
        return exposedFaceChannels + bridgeFaceCount * BRIDGE_VIRTUAL_FACE_CHANNELS;
    }

    /**
     * 桥接连接判定（供统计遍历使用，纯谓词便于单测）。
     *
     * @param directionUnknown      连接方向是否为 {@code ForgeDirection.UNKNOWN}
     *                              （gtswn 桥接经 createGridConnection 建立，恒为 UNKNOWN）
     * @param otherSideIsController 对端 machine 是否为 {@code TileController}
     * @return true = 该连接在 face 模型下独占一个 32 通道虚拟 face，计入桥接项
     */
    public static boolean isBridgeConnection(boolean directionUnknown, boolean otherSideIsController) {
        return directionUnknown && otherSideIsController;
    }
}
