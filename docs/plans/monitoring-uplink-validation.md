# 监测整合：审查地图、迁移边界与验证记录

本文件对应继首批 PR #86 之后的整合实现，覆盖服务端档案、共享采样、增量同步和可选只读 Web。
整合 PR 以 `feat/monitor-uplink-slice1-20261026` 为基分支，依赖首批 PR #86，避免在本 PR 重复展示首批差异；两者均保持 Draft，不自动合并。
原始实施目标见 [整合计划](monitoring-uplink-integration.md)。本文件不把“已编写代码／测试”视为“最终候选已通过验证”。

## 1. 当前验证状态

- 2026-10-26 最终源码完成 `spotlessApply`、`compileJava`、`compileTestJava`、`test`、`assemble`、`spotlessCheck`，退出码 0。290 suites / 1,546 tests；0 failures / 0 errors，3 项显式 opt-in 压力测试跳过。
- 982 个 main class 全部为 Java 8 major 52。运行 JAR 2,692,677 bytes，SHA-256 `044779001b381eda62ed7be8f0848ec81647f9e7c6bdd0ead4ed3829e00cb172`。已格式化源码 manifest SHA-256 `654e00adbe3d3ed6bef71f327dbd8608b9dcf97c67ae590eb4fffea3a0c90d1f`，最终测试前后未变。
- 隔离 RC2 + Core 0.1.22 服务端使用该精确 JAR 到达 Done 并正常 stop，退出码 0；66.05 秒，未强停或触发资源守卫，未进行客户端进世界。结果为 PASS_WITH_WARNINGS：整合包存在非致命 HTTP／证书／contributor 及 BlockOres/AIOOBE 警告，未重新跑无候选基线，不能断言归因或警告全无。提交 SHA 以包含本记录的 PR head 为准。
- 已有历史测试产物不自动证明后续改动后的源码通过；任何修复后都需重跑受影响检查，并在最终提交前完成全量检查。
- 服务端隔离启动／正常停止须单独记录；启动通过不等于旧存档迁移、游戏内 GUI 或真实上行优化通过。
- 客户端存档／进世界、真实多人负载、慢客户端与 Web 反向代理部署不属于本记录已验证的结果。
- 不直接修改默认分支、不合并、不发布版本；源码由独立 Draft PR 提交审核。

## 2. 模块审查地图

路径均相对于 `src/main/java/com/miaokatze/gtswn/`。

| 模块 | 主要实现入口 | 首要审查／回归点 |
| --- | --- | --- |
| 档案与旧引用迁移 | `common/monitoring/MonitoringProfiles`、`MonitorProfile`、`SourceKey` | 明确导入、幂等映射、恢复、ACL、转移、固定来源、损坏元数据不得降级为公开旧数据 |
| 玩家管理入口 | `common/gui` 中档案管理界面、`network/C2SPacketMonitorProfileAction`、`S2CPacketMonitorProfiles` | 只使用真实服务器手持物品；操作权限、列表分页、不泄露配对密钥 |
| DIT 真实设备 | `common/device/DeviceMachineIdentity`、`DeviceSampleScheduler`、`DeviceTerminalDataStore`、`mixins/minecraft/MonitorDeviceIdentityMixin` | 同坐标替换识别、重启恢复、旧历史保留、在线与离线采样去重 |
| EU 原生账户 | `common/monitoring/NativeWirelessEUSnapshot`、`NativeEUHistory`、`WirelessEUPayload`、`common/panel/EUCacheBridge` | 真实无线账户身份、团队变化边界、原采样间隔、精确大整数 |
| AE 网络身份与共享读取 | `common/monitoring/ae/MonitorIdentityGridCache`、`GridIdentityState`、`common/panel/AEMonitorController` | 原生存储回调、启动稳定期、拆分／合并、无权限／源卸载、相同 grid 采样共享 |
| AE 条目与历史 | `common/panel/AEMonitorIdentity`、`AEMonitorDataStore`、`AEMonitorDataSet` | 完整 NBT 身份、数量不属于条目身份、旧条目不误拼接、每屏来源历史边界 |
| 量子适配 | `common/monitoring/QuantumProfileAdapter`、既有 `common/quantum/QuantumControllerRegistry` 与 `QuantumTerminalAccess` | 控制器网络身份与节点 owner 分离；原生配对／安全检查继续生效；不制造终端凭据 |
| 协商与预算 | `network/telemetry/MonitorCapabilities`、`TelemetryTransport`、`TelemetryByteBudget` | 连接绑定、旧端回退、全局／玩家预算、有界队列、发送前再次授权、最新更新重试 |
| DIT 字段差量 | `common/device/DeviceTerminalRequestQueue`、`network/PacketSyncDeviceTerminalDelta`、`client/DeviceTerminalClientCache` | 完整 ACK、冻结分页、会话／epoch、缓存丢失、删除／排序、异常帧拒绝 |
| 原始历史与物理屏 | `network/PortableMonitorRequestQueue`、`PortableHistoryPatch`、`PortablePanelMetadata`、`NetworkPanelBroadcastPort` | 配置与历史分离、游标窗口、全窗归一化、ACK 换基线、旧 S35 合并不得破坏增量链 |
| 工程／量子同步 | `common/engineering/EngineeringDashboardSessions`、`client/engineering/EngineeringDashboardCache`、量子同步包与可视化同步 | 类型化正文、差量恢复、订阅有效性、队列拥塞后的最后更新 |
| UI 连续性 | `client/engineering`、`client/PortablePanelView`、`client/HudSessionLifecycle` | 暂停／重开缓存、新鲜度、不把旧值变成实时读数或伪造零值、撤权清理 |
| 只读 Web | `common/monitoring/web/`、`common/monitoring/MonitoringRuntime`、`assets/gtswn/web/` | owner 同意与 OP 发布分开、固定来源、认证先于 304、撤权、限额、生命周期 |

