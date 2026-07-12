package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
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

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketUpdateAETabState;
import com.miaokatze.gtswn.network.PacketUpdateNetworkInfoPanelConfig;

public class GuiNetworkInfoPanel extends GuiScreen {

    private final TileEntityNetworkInfoPanel panel;
    private int left;
    private int top;
    private final int xSize = 430;
    private final int ySize = 285;
    // AE 标签页按钮 ID 常量
    private static final int TAB_EU = 100;
    private static final int TAB_AE_CHART = 101;
    private static final int TAB_AE_MONITOR = 102;
    private final List<GuiTextField> textFields = new ArrayList<>();
    private GuiTextField energyMinField;
    private GuiTextField energyMaxField;
    private GuiTextField eutMinField;
    private GuiTextField eutMaxField;
    private GuiTextField borderField;
    private GuiTextField chartBgField;
    private GuiTextField lineField;
    private GuiTextField smoothingField;
    private GuiTextField screenColorField;

    // AE 图表配置文本框（tab=1）
    private GuiTextField aeAxisMinField;
    private GuiTextField aeAxisMaxField;
    private GuiTextField aeChartBorderField;
    private GuiTextField aeTrendLineField;
    private GuiTextField aeSmoothingField;
    private GuiTextField aeBgField;
    private GuiTextField aeLineColorField;

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
        this.panel = panel;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        textFields.clear();
        left = (width - xSize) / 2;
        top = (height - ySize) / 2;

