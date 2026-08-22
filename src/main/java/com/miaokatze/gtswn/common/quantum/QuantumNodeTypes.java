package com.miaokatze.gtswn.common.quantum;

import appeng.api.networking.IGridHost;

/**
 * 量子节点类型注册表（O2-B08：quantum→tile 拆环的 Class 注入点）。
 *
 * <p>
 * AE2 的 {@code grid.getMachines(Class)} 必须传具体机器类，无法纯接口化；
 * tile 侧节点类在类加载时把自身 Class 注册进来，quantum 侧网格枚举经
 * {@link #nodeClass()} 取用，quantum→tile 的编译期类引用归零。
 * usedChannels 口径（单节点取其全部连接的 max）留在调用点——该循环只触
 * AE2 API（IGridNode/IGridConnection），无 tile 耦合，无需随注册表迁移。
 * </p>
 */
public final class QuantumNodeTypes {

    /** 已注册的量子节点类（tile 侧类加载期写入，主线程网格枚举只读；volatile 保守可见性） */
    private static volatile Class<? extends IGridHost> nodeClass = null;

    private QuantumNodeTypes() {}

    /**
     * tile 侧节点类加载时注册。节点类在 {@code BlockRegistrar.registerTileEntity}
     * （preInit）即被加载，早于任何网格枚举（运行期主线程）。
     */
    public static void register(Class<? extends IGridHost> clazz) {
        nodeClass = clazz;
    }

    /** 已注册的量子节点类；未注册（理论不可达）返回 null，调用方按零节点处理 */
    public static Class<? extends IGridHost> nodeClass() {
        return nodeClass;
    }
}
