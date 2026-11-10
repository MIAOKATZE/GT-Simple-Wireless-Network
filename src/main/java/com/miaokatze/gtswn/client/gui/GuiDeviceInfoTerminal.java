package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.ObjectValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.menu.DropdownWidget;
import com.cleanroommc.modularui.widgets.menu.Menu;
import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache.Snapshot;
import com.miaokatze.gtswn.client.DeviceTerminalNames;
import com.miaokatze.gtswn.client.engineering.FactoryDashboard;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalActions;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalAction;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/**
 * Full-screen device catalogue with native filters and a shared list/card presentation.
 * Whole-device link actions carry explicit desired state.
 */
public class GuiDeviceInfoTerminal extends GtswnMuiScreen {

    /** 轮询间隔（tick）：每 20 tick（1 秒）发一次请求包 8 */
    private static final int POLL_INTERVAL_TICKS = 20;

    /** 锚点复核间隔（tick）：槽位指纹之外的低频兜底全扫（G5-1；GUI 打开时另强制一次） */
    private static final int ANCHOR_RECHECK_TICKS = 20;

    /** 滚轮触发追加轮询的最小间隔（tick）：限频防滚轮连击形成请求洪峰（G5-3） */
    private static final int EXTRA_POLL_INTERVAL_TICKS = 10;

    /** GUI 宽度（紧凑列与链路操作） */
    private int xSize = 800;

    /** GUI 高度（标题/按钮/列头/列表/底行合计） */
    private int ySize = 450;

    private static final String[] POWER_FILTER_KEYS = { "gtswn.device.gui.filter.power.all",
        "gtswn.device.gui.filter.power.generate_eu", "gtswn.device.gui.filter.power.consume_eu" };

    /** 列头行文本 Y 偏移（按钮行下方） */
    private int headerY = 116;

    /** 底行提示 Y 偏移（列表底界 top+232 下方） */
    private int listTop = 140;

    /** 列头 lang 键（下标 = 排序列 0-4 NAME/STATE/INST/AVG/POS，与包 10 COLUMN_* 一致） */
    private static final String[] COLUMN_KEYS = { "gtswn.device.gui.col.name", "gtswn.device.gui.col.state",
        "gtswn.device.gui.col.instant", "gtswn.device.gui.col.average", "gtswn.device.gui.col.pos" };

    /** 状态 lang 键（0 待机 / 1 运行 / 2 停机 / 3 忽略，值自带 § 颜色码） */
    private static final String[] STATE_KEYS = { "gtswn.device.gui.state.idle", "gtswn.device.gui.state.running",
        "gtswn.device.gui.state.stopped", "gtswn.device.gui.state.ignored" };

    // ==================== 状态 ====================

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

    /** A directly opened terminal keeps its identity even if another terminal is held. */
    private UUID requestedTerminalId;
    private ItemStack[] anchorBaubleRefs;
    private boolean hasLinkTerminal;
    private boolean inventoryTeleportAnchor;

    /** 当前终端锚点 UUID（构造传入 + 指纹/复核触发重解析；null=客户端 NBT 尚未同步到 UUID） */
    private UUID anchor;

    /** Identity includes authorization resets even when version and row count are unchanged. */
    private Snapshot displayedSnapshot;
    private long displayedAuthorizationEpoch = Long.MIN_VALUE;

    /** 排序后条目（绘制与列表命中共用；无数据为空列表） */
    private List<Entry> sortedEntries = Collections.emptyList();

    /** 排序列（0-4，与 PacketDeviceTerminalAction.COLUMN_* 一致；初始从 stack NBT 读） */
    private int sortColumn;

    /** 排序方向（true=降序；初始从 stack NBT 读，默认 DESC） */
    private boolean sortDesc;

    /** 计数法（0 常规 / 1 科学 / 2 千位 / 3 电压等级；初始从 stack NBT 读） */
    private int countMode;

    /** 功率筛选会话态：全部、电力、蒸汽、产能、耗电、产汽、耗气（重开 GUI 复位为全部） */
    private int powerFilter = 0;

    /** Native secondary panel controls the filter menu lifecycle. */
    private IPanelHandler tagPanel;

    /** 状态筛选会话态：0 全部 / 1 待机 / 2 运行 / 3 停机 / 4 忽略（重开 GUI 复位为全部） */
    private int stateFilter = 0;
    private final com.miaokatze.gtswn.client.DeviceListFilters filters = new com.miaokatze.gtswn.client.DeviceListFilters();
    private com.cleanroommc.modularui.widgets.textfield.TextFieldWidget searchField;

    /** 排序偏好被点击后置位，强制下次 refreshEntries 重排（同版本快照也重排） */
    private boolean sortDirty = false;

    /** 滚动列表控件（initGui 创建） */
    private GuiDeviceEntryList entryList;
    private GuiDeviceNameEditor nameEditor;
    private Entry selectedDesktopEntry;
    private ParentWidget<?> content;
    private ParentWidget<?> tagStrip;
    private final List<Widget<?>> filterControls = new ArrayList<>();
    private final List<Widget<?>> headerControls = new ArrayList<>();
    private ButtonWidget<?> resetFilter, listView, iconView, sortDirection, filterVisibility, mountButton,
        engineeringButton;
    private List<String> availableTags = Collections.emptyList();
    private String tagSignature = "";
    private boolean iconMode;
    private boolean tagStripDirty;
    private boolean filtersExpanded = true;
    private boolean filterVisibilityChosen;

    /**
     * 构造：锚点 UUID + 初始 UI 偏好来源 stack（排序列/方向/计数法，只读）。
     *
     * @param terminalId 打开时解析的终端 UUID（可为 null——客户端 NBT 未同步 UUID 时先显示占位，
     *                   每 tick 重解析在槽位同步后自动锚定）
     * @param prefStack  偏好来源物品（可为 null，取默认值 AVG/DESC/NORMAL）
     */
    public GuiDeviceInfoTerminal(UUID terminalId, ItemStack prefStack) {
        super(
            new ModularPanel("device_info_terminal").sizeRel(1f)
                .pos(0, 0)
                .background(IDrawable.EMPTY));
        this.anchor = terminalId;
        this.requestedTerminalId = terminalId;
        this.sortColumn = prefStack == null ? PacketDeviceTerminalAction.COLUMN_AVG
            : columnFromString(ItemDeviceInfoTerminal.getSortColumn(prefStack));
        this.sortDesc = prefStack == null
            || ItemDeviceInfoTerminal.DIR_DESC.equals(ItemDeviceInfoTerminal.getSortDirection(prefStack));
        this.countMode = prefStack == null ? PacketDeviceTerminalAction.MODE_NORMAL
            : modeFromString(ItemDeviceInfoTerminal.getCountMode(prefStack));
        buildPanel();
    }

