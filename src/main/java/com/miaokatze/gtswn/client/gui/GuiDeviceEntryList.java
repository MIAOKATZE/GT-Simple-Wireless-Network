package com.miaokatze.gtswn.client.gui;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.miaokatze.gtswn.client.DeviceTerminalClientCache.Snapshot;
import com.miaokatze.gtswn.client.DeviceTerminalNames;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

/** Both display modes use the same native scrolling, clipping, entry identity and action checks. */
class GuiDeviceEntryList extends ListWidget<IWidget, GuiDeviceEntryList> {

    private final GuiDeviceInfoTerminal host;
    private List<Entry> displayed = Collections.emptyList();
    private int layoutWidth = -1;
    private boolean cards;

    GuiDeviceEntryList(GuiDeviceInfoTerminal host) {
        this.host = host;
        name("device.entries");
        pos(10, 140).size(780, 264);
        crossAxisAlignment(Alignment.CrossAxis.START);
        background(GtswnMuiTheme.LIST_PANEL);
    }

    void invalidateLayout() {
        layoutWidth = -1;
        clampScroll();
        scheduleResize();
    }

    void clampScroll() {
        List<Entry> entries = host.sortedEntries();
        int width = host.entryWidth();
        boolean iconMode = host.iconMode();
        if (displayed == entries && layoutWidth == width && cards == iconMode) return;
        displayed = entries;
        layoutWidth = width;
        cards = iconMode;
        removeAll();
        int columns = cards ? Math.max(1, width / 246) : 1;
        int cardWidth = Math.max(1, (width - (columns - 1) * 6) / columns);
        int index = 0;
        ParentWidget<?> line = null;
        for (Entry entry : entries) {
            int rowHeight = cards ? 148 : width < 520 ? 96 : width < 900 ? 70 : 50;
            if (index % columns == 0) {
                line = new ParentWidget<>().size(width, rowHeight + 6)
                    .background(IDrawable.EMPTY);
                child(line);
            }
            RecordRow row = new RecordRow(entry, cardWidth, rowHeight, cards).name("device.row." + index)
                .pos(index % columns * (cardWidth + 6), 3)
                .size(cardWidth, rowHeight)
                .background(IDrawable.EMPTY)
                .hoverBackground(IDrawable.EMPTY)
                .tooltipBuilder(tooltip -> {
                    tooltip.showUpTimer(0);
                    for (String text : host.entryTooltip(entry)) tooltip.addLine(text);
                    tooltip.addLine(host.kindText(entry.machineKind) + " / " + host.tierText(entry.machineTier));
                    tooltip.addLine(entry.dim + " (" + entry.x + ", " + entry.y + ", " + entry.z + ")");
                })
                .tooltipAutoUpdate(true)
                .onMousePressed(button -> rowAction(entry, button));
            ItemStack controller = DeviceTerminalNames.controllerStack(entry);
            if (controller != null) {
                ItemDrawable icon = new ItemDrawable(controller);
                IDrawable guarded = (context, x, y, w, h, theme) -> {
                    if (row.current()) icon.draw(context, x, y, w, h, theme);
                };
                row.controls.child(
                    guarded.asWidget()
                        .pos(9, cards ? 9 : 10)
                        .size(cards ? 34 : 24));
            }
            addActions(row, entry, cardWidth, rowHeight, cards);
            line.child(row);
            index++;
        }
    }

