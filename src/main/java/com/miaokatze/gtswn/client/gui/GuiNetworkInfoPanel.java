package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.panel.NetworkChartStyle;
import com.miaokatze.gtswn.common.panel.WindowLabel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketUpdateAETabState;
import com.miaokatze.gtswn.network.PacketUpdateNetworkInfoPanelConfig;

public class GuiNetworkInfoPanel extends GtswnMuiScreen {

    private final TileEntityNetworkInfoPanel panel;
    private int left;
    private int top;
    private final int xSize = 430;
    private final int ySize = 285;
    private final ParentWidget<?> content = new ParentWidget<>().name("panel.body")
        .size(430, 285);
    private final Map<Integer, ButtonWidget<?>> buttons = new LinkedHashMap<>();
    private boolean refreshPending;
    // AE 标签页按钮 ID 常量
    private static final int TAB_EU = 100;
    private static final int TAB_AE_CHART = 101;
    private static final int TAB_AE_MONITOR = 102;
    private final List<TextFieldWidget> textFields = new ArrayList<>();
    private TextFieldWidget energyMinField;
    private TextFieldWidget energyMaxField;
    private TextFieldWidget eutMinField;
    private TextFieldWidget eutMaxField;
    private TextFieldWidget borderField;
    private TextFieldWidget chartBgField;
    private TextFieldWidget lineField;
    private TextFieldWidget smoothingField;
    private TextFieldWidget screenColorField;
    private static final int PALETTE_PAGE = 90;
    private static final int PALETTE_TOGGLE = 91;
    private static final int CHART_STYLE = 92;
    private NetworkChartStyle chartStyleDraft;
    private static final String[] PALETTE_KEYS = { "energyFlatColor", "energyUpColor", "energyDownColor",
        "rateFlatColor", "rateUpColor", "rateDownColor" };
    private final TextFieldWidget[] paletteFields = new TextFieldWidget[6];
    private boolean palettePage, paletteEnabled;
    private String chartDraft = "";
    private String paletteDraft = "";

    // AE 图表配置文本框（tab=1）
    private TextFieldWidget aeAxisMinField;
    private TextFieldWidget aeAxisMaxField;
    private TextFieldWidget aeChartBorderField;
    private TextFieldWidget aeTrendLineField;
    private TextFieldWidget aeSmoothingField;
    private TextFieldWidget aeBgField;
    private TextFieldWidget aeLineColorField;

    /** 客户端上一次渲染时使用的标签页，用于检测服务端同步后重建控件 */
    private int lastKnownTab = -1;
    /** 用户点击切页后暂存的目标标签页，避免服务端回包前被旧值覆盖 */
    private int pendingTab = -1;

    /** AE 监控列表当前渲染的条目（ItemStack 或 FluidStack），用于 actionPerformed 中定位移除目标 */
    private Object[] monitoredEntries = new Object[0];

    // AE 标签页控件 ID
    private static final int AE_APPLY_BUTTON = 11;
    private static final int AE_DISPLAY_MODE_BUTTON = 12;
    private static final int AE_CLEAR_ALL_BUTTON = 13;
    /** 仅显示当前 AE 时长，无动作 */
    private static final int AE_WINDOW_LABEL_BUTTON = 24;
    /** AE 时长 -（切换到上一个窗口） */
    private static final int AE_WINDOW_PREV_BUTTON = 25;
    /** AE 时长 +（切换到下一个窗口） */
    private static final int AE_WINDOW_NEXT_BUTTON = 26;
    /** AE 走势图简报开关 */
    private static final int AE_BRIEF_BUTTON = 20;
    /** AE 走势图存量曲线开关 */
    private static final int AE_CHART_AMOUNT_BUTTON = 21;
    /** AE 走势图变化率曲线开关 */
    private static final int AE_CHART_RATE_BUTTON = 22;

    // AE 实时监控配置按钮 ID
    private static final int AE_MONITOR_FONT_SIZE_MINUS = 30;
    private static final int AE_MONITOR_FONT_SIZE_PLUS = 31;
    private static final int AE_MONITOR_BOLD_BUTTON = 32;
    private static final int AE_MONITOR_RENDER_MODE_BUTTON = 33;
    private static final int AE_MONITOR_ICON_SIZE_MINUS = 34;
    private static final int AE_MONITOR_ICON_SIZE_PLUS = 35;
    /** 字号标签按钮，仅显示当前字号，无动作 */
    private static final int AE_MONITOR_FONT_SIZE_LABEL = 36;
    /** 图标大小标签按钮，仅显示当前图标大小，无动作 */
    private static final int AE_MONITOR_ICON_SIZE_LABEL = 37;

    /** AE 实时监控自定义滚动列表控件（不依赖 GuiSlot） */
    private GuiAEMonitorList aeMonitorList;

    public GuiNetworkInfoPanel(TileEntityNetworkInfoPanel panel) {
        super("network_info_panel", 430, 285);
        this.panel = panel;
        getMainPanel().child(content);
        buildPanel();
    }