        // 标签页按钮（顶部，3 个均显示，当前选中高亮）
        int tabY = top + 28;
        buttonList.add(new GuiButton(TAB_EU, left + 12, tabY, 130, 16, tr("gtswn.network_info.gui.tab.eu")));
        buttonList
            .add(new GuiButton(TAB_AE_CHART, left + 150, tabY, 130, 16, tr("gtswn.network_info.gui.tab.ae_chart")));
        buttonList
            .add(new GuiButton(TAB_AE_MONITOR, left + 288, tabY, 130, 16, tr("gtswn.network_info.gui.tab.ae_monitor")));

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
    }

    /** 初始化 EU 标签页原有按钮 0-8 与 textFields（从原 initGui 抽取） */
    private void initEUTabControls() {
        int y1 = top + 52;
        int y2 = top + 72;
        buttonList.add(
            new GuiButton(
                0,
                left + 12,
                y1,
                94,
                16,
                bool(tr("gtswn.network_info.gui.brief.eu"), panel.isShowBriefEnergy())));
        buttonList.add(
            new GuiButton(
                1,
                left + 110,
                y1,
                94,
                16,
                bool(tr("gtswn.network_info.gui.brief.status"), panel.isShowBriefStatus())));
        buttonList.add(
            new GuiButton(
                2,
                left + 208,
                y1,
                94,
                16,
                bool(tr("gtswn.network_info.gui.chart.eu"), panel.isShowChartEnergy())));
        buttonList.add(
            new GuiButton(
                3,
                left + 306,
                y1,
                112,
                16,
                bool(tr("gtswn.network_info.gui.chart.eut"), panel.isShowChartStatus())));
        buttonList.add(
            new GuiButton(
                4,
                left + 12,
                y2,
                112,
                16,
                tr("gtswn.network_info.gui.window") + ": " + panel.getWindowName()));
        buttonList.add(new GuiButton(5, left + 130, y2, 24, 16, "-"));
        buttonList.add(new GuiButton(6, left + 158, y2, 24, 16, "+"));
        buttonList.add(new GuiButton(7, left + 188, y2, 74, 16, getDisplayModeButtonText(panel.getDisplayMode())));
        buttonList.add(new GuiButton(8, left + 344, y2, 74, 16, tr("gtswn.network_info.gui.apply")));

        int fieldY = top + 165;
        energyMinField = addField(left + 104, fieldY, 66, panel.getEnergyAxisMinText());
        energyMaxField = addField(left + 250, fieldY, 66, panel.getEnergyAxisMaxText());
        fieldY += 22;
        eutMinField = addField(left + 104, fieldY, 66, panel.getEutAxisMinText());
        eutMaxField = addField(left + 250, fieldY, 66, panel.getEutAxisMaxText());
        fieldY += 22;
        borderField = addField(left + 104, fieldY, 46, String.valueOf(panel.getChartBorderThickness()));
        lineField = addField(left + 250, fieldY, 46, String.valueOf(panel.getTrendLineThickness()));
        smoothingField = addField(left + 372, fieldY, 46, String.valueOf(panel.getTrendLineSmoothing()));
        fieldY += 22;
        chartBgField = addField(left + 104, fieldY, 66, panel.getChartBackgroundColorText());
        screenColorField = addField(left + 250, fieldY, 66, panel.getScreenBackgroundColorText());
    }

    /** 初始化 AE 走势图配置标签页控件（tab=1），布局与 EU 标签页对称 */
    private void initAEChartTabControls() {
        int row1Y = top + 52;
        int row2Y = top + 74;

        // 第一行：简报 / 存量 / 变化率 / 显示模式
        buttonList.add(
            new GuiButton(
                AE_BRIEF_BUTTON,
                left + 12,
                row1Y,
                94,
                16,
                bool(tr("gtswn.network_info.gui.ae.brief"), panel.isShowAEBrief())));
        buttonList.add(
            new GuiButton(
                AE_CHART_AMOUNT_BUTTON,
                left + 110,
                row1Y,
                94,
                16,
                bool(tr("gtswn.network_info.gui.ae.chart.amount"), panel.isShowAEChartAmount())));
        buttonList.add(
            new GuiButton(
                AE_CHART_RATE_BUTTON,
                left + 208,
                row1Y,
                94,
                16,
                bool(tr("gtswn.network_info.gui.ae.chart.rate"), panel.isShowAEChartRate())));
        buttonList.add(
            new GuiButton(
                AE_DISPLAY_MODE_BUTTON,
                left + 306,
                row1Y,
                112,
                16,
                getDisplayModeButtonText(panel.getDisplayMode())));

        // 第二行：AE 时长显示 / - / + / 应用
        buttonList.add(
            new GuiButton(
                AE_WINDOW_LABEL_BUTTON,
                left + 12,
                row2Y,
                130,
                16,
                getAEWindowDisplay(panel.getAETrackingWindow())));
        buttonList.add(new GuiButton(AE_WINDOW_PREV_BUTTON, left + 146, row2Y, 24, 16, "-"));
        buttonList.add(new GuiButton(AE_WINDOW_NEXT_BUTTON, left + 174, row2Y, 24, 16, "+"));
        buttonList.add(new GuiButton(AE_APPLY_BUTTON, left + 344, row2Y, 74, 16, tr("gtswn.network_info.gui.apply")));

        // 配置文本框整体下移，避免与按钮行重叠并和 EU 标签页对称
        int fieldY = top + 165;
        aeAxisMinField = addField(left + 104, fieldY, 66, panel.getAEAxisMinText());
        aeAxisMaxField = addField(left + 250, fieldY, 66, panel.getAEAxisMaxText());
        fieldY += 22;
        aeChartBorderField = addField(left + 104, fieldY, 46, String.valueOf(panel.getAEChartBorderThickness()));
        aeTrendLineField = addField(left + 250, fieldY, 46, String.valueOf(panel.getAETrendLineThickness()));
        aeSmoothingField = addField(left + 372, fieldY, 46, String.valueOf(panel.getAETrendLineSmoothing()));
        fieldY += 22;
        aeBgField = addField(left + 104, fieldY, 66, panel.getAEChartBackgroundColorText());
        aeLineColorField = addField(left + 250, fieldY, 66, panel.getAELineColorText());
    }

    /** 初始化 AE 实时监控管理标签页控件（tab=2） */
    @SuppressWarnings("unchecked")
    private void initAEMonitorTabControls() {
        // 区域 2：第一排配置按钮（显示模式、字号、加粗、呈现方式）
        int row1Y = top + 52;
        buttonList.add(
            new GuiButton(
                AE_DISPLAY_MODE_BUTTON,
                left + 12,
                row1Y,
                100,
                16,
                getDisplayModeButtonText(panel.getDisplayMode())));
        // 字号标签与 -/+
        buttonList.add(
            new GuiButton(
                AE_MONITOR_FONT_SIZE_LABEL,
                left + 116,
                row1Y,
                70,
                16,
                tr("gtswn.network_info.gui.ae.monitor.font_size") + ": " + panel.getAEMonitorFontSize()));
        buttonList.add(new GuiButton(AE_MONITOR_FONT_SIZE_MINUS, left + 190, row1Y, 24, 16, "-"));
        buttonList.add(new GuiButton(AE_MONITOR_FONT_SIZE_PLUS, left + 218, row1Y, 24, 16, "+"));
        // 加粗开关
        buttonList.add(
            new GuiButton(
                AE_MONITOR_BOLD_BUTTON,
                left + 246,
                row1Y,
                70,
                16,
                tr("gtswn.network_info.gui.ae.monitor.bold") + " "
                    + (panel.isAEMonitorBold() ? tr("gtswn.network_info.gui.on") : tr("gtswn.network_info.gui.off"))));
        // 呈现方式切换
        buttonList.add(
            new GuiButton(
                AE_MONITOR_RENDER_MODE_BUTTON,
                left + 320,
                row1Y,
                90,
                16,
                panel.getAEMonitorRenderMode() == 0 ? tr("gtswn.network_info.gui.ae.monitor.render_mode.list")
                    : tr("gtswn.network_info.gui.ae.monitor.render_mode.grid")));

        // 区域 2：第二排图标大小配置
        int row2Y = top + 74;
        buttonList.add(
            new GuiButton(
                AE_MONITOR_ICON_SIZE_LABEL,
                left + 12,
                row2Y,
                80,
                16,
                tr("gtswn.network_info.gui.ae.monitor.icon_size") + ": " + panel.getAEMonitorIconSize()));
        buttonList.add(new GuiButton(AE_MONITOR_ICON_SIZE_MINUS, left + 96, row2Y, 24, 16, "-"));
        buttonList.add(new GuiButton(AE_MONITOR_ICON_SIZE_PLUS, left + 124, row2Y, 24, 16, "+"));

        // 区域 3：全部清除按钮（向下移动 10px，避免与简报区 top+100 贴边）
        buttonList.add(
            new GuiButton(
                AE_CLEAR_ALL_BUTTON,
                left + 318,
                top + 112,
                100,
                16,
                tr("gtswn.network_info.gui.ae.clear_all")));

        // 构造当前监控条目数组，供滚动列表使用
        refreshMonitoredEntries();

        // 区域 4：滚动列表区（top+148 到 top+255，行高 20）
        aeMonitorList = new GuiAEMonitorList();
    }

    /** 从 panel 刷新监控条目数组 */
    private void refreshMonitoredEntries() {
        List<ItemStack> items = panel.getMonitoredItems();
        List<FluidStack> fluids = panel.getMonitoredFluids();
        int total = items.size() + fluids.size();
        monitoredEntries = new Object[total];
        int idx = 0;
        for (ItemStack stack : items) {
            monitoredEntries[idx++] = stack;
        }
        for (FluidStack fluid : fluids) {
            monitoredEntries[idx++] = fluid;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == TAB_EU || button.id == TAB_AE_CHART || button.id == TAB_AE_MONITOR) {
            int newTab = (button.id == TAB_EU) ? 0 : (button.id == TAB_AE_CHART ? 1 : 2);
            // 本地立即重建，服务端 setCurrentTab 后通过 getDescriptionPacket 回写，updateScreen 会再次比对确保一致
            pendingTab = newTab;
            lastKnownTab = newTab;
            initGui();
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateAETabState(panel.xCoord, panel.yCoord, panel.zCoord, (byte) 0, newTab, null));
            return;
        }
        if (button.id == 8) {
            // EU 图表配置应用
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, buildChartConfig()));
        } else if (button.id == AE_APPLY_BUTTON) {
            // AE 图表配置应用
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(
                    panel.xCoord,
                    panel.yCoord,
                    panel.zCoord,
                    buildAEChartConfig(),
                    true));
        } else if (button.id == AE_DISPLAY_MODE_BUTTON) {
            // AE 标签页的显示模式切换复用 EU 的 action 7
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, 7));
        } else if (button.id == AE_CLEAR_ALL_BUTTON) {
            // 一键清除全部物品+流体监控
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateAETabState(panel.xCoord, panel.yCoord, panel.zCoord, (byte) 6, 0, null));
        } else if (button.id == AE_WINDOW_LABEL_BUTTON || button.id == AE_MONITOR_FONT_SIZE_LABEL
            || button.id == AE_MONITOR_ICON_SIZE_LABEL) {
                // 时长标签、字号标签、图标大小标签仅作显示，无动作
                return;
            } else if (button.id >= AE_MONITOR_FONT_SIZE_MINUS && button.id <= AE_MONITOR_ICON_SIZE_PLUS) {
                // AE 实时监控配置按钮（字号、加粗、呈现方式、图标大小）
                GTSWNPacketHandler.NETWORK.sendToServer(
                    new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, button.id));
            } else if (button.id == 20 || button.id == 21 || button.id == 22 || button.id == 25 || button.id == 26) {
                // AE 走势图开关与时长 +/- 直接走通用 action 包
                GTSWNPacketHandler.NETWORK.sendToServer(
                    new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, button.id));
            } else {
                GTSWNPacketHandler.NETWORK.sendToServer(
                    new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, button.id));
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
    public void updateScreen() {
        int serverTab = panel.getCurrentTab();
        if (pendingTab == -1) {
            if (serverTab != lastKnownTab) {
                lastKnownTab = serverTab;
                initGui();
                return;
            }
        } else if (pendingTab == serverTab) {
            pendingTab = -1;
            if (serverTab != lastKnownTab) {
                lastKnownTab = serverTab;
                initGui();
                return;
            }
        }
        // AE 监控标签页：监控列表长度变化时刷新滚动列表条目并校正滚动位置
        if (lastKnownTab == 2 && aeMonitorList != null) {
            int currentSize = panel.getMonitoredItems()
                .size()
                + panel.getMonitoredFluids()
                    .size();
            if (currentSize != monitoredEntries.length) {
                refreshMonitoredEntries();
                aeMonitorList.clampScroll();
            }
        }
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            switch (button.id) {
                case TAB_EU:
                    button.displayString = (lastKnownTab == 0 ? "[ " : "  ") + tr("gtswn.network_info.gui.tab.eu")
                        + (lastKnownTab == 0 ? " ]" : "  ");
                    break;
                case TAB_AE_CHART:
                    button.displayString = (lastKnownTab == 1 ? "[ " : "  ") + tr("gtswn.network_info.gui.tab.ae_chart")
                        + (lastKnownTab == 1 ? " ]" : "  ");
                    break;
                case TAB_AE_MONITOR:
                    button.displayString = (lastKnownTab == 2 ? "[ " : "  ")
                        + tr("gtswn.network_info.gui.tab.ae_monitor")
                        + (lastKnownTab == 2 ? " ]" : "  ");
                    break;
                case 0:
                    button.displayString = bool(tr("gtswn.network_info.gui.brief.eu"), panel.isShowBriefEnergy());
                    break;
                case 1:
                    button.displayString = bool(tr("gtswn.network_info.gui.brief.status"), panel.isShowBriefStatus());
                    break;
                case 2:
                    button.displayString = bool(tr("gtswn.network_info.gui.chart.eu"), panel.isShowChartEnergy());
                    break;
                case 3:
                    button.displayString = bool(tr("gtswn.network_info.gui.chart.eut"), panel.isShowChartStatus());
                    break;
                case 4:
                    button.displayString = tr("gtswn.network_info.gui.window") + ": " + panel.getWindowName();
                    break;
                case 7:
                    button.displayString = getDisplayModeButtonText(panel.getDisplayMode());
                    break;
                case AE_BRIEF_BUTTON:
                    button.displayString = bool(tr("gtswn.network_info.gui.ae.brief"), panel.isShowAEBrief());
                    break;
                case AE_CHART_AMOUNT_BUTTON:
                    button.displayString = bool(
                        tr("gtswn.network_info.gui.ae.chart.amount"),
                        panel.isShowAEChartAmount());
                    break;
                case AE_CHART_RATE_BUTTON:
                    button.displayString = bool(tr("gtswn.network_info.gui.ae.chart.rate"), panel.isShowAEChartRate());
                    break;
                case AE_WINDOW_LABEL_BUTTON:
                    button.displayString = getAEWindowDisplay(panel.getAETrackingWindow());
                    break;
                case AE_DISPLAY_MODE_BUTTON:
                    button.displayString = getDisplayModeButtonText(panel.getDisplayMode());
                    break;
                case AE_MONITOR_FONT_SIZE_LABEL:
                    button.displayString = tr("gtswn.network_info.gui.ae.monitor.font_size") + ": "
                        + panel.getAEMonitorFontSize();
                    break;
                case AE_MONITOR_BOLD_BUTTON:
                    button.displayString = tr("gtswn.network_info.gui.ae.monitor.bold") + " "
                        + (panel.isAEMonitorBold() ? tr("gtswn.network_info.gui.on")
                            : tr("gtswn.network_info.gui.off"));
                    break;
                case AE_MONITOR_RENDER_MODE_BUTTON:
                    button.displayString = panel.getAEMonitorRenderMode() == 0
                        ? tr("gtswn.network_info.gui.ae.monitor.render_mode.list")
                        : tr("gtswn.network_info.gui.ae.monitor.render_mode.grid");
                    break;
                case AE_MONITOR_ICON_SIZE_LABEL:
                    button.displayString = tr("gtswn.network_info.gui.ae.monitor.icon_size") + ": "
                        + panel.getAEMonitorIconSize();
                    break;
                default:
                    break;
            }
        }
        for (GuiTextField field : textFields) {
            field.updateCursorCounter();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 移除 drawDefaultBackground()，避免 AE 实时监控等标签页出现巨大 dirt 背景；
        // 面板背景由 drawPanelBackground() 统一绘制。
        drawPanelBackground();
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
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (activeTab == 0 || activeTab == 1) {
            for (GuiTextField field : textFields) {
                field.drawTextBox();
            }
        }
        if (activeTab == 2 && aeMonitorList != null) {
            aeMonitorList.draw(mouseX, mouseY, partialTicks);
        }
    }

    /** 绘制 EU 标签页（原 drawScreen 主体内容，去掉 super.drawScreen 与 textField 绘制） */
    private void drawEUTab() {
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.owner", safe(panel.getOwnerName())),
            left + 12,
            top + 108,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(
                "gtswn.network_info.energy",
                formatEU(panel.getCachedEu(), panel.getDisplayMode())),
            left + 12,
            top + 122,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.status", panel.getCachedStatus()),
            left + 12,
            top + 136,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.brief_ratio", panel.getBriefRatio()),
            left + 272,
            top + 80,
            0x2F3640);
        drawChartSettings();
    }

    /** 绘制 AE 走势图配置标签页（v1.5.5 改为纯配置界面，数据展示由 TESR 负责） */
    private void drawAEChartTab() {
        ItemStack item = panel.getChartItem();
        FluidStack fluid = panel.getChartFluid();

        // 未绑定：显示空提示与右键配置提示
        if (item == null && fluid == null) {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.chart.empty"), left + 12, top + 110, 0x6B7680);
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + 130, 0x6B7680);
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
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(name, nameMaxW), left + 32, top + 112, 0x2F3640);

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
        // 在线状态文字使用深绿 0x2E7D32，与 AE 实时监控主题色一致
        int statusColor = online ? 0x2E7D32 : 0xF44336;
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
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_icon"), listLeft + 4, headerY, 0x4C5660);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_name"), listLeft + 22, headerY, 0x4C5660);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_amount"), listLeft + 110, headerY, 0x4C5660);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_realtime"), listLeft + 170, headerY, 0x4C5660);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_average"), listLeft + 260, headerY, 0x4C5660);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_remove"), listRight - 56, headerY, 0x4C5660);

        // 空列表提示（覆盖在列表上方）
        if (monitoredEntries.length == 0) {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.empty"), left + 12, top + 185, 0x6B7680);
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
        // 标题栏背景
        drawRect(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF607080);
        // 标题栏边框（上/下/左/右 1px 高光/阴影）
        drawRect(boxX, boxY, boxX + boxW, boxY + 1, 0xFFA0A8B0);
        drawRect(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0xFF405060);
        drawRect(boxX, boxY, boxX + 1, boxY + boxH, 0xFFA0A8B0);
        drawRect(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0xFF405060);
        fontRendererObj.drawString(title, boxX + boxPadding, boxY + 3, 0xFFFFFF);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        for (GuiTextField field : textFields) {
            if (field.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        // AE 监控标签页优先由自定义列表消费滚轮事件
        if (lastKnownTab == 2 && aeMonitorList != null && aeMonitorList.handleMouseInput()) {
            return;
        }
        super.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (lastKnownTab == 2 && aeMonitorList != null) {
            aeMonitorList.mouseClicked(mouseX, mouseY, mouseButton);
        }
        for (GuiTextField field : textFields) {
            field.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (lastKnownTab == 2 && aeMonitorList != null) {
            aeMonitorList.mouseClickMove(mouseX, mouseY, clickedMouseButton);
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (aeMonitorList != null) {
            aeMonitorList.mouseReleased(mouseX, mouseY, which);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawPanelBackground() {
        drawRect(left, top, left + xSize, top + ySize, 0xFFE8EAEC);
        drawRect(left, top, left + xSize, top + 1, 0xFF607080);
        drawRect(left, top + ySize - 1, left + xSize, top + ySize, 0xFF607080);
        drawRect(left, top, left + 1, top + ySize, 0xFF607080);
        drawRect(left + xSize - 1, top, left + xSize, top + ySize, 0xFF607080);
        drawRect(left + 8, top + 48, left + xSize - 8, top + 49, 0xFFB8C0C8);
        drawRect(left + 8, top + 96, left + xSize - 8, top + 97, 0xFFB8C0C8);
        drawRect(left + 8, top + 144, left + xSize - 8, top + 145, 0xFFB8C0C8);
    }

    private GuiTextField addField(int x, int y, int w, String value) {
        GuiTextField field = new GuiTextField(fontRendererObj, x, y, w, 16);
        field.setMaxStringLength(16);
        field.setText(value == null ? "" : value);
        textFields.add(field);
        return field;
    }

    private void drawChartSettings() {
        int x = left + 12;
        int y = top + 157;
        fontRendererObj
            .drawString(EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.chart_settings"), x, y, 0x2F3640);
        y += 16;
        drawFieldLabel(tr("gtswn.network_info.gui.energy_y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.energy_y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.eut_y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.eut_y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.border_width"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.line_width"), left + 180, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.smoothing"), left + 326, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.chart_bg"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.screen_bg"), left + 180, y + 4);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.blank_auto"), left + 326, y + 4, 0x6B7680);
    }

    /** 绘制 AE 走势图配置项标签（与 EU 的 drawChartSettings 布局对称，整体下移避免与按钮行重叠） */
    private void drawAEChartSettings() {
        int x = left + 12;
        int y = top + 157;
        fontRendererObj
            .drawString(EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.ae.chart_settings"), x, y, 0x2F3640);
        y += 16;
        // AE 走势图输入框标签复用 EU 标签，避免重复定义（按字段语义映射）
        drawFieldLabel(tr("gtswn.network_info.gui.energy_y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.energy_y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.border_width"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.line_width"), left + 180, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.smoothing"), left + 326, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.chart_bg"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.ae.line_color"), left + 180, y + 4);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.blank_auto"), left + 326, y + 4, 0x6B7680);
    }

    private void drawFieldLabel(String label, int x, int y) {
        fontRendererObj.drawString(label, x, y, 0x4C5660);
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

    /** 根据 AE 时长窗口常量生成按钮显示文本 */
    private String getAEWindowDisplay(int window) {
        String suffix;
        switch (window) {
            case 1:
                suffix = tr("gtswn.network_info.gui.ae.window.1h");
                break;
            case 2:
                suffix = tr("gtswn.network_info.gui.ae.window.8h");
                break;
            case 3:
                suffix = tr("gtswn.network_info.gui.ae.window.24h");
                break;
            case 0:
            default:
                suffix = tr("gtswn.network_info.gui.ae.window.5min");
                break;
        }
        return tr("gtswn.network_info.gui.ae.window") + ": " + suffix;
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

    /** 绘制 16x16 物品图标（启用标准 GUI 物品光照） */
    private void drawItemIcon(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        RenderItem.getInstance()
            .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
        RenderHelper.disableStandardItemLighting();
    }

    /** 绘制 16x16 流体图标（使用流体 IIcon 纹理而非纯色块） */
    private void drawFluidIcon(FluidStack fluid, int x, int y) {
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

    /** 格式化 AE 监控存量（根据显示模式切换常规/科学/千位计数） */
    private String formatAEMonitorAmount(long amount, int displayMode) {
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
    private String formatAEMonitorRate(double rate, int displayMode) {
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

    /** 根据变化速率返回颜色：正深绿、负红、零灰 */
    private int rateColor(double rate) {
        if (rate > 0.0D) {
            return 0x2E7D32;
        }
        if (rate < 0.0D) {
            return 0xF44336;
        }
        return 0x6B7680;
    }

    /** 客户端没有 AE gridProxy，通过是否存在监控数据推断在线状态 */
    private static boolean hasAEMonitorData(TileEntityNetworkInfoPanel panel) {
        return !panel.getAEMonitorLatest()
            .isEmpty()
            || !panel.getAEChartSamples()
                .isEmpty();
    }

    /**
     * AE 实时监控自定义滚动列表。
     * <p>
     * 不再继承 {@link GuiSlot}，自行管理滚动偏移、滚动条拖拽、鼠标滚轮与条目绘制。
     * 每行左侧显示图标、名称与数量/存量，中间显示实时变化量 / 平均变化量两列，右侧 60 像素为“清除”按钮区域。
     * 点击非清除区域无动作；点击清除区域向服务端发送移除包。
     */
    private class GuiAEMonitorList {

        // ==================== 几何常量 ====================
        /** 列表左边界（面板内部左侧留 8px） */
        private final int listLeft = GuiNetworkInfoPanel.this.left + 8;
        /** 列表上边界（位于表头下方） */
        private final int listTop = GuiNetworkInfoPanel.this.top + 163;
        /** 列表右边界（面板内部右侧留 8px） */
        private final int listRight = GuiNetworkInfoPanel.this.left + xSize - 8;
        /** 列表内容宽度（430 - 16 = 414） */
        private final int listWidth = xSize - 16;
        /** 列表可视高度（从 top+163 到 top+270） */
        private final int listHeight = GuiNetworkInfoPanel.this.top + 270 - listTop;
        /** 列表下边界（与原 GuiSlot 的 bottom 一致） */
        private final int listBottom = listTop + listHeight;
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

        GuiAEMonitorList() {
            // 无需额外初始化，几何常量已在声明时计算
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
            Object entry = monitoredEntries[index];
            int displayMode = panel.getDisplayMode();
            int iconSize = 16;
            int iconY = y + (slotHeight - iconSize) / 2;
            String name;
            String key;
            if (entry instanceof ItemStack) {
                ItemStack stack = (ItemStack) entry;
                drawItemIcon(stack, x, iconY);
                name = stack.getDisplayName();
                key = TileEntityNetworkInfoPanel.getAEKey(stack);
            } else if (entry instanceof FluidStack) {
                FluidStack fluid = (FluidStack) entry;
                drawFluidIcon(fluid, x, iconY);
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
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(name, nameMaxW), x + 22, y + 6, 0x2F3640);

            // 数量/存量列
            String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, displayMode);
            fontRendererObj.drawString(amountText, x + 110, y + 6, 0x2F3640);

            // 实时变化量 / 平均变化量两列
            String realtimeText;
            int realtimeColor;
            String averageText;
            int averageColor;
            if (sample == null) {
                realtimeText = "-";
                realtimeColor = 0x6B7680;
                averageText = "-";
                averageColor = 0x6B7680;
            } else {
                realtimeText = formatAEMonitorRate(sample.rate, displayMode);
                realtimeColor = rateColor(sample.rate);
                Double avgRate = panel.getAEMonitorAvg300s()
                    .get(key);
                if (avgRate == null) {
                    averageText = "-";
                    averageColor = 0x6B7680;
                } else {
                    averageText = formatAEMonitorRate(avgRate, displayMode);
                    averageColor = rateColor(avgRate);
                }
            }
            fontRendererObj.drawString(realtimeText, x + 170, y + 6, realtimeColor);
            fontRendererObj.drawString(averageText, x + 260, y + 6, averageColor);

            // 清除按钮区域（仅视觉提示，点击由 mouseClicked 处理）
            int btnX = listRight - 62;
            int btnY = y + 4;
            int btnW = 56;
            int btnH = slotHeight - 8;
            drawRect(btnX, btnY, btnX + btnW, btnY + btnH, 0xFFB8C0C8);
            String removeText = tr("gtswn.network_info.gui.ae.remove");
            int textW = fontRendererObj.getStringWidth(removeText);
            fontRendererObj.drawString(removeText, btnX + (btnW - textW) / 2, btnY + 2, 0x2F3640);
        }

        // ==================== 背景与裁剪 ====================
        /** 绘制列表背景色块（覆盖面板背景分隔线，避免列表区出现不需要的线条）。 */
        private void drawListBackground() {
            drawRect(listLeft, listTop, listRight, listBottom, 0xFFEDF1F5);
        }

        /**
         * 启用剪刀测试，将后续绘制限制在列表可视区域内。
         * <p>
         * OpenGL 的 scissor 坐标以屏幕左下角为原点，单位是像素，因此需要按 GUI 缩放比例转换。
         */
        private void enableListScissor() {
            ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            int scale = sr.getScaleFactor();
            int sx = listLeft * scale;
            int sy = mc.displayHeight - listBottom * scale;
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
            int trackX = listRight - scrollbarWidth - scrollbarMarginRight;
            int maxScroll = getMaxScroll();
            // 轨道
            drawRect(trackX, listTop, trackX + scrollbarWidth, listBottom, 0xFFB8C0C8);
            if (maxScroll > 0) {
                int totalRows = Math.max(visibleRows(), monitoredEntries.length);
                int thumbH = Math.max(10, listHeight * visibleRows() / totalRows);
                int thumbY = listTop + scrollOffset * (listHeight - thumbH) / maxScroll;
                drawRect(trackX, thumbY, trackX + scrollbarWidth, thumbY + thumbH, 0xFF6A7680);
            }
        }

        // ==================== 滚动计算 ====================
        /** 返回最大可滚动行数（总条目 - 可见行数，至少为 0）。 */
        private int getMaxScroll() {
            return Math.max(0, monitoredEntries.length - visibleRows());
        }

        /** 返回列表可视区域可容纳的完整行数。 */
        private int visibleRows() {
            return listHeight / slotHeight;
        }

        /** 将 scrollOffset 限制在合法范围内。 */
        private void clampScroll() {
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
            int x = Mouse.getEventX() * width / mc.displayWidth;
            int y = height - Mouse.getEventY() * height / mc.displayHeight - 1;
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
            if (row >= 0 && row < monitoredEntries.length && mouseX >= listRight - 62) {
                sendMonitorToggle(row);
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
    }

}
