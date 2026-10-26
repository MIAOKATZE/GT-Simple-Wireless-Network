# 2026-10-26 追加审查与修正（候选，未发布）

> 审查起点：`3f7aac2bcfcdd46a217b3d7716504480b6ebaa8f`；目标仍是既有 Draft PR #87，基于 #86，不另建发布版本。依用户要求保持不启用 Work，先进行 Astra high 多角度独立审查，再集中修正。`RELEASE_VERSION = 2.0.25` 未变。
> **本轮源码以包含本文的 PR #87 修正提交为准，不在提交内写自引用 SHA。** 下表依据本轮实际修正源码核对；执行回执单列于下。旧 290 suites／1,546 tests、旧 JAR 与专服启动仅证明修正前快照，不能用于本轮候选。

## 已修正问题与回归入口

| 项目 | 问题与修正边界 | 源码／回归入口 |
| --- | --- | --- |
| EU 固定来源授权 | 保留 AE 面板不代表授权面板 owner 当前无线账户。EU scalar、归一化曲线、raw history 均要求当前账户对应的精确根 `SourceKey`（epoch/account/generation）和当前 ACL；移除根 EU 或换队不能借 AE 继续读取。AE-only 使用独立 render/history 基线；发送／动作重放复验原账户、绑定及 EU 授权。旧无档案 bearer 行为未重定义。 | `MonitoringProfiles.canReadPortableEU`；`WirelessEURequestQueue`；`PortableMonitorRequestQueue`；`PortableProfileEUScopeTest`、`PortableSnapshotReuseTest` |
| 空档案转移 | 无来源时无法证明接收方原生权限，转移现明确拒绝，避免向任意 UUID 倾倒档案、绕过 owner 创建配额。空档案类型推断／不能重填的旧限制仍保留。 | `MonitoringProfiles.transfer`；`MonitoringProfilesTest` |
| 摘要／详情连续显示 | summary/detail 的 `aeWindow` 投影差异不再被误当来源失效；同一模式真正窗口变化仍会断开旧显示，撤权不保留敏感缓存。 | `PortableSuperDashboard`；`PortableDisplayContinuityTest` |
| AE 时钟回退 | 运行中及持久未来采样锁检测回退：旧 active timeline 存为显式归档，再开启新窗口，避免未来锁等待和跨回退 rate/rollup 拼接。每数据集最多新建 64 个时钟归档；额度耗尽则持久暂停、保留既有归档及当前历史，不静默删除。 | `AEMonitorController.tickSampling/sample`；`AEMonitorDataSet.resetSampleClock/observeClock`；`AEMonitorClockLifecycleTest`、`AESourceHistoryTest` |
| AE Web 投影与新鲜度 | 写入／加载维护 sampled-key 与 sample-tick 索引，先取有界排序前缀再复制历史，不构造旧全量 snapshot。来源时间取保留非空序列中最旧有效采样；未知／未来／暂停返回 0，保守显示 cached，不借另一新序列冒充 LIVE。 | `PortableProfileAdapter.exportAE/aeSampleTick`；`AEMonitorDataSet`；`PortableAESampleTickTest` |
| Web 发布租约 | API 主线程成功发布（包括内容不变）才续 10 秒单调时钟租约；过期认证 API 请求返回无正文 503，涵盖 HEAD／条件请求。发送前再验快照／租约，失效可直接关闭响应。HTTP 轮询不续租，浏览器感知时点仍取决于轮询／退避。 | `ReadonlyWebAdapter.publish/currentPublication/handle`；`ReadonlyWebAdapterTest` |
| DIT 冻结页预算进度 | legacy／delta 大页进入现有 `offerStatus` queued-BULK reservation，而非仅靠不可见 `trySend` 重试；每 terminal 至多一个冻结待发页，只有 sent callback 推进页游标／发出基线，发送前复验实际物品、连接、来源与 ACL；撤权取消页且不前移生产者；新 client session 取消旧待发页，授权 supplier 抛出 RuntimeException 时清待发标记，避免生产者卡死。 | `DeviceTerminalRequestQueue.queueFrozenPage`；`DeviceFrozenPageBudgetTest` |

Java 路径分别位于 `src/main/java/com/miaokatze/gtswn/` 和对应 `src/test/java/com/miaokatze/gtswn/`；方法／测试入口供精确复核，不代替执行回执。

## 验证与非承诺

- 执行格式化、编译、全量测试、打包与格式检查成功：**293 suites／1,566 tests，1,563 passed、0 failures、0 errors、3 项显式 opt-in 压测 skipped**；983 main classes 均 Java 8 major 52。首轮 1,565 tests 的 2 项 fixture failures／0 errors／3 skips 仍作为失败过程记录保留，修正 fixture 并新增 DIT session／授权异常回归后全量重跑，不能抹去失败或把它当生产缺陷通过证据。
- 963 个源码文件 manifest SHA-256：`4c23d6c813875ab82588b6d09cdedc85e6ac3d839184479969825e8f663ecb15`；最终回执确认验证及运行前后源码未变。精确 JAR：2,698,619 bytes，SHA-256 `4a1d5b90ab1bb8ecd0be6fe1b7c6e2ac5f723f0dd4a98452e6c0844d1180d156`；制品名不是源码出处，不是已发布产品。
- 该精确 JAR 在官方 GTNH 2.9.0-RC-2 + Core 0.1.22 隔离专服到达 `Done` 后正常 `stop`，退出 0，64.05 秒，最低可用 5,532 MiB，无强停。结果 **PASS_WITH_WARNINGS**；非致命日志仍存在，无新无候选对照归因。只证明有限专服启停，无客户端进世界／玩法验收。数据见本轮最终验证回执；不是第二次独立执行。
- Web 投影引用／卡片／历史预算不等于所有传递原生授权工作有硬上界：AE／量子安全 provider 遍历、provider ACL 回调和控制器结构成员检查仍可能随原生状态增长。4096 source-reference 配额不是 provider／ACL 项数或总 tick-time 上限，未声称 TPS／上行节省率。
- 未执行真实旧存档、复杂拓扑、客户端 UI、多人慢链路、生产 HTTP/TLS 或暂停／恢复端到端验收。本轮精确制品的有限启动不能替代这些场景；旧启动回执也不挪作新候选证据。
- 不批准、不合并、不发布；Plan 追加任务／验收证据，Wiki 只更新对应候选机制。保护 Plan `sum/`、`workflow/` 与 Wiki 稳定旧页；源码永久锚点须等最终 SHA 核实后更新，不用临时工作区地址。