    private final class FullscreenContent extends ParentWidget<FullscreenContent> {

        @Override
        public boolean canHover() {
            return false;
        }

        @Override
        public void beforeResize(boolean onOpen) {
            layoutControls(getParentArea().w(), getParentArea().h());
            super.beforeResize(onOpen);
        }
    }

    private void buildPanel() {
        // MUI draws sibling backgrounds before their content; the full-screen fill belongs to the parent.
        content = new FullscreenContent().name("device.fullscreen")
            .sizeRel(1f)
            .background((context, x, y, width, height, theme) -> GtswnMuiWidgets.drawLegacy(this::drawPanelBackground));
        getMainPanel().child(content);
        content.child(GtswnMuiWidgets.canvas("device.chrome", 0, 0, 1, 1, (context, mouseX, mouseY, partialTicks) -> {
            drawTitle();
            drawBody(mouseX, mouseY);
            drawFooter();
        })
            .sizeRel(1f));
        headerControls.add(
            DeviceTerminalUi.button(
                "device.refresh",
                () -> tr("gtswn.device.gui.refresh"),
                0,
                0,
                68,
                20,
                () -> anchor != null && extraPollCooldown == 0,
                () -> {
                    pollTimer = 0;
                    extraPollCooldown = POLL_INTERVAL_TICKS;
                })
                .tooltip(t -> t.addLine(IKey.lang("gtswn.device.gui.refresh_hint"))));
        headerControls.add(
            DeviceTerminalUi.button(
                "device.settings",
                () -> tr("gtswn.device.gui.settings"),
                0,
                0,
                68,
                20,
                () -> anchor != null,
                () -> {
                    int slot = com.miaokatze.gtswn.common.device.DeviceTerminalSource.find(mc.thePlayer, anchor, false);
                    com.miaokatze.gtswn.common.gui.DeviceTerminalSettingsGuiFactory.requestOpen(
                        mc.thePlayer,
                        com.miaokatze.gtswn.common.device.DeviceTerminalSource.stack(mc.thePlayer, slot));
                }));
        headerControls
            .add(DeviceTerminalUi.button("device.close", "X", 0, 0, 22, 20, () -> getMainPanel().closeIfOpen()));
        for (Widget<?> control : headerControls) content.child(control);
        searchField = new com.cleanroommc.modularui.widgets.textfield.TextFieldWidget() {

            @Override
            protected void onTextChanged() {
                super.onTextChanged();
                filters.query = getText();
                if (entryList != null) filterChanged();
            }
        }.value(new com.cleanroommc.modularui.value.StringValue(""))
            .autoUpdateOnChange(true)
            .setMaxLength(64)
            .background(DeviceTerminalUi.INPUT)
            .hoverBackground(DeviceTerminalUi.INPUT_HOVER)
            .setTextColor(GtswnGuiPalette.TEXT_BODY)
            .hintColor(GtswnGuiPalette.TEXT_MUTED)
            .hintText(tr("gtswn.device.filter.search_hint"))
            .size(100, 20)
            .name("device.filter.search");
        searchField.tooltip(t -> t.addLine(IKey.lang("gtswn.device.filter.search")));
        content.child(searchField);
        listView = DeviceTerminalUi
            .button("device.view.list", () -> tr("gtswn.device.view.list"), 0, 0, 52, 20, () -> setIconMode(false));
        iconView = DeviceTerminalUi
            .button("device.view.icons", () -> tr("gtswn.device.view.icons"), 0, 0, 52, 20, () -> setIconMode(true));
        listView.background(
            (context, x, y, w, h, theme) -> (iconMode ? DeviceTerminalUi.NORMAL : DeviceTerminalUi.SELECTED)
                .draw(context, x, y, w, h, theme));
        listView.hoverBackground(
            (context, x, y, w, h, theme) -> (iconMode ? DeviceTerminalUi.HOVER : DeviceTerminalUi.SELECTED_HOVER)
                .draw(context, x, y, w, h, theme));
        iconView.background(
            (context, x, y, w, h, theme) -> (iconMode ? DeviceTerminalUi.SELECTED : DeviceTerminalUi.NORMAL)
                .draw(context, x, y, w, h, theme));
        iconView.hoverBackground(
            (context, x, y, w, h, theme) -> (iconMode ? DeviceTerminalUi.SELECTED_HOVER : DeviceTerminalUi.HOVER)
                .draw(context, x, y, w, h, theme));
        resetFilter = DeviceTerminalUi
            .button("device.filter.reset", () -> tr("gtswn.device.filter.reset"), 0, 0, 62, 20, () -> {
                filters.kind = filters.link = filters.owner = powerFilter = stateFilter = 0;
                filters.dimension = filters.tier = null;
                filters.tags.clear();
                filters.query = "";
                searchField.setText("");
                filterChanged();
            });
        content.child(listView)
            .child(iconView)
            .child(resetFilter);
        addDropdown(
            "count",
            () -> countMode,
            this::selectCountMode,
            () -> numbers(0, 4),
            value -> tr(
                "gtswn.device.gui.count_mode."
                    + new String[] { "normal", "scientific", "thousands", "voltage" }[value]));
        addDropdown("power", () -> powerFilter, value -> {
            powerFilter = value;
            filterChanged();
        }, () -> numbers(0, POWER_FILTER_KEYS.length), value -> tr(POWER_FILTER_KEYS[value]));
        addDropdown("state", () -> stateFilter, value -> {
            stateFilter = value;
            filterChanged();
        },
            () -> numbers(0, 5),
            value -> value == 0 ? tr("gtswn.device.gui.filter.state.all") : stateText((byte) (value - 1)));
        addDropdown("kind", () -> filters.kind, value -> {
            filters.kind = value;
            filterChanged();
        }, () -> numbers(0, 5), value -> value == 0 ? tr("gtswn.device.filter.all") : kindText(value.byteValue()));
        addDropdown("dimension", () -> filters.dimension == null ? Integer.MIN_VALUE : filters.dimension, value -> {
            filters.dimension = value == Integer.MIN_VALUE ? null : value;
            filterChanged();
        },
            () -> cachedOptions(false),
            value -> value == Integer.MIN_VALUE ? tr("gtswn.device.filter.all") : value.toString());
        addDropdown("tier", () -> filters.tier == null ? Integer.MIN_VALUE : filters.tier, value -> {
            filters.tier = value == Integer.MIN_VALUE ? null : value;
            filterChanged();
        },
            () -> cachedOptions(true),
            value -> value == Integer.MIN_VALUE ? tr("gtswn.device.filter.all") : tierText(value.byteValue()));
        addDropdown("link", () -> filters.link, value -> {
            filters.link = value;
            filterChanged();
        }, () -> numbers(0, 6), value -> tr("gtswn.device.filter.link." + value));
        addDropdown("owner", () -> filters.owner, value -> {
            filters.owner = value;
            filterChanged();
        }, () -> numbers(0, 4), value -> tr("gtswn.device.filter.owner." + value));
        addDropdown(
            "sort",
            () -> sortColumn,
            this::selectSortColumn,
            () -> numbers(0, 5),
            value -> tr(COLUMN_KEYS[value]));
        sortDirection = DeviceTerminalUi.button(
            "device.sort.direction",
            () -> tr(sortDesc ? "gtswn.device.sort.desc" : "gtswn.device.sort.asc"),
            0,
            0,
            80,
            20,
            () -> onColumnHeaderClicked(sortColumn));
        content.child(sortDirection);
        filterVisibility = DeviceTerminalUi.button(
            "device.filters.expand",
            () -> tr(filtersExpanded ? "gtswn.device.filters.collapse" : "gtswn.device.filters.expand"),
            0,
            0,
            82,
            20,
            () -> {
                filterVisibilityChosen = true;
                filtersExpanded = !filtersExpanded;
                layoutControls(xSize, ySize);
                content.scheduleResize();
            })
            .background(
                (context, x, y, w, h, theme) -> (filtersExpanded ? DeviceTerminalUi.SELECTED : DeviceTerminalUi.NORMAL)
                    .draw(context, x, y, w, h, theme))
            .hoverBackground(
                (context, x, y, w, h,
                    theme) -> (filtersExpanded ? DeviceTerminalUi.SELECTED_HOVER : DeviceTerminalUi.HOVER)
                        .draw(context, x, y, w, h, theme));
        content.child(filterVisibility);
        tagStrip = new ParentWidget<>().name("device.tags")
            .height(20)
            .background(IDrawable.EMPTY);
        content.child(tagStrip);
        entryList = new GuiDeviceEntryList(this);
        content.child(entryList);
        nameEditor = new GuiDeviceNameEditor(this);
        tagPanel = IPanelHandler.simple(getMainPanel(), (parent, player) -> buildTagPanel(), true);
        mountButton = DeviceTerminalUi
            .button(
                "device.desktop.mount",
                () -> tr("gtswn.device.gui.mount"),
                0,
                0,
                146,
                20,
                () -> DesktopDeviceSources.binding(anchor, selectedDesktopEntry) != null,
                () -> FactoryDashboard.mount(anchor, selectedDesktopEntry))
            .tooltip(t -> t.addLine(IKey.lang("gtswn.device.gui.mount.hint")));
        engineeringButton = DeviceTerminalUi
            .button(
                "device.engineering",
                () -> tr("gtswn.device.gui.engineering"),
                0,
                0,
                110,
                20,
                () -> EngineeringTerminalActions.canOpen(anchor),
                () -> EngineeringTerminalActions.openGrid(anchor))
            .tooltip(t -> t.addLine(IKey.lang("gtswn.device.gui.engineering.hint")));
        content.child(mountButton)
            .child(engineeringButton);
    }

