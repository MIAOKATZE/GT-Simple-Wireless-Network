package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

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
    private static final int AE_WINDOW_BUTTON = 10;
    private static final int AE_APPLY_BUTTON = 11;
    private static final int AE_DISPLAY_MODE_BUTTON = 12;
    private static final int AE_CLEAR_ALL_BUTTON = 13;
    private static final int AE_MONITOR_REMOVE_BASE = 200;

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
        buttonList.add(
            new GuiButton(
                7,
                left + 188,
                y2,
                74,
                16,
                panel.getDisplayMode() == 0 ? tr("gtswn.network_info.gui.mode.normal")
                    : tr("gtswn.network_info.gui.mode.scientific")));
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

    /** 初始化 AE 走势图配置标签页控件（tab=1） */
    private void initAEChartTabControls() {
        int fieldY = top + 96;
        aeAxisMinField = addField(left + 104, fieldY, 66, panel.getAEAxisMinText());
        aeAxisMaxField = addField(left + 250, fieldY, 66, panel.getAEAxisMaxText());
        fieldY += 22;
        aeChartBorderField = addField(left + 104, fieldY, 46, String.valueOf(panel.getAEChartBorderThickness()));
        aeTrendLineField = addField(left + 250, fieldY, 46, String.valueOf(panel.getAETrendLineThickness()));
        aeSmoothingField = addField(left + 372, fieldY, 46, String.valueOf(panel.getAETrendLineSmoothing()));
        fieldY += 22;
        aeBgField = addField(left + 104, fieldY, 66, panel.getAEChartBackgroundColorText());
        aeLineColorField = addField(left + 250, fieldY, 66, panel.getAELineColorText());

        // 时长循环按钮与应用按钮
        buttonList.add(
            new GuiButton(
                AE_WINDOW_BUTTON,
                left + 12,
                top + 52,
                130,
                16,
                getAEWindowDisplay(panel.getAETrackingWindow())));
        buttonList
            .add(new GuiButton(AE_APPLY_BUTTON, left + 344, top + 52, 74, 16, tr("gtswn.network_info.gui.apply")));
    }

    /** 初始化 AE 实时监控管理标签页控件（tab=2） */
    @SuppressWarnings("unchecked")
    private void initAEMonitorTabControls() {
        // 显示模式切换 + 全部清除
        buttonList.add(
            new GuiButton(
                AE_DISPLAY_MODE_BUTTON,
                left + 12,
                top + 52,
                112,
                16,
                panel.getDisplayMode() == 0 ? tr("gtswn.network_info.gui.mode.normal")
                    : tr("gtswn.network_info.gui.mode.scientific")));
        buttonList.add(
            new GuiButton(
                AE_CLEAR_ALL_BUTTON,
                left + 318,
                top + 52,
                100,
                16,
                tr("gtswn.network_info.gui.ae.clear_all")));

        // 构造当前监控条目数组，便于 actionPerformed 通过 ID 反查
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

        // 为每个监控条目添加移除按钮
        int rowY = top + 96;
        int rowHeight = 20;
        int bottomLimit = top + ySize - 30;
        for (int i = 0; i < monitoredEntries.length; i++) {
            if (rowY + rowHeight > bottomLimit) {
                break;
            }
            buttonList.add(
                new GuiButton(
                    AE_MONITOR_REMOVE_BASE + i,
                    left + 344,
                    rowY + 2,
                    60,
                    14,
                    tr("gtswn.network_info.gui.ae.remove")));
            rowY += rowHeight;
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
        } else if (button.id == AE_WINDOW_BUTTON) {
            // 循环切换 AE 时长窗口并立即发送
            int next = (panel.getAETrackingWindow() + 1) % 4;
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(
                    panel.xCoord,
                    panel.yCoord,
                    panel.zCoord,
                    "aeWindow=" + next,
                    true));
        } else if (button.id == AE_DISPLAY_MODE_BUTTON) {
            // AE 监控标签页的显示模式切换复用 EU 的 action 7
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, 7));
        } else if (button.id == AE_CLEAR_ALL_BUTTON) {
            // 移除当前所有监控项
            sendClearAllMonitors();
        } else if (isMonitorRemoveButton(button.id)) {
            int idx = button.id - AE_MONITOR_REMOVE_BASE;
            sendMonitorToggle(idx);
        } else {
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, button.id));
        }
    }

    /** 判断按钮 ID 是否对应当前监控列表中的移除按钮 */
    private boolean isMonitorRemoveButton(int id) {
        return id >= AE_MONITOR_REMOVE_BASE && id < AE_MONITOR_REMOVE_BASE + monitoredEntries.length;
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

    /** 发送所有当前监控条目的移除包 */
    private void sendClearAllMonitors() {
        for (int i = 0; i < monitoredEntries.length; i++) {
            sendMonitorToggle(i);
        }
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
                    button.displayString = panel.getDisplayMode() == 0 ? tr("gtswn.network_info.gui.mode.normal")
                        : tr("gtswn.network_info.gui.mode.scientific");
                    break;
                case AE_WINDOW_BUTTON:
                    button.displayString = getAEWindowDisplay(panel.getAETrackingWindow());
                    break;
                case AE_DISPLAY_MODE_BUTTON:
                    button.displayString = panel.getDisplayMode() == 0 ? tr("gtswn.network_info.gui.mode.normal")
                        : tr("gtswn.network_info.gui.mode.scientific");
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
        drawDefaultBackground();
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
                panel.getDisplayMode() == 0 ? FormatUtil.formatNormal(panel.getCachedEu())
                    : FormatUtil.formatScientific(panel.getCachedEu())),
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

    /** 绘制 AE 走势图配置标签页（v1.5.4 改为纯配置界面，数据展示由 TESR 负责） */
    private void drawAEChartTab() {
        ItemStack item = panel.getChartItem();
        FluidStack fluid = panel.getChartFluid();

        // 未绑定：显示空提示与右键配置提示
        if (item == null && fluid == null) {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.chart.empty"), left + 12, top + 64, 0x6B7680);
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + 84, 0x6B7680);
            return;
        }

        // 绑定图标与名称（只读）
        int iconX = left + 12;
        int iconY = top + 48;
        String name;
        if (item != null) {
            drawItemIcon(item, iconX, iconY);
            name = item.getDisplayName();
        } else {
            drawFluidIcon(fluid, iconX, iconY);
            name = fluid.getLocalizedName();
        }
        fontRendererObj.drawString(name, left + 34, top + 50, 0x2F3640);

        // 配置项标签
        drawAEChartSettings();
    }

    /** 绘制 AE 实时监控管理标签页（v1.5.4 改为纯管理界面，数据展示由 TESR 负责） */
    private void drawAEMonitorTab() {
        // 右键提示
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + 80, 0x6B7680);

        // 空列表提示
        if (monitoredEntries.length == 0) {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.empty"), left + 12, top + 100, 0x6B7680);
            return;
        }

        // 表头
        int headerY = top + 96;
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.header_icon"), left + 12, headerY, 0x4C5660);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.header_name"), left + 36, headerY, 0x4C5660);

        // 监控条目行（仅图标+名称，移除按钮在 initGui 中创建）
        int rowY = top + 112;
        int rowHeight = 20;
        int bottomLimit = top + ySize - 30;
        for (Object entry : monitoredEntries) {
            if (rowY + rowHeight > bottomLimit) {
                break;
            }
            if (entry instanceof ItemStack) {
                ItemStack stack = (ItemStack) entry;
                drawItemIcon(stack, left + 12, rowY);
                String name = fontRendererObj.trimStringToWidth(stack.getDisplayName(), 220);
                fontRendererObj.drawString(name, left + 36, rowY + 4, 0x2F3640);
            } else if (entry instanceof FluidStack) {
                FluidStack fluid = (FluidStack) entry;
                drawFluidIcon(fluid, left + 12, rowY);
                String name = fontRendererObj.trimStringToWidth(fluid.getLocalizedName(), 220);
                fontRendererObj.drawString(name, left + 36, rowY + 4, 0x2F3640);
            }
            rowY += rowHeight;
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
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiTextField field : textFields) {
            field.mouseClicked(mouseX, mouseY, mouseButton);
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

    /** 绘制 AE 走势图配置项标签（与 EU 的 drawChartSettings 布局对称） */
    private void drawAEChartSettings() {
        int x = left + 12;
        int y = top + 76;
        fontRendererObj
            .drawString(EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.ae.chart_settings"), x, y, 0x2F3640);
        y += 16;
        drawFieldLabel(tr("gtswn.network_info.gui.ae.y_min"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.ae.y_max"), left + 180, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.ae.border"), left + 12, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.ae.line_width"), left + 180, y + 4);
        drawFieldLabel(tr("gtswn.network_info.gui.ae.smoothing"), left + 326, y + 4);
        y += 22;
        drawFieldLabel(tr("gtswn.network_info.gui.ae.bg"), left + 12, y + 4);
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

    /** 绘制 16x16 流体颜色块 */
    private void drawFluidIcon(FluidStack fluid, int x, int y) {
        if (fluid == null || fluid.getFluid() == null) {
            return;
        }
        int color = fluid.getFluid()
            .getColor(fluid);
        drawRect(x, y, x + 16, y + 16, 0xFF000000 | color);
    }

    /** 格式化 AE 监控存量（根据显示模式切换常规/科学计数） */
    private String formatAEMonitorAmount(long amount, int displayMode) {
        BigInteger value = BigInteger.valueOf(amount);
        return displayMode == 0 ? FormatUtil.formatNormal(value) : FormatUtil.formatScientific(value);
    }

    /** 格式化 AE 监控变化速率（根据显示模式切换常规/科学计数） */
    private String formatAEMonitorRate(double rate, int displayMode) {
        return displayMode == 0 ? FormatUtil.formatNormalDouble(rate) : FormatUtil.formatScientificDouble(rate);
    }

    /** 根据变化速率返回颜色：正绿、负红、零灰 */
    private int rateColor(double rate) {
        if (rate > 0.0D) {
            return 0x4CAF50;
        }
        if (rate < 0.0D) {
            return 0xF44336;
        }
        return 0x6B7680;
    }

}
