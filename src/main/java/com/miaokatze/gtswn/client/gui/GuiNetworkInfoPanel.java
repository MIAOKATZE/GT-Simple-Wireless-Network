package com.miaokatze.gtswn.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketUpdateNetworkInfoPanelConfig;

public class GuiNetworkInfoPanel extends GuiScreen {

    private final TileEntityNetworkInfoPanel panel;
    private int left;
    private int top;
    private final int xSize = 430;
    private final int ySize = 285;
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
                panel.getChartLayoutMode() == 0 ? tr("gtswn.network_info.gui.split")
                    : tr("gtswn.network_info.gui.overlay")));
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
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            switch (button.id) {
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
                    button.displayString = panel.getChartLayoutMode() == 0 ? tr("gtswn.network_info.gui.split")
                        : tr("gtswn.network_info.gui.overlay");
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
        fontRendererObj
            .drawString(EnumChatFormatting.BOLD + tr("gtswn.network_info.gui.title"), left + 12, top + 12, 0x28313A);
        fontRendererObj.drawString(tr("gtswn.network_info.gui.tab.wireless_eu"), left + 12, top + 24, 0x286080);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.owner", safe(panel.getOwnerName())),
            left + 12,
            top + 88,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector
                .translateToLocalFormatted("gtswn.network_info.energy", FormatUtil.formatNormal(panel.getCachedEu())),
            left + 12,
            top + 102,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.status", panel.getCachedStatus()),
            left + 12,
            top + 116,
            0x2F3640);
        fontRendererObj.drawString(
            StatCollector.translateToLocalFormatted("gtswn.network_info.gui.brief_ratio", panel.getBriefRatio()),
            left + 272,
            top + 62,
            0x2F3640);
        drawChartSettings();
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (GuiTextField field : textFields) {
            field.drawTextBox();
        }
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
        int y = top + 137;
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
