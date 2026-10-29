package com.miaokatze.gtswn.client.gui;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache.Snapshot;
import com.miaokatze.gtswn.client.DeviceTerminalNames;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Native MUI2 scrolling viewport. Row actions share the framework's clip and hit transform. */
class GuiDeviceEntryList extends ListWidget<IWidget, GuiDeviceEntryList> {

    // ==================== 列几何常量（宿主列头绘制与命中共用，相对 listLeft） ====================

    /** 机器名列 X 偏移 */
    static final int COL_NAME_X = 4;

    /** 状态列 X 偏移 */
    static final int COL_STATE_X = 112;

    /** 瞬时列 X 偏移 */
    static final int COL_INST_X = 162;

    /** 平均列 X 偏移 */
    static final int COL_AVG_X = 228;

    /** 位置列 X 偏移 */
    static final int COL_POS_X = 304;

    /** 传送按钮 X 偏移（列表右缘内、滚动条左侧） */
    static final int TP_BTN_X = 400;

    /** 传送按钮宽度 */
    static final int TP_BTN_W = 24;

    /** 机器名列最大文本宽度（超出截断+省略号） */
    static final int NAME_WIDTH = 104;

    /** 位置列最大文本宽度（v1.7.2 面板加宽 +20px） */
    static final int POS_WIDTH = 92;

    /** 列头命中区右边界（相对 listLeft；传送列头不可排序；v1.7.2 加宽 +20px） */
    static final int HEADER_MAX_X = 400;

    /** 配方单条覆盖可用宽（COL_INST_X→COL_POS_X，v1.7.2 显示配方单条行） */
    static final int RECIPE_LINE_WIDTH = COL_POS_X - COL_INST_X;

    private final GuiDeviceInfoTerminal host;
    private static final int slotHeight = 20;
    private List<Entry> displayed = Collections.emptyList();

    GuiDeviceEntryList(GuiDeviceInfoTerminal host) {
        this.host = host;
        name("device.entries");
        pos(8, 52).size(434, 180);
        crossAxisAlignment(Alignment.CrossAxis.START);
        background(
            (context, x, y, width, height, theme) -> GtswnGuiDrawing
                .drawNineSlice(GtswnGuiTextures.LIST_PANEL, 4, (int) x, (int) y, (int) width, (int) height, 0));
    }

    /** Refresh only when the host publishes a new sorted snapshot; preserve the native scroll position. */
    void clampScroll() {
        List<Entry> entries = host.sortedEntries();
        if (displayed == entries) return;
        displayed = entries;
        removeAll();
        int index = 0;
        for (Entry entry : entries) {
            final String rowName = "device.row." + index++;
            DeviceRow row = new DeviceRow(entry).name(rowName)
                .size(426, slotHeight)
                .background(IDrawable.EMPTY)
                .hoverBackground(IDrawable.EMPTY)
                .onMousePressed(button -> rowAction(entry, button, false, false));
            ParentWidget<?> controls = new ParentWidget<>().size(426, slotHeight)
                .background(IDrawable.EMPTY);
            controls.child(
                new ButtonWidget<>().name(rowName + ".name")
                    .pos(COL_NAME_X, 0)
                    .size(NAME_WIDTH, slotHeight)
                    .background(IDrawable.EMPTY)
                    .hoverBackground(IDrawable.EMPTY)
                    .tooltipBuilder(tooltip -> {
                        tooltip.showUpTimer(0);
                        for (String line : host.entryTooltip(entry)) tooltip.addLine(line);
                    })
                    .tooltipAutoUpdate(true)
                    .onMousePressed(button -> rowAction(entry, button, true, false)));
            controls.child(
                new ButtonWidget<>().name(rowName + ".teleport")
                    .pos(TP_BTN_X, 3)
                    .size(TP_BTN_W, slotHeight - 6)
                    .background(IDrawable.EMPTY)
                    .hoverBackground(IDrawable.EMPTY)
                    .tooltipBuilder(tooltip -> {
                        tooltip.showUpTimer(0);
                        tooltip.addLine(
                            StatCollector.translateToLocalFormatted(
                                "gtswn.device.gui.tooltip.teleport_cost",
                                host.teleportCost()));
                    })
                    .tooltipAutoUpdate(true)
                    .onMousePressed(button -> rowAction(entry, button, false, true)));
            row.child(controls);
            child(row);
        }
    }

    @Override
    public boolean layoutWidgets() {
        if (!hasChildren()) getScrollData().setScrollSize(0);
        boolean complete = super.layoutWidgets();
        if (complete) getScrollData().clamp(getScrollArea());
        return complete;
    }

    private final class DeviceRow extends ButtonWidget<DeviceRow> {

        private final Entry entry;
        private final Snapshot snapshot;
        private final List<Entry> rows;

