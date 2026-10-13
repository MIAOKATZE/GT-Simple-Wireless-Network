package com.miaokatze.gtswn.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.PortablePanelView;
import com.miaokatze.gtswn.client.render.RenderNetworkInfoPanel;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

/** Remote content viewer plus all client HUD preferences. Shared screen configuration stays on site. */
public final class GuiPortableMonitor extends GuiScreen {

    private final ItemStack stack;
    private boolean settings;
    private int settingsPage;
    private String[] numericEdits;
    private int selected, page, tab;
    private int left, top, panelWidth, panelHeight, visibleRows;
    private String notice = "";
    private final List<GuiTextField> fields = new ArrayList<>();
    private final List<PacketSyncPortableMonitor.Row> shownRows = new ArrayList<>();
    private PacketSyncPortableMonitor lastSnapshot;
    private boolean lastStale;
    private final RenderNetworkInfoPanel fullRenderer = new RenderNetworkInfoPanel();
    private float chartZoom = 1;
    private float panX, panY;
    private int dragX, dragY;
    private final java.nio.IntBuffer oldScissorBox = org.lwjgl.BufferUtils.createIntBuffer(16);
    private PacketSyncPortableMonitor.Row footerRow;
    private boolean manualTab, pinManager;

    public GuiPortableMonitor(ItemStack stack) {
        this.stack = stack;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.monitor." + key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        panelWidth = Math.min(960, width - 16);
        panelHeight = Math.min(620, height - 16);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        visibleRows = Math.max(1, Math.min(8, (panelHeight - 174) / 20));
        rebuild();
    }

    private void add(int id, int x, int y, int w, String label) {
        buttonList.add(new GuiButton(id, left + x, top + y, w, 18, label));
    }

    private void rebuild() {
        buttonList.clear();
        fields.clear();
        footerRow = null;
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
                GuiTextField field = new GuiTextField(
                    fontRendererObj,
                    left + 100 + col * (panelWidth / 2),
                    top + 43 + row * 25,
                    Math.max(32, panelWidth / 2 - 116),
                    18);
                field.setMaxStringLength(8);
                field.setText(numericEdits[i]);
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
            return;
        }
        add(0, 12, 34, 30, "<");
        add(1, panelWidth - 42, 34, 30, ">");
        int tabWidth = (panelWidth - 24) / 4;
        add(6, 12, 59, tabWidth - 3, "EU");
        add(7, 12 + tabWidth, 59, tabWidth - 3, tr("ae.chart"));
        add(8, 12 + tabWidth * 2, 59, tabWidth - 3, tr("ae.list"));
        add(4, 12 + tabWidth * 3, 59, tabWidth - 3, tr("remove"));
        add(5, 12, panelHeight - 28, 155, tr("mode." + currentMode()));
        if (tab == 2 && pinManager) {
            add(9, panelWidth - 88, panelHeight - 28, 32, "<");
            add(10, panelWidth - 48, panelHeight - 28, 32, ">");
        }
        if (tab == 2) add(45, 12, 82, 145, tr(pinManager ? "graph.native" : "graph.manage"));
        if (!(tab == 2 && pinManager)) {
            PacketSyncPortableMonitor.Panel current = panel();
            PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
            boolean graph = current != null && data != null
                && PortableMonitorClientCache.graphPinned(data.terminalId, current, tab);
            if (tab != 2) add(40, 12, 82, 145, (graph ? "\u2611 " : "\u2610 ") + tr("graph.pin"));
            add(41, panelWidth - 108, 82, 28, "-");
            add(42, panelWidth - 76, 82, 28, "+");
            add(43, panelWidth - 44, 82, 32, "1:1");
            if (tab != 2 && current != null && data != null) for (PacketSyncPortableMonitor.Row row : current.rows) {
                if (tab == 0 ? row.kind == 0 : row.key.equals(current.chartKey)) {
                    footerRow = row;
                    break;
                }
            }
            if (footerRow != null) add(
                44,
                panelWidth - 126,
                panelHeight - 28,
                114,
                (PortableMonitorClientCache.pinned(data.terminalId, current, footerRow) ? "\u2611 " : "\u2610 ")
                    + tr("pin.short"));
        }
        buildRows();
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

