package com.miaokatze.gtswn.client.engineering;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.device.DevicePowerCapabilities;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;

/** Cockpit over existing authorized caches. No sampling or network work occurs in draw methods. */
public final class FactoryDashboard extends Gui {

    private static final int BACK = 0xFF15202A, CARD = 0xFFF0F0E8, EDGE = 0xFFD1D9D9;
    private static final int TEXT = 0xFF253C48, MUTED = 0xFF617681, CYAN = 0xFF287F78, AMBER = 0xFF9D6F33;
    private static final List<Hit> hits = new ArrayList<>();
    private static int screenIndex, resourceKind = 1, resourcePage, window;
    private static final int preset = 0;
    private static boolean rate;
    private static List<Entry> devices = Collections.emptyList(), attention = Collections.emptyList();
    private static long deviceRevision = Long.MIN_VALUE;
    private static boolean deviceFresh;
    private static java.util.UUID deviceSource;
    private static int running, idle, stopped, unknown;
    private static List<Panel> panels = Collections.emptyList();
    private static Panel chartPanel;
    private static String resourceKey = "";
    private static int selectedResourceKind = 1;
    private static FactoryTrend plot;
    private static long[] ticks = new long[0];
    private static String[] exact = new String[0];
    private static String plotUnit = "EU", plotWindow = "", plotSource = "", hover;
    private static boolean relative;
    private static final FactoryTextSearch search = new FactoryTextSearch();
    private static Rect searchRect;
    private static List<Row> resources = Collections.emptyList();
    private static int resourceSort;
    private static String resourceSignature = "";
    private static Panel resourcePanel;
    private static java.util.UUID portableSource;
    private static String selectedScreen = "";
    private static String portableBinding = "";
    private static WidgetSpec painting;

    private FactoryDashboard() {}

    static void restore() {
        deviceRevision = Long.MIN_VALUE;
        portableSource = null;
        portableBinding = "";
        selectedScreen = "";
        chartPanel = null;
    }

    static void remember() {
        if (portableSource != null && !selectedScreen.isEmpty())
            FactoryDisplayPreferences.text("cockpit_screen_" + portableSource, selectedScreen);
    }

    static void clear() {
        FactoryDesktop.suspend();
        search.focused = false;
        devices = attention = Collections.emptyList();
        panels = Collections.emptyList();
        chartPanel = null;
        portableSource = null;
        portableBinding = "";
        selectedScreen = "";
        plot = null;
        ticks = new long[0];
        exact = new String[0];
        hits.clear();
    }

    static void tick(EngineeringTerminalHudSnapshot snapshot) {
        FactoryDesktop.tick(WorkbenchHud.dashboardVisible());
        EngineeringTerminalHudSnapshot.Source source = snapshot.device;
        if (deviceRevision != source.revision || deviceFresh != source.fresh
            || !java.util.Objects.equals(deviceSource, source.terminalId)) {
            deviceSource = source.terminalId;
            deviceRevision = source.revision;
            deviceFresh = source.fresh;
            devices = source.devices;
            running = idle = stopped = unknown = 0;
            List<Entry> warnings = new ArrayList<>();
            for (Entry entry : devices) {
                String state = FactoryMachineFilter.status(entry, source.fresh);
                if ("running".equals(state)) running++;
                else if ("idle".equals(state)) idle++;
                else if ("stopped".equals(state)) stopped++;
                if (!FactoryMachineFilter.ignored(entry) && entry.evidenceKind == 3) unknown++;
                if (FactoryMachineFilter.ATTENTION.matches(entry, source.fresh, DevicePowerFilter.ALL))
                    warnings.add(entry);
            }
            attention = Collections.unmodifiableList(warnings);
        }
        panels = PortableSuperDashboard.panels();
        com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source actual = com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources
            .resolve(Minecraft.getMinecraft().thePlayer, 0);
        String nextBinding = actual.origin + ":" + actual.binding;
        if (!java.util.Objects.equals(portableSource, snapshot.portable.terminalId)
            || !portableBinding.equals(nextBinding)) {
            portableBinding = nextBinding;
            portableSource = snapshot.portable.terminalId;
            selectedScreen = portableSource == null ? ""
                : FactoryDisplayPreferences.text("cockpit_screen_" + portableSource);
            resourceKey = "";
            resourcePage = 0;
        }
        List<String> identities = PortableSuperDashboard.screenIds();
        if (selectedScreen.isEmpty() && !identities.isEmpty()) selectedScreen = identities.get(0);
        screenIndex = identities.indexOf(selectedScreen);
        Panel panel = selectedPanel(panels, selectedScreen);
        if (panel != chartPanel) {
            chartPanel = panel;
            rebuildPlot();
        }
        String signature = resourceKind + ":" + resourceSort + ":" + search.value;
        if (resourcePanel != panel || !resourceSignature.equals(signature)) {
            resourcePanel = panel;
            resourceSignature = signature;
            List<Row> filtered = new ArrayList<>();
            if (PortableSuperDashboard.available(panel))
                for (Row row : panel.rows) if (row.kind == resourceKind && search.matches(row.name)) filtered.add(row);
            filtered.sort(
                (a, b) -> resourceSort == 2 ? Double.compare(b.rate, a.rate)
                    : resourceSort == 1 ? amount(b.amount).compareTo(amount(a.amount))
                        : a.name.compareToIgnoreCase(b.name));
            resources = Collections.unmodifiableList(filtered);
        }
    }

