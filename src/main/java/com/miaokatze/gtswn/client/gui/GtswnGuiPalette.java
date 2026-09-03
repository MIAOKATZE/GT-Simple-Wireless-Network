package com.miaokatze.gtswn.client.gui;

/**
 * gtswn GUI 文字与语义色板（深色工业琥珀主题，代码绘制专用）。
 * <p>
 * 文字/状态色均为不透明 ARGB（0xFF 前缀），可直接传给
 * {@code FontRenderer.drawString / drawStringWithShadow}；分隔线 {@link #DIVIDER}
 * 保留含 alpha 形式供 {@code Gui.drawRect} 使用。
 * <p>
 * 契约出处：plan/ui/texture-list.md §4「Java 消费层 API 契约」之 GtswnGuiPalette，
 * 旧色 → 新色替换表见同文件 §5。
 */
public final class GtswnGuiPalette {

    private GtswnGuiPalette() {}

    // ---------------------------------------------------------------------
    // 文字色（不透明 ARGB，供 drawString 系调用）
    // ---------------------------------------------------------------------

    /** 面板标题字（替代旧 0x404040 标题用法） */
    public static final int TEXT_TITLE = 0xFFF0E8D8;

    /** 正文/列头字（替代旧 0x2F3640、0x404040 正文用法） */
    public static final int TEXT_BODY = 0xFFD8D4C8;

    /** 标签字（替代旧 0x4C5660） */
    public static final int TEXT_LABEL = 0xFFB0AA9A;

    /** 提示/占位/灰字（替代旧 0x6B7680） */
    public static final int TEXT_MUTED = 0xFF9AA0A8;

    /** 强调字：排序列高亮、警示强调（替代旧 0x1F4E79） */
    public static final int TEXT_ACCENT = 0xFFF0A028;

    /** HUD/chip 白字（与旧 0xFFFFFF 一致，不变） */
    public static final int TEXT_WHITE = 0xFFFFFFFF;

    // ---------------------------------------------------------------------
    // 语义状态色（数据状态文字用）
    // ---------------------------------------------------------------------

    /** 在线/正常（替代旧 0x2E7D32） */
    public static final int STATE_ONLINE = 0xFF4CE08A;

    /** 离线/断开（替代旧 0xF44336） */
    public static final int STATE_OFFLINE = 0xFFF05A5A;

    /** 空闲/待机（替代旧 0xE67E22） */
    public static final int STATE_IDLE = 0xFFF0B03C;

    /** 过载/异常（替代旧 0xFF0000） */
    public static final int STATE_OVERLOAD = 0xFFFF4040;

    // ---------------------------------------------------------------------
    // 线条色（含 alpha 形式，供 drawRect）
    // ---------------------------------------------------------------------

    /** 分隔线琥珀暗线（替代旧 0xFFB8C0C8，drawRect 用含 alpha 形式） */
    public static final int DIVIDER = 0xFF7A5A1E;
}
