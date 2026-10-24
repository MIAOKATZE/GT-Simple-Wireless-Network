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
import com.miaokatze.gtswn.client.engineering.desktop.WidgetPresentation;
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
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Cockpit over existing authorized caches. No sampling or network work occurs in draw methods. */
public final class FactoryDashboard extends Gui {

    private static final int EDGE = DashboardVisuals.EDGE;
    private static final int TEXT = DashboardVisuals.TEXT, MUTED = DashboardVisuals.MUTED, CYAN = DashboardVisuals.CYAN,
        AMBER = DashboardVisuals.AMBER;
    private static final List<Hit> hits = new ArrayList<>();
    private static int screenIndex, resourceKind = 1, window;
    private static final DesktopResourcePages resourcePages = new DesktopResourcePages();
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
    private static Rect paintingBounds;

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
            resourcePages.clear();
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
        plotWindow = ticks.length >= 2
            ? com.miaokatze.gtswn.common.panel.WindowLabel.formatSpan(Math.max(0, ticks[ticks.length - 1] - ticks[0]))
            : "\u2014";
        if (ae && com.miaokatze.gtswn.common.panel.AEMonitorIdentity.isLegacy(p.chartKey))
            plotWindow += " / " + StatCollector.translateToLocal("gtswn.network_info.ae.legacy");
    }

    static void render(FontRenderer f, Rect r, int mx, int my, int canvasWidth, int canvasHeight) {
        hits.clear();
        hover = null;
        searchRect = null;
        FactoryDesktop.render(f, r, mx, my, canvasWidth, canvasHeight);
    }

    public static boolean mount(java.util.UUID source, Entry entry) {
        return FactoryDesktop.mount(source, entry);
    }

    static void paintWidget(FontRenderer f, WidgetSpec widget, Rect r, int mx, int my) {
        painting = widget;
        paintingBounds = r;
        try {
            EngineeringSnapshot power = EngineeringDashboardCache.snapshot();
            EngineeringTerminalHudSnapshot snapshot = EngineeringTerminalHudSnapshot
                .capture(Minecraft.getMinecraft().thePlayer);
            card(f, r, FactoryDesktop.label(widget));
            if (r.contains(mx, my)) hover = FactoryDesktop.label(widget);
            if (r.w < 100 || r.h < 88) {
                drawCompact(f, r, widget, power, snapshot);
                return;
            }
            if (!WidgetSpec.availableMetrics(widget.kind)
                .isEmpty() && widget.presentation != WidgetPresentation.DEFAULT
                && widget.presentation != WidgetPresentation.STATUS_LIST) {
                drawInstrument(f, r, widget, power, snapshot);
                return;
            }
            if (widget.presentation == WidgetPresentation.STATUS_LIST && widget.kind == WidgetKind.MACHINES) {
                drawMachineList(f, r, snapshot);
                return;
            }
            if (!DesktopFieldPolicy.keepsBody(widget.kind)
                && (widget.fieldsConfigured || widget.kind == WidgetKind.DEVICE
                    || widget.presentation == WidgetPresentation.STATUS_LIST && widget.kind != WidgetKind.ATTENTION)) {
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
                    if (r.w < 205 || r.h < 124) {
                        drawCompact(f, r, widget, power, snapshot);
                        break;
                    }
                    drawTrend(f, r, mx, my);
                    break;
                case REGION:
                    drawRegion(f, r, power);
                    break;
                case RESOURCES:
                    if (r.w < 215 || r.h < 136) {
                        drawCompact(f, r, widget, power, snapshot);
                        break;
                    }
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
            paintingBounds = null;
        }
    }

    static void widgetClick(int x, int y) {
        if (!WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).rect.contains(x, y)) {
            hits.get(i).action.run();
            return;
        }
    }

    /** Tooltip is the last screen layer, after Core restores the card's clipping rectangle. */
    static void drawHover(FontRenderer font, int mx, int my, int width, int height) {
        if (hover == null || hover.isEmpty() || width < 40 || height < 24) return;
        int maxWidth = Math.max(20, Math.min(280, width - 20));
        List<String> lines = font.listFormattedStringToWidth(hover, maxWidth);
        int count = Math.min(lines.size(), Math.max(1, (height - 16) / 12));
        int boxWidth = 0;
        for (int i = 0; i < count; i++) boxWidth = Math.max(boxWidth, font.getStringWidth(lines.get(i)));
        int boxHeight = count * 12 + 8;
        int x = Math.max(4, Math.min(width - boxWidth - 12, mx + 12));
        int y = my + 15 + boxHeight > height ? Math.max(4, my - boxHeight - 7) : my + 15;
        try (GlScope ignored = new GlScope()) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            fill(new Rect(x, y, boxWidth + 8, boxHeight), 0xF0102538);
            drawRect(x, y, x + boxWidth + 8, y + 1, CYAN);
            for (int i = 0; i < count; i++) font.drawString(lines.get(i), x + 4, y + 5 + i * 12, TEXT);
        }
    }

    static void blurResourceSearch(int x, int y) {
        if (!WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
    }

    private static void drawFields(FontRenderer f, Rect r, WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        DesktopDeviceSources.Reading reading = widget.device == null ? null : DesktopDeviceSources.read(widget.device);
        Entry device = reading == null ? null : reading.entry;
        if (widget.device != null && device == null) {
            text(f, FactoryDesktop.tr("disconnected"), r.x + 12, r.y + 48, AMBER, r.w - 24);
            text(f, FactoryDesktop.tr("reason." + reading.reason), r.x + 12, r.y + 67, MUTED, r.w - 24);
            return;
        }
        List<String> fields = DesktopFieldPolicy.fields(widget);
        if (fields.isEmpty()) text(f, FactoryDesktop.tr("fields.empty"), r.x + 10, r.y + 34, MUTED, r.w - 20);
        int columns = Math.min(widget.displayColumns, Math.max(1, r.w / 130));
        int cellWidth = (r.w - 20) / columns;
        int visibleRows = Math.max(0, (r.h - 36) / 29);
        for (int index = 0; index < Math.min(fields.size(), visibleRows * columns); index++) {
            int x = r.x + 10 + index % columns * cellWidth, y = r.y + 30 + index / columns * 29;
            String field = fields.get(index);
            DashboardVisuals.label(f, FactoryDesktop.tr("field." + field), x, y, cellWidth - 6, MUTED, .85f);
            String fieldText = fieldValue(field, widget, device, power, snapshot);
            text(f, fieldText, x, y + 12, TEXT, cellWidth - 6);
            if (new Rect(x, y, cellWidth - 6, 25).contains(FactoryDesktop.pointerX(), FactoryDesktop.pointerY()))
                hover = FactoryDesktop.tr("field." + field) + ": " + fieldText;
            drawRect(x, y + 25, x + cellWidth - 8, y + 26, DashboardVisuals.TRACK);
        }
        if (fields.size() > visibleRows * columns)
            text(f, "+" + (fields.size() - visibleRows * columns), r.x + r.w - 30, r.y + r.h - 12, MUTED, 24);
        openWidget(widget, r);
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
        drawInstrument(f, r, painting, p, s);
    }

    private static void drawMachineList(FontRenderer font, Rect r, EngineeringTerminalHudSnapshot snapshot) {
        if (!snapshot.device.fresh) {
            text(font, sourceQuality(snapshot.device), r.x + 10, r.y + 33, AMBER, r.w - 20);
            return;
        }
        String[] states = { "running", "idle", "stopped", "unknown" };
        int[] counts = { running, idle, stopped, unknown };
        for (int i = 0; i < states.length; i++) {
            int y = r.y + 32 + i * 27;
            if (y + 20 > r.y + r.h - 8) break;
            int color = i == 0 ? CYAN : i >= 2 ? AMBER : MUTED;
            text(font, FactoryDesktop.tr("metric." + states[i]), r.x + 10, y, MUTED, r.w - 52);
            text(font, Integer.toString(counts[i]), r.x + r.w - 40, y, color, 30);
            DashboardVisuals.bar(
                new Rect(r.x + 10, y + 14, r.w - 20, 3),
                devices.isEmpty() ? null : counts[i] / (double) devices.size(),
                color);
        }
        hits.add(new Hit(r, WorkbenchHud::openDevices));
    }

    private static BigDecimal metric(WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        String field = widget.metricField;
        if (widget.kind == WidgetKind.DEVICE) {
            DesktopDeviceSources.Reading reading = widget.device == null ? null
                : DesktopDeviceSources.read(widget.device);
            Entry e = reading == null ? null : reading.entry;
            if (e == null || FactoryMachineFilter.ignored(e) || e.evidenceKind >= 2) return null;
            if ("instantInput".equals(field)) return decimal(e.instIn);
            if ("instantOutput".equals(field)) return decimal(e.instOut);
            if ("averageInput".equals(field)) return decimal(e.avgIn);
            if ("averageOutput".equals(field)) return decimal(e.avgOut);
            return null;
        }
        if (widget.kind == WidgetKind.MACHINES) {
            if (!snapshot.device.fresh) return null;
            return BigDecimal.valueOf(
                "running".equals(field) ? running
                    : "idle".equals(field) ? idle
                        : "stopped".equals(field) ? stopped : "unknown".equals(field) ? unknown : devices.size());
        }
        if (widget.kind == WidgetKind.WIRELESS) {
            if (!PortableSuperDashboard.available(chartPanel) || chartPanel.currentEnergy.isEmpty()) return null;
            try {
                return new BigDecimal(chartPanel.currentEnergy);
            } catch (NumberFormatException invalid) {
                return null;
            }
        }
        if (power == null || power.slot != 2 || !"ok".equals(power.status) || !EngineeringDashboardCache.fresh())
            return null;
        if ("monitored".equals(field)) return BigDecimal.valueOf(power.monitored);
        if ("unknown".equals(field)) return BigDecimal.valueOf(power.unknown);
        if ("loadedChunks".equals(field))
            return power.review == null ? null : BigDecimal.valueOf(power.review.loadedChunks);
        if ("totalChunks".equals(field))
            return power.review == null ? null : BigDecimal.valueOf(power.review.totalChunks);
        if (power.monitored <= 0) return null;
        return "output".equals(field) ? decimal(power.current.instOut) : decimal(power.current.instIn);
    }

    private static String numeric(BigDecimal value) {
        if (value == null) return "—";
        if (value.scale() <= 0 || value.stripTrailingZeros()
            .scale() <= 0) return FormatUtil.formatMetric(value.toBigInteger());
        double number = value.doubleValue();
        return Double.isFinite(number) ? FormatUtil.formatMetricDouble(number)
            : value.round(new java.math.MathContext(4))
                .toEngineeringString();
    }

    private static String unit(WidgetSpec widget) {
        if (widget.kind == WidgetKind.WIRELESS) return "EU";
        return widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT || widget.kind == WidgetKind.DEVICE
            ? "EU/t"
            : FactoryDesktop.tr("metric." + widget.metricField);
    }

    private static void openWidget(WidgetSpec widget, Rect r) {
        Runnable action = widget.kind == WidgetKind.MACHINES || widget.kind == WidgetKind.DEVICE
            ? WorkbenchHud::openDevices
            : widget.kind == WidgetKind.ATTENTION ? WorkbenchHud::openAttention
                : widget.kind == WidgetKind.REGION ? () -> WorkbenchHud.switchTask(1)
                    : widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT
                        ? WorkbenchHud::openPowerReview
                        : null;
        if (action != null) hits.add(new Hit(r, action));
    }

    private static void drawInstrument(FontRenderer f, Rect r, WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        BigDecimal amount = metric(widget, power, snapshot);
        String value = numeric(amount), suffix = unit(widget);
        boolean stateRing = widget.kind == WidgetKind.MACHINES && widget.presentation == WidgetPresentation.DEFAULT;
        boolean ring = stateRing || widget.presentation == WidgetPresentation.GAUGE;
        Double fraction = widget.userTarget == null || amount == null ? null
            : amount.divide(BigDecimal.valueOf(widget.userTarget), java.math.MathContext.DECIMAL64)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE)
                .doubleValue();
        if (stateRing)
            fraction = snapshot.device.fresh && !devices.isEmpty() ? running / (double) devices.size() : null;
        int bodyTop = r.y + 30, bodyBottom = r.y + r.h - 24;
        int color = widget.kind == WidgetKind.OUTPUT ? AMBER : CYAN;
        if (ring && bodyBottom - bodyTop >= 38) {
            int diameter = Math.min(r.w - 24, bodyBottom - bodyTop);
            double cx = r.x + r.w / 2.0, cy = bodyTop + diameter / 2.0;
            DashboardVisuals.ring(cx, cy, diameter / 2.0 - 4, fraction, color);
            float scale = diameter >= 100 ? 1.65f : diameter >= 65 ? 1.3f : 1;
            int textWidth = diameter - 18;
            DashboardVisuals.label(
                f,
                value,
                (int) cx - Math.min(textWidth, (int) (f.getStringWidth(value) * scale)) / 2,
                (int) cy - 6,
                textWidth,
                color,
                scale);
            if (diameter >= 65) DashboardVisuals.label(
                f,
                stateRing ? FactoryDesktop.tr("metric.count") : suffix,
                (int) cx - textWidth / 2,
                (int) cy + 10,
                textWidth,
                MUTED,
                .85f);
        } else {
            float scale = r.w >= 220 ? 2.0f : 1.5f;
            DashboardVisuals.label(f, value, r.x + 10, bodyTop + 3, r.w - 20, color, scale);
            text(f, suffix, r.x + 10, bodyTop + 23, MUTED, r.w - 20);
            if (bodyBottom - bodyTop > 49) {
                Double barValue = fraction;
                if (widget.presentation == WidgetPresentation.DEFAULT
                    && (widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT)
                    && amount != null
                    && power != null) {
                    double total = Math.max(0, power.current.instIn) + Math.max(0, power.current.instOut);
                    barValue = total > 0 ? amount.doubleValue() / total : null;
                }
                DashboardVisuals.bar(new Rect(r.x + 10, bodyBottom - 9, r.w - 20, 5), barValue, color);
            }
        }
        String foot = stateRing
            ? countLabel("running", running) + " / " + (snapshot.device.fresh ? devices.size() : "?")
            : widget.presentation == WidgetPresentation.BAR || widget.presentation == WidgetPresentation.GAUGE
                ? widget.userTarget == null ? FactoryDesktop.tr("target.unset")
                    : FactoryDesktop.tr("target.user") + " "
                        + FormatUtil.formatMetricDouble(widget.userTarget)
                        + " "
                        + suffix
                : widget.kind == WidgetKind.MACHINES ? sourceQuality(snapshot.device)
                    : widget.kind == WidgetKind.WIRELESS
                        ? chartPanel == null ? tr("no.screens") : chartPanel.binding.label()
                        : widget.kind == WidgetKind.DEVICE ? FactoryDesktop.tr("metric." + widget.metricField)
                            : powerStatus(power);
        if (stateRing && !snapshot.device.fresh) foot = sourceQuality(snapshot.device);
        if (widget.presentation == WidgetPresentation.DEFAULT && amount != null
            && (widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT))
            foot = FactoryDesktop.tr("flow.share") + " / " + foot;
        text(f, foot, r.x + 9, r.y + r.h - 15, MUTED, r.w - 18);
        if (hover != null && r.contains(FactoryDesktop.pointerX(), FactoryDesktop.pointerY()))
            hover = FactoryDesktop.label(widget) + " · " + value + " " + suffix + " · " + foot;
        openWidget(widget, r);
    }

    private static void drawCompact(FontRenderer f, Rect r, WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        int top = Math.min(22, Math.max(9, r.h / 4)) + 5;
        int width = Math.max(1, r.w - 12);
        if (r.w < 22 || r.h < 24) {
            drawRect(r.x + 1, r.y + r.h / 2, r.x + Math.max(2, r.w - 1), r.y + r.h / 2 + 1, CYAN);
            if (r.contains(FactoryDesktop.pointerX(), FactoryDesktop.pointerY()))
                hover = FactoryDesktop.label(widget) + (WidgetSpec.availableMetrics(widget.kind)
                    .isEmpty() ? "" : " / " + numeric(metric(widget, power, snapshot)) + " " + unit(widget));
            openWidget(widget, r);
            return;
        }
        if (widget.kind == WidgetKind.HISTORY) {
            boolean valid = plot != null && plot.normalized.length >= 2 && PortableSuperDashboard.available(chartPanel);
            text(f, valid ? plotUnit : tr("no.history"), r.x + 6, r.y + top, valid ? CYAN : AMBER, width);
            if (valid) DashboardVisuals.trend(
                new Rect(r.x + 6, r.y + top + 14, width, Math.max(2, r.h - top - 20)),
                plot.normalized,
                relative ? null : ticks,
                CYAN);
            else DashboardVisuals.bar(new Rect(r.x + 6, r.y + r.h - 6, width, 2), null, CYAN);
        } else if (widget.kind == WidgetKind.RESOURCES) {
            boolean available = PortableSuperDashboard.available(chartPanel);
            text(
                f,
                available ? Integer.toString(resources.size()) + " " + tr(resourceKind == 1 ? "items" : "fluids")
                    : tr("no.resources"),
                r.x + 6,
                r.y + top,
                available ? CYAN : AMBER,
                width);
            int lines = Math.max(0, (r.h - top - 18) / 15);
            for (int i = 0; i < Math.min(lines, resources.size()); i++) {
                Row row = resources.get(i);
                text(f, row.name, r.x + 6, r.y + top + 17 + i * 15, TEXT, width * 2 / 3);
                text(f, compact(row.amount), r.x + 6 + width * 2 / 3, r.y + top + 17 + i * 15, CYAN, width / 3);
            }
        } else if (widget.kind == WidgetKind.ATTENTION) {
            text(f, snapshot.device.fresh ? Integer.toString(attention.size()) : "?", r.x + 6, r.y + top, AMBER, width);
            DashboardVisuals.bar(
                new Rect(r.x + 6, r.y + r.h - 7, width, 3),
                snapshot.device.fresh && !devices.isEmpty() ? attention.size() / (double) devices.size() : null,
                AMBER);
        } else {
            BigDecimal value = metric(widget, power, snapshot);
            boolean ring = r.w >= 52 && r.h >= 80
                && (widget.presentation == WidgetPresentation.GAUGE
                    || widget.kind == WidgetKind.MACHINES && widget.presentation == WidgetPresentation.DEFAULT);
            if (r.h >= top + 9 && !ring)
                DashboardVisuals.label(f, numeric(value), r.x + 6, r.y + top, width, CYAN, r.h >= 46 ? 1.15f : .8f);
            if (r.h >= 58 && !ring) text(f, unit(widget), r.x + 6, r.y + top + 16, MUTED, width);
            Double fraction = value != null && widget.userTarget != null
                ? value.divide(BigDecimal.valueOf(widget.userTarget), java.math.MathContext.DECIMAL64)
                    .max(BigDecimal.ZERO)
                    .min(BigDecimal.ONE)
                    .doubleValue()
                : null;
            if (widget.kind == WidgetKind.MACHINES && widget.presentation == WidgetPresentation.DEFAULT)
                fraction = snapshot.device.fresh && !devices.isEmpty() ? running / (double) devices.size() : null;
            if (ring) {
                int diameter = Math.min(width - 4, r.h - top - 10);
                double cx = r.x + r.w / 2.0, cy = r.y + top + diameter / 2.0;
                DashboardVisuals.ring(cx, cy, diameter / 2.0 - 2, fraction, CYAN);
                String number = numeric(value);
                DashboardVisuals.label(
                    f,
                    number,
                    (int) cx - Math.min(diameter - 12, f.getStringWidth(number)) / 2,
                    (int) cy - 4,
                    diameter - 12,
                    CYAN,
                    1);
            } else if (r.h > 30) DashboardVisuals.bar(new Rect(r.x + 6, r.y + r.h - 7, width, 3), fraction, CYAN);
            if (r.contains(FactoryDesktop.pointerX(), FactoryDesktop.pointerY()))
                hover = FactoryDesktop.label(widget) + " · " + numeric(value) + " " + unit(widget);
        }
        openWidget(widget, r);
    }

    private static void drawTrend(FontRenderer f, Rect r, int mx, int my) {
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
        DashboardVisuals.trend(new Rect(x, y, w, h), plot.normalized, relative ? null : ticks, CYAN);
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
        boolean valid = s != null && s.slot == 2 && "ok".equals(s.status) && EngineeringDashboardCache.fresh();
        text(
            f,
            valid ? (s.coverageBoundOnly ? tr("bound") : tr("loaded.region")) : powerStatus(s),
            r.x + 9,
            r.y + 30,
            valid ? TEXT : AMBER,
            r.w - 18);
        int diameter = Math.min(r.w - 26, Math.max(0, r.h - 86));
        if (diameter >= 48 && valid && s.review != null) {
            double cx = r.x + r.w / 2.0, cy = r.y + 48 + diameter / 2.0;
            DashboardVisuals.ring(
                cx,
                cy,
                diameter / 2.0 - 3,
                s.review.totalChunks > 0 ? s.review.loadedChunks / (double) s.review.totalChunks : null,
                CYAN);
            String value = s.review.loadedChunks + "/" + s.review.totalChunks;
            DashboardVisuals.label(
                f,
                value,
                (int) cx - Math.min(diameter - 14, f.getStringWidth(value)) / 2,
                (int) cy - 4,
                diameter - 14,
                CYAN,
                1);
        } else if (valid) {
            text(f, tr("observed") + " " + s.monitored, r.x + 9, r.y + 48, CYAN, r.w - 18);
            if (r.h >= 108) text(f, "? " + s.unknown, r.x + 9, r.y + 64, AMBER, r.w - 18);
        }
        text(
            f,
            valid ? tr("observed") + " " + s.monitored + " ? ? " + s.unknown : tr("scope.device"),
            r.x + 9,
            r.y + r.h - 16,
            MUTED,
            r.w - 18);
        hits.add(new Hit(r, () -> WorkbenchHud.switchTask(1)));
    }

    private static void drawResources(FontRenderer f, Rect r, int mx, int my) {
        button(f, new Rect(r.x + r.w - 106, r.y + 3, 48, 16), tr(resourceKind == 1 ? "items" : "fluids"), () -> {
            resourceKind = resourceKind == 1 ? 2 : 1;
            resourcePages.clear();
        });
        final String cardId = painting == null ? "resources" : painting.id;
        final int visible = Math.max(1, (r.h - 82) / 18);
        button(
            f,
            new Rect(r.x + r.w - 55, r.y + 3, 48, 16),
            tr("next"),
            () -> resourcePages.next(cardId, resources.size(), visible));
        Panel p = chartPanel;
        text(
            f,
            p == null ? tr("no.screens")
                : metadata(
                    p.binding.label(),
                    p.state == 0
                        ? p.render == null ? p.aeWindow
                            : com.miaokatze.gtswn.common.panel.WindowLabel.actualSpan(p.render.ae)
                        : PortableMonitorClientCache.stateText(p.state)).replaceFirst("^ · ", ""),
            r.x + 9,
            r.y + 35,
            MUTED,
            r.w - 65);
        button(f, new Rect(r.x + r.w - 55, r.y + 31, 48, 16), tr("source"), () -> {
            List<String> identities = PortableSuperDashboard.screenIds();
            if (!identities.isEmpty()) selectedScreen = identities
                .get(Math.floorMod(identities.indexOf(selectedScreen) + 1, identities.size()));
            chartPanel = null;
            resourcePages.clear();
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
        int start = resourcePages.start(cardId, count, visible);
        for (int index = start; index < Math.min(count, start + visible); index++) {
            Row row = resources.get(index);
            Rect line = new Rect(r.x + 6, r.y + 82 + (index - start) * 18, r.w - 12, 17);
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
        text(
            f,
            d.fresh ? stopped + " " + tr("stopped") + " · " + unknown + " " + tr("unknown") : sourceQuality(d),
            r.x + 9,
            r.y + 36,
            AMBER,
            r.w - 18);
        text(f, p != null && p.truncated ? tr("partial") : tr("attention.hint"), r.x + 9, r.y + 51, MUTED, r.w - 18);
        int shown = Math.min(attention.size(), Math.max(0, (r.h - 73) / 19));
        for (int i = 0; i < shown; i++) {
            Entry e = attention.get(i);
            int y = r.y + 70 + i * 19;
            drawRect(r.x + 8, y + 2, r.x + 11, y + 8, AMBER);
            text(f, e.displayName.isEmpty() ? e.name : e.displayName, r.x + 17, y, TEXT, r.w - 26);
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
                resourcePages.clear();
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
        int header = Math.min(22, Math.max(9, r.h / 4));
        drawRect(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + header, DashboardVisuals.HEADER);
        drawRect(r.x + 1, r.y + 1, r.x + Math.min(r.w - 1, 28), r.y + 2, CYAN);
        drawRect(r.x, r.y + header, r.x + r.w, r.y + header + 1, EDGE);
        int reserve = painting == null ? 0
            : ((painting.kind == WidgetKind.HISTORY && r.w >= 205 && r.h >= 124
                || painting.kind == WidgetKind.RESOURCES && r.w >= 215 && r.h >= 136)
                    ? DesktopCardGeometry.headerReserved(painting)
                    : 0);
        DashboardVisuals.label(
            f,
            title,
            r.x + 6,
            r.y + Math.max(1, (header - 8) / 2),
            r.w - 12 - reserve,
            TEXT,
            r.w < 100 ? .8f : 1);
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
        if (width < 2 || paintingBounds != null
            && (y < paintingBounds.y || y + font.FONT_HEIGHT > paintingBounds.y + paintingBounds.h - 2)) return;
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
