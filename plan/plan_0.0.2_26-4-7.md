# GT-Simple-Wireless-Network 改进计划 (Plan.md)

本文档记录了代码审查过程中发现的潜在改进点，按照**轻重缓急**（P0 - P3）进行分类。

---

## 🔴 P0: 严重问题 (必须修复)
这些问题是导致模组崩溃、配置失效或逻辑错误的根本原因。

1.  **Config.java - 配置项与逻辑脱节**
    *   **状态**: ✅ 已修复
    *   **处理**: 采纳建议，保留 `MetaTileEntityID.java` 中的硬编码 `BASE = 14600` 以支持按类型分段管理 ID。删除了 `Config.java` 中未生效的 `preferredMetaBase` 配置项，仅保留 `metaIdOffset` 用于微调。
2.  **CommonProxy.java / MachineLoader.java - 注册时机风险**
    *   **状态**: ✅ 已优化
    *   **处理**: 调整了 `CommonProxy.init()` 的逻辑顺序和注释，明确强调机器注册（通过 GT 队列在 `preInit` 末尾触发）必须先于创造模式物品栏初始化完成。增强了 `CreativeTabManager` 的空值兜底逻辑。
3.  **ClientProxy.java - 包声明丢失 (已修复但需复查)**
    *   **状态**: ✅ 已确认
    *   **处理**: 经复查，`ClientProxy.java` 头部已包含正确的 `package com.miaokatze.gtswn.main;` 声明，编译正常。

---

## 🟠 P1: 重要改进 (强烈建议)
这些问题影响模组的稳定性、兼容性和开发体验。

1.  **RegistrationManager.java - 异常隔离不足**
    *   **状态**: ✅ 已修复
    *   **处理**: 在 `registerAll()` 循环中增加了 `try-catch` 块，确保单个注册任务失败不会导致整个模组加载崩溃，并记录了错误日志。
2.  **TestMachineRegistrar.java - 本地化键名不一致**
    *   **状态**: ✅ 已修复
    *   **处理**: 将语言文件中的 `wts.` 前缀统一修改为 `gtswn.machine.test.`，并同步更新了中英文 `.lang` 文件。
3.  **GTSWNItemList.java - 关键方法未实现**
    *   **状态**: ⏸️ 暂缓
    *   **处理**: 考虑到当前版本暂无电动物品或矿物词典需求，决定保持现状，待后续功能扩展时再行补全。
4.  **MetaTileEntityID.java - ID 冲突风险**
    *   **状态**: ❌ 不予修改
    *   **处理**: 经确认 `BASE = 14600` 在当前环境下无冲突。计划在未来实现“自动检索可用 ID 区间”的功能，而非手动调整基准值。

---

## 🟡 P2: 代码优化 (建议实施)
这些改进能提升代码质量和可维护性。

1.  **MachineRegistrar.java - 缺乏幂等性保护**
    *   **状态**: ❌ 不予修改
    *   **处理**: 经分析，`registerAll()` 在正常生命周期中仅被调用一次。为避免过度设计增加代码复杂度，决定暂不引入幂等性检查，待未来出现实际需求时再行优化。
2.  **MTETestMachine.java - 逻辑耦合**
    *   **状态**: ⏸️ 暂缓
    *   **处理**: 鉴于该类仅为测试用途，且后续将用于验证无线输电核心功能，目前的硬编码实现已足够，暂不进行解耦重构。
3.  **Config.java - 冗余配置**
    *   **状态**: ✅ 已修复
    *   **处理**: 删除了 `greeting` 字段及其相关的读取逻辑，精简了配置文件结构。
4.  **CommonProxy.java - 日志规范化**
    *   **状态**: ✅ 已优化
    *   **处理**: 移除了所有 Emoji 表情和装饰性分隔线，转而使用更清晰的步骤标识（如 `[1/3]`）和更详细的文本描述，提升了日志在生产环境下的可读性与检索效率。

---

## 🟢 P3: 细节完善 (可选)
锦上添花的修改。

1.  **命名规范**: 统一枚举命名风格（如 `LuV` vs `LUV`）。
    *   **状态**: ❌ 不予修改
    *   **处理**: 采纳建议，遵循 GregTech 社区对电压等级的习惯写法（EV, IV, LuV），保持代码风格与上游一致。
2.  **注释本地化**: 将代码中的英文日志和异常提示统一改为中文或提取到配置。
    *   **状态**: ✅ 已优化
    *   **处理**: 已将模组内所有的开发者日志（Log）和异常提示信息统一修改为中文，提升了作为开发者的阅读效率。
3.  **依赖声明**: 在 `@Mod` 注解中明确声明对 GregTech 版本的依赖。
    *   **状态**: ✅ 已修复
    *   **处理**: 在主类 [GTSimpleWirelessNetwork.java](file:///D:/Program/MC/mod%20KF/Work/GT-Simple-Wireless-Network/src/main/java/com/miaokatze/gtswn/main/GTSimpleWirelessNetwork.java) 的 `@Mod` 注解中增加了 `dependencies = "required-after:gregtech;"`，确保模组在 GregTech 之后加载，并在缺失依赖时给出明确提示。
4.  **反射注册**: 考虑使用反射自动扫描 `Registrar` 类，减少 `MachineLoader` 的手动维护成本。
    *   **状态**: ❌ 不予修改
    *   **处理**: 采纳建议，坚持“性能为先”的原则。手动注册虽然增加了少量维护成本，但提供了最佳的启动性能和最直观的调试体验，符合当前模组的轻量化定位。

---

> **备注**: 本计划由 AI 助手协助生成，旨在帮助开发者系统性地优化 GT-Simple-Wireless-Network 模组。
