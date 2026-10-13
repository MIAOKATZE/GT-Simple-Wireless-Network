package com.miaokatze.gtswn.client.gui;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalVisualConfig;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalAction;

/** Terminal settings: local heat view preferences and OP-controlled server teleport fee. */
public final class GuiDeviceTerminalSettings extends GuiScreen {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 328;
    private int screenWidth;
    private int screenHeight;
    private GuiCanvas canvas;
    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private int columnWidth;
    private int rightColumn;
    private int rowGap;
    private int tab;
    private int pollTicks;
    private int shownCost = -1;
    private boolean shownPermission;
    private GuiTextField cost;
    private GuiTextField heatSize;
    private GuiTextField labelSize;
    private String heatSizeNotice = "";
    private String labelSizeNotice = "";
    private boolean heatSizeInvalid;
    private boolean labelSizeInvalid;
    private boolean buttonsDirty;
    private final ItemStack stack;

    public GuiDeviceTerminalSettings() {
        this(Minecraft.getMinecraft().thePlayer == null ? null : Minecraft.getMinecraft().thePlayer.getHeldItem());
    }

    public GuiDeviceTerminalSettings(ItemStack stack) {
        this.stack = stack;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void setWorldAndResolution(Minecraft client, int width, int height) {
        screenWidth = width;
        screenHeight = height;
        super.setWorldAndResolution(client, width, height);
    }

    @Override
    public void initGui() {
        DeviceTerminalVisualConfig.load();
        if (screenWidth == 0 || screenHeight == 0) {
            screenWidth = width;
            screenHeight = height;
        }
        canvas = new GuiCanvas(screenWidth, screenHeight, WIDTH, HEIGHT);
        width = canvas.width;
        height = canvas.height;
        panelWidth = WIDTH;
        panelHeight = HEIGHT;
        columnWidth = (panelWidth - 36) / 2;
        rightColumn = 24 + columnWidth;
        rowGap = 22;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        String costDraft = cost == null ? Integer.toString(DeviceTerminalClientCache.teleportCost()) : cost.getText();
        boolean costFocused = cost != null && cost.isFocused();
        cost = new GuiTextField(fontRendererObj, left + 12, top + 84, panelWidth - 120, 18);
        cost.setMaxStringLength(7);
        cost.setEnabled(DeviceTerminalClientCache.canConfigure());
        cost.setText(costDraft);
        cost.setFocused(costFocused);
        if (shownCost < 0) {
            shownCost = DeviceTerminalClientCache.teleportCost();
            shownPermission = DeviceTerminalClientCache.canConfigure();
        }
        heatSize = sizeField(heatSize, DeviceTerminalVisualConfig.heatSize);
        labelSize = sizeField(labelSize, DeviceTerminalVisualConfig.labelSize);
        rebuild();
    }

    private GuiTextField sizeField(GuiTextField previous, int configuredValue) {
        String draft = previous == null ? Integer.toString(configuredValue) : previous.getText();
        boolean focused = previous != null && previous.isFocused();
        GuiTextField field = new GuiTextField(fontRendererObj, left + rightColumn, top + 48 + rowGap * 6, 64, 18);
        field.setMaxStringLength(10);
        field.setText(draft);
        field.setFocused(focused);
        return field;
    }

    @SuppressWarnings("unchecked")
    private void button(int id, int x, int y, int w, String text) {
        if (fontRendererObj.getStringWidth(text) > w - 8) {
            text = fontRendererObj.trimStringToWidth(text, w - 16) + "...";
        }
        buttonList.add(new GtswnGuiButton(id, left + x, top + y, w, Math.min(18, rowGap - 2), text));
    }

    private void rebuild() {
        buttonsDirty = false;
        buttonList.clear();
        int tabWidth = (panelWidth - 32) / 3;
        button(0, 12, 25, tabWidth, tr("tab.visual"));
        button(4, 16 + tabWidth, 25, tabWidth, tr("tab.labels"));
        button(1, 20 + tabWidth * 2, 25, tabWidth, tr("tab.server"));
        button(2, 12, panelHeight - 27, columnWidth, tr("list"));
        button(3, rightColumn, panelHeight - 27, columnWidth, tr("done"));
        if (tab == 0) {
            button(10, 12, 48, panelWidth - 24, toggle("enabled", DeviceTerminalVisualConfig.enabled));
            button(11, 12, 48 + rowGap, columnWidth, toggle("consumer", DeviceTerminalVisualConfig.showConsumers));
            button(
                12,
                rightColumn,
                48 + rowGap,
                columnWidth,
                toggle("generator", DeviceTerminalVisualConfig.showGenerators));
            button(
                13,
                12,
                48 + rowGap * 2,
                columnWidth,
                toggle("steam.consumer", DeviceTerminalVisualConfig.showSteamConsumers));
            button(
                14,
                rightColumn,
                48 + rowGap * 2,
                columnWidth,
                toggle("steam.producer", DeviceTerminalVisualConfig.showSteamProducers));
            button(
                15,
                12,
                48 + rowGap * 3,
                columnWidth,
                label("range", Integer.toString(DeviceTerminalVisualConfig.range)));
            button(
                16,
                rightColumn,
                48 + rowGap * 3,
                columnWidth,
                label("opacity", percent(DeviceTerminalVisualConfig.opacity)));
            button(
                17,
                12,
                48 + rowGap * 4,
                columnWidth,
                label("average", percent(DeviceTerminalVisualConfig.averageWeight)));
            button(
                18,
                rightColumn,
                48 + rowGap * 4,
                columnWidth,
                label("smoothing", percent(DeviceTerminalVisualConfig.smoothing)));
            button(19, 12, 48 + rowGap * 5, columnWidth, toggle("depth", DeviceTerminalVisualConfig.depthTest));
            button(
                20,
                rightColumn,
                48 + rowGap * 5,
                columnWidth,
                label("limit", Integer.toString(DeviceTerminalVisualConfig.maxDevices)));
            button(21, rightColumn + 72, 48 + rowGap * 6, columnWidth - 72, tr("apply"));
        } else if (tab == 4) {
            button(40, 12, 48, panelWidth - 24, toggle("labels", DeviceTerminalVisualConfig.showLabels));
            button(41, 12, 48 + rowGap, columnWidth, toggle("labels.name", DeviceTerminalVisualConfig.showLabelName));
            button(
                42,
                rightColumn,
                48 + rowGap,
                columnWidth,
                toggle("labels.power", DeviceTerminalVisualConfig.showLabelPower));
            button(
                43,
                12,
                48 + rowGap * 2,
                columnWidth,
                toggle("labels.recipe", DeviceTerminalVisualConfig.showLabelRecipe));
            button(
                44,
                rightColumn,
                48 + rowGap * 2,
                columnWidth,
                toggle("labels.state", DeviceTerminalVisualConfig.showLabelState));
            button(
                45,
                12,
                48 + rowGap * 3,
                columnWidth,
                label("range", Integer.toString(DeviceTerminalVisualConfig.range)));
            button(
                46,
                rightColumn,
                48 + rowGap * 3,
                columnWidth,
                label("limit", Integer.toString(DeviceTerminalVisualConfig.maxDevices)));
            button(
                47,
                12,
                48 + rowGap * 4,
                panelWidth - 24,
                toggle("labels.depth", DeviceTerminalVisualConfig.labelDepthTest));
            button(
                48,
                12,
                48 + rowGap * 5,
                panelWidth - 24,
                label("labels.budget", Integer.toString(DeviceTerminalVisualConfig.labelBudget)));
            button(49, rightColumn + 72, 48 + rowGap * 6, columnWidth - 72, tr("apply"));
        } else if (DeviceTerminalClientCache.canConfigure()) {
            button(30, panelWidth - 96, 84, 84, tr("apply"));
            ((GuiButton) buttonList.get(buttonList.size() - 1)).enabled = DeviceTerminalClientCache.canConfigure();
        }
    }

    @Override
    public void updateScreen() {
        cost.updateCursorCounter();
        heatSize.updateCursorCounter();
        labelSize.updateCursorCounter();
        if (buttonsDirty) rebuild();
        if (pollTicks++ % 20 == 0) {
            GTSWNPacketHandler.NETWORK.sendToServer(
                DeviceTerminalClientCache
                    .requestMetadata(com.miaokatze.gtswn.client.DeviceTerminalVisualizationState.heldTerminalId()));
        }
        int value = DeviceTerminalClientCache.teleportCost();
        boolean permission = DeviceTerminalClientCache.canConfigure();
        if (shownCost != value || shownPermission != permission) {
            shownCost = value;
            shownPermission = permission;
            cost.setText(Integer.toString(value));
            cost.setEnabled(permission);
            rebuild();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
            case 1:
            case 4:
                tab = button.id;
                heatSize.setFocused(false);
                labelSize.setFocused(false);
                cost.setFocused(false);
                break;
            case 2:
                if (!applyAllSizes()) return;
                mc.displayGuiScreen(
                    new GuiDeviceInfoTerminal(
                        com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(stack),
                        stack));
                return;
            case 3:
                if (!applyAllSizes()) return;
                mc.displayGuiScreen(null);
                return;
            case 10:
                DeviceTerminalVisualConfig.chooseHeatEnabled(!DeviceTerminalVisualConfig.enabled);
                break;
            case 11:
                DeviceTerminalVisualConfig.showConsumers = !DeviceTerminalVisualConfig.showConsumers;
                break;
            case 12:
                DeviceTerminalVisualConfig.showGenerators = !DeviceTerminalVisualConfig.showGenerators;
                break;
            case 13:
                DeviceTerminalVisualConfig.showSteamConsumers = !DeviceTerminalVisualConfig.showSteamConsumers;
                break;
            case 14:
                DeviceTerminalVisualConfig.showSteamProducers = !DeviceTerminalVisualConfig.showSteamProducers;
                break;
            case 15:
                DeviceTerminalVisualConfig.range = next(
                    DeviceTerminalVisualConfig.range,
                    new int[] { 16, 32, 64, 96, 128 });
                break;
            case 16:
                DeviceTerminalVisualConfig.opacity = cycle(DeviceTerminalVisualConfig.opacity, 0.1D, 0.8D, 0.1D);
                break;
            case 17:
                DeviceTerminalVisualConfig.averageWeight = cycle(
                    DeviceTerminalVisualConfig.averageWeight,
                    0D,
                    1D,
                    0.2D);
                break;
            case 18:
                DeviceTerminalVisualConfig.smoothing = cycle(DeviceTerminalVisualConfig.smoothing, 0D, 0.9D, 0.15D);
                break;
            case 19:
                DeviceTerminalVisualConfig.depthTest = !DeviceTerminalVisualConfig.depthTest;
                break;
            case 20:
                DeviceTerminalVisualConfig.maxDevices = next(
                    DeviceTerminalVisualConfig.maxDevices,
                    new int[] { 64, 128, 256, 512 });
                break;
            case 21:
                applySize(false);
                break;
            case 40:
                DeviceTerminalVisualConfig.showLabels = !DeviceTerminalVisualConfig.showLabels;
                break;
            case 41:
                DeviceTerminalVisualConfig.showLabelName = !DeviceTerminalVisualConfig.showLabelName;
                break;
            case 42:
                DeviceTerminalVisualConfig.showLabelPower = !DeviceTerminalVisualConfig.showLabelPower;
                break;
            case 43:
                DeviceTerminalVisualConfig.showLabelRecipe = !DeviceTerminalVisualConfig.showLabelRecipe;
                break;
            case 44:
                DeviceTerminalVisualConfig.showLabelState = !DeviceTerminalVisualConfig.showLabelState;
                break;
            case 45:
                DeviceTerminalVisualConfig.range = next(
                    DeviceTerminalVisualConfig.range,
                    new int[] { 16, 32, 64, 96, 128 });
                break;
            case 46:
                DeviceTerminalVisualConfig.maxDevices = next(
                    DeviceTerminalVisualConfig.maxDevices,
                    new int[] { 64, 128, 256, 512 });
                break;
            case 47:
                DeviceTerminalVisualConfig.labelDepthTest = !DeviceTerminalVisualConfig.labelDepthTest;
                break;
            case 48:
                DeviceTerminalVisualConfig.labelBudget = next(
                    DeviceTerminalVisualConfig.labelBudget,
                    new int[] { 8, 16, 24, 32, 48, 64 });
                break;
            case 49:
                applySize(true);
                break;
            case 30:
                if (!DeviceTerminalClientCache.canConfigure()) return;
                try {
                    int value = Integer.parseInt(cost.getText());
                    if (value < 0 || value > 1000000) return;
                    GTSWNPacketHandler.NETWORK.sendToServer(
                        new PacketDeviceTerminalAction(
                            PacketDeviceTerminalAction.ACTION_SET_TELEPORT_COST,
                            (byte) 0,
                            (byte) 0,
                            (byte) 0,
                            Integer.toString(value)));
                } catch (NumberFormatException ignored) {}
                break;
            default:
                return;
        }
        if (button.id >= 10 && button.id <= 20 || button.id >= 40 && button.id <= 48) DeviceTerminalVisualConfig.save();
        // Native GuiScreen traverses buttonList while invoking this callback.
        // Rebuild on the next update/draw so one click invokes one action.
        buttonsDirty = true;
    }

    private Integer validatedSize(boolean labels) {
        GuiTextField field = labels ? labelSize : heatSize;
        int min = labels ? DeviceTerminalVisualConfig.LABEL_SIZE_MIN : DeviceTerminalVisualConfig.HEAT_SIZE_MIN;
        int max = labels ? DeviceTerminalVisualConfig.LABEL_SIZE_MAX : DeviceTerminalVisualConfig.HEAT_SIZE_MAX;
        Integer value = null;
        try {
            String input = field.getText()
                .trim();
            if (input.matches("[0-9]+")) {
                int parsed = Integer.parseInt(input);
                if (parsed >= min && parsed <= max) value = parsed;
            }
        } catch (NumberFormatException ignored) {}
        if (value == null) {
            if (labels) {
                labelSizeNotice = tr("labels.size.invalid");
                labelSizeInvalid = true;
            } else {
                heatSizeNotice = tr("heat.size.invalid");
                heatSizeInvalid = true;
            }
        }
        return value;
    }

    private boolean applySize(boolean labels) {
        Integer value = validatedSize(labels);
        if (value == null) return false;
        if (labels) DeviceTerminalVisualConfig.setLabelSize(value);
        else DeviceTerminalVisualConfig.setHeatSize(value);
        DeviceTerminalVisualConfig.save();
        sizeSaved(labels);
        return true;
    }

    private void sizeSaved(boolean labels) {
        if (labels) {
            labelSizeNotice = tr("size.saved");
            labelSizeInvalid = false;
        } else {
            heatSizeNotice = tr("size.saved");
            heatSizeInvalid = false;
        }
    }

    private boolean applyAllSizes() {
        Integer heatValue = validatedSize(false);
        Integer labelValue = validatedSize(true);
        if (heatValue == null || labelValue == null) {
            tab = heatValue == null ? 0 : 4;
            buttonsDirty = true;
            return false;
        }
        DeviceTerminalVisualConfig.setHeatSize(heatValue);
        DeviceTerminalVisualConfig.setLabelSize(labelValue);
        DeviceTerminalVisualConfig.save();
        return true;
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        if (buttonsDirty) rebuild();
        super.mouseClicked(x, y, button);
        if (tab == 1) cost.mouseClicked(x, y, button);
        if (tab == 0) heatSize.mouseClicked(x, y, button);
        if (tab == 4) labelSize.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // The size hints explicitly identify Escape as discarding unapplied edits.
            super.keyTyped(character, keyCode);
            return;
        }
        GuiTextField activeSize = tab == 0 ? heatSize : tab == 4 ? labelSize : null;
        if (activeSize != null && activeSize.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                applySize(tab == 4);
                return;
            }
            if (activeSize.textboxKeyTyped(character, keyCode)) {
                if (tab == 0) heatSizeNotice = "";
                else labelSizeNotice = "";
                return;
            }
        }
        if (tab == 1 && cost.isFocused() && (Character.isDigit(character) || Character.isISOControl(character))) {
            if (cost.textboxKeyTyped(character, keyCode)) return;
        }
        super.keyTyped(character, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (buttonsDirty) rebuild();
        GL11.glPushAttrib(GL11.GL_TRANSFORM_BIT);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            GL11.glScalef(canvas.scale, canvas.scale, 1F);
            drawCanvas(canvas.mouse(mouseX), canvas.mouse(mouseY), partialTicks);
        } finally {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private void drawSize(boolean labels) {
        GuiTextField field = labels ? labelSize : heatSize;
        drawString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(tr(labels ? "labels.size" : "heat.size"), columnWidth - 4),
            left + 12,
            top + 53 + rowGap * 6,
            GtswnGuiPalette.TEXT_BODY);
        field.drawTextBox();
        fontRendererObj.drawSplitString(
            tr(labels ? "labels.size.hint" : "heat.size.hint"),
            left + 12,
            top + 48 + rowGap * 6 + 22,
            panelWidth - 24,
            GtswnGuiPalette.TEXT_MUTED);
        String notice = labels ? labelSizeNotice : heatSizeNotice;
        boolean invalid = labels ? labelSizeInvalid : heatSizeInvalid;
        drawString(
            fontRendererObj,
            notice,
            left + 12,
            top + 48 + rowGap * 6 + 45,
            invalid ? GtswnGuiPalette.STATE_OFFLINE : GtswnGuiPalette.STATE_ONLINE);
    }

