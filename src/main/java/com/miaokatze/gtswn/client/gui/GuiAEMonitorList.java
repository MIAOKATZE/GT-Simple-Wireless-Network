package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;

/**
 * AE 实时监控自定义滚动列表（O2-07/R4：由 GuiNetworkInfoPanel 285 行内嵌类提级为顶级包私有类）。
 * <p>
 * 不继承 {@code GuiSlot}，自行管理滚动偏移、滚动条拖拽、鼠标滚轮与条目绘制。
 * 每行左侧显示图标、名称与数量/存量，中间显示实时变化量 / 平均变化量两列，右侧 60 像素为“清除”按钮区域。
 * 点击非清除区域无动作；点击清除区域经 removeCallback 向服务端发送移除包（action 3=物品 / 4=流体）。
 * <p>
 * 提级后原 8 项隐式捕获显式化：几何（left/top/xSize 构造快照）、条目数组（{@code Supplier} 活读）、
 * 移除回包（{@code IntConsumer}）、panel 引用、宿主 GUI（fontRendererObj/mc/width/height 经宿主
 * 包私有访问器——GuiScreen 字段跨包 protected 不可直引）、图标绘制（宿主包私有方法）、
 * 格式化三件套随迁本类（O2-A07 ClientChartFormat 落地后再改引）、矩形填充经宿主转发 Gui.drawRect
 * （S6a 贴图化后本类绘制全部改走 GtswnGuiDrawing，不再使用 fillRect）。
 * <p>
 * 事件链不变量：滚轮优先于 super、列表区内点击一律消费、mouseMovedOrUp 无标签页条件直调
 * （宿主侧调用点维持原状）。
 */
class GuiAEMonitorList {

    // ==================== 宿主注入（原隐式捕获显式化） ====================
    /** 宿主 GUI：字体/ Minecraft /屏幕尺寸访问与图标绘制、矩形填充转发 */
    private final GuiNetworkInfoPanel host;
    /** 信息屏 TE：显示模式与监控最新值/均值读取 */
    private final TileEntityNetworkInfoPanel panel;
    /** 当前监控条目数组活读（宿主 updateScreen 刷新后本列表即时可见） */
    private final Supplier<Object[]> entries;
    /** 清除按钮回包：入参为条目索引（宿主 sendMonitorToggle，action 3/4） */
    private final IntConsumer removeCallback;

    // ==================== 几何常量 ====================
    /** 列表左边界（面板内部左侧留 8px） */
    private final int listLeft;
    /** 列表上边界（位于表头下方） */
    private final int listTop;
    /** 列表右边界（面板内部右侧留 8px） */
    private final int listRight;
    /** 列表内容宽度（430 - 16 = 414） */
    private final int listWidth;
    /** 列表可视高度（从 top+163 到 top+270） */
    private final int listHeight;
    /** 列表下边界（与原 GuiSlot 的 bottom 一致） */
    private final int listBottom;
    /** 单行高度 */
    private final int slotHeight = 20;
    /** 滚动条宽度 */
    private final int scrollbarWidth = 6;
    /** 滚动条距离列表右边距 */
    private final int scrollbarMarginRight = 2;

    // ==================== 滚动状态 ====================
    /** 当前顶部被滚掉的行数 */
    private int scrollOffset = 0;
    /** 是否正在拖拽滚动条 */
    private boolean draggingScrollbar = false;

    GuiAEMonitorList(GuiNetworkInfoPanel host, TileEntityNetworkInfoPanel panel, int left, int top, int xSize,
        Supplier<Object[]> entries, IntConsumer removeCallback) {
        this.host = host;
        this.panel = panel;
        this.entries = entries;
        this.removeCallback = removeCallback;
        this.listLeft = left + 8;
        this.listTop = top + 163;
        this.listRight = left + xSize - 8;
        this.listWidth = xSize - 16;
        this.listHeight = top + 270 - listTop;
        this.listBottom = listTop + listHeight;
    }

