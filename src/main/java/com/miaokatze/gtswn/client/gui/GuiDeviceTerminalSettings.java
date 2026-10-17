package com.miaokatze.gtswn.client.gui;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalVisualConfig;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalAction;

/** Terminal settings: local heat view preferences and OP-controlled server teleport fee. */
public final class GuiDeviceTerminalSettings extends GtswnMuiScreen {

    private static final int WIDTH = 360;
    private static final int HEIGHT = 328;
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
    private TextFieldWidget cost;
    private TextFieldWidget heatSize;
    private TextFieldWidget labelSize;
    private String heatSizeNotice = "";
    private String labelSizeNotice = "";
    private boolean heatSizeInvalid;
    private boolean labelSizeInvalid;
    private boolean buttonsDirty;
    private final ItemStack stack;
    private final ParentWidget<?> buttons = new ParentWidget<>().size(WIDTH, HEIGHT);

    public GuiDeviceTerminalSettings() {
        this(Minecraft.getMinecraft().thePlayer == null ? null : Minecraft.getMinecraft().thePlayer.getHeldItem());
    }

    public GuiDeviceTerminalSettings(ItemStack stack) {
        super("device_terminal_settings", WIDTH, HEIGHT);
        this.stack = stack;
        buildPanel();
    }

    private void buildPanel() {
        DeviceTerminalVisualConfig.load();
        panelWidth = WIDTH;
        panelHeight = HEIGHT;
        columnWidth = (panelWidth - 36) / 2;
        rightColumn = 24 + columnWidth;
        rowGap = 22;
        getMainPanel().child(
            GtswnMuiWidgets.canvas(
                "device.settings.chrome",
                0,
                0,
                WIDTH,
                HEIGHT,
                (context, mouseX, mouseY, partialTicks) -> drawCanvas()));
        cost = field(
            "cost",
            Integer.toString(DeviceTerminalClientCache.teleportCost()),
            12,
            84,
            panelWidth - 120,
            7,
            1);
        heatSize = field(
            "heat_size",
            Integer.toString(DeviceTerminalVisualConfig.heatSize),
            rightColumn,
            48 + rowGap * 5,
            64,
            10,
            0);
        labelSize = field(
            "label_size",
            Integer.toString(DeviceTerminalVisualConfig.labelSize),
            rightColumn,
            48 + rowGap * 6,
            64,
            10,
            4);
        getMainPanel().child(buttons)
            .child(cost)
            .child(heatSize)
            .child(labelSize);
        shownCost = DeviceTerminalClientCache.teleportCost();
        shownPermission = DeviceTerminalClientCache.canConfigure();
        rebuild();
    }