    private void drawCanvas(int mouseX, int mouseY, float partialTicks) {
        GtswnGuiDrawing.drawNetworkPanel(left, top, panelWidth, panelHeight, zLevel);
        drawCenteredString(fontRendererObj, tr("title"), left + panelWidth / 2, top + 8, GtswnGuiPalette.TEXT_TITLE);
        if (tab == 0) {
            drawSize(false);
            int legendY = top + 238;
            drawString(fontRendererObj, tr("legend.electric"), left + 12, legendY, 0xDF966A);
            drawString(fontRendererObj, tr("legend.steam"), left + 12, legendY + 11, 0xAEB6BC);
            fontRendererObj
                .drawSplitString(tr("hint"), left + 12, legendY + 25, panelWidth - 24, GtswnGuiPalette.TEXT_MUTED);
        } else if (tab == 4) {
            drawSize(true);
            fontRendererObj
                .drawSplitString(tr("labels.hint"), left + 12, top + 238, panelWidth - 24, GtswnGuiPalette.TEXT_MUTED);
        } else {
            drawString(fontRendererObj, tr("cost"), left + 12, top + 62, GtswnGuiPalette.TEXT_BODY);
            cost.drawTextBox();
            fontRendererObj
                .drawSplitString(tr("server.scope"), left + 12, top + 114, panelWidth - 24, GtswnGuiPalette.TEXT_MUTED);
            fontRendererObj.drawSplitString(
                tr(DeviceTerminalClientCache.canConfigure() ? "server.op" : "server.readonly"),
                left + 12,
                top + 157,
                panelWidth - 24,
                GtswnGuiPalette.TEXT_BODY);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private static int next(int current, int[] options) {
        for (int value : options) if (value > current) return value;
        return options[0];
    }

    private static double cycle(double current, double min, double max, double step) {
        return current + step > max + 0.001D ? min : Math.round((current + step) * 100D) / 100D;
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100D);
    }

    private static String label(String key, String value) {
        return tr(key) + ": " + value;
    }

    private static String toggle(String key, boolean value) {
        return label(key, StatCollector.translateToLocal("gtswn.device.gui." + (value ? "on" : "off")));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.device.settings." + key);
    }
}
