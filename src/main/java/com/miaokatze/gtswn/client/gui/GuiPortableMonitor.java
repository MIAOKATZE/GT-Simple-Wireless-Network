package com.miaokatze.gtswn.client.gui;

import static net.minecraft.client.gui.Gui.drawRect;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.PortableMonitorSelection;
import com.miaokatze.gtswn.client.PortablePanelView;
import com.miaokatze.gtswn.client.render.RenderNetworkInfoPanel;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

/** Remote content viewer plus all client HUD preferences. Shared screen configuration stays on site. */
public final class GuiPortableMonitor extends GtswnMuiScreen {

    private ItemStack stack;
    private boolean settings;
    private int settingsPage;
    private String[] numericEdits;
    private int selected, page, tab;
    private final int left = 0, top = 0, panelWidth = 640, panelHeight = 420, visibleRows = 8;
    private final ParentWidget<?> content = new ParentWidget<>().name("portable.body")
        .size(640, 420);
    private String notice = "";
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<PacketSyncPortableMonitor.Row> shownRows = new ArrayList<>();
    private PacketSyncPortableMonitor lastSnapshot;
    private boolean lastStale;
    private final RenderNetworkInfoPanel fullRenderer = new RenderNetworkInfoPanel();
    private float chartZoom = 1;
    private float panX, panY;
    private int dragX, dragY;
    private boolean manualTab, pinManager;
    private UUID viewedPanel;
    private boolean rebuildPending;
    private String pendingRemoval;

    public GuiPortableMonitor(ItemStack stack) {
        super("portable_monitor", 640, 420);
        this.stack = stack;
        getMainPanel().child(content);
        buildPanel();
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.monitor." + key);
    }

    private void add(int id, int x, int y, int w, String label) {
        ButtonWidget<?> button = GtswnMuiWidgets
            .button(buttonName(id), () -> label, x, y, w, 18, () -> buttonAvailable(id), () -> action(id));
        if (id >= 6 && id <= 8 && buttonAvailable(id)) {
            button.background(tab == id - 6 ? GtswnMuiTheme.TAB_ACTIVE : GtswnMuiTheme.TAB_INACTIVE);
        }
        if (id == 4) button.tooltipBuilder(t -> t.addLine(tr("remove.current")));
        content.child(button);
    }

    private boolean buttonAvailable(int id) {
        if (id == 4 || id == 5)
            return PortableMonitorClientCache.canMutate(stack) && PortableMonitorClientCache.mutationStatus(stack) != 4
                && (id == 5 || panel() != null);
        if (!pinManager && id >= 6 && id <= 8) {
            PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
            return data != null && PortableMonitorSelection.sourceIndex(data.panels, selected, id - 6) >= 0;
        }
        return true;
    }

    private static String buttonName(int id) {
        switch (id) {
            case 2:
                return "portable.settings";
            case 3:
                return "portable.close";
            case 6:
                return "portable.tab.eu";
            case 7:
                return "portable.tab.ae";
            case 8:
                return "portable.tab.list";
            case 31:
                return "portable.settings.effects";
            case 45:
                return "portable.pins";
            default:
                return "portable.action." + id;
        }
    }

    private void rebuild() {
        rebuildPending = true;
    }

