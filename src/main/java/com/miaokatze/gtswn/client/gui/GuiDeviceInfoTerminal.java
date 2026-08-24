package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache.Snapshot;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalAction;
import com.miaokatze.gtswn.network.PacketRequestDeviceTerminalData;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * 设备信息终端客户端 GUI（实施计划 E1/E3/E4：vanilla {@link GuiScreen} 自绘，450×252 六列布局，
 * v1.7.2 面板加宽 +20px）。
 * <p>
 * 数据流（UI 只发 action，不直改服务端权威数据）：
 * <ol>
 * <li>{@link #updateScreen()} 每 {@value #POLL_INTERVAL_TICKS} tick 经包 8 向服务端轮询
 * （pollTimer=0 首帧即发）；{@link #doesGuiPauseGame()} 返回 false 防单人暂停导致轮询僵死；</li>
 * <li>服务端包 9 分页回发 → ClientProxy 切主线程 → {@link DeviceTerminalClientCache}
 * （锚点=终端 UUID，GUI 关闭不清缓存，重开即显上次快照）；</li>
 * <li>绘制读 {@link #sortedEntries}：每次快照版本变化按当前排序列/方向重排（稳定排序，
 * 同键保持绑定序）；排序/计数法点击后本地立即生效并发包 10（action 0/1）持久化到物品 NBT。</li>
 * </ol>
 * <p>
 * 布局（自上而下）：标题 / 顶行两按钮（计数模式四态轮换 + 显示配方纯本地开关）/ 列头行
 * （点击排序，▲▼ 高亮当前列，平均列默认降序、其余默认升序）/ 滚动列表
 * （{@link GuiDeviceEntryList}，行高 20）/ 底行操作提示。
 * <p>
 * 锚点解析：GUI 持有打开时传入的终端 UUID，每 tick 只读重解析手持优先终端（与服务端
 * {@code DeviceTerminalRequestQueue.findTerminalStack} 同序；服务端右击空气时显式
 * S2FPacketSetSlot 同步 DIT_UUID 到客户端，见 ItemDeviceInfoTerminal.onItemRightClick）。
 * <p>
 * 初始 UI 偏好（排序列/方向/计数法）从打开时物品 stack NBT 读；action 后以本地状态为准
 * （服务端 NBT 写回由动作队列权威执行，下轮打开校正）。
 */
public class GuiDeviceInfoTerminal extends GuiScreen {

    /** 轮询间隔（tick）：每 20 tick（1 秒）发一次请求包 8 */
    private static final int POLL_INTERVAL_TICKS = 20;

    /** 悬浮出 tooltip 的停留门槛（毫秒） */
    private static final long HOVER_TOOLTIP_DELAY_MS = 500L;

    /** 悬浮配方文本换行宽度（像素，≈200px；v1.7.2 起用 listFormattedStringToWidth 按像素分行） */
    private static final int TOOLTIP_WRAP_WIDTH = 200;

    /** GUI 宽度（六列布局，v1.7.2 加宽 +20px） */
    private final int xSize = 450;

    /** GUI 高度（标题/按钮/列头/列表/底行合计） */
    private final int ySize = 252;

    /** 顶行按钮：计数法四态轮换（本地立即轮换 + 发包 10 action 1 持久化） */
    private static final int BTN_COUNT_MODE = 0;

    /** 顶行按钮：显示配方开关（纯 GUI 实例字段，不发包不持久化） */
    private static final int BTN_SHOW_RECIPE = 1;

    /** 列头行文本 Y 偏移（按钮行下方） */
    private static final int HEADER_Y = 40;

    /** 底行提示 Y 偏移（列表底界 top+232 下方） */
    private static final int FOOTER_Y = 238;

    /** 列头 lang 键（下标 = 排序列 0-4 NAME/STATE/INST/AVG/POS，与包 10 COLUMN_* 一致） */
    private static final String[] COLUMN_KEYS = { "gtswn.device.gui.col.name", "gtswn.device.gui.col.state",
        "gtswn.device.gui.col.instant", "gtswn.device.gui.col.average", "gtswn.device.gui.col.pos" };

    /** 状态三态 lang 键（0 待机 / 1 运行 / 2 停机，值自带 § 颜色码） */
    private static final String[] STATE_KEYS = { "gtswn.device.gui.state.idle", "gtswn.device.gui.state.running",
        "gtswn.device.gui.state.stopped" };

    // ==================== 状态 ====================

    /** GUI 左上角屏幕坐标（initGui 计算） */
    private int guiLeft;
    private int guiTop;

    /** 轮询计时器（初值 0 → 打开后首个 updateScreen 立即发首包） */
    private int pollTimer = 0;

    /** 当前终端锚点 UUID（构造传入 + 每 tick 只读重解析；null=客户端 NBT 尚未同步到 UUID） */
    private UUID anchor;

    /** 当前快照数据版本（Long.MIN_VALUE=尚无任何快照；版本变化触发重排） */
    private long displayedVersion = Long.MIN_VALUE;

    /** 排序后条目（绘制与列表命中共用；无数据为空列表） */
    private List<Entry> sortedEntries = Collections.emptyList();

    /** 排序列（0-4，与 PacketDeviceTerminalAction.COLUMN_* 一致；初始从 stack NBT 读） */
    private int sortColumn;

    /** 排序方向（true=降序；初始从 stack NBT 读，默认 DESC） */
    private boolean sortDesc;

    /** 计数法（0 常规 / 1 科学 / 2 千位 / 3 电压等级；初始从 stack NBT 读） */
    private int countMode;

    /** 显示配方开关（纯 GUI 实例字段：开启时瞬时/平均两列区临时替换为单条配方行） */
    private boolean showRecipe = false;

    /** 排序偏好被点击后置位，强制下次 refreshEntries 重排（同版本快照也重排） */
    private boolean sortDirty = false;

    /** 滚动列表控件（initGui 创建） */
    private GuiDeviceEntryList entryList;

    /**
     * 构造：锚点 UUID + 初始 UI 偏好来源 stack（排序列/方向/计数法，只读）。
     *
     * @param terminalId 打开时解析的终端 UUID（可为 null——客户端 NBT 未同步 UUID 时先显示占位，
     *                   每 tick 重解析在槽位同步后自动锚定）
     * @param prefStack  偏好来源物品（可为 null，取默认值 AVG/DESC/NORMAL）
     */
    public GuiDeviceInfoTerminal(UUID terminalId, ItemStack prefStack) {
        this.anchor = terminalId;
        this.sortColumn = prefStack == null ? PacketDeviceTerminalAction.COLUMN_AVG
            : columnFromString(ItemDeviceInfoTerminal.getSortColumn(prefStack));
        this.sortDesc = prefStack == null
            || ItemDeviceInfoTerminal.DIR_DESC.equals(ItemDeviceInfoTerminal.getSortDirection(prefStack));
        this.countMode = prefStack == null ? PacketDeviceTerminalAction.MODE_NORMAL
            : modeFromString(ItemDeviceInfoTerminal.getCountMode(prefStack));
    }

    /**
     * 覆写默认行为，打开 GUI 时不暂停游戏 tick（同 GuiQuantumTerminal v1.6.7）：
     * 单人模式下暂停会导致服务端不 tick、包 8 请求永远得不到回包，GUI 卡在「...」。
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        this.guiLeft = (this.width - this.xSize) / 2;
        this.guiTop = (this.height - this.ySize) / 2;
        buttonList.clear();
        // 顶行两按钮（列头行上方）
        buttonList.add(new GuiButton(BTN_COUNT_MODE, this.guiLeft + 8, this.guiTop + 16, 130, 16, countModeText()));
        buttonList.add(new GuiButton(BTN_SHOW_RECIPE, this.guiLeft + 146, this.guiTop + 16, 110, 16, showRecipeText()));
        this.entryList = new GuiDeviceEntryList(this, this.guiLeft, this.guiTop);
        refreshAnchor();
        refreshEntries();
    }

    /**
     * 每 tick：重解析锚点（只读，手持优先，与服务端队列解析同序）→ 版本变化或偏好变化时重排 →
     * 每 {@value #POLL_INTERVAL_TICKS} tick 发包 8 轮询（首帧即发）。
     */
    @Override
    public void updateScreen() {
        refreshAnchor();
        refreshEntries();
        if (this.pollTimer++ % POLL_INTERVAL_TICKS == 0) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestDeviceTerminalData());
        }
    }

    // ==================== 锚点与数据刷新 ====================

    /** 只读重解析终端锚点 UUID（手持优先 → 主背包首台；与 DeviceTerminalRequestQueue 同序）。 */
    private void refreshAnchor() {
        UUID resolved = resolveTerminalId();
        if (resolved != null && !resolved.equals(this.anchor)) {
            this.anchor = resolved;
            this.displayedVersion = Long.MIN_VALUE;
        }
    }

    /** 客户端只读解析（不生成不写 NBT）：手持优先 → 主背包首台终端的 DIT_UUID。 */
    private UUID resolveTerminalId() {
        EntityPlayer player = this.mc.thePlayer;
        if (player == null) {
            return this.anchor;
        }
        ItemStack stack = player.getHeldItem();
        if (!(stack != null && stack.getItem() instanceof ItemDeviceInfoTerminal)) {
            stack = null;
            ItemStack[] inv = player.inventory.mainInventory;
            for (ItemStack candidate : inv) {
                if (candidate != null && candidate.getItem() instanceof ItemDeviceInfoTerminal) {
                    stack = candidate;
                    break;
                }
            }
        }
        return stack == null ? this.anchor : ItemDeviceInfoTerminal.readTerminalId(stack);
    }

    /** 快照版本变化或排序偏好变化时按当前列/方向重排（稳定排序，同键保持绑定序）。 */
    private void refreshEntries() {
        Snapshot snapshot = this.anchor == null ? null : DeviceTerminalClientCache.getSnapshot(this.anchor);
        if (snapshot == null) {
            if (this.displayedVersion != Long.MIN_VALUE) {
                this.displayedVersion = Long.MIN_VALUE;
                this.sortedEntries = Collections.emptyList();
                if (this.entryList != null) this.entryList.clampScroll();
            }
            return;
        }
        if (snapshot.version != this.displayedVersion || this.sortDirty) {
            this.displayedVersion = snapshot.version;
            this.sortDirty = false;
            List<Entry> copy = new ArrayList<>(snapshot.entries);
            Comparator<Entry> cmp = (a, b) -> compareEntries(a, b, this.sortColumn);
            if (this.sortDesc) {
                cmp = cmp.reversed();
            }
            copy.sort(cmp);
            this.sortedEntries = copy;
            if (this.entryList != null) this.entryList.clampScroll();
        }
    }

    /** 排序比较器：NAME 字符串 / STATE int / INST long / AVG double / POS dim,x,y,z 字典序。 */
    private static int compareEntries(Entry a, Entry b, int column) {
        switch (column) {
            case PacketDeviceTerminalAction.COLUMN_NAME:
                return a.name.compareToIgnoreCase(b.name);
            case PacketDeviceTerminalAction.COLUMN_STATE:
                return Integer.compare(a.state, b.state);
            case PacketDeviceTerminalAction.COLUMN_INST:
                return Long.compare(a.inst, b.inst);
            case PacketDeviceTerminalAction.COLUMN_AVG:
                return Double.compare(a.avg, b.avg);
            case PacketDeviceTerminalAction.COLUMN_POS:
                int c = Integer.compare(a.dim, b.dim);
                if (c != 0) return c;
                c = Integer.compare(a.x, b.x);
                if (c != 0) return c;
                c = Integer.compare(a.y, b.y);
                if (c != 0) return c;
                return Integer.compare(a.z, b.z);
            default:
                return 0;
        }
    }

    // ==================== 顶行按钮 ====================

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_COUNT_MODE) {
            // 本地立即轮换显示（下轮打开由服务端 NBT 校正）+ 发包 action 1 持久化（四态轮换）
            this.countMode = (this.countMode + 1) % 4;
            button.displayString = countModeText();
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketDeviceTerminalAction(
                    PacketDeviceTerminalAction.ACTION_COUNT_MODE,
                    (byte) 0,
                    (byte) 0,
                    (byte) this.countMode,
                    ""));
        } else if (button.id == BTN_SHOW_RECIPE) {
            // 纯 GUI 实例字段：不发包不持久化
            this.showRecipe = !this.showRecipe;
            button.displayString = showRecipeText();
        }
    }

    /** 计数法按钮文案（计数模式: 常规计数/科学计数/千位计数/电压等级） */
    private String countModeText() {
        String mode = tr(
            this.countMode == PacketDeviceTerminalAction.MODE_NORMAL ? "gtswn.device.gui.count_mode.normal"
                : this.countMode == PacketDeviceTerminalAction.MODE_SCIENTIFIC
                    ? "gtswn.device.gui.count_mode.scientific"
                    : this.countMode == PacketDeviceTerminalAction.MODE_THOUSANDS
                        ? "gtswn.device.gui.count_mode.thousands"
                        : "gtswn.device.gui.count_mode.voltage");
        return StatCollector.translateToLocalFormatted("gtswn.device.gui.count_mode", mode);
    }

    /** 显示配方按钮文案（显示配方: 开/关） */
    private String showRecipeText() {
        return StatCollector.translateToLocalFormatted(
            "gtswn.device.gui.show_recipe",
            tr(this.showRecipe ? "gtswn.device.gui.on" : "gtswn.device.gui.off"));
    }

    // ==================== 列头排序 ====================

    /** 列头命中：返回排序列 0-4（-1=未命中；传送列头不可排序）。 */
    private int headerColumnAt(int mouseX, int mouseY) {
        if (mouseY < this.guiTop + HEADER_Y - 2 || mouseY > this.guiTop + HEADER_Y + 12) {
            return -1;
        }
        int rel = mouseX - (this.guiLeft + 8);
        if (rel < GuiDeviceEntryList.COL_NAME_X || rel >= GuiDeviceEntryList.HEADER_MAX_X) {
            return -1;
        }
        if (rel < GuiDeviceEntryList.COL_STATE_X) return PacketDeviceTerminalAction.COLUMN_NAME;
        if (rel < GuiDeviceEntryList.COL_INST_X) return PacketDeviceTerminalAction.COLUMN_STATE;
        if (rel < GuiDeviceEntryList.COL_AVG_X) return PacketDeviceTerminalAction.COLUMN_INST;
        if (rel < GuiDeviceEntryList.COL_POS_X) return PacketDeviceTerminalAction.COLUMN_AVG;
        return PacketDeviceTerminalAction.COLUMN_POS;
    }

    /** 点击列头：同列再点反向；异列切默认方向（平均列降序、其余升序）→ 本地立即重排 + 发包 action 0。 */
    private void onColumnHeaderClicked(int column) {
        if (column == this.sortColumn) {
            this.sortDesc = !this.sortDesc;
        } else {
            this.sortColumn = column;
            this.sortDesc = column == PacketDeviceTerminalAction.COLUMN_AVG;
        }
        this.sortDirty = true;
        refreshEntries();
        GTSWNPacketHandler.NETWORK.sendToServer(
            new PacketDeviceTerminalAction(
                PacketDeviceTerminalAction.ACTION_SORT,
                (byte) this.sortColumn,
                (byte) (this.sortDesc ? PacketDeviceTerminalAction.DIR_DESC : PacketDeviceTerminalAction.DIR_ASC),
                (byte) 0,
                ""));
    }

    // ==================== 动作发包（列表回调；UI 只发 action） ====================

    /** 列表传送按钮回调：发包 10 action 3（服务端 3s 冷却 + 经验复查扣减权威执行）。 */
    void sendTeleport(String key) {
        GTSWNPacketHandler.NETWORK.sendToServer(
            new PacketDeviceTerminalAction(
                PacketDeviceTerminalAction.ACTION_TELEPORT,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                key));
    }

    /** 列表 Ctrl+点击回调：发包 10 action 2 远程解绑（无确认，服务端 removeBinding 权威执行）。 */
    void sendUnbind(String key) {
        GTSWNPacketHandler.NETWORK.sendToServer(
            new PacketDeviceTerminalAction(
                PacketDeviceTerminalAction.ACTION_UNBIND,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                key));
    }

    // ==================== 绘制 ====================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawPanelBackground();
        drawTitle();
        drawColumnHeaders();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawBody(mouseX, mouseY);
        drawFooter();
        drawHoverTooltip(mouseX, mouseY);
    }

    /** 自绘面板背景（仿 GuiQuantumTerminal.drawPanelBackground 配色：浅灰底 + 深蓝灰边框）。 */
    private void drawPanelBackground() {
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + this.ySize, 0xFFE8EAEC);
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + this.xSize, this.guiTop + 1, 0xFF607080);
        drawRect(
            this.guiLeft,
            this.guiTop + this.ySize - 1,
            this.guiLeft + this.xSize,
            this.guiTop + this.ySize,
            0xFF607080);
        drawRect(this.guiLeft, this.guiTop, this.guiLeft + 1, this.guiTop + this.ySize, 0xFF607080);
        drawRect(
            this.guiLeft + this.xSize - 1,
            this.guiTop,
            this.guiLeft + this.xSize,
            this.guiTop + this.ySize,
            0xFF607080);
        // 标题分隔线
        drawRect(this.guiLeft + 8, this.guiTop + 14, this.guiLeft + this.xSize - 8, this.guiTop + 15, 0xFFB8C0C8);
        // 列头分隔线
        drawRect(this.guiLeft + 8, this.guiTop + 50, this.guiLeft + this.xSize - 8, this.guiTop + 51, 0xFFB8C0C8);
    }

    /** 标题（居中） */
    private void drawTitle() {
        String title = tr("gtswn.device.gui.title");
        this.fontRendererObj.drawString(
            title,
            this.guiLeft + (this.xSize - this.fontRendererObj.getStringWidth(title)) / 2,
            this.guiTop + 4,
            0x404040);
    }

    /**
     * 列头行：五列可排序标签 + 当前排序列 ▲/▼ 高亮（点击排序，见 headerColumnAt）+ 传送列标签（不可排序）。
     * <p>
     * v1.7.2 列头全加粗 §l（与列表行加粗一致）。
     */
    private void drawColumnHeaders() {
        int[] colX = { GuiDeviceEntryList.COL_NAME_X, GuiDeviceEntryList.COL_STATE_X, GuiDeviceEntryList.COL_INST_X,
            GuiDeviceEntryList.COL_AVG_X, GuiDeviceEntryList.COL_POS_X };
        for (int i = 0; i < COLUMN_KEYS.length; i++) {
            String label = tr(COLUMN_KEYS[i]) + (i == this.sortColumn ? (this.sortDesc ? " \u25BC" : " \u25B2") : "");
            this.fontRendererObj.drawString(
                "§l" + label,
                this.guiLeft + 8 + colX[i],
                this.guiTop + HEADER_Y,
                i == this.sortColumn ? 0x1F4E79 : 0x2F3640);
        }
        // 第六列：传送列头（行内 ✦ 按钮列；不可排序，仅标签）
        this.fontRendererObj.drawString(
            "§l" + tr("gtswn.device.gui.teleport"),
            this.guiLeft + 8 + GuiDeviceEntryList.TP_BTN_X - 6,
            this.guiTop + HEADER_Y,
            0x2F3640);
    }

    /** 列表主体：无快照「...」占位 / 无绑定提示 / 正常列表。 */
    private void drawBody(int mouseX, int mouseY) {
        Snapshot snapshot = this.anchor == null ? null : DeviceTerminalClientCache.getSnapshot(this.anchor);
        if (snapshot == null) {
            // 首个回包未到达（或锚点未同步）：中央灰字等待占位
            drawCenteredListText("...");
            return;
        }
        if (snapshot.entries.isEmpty()) {
            // 快照已到齐但无绑定条目
            drawCenteredListText(tr("gtswn.device.gui.no_bindings"));
            return;
        }
        this.entryList.draw(mouseX, mouseY, 0);
    }

    /** 列表区中央灰字提示（占位 / 无绑定共用）。 */
    private void drawCenteredListText(String text) {
        int x = this.guiLeft + 8 + (434 - this.fontRendererObj.getStringWidth(text)) / 2;
        int y = this.guiTop + 52 + 180 / 2 - 4;
        this.fontRendererObj.drawString(text, x, y, 0x6B7680);
    }

    /** 底行固定操作提示。 */
    private void drawFooter() {
        this.fontRendererObj
            .drawString(tr("gtswn.device.gui.footer"), this.guiLeft + 8, this.guiTop + FOOTER_Y, 0x6B7680);
    }

    /**
     * 行机器名列悬浮 ≥0.5s：显示当前执行配方（v1.7.2 双侧：标题 +「输入:」行 +「输出:」行，
     * 单侧最多 4 项超限补「 等...」，{@code listFormattedStringToWidth} 按 ≈200px 像素分行，
     * 替代旧 48 字符硬切）。
     */
    private void drawHoverTooltip(int mouseX, int mouseY) {
        if (this.entryList == null || this.entryList.hoverElapsedMillis() < HOVER_TOOLTIP_DELAY_MS) {
            return;
        }
        Entry hovered = this.entryList.hoveredEntry();
        if (hovered == null) {
            return;
        }
        String recipeIn = hovered.recipeIn == null ? "" : hovered.recipeIn.trim();
        String recipeOut = hovered.recipeOut == null ? "" : hovered.recipeOut.trim();
        List<String> lines = new ArrayList<>();
        lines.add(tr("gtswn.device.gui.tooltip.recipe_title"));
        if (recipeIn.isEmpty() && recipeOut.isEmpty()) {
            lines.add(tr("gtswn.device.gui.tooltip.recipe_none"));
        } else {
            // 某侧为空不画该行（输入侧因 GT5U 无公开 lastRecipe 入路暂为空串）
            if (!recipeIn.isEmpty()) {
                lines.addAll(
                    this.fontRendererObj.listFormattedStringToWidth(
                        StatCollector.translateToLocalFormatted(
                            "gtswn.device.gui.tooltip.recipe_in",
                            GuiDeviceEntryList.sidePreview(recipeIn, 4)),
                        TOOLTIP_WRAP_WIDTH));
            }
            if (!recipeOut.isEmpty()) {
                lines.addAll(
                    this.fontRendererObj.listFormattedStringToWidth(
                        StatCollector.translateToLocalFormatted(
                            "gtswn.device.gui.tooltip.recipe_out",
                            GuiDeviceEntryList.sidePreview(recipeOut, 4)),
                        TOOLTIP_WRAP_WIDTH));
            }
        }
        drawHoveringText(lines, mouseX, mouseY, this.fontRendererObj);
    }

    // ==================== 鼠标事件（列头优先，列表次之） ====================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        int column = headerColumnAt(mouseX, mouseY);
        if (column >= 0) {
            onColumnHeaderClicked(column);
            return;
        }
        if (this.entryList != null) {
            this.entryList.mouseClicked(mouseX, mouseY, button);
        }
    }

    @Override
    public void handleMouseInput() {
        if (this.entryList != null && this.entryList.handleMouseInput()) {
            return;
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (this.entryList != null) {
            this.entryList.mouseClickMove(mouseX, mouseY, clickedMouseButton);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (this.entryList != null) {
            this.entryList.mouseReleased(mouseX, mouseY, which);
        }
    }

    // ==================== 列表注入（数据活取 / 格式化 / 状态文案） ====================

    /** @return 排序后条目（列表绘制与命中共用，宿主 refreshEntries 后活读即时可见） */
    List<Entry> sortedEntries() {
        return this.sortedEntries;
    }

    /** @return 显示配方开关（列表据此用单条配方行覆盖瞬时/平均两列区文本） */
    boolean showRecipeEnabled() {
        return this.showRecipe;
    }

    /** @param state 三态 0/1/2（越界回退待机文案；lang 值自带 § 颜色码） */
    String stateText(byte state) {
        int idx = state >= 0 && state < STATE_KEYS.length ? state : 0;
        return tr(STATE_KEYS[idx]);
    }

    /**
     * 瞬时 EU/t（long）按当前计数模式格式化：常规 FormatUtil / 科学 FormatUtil /
     * 千位（v1.7.2 起公制 K/M/G/T/P 2 位小数）FormatUtil.formatMetric / 电压等级 GTTierUtil。
     */
    String formatEUt(long value) {
        switch (this.countMode) {
            case PacketDeviceTerminalAction.MODE_SCIENTIFIC:
                return FormatUtil.formatScientific(BigInteger.valueOf(value));
            case PacketDeviceTerminalAction.MODE_THOUSANDS:
                return FormatUtil.formatMetric(BigInteger.valueOf(value), 2);
            case PacketDeviceTerminalAction.MODE_VOLTAGE:
                return GTTierUtil.formatGTPowerDecimal(value);
            case PacketDeviceTerminalAction.MODE_NORMAL:
            default:
                return FormatUtil.formatNormal(BigInteger.valueOf(value));
        }
    }

    /** 平均 EU/t（double，60 点均值）按当前计数模式格式化（double 变体）。 */
    String formatAvg(double value) {
        switch (this.countMode) {
            case PacketDeviceTerminalAction.MODE_SCIENTIFIC:
                return FormatUtil.formatScientificDouble(value);
            case PacketDeviceTerminalAction.MODE_THOUSANDS:
                return FormatUtil.formatMetricDouble(value, 2);
            case PacketDeviceTerminalAction.MODE_VOLTAGE:
                return GTTierUtil.formatGTPowerDecimal(value);
            case PacketDeviceTerminalAction.MODE_NORMAL:
            default:
                return FormatUtil.formatNormalDouble(value);
        }
    }

    /** @return 传送按钮经验消耗（Config.deviceTeleportXPCost；服务端动作队列仍权威复查） */
    int teleportCost() {
        return Config.deviceTeleportXPCost;
    }

    /** @return 客户端玩家当前经验等级（传送按钮红/绿显示用） */
    int clientPlayerLevel() {
        EntityPlayer player = this.mc.thePlayer;
        return player == null ? 0 : player.experienceLevel;
    }

    // ==================== 宿主访问器（GuiScreen protected 字段跨包转发，仿 GuiNetworkInfoPanel） ====================

    /** 字体渲染器访问（列表绘制用） */
    FontRenderer font() {
        return this.fontRendererObj;
    }

    /** Minecraft 实例访问（列表滚轮坐标换算与 scissor 用） */
    Minecraft client() {
        return this.mc;
    }

    /** 矩形填充转发（列表背景/滚动条/按钮绘制用） */
    void fillRect(int x1, int y1, int x2, int y2, int color) {
        drawRect(x1, y1, x2, y2, color);
    }

    // ==================== 偏好映射工具 ====================

    /** 物品 NBT 排序列字符串 → 包 10 列 byte（非法回退 AVG） */
    private static int columnFromString(String column) {
        if (ItemDeviceInfoTerminal.SORT_NAME.equals(column)) return PacketDeviceTerminalAction.COLUMN_NAME;
        if (ItemDeviceInfoTerminal.SORT_STATE.equals(column)) return PacketDeviceTerminalAction.COLUMN_STATE;
        if (ItemDeviceInfoTerminal.SORT_INST.equals(column)) return PacketDeviceTerminalAction.COLUMN_INST;
        if (ItemDeviceInfoTerminal.SORT_POS.equals(column)) return PacketDeviceTerminalAction.COLUMN_POS;
        return PacketDeviceTerminalAction.COLUMN_AVG;
    }

    /** 物品 NBT 计数法字符串 → 包 10 计数法 byte（非法回退 NORMAL） */
    private static int modeFromString(String mode) {
        if (ItemDeviceInfoTerminal.NUM_SCIENTIFIC.equals(mode)) return PacketDeviceTerminalAction.MODE_SCIENTIFIC;
        if (ItemDeviceInfoTerminal.NUM_THOUSANDS.equals(mode)) return PacketDeviceTerminalAction.MODE_THOUSANDS;
        if (ItemDeviceInfoTerminal.NUM_VOLTAGE.equals(mode)) return PacketDeviceTerminalAction.MODE_VOLTAGE;
        return PacketDeviceTerminalAction.MODE_NORMAL;
    }

    /** 本地化工具 */
    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /** 本地化工具（{@link GuiDeviceEntryList} 同包静态共用） */
    static String trStatic(String key) {
        return StatCollector.translateToLocal(key);
    }
}