    private void addActions(RecordRow row, Entry entry, int width, int height, boolean card) {
        int buttonY = card ? height - 26 : width < 520 ? height - 24 : width < 900 ? 27 : 8;
        int teleportY = card ? buttonY : 6;
        EntryActionButton travel = new EntryActionButton(
            entry,
            host::teleportAffordable,
            () -> host.sendTeleport(entry.key)) {

            @Override
            public IDrawable getBackground() {
                return (context, x, y, w, h, theme) -> {
                    boolean hovered = isHovering() && host.teleportAffordable();
                    int color = host.teleportColor();
                    if (hovered) color = host.teleportHasAnchor() ? 0xFFB2F0D0 : 0xFFFFDA9E;
                    host.fillRect((int) x, (int) y, (int) (x + w), (int) (y + h), color);
                    host.fillRect(
                        (int) x + 1,
                        (int) y + 1,
                        (int) (x + w) - 1,
                        (int) (y + h) - 1,
                        host.teleportHasAnchor() ? hovered ? 0xFF2D6A51 : 0xFF224B3C
                            : host.teleportAffordable() ? hovered ? 0xFF785A33 : 0xFF58412A : 0xFF502E32);
                };
            }

            @Override
            public IDrawable getHoverBackground() {
                return getBackground();
            }
        }.name(row.getName() + ".teleport")
            .pos(width - 86, teleportY)
            .size(78, 20)
            .overlay(IKey.dynamic(() -> tr("gtswn.device.gui.teleport")))
            .tooltipBuilder(t -> t.addLine(host.teleportHint()))
            .tooltipAutoUpdate(true);
        row.controls.child(travel);
        int attachX = card ? 8 : width < 900 ? width - 188 : width - 204;
        int removeX = attachX + 52;
        row.controls.child(
            new EntryActionButton(entry, () -> host.linkAvailable(entry), () -> host.requestLink(entry, true))
                .name(row.getName() + ".attach")
                .pos(attachX, buttonY)
                .size(48, 20)
                .overlay(IKey.lang("gtswn.device.link.attach"))
                .tooltipBuilder(t -> t.addLine(host.linkTooltip(entry)))
                .tooltipAutoUpdate(true));
        row.controls.child(
            new EntryActionButton(entry, () -> host.linkAvailable(entry), () -> host.requestLink(entry, false))
                .name(row.getName() + ".remove")
                .pos(removeX, buttonY)
                .size(48, 20)
                .overlay(IKey.lang("gtswn.device.link.remove"))
                .tooltipBuilder(t -> t.addLine(host.linkTooltip(entry)))
                .tooltipAutoUpdate(true));
    }

    private class EntryActionButton extends ButtonWidget<EntryActionButton> {

        final Entry entry;
        private final BooleanSupplier enabled;
        private final Runnable action;

        EntryActionButton(Entry entry, BooleanSupplier enabled, Runnable action) {
            this.entry = entry;
            this.enabled = enabled;
            this.action = action;
        }

        @Override
        public Result onMousePressed(int button) {
            if (!host.isActive() || !host.isCurrentEntry(entry)) return Result.STOP;
            if (button == 1 || Interactable.hasAltDown()
                || Interactable.hasControlDown()
                || Interactable.hasShiftDown()) {
                rowAction(entry, button);
                return Result.SUCCESS;
            }
            if (button == 0 && enabled.getAsBoolean()) action.run();
            return Result.SUCCESS;
        }

        @Override
        public IDrawable getBackground() {
            return enabled.getAsBoolean() ? DeviceTerminalUi.NORMAL : DeviceTerminalUi.DISABLED;
        }

        @Override
        public IDrawable getHoverBackground() {
            return enabled.getAsBoolean() ? DeviceTerminalUi.HOVER : DeviceTerminalUi.DISABLED;
        }
    }

    private boolean rowAction(Entry entry, int button) {
        if (!host.isActive() || !host.isCurrentEntry(entry)) return true;
        if (button == 1) host.openNameEditor(entry, 0, 0);
        else if (button == 0) {
            if (Interactable.hasAltDown()) host.toggleFavorite(entry);
            else if (Interactable.hasControlDown()) host.sendUnbind(entry.key);
            else if (Interactable.hasShiftDown()) host.cycleRecognition(entry.key);
            else host.selectDesktopEntry(entry);
        }
        return true;
    }

    @Override
    public boolean layoutWidgets() {
        if (!hasChildren()) getScrollData().setScrollSize(0);
        boolean complete = super.layoutWidgets();
        if (complete) getScrollData().clamp(getScrollArea());
        return complete;
    }

