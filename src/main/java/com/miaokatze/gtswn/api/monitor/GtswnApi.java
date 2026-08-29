package com.miaokatze.gtswn.api.monitor;

/**
 * GTSWN 公开监控 API 入口（v1.7.9 新增）。
 * <p>
 * 静态无状态单例，直接调用即可，无需在 mod 生命周期任何阶段注册或初始化：
 * 
 * <pre>
 * 
 * {
 *     &#64;code
 *     Optional<NetworkSnapshot> snap = GtswnApi.getMonitorApi()
 *         .getNetworkSnapshot(owner);
 * }
 * </pre>
 * <p>
 * 两个 getter 始终返回非 null 实例；数据集为懒加载，首次查询时才从世界存档挂载。
 * 所有方法的线程与防御语义见 {@link IGtswnMonitorApi} / {@link IGtswnMonitorControlApi}
 * （仅服务端主线程调用；客户端逻辑侧返回 empty/false）。
 */
public final class GtswnApi {

    /** 内部实现实例（包私有实现类，不属于公共契约，外部勿直接引用） */
    private static final GtswnMonitorApiImpl IMPL = new GtswnMonitorApiImpl();

    private GtswnApi() {}

    /**
     * 获取只读监控 API。
     *
     * @return 只读监控 API 实例（非 null）
     */
    public static IGtswnMonitorApi getMonitorApi() {
        return IMPL;
    }

    /**
     * 获取受控交互 API。
     *
     * @return 受控交互 API 实例（非 null）
     */
    public static IGtswnMonitorControlApi getControlApi() {
        return IMPL;
    }
}
