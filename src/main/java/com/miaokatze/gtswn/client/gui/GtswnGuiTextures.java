package com.miaokatze.gtswn.client.gui;

import net.minecraft.util.ResourceLocation;

/**
 * gtswn 原版风 GUI 贴图资源常量表：15 张贴图的 {@link ResourceLocation} 与真实像素尺寸的唯一权威来源。
 * <p>
 * 尺寸常量（{@code _W}/{@code _H}）与贴图文件一一配对，是 {@link GtswnGuiDrawing} UV 归一化时
 * 唯一允许引用的尺寸来源——绘制代码不得再写死任何纹理分辨率。
 * <p>
 * 契约出处：plan/ui/texture-list.md §3「贴图家族」（15 张，产物直写
 * {@code src/main/resources/assets/gtswn/textures/gui/}）。文件名、尺寸常量与该表逐项对应，
 * 若贴图实际分辨率与常量不符视为贴图侧缺陷，不得修改本表迁就。
 */
public final class GtswnGuiTextures {

    private GtswnGuiTextures() {}

    /** 拼接 gtswn GUI 贴图路径：name → "gtswn:textures/gui/{name}.png" */
    private static ResourceLocation rl(String name) {
        return new ResourceLocation("gtswn", "textures/gui/" + name + ".png");
    }

    // ---------------------------------------------------------------------
    // 整版面板（§2 三块整版，整绘 1:1，不用 9-slice）
    // ---------------------------------------------------------------------

    /** §3 #1 设备信息终端整版面板 450×252（GuiDeviceInfoTerminal 背景替换） */
    public static final ResourceLocation PANEL_DEVICE_INFO = rl("panel_device_info");
    /** #1 宽 450 px */
    public static final int PANEL_DEVICE_INFO_W = 450;
    /** #1 高 252 px */
    public static final int PANEL_DEVICE_INFO_H = 252;

    /** §3 #2 量子终端整版面板 120×92（GuiQuantumTerminal 背景替换） */
    public static final ResourceLocation PANEL_QUANTUM = rl("panel_quantum");
    /** #2 宽 120 px */
    public static final int PANEL_QUANTUM_W = 120;
    /** #2 高 92 px */
    public static final int PANEL_QUANTUM_H = 92;

    /** §3 #3 网络信息面板整版 430×285（GuiNetworkInfoPanel 背景替换） */
    public static final ResourceLocation PANEL_NETWORK_INFO = rl("panel_network_info");
    /** #3 宽 430 px */
    public static final int PANEL_NETWORK_INFO_W = 430;
    /** #3 高 285 px */
    public static final int PANEL_NETWORK_INFO_H = 285;

    // ---------------------------------------------------------------------
    // 按钮与标签页（64×20，9-slice 切片 4px）
    // ---------------------------------------------------------------------

    /** §3 #4 按钮常态：凸起 BTN_FACE 面芯 + 亮/暗斜面（GtswnGuiButton normal） */
    public static final ResourceLocation BUTTON_NORMAL = rl("button_normal");
    /** #4 宽 64 px */
    public static final int BUTTON_NORMAL_W = 64;
    /** #4 高 20 px */
    public static final int BUTTON_NORMAL_H = 20;

    /** §3 #5 按钮 hover：BTN_HOVER 面芯 + 顶缘 1px 琥珀（GtswnGuiButton hover） */
    public static final ResourceLocation BUTTON_HOVER = rl("button_hover");
    /** #5 宽 64 px */
    public static final int BUTTON_HOVER_W = 64;
    /** #5 高 20 px */
    public static final int BUTTON_HOVER_H = 20;

    /** §3 #6 按钮禁用：平框 + BTN_DISABLED 面芯（GtswnGuiButton disabled） */
    public static final ResourceLocation BUTTON_DISABLED = rl("button_disabled");
    /** #6 宽 64 px */
    public static final int BUTTON_DISABLED_W = 64;
    /** #6 高 20 px */
    public static final int BUTTON_DISABLED_H = 20;

