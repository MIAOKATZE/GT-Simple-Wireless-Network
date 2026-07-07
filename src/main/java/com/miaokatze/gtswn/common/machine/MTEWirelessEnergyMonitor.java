package com.miaokatze.gtswn.common.machine;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.miaokatze.gtswn.common.machine.base.MTEMonitor;
import com.miaokatze.gtswn.common.machine.widgets.ScientificTextFieldWidget;
import com.miaokatze.gtswn.common.util.EUDataSet;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.metatileentity.IMetricsExporter;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.modularui2.GTGuiTextures;
import gregtech.api.modularui2.GTGuis;
import gregtech.common.misc.WirelessNetworkManager;

/**
 * 无线能量监视器 (LV)
 * <p>
 * 用于实时监控玩家所属团队的无线电网能量状态。
 * 此机器不消耗也不产生能量，只是一个信息显示设备。
 * <p>
 * 功能特性：
 * <ul>
 * <li>显示无线电网总能量（支持常规/科学计数）</li>
 * <li>智能计算 EU/t 变化率（带 GT 电压等级显示）</li>
 * <li>5 种红石控制模式（关闭/正向/反向/正向滞后/反向滞后）</li>
 * <li>动态贴图切换（根据红石输出状态）</li>
 * </ul>
 */
public class MTEWirelessEnergyMonitor extends MTEMonitor implements IMetricsExporter {

    // EU/t 计算相关
    private long lastCheckTick = 0;
    private double euPerTick = 0.0;

    // 红石检测独立时间戳（与 UI 更新解耦，2 ticks 一次 = 0.1s，保证红石快速响应）
    private long lastRedstoneCheckTick = 0;
    /** 红石检测间隔（ticks），2 ticks = 0.1 秒 */
    private static final long REDSTONE_CHECK_INTERVAL = 2L;

    // 智能 EU/t 计算相关（与 HUD 保持一致）
    // 测量历史管理（容量 61、FIFO 老化、NBT 序列化）已迁移至 EUDataSet（T4 公共工具类提取）
    private final EUDataSet dataSet = new EUDataSet();

    // UI 同步字段
    // 初始值使用本地化文本（带 § 颜色代码），避免机器刚放置、onPostTick 未执行时打开 GUI 显示无颜色文本
    private String cachedModeText = StatCollector.translateToLocal("gtswn.ui.mode.normal");
    private String cachedEUText = StatCollector.translateToLocalFormatted("gtswn.ui.wireless.energy", "0");
    private String cachedStatusText = StatCollector.translateToLocal("gtswn.ui.network.status.nochange");
    private String cachedRedstoneModeText = StatCollector.translateToLocalFormatted(
        "gtswn.ui.redstone.mode",
        StatCollector.translateToLocal("gtswn.ui.redstone.mode.off"));
    private String cachedRedstoneOutputText = StatCollector.translateToLocal("gtswn.ui.redstone.output.off");
    private String cachedModeDescText = StatCollector.translateToLocal("gtswn.ui.redstone.desc.off");

    // 红石控制相关
    private int redstoneMode = 0; // 0=关闭, 1=正向, 2=反向, 3=正向区间, 4=反向区间
    /** 锚定参数模式：0=电网电量（BigInteger），1=电网状态数值（EU/t，double→long 截断） */
    private int anchorMode = 0;
    private BigInteger param1Value = BigInteger.ZERO; // 参数1数值
    private BigInteger param2Value = BigInteger.ZERO; // 参数2数值
    private boolean redstoneOutput = false; // 红石输出状态

    // v1.2.4：长时卸载重载后的红石延迟计数器（ticks）
    // >0 时跳过 updateRedstoneOutput，保持 mSidedRedstone[] 缓存的红石信号
    // 不需要持久化：重载时由 gap 检测重新设置
    private int redstoneReloadDelay = 0;

