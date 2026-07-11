package com.miaokatze.gtswn.client.gui;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

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
    /** 客户端上一次渲染时使用的标签页，用于检测服务端同步后重建控件 */
    private int lastKnownTab = -1;
    /** 用户点击切页后暂存的目标标签页，避免服务端回包前被旧值覆盖 */
    private int pendingTab = -1;

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
        }
        // AE 标签页（1/2）暂无按钮控件，仅绘制占位
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

    /** 绘制 AE 走势图标签页（v1.5.3） */
    private void drawAEChartTab() {
        ItemStack item = panel.getChartItem();
        FluidStack fluid = panel.getChartFluid();

        // 未绑定：显示空提示与右键配置提示
        if (item == null && fluid == null) {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.chart.empty"), left + 12, top + 64, 0x6B7680);
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + 84, 0x6B7680);
            return;
        }

        // 绑定图标
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

        // 名称
        fontRendererObj.drawString(name, left + 34, top + 50, 0x2F3640);

        // 当前存量与变化速率（取最新样本）
        List<AEMonitorSample> samples = panel.getAEChartSamples();
        AEMonitorSample newest = samples.isEmpty() ? null : samples.get(samples.size() - 1);
        int displayMode = panel.getDisplayMode();
        String amountText = newest == null ? tr("gtswn.network_info.gui.ae.chart.collecting")
            : formatAEMonitorAmount(newest.amount, displayMode);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.ae.chart.current", amountText),
            left + 34,
            top + 62,
            0x2F3640);

        String rateText = newest == null ? "-" : formatAEMonitorRate(newest.rate, displayMode);
        String rateLabel = StatCollector.translateToLocalFormatted("gtswn.network_info.gui.ae.chart.rate", rateText);
        int rateColor = newest == null ? 0x6B7680 : rateColor(newest.rate);
        fontRendererObj.drawString(rateLabel, left + 34, top + 74, rateColor);

        // 走势图区域
        int chartX = left + 12;
        int chartY = top + 92;
        int chartW = xSize - 24;
        int chartH = ySize - 110;
        drawChartFrame(chartX, chartY, chartW, chartH);

        if (samples.size() < 2) {
            String collecting = StatCollector
                .translateToLocalFormatted("gtswn.network_info.gui.ae.chart.collecting", samples.size());
            fontRendererObj.drawString(
                collecting,
                chartX + (chartW - fontRendererObj.getStringWidth(collecting)) / 2,
                chartY + chartH / 2 - 4,
                0x777777);
            return;
        }

        double[] amounts = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            amounts[i] = samples.get(i).amount;
        }
        double[] range = range(amounts, null, null);
        drawAESeries(amounts, range, chartX + 1, chartY + 1, chartW - 2, chartH - 2, 0x1F6FFF, 2, 4);
    }

    /** 绘制走势图矩形边框与背景 */
    private void drawChartFrame(int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + h, 0xFFEEEEEE);
        drawRect(x, y, x + w, y + 1, 0xFFB8C0C8);
        drawRect(x, y + h - 1, x + w, y + h, 0xFFB8C0C8);
        drawRect(x, y, x + 1, y + h, 0xFFB8C0C8);
        drawRect(x + w - 1, y, x + w, y + h, 0xFFB8C0C8);
    }

    /** 绘制 AE 实时监控标签页（v1.5.3） */
    private void drawAEMonitorTab() {
        List<ItemStack> items = panel.getMonitoredItems();
        List<FluidStack> fluids = panel.getMonitoredFluids();
        int itemCount = items.size();
        int fluidCount = fluids.size();

        // 空列表提示
        if (itemCount == 0 && fluidCount == 0) {
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.empty"), left + 12, top + 64, 0x6B7680);
            fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.rightclick_hint"), left + 12, top + 84, 0x6B7680);
            return;
        }

        // 表头
        int displayMode = panel.getDisplayMode();
        int headerY = top + 52;
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.header_icon"), left + 12, headerY, 0x4C5660);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.header_name"), left + 36, headerY, 0x4C5660);
        fontRendererObj
            .drawString(tr("gtswn.network_info.gui.ae.monitor.header_amount"), left + 230, headerY, 0x4C5660);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.ae.monitor.header_rate"), left + 340, headerY, 0x4C5660);

        Map<String, AEMonitorSample> latest = panel.getAEMonitorLatest();
        int rowY = top + 68;
        int rowHeight = 20;
        int bottomLimit = top + ySize - 30;

        // 物品行
        for (ItemStack stack : items) {
            if (rowY + rowHeight > bottomLimit) break;
            String key = TileEntityNetworkInfoPanel.getAEKey(stack);
            AEMonitorSample sample = key == null ? null : latest.get(key);
            drawMonitorRow(stack, null, sample, rowY, displayMode);
            rowY += rowHeight;
        }

        // 流体行
        for (FluidStack fluid : fluids) {
            if (rowY + rowHeight > bottomLimit) break;
            String key = TileEntityNetworkInfoPanel.getAEKey(fluid);
            AEMonitorSample sample = key == null ? null : latest.get(key);
            drawMonitorRow(null, fluid, sample, rowY, displayMode);
            rowY += rowHeight;
        }
    }

    /** 绘制实时监控列表中的一行 */
    private void drawMonitorRow(ItemStack item, FluidStack fluid, AEMonitorSample sample, int rowY, int displayMode) {
        int iconX = left + 12;
        String name;
        if (item != null) {
            drawItemIcon(item, iconX, rowY);
            name = item.getDisplayName();
        } else {
            drawFluidIcon(fluid, iconX, rowY);
            name = fluid.getLocalizedName();
        }

        // 名称（截断以避免重叠）
        int nameMaxWidth = 180;
        String displayName = fontRendererObj.trimStringToWidth(name, nameMaxWidth);
        fontRendererObj.drawString(displayName, left + 36, rowY + 4, 0x2F3640);

        // 当前存量
        String amountText = sample == null ? "-" : formatAEMonitorAmount(sample.amount, displayMode);
        fontRendererObj.drawString(amountText, left + 230, rowY + 4, 0x2F3640);

        // 变化速率
        String rateText;
        int color;
        if (sample == null) {
            rateText = "-";
            color = 0x6B7680;
        } else if (sample.rate > 0.0D) {
            rateText = String.format(
                StatCollector.translateToLocal("gtswn.network_info.gui.ae.rate.up"),
                formatAEMonitorRate(sample.rate, displayMode));
            color = 0x4CAF50;
        } else if (sample.rate < 0.0D) {
            rateText = String.format(
                StatCollector.translateToLocal("gtswn.network_info.gui.ae.rate.down"),
                formatAEMonitorRate(-sample.rate, displayMode));
            color = 0xF44336;
        } else {
            rateText = StatCollector.translateToLocal("gtswn.network_info.gui.ae.rate.stable");
            color = 0x6B7680;
        }
        fontRendererObj.drawString(rateText, left + 340, rowY + 4, color);
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

    /** 绘制 AE 走势图单条数据线（参考 RenderNetworkInfoPanel.drawSeries） */
    private static void drawAESeries(double[] values, double[] range, int x, int y, int w, int h, int color,
        int thickness, int smoothing) {
        if (values == null || values.length < 2 || h <= 2 || range == null) {
            return;
        }
        double min = range[0];
        double max = range[1];
        // smoothing 配置映射为样条分段数：0=线性(1段)，1..12 → 4..26 段
        int segments = smoothing <= 0 ? 1 : smoothing * 2 + 2;
        double[][] path = catmullRomPath(values, segments);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(thickness);
        setGLColor(color);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        double denom = values.length - 1;
        for (int i = 0; i < path.length; i++) {
            double normalized = (path[i][1] - min) / (max - min);
            // clamp 到 [0,1] 防止样条过冲溢出图表区
            normalized = Math.max(0.0D, Math.min(1.0D, normalized));
            double px = x + path[i][0] / denom * w;
            double py = y + h - normalized * h;
            GL11.glVertex3d(px, py, 0.0D);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }

    /**
     * 用 Catmull-Rom 样条曲线在样品点之间生成密集顶点路径。
     * 样条经过每个样品点（p1、p2 为段端点）；两端用线性外推虚拟点处理边界。
     *
     * @param values          样品值数组（已按时间索引化，X 等间距）
     * @param segmentsPerSpan 每相邻两点间的插值段数（≥1；1 = 线性）
     * @return 密集顶点路径，长度 = (values.length - 1) * segmentsPerSpan + 1
     */
    private static double[][] catmullRomPath(double[] values, int segmentsPerSpan) {
        int n = values.length;
        if (n < 2 || segmentsPerSpan < 1) {
            double[][] path = new double[n][2];
            for (int i = 0; i < n; i++) {
                path[i][0] = i;
                path[i][1] = values[i];
            }
            return path;
        }
        int total = (n - 1) * segmentsPerSpan + 1;
        double[][] path = new double[total][2];
        path[0][0] = 0D;
        path[0][1] = values[0];

        int idx = 1;
        for (int i = 0; i < n - 1; i++) {
            double p0 = (i == 0) ? 2 * values[0] - values[1] : values[i - 1];
            double p1 = values[i];
            double p2 = values[i + 1];
            double p3 = (i + 2 >= n) ? 2 * values[n - 1] - values[n - 2] : values[i + 2];

            for (int s = 1; s <= segmentsPerSpan; s++) {
                double t = (double) s / segmentsPerSpan;
                double t2 = t * t;
                double t3 = t2 * t;
                double y = 0.5D * (2 * p1 + (-p0 + p2) * t
                    + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                    + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
                path[idx][0] = i + t;
                path[idx][1] = y;
                idx++;
            }
        }
        return path;
    }

    /** 计算数据范围，支持自定义最小/最大值，自动模式追加 10% 冗余 */
    private static double[] range(double[] values, Double customMin, Double customMax) {
        double min = values[0];
        double max = values[0];
        for (double value : values) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (customMin != null) {
            min = customMin.doubleValue();
        }
        if (customMax != null) {
            max = customMax.doubleValue();
        }
        if (Math.abs(max - min) < 0.000001D || max < min) {
            double center = (max + min) / 2.0D;
            min = center - 0.5D;
            max = center + 0.5D;
            return new double[] { min, max };
        }
        if (customMin == null && customMax == null) {
            double span = max - min;
            min = min - span * 0.1D;
            max = max + span * 0.1D;
        }
        return new double[] { min, max };
    }

    /** 将 0xRRGGBB 颜色设置为当前 OpenGL 绘制颜色 */
    private static void setGLColor(int color) {
        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        GL11.glColor4f(r, g, b, 1.0F);
    }
}