        DeviceRow(Entry entry) {
            this.entry = entry;
            this.snapshot = host.displayedSnapshot();
            this.rows = host.sortedEntries();
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            if (rows != host.sortedEntries() || !host.displayCurrent(snapshot)) return;
            GtswnMuiWidgets.drawLegacy(() -> drawSlot(entry, 0, 0, isHovering()));
        }
    }

    private boolean rowAction(Entry entry, int button, boolean name, boolean teleport) {
        if (!host.isActive() || !host.isCurrentEntry(entry)) return true;
        if (name && button == 1) {
            host.openNameEditor(entry, 0, 0);
            return true;
        }
        if (button != 0) return true;
        if (name && Interactable.hasAltDown()) host.openNameEditor(entry, 0, 0);
        else if (Interactable.hasControlDown()) host.sendUnbind(entry.key);
        else if (Interactable.hasShiftDown()) host.cycleRecognition(entry.key);
        else if (teleport) host.sendTeleport(entry.key);
        else host.selectDesktopEntry(entry);
        return true;
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        host.requestPendingPages();
        return super.onMouseScroll(direction, amount);
    }

    private void drawSlot(Entry entry, int x, int y, boolean inRow) {
        FontRenderer font = host.font();
        int textY = y + 6;

        // 行 hover 高亮（贴图化新增视觉项，契约 §3 #13 / §6 ④）：鼠标在行矩形内先画 row_hover 底再画内容；
        // 仅视觉，不改上方 inRow/inNameCol 命中与 tooltip 悬浮判定
        if (inRow || host.desktopEntrySelected(entry)) {
            GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.ROW_HOVER, 4, 0, y, 426, slotHeight, host.guiZLevel());
        }

        // 机器名列：超宽截断+省略号；v1.7.2 全行文字加粗 §l（applyBold 与颜色码共存）
        String machineName = DeviceTerminalNames.localName(entry);
        if (!DeviceTerminalNames.hasOverride(entry)) machineName = applyBold(machineName);
        font.drawString(
            ellipsis(font, machineName, NAME_WIDTH - (entry.linkNodeType == 0 ? 0 : 11)),
            x + COL_NAME_X,
            textY,
            GtswnGuiPalette.TEXT_BODY);
        if (entry.linkNodeType != 0) drawLinkIcon(x + COL_NAME_X + NAME_WIDTH - 9, textY, entry.linkNodeType);

        // 状态列：包含忽略状态；lang 值自带颜色码。
        font.drawString(
            ellipsis(font, applyBold(host.stateText(entry.state)), COL_INST_X - COL_STATE_X - 3),
            x + COL_STATE_X,
            textY,
            GtswnGuiPalette.TEXT_BODY);

        // 瞬时 / 平均区（v1.7.2 配方改单条覆盖）：显示配方开启且有配方 → 只画一条，
        // 起点 COL_INST_X、可用宽 RECIPE_LINE_WIDTH（灰字，ellipsis 截断）；
        // 关闭时两列恢复数值显示（排序始终按 Entry 原始数值，与显示无关）
        String recipeIn = entry.recipeIn == null ? "" : entry.recipeIn.trim();
        String recipeOut = entry.recipeOut == null ? "" : entry.recipeOut.trim();
        boolean recipeOverride = entry.state != 3 && host.showRecipeEnabled()
            && (!recipeIn.isEmpty() || !recipeOut.isEmpty());
        if (recipeOverride) {
            String line = recipeLine(recipeIn, recipeOut, 2);
            font.drawString(
                ellipsis(font, applyBold(line), RECIPE_LINE_WIDTH - 3),
                x + COL_INST_X,
                textY,
                GtswnGuiPalette.TEXT_MUTED);
        } else {
            font.drawString(
                ellipsis(font, applyBold(host.formatInstant(entry)), COL_AVG_X - COL_INST_X - 3),
                x + COL_INST_X,
                textY,
                amountColor(entry, GuiDeviceInfoTerminal.instantValue(entry)));
            font.drawString(
                ellipsis(font, applyBold(host.formatAverage(entry)), COL_POS_X - COL_AVG_X - 3),
                x + COL_AVG_X,
                textY,
                amountColor(entry, GuiDeviceInfoTerminal.averageValue(entry)));
        }

        // 位置列：dim(x,y,z)
        String posText = entry.dim + "(" + entry.x + "," + entry.y + "," + entry.z + ")";
        font.drawString(
            ellipsis(font, applyBold(posText), Math.min(POS_WIDTH, TP_BTN_X - COL_POS_X - 3)),
            x + COL_POS_X,
            textY,
            GtswnGuiPalette.TEXT_BODY);