    private void buildPanel() {
        content.removeAll();
        buttons.clear();
        textFields.clear();
        left = top = 0;
        content.child(
            GtswnMuiWidgets.canvas(
                "panel.content",
                0,
                0,
                xSize,
                ySize,
                (context, mouseX, mouseY, partialTicks) -> drawCanvas(mouseX, mouseY, partialTicks)));

        int tabY = top + 28;
        int initTab = lastKnownTab >= 0 ? lastKnownTab : panel.getCurrentTab();
        addButton(TAB_EU, left + 12, tabY, 130, 16, tr("gtswn.network_info.gui.tab.eu"));
        addButton(TAB_AE_CHART, left + 150, tabY, 130, 16, tr("gtswn.network_info.gui.tab.ae_chart"));
        addButton(TAB_AE_MONITOR, left + 288, tabY, 130, 16, tr("gtswn.network_info.gui.tab.ae_monitor"));

        int activeTab = lastKnownTab >= 0 ? lastKnownTab : panel.getCurrentTab();
        if (activeTab == 0) {
            // EU 标签页：原有按钮 0-8 + textFields
            initEUTabControls();
        } else if (activeTab == 1) {
            // AE 走势图标签页：改为纯配置面板
            initAEChartTabControls();
        } else if (activeTab == 2) {
            // AE 实时监控标签页：改为管理面板
            initAEMonitorTabControls();
        }
        lastKnownTab = activeTab;
        if (content.isValid()) content.scheduleResize();
    }

    /** 初始化 EU 标签页原有按钮 0-8 与 textFields（从原 initGui 抽取） */
    private void initEUTabControls() {
        if (chartStyleDraft == null) chartStyleDraft = panel.getChartStyle();
        addButton(CHART_STYLE, left + 12, top + 253, 150, 16, getChartStyleText());
        addButton(
            PALETTE_PAGE,
            left + 320,
            top + 253,
            98,
            16,
            tr(palettePage ? "gtswn.network_info.gui.chart_settings" : "gtswn.network_info.gui.trend_colors"));
        if (palettePage) {
            initPaletteControls();
            return;
        }
        int y1 = top + 52;
        int y2 = top + 72;
        addButton(0, left + 12, y1, 94, 16, bool(tr("gtswn.network_info.gui.brief.eu"), panel.isShowBriefEnergy()));
        addButton(
            1,
            left + 110,
            y1,
            94,
            16,
            bool(tr("gtswn.network_info.gui.brief.status"), panel.isShowBriefStatus()));
        addButton(2, left + 208, y1, 94, 16, bool(tr("gtswn.network_info.gui.chart.eu"), panel.isShowChartEnergy()));
        addButton(3, left + 306, y1, 112, 16, bool(tr("gtswn.network_info.gui.chart.eut"), panel.isShowChartStatus()));
        addButton(4, left + 12, y2, 112, 16, tr("gtswn.network_info.gui.window") + ": " + panel.getWindowName());
        addButton(5, left + 130, y2, 24, 16, "-");
        addButton(6, left + 158, y2, 24, 16, "+");
        addButton(7, left + 188, y2, 74, 16, getDisplayModeButtonText(panel.getDisplayMode()));
        addButton(8, left + 344, y2, 74, 16, tr("gtswn.network_info.gui.apply"));
        // 样条类型按钮：位于平滑强度输入框正上方，点击轮换 0过点/1拟合/2折线/3数字
        addButton(9, left + 372, top + 187, 46, 16, getSplineTypeName(panel.getTrendLineSplineType()));

        int fieldY = top + 165;
        energyMinField = addField(left + 104, fieldY, 66, panel.getEnergyAxisMinText());
        energyMaxField = addField(left + 250, fieldY, 66, panel.getEnergyAxisMaxText());
        fieldY += 22;
        eutMinField = addField(left + 104, fieldY, 66, panel.getEutAxisMinText());
        eutMaxField = addField(left + 250, fieldY, 66, panel.getEutAxisMaxText());
        fieldY += 22;
        borderField = addField(left + 104, fieldY, 46, String.valueOf(panel.getChartBorderThickness()));
        lineField = addField(left + 250, fieldY, 46, String.valueOf(panel.getTrendLineThickness()));
        smoothingField = addField(left + 372, fieldY + 10, 46, String.valueOf(panel.getTrendLineSmoothing()));
        fieldY += 22;
        chartBgField = addField(left + 104, fieldY, 66, panel.getChartBackgroundColorText());
        screenColorField = addField(left + 250, fieldY, 66, panel.getScreenBackgroundColorText());
        String[] keys = { "energyMin", "energyMax", "eutMin", "eutMax", "border", "chartBg", "line", "smoothing",
            "screenColor" };
        TextFieldWidget[] fields = { energyMinField, energyMaxField, eutMinField, eutMaxField, borderField,
            chartBgField, lineField, smoothingField, screenColorField };
        for (String entry : chartDraft.split("\n")) {
            int split = entry.indexOf('=');
            if (split < 0) continue;
            for (int i = 0; i < keys.length; i++)
                if (keys[i].equals(entry.substring(0, split))) setFieldText(fields[i], entry.substring(split + 1));
        }

    }

    /** EU direction colours use their own page so all six native fields fit the existing canvas. */
    private void initPaletteControls() {
        com.miaokatze.gtswn.common.panel.TrendLinePalette palette = panel.getTrendLinePalette();
        paletteEnabled = palette.enabled;
        String[] values = { com.miaokatze.gtswn.common.panel.PanelConfigStore.colorText(palette.energyFlat),
            com.miaokatze.gtswn.common.panel.PanelConfigStore.colorText(palette.energyUp),
            com.miaokatze.gtswn.common.panel.PanelConfigStore.colorText(palette.energyDown),
            com.miaokatze.gtswn.common.panel.PanelConfigStore.colorText(palette.rateFlat),
            com.miaokatze.gtswn.common.panel.PanelConfigStore.colorText(palette.rateUp),
            com.miaokatze.gtswn.common.panel.PanelConfigStore.colorText(palette.rateDown) };
        for (String line : paletteDraft.split("\n")) {
            int split = line.indexOf('=');
            if (split < 0) continue;
            String key = line.substring(0, split), value = line.substring(split + 1);
            if ("trendDirectionColors".equals(key)) paletteEnabled = Boolean.parseBoolean(value);
            for (int i = 0; i < PALETTE_KEYS.length; i++) {
                if (PALETTE_KEYS[i].equals(key)) values[i] = value;
            }
        }
        addButton(
            PALETTE_TOGGLE,
            left + 12,
            top + 52,
            230,
            16,
            bool(tr("gtswn.network_info.gui.trend_regions"), paletteEnabled));
        addButton(8, left + 344, top + 52, 74, 16, tr("gtswn.network_info.gui.apply"));
        for (int i = 0; i < paletteFields.length; i++) {
            paletteFields[i] = addField(left + 130 + i % 3 * 94, top + 120 + i / 3 * 48, 70, values[i]);
            paletteFields[i].setMaxLength(7);
        }
    }

