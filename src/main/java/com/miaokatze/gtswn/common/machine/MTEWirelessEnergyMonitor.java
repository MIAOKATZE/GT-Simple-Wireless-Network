package com.miaokatze.gtswn.common.machine;

import java.math.BigInteger;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;
import com.gtnewhorizons.modularui.common.widget.ButtonWidget;
import com.gtnewhorizons.modularui.common.widget.FakeSyncWidget;
import com.gtnewhorizons.modularui.common.widget.TextWidget;

import gregtech.api.gui.modularui.GTUITextures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.common.misc.WirelessNetworkManager;

/**
 * 无线能量监视器 (LV)
 * 用于实时监控玩家所属团队的无线电网能量状态。
 * 此机器不消耗也不产生能量，只是一个信息显示设备。
 */
public class MTEWirelessEnergyMonitor extends MTEBasicGenerator {

    private UUID ownerUUID;
    private int displayMode; // 0=常规计数, 1=科学计数

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
    private int unchangedCount = 0;
    private static final int MAX_UNCHANGED_COUNT = 1200; // 10分钟 = 1200次 * 0.5秒

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

    // 构造函数:用于注册
    public MTEWirelessEnergyMonitor(int aID, String aName, String aNameRegional) {
        super(
            aID,
            aName,
            aNameRegional,
            1,
            new String[] { net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line1"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line2"),
                net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line3") });
    }

    // 拷贝构造函数
    public MTEWirelessEnergyMonitor(String aName, int aTier, String[] aDescription, ITexture[][][] aTextures) {
        super(aName, aTier, aDescription, aTextures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEWirelessEnergyMonitor(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    public int getPollution() {
        return 0;
    }

    @Override
    public int getCapacity() {
        return 0;
    }

    @Override
    public int getEfficiency() {
        return 0;
    }

    @Override
    public String[] getDescription() {
        // 返回构造函数中传入的描述数组，不包含燃料效率等信息
        return new String[] { net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line1"),
            net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line2"),
            net.minecraft.util.StatCollector.translateToLocal("gtswn.desc.wireless_monitor.line3") };
    }

    @Override
    public gregtech.api.recipe.RecipeMap<?> getRecipeMap() {
        return null;
    }

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
        if (ownerUUID == null) {
            ownerUUID = aBaseMetaTileEntity.getOwnerUuid();
        }
    }

    @Override
    public void onPostTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        super.onPostTick(aBaseMetaTileEntity, aTick);

        // 每 10 ticks (0.5秒) 更新一次 UI 显示
        if (aTick % 10L == 0L) {
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
    public byte getGeneralRS(net.minecraftforge.common.util.ForgeDirection side) {
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

    // 记录测量历史（只记录发生变化的点）
    private void recordMeasurement(long tick, BigInteger value) {
        if (measurementHistory.isEmpty()) {
            measurementHistory.add(new Measurement(tick, value));
            unchangedCount = 0;
            return;
        }

        Measurement latest = measurementHistory.get(measurementHistory.size() - 1);

        if (!latest.value.equals(value)) {
            measurementHistory.add(new Measurement(tick, value));
            unchangedCount = 0;

            if (measurementHistory.size() > 10) {
                measurementHistory.remove(0);
            }
        } else {
            unchangedCount++;
        }
    }

    // 计算 EU/t
    private double calculateEUT(long currentTick) {
        if (unchangedCount >= MAX_UNCHANGED_COUNT) {
            return 0.0; // 超过阈值，返回 0
        }

        if (measurementHistory.size() < 2) {
            return 0.0;
        }

        Measurement latest = measurementHistory.get(measurementHistory.size() - 1);
        Measurement previous = measurementHistory.get(measurementHistory.size() - 2);

        BigInteger diff = latest.value.subtract(previous.value);
        long tickDiff = latest.tick - previous.tick;

        if (tickDiff <= 0) return 0.0;

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
    private String getnetworkStatusText() {
        if (euPerTick > 0.01) {
            String euPerTickStr = formatEUtValue(euPerTick);
            String gtPowerText = formatGTPower(euPerTick);
            return translate("gtswn.ui.network.status.up", euPerTickStr, gtPowerText);
        } else if (euPerTick < -0.01) {
            String euPerTickStr = formatEUtValue(Math.abs(euPerTick));
            String gtPowerText = formatGTPower(Math.abs(euPerTick));
            return translate("gtswn.ui.network.status.down", euPerTickStr, gtPowerText);
        } else {
            return translate("gtswn.ui.network.status.nochange");
        }
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

    // 右键打开 GUI - MTEBasicGenerator 已自动处理

    /**
     * 禁用玩家物品栏绑定
     * 此机器不需要物品栏，禁用后可以自由调整窗口大小
     */
    @Override
    public boolean doesBindPlayerInventory() {
        return false;
    }

    /**
     * 是否发出红石信号
     */
    public boolean emitsRedstoneSignal() {
        return redstoneOutput && redstoneMode > 0;
    }

    /**
     * 根据红石输出状态切换贴图（叠加在基础材质上）
     */
    @Override
    public ITexture[] getTexture(IGregTechTileEntity baseMetaTileEntity, ForgeDirection side,
        ForgeDirection facingDirection, int colorIndex, boolean active, boolean redstoneLevel) {
        // 先获取基础机器的默认材质
        ITexture[] baseTextures = super.getTexture(
            baseMetaTileEntity,
            side,
            facingDirection,
            colorIndex,
            active,
            redstoneLevel);

        // 只有正面（facingDirection）叠加自定义贴图
        if (side == facingDirection) {
            // 选择 ON 或 OFF 贴图
            gregtech.api.interfaces.IIconContainer icon = redstoneLevel
                ? com.miaokatze.gtswn.register.TextureManager.TEX_WIRELESS_MONITOR_ON
                : com.miaokatze.gtswn.register.TextureManager.TEX_WIRELESS_MONITOR_OFF;

            // 创建新数组：基础材质 + 自定义贴图（叠加在上层）
            ITexture[] result = new ITexture[baseTextures.length + 1];
            System.arraycopy(baseTextures, 0, result, 0, baseTextures.length);
            result[baseTextures.length] = gregtech.api.render.TextureFactory.of(icon);
            return result;
        }

        // 其他面只返回基础材质
        return baseTextures;
    }

    @Override
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext buildContext) {
        // 设置窗口大小为 236x150（禁用物品栏后，足够容纳所有内容）
        builder.setSize(236, 150);

        // 标题
        builder.widget(
            new TextWidget(translate("gtswn.ui.title")).setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 6));

        // 切换按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            displayMode = 1 - displayMode; // 切换模式
            // 标记数据需要保存
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.toggle.mode"))
            .setPos(192, 5)
            .setSize(16, 16));

        // 模式显示文字（动态更新）
        builder.widget(
            TextWidget.dynamicString(() -> cachedModeText)
                .setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 25));

        // 无线电网能量显示（动态更新）
        builder.widget(
            TextWidget.dynamicString(() -> cachedEUText)
                .setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 38));

        // 电网状态 EU/t（动态更新）
        builder.widget(
            TextWidget.dynamicString(() -> cachedStatusText)
                .setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 51));

        // 红石控制区域
        // 红石模式显示
        builder.widget(
            TextWidget.dynamicString(() -> cachedRedstoneModeText)
                .setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 68));

        // 切换红石模式按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            redstoneMode = (redstoneMode + 1) % 5; // 循环切换 0->1->2->3->4->0
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.toggle.redstone"))
            .setPos(192, 67)
            .setSize(16, 16));