    private void buildPanel() {
        content.removeAll();
        content.child(
            GtswnMuiWidgets.canvas(
                "portable.content",
                0,
                0,
                panelWidth,
                panelHeight,
                (context, mouseX, mouseY, partialTicks) -> drawCanvas(mouseX, mouseY, partialTicks)));
        content.child(
            new Widget<>().name("portable.status")
                .pos(12, panelHeight - 44)
                .size(panelWidth - 24, 12)
                .background(IDrawable.EMPTY)
                .tooltipBuilder(tooltip -> {
                    String status = statusText();
                    if (!status.isEmpty()) tooltip.addLine(status);
                })
                .tooltipAutoUpdate(true));
        fields.clear();
        add(3, panelWidth - 58, 7, 50, tr("close"));
        add(2, panelWidth - 135, 7, 70, tr(settings ? "viewer" : "settings"));
        if (settings) {
            String[] values = { Integer.toString(Config.hudXOffset), Integer.toString(Config.hudYOffset),
                Float.toString(Config.hudScale), Integer.toString(Config.hudPanelWidth),
                Integer.toString(Config.hudPinnedRows), Integer.toString(Config.hudRotationSeconds),
                Integer.toString(Config.hudUpdateInterval) };
            if (numericEdits == null) numericEdits = values;
            if (settingsPage == 0) for (int i = 0; i < values.length; i++) {
                int col = i / 4, row = i % 4;
                TextFieldWidget field = GtswnMuiWidgets.textField(
                    "portable.field." + i,
                    numericEdits[i],
                    100 + col * (panelWidth / 2),
                    43 + row * 25,
                    Math.max(32, panelWidth / 2 - 116),
                    18,
                    8);
                content.child(field);
                fields.add(field);
            }
            String[] labels = { "charge", "eu", "instant", "average", "compact", "tier", "amount", "rate" };
            boolean[] switches = { Config.hudChargeEnabled, Config.hudEUEnabled, Config.hudInstantEnabled,
                Config.hudAverageEnabled, Config.hudCompact, Config.hudTierEnabled, Config.hudAEAmountEnabled,
                Config.hudAERateEnabled };
            if (settingsPage == 1) {
                for (int i = 0; i < labels.length; i++) add(
                    20 + i,
                    12 + (i % 2) * (panelWidth / 2),
                    43 + (i / 2) * 22,
                    panelWidth / 2 - 18,
                    (switches[i] ? "\u2611 " : "\u2610 ") + tr("field." + labels[i]));
                add(28, 12, 139, panelWidth / 2 - 18, tr("render." + Config.hudRenderMode));
                add(29, panelWidth / 2 + 12, 139, panelWidth / 2 - 24, tr("pins.clear"));
            }
            add(
                31,
                12,
                panelHeight - 28,
                panelWidth / 2 - 18,
                tr(settingsPage == 0 ? "settings.display" : "settings.layout"));
            add(30, panelWidth / 2 + 12, panelHeight - 28, panelWidth / 2 - 24, tr("save"));
            if (content.isValid()) content.scheduleResize();
            return;
        }
        add(0, 12, 34, 30, "<");
        add(1, panelWidth - 42, 34, 30, ">");
        int tabWidth = (panelWidth - 24) / 4;
        add(6, 12, 59, tabWidth - 3, "EU");
        add(7, 12 + tabWidth, 59, tabWidth - 3, tr("ae.chart"));
        add(8, 12 + tabWidth * 2, 59, tabWidth - 3, tr("ae.list"));
        add(4, 12 + tabWidth * 3, 59, tabWidth - 3, tr("remove.current"));
        add(5, 12, panelHeight - 28, 155, tr(currentMode() == 0 ? "hud.off" : "hud.on"));
        if (pinManager) {
            add(9, panelWidth - 88, panelHeight - 28, 32, "<");
            add(10, panelWidth - 48, panelHeight - 28, 32, ">");
        }
        add(
            45,
            12,
            82,
            tab == 2 || pinManager ? panelWidth - 24 : panelWidth - 126,
            tr(pinManager ? "graph.native" : "graph.manage"));
        if (!pinManager) {
            if (tab != 2) {
                add(41, panelWidth - 108, 82, 28, "-");
                add(42, panelWidth - 76, 82, 28, "+");
                add(43, panelWidth - 44, 82, 32, "1:1");
            }
        }
        buildRows();
        if (!pinManager) content.child(
            new ChartWidget().name("portable.chart")
                .pos(12, 107)
                .size(panelWidth - 24, panelHeight - 156)
                .background(IDrawable.EMPTY));
        if (content.isValid()) content.scheduleResize();
    }

    private int currentMode() {
        PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
        return data == null ? PortableWirelessNetworkMonitor.getHudMode(stack) : data.hudMode;
    }

    private PacketSyncPortableMonitor.Panel panel() {
        PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
        if (data == null || data.panels.isEmpty()) return null;
        selected = Math.min(selected, data.panels.size() - 1);
        return data.panels.get(selected);
    }

    private boolean followPanel(PacketSyncPortableMonitor.Panel current) {
        if (current == null || current.binding.panelId.equals(viewedPanel)) return false;
        viewedPanel = current.binding.panelId;
        manualTab = false;
        page = 0;
        chartZoom = 1;
        panX = panY = 0;
        tab = current.tab;
        return true;
    }

