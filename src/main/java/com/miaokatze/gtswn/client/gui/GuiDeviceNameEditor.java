package com.miaokatze.gtswn.client.gui;

import java.util.UUID;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.client.DeviceTerminalNames;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache;
import com.miaokatze.gtswn.common.device.DeviceTerminalNameRules;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** A native modal panel; focus, clipboard, key repeat and input routing belong to MUI2. */
final class GuiDeviceNameEditor {

    private static final int WIDTH = 280;
    private static final int HEIGHT = 196;
    private static final int[] COLORS = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
        0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };
    private final GuiDeviceInfoTerminal host;
    private final IPanelHandler handler;
    private UUID terminal;
    private Entry entry;
    private long authorizationEpoch;
    private String defaultPlainName = "";
    private TextFieldWidget name;
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
        name.getContext()
            .focus(name);
    }

    void close() {
        entry = null;
        terminal = null;
        defaultPlainName = "";
        handler.closePanel();
    }

    void validateSelection() {
        if (entry != null && !host.entryCurrent(terminal, entry, authorizationEpoch)) close();
    }

    private ModularPanel buildPanel() {
        ModularPanel panel = new Dialog<>("device_name_editor").size(WIDTH, HEIGHT)
            .background(GtswnMuiTheme.PANEL)
            .themeOverride(GtswnMuiTheme.THEME_ID);
        GtswnMuiTheme.fit(panel);
        panel.child(
            GtswnMuiWidgets
                .canvas("device.rename.preview", 0, 0, WIDTH, HEIGHT, (context, mouseX, mouseY, partialTicks) -> {
                    host.font()
                        .drawString(tr("title"), 12, 10, GtswnGuiPalette.TEXT_TITLE);
                    for (int i = 0; i < COLORS.length; i++) {
                        int px = 12 + i % 8 * 32, py = 56 + i / 8 * 23;
                        host.fillRect(px - 2, py - 2, px + 24, py + 20, i == color ? 0xFFF0B04C : 0xFF3B454F);
                        host.fillRect(px, py, px + 22, py + 18, 0xFF000000 | COLORS[i]);
                    }
                    String normalized = DeviceTerminalNameRules.normalize(name.getText());
                    String preview = normalized == null ? tr("invalid")
                        : normalized.isEmpty() ? defaultPlainName
                            : DeviceTerminalNames.styledName(normalized, color, bold);
                    host.font()
                        .drawString(
                            host.font()
                                .trimStringToWidth(preview, WIDTH - 24),
                            12,
                            131,
                            0xFFFFFF);
                    host.font()
                        .drawString(
                            tr(normalized == null ? "invalid" : "hint"),
                            12,
                            178,
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
            .background(GtswnMuiTheme.LIST_PANEL)
            .value(new StringValue(""))
            .setMaxLength(DeviceTerminalNameRules.MAX_NAME_LENGTH)
            .autoUpdateOnChange(true)
            .setFocusOnGuiOpen(true);
        panel.child(name);
        for (int i = 0; i < COLORS.length; i++) {
            final int selected = i;
            panel.child(
                GtswnMuiWidgets
                    .button(
                        "device.rename.color." + i,
                        "",
                        12 + i % 8 * 32,
                        56 + i / 8 * 23,
                        22,
                        18,
                        () -> color = selected)
                    .background(IDrawable.EMPTY)
                    .hoverBackground(IDrawable.EMPTY)
                    .tooltip(tooltip -> tooltip.addLine(tr("color.hint"))));
        }
        panel.child(
            GtswnMuiWidgets
                .button(
                    "device.rename.bold",
                    () -> tr(bold ? "style.bold" : "style.normal"),
                    12,
                    105,
                    WIDTH - 24,
                    18,
                    () -> bold = !bold)
                .tooltip(tooltip -> tooltip.addLine(tr("style.hint"))));
        panel.child(GtswnMuiWidgets.button("device.rename.save", tr("save"), 12, 151, 80, 18, () -> save(false)));
        panel.child(GtswnMuiWidgets.button("device.rename.reset", tr("reset"), 100, 151, 80, 18, () -> save(true)));
        panel.child(GtswnMuiWidgets.button("device.rename.cancel", tr("cancel"), 188, 151, 80, 18, this::close));
        return panel;
    }

    private void save(boolean reset) {
        // Network receipts can replace authority between the last GUI tick and this input callback.
        if (!host.entryCurrent(terminal, entry, authorizationEpoch)) {
            close();
            return;
        }
        String plain = reset ? "" : DeviceTerminalNameRules.normalize(name.getText());
        if (plain == null) return;
        GTSWNPacketHandler.NETWORK.sendToServer(
            PacketDeviceTerminalVisualAction.rename(
                terminal,
                entry.key,
                plain,
                reset ? DeviceTerminalNameRules.DEFAULT_COLOR : color,
                !reset && bold));
        close();
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.device.rename." + key);
    }
}
