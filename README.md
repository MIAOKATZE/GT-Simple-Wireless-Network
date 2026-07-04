<h1 align="center">GT-Simple-Wireless-Network</h1>
<p align="center"><strong><em>GTNH Wireless Energy Network Mod</em></strong><br><strong><em>GTNH 无线电网模组</em></strong></p>

A GregTech New Horizons mod that adds **wireless energy monitoring, transfer, and redstone control** to the GTNH wireless EU network. It provides portable and block-based monitors, wireless network link terminals, and link terminal covers (Energy/Power) — all craftable at LV tier — enabling intelligent grid analysis, redstone logic output, and seamless wireless energy transfer for any machine.

一个 GregTech New Horizons 模组，为 GTNH 无线 EU 网络添加**无线能量监控、传输和红石控制**。提供便携式和方块式监视器、无线网络链路终端和链路终端覆盖板（能源/动力）——全部可在 LV 阶段合成——实现智能电网分析、红石逻辑输出和任意机器的无线能量传输。

> \[!NOTE]
> This is an unofficial mod. Please avoid discussing this mod in official GTNH forums.
> 这是一个非官方模组，讨论此模组时请注意场合。

## Downloads & Requirements / 下载与版本需求

| GTNH         | GTSWN  | Maintenance / 维护 |
| ------------ | ------ | :--------------: |
| 2.9.0 beta-1 | 1.0.0+ |        ✔️        |
| 2.8.4        | 0.2.0  |        ❌️        |

***

## Wireless Energy Monitor / 无线能量监视器

<p align="center"><img src="images/Wireless_Energy_Monitor_AL.png" width="400"><br><em>无线能量监视器 / Wireless Energy Monitor</em></p>

**无线能量监视器 / Wireless Energy Monitor** — A single-block machine that displays real-time wireless network energy status with advanced redstone control. Supports 5 redstone modes (Off/High/Low/High-Hysteresis/Low-Hysteresis) with parametric threshold settings. Dynamic texture switching reflects redstone output state.

无线能量监视器，单方块机器，实时显示无线电网能量状态，具备高级红石控制。支持5种红石模式（关闭/高电平/低电平/正向滞后/反向滞后），参数化阈值设定，状态贴图动态切换。

- **Redstone Modes**: Off → High (signal when EU > threshold) → Low (signal when EU < threshold) → High-Hysteresis → Low-Hysteresis
- **Display Modes**: Normal counting (1,234,567 EU) / Scientific notation (1.235×10^6 EU)
- **Smart EU/t**: Real-time change rate with GT-style amperage + voltage tier display (e.g., "2A HV")

<p align="center"><img src="images/Wireless_Energy_Monitor_CN.png" width="300"> <img src="images/Wireless_Energy_Monitor_EN.png" width="300"><br><em>中文界面 (left) & English interface (right)</em></p>

***

## Portable Wireless Network Monitor / 便携无线监测终端

A handheld device that displays a HUD overlay when in inventory (including any Baubles accessory slot). Shows real-time wireless network energy, EU/t change rate, and GT-style power tier — all without placing any block. Works correctly on both single-player and dedicated servers via C→S→C network packet synchronization.

背包内（含任意 Baubles 饰品栏）自动显示 HUD 的手持设备。实时显示无线电网能量、EU/t 变化率和 GT 风格功率等级——无需放置任何方块。通过 C→S→C 网络包同步，在单人世界和专用服务器中均可正常使用。

- **Three display modes**: Off / Normal counting / Scientific notation
- **Smart dEU/dt**: Intelligent change rate calculation with automatic zero-detection timeout
- **GT Power Display**: Amperage + voltage tier format (e.g., "§b2A HV")
- **Baubles Support**: Can be placed in any Baubles accessory slot; HUD scans main hand → Baubles → inventory
- **Server Compatible**: Correctly displays EU on dedicated servers via client-request / server-response network synchronization

<p align="center"><img src="images/README-Portable_Wireless_Network_Monitor-CN1.png" width="300"> <img src="images/README-Portable_Wireless_Network_Monitor-CN2.png" width="300"><br><em>科学计数模式 — 充电状态 (left) & 放电状态 (right)</em></p>

***

## Wireless Energy Tap & Covers / 无线网络链路终端与覆盖板

<p align="center"><img src="images/README-Portable_Wireless_Network_Tap.jpg" width="400"><br><em>链路终端与覆盖板 / Energy Tap and Covers</em></p>

### Wireless Energy Tap / 无线网络链路终端

A portable item that connects any machine to the wireless EU network. Shift+right-click to switch between Energy mode (draw from network, configurable loss, default 15%) and Power mode (output to network, virtual-cable drain via capacity buffer). Dynamic texture reflects current mode.

便携物品，将任意机器连接到无线 EU 网络。Shift+右键切换能源模式（从电网获取，可配置损耗，默认15%）和动力模式（向电网输出，通过电容量缓冲池像虚拟导线般取电）。动态纹理反映当前模式。

