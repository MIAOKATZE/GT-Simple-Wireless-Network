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
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.LongSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.common.machine.base.MTEMonitor;

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
    private BigInteger lastEU = BigInteger.ZERO;
    private long lastCheckTick = 0;
    private double euPerTick = 0.0;

    // 智能 EU/t 计算相关（与 HUD 保持一致）
    private static class Measurement {

        long tick;
        BigInteger value;

        Measurement(long tick, BigInteger value) {
            this.tick = tick;
            this.value = value;
        }
    }

    private java.util.List<Measurement> measurementHistory = new java.util.ArrayList<>();
    /** EU/t 均值计算窗口（ticks），6000 ticks = 300 秒 */
    private static final long WINDOW_TICKS = 6000L;

    // UI 同步字段
    private String cachedModeText = "模式: 常规计数";
    private String cachedEUText = "无线电网能量: 0 EU";
    private String cachedStatusText = "电网状态: 无变化/计算中";
    private String cachedRedstoneModeText = "红石模式: 关闭";
    private String cachedRedstoneOutputText = "红石输出: 关闭";
    private String cachedModeDescText = "关闭: 不输出红石信号";

    // 红石控制相关
    private int redstoneMode = 0; // 0=关闭, 1=正向, 2=反向, 3=正向区间, 4=反向区间
    private String param1Text = ""; // 参数1文本
    private String param2Text = ""; // 参数2文本
    private BigInteger param1Value = BigInteger.ZERO; // 参数1数值
    private BigInteger param2Value = BigInteger.ZERO; // 参数2数值
    private boolean redstoneOutput = false; // 红石输出状态

    // 构造函数：用于注册
    public MTEWirelessEnergyMonitor(int aID, String aName, String aNameRegional) {
        super(
            aID,
            aName,
            aNameRegional,
            new String[] { net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line1"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line2"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line3") });
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
    public String[] getDescription() {
        // 每次都创建新的数组实例，避免与其他机器共享引用导致缓存污染
        return new String[] { net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line1"),
            net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line2"),
            net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line3") };
    }

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

        // 每 600 ticks (30秒) 更新一次 UI 显示与检测
        // 注：红石判定周期同步改为 30s（与检测同周期，不拆分）
        if (aTick % 600L == 0L) {
            // 服务端执行 EU/t 计算和红石逻辑
            if (aBaseMetaTileEntity.isServerSide()) {
                calculateSmartEUT(aTick);
                updateRedstoneOutput();
            }

            // 更新缓存文本（服务端和客户端都执行）
            cachedModeText = displayMode == 0 ? translate("gtswn.ui.mode.normal")
                : translate("gtswn.ui.mode.scientific");
            cachedEUText = getWirelessEUText();
            cachedStatusText = getnetworkStatusText();
            cachedRedstoneModeText = getRedstoneModeText();
            cachedRedstoneOutputText = redstoneOutput ? translate("gtswn.ui.redstone.output.on")
                : translate("gtswn.ui.redstone.output.off");
            cachedModeDescText = getModeDescText();
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

        // 根据当前电网能量和参数判断是否输出信号
        BigInteger currentEU = getWirelessEU();
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
            case 3: // 正向区间：>参数1输出, <参数2取消
                shouldOutput = currentEU.compareTo(param1Value) > 0 && currentEU.compareTo(param2Value) >= 0;
                break;
            case 4: // 反向区间：>参数1取消, <参数2输出
                shouldOutput = currentEU.compareTo(param1Value) <= 0 && currentEU.compareTo(param2Value) < 0;
                break;
            default:
                shouldOutput = false;
        }

        // 如果应该输出，返回15强度；否则返回0
        return shouldOutput ? (byte) 15 : (byte) 0;
    }

    // 更新红石输出状态
    private void updateRedstoneOutput() {
        BigInteger currentEU = getWirelessEU();
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
    private void calculateSmartEUT(long currentTick) {
        BigInteger currentEU = getWirelessEU();

        // 记录测量历史
        recordMeasurement(currentTick, currentEU);

        // 计算 EU/t
        euPerTick = calculateEUT(currentTick);

        lastEU = currentEU;
        lastCheckTick = currentTick;
    }

    /**
     * 记录测量历史（每次检测都记录，不再判断是否变化）
     * <p>
     * 改动意图：保证 300 秒窗口内有足够样本支撑首末两点斜率算法。
     * 每次记录后立即调用 purgeExpired 清理窗口外样本。
     */
    private void recordMeasurement(long tick, BigInteger value) {
        if (value == null) return;
        measurementHistory.add(new Measurement(tick, value));
        // 老化：清理窗口外样本（tick < currentTick - WINDOW_TICKS）
        purgeExpired(tick);
    }

    /**
     * 老化过期样本：淘汰 tick &lt; currentTick - WINDOW_TICKS 的样本
     * <p>
     * 列表按时间顺序追加，遇到第一个未过期样本即可停止遍历。
     */
    private void purgeExpired(long currentTick) {
        long cutoff = currentTick - WINDOW_TICKS;
        java.util.Iterator<Measurement> it = measurementHistory.iterator();
        while (it.hasNext()) {
            if (it.next().tick < cutoff) {
                it.remove();
            } else {
                break; // 列表按时间顺序，遇到第一个未过期即可停止
            }
        }
    }

    /**
     * 计算 EU/t（300 秒窗口首末两点斜率法）
     * <p>
     * 算法：(lastValue - firstValue) / (lastTick - firstTick)
     * 边界情况：
     * <ul>
     * <li>历史为空或仅 1 个点：返回 0.0</li>
     * <li>首末 tick 相同（tickDiff &lt;= 0）：返回 0.0</li>
     * <li>长期无变化：返回 0.0（显示 "0.00" EU/t）</li>
     * </ul>
     */
    private double calculateEUT(long currentTick) {
        // 先清理窗口外样本
        purgeExpired(currentTick);

        if (measurementHistory.size() < 2) {
            return 0.0;
        }

        Measurement first = measurementHistory.get(0);
        Measurement last = measurementHistory.get(measurementHistory.size() - 1);
        long tickDiff = last.tick - first.tick;
        if (tickDiff <= 0) {
            return 0.0;
        }
        BigInteger diff = last.value.subtract(first.value);
        return diff.doubleValue() / tickDiff;
    }

    // 获取无线电网能量
    private BigInteger getWirelessEU() {
        if (ownerUUID == null) return BigInteger.ZERO;
        return WirelessNetworkManager.getUserEU(ownerUUID);
    }

    // 格式化能量文本
    private String getWirelessEUText() {
        BigInteger eu = getWirelessEU();
        if (displayMode == 0) {
            return translate("gtswn.ui.wireless.energy", formatNormal(eu));
        } else {
            return translate("gtswn.ui.wireless.energy.scientific", formatScientific(eu));
        }
    }

    // 获取电网状态文本（与 HUD 保持一致）
    // 改动：在 EU/t 显示文本后追加灰色 [300s avg] 标注，表明这是 300 秒窗口均值
    private String getnetworkStatusText() {
        // 灰色标注，表明 EU/t 为 300 秒窗口均值
        String avgTag = " §7[300s avg]";
        if (euPerTick > 0.01) {
            String euPerTickStr = formatEUtValue(euPerTick);
            String gtPowerText = formatGTPower(euPerTick);
            return translate("gtswn.ui.network.status.up", euPerTickStr, gtPowerText) + avgTag;
        } else if (euPerTick < -0.01) {
            String euPerTickStr = formatEUtValue(Math.abs(euPerTick));
            String gtPowerText = formatGTPower(Math.abs(euPerTick));
            return translate("gtswn.ui.network.status.down", euPerTickStr, gtPowerText) + avgTag;
        } else {
            return translate("gtswn.ui.network.status.nochange") + avgTag;
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
            // 电网状态（复用 UI 的格式化文本，含 EU/t、电压等级、[300s avg] 标注）
            StatCollector.translateToLocalFormatted("gtswn.info.status", getnetworkStatusText()) };
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

    // 格式化 EU/t 数值（根据显示模式）
    private String formatEUtValue(double value) {
        if (Math.abs(value) < 0.01) {
            return "0.00";
        }

        if (displayMode == 0) {
            // 常规计数
            if (Math.abs(value) < 1000) {
                return String.format("%.2f", value);
            } else {
                return formatNormalDouble(value);
            }
        } else {
            // 科学计数法
            int exponent = (int) Math.floor(Math.log10(Math.abs(value)));
            double coefficient = value / Math.pow(10, exponent);
            return String.format("%.2f×10^%d", coefficient, exponent);
        }
    }

    // 格式化 double 为常规计数（带逗号分隔）
    private String formatNormalDouble(double value) {
        String formatted = String.format("%.2f", Math.abs(value));
        String[] parts = formatted.split("\\.");
        String integerPart = parts[0];
        String decimalPart = parts.length > 1 ? parts[1] : "00";

        StringBuilder result = new StringBuilder();
        int length = integerPart.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(integerPart.charAt(i));
        }

        String finalResult = result.toString() + "." + decimalPart;
        if (value < 0) {
            finalResult = "-" + finalResult;
        }
        return finalResult;
    }

    // GT 电压等级定义
    private static final long[] VOLTAGES = { 8L, 32L, 128L, 512L, 2048L, 8192L, 32768L, 131072L, 524288L, 2097152L,
        8388608L, 33554432L, 134217728L, 536870912L, 2147483647L };
    private static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV", "MAX" };
    private static final String[] TIER_COLORS = { "§7", "§7", "§b", "§9", "§3", "§3", "§a", "§e", "§e", "§6", "§6",
        "§c", "§c", "§4", "§4" };

    // 将 EU/t 转换为 GT 的电流+电压等级格式
    private String formatGTPower(double euPerTick) {
        double absEU = Math.abs(euPerTick);
        int tier = 0;
        for (int i = 0; i < VOLTAGES.length; i++) {
            if (absEU < VOLTAGES[i] * 5) {
                tier = i;
                break;
            }
            if (i == VOLTAGES.length - 1) {
                tier = i;
            }
        }

        long voltage = VOLTAGES[tier];
        int amperage = (int) Math.ceil(absEU / voltage);

        boolean isOverloaded = false;
        if (amperage > 4 && tier < VOLTAGES.length - 1) {
            tier++;
            voltage = VOLTAGES[tier];
            amperage = (int) Math.ceil(absEU / voltage);
            while (amperage > 4 && tier < VOLTAGES.length - 1) {
                tier++;
                voltage = VOLTAGES[tier];
                amperage = (int) Math.ceil(absEU / voltage);
            }
            if (tier == VOLTAGES.length - 1 && amperage > 4) {
                isOverloaded = true;
                amperage = 4;
            }
        } else if (amperage > 4) {
            isOverloaded = true;
            amperage = 4;
        }

        String color = TIER_COLORS[tier];
        String tierName = TIER_NAMES[tier];
        if (isOverloaded) {
            return color + amperage + "A " + tierName + "+";
        } else {
            return color + amperage + "A " + tierName;
        }
    }

    // 常规计数
    private String formatNormal(BigInteger value) {
        if (value == null) return "0";
        String str = value.toString();
        StringBuilder result = new StringBuilder();
        int length = str.length();
        for (int i = 0; i < length; i++) {
            if (i > 0 && (length - i) % 3 == 0) {
                result.append(",");
            }
            result.append(str.charAt(i));
        }
        return result.toString();
    }

    // 科学计数法 (使用 10^ 指数格式)
    private String formatScientific(BigInteger value) {
        if (value == null) return "0";
        double d = value.doubleValue();
        if (d == 0) return "0";
        int exp = (int) Math.floor(Math.log10(Math.abs(d)));
        double mantissa = d / Math.pow(10, exp);
        return String.format("%.3f×10^%d", mantissa, exp);
    }

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
     * 布局结构（236×150，禁用物品栏，禁用 GT Logo，保留 cover tabs）：
     *
     * <pre>
     * ┌──────── GT 默认背景（无 Logo） ────────┐
     * │ [切换模式按钮]   标题文本              │
     * │ 模式: 常规计数                        │
     * │ 无线电网能量: 1,234 EU                │
     * │ 电网状态: +1.50 EU/t [300s avg]       │
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
        ModularPanel panel = GTGuis.mteTemplatePanelBuilder(this, guiData, syncManager, uiSettings)
            .setWidth(236)
            .setHeight(150)
            .doesBindPlayerInventory(false)
            .doesAddGregTechLogo(false)
            .build();

        // === 主内容列布局（padding 4px，子项间距 2px） ===
        Flow column = Flow.column()
            .full()
            .padding(4)
            .childPadding(2)
            .crossAxisAlignment(com.cleanroommc.modularui.utils.Alignment.CrossAxis.START);

        // 第 1 行：标题 + 切换模式按钮
        column.child(
            Flow.row()
                .coverChildren()
                .childPadding(4)
                .child(
                    IKey.lang("gtswn.ui.title")
                        .asWidget())
                .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                    // 切换显示模式（0/1 之间切换）
                    setDisplayMode(1 - getDisplayMode());
                    return true;
                })
                    .background(GTGuiTextures.BUTTON_STANDARD)
                    .size(16, 16)
                    .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.toggle.mode")))));

        // 第 2-7 行：6 个动态文本（模式/能量/状态/红石模式/红石输出/模式说明）
        column.child(
            IKey.dynamic(() -> cachedModeText)
                .asWidget());
        column.child(
            IKey.dynamic(() -> cachedEUText)
                .asWidget());
        column.child(
            IKey.dynamic(() -> cachedStatusText)
                .asWidget());

        // 红石模式行：文本 + 切换红石按钮
        column.child(
            Flow.row()
                .coverChildren()
                .childPadding(4)
                .child(
                    IKey.dynamic(() -> cachedRedstoneModeText)
                        .asWidget())
                .child(new ButtonWidget<>().onMousePressed(mouseButton -> {
                    // 循环切换红石模式 0->1->2->3->4->0
                    setRedstoneMode((getRedstoneMode() + 1) % 5);
                    return true;
                })
                    .background(GTGuiTextures.BUTTON_STANDARD)
                    .size(16, 16)
                    .tooltip(t -> t.addLine(translate("gtswn.ui.tooltip.toggle.redstone")))));

        column.child(
            IKey.dynamic(() -> cachedRedstoneOutputText)
                .asWidget());
        column.child(
            IKey.dynamic(() -> cachedModeDescText)
                .asWidget());

        // 参数1行：标签 + 输入框（宽度 100px）
        column.child(
            Flow.row()
                .coverChildren()
                .childPadding(4)
                .child(
                    IKey.str(translate("gtswn.ui.param1", ""))
                        .asWidget())
                .child(
                    new TextFieldWidget().formatAsInteger(true)
                        .numbersLong(() -> 0L, () -> Long.MAX_VALUE)
                        .size(100, 12)
                        .value(param1Sync)));

        // 参数2行：标签 + 输入框（宽度 100px，红石模式 0/1/2 下不禁用）
        column.child(
            Flow.row()
                .coverChildren()
                .childPadding(4)
                .child(
                    IKey.str(translate("gtswn.ui.param2", ""))
                        .asWidget())
                .child(
                    new TextFieldWidget().formatAsInteger(true)
                        .numbersLong(() -> 0L, () -> Long.MAX_VALUE)
                        .size(100, 12)
                        .value(param2Sync)));

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
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取参数1数值（用于 LongSyncValue 同步）
     * <p>
     * BigInteger 转 long：超过 Long.MAX_VALUE 时截断为 Long.MAX_VALUE，负数截断为 0。
     */
    public long getParam1Value() {
        if (param1Value.compareTo(BigInteger.ZERO) < 0) {
            return 0L;
        }
        return param1Value.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    /**
     * 设置参数1数值（用于 LongSyncValue 同步）
     * <p>
     * long 转 BigInteger：直接转换，并同步更新 param1Text。
     */
    public void setParam1Value(long val) {
        if (val < 0) val = 0;
        this.param1Value = BigInteger.valueOf(val);
        this.param1Text = this.param1Value.toString();
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }

    /**
     * 获取参数2数值（用于 LongSyncValue 同步）
     * <p>
     * BigInteger 转 long：超过 Long.MAX_VALUE 时截断为 Long.MAX_VALUE，负数截断为 0。
     */
    public long getParam2Value() {
        if (param2Value.compareTo(BigInteger.ZERO) < 0) {
            return 0L;
        }
        return param2Value.min(BigInteger.valueOf(Long.MAX_VALUE))
            .longValue();
    }

    /**
     * 设置参数2数值（用于 LongSyncValue 同步）
     * <p>
     * long 转 BigInteger：直接转换，并同步更新 param2Text。
     */
    public void setParam2Value(long val) {
        if (val < 0) val = 0;
        this.param2Value = BigInteger.valueOf(val);
        this.param2Text = this.param2Value.toString();
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
        // 保存测量历史（用于红石功能）
        // 注：unchangedCount 已废弃（300s 窗口均值算法不再使用），不再持久化
        NBTTagCompound historyTag = new NBTTagCompound();
        for (int i = 0; i < measurementHistory.size(); i++) {
            Measurement m = measurementHistory.get(i);
            NBTTagCompound measTag = new NBTTagCompound();
            measTag.setLong("tick", m.tick);
            measTag.setString("value", m.value.toString());
            historyTag.setTag("m" + i, measTag);
        }
        historyTag.setInteger("count", measurementHistory.size());
        aNBT.setTag("measurementHistory", historyTag);

        // 保存当前无线电网能量值（用于红石功能）
        BigInteger currentEU = getWirelessEU();
        if (currentEU != null) {
            aNBT.setString("lastWirelessEU", currentEU.toString());
        }

        // 保存红石控制状态
        aNBT.setInteger("redstoneMode", redstoneMode);
        aNBT.setString("param1Text", param1Text);
        aNBT.setString("param2Text", param2Text);
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
        // 加载测量历史（用于红石功能）
        if (aNBT.hasKey("measurementHistory")) {
            NBTTagCompound historyTag = aNBT.getCompoundTag("measurementHistory");
            int count = historyTag.getInteger("count");
            measurementHistory.clear();
            for (int i = 0; i < count; i++) {
                NBTTagCompound measTag = historyTag.getCompoundTag("m" + i);
                long tick = measTag.getLong("tick");
                BigInteger value = new BigInteger(measTag.getString("value"));
                measurementHistory.add(new Measurement(tick, value));
            }
        }
        // 加载后立即清理过期样本（用户决策8：NBT兼容，加载后立即 purgeExpired 过滤窗口外样本）
        // 若世界尚未初始化，则跳过，等首次 onPostTick 时由 calculateEUT 内部清理
        if (getBaseMetaTileEntity() != null && getBaseMetaTileEntity().getWorld() != null) {
            long currentTick = getBaseMetaTileEntity().getWorld()
                .getTotalWorldTime();
            purgeExpired(currentTick);
        }
        // 注意：lastWirelessEU 不需要保存，因为退出重进后会重新从 WirelessNetworkManager 获取

        // 加载红石控制状态
        redstoneMode = aNBT.getInteger("redstoneMode");
        param1Text = aNBT.getString("param1Text");
        param2Text = aNBT.getString("param2Text");
        if (aNBT.hasKey("param1Value")) {
            param1Value = new BigInteger(aNBT.getString("param1Value"));
        }
        if (aNBT.hasKey("param2Value")) {
            param2Value = new BigInteger(aNBT.getString("param2Value"));
        }
        // 加载保存的红石输出状态
        boolean savedRedstoneOutput = aNBT.getBoolean("redstoneOutput");

        // 重新计算当前的红石输出状态（基于加载的参数和当前电网能量）
        BigInteger currentEU = getWirelessEU();
        if (currentEU != null && redstoneMode > 0) {
            // 根据红石模式和参数重新判断是否应该输出
            switch (redstoneMode) {
                case 1: // 正向：电量 > 参数1
                    redstoneOutput = currentEU.compareTo(param1Value) > 0;
                    break;
                case 2: // 反向：电量 < 参数1
                    redstoneOutput = currentEU.compareTo(param1Value) < 0;
                    break;
                case 3: // 正向区间
                    redstoneOutput = currentEU.compareTo(param1Value) > 0 && currentEU.compareTo(param2Value) >= 0;
                    break;
                case 4: // 反向区间
                    redstoneOutput = currentEU.compareTo(param1Value) <= 0 && currentEU.compareTo(param2Value) < 0;
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