    // ==================== 外部绘制入口 ====================
    /**
     * 绘制整个列表，包括背景、可见行、滚动条。
     *
     * @param mouseX       鼠标 X（屏幕坐标）
     * @param mouseY       鼠标 Y（屏幕坐标）
     * @param partialTicks 部分刻
     */
    void draw(int mouseX, int mouseY, float partialTicks) {
        Object[] monitoredEntries = entries.get();
        clampScroll();
        drawListBackground();
        enableListScissor();
        int firstRow = scrollOffset;
        // 多渲染一行以覆盖可能部分显示的最底行
        int lastRow = Math.min(monitoredEntries.length, firstRow + visibleRows() + 1);
        for (int i = firstRow; i < lastRow; i++) {
            int y = listTop + (i - firstRow) * slotHeight;
            drawSlot(i, listLeft, y, mouseX, mouseY);
        }
        disableListScissor();
        drawScrollbar();
    }

    // ==================== 条目绘制 ====================
    /**
     * 绘制单行内容：图标、名称、实时变化量、平均变化量、清除按钮。
     *
     * @param index  条目索引
     * @param x      行左上角 X
     * @param y      行左上角 Y
     * @param mouseX 鼠标 X
     * @param mouseY 鼠标 Y
     */
    private void drawSlot(int index, int x, int y, int mouseX, int mouseY) {
        // 行 hover 高亮（契约 §6-④ 新增视觉项）：ROW_HOVER 9-slice 先画、行内容后画；
        // 纯绘制判定，命中/点击/滚动逻辑零改动。命中区右界让位滚动条区（宽 6 + 边距 2）。
        int hoverRight = listRight - scrollbarWidth - scrollbarMarginRight;
        if (mouseX >= listLeft && mouseX < hoverRight && mouseY >= y && mouseY < y + slotHeight) {
            GtswnGuiDrawing
                .drawNineSlice(GtswnGuiTextures.ROW_HOVER, 4, listLeft, y, listWidth, slotHeight, host.guiZLevel());
        }
        Object entry = entries.get()[index];
        int displayMode = panel.getDisplayMode();
        int iconSize = 16;
        int iconY = y + (slotHeight - iconSize) / 2;
        String name;
        String key;
        if (entry instanceof ItemStack) {
            ItemStack stack = (ItemStack) entry;
            host.drawItemIcon(stack, x, iconY);
            name = stack.getDisplayName();
            key = TileEntityNetworkInfoPanel.getAEKey(stack);
        } else if (entry instanceof FluidStack) {
            FluidStack fluid = (FluidStack) entry;
            host.drawFluidIcon(fluid, x, iconY);
            name = fluid.getLocalizedName();
            key = TileEntityNetworkInfoPanel.getAEKey(fluid);
        } else {
            return;
        }

        // 提前获取采样数据，供数量列与变化量列共用
        AEMonitorSample sample = key == null ? null
            : panel.getAEMonitorLatest()
                .get(key);

        // 名称列：限制宽度，避免与数量列重叠（数量列起始于 x+110）
        int nameMaxW = 84;
        host.font()
            .drawString(
                host.font()
                    .trimStringToWidth(name, nameMaxW),
                x + 22,
                y + 6,
                GtswnGuiPalette.TEXT_BODY);

        // 数量/存量列
        String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, displayMode);
        host.font()
            .drawString(amountText, x + 110, y + 6, GtswnGuiPalette.TEXT_BODY);

        // 实时变化量 / 平均变化量两列
        String realtimeText;
        int realtimeColor;
        String averageText;
        int averageColor;
        if (sample == null) {
            realtimeText = "-";
            realtimeColor = GtswnGuiPalette.TEXT_MUTED;
            averageText = "-";
            averageColor = GtswnGuiPalette.TEXT_MUTED;
        } else {
            realtimeText = formatAEMonitorRate(sample.rate, displayMode);
            realtimeColor = rateColor(sample.rate);
            Double avgRate = panel.getAEMonitorAvg300s()
                .get(key);
            if (avgRate == null) {
                averageText = "-";
                averageColor = GtswnGuiPalette.TEXT_MUTED;
            } else {
                averageText = formatAEMonitorRate(avgRate, displayMode);
                averageColor = rateColor(avgRate);
            }
        }
        host.font()
            .drawString(realtimeText, x + 170, y + 6, realtimeColor);
        host.font()
            .drawString(averageText, x + 260, y + 6, averageColor);

