package com.miaokatze.gtswn.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
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
    /** 客户端上一次渲染时使用的标签页，用于检测服务端同步后重建控件 */
    private int lastKnownTab = -1;

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
        int tabY = top + 8;
        buttonList.add(new GuiButton(TAB_EU, left + 12, tabY, 130, 16, tr("gtswn.network_info.gui.tab.eu")));
        buttonList
            .add(new GuiButton(TAB_AE_CHART, left + 150, tabY, 130, 16, tr("gtswn.network_info.gui.tab.ae_chart")));
        buttonList
            .add(new GuiButton(TAB_AE_MONITOR, left + 288, tabY, 130, 16, tr("gtswn.network_info.gui.tab.ae_monitor")));

        int activeTab = lastKnownTab >= 0 ? lastKnownTab : panel.getCurrentTab();
        if (activeTab == 0) {
            // EU 标签页：原有按钮 0-8 + textFields
            initEUTabControls();
        }
        // AE 标签页（1/2）暂无按钮控件，仅绘制占位
        lastKnownTab = activeTab;
    }

    /** 初始化 EU 标签页原有按钮 0-8 与 textFields（从原 initGui 抽取） */
    private void initEUTabControls() {
        int y1 = top + 38;
        int y2 = top + 58;
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

        int fieldY = top + 151;
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

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == TAB_EU || button.id == TAB_AE_CHART || button.id == TAB_AE_MONITOR) {
            int newTab = (button.id == TAB_EU) ? 0 : (button.id == TAB_AE_CHART ? 1 : 2);
            // 本地立即重建，服务端 setCurrentTab 后通过 getDescriptionPacket 回写，updateScreen 会再次比对确保一致
            lastKnownTab = newTab;
            initGui();
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateAETabState(panel.xCoord, panel.yCoord, panel.zCoord, (byte) 0, newTab, null));
            return;
        }
        if (button.id == 8) {
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, buildChartConfig()));
        } else {
            GTSWNPacketHandler.NETWORK.sendToServer(
                new PacketUpdateNetworkInfoPanelConfig(panel.xCoord, panel.yCoord, panel.zCoord, button.id));
        }
    }

    @Override
    public void updateScreen() {
        int serverTab = panel.getCurrentTab();
        if (serverTab != lastKnownTab) {
            lastKnownTab = serverTab;
            initGui();
            return;
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
        if (activeTab == 0) {
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
            top + 94,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted(
                "gtswn.network_info.energy",
                panel.getDisplayMode() == 0 ? FormatUtil.formatNormal(panel.getCachedEu())
                    : FormatUtil.formatScientific(panel.getCachedEu())),
            left + 12,
            top + 108,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.status", panel.getCachedStatus()),
            left + 12,
            top + 122,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.brief_ratio", panel.getBriefRatio()),
            left + 272,
            top + 66,
            0x2F3640);
        drawChartSettings();
    }

    /** 绘制 AE 走势图标签页（v1.5.1 占位） */
    private void drawAEChartTab() {
        // 绑定状态
        ItemStack item = panel.getChartItem();
        FluidStack fluid = panel.getChartFluid();
        int y = top + 50;
        if (item != null) {
            fontRendererObj.drawString(
                StatCollector
                    .translateToLocalFormatted("gtswn.network_info.gui.ae.chart.bind_item", item.getDisplayName()),
                left + 12,
                y,
                0x2F3640);
        } else if (fluid != null) {
            fontRendererObj.drawString(
                StatCollector
                    .translateToLocalFormatted("gtswn.network_info.gui.ae.chart.bind_fluid", fluid.getLocalizedName()),
                left + 12,
                y,
                0x2F3640);
        } else {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.chart.empty"), left + 12, y, 0x6B7680);
        }
        // 占位提示
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.placeholder"), left + 12, top + 80, 0x6B7680);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + 96, 0x6B7680);
    }

    /** 绘制 AE 实时监控标签页（v1.5.1 占位） */
    private void drawAEMonitorTab() {
        int itemCount = panel.getMonitoredItems()
            .size();
        int fluidCount = panel.getMonitoredFluids()
            .size();
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.ae.monitor.list", itemCount, fluidCount),
            left + 12,
            top + 50,
            0x2F3640);
        // 列出监视项名称
        int y = top + 70;
        for (ItemStack s : panel.getMonitoredItems()) {
            fontRendererObj.drawString("- " + s.getDisplayName(), left + 20, y, 0x4C5660);
            y += 12;
            if (y > top + ySize - 30) break;
        }
        for (FluidStack f : panel.getMonitoredFluids()) {
            fontRendererObj.drawString("- " + f.getLocalizedName(), left + 20, y, 0x4C5660);
            y += 12;
            if (y > top + ySize - 30) break;
        }
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.placeholder"), left + 12, top + ySize - 40, 0x6B7680);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + ySize - 24, 0x6B7680);
    }

    /** 在面板最左上角绘制统一的“网络信息屏”标题栏 */
    private void drawTitleBar() {
        String title = EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.title");
        int titleWidth = fontRendererObj.getStringWidth(title);
        int boxPadding = 6;
        int boxX = left + 8;
        int boxY = top - 12;
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
        drawRect(left + 8, top + 32, left + xSize - 8, top + 33, 0xFFB8C0C8);
        drawRect(left + 8, top + 82, left + xSize - 8, top + 83, 0xFFB8C0C8);
        drawRect(left + 8, top + 130, left + xSize - 8, top + 131, 0xFFB8C0C8);
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
        int y = top + 143;
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

    private static String bool(String label, boolean value) {
        return label + " " + (value ? tr("gtswn.network_info.gui.on") : tr("gtswn.network_info.gui.off"));
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? tr("gtswn.network_info.unknown") : value;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