### Link Terminal (Energy/Power) / 链路终端（能源/动力）

Both are **void covers** (虚空覆盖板) — they have no recipe and exist only as NBT-driven attachments placed by the Wireless Energy Tap. Their behavior is governed entirely by NBT parameters assigned on right-click; bare cover items without these parameters are inert. All NBT parameters persist across save/load.

两种覆盖板均为**虚空覆盖板**——无合成配方，仅作为由无线网络链路终端右击附着的 NBT 驱动覆盖板存在。其行为完全由右击赋予的 NBT 参数决定；裸覆盖板无参数时无效。所有 NBT 参数跨存档/退出持久化。

#### Link Terminal (Energy) / 链路终端（能源）

Acts as a **virtual power source** — maintains an internal capacity buffer and continuously injects EU into the bound machine like a real cable.

作为一个**虚拟电源**——内部维护电容量缓冲池，像导线一样持续向被绑定机器输入 EU。

- **Capacity / 电容量**: `V × A × 800` ticks (computed on configuration; immediately refilled to capacity)
- **Per-tick behavior / 每 tick 行为**: Injects `min(V × A, machine_needed, storedEU)` EU per tick into the bound machine — behaves like a real cable
- **Refill / 补满**: Every 600 ticks, deducts `(capacity − storedEU) × (1 + downlink loss)` EU from the wireless network to refill the buffer
- **Special case / 特例**: Single-block Arc Furnace is force-set to 4A (identified via recipe map `RecipeMaps.arcFurnaceRecipes`, not class name)
- **Unload / 卸载**: On cover removal, remaining buffer is returned to the network at `(1 − uplink loss)` rate

#### Link Terminal (Power) / 链路终端（动力）

Acts as a **virtual cable** — drains the machine's output into an internal buffer and uploads to the wireless network periodically. Capacity is fixed at `2^63 − 1` (GT5U MAX Battery).

作为一个**虚拟导线**——读取机器的输出存入内部缓冲池，并周期性上送到无线电网。电容量固定为 `2^63 − 1`（GT5U 太·终极电池）。

- **Capacity / 电容量**: `Long.MAX_VALUE` (2^63 − 1)
- **Per-tick behavior / 每 tick 行为**: Reads `getOutputVoltage()` / `getOutputAmperage()` from the machine; if V > 0, drains `min(available, V × A)` EU into the buffer (respecting `getMinimumStoredEU()`). Non-output machines (V = 0) are skipped to avoid draining energy from machines that don't produce any.
- **Blocking / 阻断**: `letsEnergyOut() = false` blocks the machine from outputting to real cables on the cover's side, preventing double consumption
- **Upload / 上送**: Every 600 ticks, the buffer is uploaded to the wireless network at `(1 − uplink loss)` rate
- **Unload / 卸载**: On cover removal, remaining buffer is returned to the network at `(1 − uplink loss)` rate

***

## Redstone Control System / 红石控制系统

The Wireless Energy Monitor features a 5-mode redstone control system:

无线能量监视器具备5模式红石控制系统：

| Mode            | Behavior                                              |
| --------------- | ----------------------------------------------------- |
| Off             | No redstone output                                    |
| High            | Output signal when EU > threshold                     |
| Low             | Output signal when EU < threshold                     |
| High-Hysteresis | Output when EU > param1, cancel only when EU < param2 |
| Low-Hysteresis  | Output when EU < param2, cancel only when EU > param1 |

| 模式   | 行为                 |
| ---- | ------------------ |
| 关闭   | 不输出红石信号            |
| 高电平  | 电量 > 阈值时输出信号       |
| 低电平  | 电量 < 阈值时输出信号       |
| 正向滞后 | >参数1时输出，必须<参数2才能取消 |
| 反向滞后 | <参数2时输出，必须>参数1才能取消 |

***

## Admin Commands / 管理员命令

OP level 4 required. / 需要 OP 等级 4。

- **`/gtswn global_energy_trans <fromUUID> <toUUID>`** — One-time transfer of all EU from one UUID's network to another. Use when a player's UUID changes (e.g., premium → third-party account). / 一次性将 fromUUID 网络的所有 EU 迁移到 toUUID 网络。用于玩家换账户（正版转第三方等）导致 UUID 变化后迁移 EU。

- **`/gtswn global_energy_join <memberUUID> <leaderUUID>`** — Permanently join a player's network into another player's network via GT5U's team system (`SpaceProjectManager`). After joining, the member's wireless EU operations automatically resolve to the leader's network. / 通过 GT5U 团队系统将玩家的网络永久加入另一个玩家的网络。加入后，成员的无线 EU 操作自动解析到队长的网络。

***

## Tech Stack / 技术栈

- Java 17→8 (JVM Downgrader) / Minecraft 1.7.10 / Forge 10.13.4.1614
- Dependencies: GT5-Unofficial, GTNHLib, StructureLib, ModularUI, ModularUI2, AE2

## License / 许可证

See LICENSE file.
详见 LICENSE 文件。