    @Override
    public boolean onMouseScroll(UpOrDown direction, int amount) {
        host.requestPendingPages();
        return super.onMouseScroll(direction, amount);
    }

    private final class RecordRow extends ButtonWidget<RecordRow> {

        private final Entry entry;
        private final Snapshot snapshot;
        private final List<Entry> rows;
        private final int width, height;
        private final boolean card;
        private final ParentWidget<?> controls = new ParentWidget<>().sizeRel(1f)
            .background(IDrawable.EMPTY)
            .hoverBackground(IDrawable.EMPTY);

        RecordRow(Entry entry, int width, int height, boolean card) {
            this.entry = entry;
            this.width = width;
            this.height = height;
            this.card = card;
            snapshot = host.displayedSnapshot();
            rows = host.sortedEntries();
            child(controls);
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            if (!current()) return;
            GtswnMuiWidgets.drawLegacy(() -> {
                host.fillRect(0, 0, width, height, host.desktopEntrySelected(entry) ? 0xFFF0B04C : 0xFF536676);
                host.fillRect(1, 1, width - 1, height - 1, isHovering() ? 0xFF2B3B49 : 0xFF1C2833);
                if (card) drawCard();
                else drawList();
            });
        }

        boolean current() {
            return rows == host.sortedEntries() && host.displayCurrent(snapshot);
        }

        private void text(String text, int x, int y, int maxWidth, int color) {
            host.font()
                .drawString(ellipsis(host.font(), text, maxWidth), x, y, color);
        }

        private String name() {
            return (entry.favoriteOrder > 0 ? "\u00a7e\u2605 \u00a7r" : "") + DeviceTerminalNames.localName(entry);
        }

        private String metadata() {
            return host.kindText(entry.machineKind) + " / "
                + host.tierText(entry.machineTier)
                + " / "
                + entry.dim
                + " ("
                + entry.x
                + ", "
                + entry.y
                + ", "
                + entry.z
                + ")"
                + " / "
                + tr("gtswn.device.link.column")
                + " "
                + host.linkText(entry);
        }

        private void drawCard() {
            text(name(), 52, 10, width - 60, GtswnGuiPalette.TEXT_BODY);
            text(
                host.stateText(entry.state) + " / " + host.linkText(entry),
                52,
                27,
                width - 60,
                GtswnGuiPalette.TEXT_BODY);
            text(
                tr("gtswn.device.gui.col.instant") + ": " + host.formatInstant(entry) + " EU/t",
                10,
                52,
                width - 20,
                amountColor(entry, GuiDeviceInfoTerminal.instantValue(entry)));
            text(
                tr("gtswn.device.gui.col.average") + ": " + host.formatAverage(entry) + " EU/t",
                10,
                67,
                width - 20,
                amountColor(entry, GuiDeviceInfoTerminal.averageValue(entry)));
            text(metadata(), 10, 83, width - 20, GtswnGuiPalette.TEXT_MUTED);
            text(
                entry.tags.isEmpty() ? tr("gtswn.device.tags.empty") : "# " + entry.tags,
                10,
                100,
                width - 20,
                0xFFAECAD9);
        }