    static Panel selectedPanel(List<Panel> values, String identity) {
        for (Panel panel : values) if (panel.binding != null && panel.binding.panelId.toString()
            .equals(identity)) return panel;
        return null;
    }

    private static BigInteger amount(String value) {
        try {
            return new BigInteger(value);
        } catch (NumberFormatException ignored) {
            return BigInteger.ZERO;
        }
    }

    private static void rebuildPlot() {
        plot = null;
        ticks = new long[0];
        exact = new String[0];
        Panel p = chartPanel;
        if (!PortableSuperDashboard.available(p)) return;
        if (!resourceKey.isEmpty() && !resourceKey.equals(p.chartKey)) return;
        boolean ae = !resourceKey.isEmpty() && resourceKey.equals(p.chartKey);
        plotUnit = ae ? (selectedResourceKind == 2 ? rate ? "mB/min" : "mB" : rate ? "/min" : tr("items"))
            : rate ? "EU/t" : "EU";
        plotWindow = ae ? p.aeWindow : p.window;
        plotSource = p.binding.label();
        relative = p.render == null;
        if (relative) {
            double[] values = ae ? rate ? p.aeRateChart : p.aeChart : rate ? p.euRateChart : p.euChart;
            int first = Math.max(0, values.length - Math.max(1, (values.length + (1 << window) - 1) / (1 << window)));
            values = java.util.Arrays.copyOfRange(values, first, values.length);
            BigDecimal[] converted = new BigDecimal[values.length];
            for (int i = 0; i < values.length; i++) if (Double.isFinite(values[i])) converted[i] = decimal(values[i]);
            plot = new FactoryTrend(converted);
            return;
        }
        int size = ae ? p.render.ae.size() : p.render.eu.size();
        int start = Math.max(0, size - Math.max(1, (size + (1 << window) - 1) / (1 << window)));
        BigDecimal[] values = new BigDecimal[size - start];
        ticks = new long[values.length];
        exact = new String[values.length];
        for (int i = start; i < size; i++) {
            int at = i - start;
            if (ae) {
                AEMonitorSample s = p.render.ae.get(i);
                ticks[at] = s.tick;
                exact[at] = rate ? Double.toString(s.rate) : Long.toString(s.amount);
                values[at] = rate ? decimal(s.rate) : BigDecimal.valueOf(s.amount);
            } else {
                NetworkInfoSample s = p.render.eu.get(i);
                ticks[at] = s.tick;
                exact[at] = rate ? Double.toString(s.eut) : s.eu.toString();
                values[at] = rate ? decimal(s.eut) : new BigDecimal(s.eu);
            }
        }
        plot = new FactoryTrend(values);
    }

    static void render(FontRenderer f, Rect r, int mx, int my, int canvasWidth, int canvasHeight) {
        hits.clear();
        FactoryDesktop.render(f, r, mx, my, canvasWidth, canvasHeight);
    }

    public static boolean mount(java.util.UUID source, Entry entry) {
        return FactoryDesktop.mount(source, entry);
    }

