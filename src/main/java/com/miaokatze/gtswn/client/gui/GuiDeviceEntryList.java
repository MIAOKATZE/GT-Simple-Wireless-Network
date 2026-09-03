package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * 设备信息终端滚动列表（实施计划 E2：复制改造 {@link GuiAEMonitorList} 的
 * 滚动偏移 / 滚动条拖拽 / 鼠标滚轮 / 行命中骨架，不继承 {@code GuiSlot}）。
 * <p>
 * 每行六列：机器名（超宽截断+省略号）、状态色字（§ 键本地化，0 待机金 / 1 运行绿 / 2 停机红）、
 * 瞬时 EU/t、平均 EU/t（v1.7.2 宿主「显示配方」开启时该两列区临时替换为单条配方行——
 * 输入侧前2项 " → " 输出侧前2项，灰字 ellipsis，排序仍按数值——排序在宿主侧基于 Entry
 * 原始字段完成）、位置 {@code dim(x,y,z)}、行右侧传送小按钮
 * {@code ✦N}（N={@code Config.deviceTeleportXPCost}，客户端经验等级不足时红字）。
 * v1.7.2 全行文字加粗 §l（{@code applyBold} 与既有 § 颜色码共存，ellipsis 去码截断防花屏）。
 * <p>
 * 交互（UI 只发包不直改服务端权威数据）：点击传送按钮 → 宿主 sendTeleport（包 10 action 3）；
 * Ctrl+点击行任意处 → 宿主 sendUnbind（包 10 action 2，无确认）；其余列表区点击一律消费防穿透。
 * <p>
 * 悬浮（v1.7.2 Tooltip 收敛到机器名列）：鼠标停在行上<b>且 X 在机器名列区间内</b>才计时，
 * 换行或移出机器名列即重置时间戳（{@link #hoverIndex} / {@link #hoverStartMillis}），
 * 宿主 drawScreen 末尾按 ≥0.5s 询问 {@link #hoverElapsedMillis()} 画配方 tooltip。
 * <p>
 * 宿主依赖与 {@code GuiAEMonitorList} 相同：GuiScreen 的 {@code mc}/{@code fontRendererObj}
 * 跨包 protected 不可直引，经宿主包私有访问器（{@code client()} / {@code font()} / {@code fillRect()}）
 * 转发；数据与动作经宿主包私有方法活取（条目数组每次 draw 活读，宿主排序后本列表即时可见）。
 */
class GuiDeviceEntryList {

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

    // ==================== 几何（构造快照，仿 GuiAEMonitorList） ====================

    /** 宿主 GUI（字体/Minecraft 访问、数据活取、动作回调、矩形填充转发） */
    private final GuiDeviceInfoTerminal host;

    /** 列表左边界（面板内部左侧留 8px） */
    private final int listLeft;

    /** 列表上边界（列头行下方，top+52） */
    private final int listTop;

    /** 列表右边界（面板内部右侧留 8px） */
    private final int listRight;

    /** 列表内容宽度（450 - 16 = 434，v1.7.2 面板加宽 +20px） */
    private final int listWidth;

    /** 列表可视高度（top+52 到 top+232，9 行 × 20） */
    private final int listHeight;

    /** 列表下边界 */
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

    // ==================== 悬浮计时（配方 tooltip 用） ====================

    /** 当前悬浮行下标（-1=无；换行即重置时间戳） */
    private int hoverIndex = -1;

    /** 进入当前悬浮行的墙钟时间戳（毫秒） */
    private long hoverStartMillis = 0L;

    GuiDeviceEntryList(GuiDeviceInfoTerminal host, int left, int top) {
        this.host = host;
        this.listLeft = left + 8;
        this.listTop = top + 52;
        this.listRight = left + 450 - 8;
        this.listWidth = 434;
        this.listHeight = top + 232 - this.listTop;
        this.listBottom = this.listTop + this.listHeight;
    }

    // ==================== 外部绘制入口 ====================

    /**
     * 绘制整个列表：背景、可见行、滚动条；并维护悬浮行计时。
     *
     * @param mouseX       鼠标 X（屏幕坐标）
     * @param mouseY       鼠标 Y（屏幕坐标）
     * @param partialTicks 部分刻
     */
    void draw(int mouseX, int mouseY, float partialTicks) {
        List<Entry> entries = host.sortedEntries();
        clampScroll();
        // 鼠标离开列表区即清除悬浮（时间戳随 hoverIndex=-1 一并作废）
        if (mouseX < listLeft || mouseX > listRight || mouseY < listTop || mouseY > listBottom) {
            hoverIndex = -1;
        }
        drawListBackground();
        enableListScissor();
        int firstRow = scrollOffset;
        // 多渲染一行以覆盖可能部分显示的最底行
        int lastRow = Math.min(entries.size(), firstRow + visibleRows() + 1);
        for (int i = firstRow; i < lastRow; i++) {
            int y = listTop + (i - firstRow) * slotHeight;
            drawSlot(i, listLeft, y, mouseX, mouseY);
        }
        disableListScissor();
        drawScrollbar();
    }

    // ==================== 条目绘制 ====================

    /**
     * 绘制单行内容：机器名、状态色字、瞬时/平均 EU/t（或配方覆盖文本）、位置、传送按钮。
     *
     * @param index  条目索引（排序后列表下标）
     * @param x      行左上角 X
     * @param y      行左上角 Y
     * @param mouseX 鼠标 X（悬浮判定）
     * @param mouseY 鼠标 Y（悬浮判定）
     */
    private void drawSlot(int index, int x, int y, int mouseX, int mouseY) {
        List<Entry> entries = host.sortedEntries();
        if (index < 0 || index >= entries.size()) {
            return;
        }
        Entry entry = entries.get(index);
        FontRenderer font = host.font();
        int textY = y + 6;

        // 悬浮计时（v1.7.2 Tooltip 收敛到机器名列）：命中本行且 X 在机器名列区间内才计时，
        // 换行重置时间戳；行内移出机器名列立即作废计时（不出 tooltip）
        boolean inRow = mouseX >= listLeft && mouseX <= listRight && mouseY >= y && mouseY < y + slotHeight;
        boolean inNameCol = mouseX >= listLeft + COL_NAME_X && mouseX < listLeft + COL_NAME_X + NAME_WIDTH;
        if (inRow && inNameCol) {
            if (hoverIndex != index) {
                hoverIndex = index;
                hoverStartMillis = System.currentTimeMillis();
            }
        } else if (hoverIndex == index) {
            hoverIndex = -1;
        }

        // 行 hover 高亮（贴图化新增视觉项，契约 §3 #13 / §6 ④）：鼠标在行矩形内先画 row_hover 底再画内容；
        // 仅视觉，不改上方 inRow/inNameCol 命中与 tooltip 计时判定
        if (inRow) {
            GtswnGuiDrawing
                .drawNineSlice(GtswnGuiTextures.ROW_HOVER, 4, listLeft, y, listWidth, slotHeight, host.guiZLevel());
        }

        // 机器名列：超宽截断+省略号；v1.7.2 全行文字加粗 §l（applyBold 与颜色码共存）
        font.drawString(
            applyBold(ellipsis(font, entry.name, NAME_WIDTH)),
            x + COL_NAME_X,
            textY,
            GtswnGuiPalette.TEXT_BODY);

        // 状态列：三态色字（lang 键自带 §6/§a/§c 颜色码，覆盖默认色参数）
        font.drawString(applyBold(host.stateText(entry.state)), x + COL_STATE_X, textY, GtswnGuiPalette.TEXT_BODY);

        // 瞬时 / 平均区（v1.7.2 配方改单条覆盖）：显示配方开启且有配方 → 只画一条，
        // 起点 COL_INST_X、可用宽 RECIPE_LINE_WIDTH（灰字，ellipsis 截断）；
        // 关闭时两列恢复数值显示（排序始终按 Entry 原始数值，与显示无关）
        String recipeIn = entry.recipeIn == null ? "" : entry.recipeIn.trim();
        String recipeOut = entry.recipeOut == null ? "" : entry.recipeOut.trim();
        boolean recipeOverride = host.showRecipeEnabled() && (!recipeIn.isEmpty() || !recipeOut.isEmpty());
        if (recipeOverride) {
            String line = recipeLine(recipeIn, recipeOut, 2);
            font.drawString(
                applyBold(ellipsis(font, line, RECIPE_LINE_WIDTH)),
                x + COL_INST_X,
                textY,
                GtswnGuiPalette.TEXT_MUTED);
        } else {
            font.drawString(
                applyBold(host.formatEUt(entry.inst)),
                x + COL_INST_X,
                textY,
                eutColor(entry.powerType, entry.inst));
            font.drawString(
                applyBold(host.formatAvg(entry.avg)),
                x + COL_AVG_X,
                textY,
                eutColor(entry.powerType, entry.avg));
        }

        // 位置列：dim(x,y,z)
        String posText = entry.dim + "(" + entry.x + "," + entry.y + "," + entry.z + ")";
        font.drawString(applyBold(ellipsis(font, posText, POS_WIDTH)), x + COL_POS_X, textY, GtswnGuiPalette.TEXT_BODY);

        // 传送按钮（仅视觉，点击由 mouseClicked 处理）：✦N，N=传送经验消耗；
        // 客户端经验等级足够=绿字，不足=红字（服务端动作队列仍会权威复查）
        int btnX = x + TP_BTN_X;
        int btnY = y + 3;
        int btnH = slotHeight - 6;
        GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.CHIP_NORMAL, 4, btnX, btnY, TP_BTN_W, btnH, host.guiZLevel());
        String tpText = "\u2726" + host.teleportCost();
        int tpW = font.getStringWidth(tpText);
        int tpColor = host.clientPlayerLevel() >= host.teleportCost() ? GtswnGuiPalette.STATE_ONLINE
            : GtswnGuiPalette.STATE_OFFLINE;
        font.drawString(applyBold(tpText), btnX + (TP_BTN_W - tpW) / 2, btnY + 3, tpColor);
    }

    /**
     * EU/t 列着色（v1.7.11）：非零按功率分类着色（发电绿 / 耗电橙），零值回退中性色；
     * 瞬时 / 平均两列各自独立判零，排序仍按 Entry 原始数值不受影响。
     */
    private static int eutColor(byte powerType, double value) {
        if (value == 0D) {
            return GtswnGuiPalette.TEXT_BODY;
        }
        return powerType == DeviceTerminalDataStore.POWER_TYPE_GENERATE ? GtswnGuiPalette.STATE_ONLINE
            : GtswnGuiPalette.STATE_IDLE;
    }

    // ==================== 悬浮查询（宿主 tooltip 用） ====================

    /** @return 当前悬浮行条目（无悬浮返回 null） */
    Entry hoveredEntry() {
        List<Entry> entries = host.sortedEntries();
        if (hoverIndex < 0 || hoverIndex >= entries.size()) {
            return null;
        }
        return entries.get(hoverIndex);
    }

    /** @return 当前行已悬浮毫秒数（无悬浮返回 0；宿主按 ≥500ms 门槛出 tooltip） */
    long hoverElapsedMillis() {
        return hoverIndex < 0 ? 0L : System.currentTimeMillis() - hoverStartMillis;
    }

    // ==================== 背景与裁剪（仿 GuiAEMonitorList） ====================

    /**
     * 绘制列表背景（覆盖面板背景分隔线，避免列表区出现不需要的线条）：
     * 消费 gtswn 贴图 list_panel（INSET 凹陷，9-slice 切片 4px，契约 plan/ui/texture-list.md §3 #15），
     * 区域几何零变化（listLeft/listTop/listWidth/listHeight 与原 fillRect 区域一致）。
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

    // ==================== 滚动条（仿 GuiAEMonitorList） ====================

    /** 绘制滚动条轨道与滑块。 */
    private void drawScrollbar() {
        int trackX = listRight - scrollbarWidth - scrollbarMarginRight;
        int maxScroll = getMaxScroll();
        // 轨道（贴图化：scrollbar_track 纵向 9-slice 切片 2px，宽 6 与区域几何不变）
        GtswnGuiDrawing.drawNineSlice(
            GtswnGuiTextures.SCROLLBAR_TRACK,
            2,
            trackX,
            listTop,
            scrollbarWidth,
            listHeight,
            host.guiZLevel());
        if (maxScroll > 0) {
            int totalRows = Math.max(
                visibleRows(),
                host.sortedEntries()
                    .size());
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

    // ==================== 滚动计算（仿 GuiAEMonitorList） ====================

    /** 返回最大可滚动行数（总条目 - 可见行数，至少为 0）。 */
    private int getMaxScroll() {
        return Math.max(
            0,
            host.sortedEntries()
                .size() - visibleRows());
    }

    /** 返回列表可视区域可容纳的完整行数。 */
    private int visibleRows() {
        return listHeight / slotHeight;
    }

    /** 将 scrollOffset 限制在合法范围内（宿主在条目数变化/排序后调用校正）。 */
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

    // ==================== 鼠标事件（仿 GuiAEMonitorList 骨架 + 设备终端动作） ====================

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
     * 处理鼠标点击事件：滚动条 → 拖拽；行内传送按钮 → 宿主发包传送（action 3）；
     * Ctrl+点击行 → 宿主发包解绑（action 2，无确认）；其余列表区点击消费防穿透。
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
        // 条目区域
        List<Entry> entries = host.sortedEntries();
        int row = (mouseY - listTop) / slotHeight + scrollOffset;
        if (row >= 0 && row < entries.size()) {
            if (mouseX >= listLeft + TP_BTN_X && mouseX <= listLeft + TP_BTN_X + TP_BTN_W) {
                // 传送按钮：发包 action 3（服务端 3s 冷却 + 经验复查扣减权威执行）
                hoverIndex = -1;
                host.sendTeleport(entries.get(row).key);
                return true;
            }
            if (GuiScreen.isCtrlKeyDown()) {
                // Ctrl+点击行任意处：远程解绑（action 2，无确认）
                hoverIndex = -1;
                host.sendUnbind(entries.get(row).key);
                return true;
            }
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
        int maxScroll = getMaxScroll();
        if (maxScroll <= 0) {
            scrollOffset = 0;
            return;
        }
        int totalRows = Math.max(
            visibleRows(),
            host.sortedEntries()
                .size());
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

    // ==================== 文本工具 ====================

    /**
     * 超宽截断+省略号（宽度内放不下时截到 width-6 并补 "..."）。
     * <p>
     * v1.7.2 §l 加粗与颜色码共存防花屏：测量与截断基于去格式码可见宽度
     * （vanilla {@code getStringWidth}/{@code trimStringToWidth} 均跳过 § 序列，
     * 不会把 § 与其码字符切断）；截断后 "..." 继承截断点激活的颜色/加粗状态。
     */
    static String ellipsis(FontRenderer font, String text, int width) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (font.getStringWidth(text) <= width) {
            return text;
        }
        return font.trimStringToWidth(text, Math.max(0, width - 6)) + "...";
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
