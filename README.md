模组说明  Mod Description
=====================

| 说明 | Description |
| --- | --- |
该工程目前处于基础架构验证阶段，已完成测试用多方块机器 (MTEMultiTestMachine) 的开发与专属配方系统集成。适配版本 GTNH 2.8.x。 | This project is currently in the infrastructure verification stage. Development of the test multiblock machine (MTEMultiTestMachine) and integration of the exclusive recipe system have been completed. Target compatibility: GTNH 2.8.x. |

## 功能介绍  Features

| 功能 | Feature |
| --- | --- |
| **便携无线监测终端**<br>• 背包内自动显示 HUD<br>• 三种显示模式：关闭/常规计数/科学计数<br>• 实时显示电网状态和 EU/t 变化率<br>• GT 风格电流+电压等级显示（15 级色阶）<br>• 智能 dEU/dt 计算<br><br>![科学计数 - 充电](images/README-Portable_Wireless_Network_Monitor-CN1.png)<br>*科学计数模式 - 电网充电状态*<br><br>![科学计数 - 放电](images/README-Portable_Wireless_Network_Monitor-CN2.png)<br>*科学计数模式 - 电网放电状态* | **Portable Wireless Network Monitor**<br>• Automatic HUD display when in inventory<br>• Three display modes: Off/Normal/Scientific<br>• Real-time grid status and EU/t change rate<br>• GT-style amperage + voltage tier display (15-level color gradient)<br>• Smart dEU/dt calculation<br><br>![Scientific Notation - Charging](images/README-Portable_Wireless_Network_Monitor-EN1.png)<br>*Scientific mode - Grid charging status*<br><br>![Scientific Notation - Discharging](images/README-Portable_Wireless_Network_Monitor-EN2.png)<br>*Scientific mode - Grid discharging status* |


|       | 更新日志                                              | Update log                                                                                                    |
|-------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| 0.1.0 | • 代码结构优化<br>• 添加便携无线监测终端 |• Code structure optimization<br>• Added portable wireless network monitor|
| <span style="color:gray">0.0.5</span> | <span style="color:gray">• 添加 TestCoinE，消耗电量获取测试机器<br>• 实现物品电量管理和操作功能</span> | <span style="color:gray">• Added TestCoinE, consume electricity to obtain test machines<br>• Implemented item electricity management and operation</span>|
| <span style="color:gray">0.0.4</span> | <span style="color:gray">• 添加 MTEMultiTestMachine<br>• 添加 TestCoin<br>• 添加新机器类型及配方<br>• 添加 NEI 适配</span> | <span style="color:gray">• Added MTEMultiTestMachine<br>• Added TestCoin<br>• Added new machine type and recipes<br>• Added NEI support</span>|
| <span style="color:gray">0.0.3</span> | <span style="color:gray">• 清理冗余配置项，精简项目结构</span> | <span style="color:gray">• Removed redundant configurations and streamlined project structure</span>|
| <span style="color:gray">0.0.2</span> | <span style="color:gray">架构重构与功能完善<br>• 添加创造模式物品栏支持<br>• 采用 GregTech 队列注册方式，实现集中注册<br>• 批量导入自定义材质系统<br>• 清理冗余配置，优化项目结构</span> | <span style="color:gray">Architecture Refactoring & Feature Enhancement<br>• Added creative mode tab support<br>• Adopted GregTech queue registration for centralized machine registration<br>• Implemented batch custom texture import system<br>• Cleaned up redundant configurations and optimized project structure</span> |
| <span style="color:gray">0.0.1</span> | <span style="color:gray">• 添加 MTETestMachine 作为测试机器</span> | <span style="color:gray">• Add MTETestMachine as the testing machine</span> |



---



## 致谢  Acknowledgements

感谢 **通义灵码（Tongyi Lingma）** 在本项目开发过程中提供的智能代码辅助和技术支持。

Special thanks to **Tongyi Lingma** for providing intelligent code assistance and technical support during the development of this project.

