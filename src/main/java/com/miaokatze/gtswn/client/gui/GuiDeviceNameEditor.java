package com.miaokatze.gtswn.client.gui;

import java.util.UUID;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.client.DeviceTerminalNames;
import com.miaokatze.gtswn.common.device.DeviceTerminalNameRules;
import com.miaokatze.gtswn.common.device.DeviceTerminalTagRules;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** A native modal panel; focus, clipboard, key repeat and input routing belong to MUI2. */
final class GuiDeviceNameEditor {

    private static final int WIDTH = 340;
    private static final int HEIGHT = 262;
    private static final int[] COLORS = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
        0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };
    private final GuiDeviceInfoTerminal host;
    private final IPanelHandler handler;
    private UUID terminal;
    private Entry entry;
    private long authorizationEpoch;
    private String defaultPlainName = "";
    private TextFieldWidget name;
    private TextFieldWidget tags;
    private int color;
    private boolean bold;

    GuiDeviceNameEditor(GuiDeviceInfoTerminal host) {
        this.host = host;
        handler = IPanelHandler.simple(host.getMainPanel(), (parent, player) -> buildPanel(), true);
    }

    void open(UUID terminal, Entry entry) {
        this.terminal = terminal;
        this.entry = entry;
        this.authorizationEpoch = DeviceTerminalClientCache.authorizationEpoch(terminal);
        color = entry.nameColor;
        bold = entry.nameBold;
        defaultPlainName = EnumChatFormatting.getTextWithoutFormattingCodes(DeviceTerminalNames.defaultName(entry));
        handler.openPanel();
        String draft = DeviceTerminalNames.hasOverride(entry) ? entry.displayName : "";
        name.value(new StringValue(draft));
        name.setText(draft);
        tags.value(new StringValue(entry.tags));
        tags.setText(entry.tags);
        name.getContext()
            .focus(name);
    }

    void close() {
        clearSelection();
        handler.closePanel();
    }

    private void clearSelection() {
        entry = null;
        terminal = null;
        defaultPlainName = "";
        if (name != null) name.setText("");
        if (tags != null) tags.setText("");
    }

    void validateSelection() {
        if (entry != null && !host.entryCurrent(terminal, entry, authorizationEpoch)) close();
    }

    private ModularPanel buildPanel() {
        ModularPanel panel = new Dialog<>("device_name_editor").size(WIDTH, HEIGHT)
            .background(GtswnMuiTheme.PANEL)
            .themeOverride(GtswnMuiTheme.THEME_ID);
        // Escape/outside-click/native parent closure need not call this editor's close() method.
        panel.onCloseAction(this::clearSelection);
        GtswnMuiTheme.fit(panel);
        panel.child(
            GtswnMuiWidgets
                .canvas("device.rename.preview", 0, 0, WIDTH, HEIGHT, (context, mouseX, mouseY, partialTicks) -> {
                    if (!host.entryCurrent(terminal, entry, authorizationEpoch)) return;
                    host.font()
                        .drawString(tr("title"), 12, 10, GtswnGuiPalette.TEXT_TITLE);
                    String normalized = DeviceTerminalNameRules.normalize(name.getText());
                    String preview = normalized == null ? tr("invalid")
                        : normalized.isEmpty() ? defaultPlainName
                            : DeviceTerminalNames.styledName(normalized, color, bold);
                    host.font()
                        .drawString(
                            host.font()
                                .trimStringToWidth(preview, WIDTH - 24),
                            12,
                            188,
                            0xFFFFFF);
                    host.font()
                        .drawString(
                            tr(
                                normalized == null || DeviceTerminalTagRules.normalize(tags.getText()) == null
                                    ? "invalid"
                                    : "hint"),
                            12,
                            241,
                            normalized == null ? 0xFF6F6F : GtswnGuiPalette.TEXT_MUTED);
                }));
        name = new TextFieldWidget() {

            @Override
            public Result onKeyPressed(char character, int keyCode) {
                if (isFocused() && keyCode == Keyboard.KEY_ESCAPE) {
                    close();
                    return Result.SUCCESS;
                }
                if (isFocused() && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
                    save(false);
                    return Result.SUCCESS;
                }
                return super.onKeyPressed(character, keyCode);
            }
        }.name("device.rename.name")
            .pos(12, 30)
            .size(WIDTH - 24, 18)
            .background(DeviceTerminalUi.INPUT)
            .hoverBackground(DeviceTerminalUi.INPUT_HOVER)
            .value(new StringValue(""))
            .setMaxLength(DeviceTerminalNameRules.MAX_NAME_LENGTH)
            .autoUpdateOnChange(true)
            .setFocusOnGuiOpen(true);
        panel.child(name);
        for (int i = 0; i < COLORS.length; i++) {
            final int selected = i;
            panel.child(
                DeviceTerminalUi
                    .button(
                        "device.rename.color." + i,
                        "",
                        12 + i % 8 * 40,
                        60 + i / 8 * 23,
                        22,
                        18,
                        () -> color = selected)
                    .background(
                        (context, x, y, w, h, theme) -> DeviceTerminalUi.drawBox(
                            x,
                            y,
                            w,
                            h,
                            color == selected ? 0xFFF0B04C : 0xFF71889A,
                            0xFF000000 | COLORS[selected]))
                    .hoverBackground(
                        (context, x, y, w, h, theme) -> DeviceTerminalUi
                            .drawBox(x, y, w, h, 0xFFFFD58C, 0xFF000000 | COLORS[selected]))
                    .tooltip(tooltip -> tooltip.addLine(tr("color.hint"))));
        }
        panel.child(
            DeviceTerminalUi
                .button(
                    "device.rename.bold",
                    () -> tr(bold ? "style.bold" : "style.normal"),
                    12,
                    110,
                    WIDTH - 24,
                    18,
                    () -> bold = !bold)
                .background(
                    (context, x, y, w, h, theme) -> (bold ? DeviceTerminalUi.SELECTED : DeviceTerminalUi.NORMAL)
                        .draw(context, x, y, w, h, theme))
                .hoverBackground(
                    (context, x, y, w, h, theme) -> (bold ? DeviceTerminalUi.SELECTED_HOVER : DeviceTerminalUi.HOVER)
                        .draw(context, x, y, w, h, theme))
                .tooltip(tooltip -> tooltip.addLine(tr("style.hint"))));
        panel.child(
            com.cleanroommc.modularui.api.drawable.IKey.lang("gtswn.device.tags.input")
                .asWidget()
                .pos(12, 138)
                .size(WIDTH - 24, 14));
        tags = GtswnMuiWidgets
            .textField("device.rename.tags", "", 12, 154, WIDTH - 24, 20, DeviceTerminalTagRules.MAX_INPUT_LENGTH)
            .background(DeviceTerminalUi.INPUT)
            .hoverBackground(DeviceTerminalUi.INPUT_HOVER)
            .hintText(StatCollector.translateToLocal("gtswn.device.tags.input_hint"))
            .tooltip(t -> t.addLine(com.cleanroommc.modularui.api.drawable.IKey.lang("gtswn.device.tags.input_hint")));
        panel.child(tags);
        panel.child(DeviceTerminalUi.button("device.rename.save", tr("save"), 12, 210, 98, 20, () -> save(false)));
        panel.child(DeviceTerminalUi.button("device.rename.reset", tr("reset"), 121, 210, 98, 20, () -> save(true)));
        panel.child(DeviceTerminalUi.button("device.rename.cancel", tr("cancel"), 230, 210, 98, 20, this::close));
        return panel;
    }

    private void save(boolean reset) {
        // Network receipts can replace authority between the last GUI tick and this input callback.
        if (!handler.isPanelOpen() || !host.entryCurrent(terminal, entry, authorizationEpoch)) {
            close();
            return;
        }
        String plain = reset ? "" : DeviceTerminalNameRules.normalize(name.getText());
        String labels = DeviceTerminalTagRules.normalize(tags.getText());
        if (plain == null || labels == null) return;
        GTSWNPacketHandler.NETWORK.sendToServer(
            PacketDeviceTerminalVisualAction.edit(
                terminal,
                entry.key,
                entry.metaTileId,
                plain,
                reset ? DeviceTerminalNameRules.DEFAULT_COLOR : color,
                !reset && bold,
                labels));
        host.refreshAfterEdit();
        close();
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.device.rename." + key);
    }
}