    static void paintWidget(FontRenderer f, WidgetSpec widget, Rect r, int mx, int my) {
        painting = widget;
        try {
            EngineeringSnapshot power = EngineeringDashboardCache.snapshot();
            EngineeringTerminalHudSnapshot snapshot = EngineeringTerminalHudSnapshot
                .capture(Minecraft.getMinecraft().thePlayer);
            if (!DesktopFieldPolicy.keepsBody(widget.kind)
                && (widget.fieldsConfigured || widget.kind == WidgetKind.DEVICE)) {
                drawFields(f, r, widget, power, snapshot);
                return;
            }
            switch (widget.kind) {
                case MACHINES:
                    drawKpi(f, r, 0, power, snapshot);
                    break;
                case INPUT:
                    drawKpi(f, r, 1, power, snapshot);
                    break;
                case OUTPUT:
                    drawKpi(f, r, 2, power, snapshot);
                    break;
                case WIRELESS:
                    drawKpi(f, r, 3, power, snapshot);
                    break;
                case HISTORY:
                    drawTrend(f, r, mx, my);
                    break;
                case REGION:
                    drawRegion(f, r, power);
                    break;
                case RESOURCES:
                    drawResources(f, r, mx, my);
                    break;
                case ATTENTION:
                    drawAttention(f, r, snapshot.device, power);
                    break;
                default:
                    break;
            }
        } finally {
            painting = null;
        }
    }