相关测试集中在 `src/test/java/com/miaokatze/gtswn/common/monitoring/`、`common/panel/`、`common/device/`、`network/telemetry/` 和相应客户端测试目录。测试名称与源码存在只代表覆盖意图，执行结果以最终候选记录为准。

## 3. “共有化”的边界

已有量子系统不是“一件终端一个网络”，本次也不把它迁成“一个玩家一个网络”。需要分别理解：

1. **原生网络**：量子控制器注册结构、控制器代次／安全身份、AE grid 拓扑与原生安全机制。它决定真实网络是什么。
2. **节点／设备所有权**：用于原生权限判定。某玩家拥有节点，不意味着该玩家 UUID 就是网络 UUID。
3. **监测档案**：保存固定来源引用、可恢复显示配置、读者 ACL、离线作业意图和 Web 同意。档案 owner 是这份监测授权的管理者，不是重新定义原生网络的所有者。
4. **终端物品**：继续保留必要的原生配对信息，新档案引用只提供受控入口。档案不会替代量子加密凭据或绕过原生配对。
5. **数据投影**：同一来源可共享底层采样，但不同屏幕的条目选择、历史投影、查看者和传输仍有成本。不能宣称所有历史／传输都已经全局去重。

当前采取“适配已有原生系统”的实现，而不是强行统一量子、AE、EU 的存储结构。更大范围的网络所有权重构不属于本次交付承诺；若审查证明必须改变，应单独说明旧存档映射与权限影响。

## 4. 迁移与旧存档

### 原地升级

- 升级前备份完整世界、玩家物品与相关 mod 数据。只备份新档案文件不能恢复旧引用和原生网络状态。
- 旧终端不自动变成档案；明确导入时检查真实物品、来源与原生授权。
- DIT 旧 UUID 的数据复制到新档案 UUID，并持久保存迁移映射；重复导入不能重复建档或让另一持有者认领档案。原旧 UUID 数据不删除。
- 新档案来源固定；之后的扫描、放置或右击绑定不应偷偷扩大已分享或已同意发布的范围。EU-only 档案的屏幕后续出现 AE 连接、配置或缓存时按 SOURCE_REPLACED 处理；未通电但残留 AE 配置／数据的屏幕不能伪装成 EU 导入。需要恢复原生 AE 后明确新导入，不能借旧共享授权扩展范围。
- 量子恢复仍需先获得同源合法原生配对。档案本身不恢复密钥，不能当通用网络接管令牌。
- 控制器迁移或结构拆分／合并使固定量子来源失效时，owner 可手持该档案终端执行 `/monitorprofile release <档案 UUID>`。此操作仅解除这一件物品的档案引用及导入标识，保留原生配对凭据、服务端旧迁移映射、旧档案／历史／ACL 和副本引用；随后按原生流程重新配对、明确导入新档案。不是把旧档案的授权和 Web 同意搬到新网络。
- EU 使用原生无线账户标识；团队／账户变化不能把无关历史拼到同一个来源。

### AE 持久身份

- 新监测身份由 AE cache 经 `IGridStorage` 保存。旧存档缺少该字段时，在原生网络稳定后建立身份，而不是把运行时 grid ID 或坐标冒充持久身份。
- 正常保存／加载的身份连续性是实现目标，必须以实际 AE 回调顺序验证，不能只凭纯状态机单测宣称所有重启场景通过。
- 已知网络合并、已发布网络拆分或无法证明连续性的重建采用保守新边界；已授权档案不能默默继承新拓扑内额外的来源。
- 历史键包括屏幕身份及 SourceKey。旧坐标历史和以前来源代次保留，不盲目合并；保留不等于旧历史已无缝接入新曲线。
- AE 物品／流体身份使用版本化、数量无关的完整标签摘要；旧未版本化历史不能被误认为新条目的连续记录。

### 降级／回滚

先关闭 Web 和显式离线作业，确认服务端停止后再恢复备份或降级。新私有档案引用不能留给不理解 ACL 的旧版本继续使用；需要移走／禁用这些物品或恢复完整匹配备份。新增 NBT 被旧版本忽略不等于权限安全兼容。

## 5. 稳定平台验收矩阵

每一项都记录候选 SHA、客户端／服务端版本、预期、实际、日志与必要截图；不得把单次启动代替以下验收。