        // 清除按钮 chip：CHIP_NORMAL 9-slice，几何不变（契约 §3 #9 / §6-⑤）
        int btnX = listRight - 62;
        int btnY = y + 4;
        int btnW = 56;
        int btnH = slotHeight - 8;
        GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.CHIP_NORMAL, 4, btnX, btnY, btnW, btnH, host.guiZLevel());
        String removeText = tr("gtswn.network_info.gui.ae.remove");
        int textW = host.font()
            .getStringWidth(removeText);
        host.font()
            .drawString(removeText, btnX + (btnW - textW) / 2, btnY + 2, GtswnGuiPalette.TEXT_BODY);
    }

    // ==================== 背景与裁剪 ====================
    /**
     * 绘制列表背景（LIST_PANEL 9-slice，几何零变化：覆盖面板背景分隔线，避免列表区出现不需要的线条）。
     * 契约出处：plan/ui/texture-list.md §3 #15 / §6-⑤。
     */
    private void drawListBackground() {
        GtswnGuiDrawing
            .drawNineSlice(GtswnGuiTextures.LIST_PANEL, 4, listLeft, listTop, listWidth, listHeight, host.guiZLevel());
    }

    /**
     * 启用剪刀测试，将后续绘制限制在列表可视区域内。
     * <p>
     * OpenGL 的 scissor 坐标以屏幕左下角为原点，单位是像素，因此需要按 GUI 缩放比例转换。
     */
    private void enableListScissor() {
        ScaledResolution sr = new ScaledResolution(
            host.client(),
            host.client().displayWidth,
            host.client().displayHeight);
        int scale = sr.getScaleFactor();
        int sx = listLeft * scale;
        int sy = host.client().displayHeight - listBottom * scale;
        int sw = listWidth * scale;
        int sh = listHeight * scale;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(sx, sy, sw, sh);
    }

    /** 关闭剪刀测试，恢复普通绘制。 */
    private void disableListScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ==================== 滚动条 ====================
    /** 绘制滚动条轨道与滑块。 */
    private void drawScrollbar() {
        Object[] monitoredEntries = entries.get();
        int trackX = listRight - scrollbarWidth - scrollbarMarginRight;
        int maxScroll = getMaxScroll();
        // 轨道：SCROLLBAR_TRACK 纵向 9-slice（slice=2，宽 6 与轨道几何不变；契约 §3 #11 / §6-⑤）
        GtswnGuiDrawing.drawNineSlice(
            GtswnGuiTextures.SCROLLBAR_TRACK,
            2,
            trackX,
            listTop,
            scrollbarWidth,
            listHeight,
            host.guiZLevel());
        if (maxScroll > 0) {
            int totalRows = Math.max(visibleRows(), monitoredEntries.length);
            int thumbH = Math.max(10, listHeight * visibleRows() / totalRows);
            int thumbY = listTop + scrollOffset * (listHeight - thumbH) / maxScroll;
            GtswnGuiDrawing.drawNineSlice(
                GtswnGuiTextures.SCROLLBAR_THUMB,
                2,
                trackX,
                thumbY,
                scrollbarWidth,
                thumbH,
                host.guiZLevel());
        }
    }

    // ==================== 滚动计算 ====================
    /** 返回最大可滚动行数（总条目 - 可见行数，至少为 0）。 */
    private int getMaxScroll() {
        return Math.max(0, entries.get().length - visibleRows());
    }

    /** 返回列表可视区域可容纳的完整行数。 */
    private int visibleRows() {
        return listHeight / slotHeight;
    }

    /** 将 scrollOffset 限制在合法范围内（宿主 updateScreen 在条目数变化后调用校正入口）。 */
    void clampScroll() {
        int max = getMaxScroll();
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        if (scrollOffset > max) {
            scrollOffset = max;
        }
    }

    /** 按 delta 行滚动并限制范围。 */
    private void scrollBy(int delta) {
        scrollOffset += delta;
        clampScroll();
    }

    // ==================== 鼠标事件 ====================
    /**
     * 处理鼠标滚轮事件。
     *
     * @return 若事件在列表区域内并被消费则返回 true
     */
    boolean handleMouseInput() {
        int dwheel = Mouse.getEventDWheel();
        if (dwheel == 0) {
            return false;
        }
        int x = Mouse.getEventX() * host.width / host.client().displayWidth;
        int y = host.height - Mouse.getEventY() * host.height / host.client().displayHeight - 1;
        if (x >= listLeft && x <= listRight && y >= listTop && y <= listBottom) {
            scrollBy(-Integer.signum(dwheel));
            return true;
        }
        return false;
    }

    /**
     * 处理鼠标点击事件。
     *
     * @return 若事件在列表区域内并被消费则返回 true
     */
    boolean mouseClicked(int mouseX, int mouseY, int button) {
        if (mouseX < listLeft || mouseX > listRight || mouseY < listTop || mouseY > listBottom) {
            return false;
        }
        // 滚动条区域
        int trackX = listRight - scrollbarWidth - scrollbarMarginRight;
        if (mouseX >= trackX && mouseX <= trackX + scrollbarWidth) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }
        // 条目区域：仅当点击右侧清除按钮区时发送移除包
        int row = (mouseY - listTop) / slotHeight + scrollOffset;
        if (row >= 0 && row < entries.get().length && mouseX >= listRight - 62) {
            removeCallback.accept(row);
            return true;
        }
        // 消费列表区内的其他点击，避免穿透到底层控件
        return true;
    }

    /** 处理鼠标拖拽（用于滚动条拖拽）。 */
    void mouseClickMove(int mouseX, int mouseY, int button) {
        if (draggingScrollbar) {
            updateScrollFromMouse(mouseY);
        }
    }

    /** 处理鼠标释放（结束滚动条拖拽）。 */
    void mouseReleased(int mouseX, int mouseY, int button) {
        draggingScrollbar = false;
    }

    /** 根据鼠标 Y 坐标更新 scrollOffset（滚动条拖拽用）。 */
    private void updateScrollFromMouse(int mouseY) {
        Object[] monitoredEntries = entries.get();
        int maxScroll = getMaxScroll();
        if (maxScroll <= 0) {
            scrollOffset = 0;
            return;
        }
        int totalRows = Math.max(visibleRows(), monitoredEntries.length);
        int thumbH = Math.max(10, listHeight * visibleRows() / totalRows);
        int available = listHeight - thumbH;
        int relY = mouseY - listTop - thumbH / 2;
        if (relY < 0) {
            relY = 0;
        }
        if (relY > available) {
            relY = available;
        }
        scrollOffset = relY * maxScroll / available;
        clampScroll();
    }

    // ==================== 格式化三件套（随迁自宿主，O2-A07 落地后改引 ClientChartFormat） ====================
    /** 格式化 AE 监控存量（根据显示模式切换常规/科学/千位计数） */
    private static String formatAEMonitorAmount(long amount, int displayMode) {
        BigInteger value = BigInteger.valueOf(amount);
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientific(value);
            case 2:
                return FormatUtil.formatMetric(value, 2);
            case 0:
            default:
                return FormatUtil.formatNormal(value);
        }
    }

    /** 格式化 AE 监控变化速率（根据显示模式切换常规/科学/千位计数） */
    private static String formatAEMonitorRate(double rate, int displayMode) {
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientificDouble(rate);
            case 2:
                return FormatUtil.formatMetricDouble(rate, 2);
            case 0:
            default:
                return FormatUtil.formatNormalDouble(rate);
        }
    }

    /** 根据变化速率返回颜色：正绿、负红、零灰（契约 §5 色值映射 STATE_ONLINE/STATE_OFFLINE/TEXT_MUTED） */
    private static int rateColor(double rate) {
        if (rate > 0.0D) {
            return GtswnGuiPalette.STATE_ONLINE;
        }
        if (rate < 0.0D) {
            return GtswnGuiPalette.STATE_OFFLINE;
        }
        return GtswnGuiPalette.TEXT_MUTED;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