    private void layoutControls(int width, int height) {
        boolean changed = xSize != width || ySize != height;
        xSize = width;
        ySize = height;
        for (int i = 0; i < headerControls.size(); i++) headerControls.get(i)
            .pos(width - 180 + i * 74, 8);
        searchField.pos(10, 36)
            .width(Math.max(50, width - 202));
        listView.pos(width - 186, 36);
        iconView.pos(width - 128, 36);
        resetFilter.pos(width - 70, 36);
        if (!filterVisibilityChosen) filtersExpanded = height >= 360;
        int columns = Math.max(3, Math.min(9, (width - 14) / 104));
        int cellWidth = Math.max(1, (width - 20 - (columns - 1) * 6) / columns);
        for (int i = 0; i < filterControls.size(); i++) {
            filterControls.get(i)
                .setEnabled(filtersExpanded);
            filterControls.get(i)
                .pos(10 + i % columns * (cellWidth + 6), 62 + i / columns * 24)
                .size(cellWidth, 20);
        }
        int tagY = 62 + (filtersExpanded ? ((filterControls.size() + columns - 1) / columns) * 24 : 0);
        tagStrip.pos(10, tagY)
            .width(width - 20);
        headerY = tagY + 24;
        listTop = headerY + 24;
        sortDirection.pos(width - 90, headerY);
        filterVisibility.pos(width - 178, headerY);
        entryList.pos(10, listTop)
            .size(Math.max(1, width - 20), Math.max(12, height - listTop - 46));
        mountButton.pos(10, height - 28);
        engineeringButton.pos(width - 120, height - 28);
        if (changed) {
            rebuildTagStrip();
            entryList.invalidateLayout();
        }
    }

    private void addDropdown(String id, java.util.function.Supplier<Integer> getter,
        java.util.function.Consumer<Integer> setter, java.util.function.Supplier<List<Integer>> options,
        java.util.function.Function<Integer, String> text) {
        String label = tr("gtswn.device.filter.label." + id);
        DeviceDropdown dropdown = new DeviceDropdown("device.filter." + id, options);
        dropdown.value(new ObjectValue.Dynamic<>(Integer.class, getter, setter))
            .optionToWidget(
                (value, selected) -> IKey
                    .dynamic(
                        () -> GuiDeviceEntryList.ellipsis(
                            fontRendererObj,
                            EnumChatFormatting.getTextWithoutFormattingCodes(
                                selected ? label + ": " + text.apply(getter.get()) + " \u25BE" : text.apply(value)),
                            Math.max(
                                1,
                                dropdown.getArea()
                                    .w() - 14)))
                    .asWidget()
                    .color(GtswnGuiPalette.TEXT_BODY)
                    .height(20)
                    .widthRel(1f)
                    .padding(6, 3))
            .background(DeviceTerminalUi.NORMAL)
            .hoverBackground(DeviceTerminalUi.HOVER)
            .maxVerticalMenuSize(180)
            .size(110, 20)
            .tooltipBuilder(t -> t.addLine(label + ": " + text.apply(getter.get())))
            .tooltipAutoUpdate(true);
        filterControls.add(dropdown);
        content.child(dropdown);
    }