        // 传送按钮（视觉与原生行内 ButtonWidget 共用几何）：✦N，N=传送经验消耗；配置 0=免费时只画 ✦ 图标不显示数字；
        // 客户端经验等级足够=绿字，不足=红字（服务端动作队列仍会权威复查）
        int btnX = x + TP_BTN_X;
        int btnY = y + 3;
        int btnH = slotHeight - 6;
        GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.CHIP_NORMAL, 4, btnX, btnY, TP_BTN_W, btnH, host.guiZLevel());
        int tpCost = host.teleportCost();
        String tpText = tpCost > 0 ? "\u2726" + tpCost : "\u2726";
        if (tpCost >= 1000) {
            tpText = "\u2726"
                + com.miaokatze.gtswn.common.util.FormatUtil.formatMetric(java.math.BigInteger.valueOf(tpCost), 0);
        }
        if (font.getStringWidth(applyBold(tpText)) > TP_BTN_W - 2) tpText = "\u2726";
        int tpW = font.getStringWidth(applyBold(tpText));
        int tpColor = host.clientPlayerLevel() >= tpCost ? GtswnGuiPalette.STATE_ONLINE : GtswnGuiPalette.STATE_OFFLINE;
        font.drawString(applyBold(tpText), btnX + (TP_BTN_W - tpW) / 2, btnY + 3, tpColor);
    }

    private void drawLinkIcon(int x, int y, byte flags) {
        int input = (flags & 1) != 0 ? 0xFF55D1ED : 0xFF56616A;
        int output = (flags & 2) != 0 ? 0xFFF0B04C : 0xFF56616A;
        host.fillRect(x, y + 1, x + 3, y + 5, input);
        host.fillRect(x + 5, y + 3, x + 8, y + 7, output);
        host.fillRect(x + 2, y + 3, x + 6, y + 5, 0xFFCCD3D8);
    }

    private static int amountColor(Entry entry, double value) {
        if (entry.state == 3) return GtswnGuiPalette.TEXT_MUTED;
        return eutColor(value);
    }

    private static int eutColor(double value) {
        if (value > 0D) return GtswnGuiPalette.STATE_ONLINE;
        if (value < 0D) return GtswnGuiPalette.STATE_IDLE;
        return GtswnGuiPalette.TEXT_BODY;
    }

    // ==================== 文本工具 ====================

    /** Fits the actual formatted text and suffix, retaining complete formatting pairs and Unicode code points. */
    static String ellipsis(FontRenderer font, String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) return "";
        if (font.getStringWidth(text) <= width) return text;
        String suffix = "...";
        int leadingEnd = 0;
        while (leadingEnd + 1 < text.length() && text.charAt(leadingEnd) == '§') leadingEnd += 2;
        String initial = text.substring(0, leadingEnd) + suffix;
        String best = font.getStringWidth(initial) <= width ? initial : "";
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < text.length();) {
            int next;
            if (text.charAt(index) == '§') {
                if (index + 1 == text.length()) break;
                next = index + 2;
            } else {
                next = text.offsetByCodePoints(index, 1);
            }
            prefix.append(text, index, next);
            String visible = prefix.toString();
            if (font.getStringWidth(visible) > width) break;
            String candidate = visible + suffix;
            if (font.getStringWidth(candidate) <= width) best = candidate;
            index = next;
        }
        return best;
    }

    /**
     * 全文字加粗 §l（v1.7.2 列表全加粗）：开头拼 {@code §l}，且每个颜色码（§0-§f 会重置
     * 加粗状态）之后重拼一次，保证与既有 § 颜色码共存时加粗贯穿整串；格式码（§k-§o）
     * 不重置状态无需重拼。
     */
    static String applyBold(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder("§l");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == '§' && i + 1 < text.length()) {
                char code = text.charAt(++i);
                sb.append(code);
                if ("0123456789abcdef".indexOf(Character.toLowerCase(code)) >= 0) {
                    sb.append('§')
                        .append('l');
                }
            }
        }
        return sb.toString();
    }

    /**
     * 配方行内预览（v1.7.2 输入+输出）：输入侧前 N 项 + " → " + 输出侧前 N 项；
     * 某侧为空时只显示另一侧（输入侧因 GT5U 无公开 lastRecipe 入口暂为空串）。
     */
    static String recipeLine(String recipeIn, String recipeOut, int maxPerSide) {
        String inPart = sidePreview(recipeIn, maxPerSide);
        String outPart = sidePreview(recipeOut, maxPerSide);
        if (inPart.isEmpty()) {
            return outPart;
        }
        if (outPart.isEmpty()) {
            return inPart;
        }
        return inPart + " → " + outPart;
    }

    /** 单侧预览："A|B|C" 取前 max 项（"|" 连接保持序列化格式），超 max 项补 " 等..."（lang 键）。 */
    static String sidePreview(String side, int max) {
        if (side == null || side.isEmpty()) {
            return "";
        }
        String[] parts = side.split("\\|");
        int n = Math.min(parts.length, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(parts[i].trim());
        }
        if (parts.length > max) {
            sb.append(' ')
                .append(GuiDeviceInfoTerminal.trStatic("gtswn.device.gui.more_suffix"));
        }
        return sb.toString();
    }
}