    /** §3 #7 标签页选中：凸起 + 顶缘 1px 琥珀（GuiNetworkInfoPanel 3 标签页） */
    public static final ResourceLocation TAB_ACTIVE = rl("tab_active");
    /** #7 宽 64 px */
    public static final int TAB_ACTIVE_W = 64;
    /** #7 高 20 px */
    public static final int TAB_ACTIVE_H = 20;

    /** §3 #8 标签页未选中：凹陷 INSET 面芯（GuiNetworkInfoPanel 3 标签页） */
    public static final ResourceLocation TAB_INACTIVE = rl("tab_inactive");
    /** #8 宽 64 px */
    public static final int TAB_INACTIVE_W = 64;
    /** #8 高 20 px */
    public static final int TAB_INACTIVE_H = 20;

    // ---------------------------------------------------------------------
    // 小件（chip 32×16，9-slice 切片 4px）
    // ---------------------------------------------------------------------

    /** §3 #9 chip 常态：凹陷 INSET 小件（清除/传送 chip） */
    public static final ResourceLocation CHIP_NORMAL = rl("chip_normal");
    /** #9 宽 32 px */
    public static final int CHIP_NORMAL_W = 32;
    /** #9 高 16 px */
    public static final int CHIP_NORMAL_H = 16;

    /** §3 #10 chip 激活：凸起 BTN_FACE 小件（标题栏 chip 纵向拉伸） */
    public static final ResourceLocation CHIP_ACTIVE = rl("chip_active");
    /** #10 宽 32 px */
    public static final int CHIP_ACTIVE_W = 32;
    /** #10 高 16 px */
    public static final int CHIP_ACTIVE_H = 16;

    // ---------------------------------------------------------------------
    // 滚动条（纵向 9-slice 切片 2px）
    // ---------------------------------------------------------------------

    /** §3 #11 滚动条轨道：INSET 凹陷 + 上下端帽（宽 6 不变） */
    public static final ResourceLocation SCROLLBAR_TRACK = rl("scrollbar_track");
    /** #11 宽 6 px */
    public static final int SCROLLBAR_TRACK_W = 6;
    /** #11 高 16 px */
    public static final int SCROLLBAR_TRACK_H = 16;

    /** §3 #12 滚动条滑块：凸起 BTN_FACE */
    public static final ResourceLocation SCROLLBAR_THUMB = rl("scrollbar_thumb");
    /** #12 宽 6 px */
    public static final int SCROLLBAR_THUMB_W = 6;
    /** #12 高 12 px */
    public static final int SCROLLBAR_THUMB_H = 12;

    // ---------------------------------------------------------------------
    // 列表与 HUD
    // ---------------------------------------------------------------------

    /** §3 #13 列表行 hover：ROW_HOVER 填充 + 左缘 2px 琥珀暗线（横向 9-slice 切片 4px） */
    public static final ResourceLocation ROW_HOVER = rl("row_hover");
    /** #13 宽 32 px */
    public static final int ROW_HOVER_W = 32;
    /** #13 高 20 px */
    public static final int ROW_HOVER_H = 20;

    /** §3 #14 HUD 半透明黑底：全像素 RGBA(0,0,0,0x80)（整图拉伸，blend 由调用方管理） */
    public static final ResourceLocation HUD_BASE = rl("hud_base");
    /** #14 宽 8 px */
    public static final int HUD_BASE_W = 8;
    /** #14 高 8 px */
    public static final int HUD_BASE_H = 8;

    /** §3 #15 列表底：INSET 凹陷（9-slice 切片 4px，替换 0xFFEDF1F5 平色） */
    public static final ResourceLocation LIST_PANEL = rl("list_panel");
    /** #15 宽 32 px */
    public static final int LIST_PANEL_W = 32;
    /** #15 高 32 px */
    public static final int LIST_PANEL_H = 32;
}
