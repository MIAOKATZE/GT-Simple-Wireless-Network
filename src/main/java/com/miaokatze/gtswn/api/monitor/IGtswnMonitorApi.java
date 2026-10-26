package com.miaokatze.gtswn.api.monitor;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.World;

/**
 * GTSWN 公开只读监控 API（v1.7.9 引入，v1.7.14 新增 getNetworkAverageEut 并统一注释口径）。
 * <p>
 * 面向其他 mod 的无线电网 / AE 监视只读查询入口，全部方法返回 {@link Optional}，
 * 数据不存在时返回 {@code Optional.empty()} 而非抛出异常。返回的快照对象均为
 * 不可变副本，读取不产生任何写入副作用（不 markDirty、不创建空数据集、不改采样节奏）。
 * <ul>
 * <li>电网侧数据来源：overworld perWorldStorage 的网络信息数据集
 * （{@code gtswn_network_info_data}，按玩家当前原生无线账户查询，不创建缺失的 GT 团队），
 * 由 {@code NetworkInfoMonitorScheduler} 每 100t（5s）对活跃数据集采样填充</li>
 * <li>AE 侧数据来源：信息屏所在世界 perWorldStorage 的 AE 监视数据集
 * （{@code gtswn_ae_monitor_data}，坐标解析当前可证明的原生来源限定 key），
 * 由信息屏方块对每个被监视 key 采样填充</li>
 * </ul>
 * <p>
 * 线程与生命周期约定：<b>仅服务端主线程调用</b>（ServerTick 生命周期内）。
 * 客户端逻辑侧调用不抛异常，一律返回 {@code Optional.empty()}。
 * 服务器未启动（如 mod 加载早期）同样返回 empty。
 */
public interface IGtswnMonitorApi {

    /**
     * 查询指定玩家无线电网的最新快照（最新一次采样点）。
     * <p>
     * 字段语义见 {@link NetworkSnapshot}。数据集从未采样（如刚激活尚未到首个采样点）时返回 empty。
     * 仅查询当前原生账户；来源不明的旧 owner 历史不作为当前余额快照返回。
     *
     * @param owner 玩家 UUID；null 返回 empty
     * @return 最新采样快照；数据集不存在或无采样点返回 empty
     */
    Optional<NetworkSnapshot> getNetworkSnapshot(UUID owner);

    /**
     * 查询指定玩家电网历史的某一时间窗口（8 级窗口之一）。
     * <p>
     * 窗口常量 0-7 依次为：5 分钟 / 1 小时 / 8 小时 / 24 小时 / 7 天 / 1 个月 / 3 个月 / 1 年。
     * 样本列表旧→新排序，每窗口最多 61 点（超出时保留最新 61 点），语义详见 {@link HistoryWindow}。
     * 当前原生账户尚无任何样本时，可整套回退查询原 owner 的 legacy 历史；该旧档不能证明原生来源连续性。
     * 当前账户已有首个样本后，仅查询当前账户所有窗口，不把旧 owner 大窗口拼入新来源。
     *
     * @param owner    玩家 UUID；null 返回 empty
     * @param windowId 窗口编号，合法范围 0-7；越界返回 empty
     * @return 该窗口的历史样本；数据集不存在返回 empty（数据集存在但窗口为空时返回样本数为 0 的窗口）
     */
    Optional<HistoryWindow> getNetworkHistory(UUID owner, int windowId);

    /**
     * 查询指定玩家电网在某一时间窗口内的平均 EU/t（v1.7.14 新增）。
     * <p>
     * 对 owner 的电网监控数据集在 windowId 窗口内的全部采样点取 eut 的算术均值，
     * 正=平均净充电，负=平均净放电。均值口径与本 mod 自身显示一致：流入计数链派生窗口
     * 的聚合值即上一级窗口最近 N 点 eut 均值，设备信息终端「平均 EU/t」列同为样本值均值，
     * 均不采用 EU 存量首尾斜率。窗口编号语义与 {@link #getNetworkHistory} 相同
     * （0-7 共 8 级窗口，样本旧→新，最多 61 点）。
     * legacy 回退与 {@link #getNetworkHistory} 相同，仅在当前原生账户尚无任何样本时使用原 owner 历史。
     * <p>
     * 纯只读查询：不创建空数据集、不 markDirty、不影响采样节奏；返回值为不可变
     * {@code Double}。仅服务端主线程调用（ServerTick 生命周期内），客户端逻辑侧调用
     * 不抛异常，一律返回 {@code Optional.empty()}。
     *
     * @param owner    玩家 UUID；null 返回 empty
     * @param windowId 窗口编号，合法范围 0-7；越界返回 empty
     * @return 窗口内样本 eut 的算术均值；owner 无数据集、窗口内 0 样本、
     *         windowId 越界或客户端逻辑侧调用返回 empty
     */
    Optional<Double> getNetworkAverageEut(UUID owner, int windowId);

    /**
     * 查询指定网络信息屏的 AE 监视快照（全部已存系列的最新数量、实际跨度平均速率及 legacy 标记）。
     * <p>
     * 字段语义见 {@link AEMonitorSnapshot}。信息屏从未记录任何 key 时返回 empty。
     * 已加载且身份可证明的面板优先查询当前来源；即使当前来源尚无样本，也不回退到同坐标旧档。
     * 卸载或当前身份不可证明时，仅保留原坐标 legacy 历史查询；不会猜测某个来源归档。
     * 显式使用 {@code legacy:dimensionId:x:y:z} 可读取原坐标归档，即使该坐标已有当前来源。
     *
     * @param panelWorld 信息屏方块所在的世界实例（AE 数据按世界维度独立落盘）；null 返回 empty
     * @param panelKey   信息屏坐标、{@code legacy:} 前缀坐标归档，或明确的来源限定历史 key；null 或空串返回 empty
     * @return AE 监视快照；数据集不存在或无记录返回 empty
     */
    Optional<AEMonitorSnapshot> getAEMonitorSnapshot(World panelWorld, String panelKey);

    /**
     * 查询指定信息屏某个被监视 key（物品/流体）在某一时间窗口的历史。
     * <p>
     * 窗口常量 0-7 语义同 {@link #getNetworkHistory}；样本字段换算语义见 {@link HistorySample}。
     * 来源选择与 {@link #getAEMonitorSnapshot} 相同；旧坐标归档和明确的来源限定归档不自动拼接。
     * 对坐标传入旧未版本化 subKey 时，若原坐标归档含该 key，仍查询该归档，避免当前来源遮挡旧历史。
     *
     * @param panelWorld 信息屏方块所在的世界实例；null 返回 empty
     * @param panelKey   信息屏坐标、{@code legacy:} 前缀坐标归档，或明确的来源限定历史 key；null 或空串返回 empty
     * @param subKey     快照返回的完整版本化物品/流体身份；旧未版本化 key 仍可查询 legacy 历史，
     *                   其 NBT 归属未知，不能与新系列自动拼接；null 或空串返回 empty
     * @param windowId   窗口编号，合法范围 0-7；越界返回 empty
     * @return 该 key 的历史样本；数据集不存在返回 empty（key 无样本时返回样本数为 0 的窗口）
     */
    Optional<HistoryWindow> getAEMonitorHistory(World panelWorld, String panelKey, String subKey, int windowId);
}