    private String buildPaletteConfig() {
        StringBuilder payload = new StringBuilder("trendDirectionColors=" + paletteEnabled);
        for (int i = 0; i < PALETTE_KEYS.length; i++) {
            payload.append('\n')
                .append(PALETTE_KEYS[i])
                .append('=')
                .append(paletteFields[i].getText());
        }
        return payload.toString();
    }

    private void drawPaletteSettings() {
        drawFieldLabel(tr("gtswn.network_info.gui.trend_flat"), left + 130, top + 106);
        drawFieldLabel(tr("gtswn.network_info.gui.trend_up"), left + 224, top + 106);
        drawFieldLabel(tr("gtswn.network_info.gui.trend_down"), left + 318, top + 106);
        drawFieldLabel(tr("gtswn.network_info.gui.chart.eu"), left + 12, top + 124);
        drawFieldLabel(tr("gtswn.network_info.gui.chart.eut"), left + 12, top + 172);
        drawFieldLabel(tr("gtswn.network_info.gui.trend_color_hint"), left + 12, top + 210);
    }

    /** 初始化 AE 走势图配置标签页控件（tab=1），布局与 EU 标签页对称 */
    private void initAEChartTabControls() {
        int row1Y = top + 52;
        int row2Y = top + 74;

        // 第一行：简报 / 存量 / 变化率 / 显示模式
        addButton(
            AE_BRIEF_BUTTON,
            left + 12,
            row1Y,
            94,
            16,
            bool(tr("gtswn.network_info.gui.ae.brief"), panel.isShowAEBrief()));
        addButton(
            AE_CHART_AMOUNT_BUTTON,
            left + 110,
            row1Y,
            94,
            16,
            bool(tr("gtswn.network_info.gui.ae.chart.amount"), panel.isShowAEChartAmount()));
        addButton(
            AE_CHART_RATE_BUTTON,
            left + 208,
            row1Y,
            94,
            16,
            bool(tr("gtswn.network_info.gui.ae.chart.rate"), panel.isShowAEChartRate()));
        addButton(AE_DISPLAY_MODE_BUTTON, left + 306, row1Y, 112, 16, getDisplayModeButtonText(panel.getDisplayMode()));

        // 第二行：AE 时长显示 / - / + / 应用
        addButton(AE_WINDOW_LABEL_BUTTON, left + 12, row2Y, 130, 16, getAEWindowDisplay(panel.getAETrackingWindow()));
        addButton(AE_WINDOW_PREV_BUTTON, left + 146, row2Y, 24, 16, "-");
        addButton(AE_WINDOW_NEXT_BUTTON, left + 174, row2Y, 24, 16, "+");
        addButton(AE_APPLY_BUTTON, left + 344, row2Y, 74, 16, tr("gtswn.network_info.gui.apply"));
        // AE 样条类型按钮：位于 aeSmoothingField 输入框正上方，点击轮换 0过点/1拟合/2折线/3数字
        addButton(27, left + 372, top + 165, 46, 16, getSplineTypeName(panel.getAETrendLineSplineType()));

        // 配置文本框整体下移，避免与按钮行重叠并和 EU 标签页对称
        int fieldY = top + 165;
        aeAxisMinField = addField(left + 104, fieldY, 66, panel.getAEAxisMinText());
        aeAxisMaxField = addField(left + 250, fieldY, 66, panel.getAEAxisMaxText());
        fieldY += 22;
        aeChartBorderField = addField(left + 104, fieldY, 46, String.valueOf(panel.getAEChartBorderThickness()));
        aeTrendLineField = addField(left + 250, fieldY, 46, String.valueOf(panel.getAETrendLineThickness()));
        aeSmoothingField = addField(left + 372, fieldY + 10, 46, String.valueOf(panel.getAETrendLineSmoothing()));
        fieldY += 22;
        aeBgField = addField(left + 104, fieldY, 66, panel.getAEChartBackgroundColorText());
        aeLineColorField = addField(left + 250, fieldY, 66, panel.getAELineColorText());
    }