    // 构造函数：用于注册
    public MTEWirelessEnergyMonitor(int aID, String aName, String aNameRegional) {
        super(
            aID,
            aName,
            aNameRegional,
            new String[] { net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line1"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line2"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line3"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line4"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line5") });
    }

    // 拷贝构造函数
    public MTEWirelessEnergyMonitor(String aName, String[] aDescription) {
        super(aName, aDescription);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEWirelessEnergyMonitor(mName, mDescriptionArray);
    }

    // getDescription() 已由 MTEMonitor 基类提供，无需重复实现

    @Override
    public boolean allowPullStack(IGregTechTileEntity aBaseMetaTileEntity, int aIndex, ForgeDirection side,
        ItemStack aStack) {
        return false; // 监视器不允许抽出物品
    }

    @Override
    public boolean allowPutStack(IGregTechTileEntity aBaseMetaTileEntity, int aIndex, ForgeDirection side,
        ItemStack aStack) {
        return false; // 监视器不允许放入物品
    }

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        super.onPostTick(aBaseMetaTileEntity, aTick);

        if (aBaseMetaTileEntity.isServerSide()) {
            // v1.1.11 修复：改用 getTotalWorldTime() 替代 aTick(=mTickTimer) 作为时间轴
            // 原因：mTickTimer 不持久化，世界重启后由 validate() 重置为 0，
            // 而持久化的 lastCheckTick/lastRedstoneCheckTick 保留旧大值，
            // 导致 aTick - lastCheckTick 为负数，UI 更新分支永不触发。
            // getTotalWorldTime() 持久化于 WorldInfo，重启后继续累计，时间轴一致。
            long worldTick = aBaseMetaTileEntity.getWorld()
                .getTotalWorldTime();

            // v1.2.4：gap 检测前置（必须在 dataSet.isEmpty() 短路之前执行）
            // 检测长时卸载重载：lastCheckTick > 0 排除首次放置，gap > 200L = 10秒
            // - 长时卸载（>10秒）：清空数据集（避免跨卸载斜率错误），触发30秒红石延迟
            // - 短时卸载（≤10秒）：保留旧样本，直接续传（斜率误差 ≤3.3%，可接受）
            if (lastCheckTick > 0) {
                long gap = worldTick - lastCheckTick;
                if (gap > 200L) {
                    dataSet.clear();
                    redstoneReloadDelay = 600; // 30秒 = 600 ticks
                }
            }

            // 红石检测：如果处于红石延迟期，跳过更新（保持 mSidedRedstone[] 缓存的红石信号）；
            // 否则每 2 ticks（0.1s）检测一次
            // 设计说明：红石检测频率(2 ticks)高于 EU/t 刷新频率(100 ticks)是有意为之
            // - 红石需要快速响应（0.1秒），避免漏检临界值
            // - EU/t 均值需要长窗口稳定（5秒），避免瞬时波动误判
            // 当 anchorMode=1 时，红石基于陈旧的 euPerTick 值判断，最多有 5 秒延迟，属可接受行为
            if (redstoneReloadDelay > 0) {
                redstoneReloadDelay--;
            } else if (dataSet.isEmpty() || worldTick - lastRedstoneCheckTick >= REDSTONE_CHECK_INTERVAL) {
                updateRedstoneOutput();
                lastRedstoneCheckTick = worldTick;
            }
            // UI 更新 + EU/t 计算：每 100 ticks（5秒）
            // 关键修复：使用差值检查（worldTick - lastCheckTick >= 100）替代 modulo
            // 原因：服务器卡顿跳过 100 倍数的 tick 时，modulo 检查会错过检测
            // 首次测量加速：dataSet 为空时立即记录第一个数据点
            if (dataSet.isEmpty() || worldTick - lastCheckTick >= 100L) {
                // 服务端执行 EU/t 计算和缓存文本更新
                // 客户端不执行：避免覆盖 StringSyncValue 同步过来的服务端真实值
                // （客户端的 getWirelessEU() 返回 ZERO，会导致状态恒为"无变化"）
                // calculateSmartEUT 返回电网状态文本（formatEUTStatus 内部已格式化，含 size<2/eut==0 分支）
                String calculatedStatus = calculateSmartEUT(worldTick);

                // 显示优先级：重载延迟 > 正常计算
                // 重载期间红石逻辑维持不变（避免误切换），UI 显示"重载计算中"提示用户
                if (redstoneReloadDelay > 0) {
                    cachedStatusText = StatCollector.translateToLocal("gtswn.ui.network.status.reload");
                } else {
                    cachedStatusText = calculatedStatus;
                }

                // 更新缓存文本（仅服务端），由 StringSyncValue 自动 S2C 同步给客户端
                cachedModeText = displayMode == 0 ? translate("gtswn.ui.mode.normal")
                    : translate("gtswn.ui.mode.scientific");
                cachedEUText = getWirelessEUText();
                cachedRedstoneModeText = getRedstoneModeText();
                cachedRedstoneOutputText = redstoneOutput ? translate("gtswn.ui.redstone.output.on")
                    : translate("gtswn.ui.redstone.output.off");
                cachedModeDescText = getModeDescText();
            }
        }
    }

    /**
     * 允许通用红石输出
     */
    @Override
    public boolean allowGeneralRedstoneOutput() {
        return true;
    }

    /**
     * 获取通用红石信号强度
     */
    @Override
    public byte getGeneralRS(ForgeDirection side) {
        if (!emitsRedstoneSignal()) {
            return 0;
        }

        // 根据当前锚定值和参数判断是否输出信号（anchorMode 决定数据源）
        BigInteger currentEU = getAnchorValue();
        if (currentEU == null) {
            return 0;
        }

        boolean shouldOutput = false;

        switch (redstoneMode) {
            case 1: // 正向：电量 > 参数1 时输出
                shouldOutput = currentEU.compareTo(param1Value) > 0;
                break;
            case 2: // 反向：电量 < 参数1 时输出
                shouldOutput = currentEU.compareTo(param1Value) < 0;
                break;
            case 3: // 正向区间：>参数1输出, <参数2取消（滞后）
                // v1.2.1 修复：与 updateRedstoneOutput 保持一致的滞后状态机
                if (!redstoneOutput) {
                    // 当前未输出，检查是否大于参数1（开启阈值）
                    shouldOutput = currentEU.compareTo(param1Value) > 0;
                } else {
                    // 当前已输出，检查是否仍 >= 参数2（保持条件，低于参数2才取消）
                    shouldOutput = currentEU.compareTo(param2Value) >= 0;
                }
                break;
            case 4: // 反向区间：>参数1取消, <参数2输出（滞后）
                // v1.2.1 修复：与 updateRedstoneOutput 保持一致的滞后状态机
                if (!redstoneOutput) {
                    // 当前未输出，检查是否小于参数2（开启阈值）
                    shouldOutput = currentEU.compareTo(param2Value) < 0;
                } else {
                    // 当前已输出，检查是否仍 <= 参数1（保持条件，高于参数1才取消）
                    shouldOutput = currentEU.compareTo(param1Value) <= 0;
                }
                break;
            default:
                shouldOutput = false;
        }

        // 如果应该输出，返回15强度；否则返回0
        return shouldOutput ? (byte) 15 : (byte) 0;
    }

    // 更新红石输出状态
    private void updateRedstoneOutput() {
        // 锚定值：anchorMode=0 时为电网电量，anchorMode=1 时为 EU/t（long 截断）
        BigInteger currentEU = getAnchorValue();
        boolean newOutput = false;

        switch (redstoneMode) {
            case 0: // 关闭：不输出红石信号
                newOutput = false;
                break;
            case 1: // 正向：电网电量大于参数1时输出红石信号
                newOutput = currentEU.compareTo(param1Value) > 0;
                break;
            case 2: // 反向：电网电量小于参数1时输出红石信号
                newOutput = currentEU.compareTo(param1Value) < 0;
                break;
            case 3: // 正向区间：大于参数1时输出，必须小于参数2才能取消
                if (!redstoneOutput) {
                    // 当前未输出，检查是否大于参数1
                    newOutput = currentEU.compareTo(param1Value) > 0;
                } else {
                    // 当前已输出，检查是否小于参数2
                    newOutput = currentEU.compareTo(param2Value) >= 0;
                }
                break;
            case 4: // 反向区间：大于参数1时取消，必须小于参数2才能输出
                if (!redstoneOutput) {
                    // 当前未输出，检查是否小于参数2
                    newOutput = currentEU.compareTo(param2Value) < 0;
                } else {
                    // 当前已输出，检查是否大于参数1
                    newOutput = currentEU.compareTo(param1Value) <= 0;
                }
                break;
        }

        // 如果红石状态发生变化，更新输出
        if (newOutput != redstoneOutput) {
            redstoneOutput = newOutput;
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
                // 更新 BaseMetaTileEntity 的红石信号数组
                byte signalStrength = newOutput ? (byte) 15 : (byte) 0;
                for (int i = 0; i < 6; i++) {
                    getBaseMetaTileEntity().setOutputRedstoneSignal(ForgeDirection.getOrientation(i), signalStrength);
                }
                // 通知周围方块红石信号变化
                IGregTechTileEntity baseMeta = getBaseMetaTileEntity();
                if (baseMeta.getWorld() != null && !baseMeta.getWorld().isRemote) {
                    baseMeta.getWorld()
                        .notifyBlocksOfNeighborChange(
                            baseMeta.getXCoord(),
                            baseMeta.getYCoord(),
                            baseMeta.getZCoord(),
                            baseMeta.getWorld()
                                .getBlock(baseMeta.getXCoord(), baseMeta.getYCoord(), baseMeta.getZCoord()));
                }
            }
        }
    }

