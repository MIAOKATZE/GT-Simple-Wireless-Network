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
 * （pollTimer=0 首帧即发）；{@link #doesGuiPauseGame()} 返回 false 防单人暂停导致轮询僵死；
 * 列表区滚轮翻页且增量批次未到齐时经 {@link #requestPendingPages()} 限频追加请求（G5-3）；</li>
 * <li>服务端包 9 <b>增量分页</b>回发（每次排水最多 2 页：当前页 + 预取，v1.7.14 G5；不再
 * 版本一变即 8 页全量连发）→ ClientProxy 切主线程 → {@link DeviceTerminalClientCache}
 * （锚点=终端 UUID，GUI 关闭不清缓存，重开即显上次快照；增量页按版本累积，未到齐提交
 * 连续前缀快照）；</li>
 * <li>绘制读 {@link #sortedEntries}：快照版本<b>或条目数</b>（增量前缀增长）变化时按当前
 * 排序列/方向重排（稳定排序，同键保持绑定序），版本未变且前缀未增长且无偏好脏标则入口
 * 短路零重排（G5-2）；排序/计数法点击后本地立即生效并发包 10（action 0/1）持久化到物品 NBT。</li>
 * </ol>
 * <p>
 * 布局（自上而下）：标题 / 顶行四按钮（计数模式四态轮换 + 显示配方纯本地开关 +
 * 功率筛选三态轮换 + 状态筛选四态轮换，后三者为纯 GUI 会话态）/ 列头行
 * （点击排序，▲▼ 高亮当前列，平均列默认降序、其余默认升序）/ 滚动列表
 * （{@link GuiDeviceEntryList}，行高 20）/ 底行操作提示。
 * <p>
 * 筛选（v1.8.0）：功率（全部→耗电→发电）与状态（全部→待机→运行→停机）两组可叠加
 * （AND），插在条目副本生成后、排序前；会话态实例字段仿显示配方——不发包不持久化，
 * 重开 GUI 复位为全部/全部。
 * <p>
 * 锚点解析（G5-1 缓存）：GUI 持有打开时传入的终端 UUID；不再每 tick 全物品栏重扫——
 * 槽位指纹（主背包槽物品引用 + 手持槽下标）变化或每 {@value #ANCHOR_RECHECK_TICKS} tick
 * 复核时才重解析手持优先终端（与服务端 {@code DeviceTerminalRequestQueue.findTerminalStack}
 * 同序；服务端右击空气时显式 S2FPacketSetSlot 同步 DIT_UUID 到客户端，槽位对象替换即被
 * 指纹检出，见 ItemDeviceInfoTerminal.onItemRightClick）；GUI 打开时强制一次全扫。
 * <p>
 * 初始 UI 偏好（排序列/方向/计数法）从打开时物品 stack NBT 读；action 后以本地状态为准
 * （服务端 NBT 写回由动作队列权威执行，下轮打开校正）。
 */
public class GuiDeviceInfoTerminal extends GuiScreen {

    /** 轮询间隔（tick）：每 20 tick（1 秒）发一次请求包 8 */
    private static final int POLL_INTERVAL_TICKS = 20;

    /** 锚点复核间隔（tick）：槽位指纹之外的低频兜底全扫（G5-1；GUI 打开时另强制一次） */
    private static final int ANCHOR_RECHECK_TICKS = 20;

    /** 滚轮触发追加轮询的最小间隔（tick）：限频防滚轮连击形成请求洪峰（G5-3） */
    private static final int EXTRA_POLL_INTERVAL_TICKS = 10;

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

    /** 顶行按钮：功率筛选三态轮换（纯 GUI 会话态，不发包不持久化；v1.8.0） */
    private static final int BTN_POWER_FILTER = 2;

    /** 顶行按钮：状态筛选四态轮换（纯 GUI 会话态，不发包不持久化；v1.8.0） */
    private static final int BTN_STATE_FILTER = 3;

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

    /** 追加轮询冷却（tick，>0 期间滚轮不再触发续页请求；G5-3） */
    private int extraPollCooldown = 0;

    /** 锚点复核计时器（初值 0 → initGui 首次 refreshAnchor 即强制全扫一次） */
    private int anchorTimer = 0;

    /** 上次锚点解析时的主背包槽物品引用指纹（槽位同步/挪动替换对象即失配；null=未扫过） */
    private ItemStack[] anchorSlotRefs;

    /** 上次锚点解析时的手持槽下标（切快捷栏不换槽内容但换手持，同样触发重扫） */
    private int anchorHeldSlot = -1;

    /** 当前终端锚点 UUID（构造传入 + 指纹/复核触发重解析；null=客户端 NBT 尚未同步到 UUID） */
    private UUID anchor;

    /** 当前快照数据版本（Long.MIN_VALUE=尚无任何快照；版本变化触发重排） */
    private long displayedVersion = Long.MIN_VALUE;

    /** 当前已排条目数（-1=无快照；增量前缀同版本增长时与版本共同触发重排，G5-2/G5-3） */
    private int displayedEntryCount = -1;

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

    /** 功率筛选会话态：0 全部 / 1 耗电 / 2 发电（重开 GUI 复位为全部） */
    private int powerFilter = 0;

    /** 状态筛选会话态：0 全部 / 1 待机 / 2 运行 / 3 停机（重开 GUI 复位为全部） */
    private int stateFilter = 0;

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
        // 顶行四按钮（列头行上方）：计数模式 / 显示配方 / 功率筛选 / 状态筛选
        // （显示配方右缘 +256；功率筛选 +264 宽 94；状态筛选 +362 宽 80，右缘 +442 = xSize-8，
        // 均不越界面右缘、不与相邻按钮/列头重叠）
        buttonList
            .add(new GtswnGuiButton(BTN_COUNT_MODE, this.guiLeft + 8, this.guiTop + 16, 130, 16, countModeText()));
        buttonList
            .add(new GtswnGuiButton(BTN_SHOW_RECIPE, this.guiLeft + 146, this.guiTop + 16, 110, 16, showRecipeText()));
        buttonList
            .add(new GtswnGuiButton(BTN_POWER_FILTER, this.guiLeft + 264, this.guiTop + 16, 94, 16, powerFilterText()));
        buttonList
            .add(new GtswnGuiButton(BTN_STATE_FILTER, this.guiLeft + 362, this.guiTop + 16, 80, 16, stateFilterText()));
        this.entryList = new GuiDeviceEntryList(this, this.guiLeft, this.guiTop);
        refreshAnchor();
        refreshEntries();
    }

    /**
     * 每 tick：锚点重解析（G5-1 缓存：槽位指纹变化或每 {@value #ANCHOR_RECHECK_TICKS} tick
     * 复核才全物品栏重扫，手持优先，与服务端队列解析同序）→ 版本/条目数变化或偏好变化时重排
     * （G5-2 入口短路）→ 每 {@value #POLL_INTERVAL_TICKS} tick 发包 8 轮询（首帧即发）。
     */
    @Override
    public void updateScreen() {
        refreshAnchor();
        refreshEntries();
        if (this.extraPollCooldown > 0) {
            this.extraPollCooldown--;
        }
        if (this.pollTimer++ % POLL_INTERVAL_TICKS == 0) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestDeviceTerminalData());
        }
    }

    // ==================== 锚点与数据刷新 ====================

    /**
     * 只读重解析终端锚点 UUID（手持优先 → 主背包首台；与 DeviceTerminalRequestQueue 同序）。
     * <p>
     * G5-1 锚点缓存：不再每 tick 全物品栏重扫——槽位指纹（主背包槽物品引用 + 手持槽下标）
     * 变化才重扫，另每 {@value #ANCHOR_RECHECK_TICKS} tick 强制复核一次兜底（覆盖原地 NBT
     * 变异等不换对象引用的边角，锚点变化 ≤20t 内跟踪）；GUI 打开时（指纹未建 / 计时器归零）
     * 自然强制一次全扫。槽位挪动 / 快捷栏切换同 tick 即检出，语义与每 tick 重扫一致。
     */
    private void refreshAnchor() {
        this.anchorTimer++;
        EntityPlayer player = this.mc.thePlayer;
        if (player == null) {
            // 与旧语义一致：解析不可用（玩家未就绪）保持现锚点
            return;
        }
        if (this.anchorTimer % ANCHOR_RECHECK_TICKS != 0 && !inventoryFingerprintChanged(player)) {
            return;
        }
        ItemStack[] inv = player.inventory.mainInventory;
        this.anchorSlotRefs = new ItemStack[inv.length];
        System.arraycopy(inv, 0, this.anchorSlotRefs, 0, inv.length);
        this.anchorHeldSlot = player.inventory.currentItem;
        UUID resolved = resolveTerminalId();
        if (resolved != null && !resolved.equals(this.anchor)) {
            this.anchor = resolved;
            this.displayedVersion = Long.MIN_VALUE;
            this.displayedEntryCount = -1;
        }
    }

    /**
     * 槽位指纹比对：主背包任一槽物品引用（identity）或手持槽下标变化即视为物品栏变化。
     * <p>
     * 客户端槽位同步（S2FPacketSetSlot / 挪动 / 拾取放置）一律整对象替换数组元素，引用
     * 比对即可覆盖；比每 tick 对全物品栏做 NBT 字符串读 + UUID 解析廉价得多。
     */
    private boolean inventoryFingerprintChanged(EntityPlayer player) {
        if (this.anchorSlotRefs == null || this.anchorHeldSlot != player.inventory.currentItem) {
            return true;
        }
        ItemStack[] inv = player.inventory.mainInventory;
        if (inv.length != this.anchorSlotRefs.length) {
            return true;
        }
        for (int i = 0; i < inv.length; i++) {
            if (inv[i] != this.anchorSlotRefs[i]) {
                return true;
            }
        }
        return false;
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

    /**
     * 快照版本 / 条目数（增量前缀增长）或排序偏好变化时按当前列/方向重排（稳定排序，同键保持绑定序）。
     * <p>
     * G5-2 版本不变零重排：短路置于入口最早期——版本相等、条目数相等且无偏好/筛选脏标时
     * 仅一次缓存查询 + 两个标量比较即返回，不建副本不排序不触发 clampScroll。
     */
    private void refreshEntries() {
        Snapshot snapshot = this.anchor == null ? null : DeviceTerminalClientCache.getSnapshot(this.anchor);
        if (!this.sortDirty && snapshot != null
            && snapshot.version == this.displayedVersion
            && snapshot.entries.size() == this.displayedEntryCount) {
            return;
        }
        if (snapshot == null) {
            if (this.displayedVersion != Long.MIN_VALUE) {
                this.displayedVersion = Long.MIN_VALUE;
                this.displayedEntryCount = -1;
                this.sortedEntries = Collections.emptyList();
                if (this.entryList != null) this.entryList.clampScroll();
            }
            return;
        }
        if (snapshot.version != this.displayedVersion || snapshot.entries.size() != this.displayedEntryCount
            || this.sortDirty) {
            this.displayedVersion = snapshot.version;
            this.displayedEntryCount = snapshot.entries.size();
            this.sortDirty = false;
            List<Entry> copy = new ArrayList<>(snapshot.entries);
            // 双组筛选（AND 叠加）：插在 copy 后、sort 前（v1.8.0 计划步骤 4）
            if (this.powerFilter != 0) {
                copy.removeIf(entry -> !matchesPowerFilter(entry, this.powerFilter));
            }
            if (this.stateFilter != 0) {
                copy.removeIf(entry -> !matchesStateFilter(entry, this.stateFilter));
            }
            Comparator<Entry> cmp = (a, b) -> compareEntries(a, b, this.sortColumn);
            if (this.sortDesc) {
                cmp = cmp.reversed();
            }
            copy.sort(cmp);
            this.sortedEntries = copy;
            if (this.entryList != null) this.entryList.clampScroll();
        }
    }

    /** 功率筛选判定：1=仅耗电（powerType 0）/ 2=仅发电（powerType 1）；其余模式不筛。 */
    private static boolean matchesPowerFilter(Entry entry, int mode) {
        return mode == 1 ? entry.powerType == 0 : entry.powerType == 1;
    }

    /** 状态筛选判定：1=仅待机 / 2=仅运行 / 3=仅停机（复用三态 0/1/2）；其余模式不筛。 */
    private static boolean matchesStateFilter(Entry entry, int mode) {
        return mode == 1 ? entry.state == 0 : mode == 2 ? entry.state == 1 : entry.state == 2;
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
        } else if (button.id == BTN_POWER_FILTER) {
            // 功率筛选三态轮换：全部→耗电→发电→全部（纯会话态，不发包不持久化）
            this.powerFilter = (this.powerFilter + 1) % 3;
            button.displayString = powerFilterText();
            this.sortDirty = true;
            refreshEntries();
        } else if (button.id == BTN_STATE_FILTER) {
            // 状态筛选四态轮换：全部→待机→运行→停机→全部（纯会话态，不发包不持久化）
            this.stateFilter = (this.stateFilter + 1) % 4;
            button.displayString = stateFilterText();
            this.sortDirty = true;
            refreshEntries();
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

    /** 功率筛选按钮文案（功率筛选: 全部/耗电/发电） */
    private String powerFilterText() {
        String value = tr(
            this.powerFilter == 1 ? "gtswn.device.gui.filter.power.consume"
                : this.powerFilter == 2 ? "gtswn.device.gui.filter.power.generate"
                    : "gtswn.device.gui.filter.power.all");
        return StatCollector.translateToLocalFormatted("gtswn.device.gui.filter.power", value);
    }

    /** 状态筛选按钮文案（状态筛选: 全部/待机/运行/停机；三态名复用 gtswn.device.gui.state.*） */
    private String stateFilterText() {
        String value = tr(
            this.stateFilter == 1 ? "gtswn.device.gui.state.idle"
                : this.stateFilter == 2 ? "gtswn.device.gui.state.running"
                    : this.stateFilter == 3 ? "gtswn.device.gui.state.stopped" : "gtswn.device.gui.filter.state.all");
        return StatCollector.translateToLocalFormatted("gtswn.device.gui.filter.state", value);
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

    /**
     * 面板背景：消费 gtswn 整版贴图 panel_device_info（450×252，1:1 绘制，plan/ui/texture-list.md §2/§3 #1），
     * 几何与尺寸零变化；标题/列头两条分隔线仍代码绘制，色值琥珀化（GtswnGuiPalette.DIVIDER）。
     */
    private void drawPanelBackground() {
        GtswnGuiDrawing.bind(GtswnGuiTextures.PANEL_DEVICE_INFO);
        GtswnGuiDrawing.drawRegion(
            GtswnGuiTextures.PANEL_DEVICE_INFO,
            this.guiLeft,
            this.guiTop,
            0,
            0,
            GtswnGuiTextures.PANEL_DEVICE_INFO_W,
            GtswnGuiTextures.PANEL_DEVICE_INFO_H,
            this.zLevel);
        // 标题分隔线
        drawRect(
            this.guiLeft + 8,
            this.guiTop + 14,
            this.guiLeft + this.xSize - 8,
            this.guiTop + 15,
            GtswnGuiPalette.DIVIDER);
        // 列头分隔线
        drawRect(
            this.guiLeft + 8,
            this.guiTop + 50,
            this.guiLeft + this.xSize - 8,
            this.guiTop + 51,
            GtswnGuiPalette.DIVIDER);
    }

    /** 标题（居中） */
    private void drawTitle() {
        String title = tr("gtswn.device.gui.title");
        this.fontRendererObj.drawString(
            title,
            this.guiLeft + (this.xSize - this.fontRendererObj.getStringWidth(title)) / 2,
            this.guiTop + 4,
            GtswnGuiPalette.TEXT_TITLE);
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
                i == this.sortColumn ? GtswnGuiPalette.TEXT_ACCENT : GtswnGuiPalette.TEXT_BODY);
        }
        // 第六列：传送列头（行内 ✦ 按钮列；不可排序，仅标签）
        this.fontRendererObj.drawString(
            "§l" + tr("gtswn.device.gui.teleport"),
            this.guiLeft + 8 + GuiDeviceEntryList.TP_BTN_X - 6,
            this.guiTop + HEADER_Y,
            GtswnGuiPalette.TEXT_BODY);
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
        this.fontRendererObj.drawString(text, x, y, GtswnGuiPalette.TEXT_MUTED);
    }

    /** 底行固定操作提示。 */
    private void drawFooter() {
        this.fontRendererObj.drawString(
            tr("gtswn.device.gui.footer"),
            this.guiLeft + 8,
            this.guiTop + FOOTER_Y,
            GtswnGuiPalette.TEXT_MUTED);
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
            // G5-3 翻页按需：滚轮在列表区滚动且服务端增量批次未到齐 → 限频追加请求接力续页
            requestPendingPages();
            return;
        }
        super.handleMouseInput();
    }

    /**
     * 追加轮询（限频 {@value #EXTRA_POLL_INTERVAL_TICKS} tick 一次）：仅当当前终端缓存批次
     * 仍有未到齐页时发送包 8——服务端每次排水最多回 2 页（当前页 + 预取）自已发页接力，
     * 滚轮连击被限频；批次已到齐时客户端侧零发包（服务端版本跳过之外再省一道）。
     */
    private void requestPendingPages() {
        if (this.extraPollCooldown > 0 || this.anchor == null) {
            return;
        }
        if (!DeviceTerminalClientCache.hasMorePending(this.anchor)) {
            return;
        }
        this.extraPollCooldown = EXTRA_POLL_INTERVAL_TICKS;
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestDeviceTerminalData());
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

    /** 绘制层级转发（Gui.zLevel 为 protected 跨包不可直引，列表组件贴图绘制用） */
    float guiZLevel() {
        return zLevel;
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