    /** 初始化 AE 实时监控管理标签页控件（tab=2） */
    @SuppressWarnings("unchecked")
    private void initAEMonitorTabControls() {
        // 区域 2：第一排配置按钮（显示模式、字号、加粗、呈现方式）
        int row1Y = top + 52;
        addButton(AE_DISPLAY_MODE_BUTTON, left + 12, row1Y, 100, 16, getDisplayModeButtonText(panel.getDisplayMode()));
        // 字号标签与 -/+
        addButton(
            AE_MONITOR_FONT_SIZE_LABEL,
            left + 116,
            row1Y,
            70,
            16,
            tr("gtswn.network_info.gui.ae.monitor.font_size") + ": " + panel.getAEMonitorFontSize());
        addButton(AE_MONITOR_FONT_SIZE_MINUS, left + 190, row1Y, 24, 16, "-");
        addButton(AE_MONITOR_FONT_SIZE_PLUS, left + 218, row1Y, 24, 16, "+");
        // 加粗开关
        addButton(
            AE_MONITOR_BOLD_BUTTON,
            left + 246,
            row1Y,
            70,
            16,
            tr("gtswn.network_info.gui.ae.monitor.bold") + " "
                + (panel.isAEMonitorBold() ? tr("gtswn.network_info.gui.on") : tr("gtswn.network_info.gui.off")));
        // 呈现方式切换
        addButton(
            AE_MONITOR_RENDER_MODE_BUTTON,
            left + 320,
            row1Y,
            90,
            16,
            panel.getAEMonitorRenderMode() == 0 ? tr("gtswn.network_info.gui.ae.monitor.render_mode.list")
                : tr("gtswn.network_info.gui.ae.monitor.render_mode.grid"));

        // 区域 2：第二排图标大小配置
        int row2Y = top + 74;
        addButton(
            AE_MONITOR_ICON_SIZE_LABEL,
            left + 12,
            row2Y,
            80,
            16,
            tr("gtswn.network_info.gui.ae.monitor.icon_size") + ": " + panel.getAEMonitorIconSize());
        addButton(AE_MONITOR_ICON_SIZE_MINUS, left + 96, row2Y, 24, 16, "-");
        addButton(AE_MONITOR_ICON_SIZE_PLUS, left + 124, row2Y, 24, 16, "+");

        // 区域 3：全部清除按钮（向下移动 10px，避免与简报区 top+100 贴边）
        addButton(AE_CLEAR_ALL_BUTTON, left + 318, top + 112, 100, 16, tr("gtswn.network_info.gui.ae.clear_all"));

        // 构造当前监控条目数组，供滚动列表使用
        refreshMonitoredEntries();

        // 区域 4：滚动列表区（top+148 到 top+255，行高 20）
        // O2-07：内嵌类提级为顶级包私有 GuiAEMonitorList，几何/条目/回包经构造注入
        aeMonitorList = new GuiAEMonitorList(
            this,
            panel,
            left,
            top,
            xSize,
            () -> monitoredEntries,
            this::sendMonitorToggle);
        content.child(aeMonitorList);
    }

    /** 从 panel 刷新监控条目数组 */
    private boolean refreshMonitoredEntries() {
        List<ItemStack> items = panel.getMonitoredItems();
        List<FluidStack> fluids = panel.getMonitoredFluids();
        int total = items.size() + fluids.size();
        Object[] next = new Object[total];
        int idx = 0;
        for (ItemStack stack : items) {
            next[idx++] = stack;
        }
        for (FluidStack fluid : fluids) {
            next[idx++] = fluid;
        }
        boolean changed = next.length != monitoredEntries.length;
        for (int i = 0; !changed && i < next.length; i++) {
            changed = !java.util.Objects.equals(entryKey(next[i]), entryKey(monitoredEntries[i]));
        }
        if (changed) monitoredEntries = next;
        return changed;
    }

    private static String entryKey(Object entry) {
        return entry instanceof ItemStack ? TileEntityNetworkInfoPanel.getAEKey((ItemStack) entry)
            : TileEntityNetworkInfoPanel.getAEKey((FluidStack) entry);
    }