    // 智能 EU/t 计算（与 HUD 逻辑一致）
    // 返回值：电网状态显示文本（由 formatEUTStatus 格式化，含 size<2/eut==0/|eut|<1 分支）
    // 副作用：更新 euPerTick（用于红石）、dataSet、lastCheckTick
    private String calculateSmartEUT(long currentTick) {
        BigInteger currentEU = getWirelessEU();

        // 记录到数据集（替代 FormatUtil.recordMeasurement，EUDataSet 内部自动 FIFO 老化）
        dataSet.add(currentEU, currentTick);

        // 计算并格式化：formatEUTStatus 内部更新 euPerTick 字段
        String statusText = formatEUTStatus();

        lastCheckTick = currentTick;

        return statusText;
    }

    /**
     * 根据数据集计算 EU/t 并格式化显示文本。
     * <p>
     * 显示逻辑（与 HUD 统一）：
     * <ul>
     * <li>size &lt; 2：暂无变化/计算中（数据不足）</li>
     * <li>eut == 0：0 (静默) —— 绝对无变化</li>
     * <li>0 &lt; |eut| &lt; 1：0 (&lt;1EU) —— 近似无变化</li>
     * <li>|eut| &gt;= 1：正常显示（数值 + 电压等级）</li>
     * </ul>
     * 副作用：更新 {@link #euPerTick} 字段（用于红石锚定）。
     *
     * @return 格式化后的电网状态文本（带 § 颜色代码）
     */
    private String formatEUTStatus() {
        // 数据不足（仅 0 或 1 个采样点），无法计算斜率
        if (dataSet.size() < 2) {
            euPerTick = 0.0;
            return StatCollector.translateToLocal("gtswn.ui.network.status.nochange");
        }

        // 由 EUDataSet 计算 EU/t 斜率（BigDecimal 精确除法）
        double eut = dataSet.calculateEUT();
        euPerTick = eut;

        // 绝对无变化（首末两点 EU 完全相等）
        if (eut == 0.0) {
            return StatCollector.translateToLocal("gtswn.ui.network.status.silent");
        }

        double absEut = Math.abs(eut);

        // 小于 1 EU/t：变化过小，近似无变化
        if (absEut < 1.0) {
            return StatCollector.translateToLocal("gtswn.ui.network.status.lessthan1");
        }

        // 正常显示：数值 + GT 电压等级
        // 上升：gtswn.ui.network.status.up = "§b电网状态: §a↑ +%s §bEU/t (§f%s§b)"
        // 下降：gtswn.ui.network.status.down = "§b电网状态: §c↓ -%s §bEU/t (§f%s§b)"
        String euPerTickStr = FormatUtil.formatNormalDouble(absEut);
        String gtPowerText = GTTierUtil.formatGTPower(eut);
        if (eut > 0) {
            return String
                .format(StatCollector.translateToLocal("gtswn.ui.network.status.up"), euPerTickStr, gtPowerText);
        } else {
            return String
                .format(StatCollector.translateToLocal("gtswn.ui.network.status.down"), euPerTickStr, gtPowerText);
        }
    }

    // 获取无线电网能量
    private BigInteger getWirelessEU() {
        if (ownerUUID == null) return BigInteger.ZERO;
        return WirelessNetworkManager.getUserEU(ownerUUID);
    }

    /**
     * 获取当前锚定比较值（统一为 BigInteger 形式）
     * <p>
     * anchorMode=0：返回电网电量（getWirelessEU）
     * anchorMode=1：返回 Math.round(euPerTick) 转 BigInteger
     * 注：euPerTick 是 double，v1.2.1 修复：使用 Math.round 替代 (long) 强转，
     * 避免 1234.56 截为 1234 这类小数丢失；负值（电网下降）也保留，
     * 配合负参数可实现"下降速率超过阈值时输出"
     */
    private BigInteger getAnchorValue() {
        if (anchorMode == 1) {
            // v1.2.1 修复：使用 Math.round 替代 (long) 强转，避免小数截断丢失
            return BigInteger.valueOf(Math.round(euPerTick));
        }
        return getWirelessEU();
    }

    // 格式化能量文本
    private String getWirelessEUText() {
        BigInteger eu = getWirelessEU();
        if (displayMode == 0) {
            return translate("gtswn.ui.wireless.energy", FormatUtil.formatNormal(eu));
        } else {
            return translate("gtswn.ui.wireless.energy.scientific", FormatUtil.formatScientific(eu));
        }
    }

    // ==================== NC2 工业信息屏集成（方案A+B）====================
    // 通过实现 IGregTechDeviceInformation（已由 MetaTileEntity 间接继承）+ IMetricsExporter，
    // 让 GT 传感器套件右键本机器后能生成传感器卡，将信息显示到 NC2 工业信息屏。
    // 方案A：isGivingInformation() + getInfoData() → 普通传感器卡，同维度监测
    // 方案B：reportMetrics() → CoverMetricsTransmitter + 高级传感器卡，跨维度监测

    /**
     * 启用 GT 传感器套件识别（方案A入口）
     * BehaviourSensorKit.onItemUseFirst 会检查此方法返回 true 才生成传感器卡
     */
    @Override
    public boolean isGivingInformation() {
        return true;
    }

