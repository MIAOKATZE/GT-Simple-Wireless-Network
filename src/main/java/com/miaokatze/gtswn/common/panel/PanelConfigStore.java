package com.miaokatze.gtswn.common.panel;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 面板配置值对象域（O2-02 = E 线 E3，四域拆分第二步）：全部显示/图表配置字段（EU 三组 +
 * AE 走势/实时监控三组）+ 行协议（PacketUpdateNetworkInfoPanelConfig 的 key=value 两套与
 * action 开关族）+ NBT/S35 序列化通道。
 * <p>
 * 方法体自 {@code TileEntityNetworkInfoPanel} 逐字搬迁（E3 表 D 行段整体平移），零行为变更；
 * 本域不持 TE 引用，值变更副作用经构造注入的 {@link DirtyListener} 回调（markDirty +
 * markBlockForUpdate）。序列化只做「段搬运」——键名逐字不动，装配顺序仍由 TE 编排。
 * <p>
 * 编排契约（O2-A06）：{@link #applyConfigAction} 只做值变更并返回是否命中；case 4/7/24 的
 * 跨域后置动作（刷新走势缓存 / 重算状态文本 / AE 立即推送）由 TE 在返回 true 后执行。
 */
public final class PanelConfigStore {

    /** 值变更副作用出口：TE 注入 markDirty + worldObj.markBlockForUpdate */
    public interface DirtyListener {

        void markChanged();
    }

    private final DirtyListener dirty;

    // === EU 显示配置字段 ===
    private boolean showBriefEnergy = true;
    private boolean showBriefStatus = true;
    private boolean showChartEnergy = true;
    private boolean showChartStatus = true;
    private int trackingWindow = NetworkInfoDataSet.WINDOW_5_MIN;
    private int briefRatio = 28;
    // 显示模式：0=常规计数，1=科学计数，2=千位计数 K/M/G/T/P（EU 与 EU/t 均跟随此模式）
    // 默认 1=科学计数，符合大数值场景的常见偏好
    private int displayMode = 1;
    private String energyAxisMin = "";
    private String energyAxisMax = "";
    private String eutAxisMin = "";
    private String eutAxisMax = "";
    private int chartBorderThickness = 3;
    private String chartBackgroundColor = "";
    private int trendLineThickness = 3;
    private int trendLineSmoothing = 2;
    // 走势线样条类型：0=过点样条（Fritsch-Carlson 单调 Hermite，默认），1=拟合样条，2=折线样条，3=数字样条
    private int trendLineSplineType = 0;
    private String screenBackgroundColor = ""; // 默认无背景色，TESR 不绘制背景填充

    // === AE 图表配置字段（v1.5.4）===
    private int aeTrackingWindow = AEMonitorDataSet.WINDOW_5_MIN;
    private int aeChartBorderThickness = 3;
    private String aeChartBackgroundColor = "";
    private int aeTrendLineThickness = 3;
    private int aeTrendLineSmoothing = 2;
    // AE 走势线样条类型：0=过点样条（默认），1=拟合样条，2=折线样条，3=数字样条
    private int aeTrendLineSplineType = 0;
    private String aeAxisMin = "";
    private String aeAxisMax = "";
    private String aeLineColor = "1F6FFF";

    // === AE 走势图显示控制字段（v1.5.5）：旧存档无该字段时默认全部开启，保持向后兼容 ===
    private boolean showAEBrief = true;
    private boolean showAEChartAmount = true;
    private boolean showAEChartRate = true;

    // === AE 实时监控显示配置字段（v1.5.8）===
    private int aeMonitorFontSize = 12; // 字号，范围 8~16
    private boolean aeMonitorBold = false; // 名称是否加粗
    private int aeMonitorRenderMode = 0; // 0=条目(list), 1=格子(grid)
    private int aeMonitorIconSize = 16; // 图标大小，范围 8~32

    public PanelConfigStore(DirtyListener dirty) {
        this.dirty = dirty;
    }

    /**
     * 解析 EU 图表配置字符串，每行 key=value。
     * <p>
     * 支持键：energyMin/energyMax/eutMin/eutMax/border/chartBg/line/smoothing/screenColor。
     *
     * @param payload 配置文本
     */
    public void applyChartConfig(String payload) {
        if (payload == null) {
            return;
        }
        String[] lines = payload.split("\n", -1);
        for (String line : lines) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index);
            String value = cleanText(line.substring(index + 1));
            if ("energyMin".equals(key)) {
                energyAxisMin = value;
            } else if ("energyMax".equals(key)) {
                energyAxisMax = value;
            } else if ("eutMin".equals(key)) {
                eutAxisMin = value;
            } else if ("eutMax".equals(key)) {
                eutAxisMax = value;
            } else if ("border".equals(key)) {
                chartBorderThickness = clampInt(value, chartBorderThickness, 1, 8);
            } else if ("chartBg".equals(key)) {
                chartBackgroundColor = cleanColorText(value);
            } else if ("line".equals(key)) {
                trendLineThickness = clampInt(value, trendLineThickness, 1, 8);
            } else if ("smoothing".equals(key)) {
                trendLineSmoothing = clampInt(value, trendLineSmoothing, 0, 12);
            } else if ("screenColor".equals(key)) {
                screenBackgroundColor = cleanColorText(value);
            }
        }
        dirty.markChanged();
    }

    /**
     * 解析 AE 图表配置字符串，格式与 applyChartConfig 类似，每行 key=value。
     * <p>
     * 支持键：aeWindow, aeBorder, aeBg, aeLineW, aeSmoothing, aeMin, aeMax, aeLineColor。
     *
     * @param payload 配置文本
     */
    public void applyAEChartConfig(String payload) {
        if (payload == null) {
            return;
        }
        String[] lines = payload.split("\n", -1);
        for (String line : lines) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index);
            String value = cleanText(line.substring(index + 1));
            if ("aeWindow".equals(key)) {
                // v1.5.17：上限扩展到 WINDOW_1_YEAR（8 窗口）
                aeTrackingWindow = clampInt(
                    value,
                    aeTrackingWindow,
                    AEMonitorDataSet.WINDOW_5_MIN,
                    AEMonitorDataSet.WINDOW_1_YEAR);
            } else if ("aeBorder".equals(key)) {
                aeChartBorderThickness = clampInt(value, aeChartBorderThickness, 1, 8);
            } else if ("aeBg".equals(key)) {
                aeChartBackgroundColor = cleanColorText(value);
            } else if ("aeLineW".equals(key)) {
                aeTrendLineThickness = clampInt(value, aeTrendLineThickness, 1, 8);
            } else if ("aeSmoothing".equals(key)) {
                aeTrendLineSmoothing = clampInt(value, aeTrendLineSmoothing, 0, 12);
            } else if ("aeMin".equals(key)) {
                aeAxisMin = value;
            } else if ("aeMax".equals(key)) {
                aeAxisMax = value;
            } else if ("aeLineColor".equals(key)) {
                aeLineColor = cleanColorText(value);
            }
        }
        dirty.markChanged();
    }

    /**
     * 面板配置开关/步进行为（PacketUpdateAETabState action 族）的值变更体。
     *
     * @return true=值已变更（TE 据此执行 case 4/7/24 跨域后置动作并 markChanged）；false=未知 action
     */
    public boolean applyConfigAction(int action) {
        switch (action) {
            case 0:
                showBriefEnergy = !showBriefEnergy;
                break;
            case 1:
                showBriefStatus = !showBriefStatus;
                break;
            case 2:
                showChartEnergy = !showChartEnergy;
                break;
            case 3:
                showChartStatus = !showChartStatus;
                break;
            case 4:
                // EU 检测时长窗口环进；刷新走势缓存（跨域后置）由 TE 编排
                trackingWindow = nextTrackingWindow(trackingWindow);
                break;
            case 5:
                briefRatio = Math.max(10, briefRatio - 5);
                break;
            case 6:
                briefRatio = Math.min(80, briefRatio + 5);
                break;
            case 7:
                // 切换显示模式：0->1->2->0（常规/科学/千位），影响 EU 与 EU/t 的格式化；
                // 立即重算 cachedStatus（跨域后置）由 TE 编排，使 GUI/TESR 即时反映新格式（无需等下次采样）
                displayMode = (displayMode + 1) % 3;
                break;
            case 9:
                // EU 走势线样条类型轮换：0过点/1拟合/2折线/3数字
                trendLineSplineType = (trendLineSplineType + 1) % 4;
                break;
            case 20:
                // AE 走势图：简报显示开关
                showAEBrief = !showAEBrief;
                break;
            case 21:
                // AE 走势图：存量曲线开关
                showAEChartAmount = !showAEChartAmount;
                break;
            case 22:
                // AE 走势图：变化率曲线开关
                showAEChartRate = !showAEChartRate;
                break;
            case 24:
                // AE 检测时长窗口：点击标签按钮循环到下一个窗口（与 EU 的 case 4 行为一致）；
                // 立即推送新窗口数据给客户端（跨域后置）由 TE 编排，避免等待下次采样才刷新
                aeTrackingWindow = nextTrackingWindow(aeTrackingWindow);
                break;
            case 25:
                // AE 简报字号减小（复用 EU 的 briefRatio，与 case 5 行为一致）
                briefRatio = Math.max(10, briefRatio - 5);
                break;
            case 26:
                // AE 简报字号增大（复用 EU 的 briefRatio，与 case 6 行为一致）
                briefRatio = Math.min(80, briefRatio + 5);
                break;
            case 27:
                // AE 走势线样条类型轮换：0过点/1拟合/2折线/3数字（与 EU case 9 行为一致）
                aeTrendLineSplineType = (aeTrendLineSplineType + 1) % 4;
                break;
            case 30:
                // AE 实时监控：字号减小（最小 8）
                aeMonitorFontSize = Math.max(8, aeMonitorFontSize - 1);
                break;
            case 31:
                // AE 实时监控：字号增大（最大 16）
                aeMonitorFontSize = Math.min(16, aeMonitorFontSize + 1);
                break;
            case 32:
                // AE 实时监控：名称加粗开关
                aeMonitorBold = !aeMonitorBold;
                break;
            case 33:
                // AE 实时监控：条目/格子显示模式切换
                aeMonitorRenderMode = (aeMonitorRenderMode == 0) ? 1 : 0;
                break;
            case 34:
                // AE 实时监控：图标大小减小（最小 8）
                aeMonitorIconSize = Math.max(8, aeMonitorIconSize - 2);
                break;
            case 35:
                // AE 实时监控：图标大小增大（最大 32）
                aeMonitorIconSize = Math.min(32, aeMonitorIconSize + 2);
                break;
            default:
                return false;
        }
        return true;
    }

    // ==================== EU 显示配置 Getter / Setter ====================

    public boolean isShowBriefEnergy() {
        return showBriefEnergy;
    }

    public boolean isShowBriefStatus() {
        return showBriefStatus;
    }

    public boolean isShowChartEnergy() {
        return showChartEnergy;
    }

    public boolean isShowChartStatus() {
        return showChartStatus;
    }

    public int getTrackingWindow() {
        return trackingWindow;
    }

    public int getBriefRatio() {
        return briefRatio;
    }

    public float getBriefFontScale() {
        return Math.max(10, Math.min(80, briefRatio)) / 20.0F;
    }

    public int getDisplayMode() {
        return displayMode;
    }

    public void setDisplayMode(int mode) {
        // 钳制到 [0,2]，兼容旧存档或异常包中的越界值
        this.displayMode = Math.max(0, Math.min(2, mode));
    }

    public String getEnergyAxisMinText() {
        return energyAxisMin;
    }

    public String getEnergyAxisMaxText() {
        return energyAxisMax;
    }

    public String getEutAxisMinText() {
        return eutAxisMin;
    }

    public String getEutAxisMaxText() {
        return eutAxisMax;
    }

    public int getChartBorderThickness() {
        return chartBorderThickness;
    }

    public String getChartBackgroundColorText() {
        return chartBackgroundColor;
    }

    public int getTrendLineThickness() {
        return trendLineThickness;
    }

    public int getTrendLineSmoothing() {
        return trendLineSmoothing;
    }

    public int getTrendLineSplineType() {
        return trendLineSplineType;
    }

    public String getScreenBackgroundColorText() {
        return screenBackgroundColor;
    }

    public Double getEnergyAxisMin() {
        return parseOptionalDouble(energyAxisMin);
    }

    public Double getEnergyAxisMax() {
        return parseOptionalDouble(energyAxisMax);
    }

    public Double getEutAxisMin() {
        return parseOptionalDouble(eutAxisMin);
    }

    public Double getEutAxisMax() {
        return parseOptionalDouble(eutAxisMax);
    }

    public Integer getChartBackgroundColor() {
        return parseOptionalColor(chartBackgroundColor);
    }

    public boolean hasScreenBackgroundColor() {
        return parseOptionalColor(screenBackgroundColor) != null;
    }

    public int getScreenBackgroundColor() {
        Integer color = parseOptionalColor(screenBackgroundColor);
        return color == null ? 0xDDE1E4 : color.intValue(); // 防御性兜底：hasScreenBackgroundColor() 为 false 时渲染路径不调用此方法
    }

    // ==================== AE 图表配置 Getter（v1.5.4）====================

    public int getAETrackingWindow() {
        return aeTrackingWindow;
    }

    public int getAEChartBorderThickness() {
        return aeChartBorderThickness;
    }

    public String getAEChartBackgroundColorText() {
        return aeChartBackgroundColor;
    }

    public Integer getAEChartBackgroundColor() {
        return parseOptionalColor(aeChartBackgroundColor);
    }

    public int getAETrendLineThickness() {
        return aeTrendLineThickness;
    }

    public int getAETrendLineSmoothing() {
        return aeTrendLineSmoothing;
    }

    public int getAETrendLineSplineType() {
        return aeTrendLineSplineType;
    }

    public String getAEAxisMinText() {
        return aeAxisMin;
    }

    public String getAEAxisMaxText() {
        return aeAxisMax;
    }

    public Double getAEAxisMin() {
        return parseOptionalDouble(aeAxisMin);
    }

    public Double getAEAxisMax() {
        return parseOptionalDouble(aeAxisMax);
    }

    public String getAELineColorText() {
        return aeLineColor;
    }

    public Integer getAELineColor() {
        return parseOptionalColor(aeLineColor);
    }

    // === AE 实时监控显示配置 Getter（v1.5.8）===
    public int getAEMonitorFontSize() {
        return aeMonitorFontSize;
    }

    public boolean isAEMonitorBold() {
        return aeMonitorBold;
    }

    public int getAEMonitorRenderMode() {
        return aeMonitorRenderMode;
    }

    public int getAEMonitorIconSize() {
        return aeMonitorIconSize;
    }

    public boolean isShowAEBrief() {
        return showAEBrief;
    }

    public boolean isShowAEChartAmount() {
        return showAEChartAmount;
    }

    public boolean isShowAEChartRate() {
        return showAEChartRate;
    }

    // ==================== 序列化通道（键名逐字不动；装配顺序由 TE 编排）====================

    /**
     * 区块 NBT 配置字段段读取（原 TE.readFromNBT 的 EU 显示配置 + 三套 config 读写段）。
     */
    public void readFromNBT(NBTTagCompound tag) {
        showBriefEnergy = !tag.hasKey("showBriefEnergy") || tag.getBoolean("showBriefEnergy");
        showBriefStatus = !tag.hasKey("showBriefStatus") || tag.getBoolean("showBriefStatus");
        showChartEnergy = !tag.hasKey("showChartEnergy") || tag.getBoolean("showChartEnergy");
        showChartStatus = !tag.hasKey("showChartStatus") || tag.getBoolean("showChartStatus");
        trackingWindow = tag.getInteger("trackingWindow");
        briefRatio = tag.hasKey("briefRatio") ? tag.getInteger("briefRatio") : 28;
        displayMode = clampInt(tag.hasKey("displayMode") ? tag.getInteger("displayMode") : 1, 0, 2);
        readChartConfig(tag);
        readAEChartConfig(tag);
        readAEMonitorConfig(tag);
    }

    /**
     * 区块 NBT 配置字段段写入（原 TE.writeToNBT 的 EU 显示配置 + 三套 config 写入段）。
     */
    public void writeToNBT(NBTTagCompound tag) {
        tag.setBoolean("showBriefEnergy", showBriefEnergy);
        tag.setBoolean("showBriefStatus", showBriefStatus);
        tag.setBoolean("showChartEnergy", showChartEnergy);
        tag.setBoolean("showChartStatus", showChartStatus);
        tag.setInteger("trackingWindow", trackingWindow);
        tag.setInteger("briefRatio", briefRatio);
        tag.setInteger("displayMode", displayMode);
        writeChartConfig(tag);
        writeAEChartConfig(tag);
        writeAEMonitorConfig(tag);
    }

    /**
     * S35 描述包配置段读取（原 TE.readSyncData 的配置段，键集合与区块 NBT 段一致）。
     */
    public void readSync(NBTTagCompound tag) {
        showBriefEnergy = !tag.hasKey("showBriefEnergy") || tag.getBoolean("showBriefEnergy");
        showBriefStatus = !tag.hasKey("showBriefStatus") || tag.getBoolean("showBriefStatus");
        showChartEnergy = !tag.hasKey("showChartEnergy") || tag.getBoolean("showChartEnergy");
        showChartStatus = !tag.hasKey("showChartStatus") || tag.getBoolean("showChartStatus");
        trackingWindow = tag.getInteger("trackingWindow");
        briefRatio = tag.hasKey("briefRatio") ? tag.getInteger("briefRatio") : 28;
        displayMode = clampInt(tag.hasKey("displayMode") ? tag.getInteger("displayMode") : 1, 0, 2);
        readChartConfig(tag);
        readAEChartConfig(tag);
        readAEMonitorConfig(tag);
    }

    /**
     * S35 描述包配置段写入（原 TE.writeSyncData 的配置段）。
     */
    public void writeSync(NBTTagCompound tag) {
        tag.setBoolean("showBriefEnergy", showBriefEnergy);
        tag.setBoolean("showBriefStatus", showBriefStatus);
        tag.setBoolean("showChartEnergy", showChartEnergy);
        tag.setBoolean("showChartStatus", showChartStatus);
        tag.setInteger("trackingWindow", trackingWindow);
        tag.setInteger("briefRatio", briefRatio);
        tag.setInteger("displayMode", displayMode);
        writeChartConfig(tag);
        writeAEChartConfig(tag);
        writeAEMonitorConfig(tag);
    }

    /**
     * 放置数据 chartConfig 段读取（原 TE.readPlacementData 的 readChartConfig 调用；Owner 段留 TE）。
     */
    public void readPlacement(NBTTagCompound tag) {
        readChartConfig(tag);
    }

    /**
     * 放置数据 chartConfig 段写入（原 TE.writePlacementData 的 writeChartConfig 调用；Owner 段留 TE）。
     */
    public void writePlacement(NBTTagCompound tag) {
        writeChartConfig(tag);
    }

    // ==================== 私有 NBT 段与解析工具（逐字搬迁）====================

    private void writeChartConfig(NBTTagCompound tag) {
        tag.setString("energyAxisMin", energyAxisMin);
        tag.setString("energyAxisMax", energyAxisMax);
        tag.setString("eutAxisMin", eutAxisMin);
        tag.setString("eutAxisMax", eutAxisMax);
        tag.setInteger("chartBorderThickness", chartBorderThickness);
        tag.setString("chartBackgroundColor", chartBackgroundColor);
        tag.setInteger("trendLineThickness", trendLineThickness);
        tag.setInteger("trendLineSmoothing", trendLineSmoothing);
        tag.setInteger("trendLineSplineType", trendLineSplineType);
        tag.setString("screenBackgroundColor", screenBackgroundColor);
    }

    private void readChartConfig(NBTTagCompound tag) {
        energyAxisMin = tag.hasKey("energyAxisMin") ? tag.getString("energyAxisMin") : "";
        energyAxisMax = tag.hasKey("energyAxisMax") ? tag.getString("energyAxisMax") : "";
        eutAxisMin = tag.hasKey("eutAxisMin") ? tag.getString("eutAxisMin") : "";
        eutAxisMax = tag.hasKey("eutAxisMax") ? tag.getString("eutAxisMax") : "";
        chartBorderThickness = tag.hasKey("chartBorderThickness")
            ? clampInt(tag.getInteger("chartBorderThickness"), 1, 8)
            : 3;
        chartBackgroundColor = tag.hasKey("chartBackgroundColor")
            ? cleanColorText(tag.getString("chartBackgroundColor"))
            : "";
        trendLineThickness = tag.hasKey("trendLineThickness") ? clampInt(tag.getInteger("trendLineThickness"), 1, 8)
            : 3;
        trendLineSmoothing = tag.hasKey("trendLineSmoothing") ? clampInt(tag.getInteger("trendLineSmoothing"), 0, 12)
            : 2;
        // 旧存档无此键时默认 0（过点样条），完全向后兼容
        trendLineSplineType = tag.hasKey("trendLineSplineType") ? clampInt(tag.getInteger("trendLineSplineType"), 0, 3)
            : 0;
        screenBackgroundColor = tag.hasKey("screenBackgroundColor")
            ? cleanColorText(tag.getString("screenBackgroundColor"))
            : ""; // 旧存档无此字段时默认空（不绘制背景）
    }

    private void writeAEChartConfig(NBTTagCompound tag) {
        tag.setInteger("aeTrackingWindow", aeTrackingWindow);
        tag.setInteger("aeChartBorderThickness", aeChartBorderThickness);
        tag.setString("aeChartBackgroundColor", aeChartBackgroundColor);
        tag.setInteger("aeTrendLineThickness", aeTrendLineThickness);
        tag.setInteger("aeTrendLineSmoothing", aeTrendLineSmoothing);
        tag.setInteger("aeTrendLineSplineType", aeTrendLineSplineType);
        tag.setString("aeAxisMin", aeAxisMin);
        tag.setString("aeAxisMax", aeAxisMax);
        tag.setString("aeLineColor", aeLineColor);
        tag.setBoolean("showAEBrief", showAEBrief);
        tag.setBoolean("showAEChartAmount", showAEChartAmount);
        tag.setBoolean("showAEChartRate", showAEChartRate);
    }

    private void readAEChartConfig(NBTTagCompound tag) {
        aeTrackingWindow = tag.hasKey("aeTrackingWindow")
            ? clampInt(
                tag.getInteger("aeTrackingWindow"),
                AEMonitorDataSet.WINDOW_5_MIN,
                AEMonitorDataSet.WINDOW_1_YEAR)
            : AEMonitorDataSet.WINDOW_5_MIN;
        aeChartBorderThickness = tag.hasKey("aeChartBorderThickness")
            ? clampInt(tag.getInteger("aeChartBorderThickness"), 1, 8)
            : 3;
        aeChartBackgroundColor = tag.hasKey("aeChartBackgroundColor")
            ? cleanColorText(tag.getString("aeChartBackgroundColor"))
            : "";
        aeTrendLineThickness = tag.hasKey("aeTrendLineThickness")
            ? clampInt(tag.getInteger("aeTrendLineThickness"), 1, 8)
            : 3;
        aeTrendLineSmoothing = tag.hasKey("aeTrendLineSmoothing")
            ? clampInt(tag.getInteger("aeTrendLineSmoothing"), 0, 12)
            : 2;
        // 旧存档无此键时默认 0（过点样条），完全向后兼容
        aeTrendLineSplineType = tag.hasKey("aeTrendLineSplineType")
            ? clampInt(tag.getInteger("aeTrendLineSplineType"), 0, 3)
            : 0;
        aeAxisMin = tag.hasKey("aeAxisMin") ? cleanText(tag.getString("aeAxisMin")) : "";
        aeAxisMax = tag.hasKey("aeAxisMax") ? cleanText(tag.getString("aeAxisMax")) : "";
        aeLineColor = tag.hasKey("aeLineColor") ? cleanColorText(tag.getString("aeLineColor")) : "1F6FFF";
        // 旧存档无这些字段时默认 true，保证图表/简报默认可见
        showAEBrief = !tag.hasKey("showAEBrief") || tag.getBoolean("showAEBrief");
        showAEChartAmount = !tag.hasKey("showAEChartAmount") || tag.getBoolean("showAEChartAmount");
        showAEChartRate = !tag.hasKey("showAEChartRate") || tag.getBoolean("showAEChartRate");
    }

    // === AE 实时监控显示配置 NBT 读写（v1.5.8）===
    private void writeAEMonitorConfig(NBTTagCompound tag) {
        tag.setInteger("aeMonitorFontSize", aeMonitorFontSize);
        tag.setBoolean("aeMonitorBold", aeMonitorBold);
        tag.setInteger("aeMonitorRenderMode", aeMonitorRenderMode);
        tag.setInteger("aeMonitorIconSize", aeMonitorIconSize);
    }

    private void readAEMonitorConfig(NBTTagCompound tag) {
        aeMonitorFontSize = tag.hasKey("aeMonitorFontSize") ? clampInt(tag.getInteger("aeMonitorFontSize"), 8, 16) : 12;
        aeMonitorBold = !tag.hasKey("aeMonitorBold") || tag.getBoolean("aeMonitorBold");
        aeMonitorRenderMode = tag.hasKey("aeMonitorRenderMode") ? clampInt(tag.getInteger("aeMonitorRenderMode"), 0, 1)
            : 0;
        aeMonitorIconSize = tag.hasKey("aeMonitorIconSize") ? clampInt(tag.getInteger("aeMonitorIconSize"), 8, 32) : 16;
    }

    /** EU/AE 检测时长窗口环进（原 TE.nextTrackingWindow；E4 窗口枚举化迁入 {@link WindowLabel#nextWindow}） */
    private static int nextTrackingWindow(int window) {
        return WindowLabel.nextWindow(window);
    }

    static Double parseOptionalDouble(String value) {
        if (value == null || value.trim()
            .isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parseOptionalColor(String value) {
        String clean = cleanColorText(value);
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf((int) Long.parseLong(clean, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String cleanText(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
            .replace('\r', ' ')
            .replace('\n', ' ');
    }

    static String cleanColorText(String value) {
        String clean = cleanText(value).replace("#", "");
        if (clean.length() > 6) {
            clean = clean.substring(0, 6);
        }
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return "";
            }
        }
        return clean.toUpperCase();
    }

    static int clampInt(String value, int fallback, int min, int max) {
        try {
            return clampInt(Integer.parseInt(value), min, max);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