1. **普通升级与重启**：无新字段的旧存档；创建／导入档案；保存正常退出；重启并恢复引用；核对 owner、ACL、来源、历史及关闭的 Web 状态。
2. **AE 拓扑**：单网多屏；纯线缆添加／移除；网络拆成两半；两个已有网合并；控制器／屏幕替换；区块卸载与重新加载；分别验证身份连续或边界符合政策。
3. **量子原生语义**：同玩家两张独立网、多玩家同网、多控制器结构、节点转交、调色、原生安全变化；档案不得把这些情况合并成玩家级网络。
4. **迁移幂等与损坏**：复制旧物品重复导入、更换持有者、缺失档案数据、无效 UUID、新档案引用复制、来源替换。失败应保留旧数据并拒绝越权，而非自动认领。
5. **采样生命周期**：最后一个 GUI 关闭、HUD 隐藏、物理屏附近观察者离开、离线作业开／关、玩家掉线与服务端重启。读取缓存不能伪造采样时间；离线任务不强加载区块。
6. **同步恢复**：初次全量、只有一行变化、全部变化、空集合、删除／重排、缓存丢失、旧端互通、分页中断、基线不匹配、预算拥塞。最后状态最终到达，未知基线不能被视为完整。
7. **权限撤销**：正在查看、正在分页、队列等待、Web 已缓存时撤权或转移；验证敏感缓存清理、未发送正文取消、后续请求重新授权。
8. **Web**：默认关闭；只有 owner 同意不生成页面；OP 显式发布后才可读；错误认证不能通过 ETag；隐藏页暂停；配额／撤销／正常停止有效。公网代理与真实密钥配置另行操作。
9. **性能与字节口径**：固定负载下同时记录应用包体、传输编码层与服务器网卡上行；比较单／多人、同／不同来源、冷启动／稳定期与慢客户端。另记录 MSPT、队列长度和内存，不以包数下降代替字节节省。

## 6. 已实现与仍有边界的事项

- 有界模型、档案命令与 GUI、离线意图、各类协商增量、字节预算、缓存连续性与默认关闭 Web 已有代码；最终“完成”仍取决于审查、最终构建与上述验收。
- 传输预算是本 mod 的序列化监测流量预算，不是网卡硬限速，也不拦截所有 Minecraft 包。初始／静态区块描述及控制路径并不都计入遥测队列。
- 队列 CURRENT／BULK 分类目前按编码后大小，不是全面理解 UI 语义的优先级系统。Web 有独立预算，但与游戏共用真实上行。
- 便携含面板正文的动作结果也进入预算队列：每连接最多一条待送动作结果，发送完成前不执行下一请求／动作；不同动作不合并，预算拒绝保留结果重试，并重新校验权限与真实物品。空面板轻量 ACK／撤权仍走优先 control 路径。该修复须纳入最终回归。
- 物理屏保留附近／区块观察能力；这不是逐玩家视线遮挡检测，也不声称只有正在盯着屏幕的人才收包。
- Web 默认 loopback，只读快照；公网 TLS、反向代理及入口连接限制需要运维配置，不在本 PR 自动实施。
- Web 档案聚合新鲜度采用各来源中最旧的有效采样时间；未知／未来时间按缓存状态处理，DIT 使用记录自身采样时间，不能让一个新来源把另一个旧来源一起标成 LIVE。该修复已纳入上述最终测试。
- 游戏内真实 UI、旧存档端到端迁移、拓扑复杂事件、实际带宽／MSPT 收益尚须稳定平台验证。不能预先承诺百分比或“绝无迁移问题”。

## 7. PR 提交后的 AE 与量子复核

两路独立复核分别检查 AE 原生持久身份／历史，以及量子控制器／权限／迁移。对照实际 AE 字节码确认，`GridNode.setGrid` 的旧网移除与新网加入是同步链路，仅靠 storage 的 split/join 回调不足以覆盖 live 节点迁移；本实现的移除／加入来源配对有必要。没有发现需要以玩家 ID 重建量子网络或替换现有控制器注册表的依据。

提交后确认并修复：专用服务端的 AE 新鲜度曾读取仅供客户端使用的面板镜像，导致始终返回 tick 0。现在读取当前来源限定的服务端 `AEMonitorDataStore`，包括仅图表绑定的历史；来源替换／不可用不借用归档时间。新增真实 per-world MapStorage fixture 回归；其执行结果以本文件最终验证数字为准。

已保留的低优先级限制：移除非 DIT 档案的最后一个来源后，当前类型推断会把空档案报为 `NO_PERMISSION`，不能当作可重新填充的空模板。该情况没有证明网络数据丢失或权限泄露；本 PR 不为此扩张成类型系统重构。量子终端可明确 release 后重新绑定／导入；便携用户应以新终端重新准备原生绑定并明确导入新档案，旧档案仍保留。

仍需稳定平台验证旧存档加载、离线复制终端连续迁移日志回放、区块卸载／重载、复杂拆分／合并及重启撤权。单元 fixture、字节码审查和启动检查不能替代上述世界内场景。