    private TextFieldWidget field(String name, String initial, int x, int y, int width, int limit, int page) {
        TextFieldWidget field = new TextFieldWidget() {

            @Override
            public Result onMousePressed(int button) {
                if (page == 1 && !DeviceTerminalClientCache.canConfigure()) return Result.STOP;
                return super.onMousePressed(button);
            }

            @Override
            public Result onKeyPressed(char character, int keyCode) {
                if (page == 1 && !DeviceTerminalClientCache.canConfigure()) return Result.STOP;
                if (isFocused() && keyCode == Keyboard.KEY_ESCAPE) {
                    getMainPanel().closeIfOpen();
                    return Result.SUCCESS;
                }
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    if (page == 1) actionPerformed(30);
                    else applySize(page == 4);
                    return Result.SUCCESS;
                }
                return super.onKeyPressed(character, keyCode);
            }

            @Override
            protected void onTextChanged() {
                if (page == 0) heatSizeNotice = "";
                else if (page == 4) labelSizeNotice = "";
            }
        }.name("device.settings." + name)
            .pos(x, y)
            .size(width, 18)
            .background(GtswnMuiTheme.LIST_PANEL)
            .value(new StringValue(initial))
            .setMaxLength(limit)
            .autoUpdateOnChange(true)
            .setEnabledIf(widget -> tab == page && (page != 1 || DeviceTerminalClientCache.canConfigure()));
        field.setText(initial);
        return field;
    }

    private void button(int id, int x, int y, int width, String text) {
        buttons.child(
            GtswnMuiWidgets.button(
                "device.settings." + id,
                text,
                x,
                y,
                width,
                Math.min(18, rowGap - 2),
                () -> actionPerformed(id)));
    }

    private void rebuild() {
        buttonsDirty = false;
        buttons.removeAll();
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
                15,
                12,
                48 + rowGap * 2,
                columnWidth,
                label("range", Integer.toString(DeviceTerminalVisualConfig.range)));
            button(
                16,
                rightColumn,
                48 + rowGap * 2,
                columnWidth,
                label("opacity", percent(DeviceTerminalVisualConfig.opacity)));
            button(
                17,
                12,
                48 + rowGap * 3,
                columnWidth,
                label("average", percent(DeviceTerminalVisualConfig.averageWeight)));
            button(
                18,
                rightColumn,
                48 + rowGap * 3,
                columnWidth,
                label("smoothing", percent(DeviceTerminalVisualConfig.smoothing)));
            button(19, 12, 48 + rowGap * 4, columnWidth, toggle("depth", DeviceTerminalVisualConfig.depthTest));
            button(
                20,
                rightColumn,
                48 + rowGap * 4,
                columnWidth,
                label("limit", Integer.toString(DeviceTerminalVisualConfig.maxDevices)));
            button(21, rightColumn + 72, 48 + rowGap * 5, columnWidth - 72, tr("apply"));
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

        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
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
            if (!permission && cost.isFocused()) getContext().removeFocus();
            cost.value(new StringValue(Integer.toString(value)));
            cost.setText(Integer.toString(value));

            rebuild();
        }
    }

    private void actionPerformed(int id) {
        switch (id) {
            case 0:
            case 1:
            case 4:
                tab = id;
                getContext().removeFocus();
                break;
            case 2:
                if (!applyAllSizes()) return;
                ClientGUI.open(
                    new GuiDeviceInfoTerminal(
                        com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal.readTerminalId(stack),
                        stack));
                return;
            case 3:
                if (!applyAllSizes()) return;
                getMainPanel().closeIfOpen();
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
        if (id >= 10 && id <= 20 || id >= 40 && id <= 48) DeviceTerminalVisualConfig.save();
        // Defer widget replacement until the next update, after MUI2 finishes the current input dispatch.
        buttonsDirty = true;
    }

    private Integer validatedSize(boolean labels) {
        TextFieldWidget field = labels ? labelSize : heatSize;
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

    private void drawSize(boolean labels) {
        int row = labels ? 6 : 5;
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(tr(labels ? "labels.size" : "heat.size"), columnWidth - 4),
            left + 12,
            top + 53 + rowGap * row,
            GtswnGuiPalette.TEXT_BODY);

        fontRendererObj.drawSplitString(
            tr(labels ? "labels.size.hint" : "heat.size.hint"),
            left + 12,
            top + 48 + rowGap * row + 22,
            panelWidth - 24,
            GtswnGuiPalette.TEXT_MUTED);
        String notice = labels ? labelSizeNotice : heatSizeNotice;
        boolean invalid = labels ? labelSizeInvalid : heatSizeInvalid;
        fontRendererObj.drawString(
            notice,
            left + 12,
            top + 48 + rowGap * row + 45,
            invalid ? GtswnGuiPalette.STATE_OFFLINE : GtswnGuiPalette.STATE_ONLINE);
    }

    private void drawCanvas() {

        fontRendererObj.drawString(
            tr("title"),
            (panelWidth - fontRendererObj.getStringWidth(tr("title"))) / 2,
            8,
            GtswnGuiPalette.TEXT_TITLE);
        if (tab == 0) {
            drawSize(false);
            int legendY = top + 238;
            fontRendererObj.drawString(tr("legend.electric"), left + 12, legendY, 0xDF966A);
            fontRendererObj
                .drawSplitString(tr("hint"), left + 12, legendY + 14, panelWidth - 24, GtswnGuiPalette.TEXT_MUTED);
        } else if (tab == 4) {
            drawSize(true);
            fontRendererObj
                .drawSplitString(tr("labels.hint"), left + 12, top + 238, panelWidth - 24, GtswnGuiPalette.TEXT_MUTED);
        } else {
            fontRendererObj.drawString(tr("cost"), left + 12, top + 62, GtswnGuiPalette.TEXT_BODY);
            if (!DeviceTerminalClientCache.canConfigure()) {
                GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.LIST_PANEL, 4, 12, 84, panelWidth - 120, 18, 0);
                fontRendererObj.drawString(
                    Integer.toString(DeviceTerminalClientCache.teleportCost()),
                    16,
                    89,
                    GtswnGuiPalette.TEXT_MUTED);
            }

            fontRendererObj
                .drawSplitString(tr("server.scope"), left + 12, top + 114, panelWidth - 24, GtswnGuiPalette.TEXT_MUTED);
            fontRendererObj.drawSplitString(
                tr(DeviceTerminalClientCache.canConfigure() ? "server.op" : "server.readonly"),
                left + 12,
                top + 157,
                panelWidth - 24,
                GtswnGuiPalette.TEXT_BODY);
        }

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