    /**
     * 返回显示在 NC2 工业信息屏上的信息字符串数组（最多8条）
     * 玩家用 GT 传感器套件右键本机器后，传感器卡会通过 ItemSensorCard.update() 调用此方法
     * 只传递电网容量和电网状态（用户需求）
     */
    @Override
    public String[] getInfoData() {
        return new String[] {
            // 标题行：机器名称（蓝色）
            EnumChatFormatting.BLUE + getLocalName() + EnumChatFormatting.RESET,
            // 电网容量（复用 UI 的格式化文本，含"能量: xxx EU"）
            StatCollector.translateToLocalFormatted("gtswn.info.energy", getWirelessEUText()),
            // 电网状态（复用 UI 的格式化文本，含 EU/t、电压等级）
            StatCollector.translateToLocalFormatted("gtswn.info.status", cachedStatusText) };
    }

    /**
     * 返回跨维度监测的 metrics 列表（方案B入口）
     * CoverMetricsTransmitter.doCoverThings 会优先调用此方法（而非 getInfoData）
     * 配合高级传感器卡实现跨维度信息传输
     */
    @Override
    public List<String> reportMetrics() {
        return Arrays.asList(getInfoData());
    }

    // 上述格式化与电压等级相关方法已迁移至 FormatUtil 与 GTTierUtil（T4 公共工具类提取）

    // 右键打开 GUI - 由 MTEMonitor 基类的 onRightclick 处理

    /**
     * 启用 ModularUI 2
     * <p>
     * 此机器使用 MUI2 框架构建 GUI，保留 cover tabs 支持。
     *
     * @return 始终返回 true
     */
    @Override
    protected boolean useMui2() {
        return true;
    }

    /**
     * 是否发出红石信号
     */
    public boolean emitsRedstoneSignal() {
        return redstoneOutput && redstoneMode > 0;
    }