    private void buildRows() {
        shownRows.clear();
        PacketSyncPortableMonitor.Panel panel = panel();
        PacketSyncPortableMonitor snapshot = PortableMonitorClientCache.snapshot(stack);
        if (panel == null || !panel.detail || panel.state != 0 || PortableMonitorClientCache.stale(stack)) return;
        if (tab != 2 || !pinManager) return;
        List<PacketSyncPortableMonitor.Row> rows = new ArrayList<>();
        for (PacketSyncPortableMonitor.Row row : panel.rows)
            if (tab == 0 ? row.kind == 0 : row.kind != 0) rows.add(row);
        int pages = Math.max(1, (rows.size() + visibleRows - 1) / visibleRows);
        page = Math.min(page, pages - 1);
        for (int i = page * visibleRows; i < Math.min(rows.size(), (page + 1) * visibleRows); i++) {
            PacketSyncPortableMonitor.Row row = rows.get(i);
            shownRows.add(row);
            String label = (PortableMonitorClientCache.pinned(snapshot.terminalId, panel, row) ? "\u2611 " : "\u2610 ")
                + (row.kind == 0 ? tr("pin.short") : PortableMonitorClientCache.viewerRowText(row));
            add(
                100 + shownRows.size() - 1,
                row.kind == 0 ? panelWidth - 88 : 12,
                137 + (shownRows.size() - 1) * 20,
                row.kind == 0 ? 76 : panelWidth - 24,
                fontRendererObj.trimStringToWidth(label, row.kind == 0 ? 68 : panelWidth - 34));
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!PortableWirelessNetworkMonitor.isMonitorBound(stack) || mc.thePlayer == null) {
            mc.displayGuiScreen(null);
            return;
        }
        // Resolve the current server-authored slot reference, including delayed MonitorId synchronization.
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && held.getItem() instanceof PortableWirelessNetworkMonitor
            && (MonitorBindings.id(stack) == null || MonitorBindings.id(stack)
                .equals(MonitorBindings.id(held)))) {
            stack.stackTagCompound = held.stackTagCompound;
        }
        PortableMonitorClientCache.viewing(stack, selected);
        PortableMonitorClientCache.poll(stack);
        PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
        boolean stale = PortableMonitorClientCache.stale(stack);
        if (data != lastSnapshot || stale != lastStale) {
            lastSnapshot = data;
            lastStale = stale;
            PacketSyncPortableMonitor.Panel current = panel();
            if (!manualTab && current != null && current.detail) tab = current.tab;
            if (!settings) rebuild();
        }
        for (GuiTextField field : fields) field.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 3) {
            mc.displayGuiScreen(null);
            return;
        }
        if (button.id == 2) {
            rememberEdits();
            settings = !settings;
            notice = "";
            rebuild();
            return;
        }
        if (settings) {
            settingsAction(button.id);
            return;
        }
        PacketSyncPortableMonitor snapshot = PortableMonitorClientCache.snapshot(stack);
        PacketSyncPortableMonitor.Panel panel = panel();
        if (button.id == 0 || button.id == 1) {
            if (snapshot != null && !snapshot.panels.isEmpty())
                selected = Math.floorMod(selected + (button.id == 0 ? -1 : 1), snapshot.panels.size());
            page = 0;
            chartZoom = 1;
            panX = panY = 0;
        } else if (button.id >= 6 && button.id <= 8) {
            tab = button.id - 6;
            page = 0;
            manualTab = true;
        } else if (button.id == 4 && panel != null) PortableMonitorClientCache.action(stack, 1, selected);
        else if (button.id == 5) PortableMonitorClientCache.action(stack, 2, (currentMode() + 1) % 3);
        else if (button.id == 9) page = Math.max(0, page - 1);
        else if (button.id == 10) page++;
        else if (button.id == 40 && panel != null && snapshot != null) {
            notice = PortableMonitorClientCache.toggleGraph(snapshot.terminalId, panel, tab) ? "" : tr("save.failed");
            if (currentMode() == 0) PortableMonitorClientCache.action(stack, 2, 1);
        } else if (button.id == 41) chartZoom = Math.max(1, chartZoom / 1.25f);
        else if (button.id == 42) chartZoom = Math.min(4, chartZoom * 1.25f);
        else if (button.id == 43) {
            chartZoom = 1;
            panX = panY = 0;
        } else if (button.id == 44 && footerRow != null && panel != null && snapshot != null) {
            notice = PortableMonitorClientCache.togglePin(snapshot.terminalId, panel, footerRow) ? ""
                : tr("save.failed");
            if (currentMode() == 0) PortableMonitorClientCache.action(stack, 2, 1);
        } else if (button.id == 45) {
            pinManager = !pinManager;
            page = 0;
        } else if (button.id >= 100 && button.id < 100 + shownRows.size() && panel != null && snapshot != null) {
            notice = PortableMonitorClientCache.togglePin(snapshot.terminalId, panel, shownRows.get(button.id - 100))
                ? ""
                : tr("save.failed");
        }
        rebuild();
    }

    @Override
    public void onGuiClosed() {
        rememberEdits();
        PortableMonitorClientCache.viewing(stack, -1);
        super.onGuiClosed();
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
        // Preserve numeric edits while rebuilding toggle labels.
        List<String> edits = new ArrayList<>();
        for (GuiTextField field : fields) edits.add(field.getText());
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
                Config.hudPinnedEntries = new String[0];
                break;
            default:
                return;
        }
        notice = Config.saveHudConfiguration() ? tr("saved") : tr("save.failed");
        rebuild();
        for (int i = 0; i < edits.size(); i++) fields.get(i)
            .setText(edits.get(i));
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
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        dragX = x;
        dragY = y;
        for (GuiTextField field : fields) field.mouseClicked(x, y, button);
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == PortableMonitorClientCache.keyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        if (key == 1) {
            super.keyTyped(character, key);
            return;
        }
        for (GuiTextField field : fields) if (field.textboxKeyTyped(character, key)) return;
        super.keyTyped(character, key);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !settings) {
            if (!(tab == 2 && pinManager))
                chartZoom = Math.max(1, Math.min(4, chartZoom * (wheel > 0 ? 1.15f : 1 / 1.15f)));
            else page = Math.max(0, page + (wheel < 0 ? 1 : -1));
            rebuild();
        }
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long time) {
        if (!settings && !(tab == 2 && pinManager) && button == 0 && y >= top + 107 && y < top + panelHeight - 34) {
            panX += x - dragX;
            panY += y - dragY;
            dragX = x;
            dragY = y;
        }
    }

    private void drawFullChart(PortablePanelView view) {
        int x = left + 12, y = top + 107, w = panelWidth - 24, h = panelHeight - 144;
        drawRect(x, y, x + w, y + h, 0xffedf1f4);
        float fit = Math.min(w / 640f, h / 400f), scale = fit * chartZoom;
        float maxX = Math.max(0, (640 * scale - w) / 2), maxY = Math.max(0, (400 * scale - h) / 2);
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
        net.minecraft.client.gui.ScaledResolution resolution = new net.minecraft.client.gui.ScaledResolution(
            mc,
            mc.displayWidth,
            mc.displayHeight);
        int factor = resolution.getScaleFactor();
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        oldScissorBox.clear();
        GL11.glGetInteger(GL11.GL_SCISSOR_BOX, oldScissorBox);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * factor, mc.displayHeight - (y + h) * factor, w * factor, h * factor);
        GL11.glPushMatrix();
        GL11.glTranslatef(x + (w - 640 * scale) / 2 + panX, y + (h - 400 * scale) / 2 + panY, 0);
        GL11.glScalef(scale, scale, 1);
        view.selectTab(tab);
        try {
            fullRenderer.drawPortablePanel(view, 640, 400);
        } finally {
            GL11.glPopMatrix();
            GL11.glScissor(oldScissorBox.get(0), oldScissorBox.get(1), oldScissorBox.get(2), oldScissorBox.get(3));
            if (!scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        if (tab == 0 && (!view.energyAvailable() || !view.energySeriesAvailable())) fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(tr("graph.precision"), w - 8), x + 4, y + h - 12, 0xcc6622);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(left, top, left + panelWidth, top + panelHeight, 0xee101c29);
        drawRect(left, top, left + panelWidth, top + 2, 0xff54d7e0);
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(tr("title"), panelWidth - 154),
            left + 12,
            top + 12,
            0x65e5e9);
        if (settings) {
            String[] labels = { "x", "y", "scale", "width", "rows", "rotation", "interval" };
            for (int i = 0; i < fields.size(); i++) {
                fontRendererObj.drawString(
                    fontRendererObj.trimStringToWidth(tr("field." + labels[i]), 84),
                    left + 12 + (i / 4) * (panelWidth / 2),
                    top + 48 + (i % 4) * 25,
                    0xffffff);
                fields.get(i)
                    .drawTextBox();
            }
        } else {
            PacketSyncPortableMonitor.Panel panel = panel();
            PacketSyncPortableMonitor data = PortableMonitorClientCache.snapshot(stack);
            String title = panel == null ? tr("empty")
                : "[" + (selected + 1) + "/" + data.panels.size() + "] " + panel.binding.label();
            drawCenteredString(fontRendererObj, title, width / 2, top + 39, 0xffffff);
            if (panel != null && panel.detail && panel.state == 0 && !PortableMonitorClientCache.stale(stack)) {
                if (!(tab == 2 && pinManager)) {
                    PortablePanelView view = PortableMonitorClientCache.view(stack, selected);
                    if (view != null) drawFullChart(view);
                    else drawCenteredString(fontRendererObj, tr("state.4"), width / 2, top + 119, 0xffaa55);
                } else fontRendererObj.drawString(tr("pin.hint"), left + 12, top + 111, 0x9dd8d8);
            } else if (panel != null) drawCenteredString(
                fontRendererObj,
                PortableMonitorClientCache.stateText(panel.state == 0 ? 4 : panel.state),
                width / 2,
                top + 104,
                0xffaa55);
            else drawCenteredString(fontRendererObj, tr("bind.hint"), width / 2, top + 102, 0xaaaaaa);
            if (tab == 2 && pinManager) fontRendererObj
                .drawString(tr("page") + " " + (page + 1), left + panelWidth - 155, top + panelHeight - 22, 0xaaaaaa);
        }
        if (!notice.isEmpty()) drawCenteredString(fontRendererObj, notice, width / 2, top + panelHeight - 43, 0xffcc77);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!settings) {
            for (Object candidate : buttonList) {
                GuiButton button = (GuiButton) candidate;
                int index = button.id - 100;
                if (index < 0 || index >= shownRows.size()
                    || mouseX < button.xPosition
                    || mouseX >= button.xPosition + button.width
                    || mouseY < button.yPosition
                    || mouseY >= button.yPosition + button.height) continue;
                PacketSyncPortableMonitor.Row row = shownRows.get(index);
                String unit = row.kind == 0 ? "EU/t" : row.kind == 2 ? "mB/min" : "/min";
                List<String> tooltip = new ArrayList<>();
                tooltip.add(row.name + ": " + row.amount + (row.kind == 2 ? " mB" : row.kind == 0 ? " EU" : ""));
                tooltip.add(tr("field.instant") + ": " + PortableMonitorClientCache.rate(row.rate, unit));
                tooltip.add(tr("field.average") + ": " + PortableMonitorClientCache.rate(row.average, unit));
                if (row.kind == 0 && panel() != null)
                    tooltip.add(tr("grid.status") + ": " + PortableMonitorClientCache.gridStatus(panel(), row));
                tooltip.add(tr("pin.hint"));
                drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
                break;
            }
        }
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