    private static final class DeviceDropdown extends DropdownWidget<Integer, DeviceDropdown> {

        private final java.util.function.Supplier<List<Integer>> options;

        DeviceDropdown(String name, java.util.function.Supplier<List<Integer>> options) {
            super(name, Integer.class);
            this.options = options;
        }

        @Override
        protected Menu<?> createMenu() {
            List<Integer> values = options.get();
            clearOptions();
            options(values);
            Menu<?> menu = super.createMenu().background(DeviceTerminalUi.INPUT);
            int index = 0;
            for (com.cleanroommc.modularui.api.widget.IWidget list : menu.getChildren())
                for (com.cleanroommc.modularui.api.widget.IWidget option : list.getChildren())
                    if (option instanceof ButtonWidget<?>) {
                        Integer value = values.get(index++);
                        ((ButtonWidget<?>) option)
                            .background(
                                (context, x, y, w, h,
                                    theme) -> (value.equals(getValue().getValue()) ? DeviceTerminalUi.SELECTED
                                        : DeviceTerminalUi.NORMAL).draw(context, x, y, w, h, theme))
                            .hoverBackground(
                                (context, x, y, w, h,
                                    theme) -> (value.equals(getValue().getValue()) ? DeviceTerminalUi.SELECTED_HOVER
                                        : DeviceTerminalUi.HOVER).draw(context, x, y, w, h, theme));
                    }
            return menu;
        }

        @Override
        public IDrawable getBackground() {
            return isOpen() ? DeviceTerminalUi.SELECTED : super.getBackground();
        }

        @Override
        public IDrawable getHoverBackground() {
            return isOpen() ? DeviceTerminalUi.SELECTED_HOVER : super.getHoverBackground();
        }

        @Override
        public Result onMousePressed(int mouseButton) {
            if (mouseButton != 0) return Result.STOP;
            if (!isOpen()) {
                int below = getScreen().getScreenArea()
                    .h() - getArea().ey() - 6;
                int above = getArea().y() - 6;
                if (below < 180 && above > below) directionUp();
                else directionDown();
                maxVerticalMenuSize(Math.max(20, Math.min(180, Math.max(below, above))));
                deleteMenu();
            }
            return super.onMousePressed(mouseButton);
        }
    }

    private static List<Integer> numbers(int first, int end) {
        List<Integer> result = new ArrayList<>();
        for (int i = first; i < end; i++) result.add(i);
        return result;
    }

    private List<Integer> cachedOptions(boolean tier) {
        java.util.SortedSet<Integer> values = new java.util.TreeSet<>();
        values.add(Integer.MIN_VALUE);
        if (displayedSnapshot != null)
            for (Entry entry : displayedSnapshot.entries) values.add(tier ? (int) entry.machineTier : entry.dim);
        return new ArrayList<>(values);
    }

    private void selectCountMode(int mode) {
        countMode = mode;
        GTSWNPacketHandler.NETWORK.sendToServer(
            new PacketDeviceTerminalAction(
                anchor,
                PacketDeviceTerminalAction.ACTION_COUNT_MODE,
                (byte) 0,
                (byte) 0,
                (byte) mode,
                ""));
    }

    private void selectSortColumn(int column) {
        if (column != sortColumn) onColumnHeaderClicked(column);
    }

    private void setIconMode(boolean icons) {
        if (iconMode == icons) return;
        iconMode = icons;
        entryList.invalidateLayout();
    }

    private void filterChanged() {
        sortDirty = true;
        tagStripDirty = true;
    }

    String kindText(byte kind) {
        return tr("gtswn.device.filter.kind." + kind);
    }

    String tierText(byte tier) {
        return tier < 0 || tier >= gregtech.api.enums.GTValues.VN.length ? "?" : gregtech.api.enums.GTValues.VN[tier];
    }

