package com.miaokatze.gtswn.common.panel;

/**
 * 检测时长窗口枚举（O2-A04 窗口枚举化 = E 线 E4）：统一承载窗口常量 → 短名（TESR 时间轴
 * 标签与 GUI EU 窗口按钮）与 GUI langKey（AE 窗口按钮本地化显示）两套显示映射，外加环进
 * {@link #nextWindow(int)}。
 * <p>
 * 取代四处同构 switch（方法体逐字搬迁语义）：TE {@code getWindowName} /
 * {@link PanelConfigStore} 原 {@code nextTrackingWindow} / Render {@code aeWindowName} /
 * Gui {@code getAEWindowDisplay}。窗口常量值与 {@link WindowChain} 单源一致（0..7，
 * 枚举序与常量值同序）；越界/未知值回落 5m，与原各 switch 的 default 分支语义一致。
 */
public enum WindowLabel {

    /** 5 分钟 */
    MIN_5(WindowChain.WINDOW_5_MIN, "5m", "gtswn.network_info.gui.ae.window.5min"),
    /** 1 小时 */
    HOUR_1(WindowChain.WINDOW_1_HOUR, "1h", "gtswn.network_info.gui.ae.window.1h"),
    /** 8 小时 */
    HOUR_8(WindowChain.WINDOW_8_HOUR, "8h", "gtswn.network_info.gui.ae.window.8h"),
    /** 24 小时 */
    HOUR_24(WindowChain.WINDOW_24_HOUR, "24h", "gtswn.network_info.gui.ae.window.24h"),
    /** 7 天 */
    DAY_7(WindowChain.WINDOW_7_DAY, "7d", "gtswn.network_info.gui.ae.window.7d"),
    /** 1 个月 */
    MONTH_1(WindowChain.WINDOW_1_MONTH, "1M", "gtswn.network_info.gui.ae.window.1M"),
    /** 3 个月 */
    MONTH_3(WindowChain.WINDOW_3_MONTH, "3M", "gtswn.network_info.gui.ae.window.3M"),
    /** 1 年 */
    YEAR_1(WindowChain.WINDOW_1_YEAR, "1Y", "gtswn.network_info.gui.ae.window.1Y");

    /** 窗口常量值（与 WindowChain 及 NetworkInfoDataSet/AEMonitorDataSet 的 WINDOW_* 别名同值） */
    public final int window;

    private final String shortName;
    private final String langKey;

    /** 下标 = 窗口常量值（依赖「枚举序 == 常量值 0..7」不变量，values() 构造期固化一份） */
    private static final WindowLabel[] BY_WINDOW = values();

    private WindowLabel(int window, String shortName, String langKey) {
        this.window = window;
        this.shortName = shortName;
        this.langKey = langKey;
    }

    /** 短名（"5m".."1Y"）：原 getWindowName / aeWindowName 两套 switch 的返回值 */
    public String shortName() {
        return shortName;
    }

    /** GUI 本地化键：原 GuiNetworkInfoPanel.getAEWindowDisplay 的八分支 langKey */
    public String langKey() {
        return langKey;
    }

    /** 窗口常量 → 枚举；越界/未知值回落 5m（与原各 switch default 分支一致） */
    public static WindowLabel of(int window) {
        return window >= 0 && window < BY_WINDOW.length ? BY_WINDOW[window] : MIN_5;
    }

    /**
     * 窗口环进（原 nextTrackingWindow 逐字搬迁语义）：5m→1h→8h→24h→7d→1M→3M→1Y→5m；
     * 1Y 与越界/未知值一律回落 5m。
     */
    public static int nextWindow(int window) {
        return window >= WindowChain.WINDOW_5_MIN && window < WindowChain.WINDOW_1_YEAR ? window + 1
            : WindowChain.WINDOW_5_MIN;
    }
}