    private void action(int id) {
        if (id == CHART_STYLE) {
            chartStyleDraft = NetworkChartStyle.fromId((chartStyleDraft.ordinal() + 1) % 3);
            buttons.get(id)
                .overlay(IKey.str(getChartStyleText()));
            return;
        }
        if (id == PALETTE_PAGE) {
            if (palettePage) paletteDraft = buildPaletteConfig();
            else chartDraft = buildChartConfig();
            palettePage = !palettePage;
            refreshPending = true;
            return;
        }
        if (id == PALETTE_TOGGLE) {
            paletteEnabled = !paletteEnabled;
            buttons.get(id)
                .overlay(IKey.str(bool(tr("gtswn.network_info.gui.trend_regions"), paletteEnabled)));
            return;
        }

        if (id == TAB_EU || id == TAB_AE_CHART || id == TAB_AE_MONITOR) {
            int newTab = (id == TAB_EU) ? 0 : (id == TAB_AE_CHART ? 1 : 2);
            // 本地立即重建，服务端 setCurrentTab 后通过 getDescriptionPacket 回写，updateScreen 会再次比对确保一致
            pendingTab = newTab;
            lastKnownTab = newTab;
            refreshPending = true;
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateAETabState(panel.xCoord, panel.yCoord, panel.zCoord, (byte) 0, newTab, null));
            return;
        }
        if (id == 8) {
            // EU 图表配置应用
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(
                    panel.xCoord,
                    panel.yCoord,
                    panel.zCoord,
                    (palettePage ? chartDraft + "\n" + buildPaletteConfig() : buildChartConfig() + "\n" + paletteDraft)
                        + "\nchartStyle="
                        + chartStyleDraft.ordinal()));
        } else if (id == AE_APPLY_BUTTON) {
            // AE 图表配置应用
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(
                    panel.xCoord,
                    panel.yCoord,
                    panel.zCoord,
                    buildAEChartConfig(),
                    true));
        } else if (id == AE_DISPLAY_MODE_BUTTON) {
            // AE 标签页的显示模式切换复用 EU 的 action 7
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, 7));
        } else if (id == AE_CLEAR_ALL_BUTTON) {
            // 一键清除全部物品+流体监控
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateAETabState(panel.xCoord, panel.yCoord, panel.zCoord, (byte) 6, 0, null));
        } else if (id == AE_MONITOR_FONT_SIZE_LABEL || id == AE_MONITOR_ICON_SIZE_LABEL) {
            // 字号标签、图标大小标签仅作显示，无动作
            return;
        } else if (id >= AE_MONITOR_FONT_SIZE_MINUS && id <= AE_MONITOR_ICON_SIZE_PLUS) {
            // AE 实时监控配置按钮（字号、加粗、呈现方式、图标大小）
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, id));
        } else if (id == 9 || id == 27) {
            // 样条类型轮换：action 号与按钮 id 一致（9=EU，27=AE），服务端 Store 内取模轮换；
            // 本地按下一状态即时刷新按钮文本，服务端回包后由 initGui 重建控件校正
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, id));
            int current = id == 9 ? panel.getTrendLineSplineType() : panel.getAETrendLineSplineType();
            buttons.get(id)
                .overlay(IKey.str(getSplineTypeName((current + 1) % 4)));
        } else if (id == 20 || id == 21 || id == 22 || id == 24 || id == 25 || id == 26) {
            // AE 走势图开关、检测时长标签（切换窗口）与字号 +/- 直接走通用 action 包
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, id));
        } else {
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, id));
        }
    }

    /** 发送指定索引监控条目的移除包（action 3=物品，4=流体） */
    private void sendMonitorToggle(int idx) {
        if (idx < 0 || idx >= monitoredEntries.length) {
            return;
        }
        Object entry = monitoredEntries[idx];
        NBTTagCompound data = new NBTTagCompound();
        byte action;
        if (entry instanceof ItemStack) {
            ((ItemStack) entry).writeToNBT(data);
            action = 3;
        } else if (entry instanceof FluidStack) {
            ((FluidStack) entry).writeToNBT(data);
            action = 4;
        } else {
            return;
        }
        GTSWNPacketHandler.NETWORK
            .sendToServer(new PacketUpdateAETabState(panel.xCoord, panel.yCoord, panel.zCoord, action, 0, data));
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (refreshPending) {
            refreshPending = false;
            buildPanel();
        }
        int serverTab = panel.getCurrentTab();
        if (pendingTab == -1) {
            if (serverTab != lastKnownTab) {
                lastKnownTab = serverTab;
                refreshPending = true;
                return;
            }
        } else if (pendingTab == serverTab) {
            pendingTab = -1;
            if (serverTab != lastKnownTab) {
                lastKnownTab = serverTab;
                refreshPending = true;
                return;
            }
        }
        // Reconcile identities too: replacing an entry need not change the list length.
        if (lastKnownTab == 2 && aeMonitorList != null && refreshMonitoredEntries()) aeMonitorList.clampScroll();
        for (Map.Entry<Integer, ButtonWidget<?>> entry : buttons.entrySet()) {
            int id = entry.getKey();
            ButtonWidget<?> button = entry.getValue();
            if (id >= TAB_EU && id <= TAB_AE_MONITOR) {
                button.background(id - TAB_EU == lastKnownTab ? GtswnMuiTheme.TAB_ACTIVE : GtswnMuiTheme.TAB_INACTIVE);
            }
            switch (id) {
                case TAB_EU:
                    button.overlay(IKey.str(tr("gtswn.network_info.gui.tab.eu")));
                    break;
                case TAB_AE_CHART:
                    button.overlay(IKey.str(tr("gtswn.network_info.gui.tab.ae_chart")));
                    break;
                case TAB_AE_MONITOR:
                    button.overlay(IKey.str(tr("gtswn.network_info.gui.tab.ae_monitor")));
                    break;
                case 0:
                    button.overlay(IKey.str(bool(tr("gtswn.network_info.gui.brief.eu"), panel.isShowBriefEnergy())));
                    break;
                case 1:
                    button
                        .overlay(IKey.str(bool(tr("gtswn.network_info.gui.brief.status"), panel.isShowBriefStatus())));
                    break;
                case 2:
                    button.overlay(IKey.str(bool(tr("gtswn.network_info.gui.chart.eu"), panel.isShowChartEnergy())));
                    break;
                case 3:
                    button.overlay(IKey.str(bool(tr("gtswn.network_info.gui.chart.eut"), panel.isShowChartStatus())));
                    break;
                case 4:
                    button.overlay(IKey.str(tr("gtswn.network_info.gui.window") + ": " + panel.getWindowName()));
                    break;
                case 7:
                    button.overlay(IKey.str(getDisplayModeButtonText(panel.getDisplayMode())));
                    break;
                case AE_BRIEF_BUTTON:
                    button.overlay(IKey.str(bool(tr("gtswn.network_info.gui.ae.brief"), panel.isShowAEBrief())));
                    break;
                case AE_CHART_AMOUNT_BUTTON:
                    button.overlay(
                        IKey.str(bool(tr("gtswn.network_info.gui.ae.chart.amount"), panel.isShowAEChartAmount())));
                    break;
                case AE_CHART_RATE_BUTTON:
                    button
                        .overlay(IKey.str(bool(tr("gtswn.network_info.gui.ae.chart.rate"), panel.isShowAEChartRate())));
                    break;
                case AE_WINDOW_LABEL_BUTTON:
                    button.overlay(IKey.str(getAEWindowDisplay(panel.getAETrackingWindow())));
                    break;
                case AE_DISPLAY_MODE_BUTTON:
                    button.overlay(IKey.str(getDisplayModeButtonText(panel.getDisplayMode())));
                    break;
                case AE_MONITOR_FONT_SIZE_LABEL:
                    button.overlay(
                        IKey.str(
                            tr("gtswn.network_info.gui.ae.monitor.font_size") + ": " + panel.getAEMonitorFontSize()));
                    break;
                case AE_MONITOR_BOLD_BUTTON:
                    button.overlay(
                        IKey.str(
                            tr("gtswn.network_info.gui.ae.monitor.bold") + " "
                                + (panel.isAEMonitorBold() ? tr("gtswn.network_info.gui.on")
                                    : tr("gtswn.network_info.gui.off"))));
                    break;
                case AE_MONITOR_RENDER_MODE_BUTTON:
                    button.overlay(
                        IKey.str(
                            panel.getAEMonitorRenderMode() == 0
                                ? tr("gtswn.network_info.gui.ae.monitor.render_mode.list")
                                : tr("gtswn.network_info.gui.ae.monitor.render_mode.grid")));
                    break;
                case AE_MONITOR_ICON_SIZE_LABEL:
                    button.overlay(
                        IKey.str(
                            tr("gtswn.network_info.gui.ae.monitor.icon_size") + ": " + panel.getAEMonitorIconSize()));
                    break;
                default:
                    break;
            }
        }
    }

    private void drawCanvas(int mouseX, int mouseY, float partialTicks) {
        // 移除 drawDefaultBackground()，避免 AE 实时监控等标签页出现巨大 dirt 背景；
        // 面板背景由 drawPanelBackground() 统一绘制。
        drawTitleBar();
        // 标签页按钮始终绘制（已由 super.drawScreen 处理）
        int activeTab = lastKnownTab >= 0 ? lastKnownTab : panel.getCurrentTab();
        if (activeTab == 0) {
            drawEUTab();
        } else if (activeTab == 1) {
            drawAEChartTab();
        } else if (activeTab == 2) {
            drawAEMonitorTab();
        }
    }

    /** 绘制 EU 标签页（原 drawScreen 主体内容，去掉 super.drawScreen 与 textField 绘制） */
    private void drawEUTab() {
        if (palettePage) {
            drawPaletteSettings();
            return;
        }

        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.owner", safe(panel.getOwnerName())),
            left + 12,
            top + 108,
            GtswnGuiPalette.TEXT_BODY);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(
                "gtswn.network_info.energy",
                formatEU(panel.getCachedEu(), panel.getDisplayMode())),
            left + 12,
            top + 122,
            GtswnGuiPalette.TEXT_BODY);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.status", panel.getCachedStatus()),
            left + 12,
            top + 136,
            GtswnGuiPalette.TEXT_BODY);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.brief_ratio", panel.getBriefRatio()),
            left + 272,
            top + 94,
            GtswnGuiPalette.TEXT_BODY);
        drawChartSettings();
    }

    /** 绘制 AE 走势图配置标签页（v1.5.5 改为纯配置界面，数据展示由 TESR 负责） */
    private void drawAEChartTab() {
        ItemStack item = panel.getChartItem();
        FluidStack fluid = panel.getChartFluid();
        String coverage = StatCollector.translateToLocalFormatted(
            "gtswn.network_info.gui.ae.window.actual",
            WindowLabel.actualSpan(panel.getAEChartSamples()));
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(coverage, 134),
            left + 204,
            top + 78,
            GtswnGuiPalette.TEXT_BODY);

        // 未绑定：显示空提示与右键配置提示
        if (item == null && fluid == null) {
            fontRendererObj.drawString(
                tr("gtswn.network_info.gui.ae.chart.empty"),
                left + 12,
                top + 110,
                GtswnGuiPalette.TEXT_MUTED);
            fontRendererObj.drawString(
                tr("gtswn.network_info.gui.ae.rightclick_hint"),
                left + 12,
                top + 130,
                GtswnGuiPalette.TEXT_MUTED);
            // 即使未绑定也绘制配置项标签
            drawAEChartSettings();
            return;
        }

        // 绑定后：在第三区域（top+96 ~ top+144）绘制图标与名称
        int iconX = left + 12;
        int iconY = top + 108;
        String name;
        if (item != null) {
            drawItemIcon(item, iconX, iconY);
            name = item.getDisplayName();
        } else {
            drawFluidIcon(fluid, iconX, iconY);
            name = fluid.getLocalizedName();
        }
        // 名称最大宽度：从 left+32+8 到 left+xSize-8-8，约 382
        int nameMaxW = left + xSize - 8 - 8 - (left + 32);
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(name, nameMaxW),
            left + 32,
            top + 112,
            GtswnGuiPalette.TEXT_BODY);

        // 配置项标签
        drawAEChartSettings();
    }

    /** 绘制 AE 实时监控管理标签页（v1.5.5 改为四区域布局） */
    private void drawAEMonitorTab() {
        // 区域 1：标签页由 super.drawScreen 绘制

        // 区域 2：按钮配置区已在 initAEMonitorTabControls 创建

        // 区域 3：简报区（AE 在线状态、监控项总数），仅 GUI 显示，不进入 TESR
        int briefY = top + 100;
        boolean online = hasAEMonitorData(panel);
        String statusKey = online ? "gtswn.network_info.screen.ae_online" : "gtswn.network_info.screen.ae_offline";
        // 在线/离线状态色：GtswnGuiPalette.STATE_ONLINE / STATE_OFFLINE（契约 §5）
        int statusColor = online ? GtswnGuiPalette.STATE_ONLINE : GtswnGuiPalette.STATE_OFFLINE;
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(
                "gtswn.network_info.gui.ae.monitor.brief",
                tr(statusKey),
                monitoredEntries.length),
            left + 12,
            briefY,
            statusColor);

        // 区域 4：表头（滚动列表内部绘制行内容：图标/名称/数量/实时变化量/平均变化量/清除按钮）
        // headerY 下移 15px：表头与列表整体下移，留出更多顶部空间
        int headerY = top + 153;
        int listLeft = left + 8;
        int listRight = left + xSize - 8;
        fontRendererObj.drawString(
            tr("gtswn.network_info.gui.ae.monitor.header_icon"),
            listLeft + 4,
            headerY,
            GtswnGuiPalette.TEXT_LABEL);
        fontRendererObj.drawString(
            tr("gtswn.network_info.gui.ae.monitor.header_name"),
            listLeft + 22,
            headerY,
            GtswnGuiPalette.TEXT_LABEL);
        fontRendererObj.drawString(
            tr("gtswn.network_info.gui.ae.monitor.header_amount"),
            listLeft + 110,
            headerY,
            GtswnGuiPalette.TEXT_LABEL);
        fontRendererObj.drawString(
            tr("gtswn.network_info.gui.ae.monitor.header_realtime"),
            listLeft + 170,
            headerY,
            GtswnGuiPalette.TEXT_LABEL);
        fontRendererObj.drawString(
            tr("gtswn.network_info.gui.ae.monitor.header_average"),
            listLeft + 260,
            headerY,
            GtswnGuiPalette.TEXT_LABEL);
        fontRendererObj.drawString(
            tr("gtswn.network_info.gui.ae.monitor.header_remove"),
            listRight - 56,
            headerY,
            GtswnGuiPalette.TEXT_LABEL);

        // 空列表提示（覆盖在列表上方）
        if (monitoredEntries.length == 0) {
            fontRendererObj.drawString(
                tr("gtswn.network_info.gui.ae.monitor.empty"),
                left + 12,
                top + 185,
                GtswnGuiPalette.TEXT_MUTED);
        }
    }

    /** 在面板最左上角绘制统一的“网络信息屏”标题栏 */
    private void drawTitleBar() {
        String title = EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.title");
        int titleWidth = fontRendererObj.getStringWidth(title);
        int boxPadding = 6;
        int boxX = left + 8;
        int boxY = top + 6;
        int boxW = titleWidth + boxPadding * 2;
        int boxH = 14;
        // 标题栏 chip：9-slice 贴图（chip_active 32×16，切片 4px，boxH=14 纵向拉伸，契约 §3 #10）
        GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.CHIP_ACTIVE, 4, boxX, boxY, boxW, boxH, 0);
        fontRendererObj.drawString(title, boxX + boxPadding, boxY + 3, 0xFFFFFF);
    }

    private TextFieldWidget addField(int x, int y, int w, String value) {
        TextFieldWidget field = GtswnMuiWidgets
            .textField("panel.field." + textFields.size(), value == null ? "" : value, x, y, w, 16, 16);
        textFields.add(field);
        content.child(field);
        return field;
    }

    private void addButton(int id, int x, int y, int width, int height, String label) {
        ButtonWidget<?> button = GtswnMuiWidgets.button(buttonName(id), label, x, y, width, height, () -> action(id));
        if (id >= TAB_EU && id <= TAB_AE_MONITOR) {
            int currentTab = lastKnownTab < 0 ? panel.getCurrentTab() : lastKnownTab;
            button.background(id - TAB_EU == currentTab ? GtswnMuiTheme.TAB_ACTIVE : GtswnMuiTheme.TAB_INACTIVE);
        }
        buttons.put(id, button);
        content.child(button);
    }

    private static String buttonName(int id) {
        switch (id) {
            case TAB_EU:
                return "panel.tab.eu";
            case TAB_AE_CHART:
                return "panel.tab.ae";
            case TAB_AE_MONITOR:
                return "panel.tab.monitor";
            case PALETTE_PAGE:
                return "panel.palette";
            default:
                return "panel.action." + id;
        }
    }

    private static void setFieldText(TextFieldWidget field, String text) {
        field.value(new StringValue(text));
        field.setText(text);
    }

    private void drawChartSettings() {
        int x = left + 12;
        int y = top + 149;
        fontRendererObj.drawString(
            EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.chart_settings"),
            x,
            y,
            GtswnGuiPalette.TEXT_BODY);
        y += 16;
        drawFieldLabel(tr("gtswn.network_info.gui.energy_y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.energy_y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.eut_y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.eut_y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.border_width"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.line_width"), left + 180, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.smoothing"), left + 326, y - 2);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.chart_bg"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.screen_bg"), left + 180, y + 4);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.blank_auto"), left + 326, y + 4, GtswnGuiPalette.TEXT_MUTED);
    }

    /** 绘制 AE 走势图配置项标签（与 EU 的 drawChartSettings 布局对称，整体下移避免与按钮行重叠） */
    private void drawAEChartSettings() {
        int x = left + 12;
        int y = top + 149;
        fontRendererObj.drawString(
            EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.ae.chart_settings"),
            x,
            y,
            GtswnGuiPalette.TEXT_BODY);
        y += 16;
        drawFieldLabel(tr("gtswn.network_info.gui.ae_y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.ae_y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.border_width"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.line_width"), left + 180, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.smoothing"), left + 326, y - 2);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.chart_bg"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.ae.line_color"), left + 180, y + 4);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.blank_auto"), left + 326, y + 4, GtswnGuiPalette.TEXT_MUTED);
    }

    private void drawFieldLabel(String label, int x, int y) {
        fontRendererObj.drawString(label, x, y, GtswnGuiPalette.TEXT_LABEL);
    }

    private String buildChartConfig() {
        return "energyMin=" + energyMinField.getText()
            + "\n"
            + "energyMax="
            + energyMaxField.getText()
            + "\n"
            + "eutMin="
            + eutMinField.getText()
            + "\n"
            + "eutMax="
            + eutMaxField.getText()
            + "\n"
            + "border="
            + borderField.getText()
            + "\n"
            + "chartBg="
            + chartBgField.getText()
            + "\n"
            + "line="
            + lineField.getText()
            + "\n"
            + "smoothing="
            + smoothingField.getText()
            + "\n"
            + "screenColor="
            + screenColorField.getText();
    }

    private String getChartStyleText() {
        return tr("gtswn.network_info.gui.chart_style") + ": "
            + tr(
                "gtswn.network_info.gui.chart_style." + chartStyleDraft.name()
                    .toLowerCase(java.util.Locale.ROOT));
    }

    /** 打包 AE 图表配置为 TileEntity.applyAEChartConfig 可解析的字符串 */
    private String buildAEChartConfig() {
        return "aeMin=" + aeAxisMinField.getText()
            + "\n"
            + "aeMax="
            + aeAxisMaxField.getText()
            + "\n"
            + "aeBorder="
            + aeChartBorderField.getText()
            + "\n"
            + "aeLineW="
            + aeTrendLineField.getText()
            + "\n"
            + "aeSmoothing="
            + aeSmoothingField.getText()
            + "\n"
            + "aeBg="
            + aeBgField.getText()
            + "\n"
            + "aeLineColor="
            + aeLineColorField.getText();
    }

    /** 根据 AE 时长窗口常量生成按钮显示文本（E4 窗口枚举化：langKey 映射迁入 WindowLabel） */
    private String getAEWindowDisplay(int window) {
        return tr("gtswn.network_info.gui.ae.window.nominal") + ": "
            + tr(
                WindowLabel.of(window)
                    .langKey());
    }

    private static String bool(String label, boolean value) {
        return label + " " + (value ? tr("gtswn.network_info.gui.on") : tr("gtswn.network_info.gui.off"));
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? tr("gtswn.network_info.unknown") : value;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    /** Shared font and icon drawing for native AE list rows. */
    FontRenderer font() {
        return fontRendererObj;
    }

    /** 绘制 16x16 物品图标（启用标准 GUI 物品光照；列表行经包私有访问调用） */
    void drawItemIcon(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }

    /** 绘制 16x16 流体图标（使用流体 IIcon 纹理而非纯色块；列表行经包私有访问调用） */
    void drawFluidIcon(FluidStack fluid, int x, int y) {
        if (fluid == null || fluid.getFluid() == null) {
            return;
        }
        // 取流体对应的 IIcon；流体自身未注册图标时回退到对应方块纹理
        IIcon icon = fluid.getFluid()
            .getIcon(fluid);
        if (icon == null && fluid.getFluid()
            .getBlock() != null) {
            icon = fluid.getFluid()
                .getBlock()
                .getIcon(0, 0);
        }
        if (icon == null) {
            return;
        }

        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);

        int color = 0xFF000000 | fluid.getFluid()
            .getColor(fluid);
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;

        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f(r, g, b, 1.0F);

        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, y + 16, 0.0D, icon.getMinU(), icon.getMaxV());
        tess.addVertexWithUV(x + 16, y + 16, 0.0D, icon.getMaxU(), icon.getMaxV());
        tess.addVertexWithUV(x + 16, y, 0.0D, icon.getMaxU(), icon.getMinV());
        tess.addVertexWithUV(x, y, 0.0D, icon.getMinU(), icon.getMinV());
        tess.draw();

        if (!blendEnabled) {
            GL11.glDisable(GL11.GL_BLEND);
        }
        if (!alphaEnabled) {
            GL11.glDisable(GL11.GL_ALPHA_TEST);
        }
        if (cullEnabled) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        } else {
            GL11.glDisable(GL11.GL_CULL_FACE);
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 根据 displayMode 三态格式化 EU 总量（常规/科学/千位） */
    private static String formatEU(BigInteger eu, int displayMode) {
        switch (displayMode) {
            case 1:
                return FormatUtil.formatScientific(eu);
            case 2:
                return FormatUtil.formatMetric(eu, 2);
            case 0:
            default:
                return FormatUtil.formatNormal(eu);
        }
    }

    /** 根据 displayMode 三态生成显示模式按钮文本 */
    private static String getDisplayModeButtonText(int displayMode) {
        switch (displayMode) {
            case 1:
                return tr("gtswn.network_info.gui.mode.scientific");
            case 2:
                return tr("gtswn.network_info.gui.mode.metric");
            case 0:
            default:
                return tr("gtswn.network_info.gui.mode.normal");
        }
    }

    /** 根据样条类型四态生成样条类型按钮文本（0过点/1拟合/2折线/3数字） */
    private static String getSplineTypeName(int type) {
        switch (type) {
            case 1:
                return tr("gtswn.network_info.gui.spline_type.fit");
            case 2:
                return tr("gtswn.network_info.gui.spline_type.poly");
            case 3:
                return tr("gtswn.network_info.gui.spline_type.digital");
            case 0:
            default:
                return tr("gtswn.network_info.gui.spline_type.pass");
        }
    }

    /** 客户端没有 AE gridProxy，通过是否存在监控数据推断在线状态 */
    private static boolean hasAEMonitorData(TileEntityNetworkInfoPanel panel) {
        return !panel.getAEMonitorLatest()
            .isEmpty()
            || !panel.getAEChartSamples()
                .isEmpty();
    }

}