    /**
     * 根据红石输出状态切换贴图
     */
    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side,
        ForgeDirection facingDirection, int colorIndex, boolean active, boolean redstoneLevel) {

        // 获取基础机器外壳贴图（LV 级别）
        ITexture baseTexture = Textures.BlockIcons.MACHINE_CASINGS[1][colorIndex + 1];

        // 只有正面（facingDirection）叠加自定义贴图
        if (side == facingDirection) {
            // 选择 ON 或 OFF 贴图
            gregtech.api.interfaces.IIconContainer icon = redstoneLevel
                ? com.miaokatze.gtswn.register.TextureManager.TEX_WIRELESS_MONITOR_ON
                : com.miaokatze.gtswn.register.TextureManager.TEX_WIRELESS_MONITOR_OFF;

            // 返回两层贴图：基础外壳 + 自定义图标
            return new ITexture[] { baseTexture, gregtech.api.render.TextureFactory.of(icon) };
        }

        // 其他面只返回基础外壳
        return new ITexture[] { baseTexture };
    }

    /**
     * 构建 ModularUI 2 主面板
     * <p>
     * 布局结构（346×165，禁用物品栏，禁用 GT Logo，保留 cover tabs）：
     *
     * <pre>
     * ┌──────── GT 默认背景（无 Logo） ────────┐
     * │ [切换模式按钮]   标题文本              │
     * │ 模式: 常规计数                        │
     * │ 无线电网能量: 1,234 EU                │
     * │ 电网状态: +1.50 EU/t                  │
     * │ 红石模式: 关闭      [切换红石按钮]    │
     * │ 红石输出: 关闭                        │
     * │ 关闭: 不输出红石信号                  │
     * │ 参数1: [输入框]                       │
     * │ 参数2: [输入框]                       │
     * └────────────────────────────────────────┘
     * </pre>
     *
     * 同步值注册：
     * <ul>
     * <li>displayMode (IntSyncValue, C2S) - 显示模式</li>
     * <li>redstoneMode (IntSyncValue, C2S) - 红石模式</li>
     * <li>param1Value (LongSyncValue, C2S) - 参数1数值</li>
     * <li>param2Value (LongSyncValue, C2S) - 参数2数值</li>
     * <li>cachedModeText/euText/statusText/redstoneModeText/redstoneOutputText/modeDescText (StringSyncValue)
     * - 6 个动态显示文本</li>
     * </ul>
     *
     * @param guiData     GUI 位置数据
     * @param syncManager 同步管理器
     * @param uiSettings  UI 设置
     * @return 主面板
     */
    @Override
    public ModularPanel buildUI(PosGuiData guiData, PanelSyncManager syncManager, UISettings uiSettings) {
        // === 同步值注册（先注册，再构建 UI 以便引用） ===

        // 显示模式（客户端可写，允许玩家点击按钮切换）
        IntSyncValue displayModeSync = new IntSyncValue(this::getDisplayMode, this::setDisplayMode).allowC2S();
        syncManager.syncValue("displayMode", displayModeSync);

        // 红石模式（客户端可写，允许玩家点击按钮循环切换）
        IntSyncValue redstoneModeSync = new IntSyncValue(this::getRedstoneMode, this::setRedstoneMode).allowC2S();
        syncManager.syncValue("redstoneMode", redstoneModeSync);

        // 锚定参数模式（客户端可写，允许玩家点击按钮切换电网电量/电网状态）
        IntSyncValue anchorModeSync = new IntSyncValue(this::getAnchorMode, this::setAnchorMode).allowC2S();
        syncManager.syncValue("anchorMode", anchorModeSync);

        // 参数1数值（LongSyncValue + 输入框，超 long 边界在 setter 中截断）
        LongSyncValue param1Sync = new LongSyncValue(this::getParam1Value, this::setParam1Value).allowC2S();
        syncManager.syncValue("param1Value", param1Sync);

        // 参数2数值（红石模式 0/1/2 下不禁用，保持可编辑）
        LongSyncValue param2Sync = new LongSyncValue(this::getParam2Value, this::setParam2Value).allowC2S();
        syncManager.syncValue("param2Value", param2Sync);

        // 6 个动态文本（服务端 onPostTick 更新，单向同步到客户端）
        StringSyncValue modeTextSync = new StringSyncValue(this::getCachedModeText, this::setCachedModeText);
        syncManager.syncValue("cachedModeText", modeTextSync);

        StringSyncValue euTextSync = new StringSyncValue(this::getCachedEUText, this::setCachedEUText);
        syncManager.syncValue("cachedEUText", euTextSync);

        StringSyncValue statusTextSync = new StringSyncValue(this::getCachedStatusText, this::setCachedStatusText);
        syncManager.syncValue("cachedStatusText", statusTextSync);

        StringSyncValue redstoneModeTextSync = new StringSyncValue(
            this::getCachedRedstoneModeText,
            this::setCachedRedstoneModeText);
        syncManager.syncValue("cachedRedstoneModeText", redstoneModeTextSync);

        StringSyncValue redstoneOutputTextSync = new StringSyncValue(
            this::getCachedRedstoneOutputText,
            this::setCachedRedstoneOutputText);
        syncManager.syncValue("cachedRedstoneOutputText", redstoneOutputTextSync);

        StringSyncValue modeDescTextSync = new StringSyncValue(
            this::getCachedModeDescText,
            this::setCachedModeDescText);
        syncManager.syncValue("cachedModeDescText", modeDescTextSync);

        // === 构建主面板 ===
        // 使用 GT 模板构建器：禁用物品栏、禁用 GT Logo、保留 cover tabs
        // v1.1.12 调整：宽度 236→336（+100），高度 150→155（+5，全给下padding）
        // v1.1.13 调整：宽度 336→346（+10 延伸左边缘），高度 155→165（+10 延伸下边缘）
        ModularPanel panel = GTGuis.mteTemplatePanelBuilder(this, guiData, syncManager, uiSettings)
            .setWidth(346)
            .setHeight(165)
            .doesBindPlayerInventory(false)
            .doesAddGregTechLogo(false)
            .build();

        // === 主内容列布局（padding: 上6 右4 下13 左24） ===
        // v1.1.10 修复：childPadding 从 2 改为 0，消除行间额外间距（行高已由各 row 的 height() 显式控制）
        // v1.1.11 修复：padding 上下从 4 改为 2，配合 9 行统一行高(7*16+2*17=146) 让总高 146+4=150 严丝合缝
        // v1.1.12 调整：下padding 从 2 改为 7（+5），配合 panel 高度 155，内容区 146+9=155 严丝合缝
        // v1.1.13 调整：下padding 7→17（+10 延伸下边缘），左padding 14→24（+10 延伸左边缘），内容区右边界不变
        // v1.1.14 调整：padding 改为 (6,4,13,24)，使上下边距更均衡；所有文本/行添加居中对齐
        Flow column = Flow.column()
            .full()
            .padding(6, 4, 13, 24)
            .childPadding(0)
            .crossAxisAlignment(Alignment.CrossAxis.START);

        // 第 1 行：标题 + 切换模式按钮（SPACE_BETWEEN 让标题在左、按钮在右）
        // v1.1.10 修复：显式 height(16) 紧贴按钮高度，避免行高被拉大
        // v1.1.11 修复：无按钮行也改为 height(16)，所有行高统一
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(16)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .childPadding(4)
                .child(
                    IKey.lang("gtswn.ui.title")
                        .asWidget()
                        .alignment(Alignment.CenterLeft))
                .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                    // 切换显示模式（0/1 之间切换）
                    // 关键：通过 IntSyncValue.setValue 触发 C2S 同步，让服务端也更新
                    // 直接调用 setDisplayMode 只会改客户端副本，服务端不会收到
                    displayModeSync.setValue(1 - displayModeSync.getIntValue());
                    return true;
                })
                    .background(GTGuiTextures.BUTTON_STANDARD)
                    // 添加循环切换图标（最接近"模式切换"语义）
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_CYCLIC)
                    .size(16, 16)
                    .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.toggle.mode")))));

        // 第 2-7 行：6 个动态文本（模式/能量/状态/红石模式/红石输出/模式说明）
        // v1.1.11 修复：无按钮行也显式 height(16)，与有按钮行高一致，行间距均匀
        column.child(
            IKey.dynamic(() -> cachedModeText)
                .asWidget()
                .height(16)
                .alignment(Alignment.CenterLeft));
        // 电网容量行 + 锚定按钮1（点击设置 anchorMode=0：电网电量）
        // 选中时显示"="（当前锚定），未选中显示"<"（可切换）
        // v1.1.10 修复：显式 height(16) 紧贴按钮高度
        // v1.1.11 修复：无按钮行也改为 height(16)，所有行高统一
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(16)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .childPadding(4)
                .child(
                    IKey.dynamic(() -> cachedEUText)
                        .asWidget()
                        .alignment(Alignment.CenterLeft))
                .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                    // 切换锚定参数为电网电量模式
                    anchorModeSync.setValue(0);
                    return true;
                })
                    .background(GTGuiTextures.BUTTON_STANDARD)
                    .overlay(IKey.dynamic(() -> anchorModeSync.getIntValue() == 0 ? "=" : "<"))
                    .size(16, 16)
                    .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.toggle.anchor")))));

        // 电网状态行 + 锚定按钮2（点击设置 anchorMode=1：电网状态 EU/t）
        // 选中时显示"="（当前锚定），未选中显示"<"（可切换）
        // v1.1.10 修复：显式 height(16) 紧贴按钮高度
        // v1.1.11 修复：无按钮行也改为 height(16)，所有行高统一
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(16)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .childPadding(4)
                .child(
                    IKey.dynamic(() -> cachedStatusText)
                        .asWidget()
                        .alignment(Alignment.CenterLeft))
                .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                    // 切换锚定参数为电网状态模式
                    anchorModeSync.setValue(1);
                    return true;
                })
                    .background(GTGuiTextures.BUTTON_STANDARD)
                    .overlay(IKey.dynamic(() -> anchorModeSync.getIntValue() == 1 ? "=" : "<"))
                    .size(16, 16)
                    .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.toggle.anchor")))));

        // 红石模式行：文本 + 切换红石按钮
        // v1.1.10 修复：显式 height(16) 紧贴按钮高度
        // v1.1.11 修复：无按钮行也改为 height(16)，所有行高统一
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(16)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .childPadding(4)
                .child(
                    IKey.dynamic(() -> cachedRedstoneModeText)
                        .asWidget()
                        .alignment(Alignment.CenterLeft))
                .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                    // 循环切换红石模式 0->1->2->3->4->0
                    // 关键：通过 IntSyncValue.setValue 触发 C2S 同步，让服务端也更新
                    redstoneModeSync.setValue((redstoneModeSync.getIntValue() + 1) % 5);
                    return true;
                })
                    .background(GTGuiTextures.BUTTON_STANDARD)
                    // 添加模拟信号图标（最接近"红石比较器"语义）
                    .overlay(GTGuiTextures.OVERLAY_BUTTON_ANALOG)
                    .size(16, 16)
                    .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.toggle.redstone")))));

        // v1.1.11 修复：无按钮行也显式 height(16)
        column.child(
            IKey.dynamic(() -> cachedRedstoneOutputText)
                .asWidget()
                .height(16)
                .alignment(Alignment.CenterLeft));
        // v1.1.11 修复：无按钮行也显式 height(16)
        column.child(
            IKey.dynamic(() -> cachedModeDescText)
                .asWidget()
                .height(16)
                .alignment(Alignment.CenterLeft));

        // 参数1行：[左组：标签+输入框] [右组：4个位数调整按钮]（SPACE_BETWEEN 让按钮组右对齐到 UI 边框）
        // v1.1.10 修复：显式 height(17) 对齐输入框高度
        // v1.1.12 调整：输入框 110→220（+110），外层 row 加 right padding 5 让按钮组右移95px（其余按钮右移100px）
        // v1.1.13 调整：输入框 220→200（-20）
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(17)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .padding(0, 5, 0, 0)
                .child(
                    // 左组：标签 + 输入框
                    Flow.row()
                        .coverChildren()
                        .childPadding(2)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        .child(
                            IKey.str(translate("gtswn.ui.param1", ""))
                                .asWidget()
                                .alignment(Alignment.CenterLeft))
                        .child(
                            // 科学计数法输入框：displayMode==1 时失焦后格式化为 1.5E6 形式
                            new ScientificTextFieldWidget().displayMode(() -> displayMode)
                                .formatAsInteger(true)
                                .numbersLong(() -> Long.MIN_VALUE, () -> Long.MAX_VALUE)
                                .size(200, 17)
                                .value(param1Sync)))
                .child(
                    // 右组：4 个位数调整按钮（-, +, ×10, ÷10）
                    Flow.row()
                        .coverChildren()
                        .childPadding(2)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        // - 按钮（减少最高位）
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            long currentValue = param1Sync.getLongValue();
                            long absValue = Math.abs(currentValue);
                            long step = absValue > 0 ? (long) Math.pow(10, (int) Math.log10(absValue)) : 1;
                            // 退位处理：100 -> 90, 1000 -> 900
                            long newValue;
                            if (currentValue == step) {
                                long nextStep = step / 10;
                                if (nextStep < 1) nextStep = 1;
                                newValue = currentValue - nextStep;
                            } else {
                                newValue = currentValue - step;
                            }
                            // 支持负数：不再截断为 0
                            param1Sync.setValue(newValue);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("-"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param1.minus"))))
                        // + 按钮（增加最高位）
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            long currentValue = param1Sync.getLongValue();
                            long absValue = Math.abs(currentValue);
                            long step = absValue > 0 ? (long) Math.pow(10, (int) Math.log10(absValue)) : 1;
                            param1Sync.setValue(currentValue + step);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("+"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param1.plus"))))
                        // ×10 按钮
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            param1Sync.setValue(param1Sync.getLongValue() * 10);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("\u00D7"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param1.mul10"))))
                        // ÷10 按钮
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            param1Sync.setValue(param1Sync.getLongValue() / 10);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("\u00F7"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param1.div10"))))));

        // 参数2行：[左组：标签+输入框] [右组：4个位数调整按钮]（SPACE_BETWEEN 让按钮组右对齐到 UI 边框）
        // v1.1.10 修复：显式 height(17) 对齐输入框高度
        // v1.1.11 修复：保留 height(17) 与输入框 size 一致，其他行统一 16
        // v1.1.12 调整：输入框 110→220（+110），外层 row 加 right padding 5 让按钮组右移95px
        // v1.1.13 调整：输入框 220→200（-20）
        column.child(
            Flow.row()
                .widthRel(1f)
                .height(17)
                .mainAxisAlignment(Alignment.MainAxis.SPACE_BETWEEN)
                .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                .padding(0, 5, 0, 0)
                .child(
                    // 左组：标签 + 输入框
                    Flow.row()
                        .coverChildren()
                        .childPadding(2)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        .child(
                            IKey.str(translate("gtswn.ui.param2", ""))
                                .asWidget()
                                .alignment(Alignment.CenterLeft))
                        .child(
                            // 科学计数法输入框：displayMode==1 时失焦后格式化为 1.5E6 形式
                            new ScientificTextFieldWidget().displayMode(() -> displayMode)
                                .formatAsInteger(true)
                                .numbersLong(() -> Long.MIN_VALUE, () -> Long.MAX_VALUE)
                                .size(200, 17)
                                .value(param2Sync)))
                .child(
                    // 右组：4 个位数调整按钮（-, +, ×10, ÷10）
                    Flow.row()
                        .coverChildren()
                        .childPadding(2)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER)
                        // - 按钮（减少最高位）
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            long currentValue = param2Sync.getLongValue();
                            long absValue = Math.abs(currentValue);
                            long step = absValue > 0 ? (long) Math.pow(10, (int) Math.log10(absValue)) : 1;
                            long newValue;
                            if (currentValue == step) {
                                long nextStep = step / 10;
                                if (nextStep < 1) nextStep = 1;
                                newValue = currentValue - nextStep;
                            } else {
                                newValue = currentValue - step;
                            }
                            // 支持负数：不再截断为 0
                            param2Sync.setValue(newValue);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("-"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param2.minus"))))
                        // + 按钮（增加最高位）
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            long currentValue = param2Sync.getLongValue();
                            long absValue = Math.abs(currentValue);
                            long step = absValue > 0 ? (long) Math.pow(10, (int) Math.log10(absValue)) : 1;
                            param2Sync.setValue(currentValue + step);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("+"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param2.plus"))))
                        // ×10 按钮
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            param2Sync.setValue(param2Sync.getLongValue() * 10);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("\u00D7"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param2.mul10"))))
                        // ÷10 按钮
                        .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                            param2Sync.setValue(param2Sync.getLongValue() / 10);
                            return true;
                        })
                            .background(GTGuiTextures.BUTTON_STANDARD)
                            .overlay(IKey.str("\u00F7"))
                            .size(16, 16)
                            .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.param2.div10"))))));

        // 将列添加到面板
        panel.child(column);

        return panel;
    }

    // === UI 同步用的 getter/setter ===

    /**
     * 获取显示模式（用于 IntSyncValue 同步）
     */
    public int getDisplayMode() {
        return displayMode;
    }

    /**
     * 设置显示模式（用于 IntSyncValue 同步）
     */
    public void setDisplayMode(int mode) {
        this.displayMode = mode;
        // 立即更新缓存文本，实现实时 UI 响应（无需等 30 秒 onPostTick）
        cachedModeText = mode == 0 ? translate("gtswn.ui.mode.normal") : translate("gtswn.ui.mode.scientific");
        cachedEUText = getWirelessEUText();
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取红石模式（用于 IntSyncValue 同步）
     */
    public int getRedstoneMode() {
        return redstoneMode;
    }

    /**
     * 设置红石模式（用于 IntSyncValue 同步）
     */
    public void setRedstoneMode(int mode) {
        this.redstoneMode = mode;
        // 立即更新缓存文本，实现实时 UI 响应（无需等 30 秒 onPostTick）
        cachedRedstoneModeText = getRedstoneModeText();
        cachedModeDescText = getModeDescText();
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取锚定参数模式（用于 IntSyncValue 同步）
     * 0=电网电量，1=电网状态(EU/t)
     */
    public int getAnchorMode() {
        return anchorMode;
    }

    /**
     * 设置锚定参数模式（用于 IntSyncValue 同步）
     */
    public void setAnchorMode(int mode) {
        this.anchorMode = mode;
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取参数1数值（用于 LongSyncValue 同步）
     * <p>
     * BigInteger 转 long：超过 Long.MAX_VALUE 时截断为 Long.MAX_VALUE，支持负数。
     */
    public long getParam1Value() {
        // v1.2.1 修复：同时截断上下界，避免 BigInteger 超出 long 范围时 longValue() 返回错误值
        // 原代码仅截断上界，BigInteger 小于 Long.MIN_VALUE 时 longValue() 会返回低 64 位的错误值
        // 支持负数：截断到 [Long.MIN_VALUE, Long.MAX_VALUE]
        BigInteger truncated = param1Value.max(BigInteger.valueOf(Long.MIN_VALUE))
            .min(BigInteger.valueOf(Long.MAX_VALUE));
        return truncated.longValue();
    }

    /**
     * 设置参数1数值（用于 LongSyncValue 同步）
     * <p>
     * long 转 BigInteger：直接转换。
     */
    public void setParam1Value(long val) {
        // 支持负数：不再截断为 0
        this.param1Value = BigInteger.valueOf(val);
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取参数2数值（用于 LongSyncValue 同步）
     * <p>
     * BigInteger 转 long：超过 Long.MAX_VALUE 时截断为 Long.MAX_VALUE，支持负数。
     */
    public long getParam2Value() {
        // v1.2.1 修复：同时截断上下界，避免 BigInteger 超出 long 范围时 longValue() 返回错误值
        // 原代码仅截断上界，BigInteger 小于 Long.MIN_VALUE 时 longValue() 会返回低 64 位的错误值
        // 支持负数：截断到 [Long.MIN_VALUE, Long.MAX_VALUE]
        BigInteger truncated = param2Value.max(BigInteger.valueOf(Long.MIN_VALUE))
            .min(BigInteger.valueOf(Long.MAX_VALUE));
        return truncated.longValue();
    }

    /**
     * 设置参数2数值（用于 LongSyncValue 同步）
     * <p>
     * long 转 BigInteger：直接转换。
     */
    public void setParam2Value(long val) {
        // 支持负数：不再截断为 0
        this.param2Value = BigInteger.valueOf(val);
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取模式文本（用于 StringSyncValue 同步）
     */
    public String getCachedModeText() {
        return cachedModeText;
    }

    /**
     * 设置模式文本（用于 StringSyncValue 同步）
     */
    public void setCachedModeText(String text) {
        this.cachedModeText = text;
    }

    /**
     * 获取能量文本（用于 StringSyncValue 同步）
     */
    public String getCachedEUText() {
        return cachedEUText;
    }

    /**
     * 设置能量文本（用于 StringSyncValue 同步）
     */
    public void setCachedEUText(String text) {
        this.cachedEUText = text;
    }

    /**
     * 获取状态文本（用于 StringSyncValue 同步）
     */
    public String getCachedStatusText() {
        return cachedStatusText;
    }

    /**
     * 设置状态文本（用于 StringSyncValue 同步）
     */
    public void setCachedStatusText(String text) {
        this.cachedStatusText = text;
    }

    /**
     * 获取红石模式文本（用于 StringSyncValue 同步）
     */
    public String getCachedRedstoneModeText() {
        return cachedRedstoneModeText;
    }

    /**
     * 设置红石模式文本（用于 StringSyncValue 同步）
     */
    public void setCachedRedstoneModeText(String text) {
        this.cachedRedstoneModeText = text;
    }

    /**
     * 获取红石输出文本（用于 StringSyncValue 同步）
     */
    public String getCachedRedstoneOutputText() {
        return cachedRedstoneOutputText;
    }

    /**
     * 设置红石输出文本（用于 StringSyncValue 同步）
     */
    public void setCachedRedstoneOutputText(String text) {
        this.cachedRedstoneOutputText = text;
    }

    /**
     * 获取模式说明文本（用于 StringSyncValue 同步）
     */
    public String getCachedModeDescText() {
        return cachedModeDescText;
    }

    /**
     * 设置模式说明文本（用于 StringSyncValue 同步）
     */
    public void setCachedModeDescText(String text) {
        this.cachedModeDescText = text;
    }

    // 获取红石模式文本
    private String getRedstoneModeText() {
        String modeKey;
        switch (redstoneMode) {
            case 0:
                modeKey = "gtswn.ui.redstone.mode.off";
                break;
            case 1:
                modeKey = "gtswn.ui.redstone.mode.high";
                break;
            case 2:
                modeKey = "gtswn.ui.redstone.mode.low";
                break;
            case 3:
                modeKey = "gtswn.ui.redstone.mode.high.lag";
                break;
            case 4:
                modeKey = "gtswn.ui.redstone.mode.low.lag";
                break;
            default:
                modeKey = "gtswn.ui.redstone.mode.unknown";
        }
        return translate("gtswn.ui.redstone.mode", translate(modeKey));
    }

    /**
     * 获取红石模式说明文本
     */
    private String getModeDescText() {
        switch (redstoneMode) {
            case 0:
                return translate("gtswn.ui.redstone.desc.off");
            case 1:
                return translate("gtswn.ui.redstone.desc.high");
            case 2:
                return translate("gtswn.ui.redstone.desc.low");
            case 3:
                return translate("gtswn.ui.redstone.desc.high.lag");
            case 4:
                return translate("gtswn.ui.redstone.desc.low.lag");
            default:
                return translate("gtswn.ui.redstone.desc.unknown");
        }
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
        aNBT.setInteger("displayMode", displayMode);
        if (ownerUUID != null) {
            aNBT.setString("ownerUUID", ownerUUID.toString());
        }
        // 保存 EU/t 计算状态（用于红石功能）
        aNBT.setDouble("euPerTick", euPerTick);
        // v1.2.4：重新持久化测量历史（用于短时卸载直接续传）
        // 短时卸载（≤10秒）保留旧样本立即恢复变化率；长时卸载（>10秒）由 onPostTick 的 gap 检测清空
        // 由 EUDataSet 统一序列化（兼容旧 measurementHistory 格式）
        dataSet.saveToNBT(aNBT, "measurementHistory");

        // v1.2.1 修复：移除 lastWirelessEU 死代码
        // 原因：loadNBTData 从不读取 lastWirelessEU，写入只是浪费 NBT 空间和性能
        // 退出重进后会重新从 WirelessNetworkManager 获取，无需持久化

        // 保存红石控制状态
        aNBT.setInteger("redstoneMode", redstoneMode);
        // 保存红石检测时间戳（用于跨加载持续 0.1s 响应）
        aNBT.setLong("lastRedstoneCheckTick", lastRedstoneCheckTick);
        // 保存 UI/EUt 检测时间戳（v1.2.4：用于 onPostTick 的 gap 检测，判断长时/短时卸载）
        aNBT.setLong("lastCheckTick", lastCheckTick);
        // 保存锚定参数模式（0=电网电量，1=电网状态）
        aNBT.setInteger("anchorMode", anchorMode);
        aNBT.setString("param1Value", param1Value.toString());
        aNBT.setString("param2Value", param2Value.toString());
        aNBT.setBoolean("redstoneOutput", redstoneOutput);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
        displayMode = aNBT.getInteger("displayMode");
        if (aNBT.hasKey("ownerUUID")) {
            try {
                ownerUUID = UUID.fromString(aNBT.getString("ownerUUID"));
            } catch (Exception e) {
                ownerUUID = null;
            }
        }
        // 加载 EU/t 计算状态（用于红石功能）
        euPerTick = aNBT.getDouble("euPerTick");
        // 注：unchangedCount 已废弃（300s 窗口均值算法不再使用），不再读取
        // v1.2.4：恢复测量历史（用于短时卸载直接续传）
        // 兼容性：v1.2.3 存档无此键时 loadFromNBT 内部 hasKey 检查跳过；
        // v1.2.2 之前存档有此键，正常恢复；EUDataSet 已处理 count>CAPACITY 情况
        dataSet.loadFromNBT(aNBT, "measurementHistory");
        // 注意：lastWirelessEU 不需要保存，因为退出重进后会重新从 WirelessNetworkManager 获取

        // 加载红石控制状态
        redstoneMode = aNBT.getInteger("redstoneMode");
        // 加载红石检测时间戳（用于跨加载持续 0.1s 响应）
        lastRedstoneCheckTick = aNBT.getLong("lastRedstoneCheckTick");
        // 加载 UI/EUt 检测时间戳（v1.2.4：用于 onPostTick 的 gap 检测，判断长时/短时卸载）
        // 旧存档无此键时 getLong 返回 0，首次 onPostTick 立即触发，兼容性 OK
        lastCheckTick = aNBT.getLong("lastCheckTick");
        // 加载锚定参数模式（0=电网电量，1=电网状态）
        anchorMode = aNBT.getInteger("anchorMode");
        if (aNBT.hasKey("param1Value")) {
            param1Value = new BigInteger(aNBT.getString("param1Value"));
        }
        if (aNBT.hasKey("param2Value")) {
            param2Value = new BigInteger(aNBT.getString("param2Value"));
        }
        // 加载保存的红石输出状态
        boolean savedRedstoneOutput = aNBT.getBoolean("redstoneOutput");

        // 重新计算当前的红石输出状态（基于加载的参数和当前锚定值）
        // v1.2.1 修复：模式3/4 改为与 updateRedstoneOutput 一致的滞后逻辑（加载时 redstoneOutput 默认 false，即"未输出"状态）
        BigInteger currentEU = getAnchorValue();
        if (currentEU != null && redstoneMode > 0) {
            // 根据红石模式和参数重新判断是否应该输出
            switch (redstoneMode) {
                case 1: // 正向：电量 > 参数1
                    redstoneOutput = currentEU.compareTo(param1Value) > 0;
                    break;
                case 2: // 反向：电量 < 参数1
                    redstoneOutput = currentEU.compareTo(param1Value) < 0;
                    break;
                case 3: // 正向区间：使用滞后逻辑（加载时视为未输出状态）
                    redstoneOutput = currentEU.compareTo(param1Value) > 0;
                    break;
                case 4: // 反向区间：使用滞后逻辑（加载时视为未输出状态）
                    redstoneOutput = currentEU.compareTo(param2Value) < 0;
                    break;
                default:
                    redstoneOutput = false;
            }
        } else {
            // 如果无法获取电网能量，使用保存的状态
            redstoneOutput = savedRedstoneOutput;
        }

        // 通知方块更新（刷新贴图）
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().issueTextureUpdate();
        }
    }
}