        private void drawList() {
            if (width >= 900) {
                int nameWidth = width - 648;
                text(name(), 44, 10, nameWidth, GtswnGuiPalette.TEXT_BODY);
                int stateX = 50 + nameWidth;
                text(host.stateText(entry.state), stateX, 10, 64, GtswnGuiPalette.TEXT_BODY);
                text(
                    tr("gtswn.device.gui.col.instant") + " " + host.formatInstant(entry),
                    stateX + 68,
                    10,
                    106,
                    amountColor(entry, GuiDeviceInfoTerminal.instantValue(entry)));
                text(
                    tr("gtswn.device.gui.col.average") + " " + host.formatAverage(entry),
                    stateX + 184,
                    10,
                    106,
                    amountColor(entry, GuiDeviceInfoTerminal.averageValue(entry)));
                text(host.linkText(entry), width - 256, 10, 48, GtswnGuiPalette.TEXT_ACCENT);
                text(
                    metadata() + (entry.tags.isEmpty() ? "" : "  # " + entry.tags),
                    44,
                    32,
                    width - 62,
                    GtswnGuiPalette.TEXT_MUTED);
            } else if (width >= 520) {
                text(name(), 44, 9, width - 228, GtswnGuiPalette.TEXT_BODY);
                text(host.stateText(entry.state), width - 174, 9, 80, GtswnGuiPalette.TEXT_BODY);
                text(
                    tr("gtswn.device.gui.col.instant") + " " + host.formatInstant(entry),
                    44,
                    29,
                    (width - 264) / 2,
                    amountColor(entry, GuiDeviceInfoTerminal.instantValue(entry)));
                text(
                    tr("gtswn.device.gui.col.average") + " " + host.formatAverage(entry),
                    44 + (width - 264) / 2,
                    29,
                    (width - 264) / 2,
                    amountColor(entry, GuiDeviceInfoTerminal.averageValue(entry)));
                text(
                    metadata() + (entry.tags.isEmpty() ? "" : "  # " + entry.tags),
                    44,
                    53,
                    width - 62,
                    GtswnGuiPalette.TEXT_MUTED);
            } else {
                text(name(), 44, 9, width - 140, GtswnGuiPalette.TEXT_BODY);
                text(host.stateText(entry.state), 44, 25, width - 60, GtswnGuiPalette.TEXT_BODY);
                text(
                    tr("gtswn.device.gui.col.instant") + " " + host.formatInstant(entry) + " EU/t",
                    12,
                    43,
                    width - 24,
                    amountColor(entry, GuiDeviceInfoTerminal.instantValue(entry)));
                text(
                    tr("gtswn.device.gui.col.average") + " " + host.formatAverage(entry) + " EU/t",
                    12,
                    57,
                    width - 24,
                    amountColor(entry, GuiDeviceInfoTerminal.averageValue(entry)));
                text(metadata(), 12, 78, width - 208, GtswnGuiPalette.TEXT_MUTED);
            }
        }
    }

    private static int amountColor(Entry entry, double value) {
        if (entry.state == 3) return GtswnGuiPalette.TEXT_MUTED;
        return value > 0 ? GtswnGuiPalette.STATE_ONLINE
            : value < 0 ? GtswnGuiPalette.STATE_IDLE : GtswnGuiPalette.TEXT_BODY;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    static String ellipsis(FontRenderer font, String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) return "";
        if (font.getStringWidth(text) <= width) return text;
        String suffix = "...";
        int leadingEnd = 0;
        while (leadingEnd + 1 < text.length() && text.charAt(leadingEnd) == '§') leadingEnd += 2;
        String initial = text.substring(0, leadingEnd) + suffix;
        String best = font.getStringWidth(initial) <= width ? initial : "";
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < text.length();) {
            int next;
            if (text.charAt(index) == '§') {
                if (index + 1 == text.length()) break;
                next = index + 2;
            } else {
                next = text.offsetByCodePoints(index, 1);
            }
            prefix.append(text, index, next);
            String visible = prefix.toString();
            if (font.getStringWidth(visible) > width) break;
            String candidate = visible + suffix;
            if (font.getStringWidth(candidate) <= width) best = candidate;
            index = next;
        }
        return best;
    }

    /**
     * 全文字加粗 §l（v1.7.2 列表全加粗）：开头拼 {@code §l}，且每个颜色码（§0-§f 会重置
     * 加粗状态）之后重拼一次，保证与既有 § 颜色码共存时加粗贯穿整串；格式码（§k-§o）
     * 不重置状态无需重拼。
     */
    static String applyBold(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder sb = new StringBuilder("§l");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c == '§' && i + 1 < text.length()) {
                char code = text.charAt(++i);
                sb.append(code);
                if ("0123456789abcdef".indexOf(Character.toLowerCase(code)) >= 0) {
                    sb.append('§')
                        .append('l');
                }
            }
        }
        return sb.toString();
    }
}
