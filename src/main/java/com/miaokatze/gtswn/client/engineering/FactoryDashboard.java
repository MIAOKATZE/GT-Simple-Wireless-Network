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
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.panel.AEMonitorSample;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Cockpit over existing authorized caches. No sampling or network work occurs in draw methods. */
final class FactoryDashboard extends Gui {

    private static final int BACK = 0xF210161D, CARD = 0xF21B252E, EDGE = 0xFF30414D;
    private static final int TEXT = 0xFFE4EDF2, MUTED = 0xFF94A7B5, CYAN = 0xFF66DDD2, AMBER = 0xFFE9BA71;
    private static final List<Hit> hits = new ArrayList<>();
    private static final ScrollModel scroll = new ScrollModel();
    private static int preset, cardMask = 15, screenIndex, resourceKind = 1, resourcePage, window, editorHitStart;
    private static boolean wide = true, reverse, edit, rate, history, dragging;
    private static Rect area, track;
    private static int dragStart, dragOffset;
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

    private FactoryDashboard() {}

    static void restore() {
        preset = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.DASHBOARD_PRESET);
        cardMask = FactoryDisplayPreferences.integer(FactoryDisplayPreferences.Key.DASHBOARD_CARDS);
        wide = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.DASHBOARD_WIDE);
        reverse = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.DASHBOARD_REVERSE);
        history = edit = dragging = false;
        deviceRevision = Long.MIN_VALUE;
        portableSource = null;
        portableBinding = "";
        selectedScreen = "";
        chartPanel = null;
        scroll.set(0);
    }

    static void remember() {
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.DASHBOARD_PRESET, preset);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.DASHBOARD_CARDS, cardMask);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.DASHBOARD_WIDE, wide);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.DASHBOARD_REVERSE, reverse);
        if (portableSource != null && !selectedScreen.isEmpty())
            FactoryDisplayPreferences.text("cockpit_screen_" + portableSource, selectedScreen);
    }

    static void clear() {
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
        area = track = null;
        history = edit = dragging = false;
    }

    static void tick(EngineeringTerminalHudSnapshot snapshot) {
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
        area = r;
        hover = null;
        hits.clear();
        fill(r, BACK);
        text(f, "GTSWN / " + tr("preset." + preset), r.x + 10, r.y + 8, CYAN, r.w - 218);
        button(f, new Rect(r.x + r.w - 208, r.y + 3, 70, 20), tr("presets"), () -> {
            preset = (preset + 1) % 3;
            resourceKey = "";
            scroll.set(0);
            rebuildPlot();
        });
        button(f, new Rect(r.x + r.w - 134, r.y + 3, 62, 20), tr("layout"), () -> edit = !edit);
        button(f, new Rect(r.x + r.w - 68, r.y + 3, 60, 20), tr("screens"), () -> history = !history);
        if (history) {
            if (PortableSuperDashboard.isOpen()) PortableSuperDashboard
                .renderEmbedded(r.x + 2, r.y + 27, r.w - 4, r.h - 29, mx, my, canvasWidth, canvasHeight);
            else text(f, tr("no.screens"), r.x + 12, r.y + 40, AMBER, r.w - 24);
            return;
        }
        EngineeringSnapshot power = EngineeringDashboardCache.snapshot();
        EngineeringTerminalHudSnapshot snapshot = EngineeringTerminalHudSnapshot
            .capture(Minecraft.getMinecraft().thePlayer);
        text(
            f,
            tr("scope.authorized") + " · DIM "
                + Minecraft.getMinecraft().theWorld.provider.dimensionId
                + " · "
                + sourceQuality(snapshot.device),
            r.x + 10,
            r.y + 29,
            MUTED,
            r.w - 20);
        text(f, tr("scope.network"), r.x + 10, r.y + 42, MUTED, r.w - 20);
        int top = r.y + 60, bottom = r.y + r.h - 20;
        FactoryDashboardLayout g = new FactoryDashboardLayout(r.w - 24, preset, wide, reverse);
        scroll.measure(g.contentHeight, bottom - top, scroll.offset());
        try (GlScope ignored = new GlScope()) {
            clip(r.x, top, r.w, bottom - top, canvasWidth, canvasHeight);
            for (int i = 0; i < 4; i++) drawKpi(f, translated(g.kpis[i], r.x + 10, top), i, power, snapshot);
            if ((cardMask & 1) != 0) drawTrend(f, translated(g.trend, r.x + 10, top), mx, my);
            if ((cardMask & 2) != 0) drawRegion(f, translated(g.region, r.x + 10, top), power);
            if ((cardMask & 4) != 0) drawResources(f, translated(g.resources, r.x + 10, top), mx, my);
            if ((cardMask & 8) != 0) drawAttention(f, translated(g.attention, r.x + 10, top), snapshot.device, power);
        }
        track = scroll.maximum() > 0 ? new Rect(r.x + r.w - 6, top, 4, bottom - top) : null;
        if (track != null) {
            fill(track, EDGE);
            int thumb = Math.max(16, track.h * track.h / Math.max(1, g.contentHeight));
            int y = track.y + (track.h - thumb) * scroll.offset() / scroll.maximum();
            fill(new Rect(track.x, y, track.w, thumb), CYAN);
        }
        text(f, hover == null ? tr("limits") : hover, r.x + 10, r.y + r.h - 14, hover == null ? MUTED : CYAN, r.w - 20);
        editorHitStart = hits.size();
        if (edit) drawEditor(f, r);
    }

    private static void drawKpi(FontRenderer f, Rect r, int index, EngineeringSnapshot p,
        EngineeringTerminalHudSnapshot s) {
        card(f, r, tr("kpi." + index));
        boolean valid = p != null && p.slot == 2 && "ok".equals(p.status) && EngineeringDashboardCache.fresh();
        String value = "—", foot;
        if (index == 0) {
            value = s.device.fresh ? Integer.toString(devices.size()) : "—";
            foot = s.device.fresh ? running + "/" + idle + "/" + stopped + " · " + sourceQuality(s.device)
                : sourceQuality(s.device);
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
        GL11.glTranslatef(r.x + 9, r.y + 23, 0);
        GL11.glScalef(1.25f, 1.25f, 1);
        text(f, value, 0, 0, TEXT, (int) ((r.w - 18) / 1.25));
        GL11.glPopMatrix();
        text(f, foot, r.x + 9, r.y + 43, MUTED, r.w - 18);
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
            (relative ? tr("relative") : plotUnit) + " · " + plotWindow + " · " + plotSource,
            r.x + 9,
            r.y + 25,
            MUTED,
            r.w - 18);
        int x = r.x + 9, y = r.y + 44, w = r.w - 18, h = r.h - 73;
        if (!relative)
            text(f, axis(plot.minimum) + " … " + axis(plot.maximum) + " " + plotUnit, x, r.y + r.h - 11, MUTED, w);
        for (int i = 0; i <= 3; i++) drawRect(x, y + h * i / 3, x + w, y + h * i / 3 + 1, EDGE);
        if (!relative) drawRect(x, y + (int) (h * (1 - plot.zero)), x + w, y + (int) (h * (1 - plot.zero)) + 1, MUTED);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(.4f, .87f, .82f, 1);
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
            r.y + 25,
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
                    r.y + 76,
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
            p == null
                ? selectedScreen.isEmpty() ? tr("no.screens")
                    : selectedScreen.substring(0, Math.min(8, selectedScreen.length())) + " · "
                        + StatCollector.translateToLocal("gtswn.factory.waiting")
                : p.binding.label() + " · "
                    + (p.state == 0 ? p.aeWindow : PortableMonitorClientCache.stateText(p.state)),
            r.x + 9,
            r.y + 25,
            MUTED,
            r.w - 65);
        button(f, new Rect(r.x + r.w - 55, r.y + 23, 48, 16), tr("source"), () -> {
            List<String> identities = PortableSuperDashboard.screenIds();
            if (!identities.isEmpty()) selectedScreen = identities
                .get(Math.floorMod(identities.indexOf(selectedScreen) + 1, identities.size()));
            chartPanel = null;
            resourcePage = 0;
            resourceKey = "";
        });
        if (!PortableSuperDashboard.available(p)) {
            text(f, tr("no.resources"), r.x + 9, r.y + 49, AMBER, r.w - 18);
            return;
        }
        searchRect = new Rect(r.x + 6, r.y + 42, Math.max(40, r.w - 91), 17);
        button(
            f,
            searchRect,
            (search.focused ? "> " : "") + (search.value.isEmpty() ? tr("search") : search.value),
            () -> search.focused = true);
        button(
            f,
            new Rect(r.x + r.w - 82, r.y + 42, 76, 17),
            tr("sort." + resourceSort),
            () -> resourceSort = (resourceSort + 1) % 3);
        int count = resources.size();
        int visible = Math.max(1, (r.h - 62) / 18), pages = Math.max(1, (count + visible - 1) / visible);
        resourcePage %= pages;
        for (int index = resourcePage * visible; index < Math.min(count, (resourcePage + 1) * visible); index++) {
            Row row = resources.get(index);
            Rect line = new Rect(r.x + 6, r.y + 62 + (index % visible) * 18, r.w - 12, 17);
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
        if (count == 0) text(f, tr("no.resources"), r.x + 9, r.y + 65, MUTED, r.w - 18);
    }

    private static void drawAttention(FontRenderer f, Rect r, EngineeringTerminalHudSnapshot.Source d,
        EngineeringSnapshot p) {
        card(f, r, tr("attention"));
        text(
            f,
            d.fresh ? stopped + " " + tr("stopped") + " · " + unknown + " " + tr("unknown") : sourceQuality(d),
            r.x + 9,
            r.y + 26,
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

    private static void drawEditor(FontRenderer f, Rect r) {
        Rect menu = new Rect(r.x + r.w - Math.min(228, r.w - 16) - 8, r.y + 26, Math.min(228, r.w - 16), 168);
        fill(menu, BACK);
        for (int i = 0; i < 4; i++) {
            final int bit = 1 << i;
            button(
                f,
                new Rect(menu.x + 5, menu.y + 5 + i * 23, menu.w - 10, 20),
                ((cardMask & bit) != 0 ? "[+] " : "[-] ") + tr("card." + i),
                () -> cardMask ^= bit);
        }
        button(
            f,
            new Rect(menu.x + 5, menu.y + 99, menu.w - 10, 20),
            tr(wide ? "width.wide" : "width.narrow"),
            () -> wide = !wide);
        button(f, new Rect(menu.x + 5, menu.y + 122, menu.w - 10, 20), tr("reorder"), () -> reverse = !reverse);
        text(f, tr("layout.local"), menu.x + 7, menu.y + 150, MUTED, menu.w - 14);
    }

    static boolean mouse(int x, int y, int button, boolean pressed, int wheel) {
        if (history && area != null && PortableSuperDashboard.pointerCaptured())
            return PortableSuperDashboard.pointer(x - area.x - 2, y - area.y - 27 + 50, button, pressed);
        if (pressed && button == 0 && !WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
        if (dragging) {
            if (button == 0 && !pressed) dragging = false;
            else if (track != null)
                scroll.set(dragOffset + (y - dragStart) * (scroll.maximum() + track.h) / Math.max(1, track.h));
            return true;
        }
        if (area == null || !area.contains(x, y)) return false;
        if (history && y >= area.y + 27) {
            if (PortableSuperDashboard.pointer(x - area.x - 2, y - area.y - 27 + 50, button, pressed)) return true;
            if (wheel != 0) PortableSuperDashboard.wheel(x - area.x - 2, y - area.y - 27 + 50, wheel);
            if (pressed && button >= 0) PortableSuperDashboard.click(x - area.x - 2, y - area.y - 27 + 50, button);
            return true;
        }
        if (wheel != 0) {
            if (!edit) scroll.move(wheel > 0 ? -28 : 28);
            return true;
        }
        if (!pressed || button != 0) return true;
        if (track != null && new Rect(track.x - 3, track.y, 10, track.h).contains(x, y)) {
            dragging = true;
            dragStart = y;
            dragOffset = scroll.offset();
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).rect.contains(x, y)) {
            if (edit && i >= 3 && i < editorHitStart) return true;
            if (i >= 3 && !edit && (y < area.y + 60 || y >= area.y + area.h - 20)) return true;
            hits.get(i).action.run();
            return true;
        }
        return true;
    }

    static boolean escape() {
        if (search.focused) {
            search.focused = false;
            return true;
        }
        if (edit || history) {
            edit = history = false;
            return true;
        }
        return false;
    }

    static boolean typing() {
        return search.focused;
    }

    static void blur() {
        search.focused = false;
        dragging = false;
    }

    static boolean key(int code, char character, boolean pressed) {
        if (history && PortableSuperDashboard.key(code, pressed)) return true;
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
        return WorkbenchHud.scrollKey(scroll, code, pressed);
    }

    private static Rect translated(Rect r, int x, int y) {
        return new Rect(x + r.x, y + r.y - scroll.offset(), r.w, r.h);
    }

    private static String sourceQuality(EngineeringTerminalHudSnapshot.Source s) {
        return s.terminalId == null ? tr("no.device")
            : s.receivedAtMillis == 0 ? StatCollector.translateToLocal("gtswn.factory.waiting")
                : !s.fresh ? tr("stale")
                    : s.partial ? tr("partial") + " · " + s.devices.size() : tr("received") + " · " + s.devices.size();
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
        fill(r, CARD);
        drawRect(r.x, r.y, r.x + 2, r.y + r.h, CYAN);
        text(f, title, r.x + 9, r.y + 7, MUTED, r.w - 18);
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

    private static void clip(int x, int y, int width, int height, int canvasWidth, int canvasHeight) {
        Minecraft mc = Minecraft.getMinecraft();
        double sx = mc.displayWidth / (double) canvasWidth, sy = mc.displayHeight / (double) canvasHeight;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            (int) (x * sx),
            (int) (mc.displayHeight - (y + height) * sy),
            Math.max(0, (int) (width * sx)),
            Math.max(0, (int) (height * sy)));
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
