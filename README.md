<h1 align="center">GT-Simple-Wireless-Network</h1>
<p align="center"><strong><em>GTNH Wireless Energy Network Mod</em></strong><br><strong><em>GTNH 无线电网模组</em></strong></p>

A GregTech New Horizons mod that adds **wireless energy monitoring, transfer, and redstone control** to the GTNH wireless EU network. It provides portable and block-based monitors, wireless energy tap terminals, and wireless energy covers — all craftable at LV tier — enabling intelligent grid analysis, redstone logic output, and seamless wireless energy transfer for any machine.

一个 GregTech New Horizons 模组，为 GTNH 无线 EU 网络添加**无线能量监控、传输和红石控制**。提供便携式和方块式监视器、无线网络链路终端和无线能量覆盖板——全部可在 LV 阶段合成——实现智能电网分析、红石逻辑输出和任意机器的无线能量传输。

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

A handheld device that displays a HUD overlay when in inventory. Shows real-time wireless network energy, EU/t change rate, and GT-style power tier — all without placing any block.

背包内自动显示 HUD 的手持设备。实时显示无线电网能量、EU/t 变化率和 GT 风格功率等级——无需放置任何方块。

- **Three display modes**: Off / Normal counting / Scientific notation
- **Smart dEU/dt**: Intelligent change rate calculation with automatic zero-detection timeout
- **GT Power Display**: Amperage + voltage tier format (e.g., "§b2A HV")

<p align="center"><img src="images/README-Portable_Wireless_Network_Monitor-CN1.png" width="300"> <img src="images/README-Portable_Wireless_Network_Monitor-CN2.png" width="300"><br><em>科学计数模式 — 充电状态 (left) & 放电状态 (right)</em></p>

***

## Wireless Energy Tap & Covers / 无线网络链路终端与覆盖板

<p align="center"><img src="images/README-Portable_Wireless_Network_Tap.jpg" width="400"><br><em>链路终端与覆盖板 / Energy Tap and Covers</em></p>

### Wireless Energy Tap / 无线网络链路终端

A portable item that connects any machine to the wireless EU network. Shift+right-click to switch between Energy mode (draw from network, 15% loss) and Power mode (output to network, no loss). Dynamic texture reflects current mode.

便携物品，将任意机器连接到无线 EU 网络。Shift+右键切换能源模式（从电网获取，15%损耗）和动力模式（向电网输出，无损耗）。动态纹理反映当前模式。

### Wireless Energy Covers / 无线能量覆盖板

Block-based versions of the Energy Tap, installed as covers on any GT machine:

覆盖板版本的链路终端，安装在任何 GT 机器上：

- **能源覆盖板 / Energy Cover**: Draws EU from wireless network into the machine (15% loss)
- **动力覆盖板 / Dynamo Cover**: Outputs EU from the machine into wireless network (no loss)
- Both support configurable voltage, amperage, interval, and single-transfer energy via right-click

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

## Changelog / 更新日志

| Version   | Changes                                                                                                                                                                                   |
| --------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **1.0.0** | • GTNH 2.9.0 beta-1 compatibility (GT5U 5.09.52.594)• Migrated to jvmDowngrader• Adapted to new GT5U API: `checkMachine` signature, `CustomIcon`→`custom()`, `.dot()`→`.hint()`           |
| **0.2.0** | • Added Wireless Energy Tap• Added Wireless Energy Covers (Energy/Power modes)• Added cover recipes• Added network loss (15% deducted in Energy mode)• Fixed cover texture display issues |
| **0.1.2** | • Added MTEMonitor base class• Fixed cross-save cache persistence• Fixed HUD default enabled issue                                                                                        |
| **0.1.1** | • Added Wireless Energy Monitor• Redstone control (High/Low/Hysteresis)• Dynamic status textures                                                                                          |
| **0.1.0** | • Code structure optimization• Added portable wireless network monitor                                                                                                                    |

***

## Tech Stack / 技术栈

- Java 17→8 (JVM Downgrader) / Minecraft 1.7.10 / Forge 10.13.4.1614
- Dependencies: GT5-Unofficial, GTNHLib, StructureLib, ModularUI, ModularUI2, AE2

## License / 许可证

See LICENSE file.
详见 LICENSE 文件。