    private void buildRows() {
        shownRows.clear();
        PacketSyncPortableMonitor.Panel current = panel();
        PacketSyncPortableMonitor snapshot = PortableMonitorClientCache.snapshot(stack);
        if (current == null || snapshot == null || !pinManager) return;
        List<PacketSyncPortableMonitor.Row> rows = PortableMonitorClientCache.managementRows(
            snapshot.terminalId,
            current,
            Config.hudPinnedEntries,
            !PortableMonitorClientCache.stale(stack),
            tr("pins.unavailable"),
            tab);
        int pages = Math.max(1, (rows.size() + visibleRows - 1) / visibleRows);
        page = Math.min(page, pages - 1);
        PinList list = new PinList().name("portable.pin.entries")
            .pos(12, 137)
            .size(panelWidth - 24, panelHeight - 190)
            .background(GtswnMuiTheme.LIST_PANEL);
        for (int i = page * visibleRows; i < Math.min(rows.size(), (page + 1) * visibleRows); i++) {
            PacketSyncPortableMonitor.Row entry = rows.get(i);
            shownRows.add(entry);
            int index = shownRows.size() - 1;
            ParentWidget<?> row = new ParentWidget<>().widthRel(1f)
                .height(20)
                .background(IDrawable.EMPTY);
            int count = entry.kind == 1 || entry.kind == 2 ? 2 : 1;
            int fieldWidth = count == 2 ? (panelWidth - 40) / 2 : panelWidth - 36;
            for (int field = 0; field < count; field++) {
                int id = 100 + index * 2 + field;
                boolean pinned = count == 2
                    ? PortableMonitorClientCache.stockPinned(snapshot.terminalId, current, entry, field == 1)
                    : PortableMonitorClientCache.pinned(snapshot.terminalId, current, entry);
                String label = (pinned ? "\u2611 " : "\u2610 ")
                    + (count == 2 ? tr("pin.stock." + (field == 0 ? "instant" : "average")) + " | " + entry.name
                        : entry.kind == 3 ? entry.name
                            : PortableMonitorClientCache.viewerRowText(entry, current.displayMode));
                ButtonWidget<?> button = GtswnMuiWidgets.button(
                    "portable.pin." + id,
                    fontRendererObj.trimStringToWidth(label, fieldWidth - 8),
                    field * (fieldWidth + 4),
                    0,
                    fieldWidth,
                    18,
                    () -> action(id));
                button.tooltipBuilder(t -> { for (String line : rowTooltip(entry)) t.addLine(line); });
                row.child(button);
            }
            list.child(row);
        }
        content.child(list);
    }

    private List<String> rowTooltip(PacketSyncPortableMonitor.Row row) {
        if (row.kind == 3) return java.util.Arrays.asList(row.name, tr("pin.components.hint"));
        String unit = row.kind == 0 ? "EU/t" : row.kind == 2 ? "mB/min" : "/min";
        List<String> tooltip = new ArrayList<>();
        tooltip.add(row.name + ": " + row.amount + (row.kind == 2 ? " mB" : row.kind == 0 ? " EU" : ""));
        tooltip.add(tr("field.instant") + ": " + PortableMonitorClientCache.rate(row.rate, unit));
        tooltip.add(
            tr(row.kind == 0 ? "field.average" : "pin.stock.average") + ": "
                + PortableMonitorClientCache.rate(row.average, unit));
        if (row.kind == 0 && panel() != null)
            tooltip.add(tr("grid.status") + ": " + PortableMonitorClientCache.gridStatus(panel(), row));
        tooltip.add(tr(row.kind == 0 ? "pin.hint" : "pin.stock.hint"));
        return tooltip;
    }