    private void refreshTags(Snapshot snapshot) {
        java.util.SortedSet<String> tags = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry entry : snapshot.entries)
            tags.addAll(com.miaokatze.gtswn.common.device.DeviceTerminalTagRules.labels(entry.tags));
        String signature = String.join("\n", tags);
        if (signature.equals(tagSignature)) return;
        tagSignature = signature;
        availableTags = new ArrayList<>(tags);
        rebuildTagStrip();
        if (tagPanel != null) {
            tagPanel.closePanel();
            tagPanel.deleteCachedPanel();
        }
    }

    private void toggleTag(String tag) {
        if (!filters.tags.removeIf(value -> value.equalsIgnoreCase(tag))) filters.tags.add(tag);
        filterChanged();
    }

    private boolean tagSelected(String tag) {
        for (String value : filters.tags) if (value.equalsIgnoreCase(tag)) return true;
        return false;
    }

    private ButtonWidget<?> tagButton(String tag, int x, int y, int width) {
        return DeviceTerminalUi.button("device.tag." + tag, () -> tag, x, y, width, 20, () -> toggleTag(tag))
            .background(
                (context, px, py, w, h,
                    theme) -> (tagSelected(tag) ? DeviceTerminalUi.SELECTED : DeviceTerminalUi.NORMAL)
                        .draw(context, px, py, w, h, theme))
            .hoverBackground(
                (context, px, py, w, h,
                    theme) -> (tagSelected(tag) ? DeviceTerminalUi.SELECTED_HOVER : DeviceTerminalUi.HOVER)
                        .draw(context, px, py, w, h, theme))
            .tooltip(t -> t.addLine(IKey.lang("gtswn.device.tags.filter_hint")));
    }

    private void rebuildTagStrip() {
        if (tagStrip == null) return;
        tagStrip.removeAll();
        tagStrip
            .child(DeviceTerminalUi.button("device.tags.all", () -> tr("gtswn.device.tags.all"), 0, 0, 62, 20, () -> {
                filters.tags.clear();
                filterChanged();
            })
                .background(
                    (context, px, py, w, h,
                        theme) -> (filters.tags.isEmpty() ? DeviceTerminalUi.SELECTED : DeviceTerminalUi.NORMAL)
                            .draw(context, px, py, w, h, theme))
                .hoverBackground(
                    (context, px, py, w, h,
                        theme) -> (filters.tags.isEmpty() ? DeviceTerminalUi.SELECTED_HOVER : DeviceTerminalUi.HOVER)
                            .draw(context, px, py, w, h, theme)));
        int x = 68;
        for (String tag : availableTags) {
            int width = Math.min(160, fontRendererObj.getStringWidth(tag) + 16);
            if (x + width > xSize - 126) break;
            tagStrip.child(tagButton(tag, x, 0, width));
            x += width + 6;
        }
        tagStrip.child(
            DeviceTerminalUi.button(
                "device.tags.expand",
                () -> tr("gtswn.device.tags.expand") + (filters.tags.isEmpty() ? "" : " (" + filters.tags.size() + ")"),
                Math.max(68, xSize - 126),
                0,
                106,
                20,
                () -> tagPanel.togglePanel()));
    }

    private ModularPanel buildTagPanel() {
        int width = Math.min(520, Math.max(280, xSize - 36));
        int height = Math.min(330, Math.max(120, ySize - 36));
        ModularPanel panel = new Dialog<>("device.tags.expanded").size(width, height)
            .background(GtswnMuiTheme.PANEL)
            .themeOverride(GtswnMuiTheme.THEME_ID);
        GtswnMuiTheme.fit(panel);
        panel.child(
            IKey.lang("gtswn.device.tags.title")
                .asWidget()
                .color(GtswnGuiPalette.TEXT_TITLE)
                .pos(12, 10)
                .size(width - 48, 16));
        panel.child(
            ButtonWidget.panelCloseButton()
                .size(18)
                .background(DeviceTerminalUi.NORMAL)
                .hoverBackground(DeviceTerminalUi.HOVER));
        panel.child(
            IKey.lang("gtswn.device.tags.filter_hint")
                .asWidget()
                .color(GtswnGuiPalette.TEXT_BODY)
                .pos(12, 29)
                .size(width - 24, 24));
        ListWidget<com.cleanroommc.modularui.api.widget.IWidget, ?> list = new ListWidget<>().pos(12, 56)
            .size(width - 24, height - 68)
            .background(GtswnMuiTheme.LIST_PANEL);
        ParentWidget<?> row = null;
        int x = 0;
        List<String> labels = new ArrayList<>(availableTags);
        for (String selected : filters.tags) if (labels.stream()
            .noneMatch(value -> value.equalsIgnoreCase(selected))) labels.add(selected);
        for (String tag : labels) {
            int chipWidth = Math.min(width - 44, fontRendererObj.getStringWidth(tag) + 20);
            if (row == null || x + chipWidth > width - 44) {
                row = new ParentWidget<>().size(width - 36, 26)
                    .background(IDrawable.EMPTY);
                list.child(row);
                x = 4;
            }
            row.child(tagButton(tag, x, 3, chipWidth));
            x += chipWidth + 6;
        }
        panel.child(list);
        return panel;
    }

    @Override
    public void onOpen() {
        super.onOpen();
        refreshAnchor();
        DesktopDeviceSources.nativeViewer(anchor);
        refreshEntries();
    }

    /**
     * 每 tick：锚点重解析（G5-1 缓存：槽位指纹变化或每 {@value #ANCHOR_RECHECK_TICKS} tick
     * 复核才重扫主背包与饰品栏的指定 UUID）→ 版本/条目数变化或偏好变化时重排
     * （不可变快照身份短路）→ 每 {@value #POLL_INTERVAL_TICKS} tick 发包 8 轮询（首帧即发）。
     */
    @Override
    public void onUpdate() {
        super.onUpdate();
        refreshAnchor();
        DesktopDeviceSources.nativeViewer(anchor);
        refreshEntries();
        if (tagStripDirty) {
            tagStripDirty = false;
            rebuildTagStrip();
        }
        if (this.extraPollCooldown > 0) {
            this.extraPollCooldown--;
        }
        if (this.pollTimer++ % POLL_INTERVAL_TICKS == 0 && this.anchor != null) {
            GTSWNPacketHandler.NETWORK.sendToServer(DeviceTerminalClientCache.requestMetadata(this.anchor));
        }
    }

    @Override
    public void onFrameUpdate() {
        // Receipts can revoke an open draft between client ticks; do not draw it for another frame.
        if (nameEditor != null) nameEditor.validateSelection();
        boolean authorized = displayedAuthorizationEpoch == DeviceTerminalClientCache.authorizationEpoch(anchor);
        if (tagStrip != null) tagStrip.setEnabled(authorized);
        if (!authorized && tagPanel != null) tagPanel.closePanel();
        super.onFrameUpdate();
    }

    // ==================== 锚点与数据刷新 ====================

    /**
     * 只读验证指定终端 UUID，移走后清空显示与轮询，不跳到其他终端。
     * <p>
     * G5-1 锚点缓存：不再每 tick 解析全部 NBT——槽位指纹（主背包 / 饰品槽物品引用 + 手持槽下标）
     * 变化才重扫，另每 {@value #ANCHOR_RECHECK_TICKS} tick 强制复核一次兜底（覆盖原地 NBT
     * 变异等不换对象引用的边角，锚点变化 ≤20t 内跟踪）；GUI 打开时（指纹未建 / 计时器归零）
     * 自然强制一次全扫。槽位挪动 / 快捷栏切换同 tick 即检出。
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
        IInventory baubles = TerminalBaubles.inventory(player);
        this.anchorBaubleRefs = baubles == null ? new ItemStack[0] : new ItemStack[baubles.getSizeInventory()];
        if (baubles != null) for (int i = 0; i < anchorBaubleRefs.length; i++) {
            anchorBaubleRefs[i] = baubles.getStackInSlot(i);
        }
        hasLinkTerminal = com.miaokatze.gtswn.common.device.TerminalLinkSettings.linkTerminal(player) != null;
        inventoryTeleportAnchor = com.miaokatze.gtswn.common.gui.TerminalSupplyStore
            .inventorySlot(player, com.miaokatze.gtswn.common.gui.TerminalSupplyStore.Kind.TELEPORT_ANCHOR) >= 0;
        UUID resolved = resolveTerminalId();
        if (!java.util.Objects.equals(resolved, this.anchor)) {
            closeNameEditor();
            this.anchor = resolved;
            this.selectedDesktopEntry = null;
            this.displayedSnapshot = null;
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
        IInventory baubles = TerminalBaubles.inventory(player);
        int baubleSize = baubles == null ? 0 : baubles.getSizeInventory();
        if (anchorBaubleRefs == null || baubleSize != anchorBaubleRefs.length) return true;
        for (int i = 0; i < baubleSize; i++) {
            if (baubles.getStackInSlot(i) != anchorBaubleRefs[i]) return true;
        }
        return false;
    }

    /** Resolve only the requested terminal; an opening with unsynced NBT locks onto its first resolved UUID. */
    private UUID resolveTerminalId() {
        EntityPlayer player = this.mc.thePlayer;
        if (player == null) {
            return this.anchor;
        }
        if (requestedTerminalId != null) {
            for (ItemStack candidate : player.inventory.mainInventory) {
                if (matchesRequestedTerminal(candidate)) return requestedTerminalId;
            }
            IInventory baubles = TerminalBaubles.inventory(player);
            if (baubles != null) for (int i = 0; i < baubles.getSizeInventory(); i++) {
                if (matchesRequestedTerminal(baubles.getStackInSlot(i))) {
                    return requestedTerminalId;
                }
            }
            return null;
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
        UUID resolved = stack == null ? null : ItemDeviceInfoTerminal.readTerminalId(stack);
        if (resolved != null) requestedTerminalId = resolved;
        return resolved;
    }

    private boolean matchesRequestedTerminal(ItemStack candidate) {
        return candidate != null && candidate.getItem() instanceof ItemDeviceInfoTerminal
            && requestedTerminalId.equals(ItemDeviceInfoTerminal.readTerminalId(candidate));
    }

    /**
     * Each immutable snapshot is a complete display revision, including same-version authorization resets.
     * Keepalive and duplicate-page replies retain its identity and therefore do not trigger redundant sorting.
     */
    private void refreshEntries() {
        Snapshot snapshot = this.anchor == null ? null : DesktopDeviceSources.displaySnapshot(this.anchor);
        long epoch = DeviceTerminalClientCache.authorizationEpoch(this.anchor);
        if (epoch != displayedAuthorizationEpoch) {
            displayedAuthorizationEpoch = epoch;
            selectedDesktopEntry = null;
            closeNameEditor();
            filters.tags.clear();
            tagStripDirty = true;
            clearTags();
        }
        if (entryList != null) entryList.setEnabled(snapshot != null && !snapshot.entries.isEmpty());
        if (!this.sortDirty && snapshot == this.displayedSnapshot && (snapshot != null || this.sortedEntries.isEmpty()))
            return;
        this.displayedSnapshot = snapshot;
        if (snapshot != null && tagStrip != null) refreshTags(snapshot);
        if (snapshot == null) {
            clearTags();
            this.sortedEntries = Collections.emptyList();
            this.selectedDesktopEntry = null;
            closeNameEditor();
            if (this.entryList != null) this.entryList.clampScroll();
            return;
        }
        this.sortDirty = false;
        selectedDesktopEntry = currentEntry(selectedDesktopEntry);
        if (nameEditor != null) nameEditor.validateSelection();
        List<Entry> copy = new ArrayList<>(snapshot.entries);
        copy.removeIf(entry -> !filters.matches(entry, mc.thePlayer == null ? null : mc.thePlayer.getUniqueID()));
        // 双组筛选（AND 叠加）：插在 copy 后、sort 前（v1.8.0 计划步骤 4）
        if (this.powerFilter != 0) {
            copy.removeIf(entry -> !DevicePowerFilter.matches(entry, this.powerFilter));
        }
        if (this.stateFilter != 0) {
            copy.removeIf(entry -> !matchesStateFilter(entry, this.stateFilter));
        }
        Comparator<Entry> cmp = (a, b) -> compareEntries(a, b, this.sortColumn);
        if (this.sortDesc) {
            cmp = cmp.reversed();
        }
        copy.sort(
            Comparator.<Entry>comparingLong(entry -> entry.favoriteOrder)
                .reversed()
                .thenComparing(cmp));
        this.sortedEntries = copy;
        if (this.entryList != null) this.entryList.clampScroll();
    }

    static long instantValue(Entry entry) {
        if (entry.state == 3) return 0L;
        return entry.instNet();
    }

    static double averageValue(Entry entry) {
        if (entry.state == 3) return 0D;
        return entry.avgNet();
    }

    String formatInstant(Entry entry) {
        if (entry.state == 3) return formatEUt(0);
        return formatEUt(entry.instNet());
    }

    String formatAverage(Entry entry) {
        if (entry.state == 3) return formatAvg(0);
        return formatAvg(entry.avgNet());
    }

    /** 状态筛选判定：1 待机 / 2 运行 / 3 停机 / 4 忽略；0 不筛。 */
    private static boolean matchesStateFilter(Entry entry, int mode) {
        return mode == 0 || entry.state == mode - 1;
    }

    /** 排序比较器：NAME 字符串 / STATE int / INST long / AVG double / POS dim,x,y,z 字典序。 */
    private static int compareEntries(Entry a, Entry b, int column) {
        switch (column) {
            case PacketDeviceTerminalAction.COLUMN_NAME:
                return DeviceTerminalNames.plainName(a)
                    .compareToIgnoreCase(DeviceTerminalNames.plainName(b));
            case PacketDeviceTerminalAction.COLUMN_STATE:
                return Integer.compare(a.state, b.state);
            case PacketDeviceTerminalAction.COLUMN_INST:
                return Long.compare(instantValue(a), instantValue(b));
            case PacketDeviceTerminalAction.COLUMN_AVG:
                return Double.compare(averageValue(a), averageValue(b));
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
                anchor,
                PacketDeviceTerminalAction.ACTION_SORT,
                (byte) this.sortColumn,
                (byte) (this.sortDesc ? PacketDeviceTerminalAction.DIR_DESC : PacketDeviceTerminalAction.DIR_ASC),
                (byte) 0,
                ""));
    }

    // ==================== 动作发包（列表回调；UI 只发 action） ====================

    /** 列表传送按钮回调：发包 10 action 3（服务端 3s 冷却 + 经验复查扣减权威执行）。 */
    void sendTeleport(String key) {
        if (!teleportAffordable()) return;
        sendTargetAction(PacketDeviceTerminalAction.ACTION_TELEPORT, key);
        pollTimer = 0;
    }

    /** 列表 Ctrl+点击回调：发包 10 action 2 远程解绑（无确认，服务端 removeBinding 权威执行）。 */
    void sendUnbind(String key) {
        sendTargetAction(PacketDeviceTerminalAction.ACTION_UNBIND, key);
    }

    private void sendTargetAction(byte action, String key) {
        if (anchor == null) return;
        for (Entry entry : sortedEntries) {
            if (!entry.key.equals(key)) continue;
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketDeviceTerminalAction(anchor, action, (byte) 0, (byte) 0, (byte) 0, key)
                    .targetMetaTileId(entry.metaTileId));
            return;
        }
    }

    /** Recognition preference belongs to the opened terminal; the next server snapshot updates the row. */
    void cycleRecognition(String key) {
        if (anchor == null) return;
        GTSWNPacketHandler.NETWORK.sendToServer(PacketDeviceTerminalAction.cycleRecognition(anchor, key));
    }

    // ==================== 绘制 ====================

    /**
     * 面板背景：消费 gtswn 整版贴图 panel_device_info（450×252，1:1 绘制，plan/ui/texture-list.md §2/§3 #1），
     * 几何与尺寸零变化；标题/列头两条分隔线仍代码绘制，色值琥珀化（GtswnGuiPalette.DIVIDER）。
     */
    private void drawPanelBackground() {
        fillRect(0, 0, xSize, ySize, 0xFF111820);
        fillRect(0, 0, xSize, 31, 0xFF263442);
        fillRect(0, 30, xSize, 31, 0xFF638292);
        fillRect(10, headerY + 22, xSize - 10, headerY + 23, 0xFF465563);
        String count = StatCollector.translateToLocalFormatted(
            "gtswn.device.gui.visible_count",
            sortedEntries.size(),
            displayedSnapshot == null ? 0 : displayedSnapshot.entries.size());
        if (DeviceTerminalClientCache.hasMorePending(anchor)) count += " " + StatCollector.translateToLocalFormatted(
            "gtswn.device.gui.loading_pages",
            DeviceTerminalClientCache.receivedPages(anchor),
            DeviceTerminalClientCache.totalPages(anchor));
        else if (!com.miaokatze.gtswn.network.telemetry.MonitorCapabilities
            .clientSupports(com.miaokatze.gtswn.network.telemetry.MonitorCapabilities.DIT_PAGED))
            count += " " + tr("gtswn.device.gui.legacy_limit");
        fontRendererObj.drawString(
            GuiDeviceEntryList.ellipsis(fontRendererObj, count, xSize - 202),
            12,
            headerY + 6,
            GtswnGuiPalette.TEXT_BODY);
    }

    private void drawTitle() {
        fontRendererObj.drawString(
            GuiDeviceEntryList.ellipsis(fontRendererObj, tr("gtswn.device.gui.title"), Math.max(1, xSize - 202)),
            12,
            13,
            GtswnGuiPalette.TEXT_TITLE);
    }

    private void drawBody(int mouseX, int mouseY) {
        if (displayedSnapshot == null) drawCenteredListText("...");
        else if (displayedSnapshot.entries.isEmpty()) drawCenteredListText(tr("gtswn.device.gui.no_bindings"));
        else if (sortedEntries.isEmpty()) drawCenteredListText(tr("gtswn.device.gui.no_matches"));
    }

    private void drawCenteredListText(String text) {
        int x = (xSize - fontRendererObj.getStringWidth(text)) / 2;
        int y = listTop + Math.max(12, ySize - listTop - 46) / 2 - 4;
        fontRendererObj.drawString(text, x, y, GtswnGuiPalette.TEXT_MUTED);
    }

    private void drawFooter() {
        String text = GuiDeviceEntryList.ellipsis(fontRendererObj, tr("gtswn.device.gui.footer"), xSize - 24);
        fontRendererObj.drawString(text, 12, ySize - 42, GtswnGuiPalette.TEXT_MUTED);
    }

    void selectDesktopEntry(Entry entry) {
        if (!isCurrentEntry(entry)) return;
        selectedDesktopEntry = currentEntry(entry);
    }

    boolean linkAvailable(Entry entry) {
        return isCurrentEntry(entry)
            && entry.linkSummary.state == com.miaokatze.gtswn.common.device.DeviceLinkSummary.KNOWN
            && DeviceTerminalClientCache.isFresh(anchor, System.currentTimeMillis())
            && !DeviceTerminalClientCache.hasMorePending(anchor)
            && hasLinkTerminal
            && !com.miaokatze.gtswn.client.TerminalLinkClientState.pending();
    }

    void requestLink(Entry entry, boolean desired) {
        if (linkAvailable(entry)) com.miaokatze.gtswn.client.TerminalLinkClientState.request(anchor, entry, desired);
    }

    String linkText(Entry entry) {
        com.miaokatze.gtswn.common.device.DeviceLinkSummary summary = entry.linkSummary;
        if (summary.state == com.miaokatze.gtswn.common.device.DeviceLinkSummary.NOT_APPLICABLE) return "—";
        if (summary.state == com.miaokatze.gtswn.common.device.DeviceLinkSummary.UNKNOWN
            || !DeviceTerminalClientCache.isFresh(anchor, System.currentTimeMillis())) return "?";
        return summary.connected() + "/" + summary.total();
    }

    String linkTooltip(Entry entry) {
        com.miaokatze.gtswn.network.PacketSyncDeviceLinkResult result = com.miaokatze.gtswn.client.TerminalLinkClientState
            .result(anchor, entry);
        if (result != null) return result.pending ? tr("gtswn.device.link.pending")
            : StatCollector
                .translateToLocalFormatted("gtswn.device.link.result", result.success, result.skipped, result.failed);
        return tr(!hasLinkTerminal ? "gtswn.device.link.missing_terminal" : "gtswn.device.link.count_hint");
    }

    boolean isCurrentEntry(Entry entry) {
        // Native callbacks and tooltips may outlive removeAll(), including after an epoch has been
        // refreshed. Only the actual currently published row may act; a matching old identity is not enough.
        return entryCurrent(anchor, entry, displayedAuthorizationEpoch) && currentEntry(entry) == entry
            && sortedEntries.contains(entry);
    }

    Snapshot displayedSnapshot() {
        return displayedSnapshot;
    }

    boolean displayCurrent(Snapshot snapshot) {
        return snapshot != null && snapshot == displayedSnapshot
            && snapshot == DeviceTerminalClientCache.getSnapshot(anchor)
            && displayedAuthorizationEpoch == DeviceTerminalClientCache.authorizationEpoch(anchor);
    }

    boolean entryCurrent(UUID terminal, Entry entry, long epoch) {
        return terminal != null && terminal.equals(anchor)
            && epoch == DeviceTerminalClientCache.authorizationEpoch(terminal)
            && currentEntry(entry) != null;
    }

    private Entry currentEntry(Entry entry) {
        Snapshot snapshot = anchor == null ? null : DeviceTerminalClientCache.getSnapshot(anchor);
        if (entry != null && snapshot != null)
            for (Entry current : snapshot.entries) if (sameEntry(entry, current)) return current;
        return null;
    }

    private static boolean sameEntry(Entry first, Entry second) {
        return first != null && second != null
            && first.key.equals(second.key)
            && first.dim == second.dim
            && first.x == second.x
            && first.y == second.y
            && first.z == second.z
            && first.metaTileId == second.metaTileId;
    }

    boolean desktopEntrySelected(Entry entry) {
        return sameEntry(entry, selectedDesktopEntry);
    }

    /** 行机器名列悬浮显示命名、识别模式与功率信息。 */
    List<String> entryTooltip(Entry hovered) {
        if (!isCurrentEntry(hovered)) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        lines.add(DeviceTerminalNames.localName(hovered));
        lines.add(tr("gtswn.device.rename.hover"));
        lines.add(tr("gtswn.device.favorite.hint"));
        if (!hovered.tags.isEmpty()) lines.add(tr("gtswn.device.tags.title") + ": " + hovered.tags);
        lines.add(tr("gtswn.device.gui.tooltip.recognition.cycle"));
        lines.add(
            StatCollector.translateToLocalFormatted(
                "gtswn.device.gui.tooltip.recognition.mode",
                tr(
                    hovered.recognitionMode == 2 ? "gtswn.device.gui.recognition.ignored"
                        : "gtswn.device.gui.recognition.track")));
        if (hovered.linkNodeType != 0) {
            lines.add(
                tr(
                    "gtswn.device.gui.tooltip.link."
                        + (hovered.linkNodeType == 3 ? "both" : hovered.linkNodeType == 1 ? "input" : "output")));
        }
        lines.add(
            tr(
                "gtswn.device.gui.tooltip.evidence." + (hovered.evidenceKind == 1 ? "model"
                    : hovered.evidenceKind == 2 ? "warming" : hovered.evidenceKind == 3 ? "unavailable" : "measured")));
        lines.add(
            StatCollector
                .translateToLocalFormatted("gtswn.device.gui.tooltip.input", hovered.state == 3 ? 0L : hovered.instIn));
        lines.add(
            StatCollector.translateToLocalFormatted(
                "gtswn.device.gui.tooltip.output",
                hovered.state == 3 ? 0L : hovered.instOut));
        lines.add(
            StatCollector.translateToLocalFormatted(
                "gtswn.device.gui.tooltip.net",
                hovered.state == 3 ? 0L : hovered.instNet()));
        return lines;
    }

    /**
     * 追加轮询（限频 {@value #EXTRA_POLL_INTERVAL_TICKS} tick 一次）：仅当当前终端缓存批次
     * 仍有未到齐页时发送包 8——服务端每次排水最多回 2 页（当前页 + 预取）自已发页接力，
     * 滚轮连击被限频；批次已到齐时客户端侧零发包（服务端版本跳过之外再省一道）。
     */
    void requestPendingPages() {
        if (this.extraPollCooldown > 0 || this.anchor == null) {
            return;
        }
        if (!DeviceTerminalClientCache.hasMorePending(this.anchor)) {
            return;
        }
        this.extraPollCooldown = EXTRA_POLL_INTERVAL_TICKS;
        GTSWNPacketHandler.NETWORK.sendToServer(DeviceTerminalClientCache.requestMetadata(this.anchor));
    }

    // ==================== 列表注入（数据活取 / 格式化 / 状态文案） ====================

    /** @return 排序后条目（列表绘制与命中共用，宿主 refreshEntries 后活读即时可见） */
    List<Entry> sortedEntries() {
        return this.sortedEntries;
    }

    /** @param state 状态 0/1/2/3（越界回退待机文案；lang 值自带 § 颜色码） */
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

    /** @return 传送按钮经验消耗（Config.deviceTeleportXPCost，0=免费且按钮只显示图标；服务端动作队列仍权威复查） */
    int teleportCost() {
        return DeviceTerminalClientCache.teleportCost();
    }

    /** @return 客户端玩家当前经验等级（传送按钮红/绿显示用） */
    int clientPlayerLevel() {
        EntityPlayer player = this.mc.thePlayer;
        return player == null ? 0 : player.experienceLevel;
    }

    // ==================== 列表与命名子面板的宿主访问器 ====================

    /** 字体渲染器访问（列表绘制用） */
    boolean iconMode() {
        return iconMode;
    }

    int entryWidth() {
        return Math.max(1, xSize - 30);
    }

    boolean teleportHasAnchor() {
        return inventoryTeleportAnchor || DeviceTerminalClientCache.teleportAnchors() > 0;
    }

    boolean teleportAffordable() {
        return teleportHasAnchor() || clientPlayerLevel() >= teleportCost();
    }

    int teleportColor() {
        return teleportHasAnchor() ? 0xFF53C58B : teleportAffordable() ? 0xFFF0A24B : 0xFFE06060;
    }

    String teleportHint() {
        return teleportHasAnchor() ? tr("gtswn.device.teleport.anchor_now")
            : StatCollector.translateToLocalFormatted(
                teleportAffordable() ? "gtswn.device.teleport.xp_now" : "gtswn.device.teleport.insufficient",
                teleportCost());
    }

    void toggleFavorite(Entry entry) {
        if (!isCurrentEntry(entry)) return;
        GTSWNPacketHandler.NETWORK.sendToServer(
            com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction
                .favorite(anchor, entry.key, entry.metaTileId, entry.favoriteOrder == 0));
        pollTimer = 0;
    }

    void refreshAfterEdit() {
        pollTimer = 0;
    }

    private void clearTags() {
        if (tagStrip == null || availableTags.isEmpty() && tagSignature.isEmpty()) return;
        availableTags = Collections.emptyList();
        tagSignature = "";
        tagStripDirty = true;
        if (tagPanel != null) {
            tagPanel.closePanel();
            tagPanel.deleteCachedPanel();
        }
    }

    FontRenderer font() {
        return this.fontRendererObj;
    }

    /** Minecraft 实例访问（列表滚轮坐标换算与 scissor 用） */
    Minecraft client() {
        return this.mc;
    }

    /** 矩形填充转发（列表背景/滚动条/按钮绘制用） */
    void fillRect(int x1, int y1, int x2, int y2, int color) {
        net.minecraft.client.gui.Gui.drawRect(x1, y1, x2, y2, color);
    }

    /** 绘制层级转发（Gui.zLevel 为 protected 跨包不可直引，列表组件贴图绘制用） */
    float guiZLevel() {
        return 0;
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

    void openNameEditor(Entry entry, int mouseX, int mouseY) {
        if (!isCurrentEntry(entry)) return;
        for (Widget<?> control : filterControls) ((DeviceDropdown) control).closeMenu(false);
        nameEditor.open(anchor, entry);
    }

    void closeNameEditor() {
        if (nameEditor != null) nameEditor.close();
    }

    @Override
    public void onClose() {
        closeNameEditor();
        DesktopDeviceSources.nativeViewer(null);
        super.onClose();
    }
}
