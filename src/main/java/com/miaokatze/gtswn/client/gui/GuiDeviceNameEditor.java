package com.miaokatze.gtswn.client.gui;

import java.util.Collections;
import java.util.UUID;

import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtswn.client.DeviceTerminalNames;
import com.miaokatze.gtswn.common.device.DeviceTerminalNameRules;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceTerminalVisualAction;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Modal name and palette editor attached to the selected list row. */
final class GuiDeviceNameEditor {

    private static final int WIDTH = 280;
    private static final int HEIGHT = 196;
    private static final int[] COLORS = { 0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
        0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF };
    private final GuiDeviceInfoTerminal host;
    private final UUID terminal;
    private final Entry entry;
    private final int mouseX;
    private final int mouseY;
    private final boolean previousRepeat;
    private final String defaultPlainName;
    private GuiTextField name;
    private int left;
    private int top;
    private int color;
    private boolean bold;
    private boolean closed;

    GuiDeviceNameEditor(GuiDeviceInfoTerminal host, UUID terminal, Entry entry, int mouseX, int mouseY) {
        this.host = host;
        this.terminal = terminal;
        this.entry = entry;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        color = entry.nameColor;
        bold = entry.nameBold;
        defaultPlainName = EnumChatFormatting.getTextWithoutFormattingCodes(DeviceTerminalNames.defaultName(entry));
        previousRepeat = Keyboard.areRepeatEventsEnabled();
        Keyboard.enableRepeatEvents(true);
        relayout();
        name.setText(DeviceTerminalNames.hasOverride(entry) ? entry.displayName : "");
        name.setFocused(true);
    }

    void relayout() {
        String text = name == null ? "" : name.getText();
        left = Math.max(8, Math.min(host.width - WIDTH - 8, mouseX - 20));
        top = Math.max(8, Math.min(host.height - HEIGHT - 8, mouseY - 20));
        name = new GuiTextField(host.font(), left + 12, top + 30, WIDTH - 24, 18);
        name.setMaxStringLength(DeviceTerminalNameRules.MAX_NAME_LENGTH);
        name.setText(text);
        name.setFocused(true);
    }

    void update() {
        name.updateCursorCounter();
    }

    void close() {
        if (!closed) {
            closed = true;
            Keyboard.enableRepeatEvents(previousRepeat);
        }
    }

    void draw(int x, int y) {
        host.fillRect(0, 0, host.width, host.height, 0x90000000);
        host.fillRect(left - 1, top - 1, left + WIDTH + 1, top + HEIGHT + 1, GtswnGuiPalette.DIVIDER);
        host.fillRect(left, top, left + WIDTH, top + HEIGHT, 0xFF151A20);
        host.font()
            .drawString(tr("title"), left + 12, top + 10, GtswnGuiPalette.TEXT_TITLE);
        name.drawTextBox();
        for (int i = 0; i < COLORS.length; i++) {
            int px = left + 12 + i % 8 * 32, py = top + 56 + i / 8 * 23;
            host.fillRect(px - 2, py - 2, px + 24, py + 20, i == color ? 0xFFF0B04C : 0xFF3B454F);
            host.fillRect(px, py, px + 22, py + 18, 0xFF000000 | COLORS[i]);
        }
        button(left + 12, top + 105, WIDTH - 24, tr(bold ? "style.bold" : "style.normal"), x, y);
        String normalized = DeviceTerminalNameRules.normalize(name.getText());
        String preview = normalized == null ? tr("invalid")
            : normalized.isEmpty() ? defaultPlainName : DeviceTerminalNames.styledName(normalized, color, bold);
        host.font()
            .drawString(
                host.font()
                    .trimStringToWidth(preview, WIDTH - 24),
                left + 12,
                top + 131,
                0xFFFFFF);
        button(left + 12, top + 151, 80, tr("save"), x, y);
        button(left + 100, top + 151, 80, tr("reset"), x, y);
        button(left + 188, top + 151, 80, tr("cancel"), x, y);
        host.font()
            .drawString(
                tr(normalized == null ? "invalid" : "hint"),
                left + 12,
                top + 178,
                normalized == null ? 0xFF6F6F : GtswnGuiPalette.TEXT_MUTED);
        if (inside(x, y, left + 12, top + 105, WIDTH - 24, 18)) {
            host.editorTooltip(Collections.singletonList(tr("style.hint")), x, y);
        } else if (y >= top + 54 && y < top + 101 && x >= left + 10 && x < left + 268) {
            host.editorTooltip(Collections.singletonList(tr("color.hint")), x, y);
        }
    }

    private void button(int x, int y, int width, String text, int mouseX, int mouseY) {
        host.fillRect(x, y, x + width, y + 18, inside(mouseX, mouseY, x, y, width, 18) ? 0xFF69512B : 0xFF303A43);
        host.font()
            .drawString(
                text,
                x + (width - host.font()
                    .getStringWidth(text)) / 2,
                y + 5,
                GtswnGuiPalette.TEXT_BODY);
    }

    void mouseClicked(int x, int y, int button) {
        if (button != 0) return;
        name.mouseClicked(x, y, button);
        for (int i = 0; i < COLORS.length; i++) {
            if (inside(x, y, left + 12 + i % 8 * 32, top + 56 + i / 8 * 23, 22, 18)) {
                color = i;
                return;
            }
        }
        if (inside(x, y, left + 12, top + 105, WIDTH - 24, 18)) {
            bold = !bold;
            return;
        }
        if (inside(x, y, left + 12, top + 151, 80, 18)) save(false);
        else if (inside(x, y, left + 100, top + 151, 80, 18)) save(true);
        else if (inside(x, y, left + 188, top + 151, 80, 18)) host.closeNameEditor();
    }

    void keyTyped(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            host.closeNameEditor();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            save(false);
            return;
        }
        name.textboxKeyTyped(character, keyCode);
    }

    private void save(boolean reset) {
        String plain = reset ? "" : DeviceTerminalNameRules.normalize(name.getText());
        if (plain == null) return;
        GTSWNPacketHandler.NETWORK.sendToServer(
            PacketDeviceTerminalVisualAction.rename(
                terminal,
                entry.key,
                plain,
                reset ? DeviceTerminalNameRules.DEFAULT_COLOR : color,
                !reset && bold));
        host.closeNameEditor();
    }

    private static boolean inside(int x, int y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.device.rename." + key);
    }
}