        // 红石输出状态显示
        builder.widget(
            TextWidget.dynamicString(() -> cachedRedstoneOutputText)
                .setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 82));

        // 红石模式说明（动态更新）
        builder.widget(
            TextWidget.dynamicString(() -> cachedModeDescText)
                .setDefaultColor(COLOR_TEXT_WHITE.get())
                .setPos(10, 96));

        // 参数输入区域（使用按钮调整）
        boolean paramsEnabled = redstoneMode > 0; // 关闭模式下禁用参数
        boolean param2Enabled = paramsEnabled && (redstoneMode == 3 || redstoneMode == 4);

        // 参数1显示和调整
        builder.widget(TextWidget.dynamicString(() -> {
            String formattedValue = displayMode == 0 ? formatNormal(param1Value) : formatScientific(param1Value);
            return translate("gtswn.ui.param1", formattedValue);
        })
            .setDefaultColor(paramsEnabled ? COLOR_TEXT_WHITE.get() : 0x808080)
            .setPos(10, 110));

        // 参数1控制按钮（始终添加，但根据模式启用/禁用）
        int buttonY = 109;
        int buttonStartX = 155;
        int buttonSize = 8;
        int buttonSpacing = 2;

        // - 按钮（减少最高位）
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            // 动态计算当前最高位的步长
            long absParam1 = param1Value.abs()
                .longValue();
            long step = absParam1 > 0 ? (long) Math.pow(10, (int) Math.log10(absParam1)) : 1;

            // 特殊处理：当值为最高位本身时（如100、1000），退位到下一数量级
            BigInteger newValue;
            if (param1Value.equals(BigInteger.valueOf(step))) {
                // 退位：100 -> 90, 1000 -> 900
                long nextStep = step / 10;
                if (nextStep < 1) nextStep = 1;
                newValue = param1Value.subtract(BigInteger.valueOf(nextStep));
            } else {
                newValue = param1Value.subtract(BigInteger.valueOf(step));
            }

            if (newValue.compareTo(BigInteger.ZERO) >= 0) {
                param1Value = newValue;
                param1Text = param1Value.toString();
                if (getBaseMetaTileEntity() != null) {
                    getBaseMetaTileEntity().markDirty();
                }
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param1.minus"))
            .setPos(buttonStartX, buttonY)
            .setSize(buttonSize, buttonSize));

        // + 按钮（增加最高位）
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            // 动态计算当前最高位的步长
            long absParam1 = param1Value.abs()
                .longValue();
            long step = absParam1 > 0 ? (long) Math.pow(10, (int) Math.log10(absParam1)) : 1;

            param1Value = param1Value.add(BigInteger.valueOf(step));
            param1Text = param1Value.toString();
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param1.plus"))
            .setPos(buttonStartX + buttonSize + buttonSpacing, buttonY)
            .setSize(buttonSize, buttonSize));

        // ×10 按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            param1Value = param1Value.multiply(BigInteger.TEN);
            param1Text = param1Value.toString();
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param1.mul10"))
            .setPos(buttonStartX + (buttonSize + buttonSpacing) * 2, buttonY)
            .setSize(buttonSize, buttonSize));

        // ÷10 按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            param1Value = param1Value.divide(BigInteger.TEN);
            param1Text = param1Value.toString();
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param1.div10"))
            .setPos(buttonStartX + (buttonSize + buttonSpacing) * 3, buttonY)
            .setSize(buttonSize, buttonSize));

        // 清零按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            param1Value = BigInteger.ZERO;
            param1Text = "0";
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param1.reset"))
            .setPos(buttonStartX + (buttonSize + buttonSpacing) * 4, buttonY)
            .setSize(buttonSize, buttonSize));

        // 参数2显示和调整（仅在模式3和4时启用）
        builder.widget(TextWidget.dynamicString(() -> {
            String formattedValue = displayMode == 0 ? formatNormal(param2Value) : formatScientific(param2Value);
            return translate("gtswn.ui.param2", formattedValue);
        })
            .setDefaultColor(param2Enabled ? COLOR_TEXT_WHITE.get() : 0x808080)
            .setPos(10, 123));

        // 参数2控制按钮（始终添加，但根据模式启用/禁用）
        int buttonY2 = 122;

        // - 按钮（减少最高位）
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            // 动态计算当前最高位的步长
            long absParam2 = param2Value.abs()
                .longValue();
            long step = absParam2 > 0 ? (long) Math.pow(10, (int) Math.log10(absParam2)) : 1;

            // 特殊处理：当值为最高位本身时（如100、1000），退位到下一数量级
            BigInteger newValue;
            if (param2Value.equals(BigInteger.valueOf(step))) {
                // 退位：100 -> 90, 1000 -> 900
                long nextStep = step / 10;
                if (nextStep < 1) nextStep = 1;
                newValue = param2Value.subtract(BigInteger.valueOf(nextStep));
            } else {
                newValue = param2Value.subtract(BigInteger.valueOf(step));
            }

            // 验证：参数2不能大于参数1，且不能小于0
            if (newValue.compareTo(BigInteger.ZERO) >= 0 && newValue.compareTo(param1Value) <= 0) {
                param2Value = newValue;
                param2Text = param2Value.toString();
                if (getBaseMetaTileEntity() != null) {
                    getBaseMetaTileEntity().markDirty();
                }
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param2.minus"))
            .setPos(buttonStartX, buttonY2)
            .setSize(buttonSize, buttonSize));

        // + 按钮（增加最高位）
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            // 动态计算当前最高位的步长
            long absParam2 = param2Value.abs()
                .longValue();
            long step = absParam2 > 0 ? (long) Math.pow(10, (int) Math.log10(absParam2)) : 1;

            BigInteger newValue = param2Value.add(BigInteger.valueOf(step));
            // 验证：参数2不能大于参数1
            if (newValue.compareTo(param1Value) <= 0) {
                param2Value = newValue;
                param2Text = param2Value.toString();
                if (getBaseMetaTileEntity() != null) {
                    getBaseMetaTileEntity().markDirty();
                }
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param2.plus"))
            .setPos(buttonStartX + buttonSize + buttonSpacing, buttonY2)
            .setSize(buttonSize, buttonSize));

        // ×10 按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            BigInteger newValue = param2Value.multiply(BigInteger.TEN);
            // 验证：参数2不能大于参数1
            if (newValue.compareTo(param1Value) <= 0) {
                param2Value = newValue;
                param2Text = param2Value.toString();
                if (getBaseMetaTileEntity() != null) {
                    getBaseMetaTileEntity().markDirty();
                }
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param2.mul10"))
            .setPos(buttonStartX + (buttonSize + buttonSpacing) * 2, buttonY2)
            .setSize(buttonSize, buttonSize));

        // ÷10 按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            param2Value = param2Value.divide(BigInteger.TEN);
            param2Text = param2Value.toString();
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param2.div10"))
            .setPos(buttonStartX + (buttonSize + buttonSpacing) * 3, buttonY2)
            .setSize(buttonSize, buttonSize));

        // 清零按钮
        builder.widget(new ButtonWidget().setOnClick((clickData, widget) -> {
            param2Value = BigInteger.ZERO;
            param2Text = "0";
            if (getBaseMetaTileEntity() != null) {
                getBaseMetaTileEntity().markDirty();
            }
        })
            .setPlayClickSound(true)
            .setBackground(GTUITextures.BUTTON_STANDARD)
            .addTooltip(translate("gtswn.ui.tooltip.param2.reset"))
            .setPos(buttonStartX + (buttonSize + buttonSpacing) * 4, buttonY2)
            .setSize(buttonSize, buttonSize));

        // 同步数据到客户端
        builder.widget(new FakeSyncWidget.IntegerSyncer(() -> displayMode, val -> displayMode = val));
        builder.widget(new FakeSyncWidget.StringSyncer(() -> cachedModeText, val -> cachedModeText = val));
        builder.widget(new FakeSyncWidget.StringSyncer(() -> cachedEUText, val -> cachedEUText = val));
        builder.widget(new FakeSyncWidget.StringSyncer(() -> cachedStatusText, val -> cachedStatusText = val));
        builder.widget(new FakeSyncWidget.IntegerSyncer(() -> redstoneMode, val -> redstoneMode = val));
        builder.widget(new FakeSyncWidget.StringSyncer(() -> param1Text, val -> param1Text = val));
        builder.widget(new FakeSyncWidget.StringSyncer(() -> param2Text, val -> param2Text = val));
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
        aNBT.setInteger("unchangedCount", unchangedCount);
        // 保存测量历史（用于红石功能）
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
        unchangedCount = aNBT.getInteger("unchangedCount");
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

    // 此机器不消耗能量
    @Override
    public long maxEUStore() {
        return 0;
    }

    @Override
    public long maxEUInput() {
        return 0;
    }

    @Override
    public long maxEUOutput() {
        return 0;
    }

    @Override
    public int getLightOpacity() {
        return 0;
    }
}