    static void widgetClick(int x, int y) {
        if (!WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).rect.contains(x, y)) {
            hits.get(i).action.run();
            return;
        }
    }

    static void blurResourceSearch(int x, int y) {
        if (!WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
    }

    private static void drawFields(FontRenderer f, Rect r, WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        card(f, r, FactoryDesktop.label(widget));
        DesktopDeviceSources.Reading reading = widget.device == null ? null : DesktopDeviceSources.read(widget.device);
        Entry device = reading == null ? null : reading.entry;
        if (widget.device != null && device == null) {
            text(f, FactoryDesktop.tr("disconnected"), r.x + 12, r.y + 48, AMBER, r.w - 24);
            text(f, FactoryDesktop.tr("reason." + reading.reason), r.x + 12, r.y + 67, MUTED, r.w - 24);
            return;
        }
        List<String> fields = DesktopFieldPolicy.fields(widget);
        if (fields.isEmpty()) text(f, FactoryDesktop.tr("fields.empty"), r.x + 12, r.y + 43, MUTED, r.w - 24);
        int columns = Math.min(widget.displayColumns, Math.max(1, r.w / 110));
        int cellWidth = (r.w - 24) / columns;
        for (int index = 0; index < fields.size(); index++) {
            int x = r.x + 12 + index % columns * cellWidth, y = r.y + 42 + index / columns * 18;
            if (y + 10 >= r.y + r.h) break;
            String field = fields.get(index);
            String label = FactoryDesktop.tr("field." + field);
            int labelWidth = Math.min(cellWidth / 2, f.getStringWidth(label) + 8);
            text(f, label, x, y, MUTED, labelWidth - 6);
            text(
                f,
                fieldValue(field, widget, device, power, snapshot),
                x + labelWidth,
                y,
                TEXT,
                cellWidth - labelWidth - 5);
        }
    }

    private static String fieldValue(String field, WidgetSpec widget, Entry entry, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        boolean valid = power != null && power.slot == 2
            && "ok".equals(power.status)
            && EngineeringDashboardCache.fresh()
            && power.monitored > 0;
        if ("source".equals(field)) return widget.device != null ? widget.device.source.toString()
            .substring(0, 8)
            : widget.kind == WidgetKind.MACHINES ? sourceQuality(snapshot.device)
                : widget.kind == WidgetKind.WIRELESS || widget.kind == WidgetKind.HISTORY
                    || widget.kind == WidgetKind.RESOURCES
                        ? chartPanel == null ? tr("no.screens") : chartPanel.binding.label()
                        : powerStatus(power);
        if ("updated".equals(field)) return entry != null ? tr("received")
            : widget.kind == WidgetKind.MACHINES ? sourceQuality(snapshot.device)
                : widget.kind == WidgetKind.WIRELESS || widget.kind == WidgetKind.HISTORY
                    || widget.kind == WidgetKind.RESOURCES
                        ? PortableSuperDashboard.available(chartPanel) ? tr("received") : tr("stale")
                        : powerStatus(power);
        if ("name".equals(field))
            return entry == null ? "—" : entry.displayName.isEmpty() ? entry.name : entry.displayName;
        if ("status".equals(field)) return entry != null
            ? StatCollector.translateToLocal("gtswn.factory.status." + FactoryMachineFilter.status(entry, true))
            : widget.kind == WidgetKind.MACHINES
                ? snapshot.device.fresh ? deviceCountFoot() : sourceQuality(snapshot.device)
                : powerStatus(power);
        if ("coordinates".equals(field))
            return entry == null ? "—" : "DIM " + entry.dim + " · " + entry.x + "/" + entry.y + "/" + entry.z;
        if (entry != null) {
            boolean known = !FactoryMachineFilter.ignored(entry) && entry.evidenceKind < 2;
            if ("instantInput".equals(field))
                return known ? FormatUtil.formatMetricDouble(entry.instIn) + " EU/t" : "?";
            if ("instantOutput".equals(field))
                return known ? FormatUtil.formatMetricDouble(entry.instOut) + " EU/t" : "?";
            if ("averageInput".equals(field)) return known ? FormatUtil.formatMetricDouble(entry.avgIn) + " EU/t" : "?";
            if ("averageOutput".equals(field))
                return known ? FormatUtil.formatMetricDouble(entry.avgOut) + " EU/t" : "?";
            if ("recipeInput".equals(field)) return entry.recipeIn.isEmpty() ? "—" : entry.recipeIn;
            if ("recipeOutput".equals(field)) return entry.recipeOut.isEmpty() ? "—" : entry.recipeOut;
            if ("recognition".equals(field)) return StatCollector
                .translateToLocal("gtswn.device.gui.recognition." + (entry.recognitionMode == 2 ? "ignored" : "track"));
            if ("evidence".equals(field)) return StatCollector.translateToLocal(
                "gtswn.device.gui.tooltip.evidence." + (entry.evidenceKind == 1 ? "model"
                    : entry.evidenceKind == 2 ? "warming" : entry.evidenceKind == 3 ? "unavailable" : "measured"));
            if ("capabilities".equals(field)) {
                int roles = entry.capabilityMask & (DevicePowerCapabilities.EU_IN | DevicePowerCapabilities.EU_OUT);
                return FactoryDesktop.tr(
                    "capability." + (roles == 3 ? "both"
                        : roles == DevicePowerCapabilities.EU_IN ? "input"
                            : roles == DevicePowerCapabilities.EU_OUT ? "output" : "unknown"));
            }
            if ("type".equals(field)) return entry.name + " · #" + entry.metaTileId;
        }
        if ("input".equals(field) || "output".equals(field)) {
            boolean input = "input".equals(field);
            if (entry != null) return FactoryMachineFilter.ignored(entry) ? "—"
                : entry.evidenceKind >= 2 ? "?"
                    : FormatUtil.formatMetricDouble(input ? entry.instIn : entry.instOut) + " EU/t";
            return valid ? FormatUtil.formatMetricDouble(input ? power.current.instIn : power.current.instOut) + " EU/t"
                : "—";
        }
        if ("stored".equals(field) && PortableSuperDashboard.available(chartPanel)
            && !chartPanel.currentEnergy.isEmpty()) return compact(chartPanel.currentEnergy) + " EU";
        return FactoryDesktop.tr("unavailable");
    }

    private static void drawKpi(FontRenderer f, Rect r, int index, EngineeringSnapshot p,
        EngineeringTerminalHudSnapshot s) {
        card(f, r, tr("kpi." + index));
        boolean valid = p != null && p.slot == 2 && "ok".equals(p.status) && EngineeringDashboardCache.fresh();
        String value = "—", foot;
        if (index == 0) {
            value = s.device.fresh ? Integer.toString(devices.size()) : "—";
            foot = s.device.fresh ? deviceCountFoot() : sourceQuality(s.device);
        } else if (index < 3) {
            if (valid && p.monitored > 0)
                value = FormatUtil.formatMetricDouble(index == 1 ? p.current.instIn : p.current.instOut) + " EU/t";
            foot = valid ? (p.coverageBoundOnly ? tr("bound") : "20t") + " · " + p.monitored + " · ? " + p.unknown
                : powerStatus(p);
        } else {
            if (PortableSuperDashboard.available(chartPanel) && !chartPanel.currentEnergy.isEmpty())
                value = compact(chartPanel.currentEnergy) + " EU";
            foot = chartPanel == null
                ? selectedScreen.isEmpty() ? tr("no.screens") : StatCollector.translateToLocal("gtswn.factory.waiting")
                : chartPanel.binding.label();
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(r.x + 12, r.y + 43, 0);
        GL11.glScalef(1.65f, 1.65f, 1);
        text(f, value, 0, 0, TEXT, (int) ((r.w - 24) / 1.65));
        GL11.glPopMatrix();
        if (index == 0 && s.device.fresh && f.getStringWidth(foot) > r.w - 18) {
            text(
                f,
                countLabel("running", running) + " · " + countLabel("idle", idle),
                r.x + 9,
                r.y + r.h - 32,
                MUTED,
                r.w - 18);
            foot = countLabel("stopped", stopped);
        }
        text(f, foot, r.x + 12, r.y + r.h - 18, MUTED, r.w - 24);
        hits.add(new Hit(r, index == 0 ? WorkbenchHud::openDevices : index < 3 ? WorkbenchHud::openPowerReview : () -> {
            resourceKey = "";
            rebuildPlot();
        }));
    }

    private static void drawTrend(FontRenderer f, Rect r, int mx, int my) {
        card(f, r, tr(preset == 2 ? "power.review" : resourceKey.isEmpty() ? "trend.pool" : "trend.resource"));
        if (preset == 2) {
            EngineeringSnapshot s = EngineeringDashboardCache.snapshot();
            if (s != null && "ok".equals(s.status) && s.slot == 2 && EngineeringDashboardCache.fresh()) {
                text(
                    f,
                    tr("power.observed") + " · " + (s.coverageBoundOnly ? tr("bound") : "20t"),
                    r.x + 9,
                    r.y + 24,
                    MUTED,
                    r.w - 18);
                text(
                    f,
                    "→ " + FormatUtil.formatMetricDouble(s.current.instIn) + " EU/t",
                    r.x + 9,
                    r.y + 44,
                    CYAN,
                    r.w - 18);
                text(
                    f,
                    "← " + FormatUtil.formatMetricDouble(s.current.instOut) + " EU/t",
                    r.x + 9,
                    r.y + 61,
                    AMBER,
                    r.w - 18);
                double scale = Math.max(1, Math.max(s.current.instIn, s.current.instOut));
                drawRect(r.x + 9, r.y + 55, r.x + 9 + (int) ((r.w - 18) * (s.current.instIn / scale)), r.y + 58, CYAN);
                drawRect(
                    r.x + 9,
                    r.y + 72,
                    r.x + 9 + (int) ((r.w - 18) * (s.current.instOut / scale)),
                    r.y + 75,
                    AMBER);
                String comparison = s.review == null || !s.review.comparable ? tr("not.comparable")
                    : "Δ "
                        + FormatUtil
                            .formatMetricDouble(s.review.comparableCurrent.instIn - s.review.comparableBefore.instIn)
                        + " EU/t · "
                        + s.review.comparableMonitored
                        + " "
                        + tr("intersection");
                text(f, comparison, r.x + 9, r.y + 82, TEXT, r.w - 18);
                text(
                    f,
                    tr("modeled") + " " + (s.review == null ? "?" : s.review.modeled) + " · ? " + s.unknown,
                    r.x + 9,
                    r.y + 101,
                    MUTED,
                    r.w - 18);
            } else text(f, powerStatus(s), r.x + 9, r.y + 35, AMBER, r.w - 18);
            hits.add(new Hit(r, WorkbenchHud::openPowerReview));
            return;
        }
        button(f, new Rect(r.x + r.w - 126, r.y + 3, 58, 16), tr(rate ? "net.change" : "stock"), () -> {
            rate = !rate;
            rebuildPlot();
        });
        button(
            f,
            new Rect(r.x + r.w - 65, r.y + 3, 58, 16),
            window == 0 ? tr("original") : "1/" + (1 << window),
            () -> {
                window = (window + 1) % 3;
                rebuildPlot();
            });
        if (plot == null || plot.normalized.length < 2 || !PortableSuperDashboard.available(chartPanel)) {
            text(f, tr("no.history"), r.x + 9, r.y + 40, AMBER, r.w - 18);
            text(f, tr("no.history.hint"), r.x + 9, r.y + 58, MUTED, r.w - 18);
            return;
        }
        text(
            f,
            (relative ? tr("relative") : plotUnit) + " · " + plotWindow + metadata(plotSource, tr("received")),
            r.x + 9,
            r.y + 35,
            MUTED,
            r.w - 18);
        int x = r.x + 9, y = r.y + 54, w = r.w - 18, h = Math.max(8, r.h - 83);
        if (!relative)
            text(f, axis(plot.minimum) + " … " + axis(plot.maximum) + " " + plotUnit, x, r.y + r.h - 11, MUTED, w);
        for (int i = 0; i <= 3; i++) drawRect(x, y + h * i / 3, x + w, y + h * i / 3 + 1, EDGE);
        if (!relative) drawRect(x, y + (int) (h * (1 - plot.zero)), x + w, y + (int) (h * (1 - plot.zero)) + 1, MUTED);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(.16f, .5f, .47f, 1);
        GL11.glPointSize(2);
        GL11.glBegin(GL11.GL_POINTS);
        for (int i = 0; i < plot.normalized.length; i++) if (Double.isFinite(plot.normalized[i])) {
            double pos = !relative && ticks.length > 1 && ticks[ticks.length - 1] > ticks[0]
                ? (ticks[i] - ticks[0]) / (double) (ticks[ticks.length - 1] - ticks[0])
                : i / (double) (plot.normalized.length - 1);
            GL11.glVertex2d(x + pos * w, y + h * (1 - plot.normalized[i]));
        }
        GL11.glEnd();
        GL11.glPointSize(1);
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        text(
            f,
            relative ? tr("relative.hint")
                : ticks[0] + "t → " + ticks[ticks.length - 1] + "t · " + ticks.length + " " + tr("samples"),
            r.x + 9,
            r.y + r.h - 23,
            MUTED,
            r.w - 18);
        if (r.contains(mx, my) && !relative && exact.length > 0) {
            double selectedTick = ticks[0]
                + Math.max(0, Math.min(1, (mx - x) / (double) Math.max(1, w))) * (ticks[ticks.length - 1] - ticks[0]);
            int at = 0;
            for (int i = 1; i < ticks.length; i++)
                if (Math.abs(ticks[i] - selectedTick) < Math.abs(ticks[at] - selectedTick)) at = i;
            hover = exact[at] + " " + plotUnit + " · " + ticks[at] + "t · " + tr("game.time");
        }
    }

    private static void drawRegion(FontRenderer f, Rect r, EngineeringSnapshot s) {
        card(f, r, tr("region"));
        text(
            f,
            s != null && s.slot == 2 ? s.coverageBoundOnly ? tr("bound") : tr("loaded.region") : tr("scope.device"),
            r.x + 9,
            r.y + 35,
            TEXT,
            r.w - 18);
        if (r.h > 70) {
            text(f, powerStatus(s), r.x + 9, r.y + 42, MUTED, r.w - 18);
            if (s != null && s.slot == 2 && "ok".equals(s.status)) {
                text(f, tr("observed") + " " + s.monitored + " · ? " + s.unknown, r.x + 9, r.y + 59, MUTED, r.w - 18);
                if (s.review != null) text(
                    f,
                    tr("loaded") + " "
                        + s.review.loadedChunks
                        + "/"
                        + s.review.totalChunks
                        + (s.review.discovering ? " · " + tr("discovering") : ""),
                    r.x + 9,
                    r.y + FactoryDashboardLayout.regionLoadedLineY(r.h),
                    MUTED,
                    r.w - 18);
            }
        }
        text(f, tr("enter.world"), r.x + 9, r.y + r.h - 17, CYAN, r.w - 18);
        hits.add(new Hit(r, () -> WorkbenchHud.switchTask(1)));
    }

    private static void drawResources(FontRenderer f, Rect r, int mx, int my) {
        card(f, r, tr("resources"));
        button(f, new Rect(r.x + r.w - 106, r.y + 3, 48, 16), tr(resourceKind == 1 ? "items" : "fluids"), () -> {
            resourceKind = resourceKind == 1 ? 2 : 1;
            resourcePage = 0;
        });
        button(f, new Rect(r.x + r.w - 55, r.y + 3, 48, 16), tr("next"), () -> resourcePage++);
        Panel p = chartPanel;
        text(
            f,
            p == null ? tr("no.screens")
                : metadata(p.binding.label(), p.state == 0 ? p.aeWindow : PortableMonitorClientCache.stateText(p.state))
                    .replaceFirst("^ · ", ""),
            r.x + 9,
            r.y + 35,
            MUTED,
            r.w - 65);
        button(f, new Rect(r.x + r.w - 55, r.y + 31, 48, 16), tr("source"), () -> {
            List<String> identities = PortableSuperDashboard.screenIds();
            if (!identities.isEmpty()) selectedScreen = identities
                .get(Math.floorMod(identities.indexOf(selectedScreen) + 1, identities.size()));
            chartPanel = null;
            resourcePage = 0;
            resourceKey = "";
        });
        if (!PortableSuperDashboard.available(p)) {
            text(f, tr("no.resources"), r.x + 9, r.y + 58, AMBER, r.w - 18);
            return;
        }
        searchRect = new Rect(r.x + 6, r.y + 60, Math.max(40, r.w - 91), 17);
        button(
            f,
            searchRect,
            (search.focused ? "> " : "") + (search.value.isEmpty() ? tr("search") : search.value),
            () -> search.focused = true);
        button(
            f,
            new Rect(r.x + r.w - 82, r.y + 60, 76, 17),
            tr("sort." + resourceSort),
            () -> resourceSort = (resourceSort + 1) % 3);
        int count = resources.size();
        int visible = Math.max(1, (r.h - 82) / 18), pages = Math.max(1, (count + visible - 1) / visible);
        resourcePage %= pages;
        for (int index = resourcePage * visible; index < Math.min(count, (resourcePage + 1) * visible); index++) {
            Row row = resources.get(index);
            Rect line = new Rect(r.x + 6, r.y + 82 + (index % visible) * 18, r.w - 12, 17);
            if (line.contains(mx, my) || row.key.equals(resourceKey)) fill(line, EDGE);
            String amount = row.amount + (row.kind == 2 ? " mB" : "");
            text(f, row.name, line.x + 3, line.y + 4, TEXT, line.w / 3 - 4);
            text(f, amount, line.x + line.w / 3, line.y + 4, CYAN, line.w / 3 - 3);
            text(
                f,
                rateKnown(p, row) ? FormatUtil.formatMetricDouble(row.rate) + (row.kind == 2 ? " mB/min" : "/min")
                    : "—",
                line.x + line.w * 2 / 3,
                line.y + 4,
                rateKnown(p, row) ? AMBER : MUTED,
                line.w / 3 - 3);
            if (line.contains(mx, my)) hover = row.name + " · "
                + amount
                + " · "
                + (rateKnown(p, row) ? FormatUtil.formatMetricDouble(row.rate) + (row.kind == 2 ? " mB/min" : "/min")
                    : tr("not.monitored"))
                + " · "
                + tr("net.only");
            hits.add(new Hit(line, () -> {
                resourceKey = row.key;
                selectedResourceKind = row.kind;
                rate = true;
                rebuildPlot();
            }));
        }
        if (count == 0) text(f, tr("no.resources"), r.x + 9, r.y + 86, MUTED, r.w - 18);
    }

    private static void drawAttention(FontRenderer f, Rect r, EngineeringTerminalHudSnapshot.Source d,
        EngineeringSnapshot p) {
        card(f, r, tr("attention"));
        text(
            f,
            d.fresh ? stopped + " " + tr("stopped") + " · " + unknown + " " + tr("unknown") : sourceQuality(d),
            r.x + 9,
            r.y + 36,
            AMBER,
            r.w - 18);
        text(f, p != null && p.truncated ? tr("partial") : tr("attention.hint"), r.x + 9, r.y + 43, MUTED, r.w - 18);
        int shown = Math.min(attention.size(), Math.max(0, (r.h - 64) / 16));
        for (int i = 0; i < shown; i++) {
            Entry e = attention.get(i);
            text(f, e.displayName.isEmpty() ? e.name : e.displayName, r.x + 9, r.y + 62 + i * 16, TEXT, r.w - 18);
        }
        hits.add(new Hit(r, WorkbenchHud::openAttention));
    }

    static boolean mouse(int x, int y, int button, boolean pressed, int wheel) {
        return FactoryDesktop.mouse(x, y, button, pressed, wheel);
    }

    static boolean escape() {
        if (search.focused) {
            search.focused = false;
            return true;
        }
        return FactoryDesktop.escape();
    }

    static boolean typing() {
        return search.focused || FactoryDesktop.typing();
    }

    static void blur() {
        search.focused = false;
        FactoryDesktop.blur();
    }

    static boolean key(int code, char character, boolean pressed) {
        if (search.focused) {
            if (pressed) {
                if (code == org.lwjgl.input.Keyboard.KEY_ESCAPE || code == org.lwjgl.input.Keyboard.KEY_RETURN)
                    search.focused = false;
                else if (code == org.lwjgl.input.Keyboard.KEY_BACK) search.backspace();
                else if (code == org.lwjgl.input.Keyboard.KEY_DELETE) search.value = "";
                else search.character(character);
                resourcePage = 0;
            }
            return true;
        }
        return FactoryDesktop.key(code, character, pressed);
    }

    private static String sourceQuality(EngineeringTerminalHudSnapshot.Source s) {
        return s.terminalId == null ? tr("no.device")
            : s.receivedAtMillis == 0 ? StatCollector.translateToLocal("gtswn.factory.waiting")
                : !s.fresh ? tr("stale")
                    : s.partial ? tr("partial") + " · " + s.devices.size() : tr("received") + " · " + s.devices.size();
    }

    private static String metadata(String source, String updated) {
        String text = "";
        if (painting == null || DesktopFieldPolicy.show(painting, "source")) text = " · " + source;
        if (painting == null || DesktopFieldPolicy.show(painting, "updated")) text += " · " + updated;
        return text;
    }

    private static String deviceCountFoot() {
        return countLabel("running", running) + " · "
            + countLabel("idle", idle)
            + " · "
            + countLabel("stopped", stopped);
    }

    private static String countLabel(String state, int count) {
        return StatCollector.translateToLocalFormatted("gtswn.factory.cockpit.device." + state, count);
    }

    private static boolean rateKnown(Panel panel, Row row) {
        return row.monitored && Double.isFinite(row.rate)
            && (row.rate != 0 || row.key.equals(panel.chartKey) && panel.render != null && panel.render.ae.size() >= 2);
    }

    private static String axis(String value) {
        BigDecimal number = new BigDecimal(value);
        return value.length() <= 12 ? value
            : "≈" + number.round(new java.math.MathContext(4))
                .toEngineeringString();
    }

    private static String powerStatus(EngineeringSnapshot s) {
        return s == null || s.slot != 2 ? tr("no.power")
            : !"ok".equals(s.status) ? StatCollector.translateToLocal("gtswn.engineering.dash.status." + s.status)
                : !EngineeringDashboardCache.fresh() ? tr("stale") : s.truncated ? tr("partial") : tr("received");
    }

    private static String compact(String value) {
        try {
            return FormatUtil.formatMetric(new BigInteger(value));
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static void card(FontRenderer f, Rect r, String title) {
        if (painting != null) return;
        drawRect(r.x + 2, r.y + 3, r.x + r.w + 2, r.y + r.h + 3, 0x40000000);
        fill(r, CARD);
        drawRect(r.x, r.y, r.x + r.w, r.y + 27, 0xFFE0E7E5);
        drawRect(r.x, r.y + 27, r.x + r.w, r.y + 28, EDGE);
        text(f, painting == null ? title : FactoryDesktop.label(painting), r.x + 12, r.y + 9, TEXT, r.w - 24);
    }

    private static void button(FontRenderer f, Rect r, String title, Runnable action) {
        fill(r, EDGE);
        text(f, title, r.x + 5, r.y + (r.h - 8) / 2, TEXT, r.w - 10);
        hits.add(new Hit(r, action));
    }

    private static void fill(Rect r, int color) {
        drawRect(r.x, r.y, r.x + r.w, r.y + r.h, color);
    }

    private static void text(FontRenderer font, String value, int x, int y, int color, int width) {
        font.drawString(font.trimStringToWidth(value, Math.max(1, width)), x, y, color);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.factory.cockpit." + key);
    }

    private static final class Hit {

        final Rect rect;
        final Runnable action;

        Hit(Rect rect, Runnable action) {
            this.rect = rect;
            this.action = action;
        }
    }

    private static BigDecimal decimal(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }
}
