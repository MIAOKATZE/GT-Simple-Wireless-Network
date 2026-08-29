package com.miaokatze.gtswn.api.monitor;

import java.util.UUID;

/**
 * GTSWN 公开受控交互 API（v1.7.9 新增）。
 * <p>
 * 面向其他 mod 的电网监控数据集受控交互入口，仅提供两个无破坏性操作：
 * 激活数据集（保持采样不断线）与即时采样（立即写入一个采样点）。
 * 不提供删除、清理或改写历史的入口——运维清理仍走本 mod 命令。
 * <p>
 * 线程与生命周期约定：<b>仅服务端主线程调用</b>（ServerTick 生命周期内）。
 * 客户端逻辑侧调用不抛异常，一律返回 false。服务器未启动同样返回 false。
 */
public interface IGtswnMonitorControlApi {

    /**
     * 激活指定玩家的电网监控数据集（等价于一个网络信息屏方块在持续请求）。
     * <p>
     * 语义对齐信息屏每 tick 的请求刷新：取或创建该玩家的数据集并更新 lastRequestTick，
     * 使 {@code NetworkInfoMonitorScheduler} 在 5 分钟（6000t）内持续为其采样。
     * 不存在则新建数据集（会产生一次 markDirty 写入），属于预期的激活副作用。
     * <p>
     * 调用方应周期性调用（建议间隔远小于 5 分钟）以维持活跃状态，
     * 停止调用后数据集按既有超时语义自然停止采样（历史保留，不清理）。
     *
     * @param owner 玩家 UUID；null 返回 false
     * @return true=已激活；客户端逻辑侧 / 服务器未启动 / owner 为 null 返回 false
     */
    boolean activateNetworkDataset(UUID owner);

    /**
     * 对指定玩家立即执行一次电网采样（不等 100t 周期，复刻调度器单条采样体）。
     * <p>
     * 内部流程与 {@code NetworkInfoMonitorScheduler} 的周期采样一致：
     * 数据集采样锁（距离上次采样 &ge; 100t 才放行，多屏共享去重语义相同）→
     * 从 GT 无线网络读取 EU 总量 → 写入采样点（内部计算瞬时 EU/t）→ markDirty。
     * <p>
     * 采样锁未取得（100t 内已有采样）返回 false，不重复写入；
     * 该方法不更新 lastRequestTick，不改变数据集的活跃/超时判定，
     * 需要持续采样请配合 {@link #activateNetworkDataset} 使用。
     *
     * @param owner 玩家 UUID；null 返回 false
     * @return true=已写入一个采样点；锁未取得 / 客户端逻辑侧 / 服务器未启动返回 false
     */
    boolean requestNetworkSample(UUID owner);
}