    private final class PinList extends ListWidget<IWidget, PinList> {

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            page = Math.max(0, page + (direction.isDown() ? 1 : -1));
            rebuild();
            return true;
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (rebuildPending) {
            rebuildPending = false;
            buildPanel();
        }
        stack = PortableMonitorClientCache.currentStack(stack);
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack) || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        PortableMonitorClientCache.viewing(stack, selected);
        PortableMonitorClientCache.poll(stack);
        PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
        boolean stale = PortableMonitorClientCache.stale(stack);
        PacketSyncPortableMonitor.Panel current = panel();
        boolean panelChanged = followPanel(current);
        if (!pinManager && current != null) tab = current.tab;
        int mutation = PortableMonitorClientCache.mutationStatus(stack);
        PacketSyncPortableMonitor ack = PortableMonitorClientCache.mutationResponse(stack);
        if (pendingRemoval != null && (mutation == 1 || mutation == 3) && ack != null) {
            notice = StatCollector
                .translateToLocalFormatted("gtswn.monitor.remove.done", pendingRemoval, ack.panels.size());
            pendingRemoval = null;
        } else if (pendingRemoval != null && mutation == 2) pendingRemoval = null;
        if (data != null && data.panels.isEmpty()) viewedPanel = null;
        if (data != lastSnapshot || stale != lastStale || panelChanged) {
            lastSnapshot = data;
            lastStale = stale;
            if (!manualTab && !pinManager && current != null) tab = current.tab;
            if (!settings) rebuild();
        }
    }

    private void action(int id) {
        stack = PortableMonitorClientCache.currentStack(stack);
        if (id == 3) {
            mc.displayGuiScreen(null);
            return;
        }
        if (id == 2) {
            rememberEdits();
            settings = !settings;
            notice = "";
            rebuild();
            return;
        }
        if (settings) {
            settingsAction(id);
            return;
        }
        PacketSyncPortableMonitor snapshot = PortableMonitorClientCache.snapshot(stack);
        PacketSyncPortableMonitor.Panel panel = panel();
        if (id == 0 || id == 1) {
            if (snapshot != null && !snapshot.panels.isEmpty())
                selected = Math.floorMod(selected + (id == 0 ? -1 : 1), snapshot.panels.size());
            followPanel(panel());
        } else if (id >= 6 && id <= 8) {
            if (pinManager) {
                tab = id - 6;
                manualTab = true;
            } else if (snapshot != null) {
                int source = PortableMonitorSelection.sourceIndex(snapshot.panels, selected, id - 6);
                if (source < 0) return;
                selected = source;
                followPanel(panel());
                tab = panel().tab;
                manualTab = false;
                PortableMonitorClientCache.viewing(stack, selected);
                PortableMonitorClientCache.poll(stack);
            }
            page = 0;
        } else if (id == 4 && panel != null) {
            if (PortableMonitorClientCache.action(stack, 1, selected)) {
                pendingRemoval = sourceLabel(panel);
                notice = "";
            }
        } else if (id == 5) PortableMonitorClientCache.action(stack, 2, currentMode() == 0 ? 1 : 0);
        else if (id == 9) page = Math.max(0, page - 1);
        else if (id == 10) page++;
        else if (id == 41) chartZoom = Math.max(1, chartZoom / 1.25f);
        else if (id == 42) chartZoom = Math.min(4, chartZoom * 1.25f);
        else if (id == 43) {
            chartZoom = 1;
            panX = panY = 0;
        } else if (id == 45) {
            pinManager = !pinManager;
            if (!pinManager && panel != null) {
                tab = panel.tab;
                manualTab = false;
            }
            page = 0;
        } else if (id >= 100 && id < 100 + shownRows.size() * 2 && panel != null && snapshot != null) {
            PacketSyncPortableMonitor.Row row = shownRows.get((id - 100) / 2);
            boolean saved = row.kind == 1 || row.kind == 2
                ? PortableMonitorClientCache.toggleStockPin(snapshot.terminalId, panel, row, (id - 100) % 2 == 1)
                : PortableMonitorClientCache.togglePin(snapshot.terminalId, panel, row);
            notice = saved ? "" : tr("save.failed");
            enablePinnedHud();
        }
        rebuild();
    }

    private void enablePinnedHud() {
        if (currentMode() != 0 || !PortableMonitorClientCache.canMutate(stack)) return;
        String prefix = MonitorBindings.id(stack) + "/";
        for (String pin : Config.hudPinnedEntries) if (pin.startsWith(prefix)) {
            PortableMonitorClientCache.action(stack, 2, 1);
            break;
        }
    }

    @Override
    public void onClose() {
        rememberEdits();
        PortableMonitorClientCache.viewing(stack, -1);
        super.onClose();
    }

    private void settingsAction(int id) {
        if (id == 31) {
            rememberEdits();
            settingsPage = 1 - settingsPage;
            rebuild();
            return;
        }
        if (id == 30) {
            saveFields();
            return;
        }
        rememberEdits();
        switch (id) {
            case 20:
                Config.hudChargeEnabled = !Config.hudChargeEnabled;
                break;
            case 21:
                Config.hudEUEnabled = !Config.hudEUEnabled;
                break;
            case 22:
                Config.hudInstantEnabled = !Config.hudInstantEnabled;
                break;
            case 23:
                Config.hudAverageEnabled = !Config.hudAverageEnabled;
                break;
            case 24:
                Config.hudCompact = !Config.hudCompact;
                break;
            case 25:
                Config.hudTierEnabled = !Config.hudTierEnabled;
                break;
            case 26:
                Config.hudAEAmountEnabled = !Config.hudAEAmountEnabled;
                break;
            case 27:
                Config.hudAERateEnabled = !Config.hudAERateEnabled;
                break;
            case 28:
                Config.hudRenderMode = (Config.hudRenderMode + 1) % 3;
                break;
            case 29:
                String prefix = MonitorBindings.id(stack) + "/";
                Config.hudPinnedEntries = java.util.Arrays.stream(Config.hudPinnedEntries)
                    .filter(pin -> !pin.startsWith(prefix))
                    .toArray(String[]::new);
                break;
            default:
                return;
        }
        notice = Config.saveHudConfiguration() ? tr("saved") : tr("save.failed");
        rebuild();
    }

    private void saveFields() {
        rememberEdits();
        try {
            int x = integer(0, -500, 500), y = integer(1, -500, 500);
            float scale = Float.parseFloat(numericEdits[2]);
            if (!Float.isFinite(scale) || scale < .2 || scale > 5) throw new IllegalArgumentException();
            int w = integer(3, 100, 600), rows = integer(4, 1, 16), seconds = integer(5, 1, 120),
                interval = integer(6, 1, 600);
            Config.hudXOffset = x;
            Config.hudYOffset = y;
            Config.hudScale = scale;
            Config.hudPanelWidth = w;
            Config.hudPinnedRows = rows;
            Config.hudRotationSeconds = seconds;
            Config.hudUpdateInterval = interval;
            notice = Config.saveHudConfiguration() ? tr("saved") : tr("save.failed");
        } catch (IllegalArgumentException invalid) {
            notice = tr("invalid");
        }
    }

    private int integer(int index, int min, int max) {
        int value = Integer.parseInt(numericEdits[index]);
        if (value < min || value > max) throw new IllegalArgumentException();
        return value;
    }

    private void rememberEdits() {
        if (fields.size() == 7) for (int i = 0; i < fields.size(); i++) numericEdits[i] = fields.get(i)
            .getText();
    }

    @Override
    public boolean onKeyPressed(char character, int key) {
        if (!getContext().isFocused() && key == PortableMonitorClientCache.keyCode()) {
            mc.displayGuiScreen(null);
            return true;
        }
        return super.onKeyPressed(character, key);
    }

    private final class ChartWidget extends Widget<ChartWidget> implements Interactable {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            PacketSyncPortableMonitor.Panel current = panel();
            if (current == null || !current.detail || current.state != 0 || PortableMonitorClientCache.stale(stack))
                return;
            PortablePanelView view = PortableMonitorClientCache.view(stack, selected);
            if (view == null) return;
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            try {
                drawFullChart(view, context);
            } finally {
                GL11.glPopMatrix();
                GL11.glPopAttrib();
            }
        }

        @Override
        public Result onMousePressed(int mouseButton) {
            if (mouseButton != 0) return Result.IGNORE;
            dragX = getContext().getMouseX();
            dragY = getContext().getMouseY();
            return Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(int mouseButton, long timeSinceClick) {
            if (mouseButton != 0) return;
            int x = getContext().getMouseX(), y = getContext().getMouseY();
            panX += x - dragX;
            panY += y - dragY;
            dragX = x;
            dragY = y;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            chartZoom = Math.max(1, Math.min(4, chartZoom * (direction.isUp() ? 1.15f : 1 / 1.15f)));
            return true;
        }
    }

    private void drawFullChart(PortablePanelView view, ModularGuiContext context) {
        int x = 0, y = 0, w = panelWidth - 24, h = panelHeight - 156;
        drawRect(x, y, x + w, y + h, 0xffedf1f4);
        float scale = chartZoom;
        float maxX = Math.max(0, (w * scale - w) / 2), maxY = Math.max(0, (h * scale - h) / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        Stencil.apply(0, 0, w, h, context);
        // The stencil rectangle leaves colour drawing active; legacy fonts need their texture state restored.
        Platform.setupDrawFont();
        GL11.glPushMatrix();
        GL11.glTranslatef(x + (w - w * scale) / 2 + panX, y + (h - h * scale) / 2 + panY, 0);
        GL11.glScalef(scale, scale, 1);
        view.selectTab(tab);
        try {
            fullRenderer.drawPortablePanel(view, w, h);
        } finally {
            GL11.glPopMatrix();
            Stencil.remove();
            Platform.setupDrawFont();
        }
        if (tab == 0 && (!view.energyAvailable() || !view.energySeriesAvailable())) fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(tr("graph.precision"), w - 8), x + 4, y + h - 12, 0xcc6622);
    }

    private static String sourceLabel(PacketSyncPortableMonitor.Panel panel) {
        String type = panel.tab == 0 ? "EU" : tr(panel.tab == 1 ? "ae.chart" : "ae.list");
        return type + " · " + panel.binding.label();
    }

    private void drawCanvas(int mouseX, int mouseY, float partialTicks) {
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(tr("title"), panelWidth - 154),
            left + 12,
            top + 12,
            GtswnGuiPalette.TEXT_TITLE);
        if (settings) {
            String[] labels = { "x", "y", "scale", "width", "rows", "rotation", "interval" };
            for (int i = 0; i < fields.size(); i++) {
                fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(tr("field." + labels[i]), 84),
                    left + 12 + (i / 4) * (panelWidth / 2),
                    top + 48 + (i % 4) * 25,
                    0xffffff);

            }
        } else {
            PacketSyncPortableMonitor.Panel panel = panel();
            PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
            String title = panel == null ? tr("empty")
                : "[" + (selected + 1) + "/" + data.panels.size() + "] " + sourceLabel(panel);
            drawCenteredString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(title, panelWidth - 110),
                panelWidth / 2,
                top + 39,
                GtswnGuiPalette.TEXT_BODY);
            if (pinManager) {
                fontRendererObj.drawSplitString(
                    tr(shownRows.isEmpty() ? "pins.empty" : "pin.hint"),
                    left + 12,
                    top + 111,
                    panelWidth - 24,
                    GtswnGuiPalette.TEXT_BODY);
            } else if (panel != null && panel.detail && panel.state == 0 && !PortableMonitorClientCache.stale(stack)) {
                PortablePanelView view = PortableMonitorClientCache.view(stack, selected);
                if (view == null)
                    drawCenteredString(fontRendererObj, tr("state.4"), panelWidth / 2, top + 119, 0xffaa55);
            } else if (panel != null) drawCenteredString(
                fontRendererObj,
                PortableMonitorClientCache.stateText(panel.state == 0 ? 4 : panel.state),
                panelWidth / 2,
                top + 104,
                0xffaa55);
            else drawCenteredString(fontRendererObj, tr("bind.hint"), panelWidth / 2, top + 102, 0xaaaaaa);
            if (pinManager) fontRendererObj
                .drawString(tr("page") + " " + (page + 1), left + panelWidth - 155, top + panelHeight - 22, 0xaaaaaa);
        }
        String status = statusText();
        if (!status.isEmpty()) drawCenteredString(
            fontRendererObj,
            fontRendererObj.trimStringToWidth(status, panelWidth - 24),
            panelWidth / 2,
            top + panelHeight - 43,
            GtswnGuiPalette.TEXT_ACCENT);
    }

    private String statusText() {
        int mutation = PortableMonitorClientCache.mutationStatus(stack);
        return !notice.isEmpty() ? notice
            : !settings && mutation != 0 ? tr("action." + mutation)
                : !settings && !PortableMonitorClientCache.canMutate(stack) ? tr("action.readonly") : "";
    }

    private static void drawCenteredString(net.minecraft.client.gui.FontRenderer font, String text, int x, int y,
        int color) {
        font.drawStringWithShadow(text, x - font.getStringWidth(text) / 2, y, color);
    }

    /** Normalized line strip, with a visible half-height baseline for a constant series. */
    public static void drawChart(double[] values, int x, int y, int w, int h, int color) {
        if (values.length == 0) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f((color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        for (int i = 0; i < values.length; i++)
            GL11.glVertex2d(x + i * w / (double) Math.max(1, values.length - 1), y + h * (1 - values[i]));
        GL11.glEnd();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }
}
