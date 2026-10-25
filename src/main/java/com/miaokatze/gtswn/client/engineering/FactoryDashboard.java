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

import com.cleanroommc.modularui.drawable.FluidDrawable;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.theme.WidgetTheme;
import com.miaokatze.gtswn.client.DevicePowerFilter;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.engineering.DashboardIcons.Icon;
import com.miaokatze.gtswn.client.engineering.WorkbenchLayout.Rect;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.desktop.DashboardTheme;
import com.miaokatze.gtswn.client.engineering.desktop.DesktopDeviceSources;
import com.miaokatze.gtswn.client.engineering.desktop.ResourceBinding;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetEffects;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetPresentation;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetResources;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.device.DevicePowerCapabilities;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;
import com.miaokatze.miaokatzecore.api.ui.CanvasTransform;
import com.miaokatze.miaokatzecore.api.ui.component.UiRenderer;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Cockpit over existing authorized caches. No sampling or network work occurs in draw methods. */
public final class FactoryDashboard extends Gui {

    private static int EDGE = DashboardVisuals.EDGE;
    private static int TEXT = DashboardVisuals.TEXT, MUTED = DashboardVisuals.MUTED, CYAN = DashboardVisuals.CYAN,
        AMBER = DashboardVisuals.AMBER;
    private static DashboardTheme theme = DashboardTheme.STANDARD;
    private static UiRenderer paintingRenderer;
    private static String controlHover;
    private static final List<Hit> hits = new ArrayList<>();
    private static int screenIndex, resourceKind = 1, window;
    private static final DesktopResourcePages resourcePages = new DesktopResourcePages();
    private static boolean rate;
    private static List<Entry> devices = Collections.emptyList(), attention = Collections.emptyList();
    private static long deviceRevision = Long.MIN_VALUE;
    private static boolean deviceFresh;
    private static java.util.UUID deviceSource;
    private static int running, idle, stopped, unknown;
    private static List<Panel> panels = Collections.emptyList();
    private static Panel chartPanel;
    private static String resourceKey = "";
    private static FactoryTrend plot;
    private static long[] ticks = new long[0];
    private static String[] exact = new String[0];
    private static String plotUnit = "EU", plotWindow = "", plotSource = "", hover;
    private static WirelessGridReading terminalGrid;
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
    private static final DashboardMotion motion = new DashboardMotion();
    private static DashboardMotion.State paintingMotion;
    private static Rect paintingBounds;
    private static CanvasTransform paintingTransform = CanvasTransform.identity();
    private static int localMouseX, localMouseY;
    private static final ItemDrawable resourceItem = new ItemDrawable();
    private static final FluidDrawable resourceFluid = new FluidDrawable();
    private static final WidgetTheme iconTheme = WidgetTheme.whiteTextShadow(16, 16, null);

    private FactoryDashboard() {}

    public static void theme(DashboardTheme value) {
        theme = java.util.Objects.requireNonNull(value);
        EDGE = value.edge;
        TEXT = value.text;
        MUTED = value.muted;
        CYAN = value.positive;
        AMBER = value.warning;
        FactoryDashboardArt.theme(value);
    }

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
        PortableSuperDashboard.factoryScreens(false);
        FactoryDesktop.suspend();
        search.focused = false;
        devices = attention = Collections.emptyList();
        panels = Collections.emptyList();
        chartPanel = null;
        portableSource = null;
        portableBinding = "";
        selectedScreen = "";
        plot = null;
        terminalGrid = null;
        ticks = new long[0];
        exact = new String[0];
        hits.clear();
        motion.clear();
        WidgetResources.update(null, Collections.emptyList());
    }

    static void tick(EngineeringTerminalHudSnapshot snapshot) {
        PortableSuperDashboard.factoryScreens(WorkbenchHud.dashboardVisible());
        FactoryDesktop.tick(WorkbenchHud.dashboardVisible());
        if (!WorkbenchHud.dashboardVisible()) motion.clear();
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
        panels = PortableSuperDashboard.mountedPanels();
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
        WidgetResources.update(portableSource, panels, PortableSuperDashboard.mountedScreenIds());
        List<String> identities = PortableSuperDashboard.mountedScreenIds();
        screenIndex = identities.indexOf(selectedScreen);
        WirelessGridReading nextGrid = WirelessGridReading.readTerminal();
        boolean changed = terminalGrid == null || terminalGrid.receivedAtMillis != nextGrid.receivedAtMillis
            || terminalGrid.fresh != nextGrid.fresh
            || terminalGrid.history != nextGrid.history;
        terminalGrid = nextGrid;
        if (changed) rebuildPlot();
    }

    private static void selectResourcePanel(Panel panel) {
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
        WirelessGridReading reading = grid();
        if (!reading.fresh || reading.history.isEmpty()) return;
        plotUnit = rate ? "EU/t" : "EU";
        plotSource = reading.source;
        int size = reading.history.size();
        int start = Math.max(0, size - Math.max(1, (size + (1 << window) - 1) / (1 << window)));
        BigDecimal[] values = new BigDecimal[size - start];
        ticks = new long[values.length];
        exact = new String[values.length];
        for (int i = start; i < size; i++) {
            int at = i - start;
            NetworkInfoSample sample = reading.history.get(i);
            ticks[at] = sample.tick;
            exact[at] = rate ? Double.isFinite(sample.eut) ? Double.toString(sample.eut) : null : sample.eu.toString();
            values[at] = rate ? decimal(sample.eut) : new BigDecimal(sample.eu);
            if (values[at] == null) {
                values[at] = null;
                exact[at] = null;
            }
        }
        plot = new FactoryTrend(values, rate);
        plotWindow = ticks.length >= 2 ? FactoryChartGeometry.span(ticks[ticks.length - 1], ticks[0]) : "0 s";
    }

    static void render(FontRenderer f, Rect r, int mx, int my, int canvasWidth, int canvasHeight) {
        hits.clear();
        hover = null;
        searchRect = null;
        motion.begin(System.nanoTime());
        try {
            FactoryDesktop.render(f, r, mx, my, canvasWidth, canvasHeight);
        } finally {
            motion.end();
        }
    }

    public static boolean mount(java.util.UUID source, Entry entry) {
        return FactoryDesktop.mount(source, entry);
    }

    static void paintWidget(FontRenderer f, WidgetSpec widget, Rect r, int mx, int my, CanvasTransform widgetToScreen) {
        paintWidget(f, widget, r, mx, my, widgetToScreen, null);
    }

    static void paintWidget(FontRenderer f, WidgetSpec widget, Rect r, int mx, int my, CanvasTransform widgetToScreen,
        UiRenderer renderer) {
        painting = widget;
        paintingRenderer = renderer;
        controlHover = null;
        paintingMotion = motion.card(widget.id, widget.effects);
        paintingBounds = r;
        paintingTransform = widgetToScreen;
        localMouseX = mx;
        localMouseY = my;
        try {
            EngineeringSnapshot power = EngineeringDashboardCache.snapshot();
            EngineeringTerminalHudSnapshot snapshot = EngineeringTerminalHudSnapshot
                .capture(Minecraft.getMinecraft().thePlayer);
            card(f, r, FactoryDesktop.label(widget));
            if (r.contains(mx, my)) hover = FactoryDesktop.label(widget);
            if (widget.kind == WidgetKind.RESOURCES && widget.singleResource && widget.resource == null) {
                String message = tr(widget.autoResourcePending ? "resource.pending" : "resource.choose");
                text(f, message, r.x + 10, r.y + 33, MUTED, r.w - 20);
                if (r.contains(mx, my)) hover = message + "\n" + FactoryDesktop.tr("resource.source.help");
                bindingAction(f, r, widget, r.h >= 110 ? 60 : 45);
                return;
            }
            if (widget.kind == WidgetKind.RESOURCES && widget.resource == null) {
                WidgetResources.ScreenReading screen = WidgetResources.readScreen(widget.screen);
                if (screen.panel == null) {
                    text(f, tr("screen." + screen.reason), r.x + 10, r.y + 36, AMBER, r.w - 20);
                    bindingAction(f, r, widget, 60);
                    return;
                }
                chartPanel = screen.panel;
                selectResourcePanel(screen.panel);
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
                    drawGrid(f, r);
                    break;
                case HISTORY:
                    drawTrend(f, r, mx, my);
                    break;
                case REGION:
                    drawRegion(f, r, power);
                    break;
                case RESOURCES:
                    if (widget.resource == null) drawResources(f, r, mx, my);
                    else drawResource(f, r, widget, mx, my);
                    break;
                case ATTENTION:
                    drawAttention(f, r, snapshot.device, power);
                    break;
                default:
                    break;
            }
        } finally {
            if (controlHover != null) hover = controlHover;
            paintingRenderer = null;
            controlHover = null;
            painting = null;
            paintingMotion = null;
            paintingBounds = null;
            paintingTransform = CanvasTransform.identity();
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
            fill(new Rect(x, y, boxWidth + 8, boxHeight), theme.cardBottom);
            drawRect(x, y, x + boxWidth + 8, y + 1, CYAN);
            for (int i = 0; i < count; i++) font.drawString(lines.get(i), x + 4, y + 5 + i * 12, TEXT);
        }
    }

    static void blurResourceSearch(int x, int y) {
        if (!WorkbenchLayout.inside(searchRect, x, y)) search.focused = false;
    }

    private static void drawFields(FontRenderer f, Rect r, WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        if (requiresDevice(widget) && widget.device == null) {
            text(f, FactoryDesktop.tr("device.choose"), r.x + 12, r.y + 36, AMBER, r.w - 24);
            bindingAction(f, r, widget, 60);
            return;
        }
        DesktopDeviceSources.Reading reading = widget.device == null ? null : DesktopDeviceSources.read(widget.device);
        Entry device = reading == null ? null : reading.entry;
        if (widget.device != null && device == null) {
            text(f, FactoryDesktop.tr("disconnected"), r.x + 12, r.y + 48, AMBER, r.w - 24);
            text(f, FactoryDesktop.tr("reason." + reading.reason), r.x + 12, r.y + 67, MUTED, r.w - 24);
            bindingAction(f, r, widget, 85);
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
            int labelInset = 0;
            if ("status".equals(field) && device != null) {
                String state = FactoryMachineFilter.status(device, reading.reason.isEmpty());
                int stateIndex = "running".equals(state) ? 0
                    : "idle".equals(state) ? 1 : "stopped".equals(state) ? 2 : 3;
                FactoryDashboardArt.status(
                    x + 7,
                    y + 6,
                    stateIndex,
                    reading.reason.isEmpty(),
                    phase(),
                    effects(),
                    stateIndex >= 2 ? AMBER : accent());
                labelInset = 20;
            }
            DashboardVisuals.label(
                f,
                FactoryDesktop.tr("field." + field),
                x + labelInset,
                y,
                cellWidth - 6 - labelInset,
                MUTED,
                .85f);
            String fieldText = fieldValue(field, widget, device, power, snapshot);
            text(f, fieldText, x, y + 12, TEXT, cellWidth - 6);
            if (new Rect(x, y, cellWidth - 6, 25).contains(localMouseX, localMouseY))
                hover = FactoryDesktop.tr("field." + field) + ": " + fieldText;
            drawRect(x, y + 25, x + cellWidth - 8, y + 26, theme.track);
            if (device != null && "running".equals(FactoryMachineFilter.status(device, reading.reason.isEmpty())))
                FactoryDashboardArt.flow(
                    new Rect(x, y + 25, cellWidth - 8, 1),
                    true,
                    1,
                    (phase() + index * .17) % 1,
                    effects(),
                    accent());
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
                : widget.kind == WidgetKind.WIRELESS || widget.kind == WidgetKind.HISTORY ? grid().source
                    : widget.kind == WidgetKind.RESOURCES
                        ? chartPanel == null ? tr("no.screens") : chartPanel.binding.label()
                        : powerScope(power);
        if ("updated".equals(field)) return entry != null ? tr("received")
            : widget.kind == WidgetKind.MACHINES ? sourceQuality(snapshot.device)
                : widget.kind == WidgetKind.WIRELESS || widget.kind == WidgetKind.HISTORY ? gridState(widget)
                    : widget.kind == WidgetKind.RESOURCES
                        ? PortableSuperDashboard.available(chartPanel) ? tr("received") : tr("stale")
                        : powerStatus(power);
        if ("name".equals(field))
            return entry == null ? "—" : entry.displayName.isEmpty() ? entry.name : entry.displayName;
        if ("status".equals(field) && widget.kind == WidgetKind.WIRELESS) return gridState(widget);
        if ("status".equals(field)) return entry != null
            ? StatCollector.translateToLocal("gtswn.factory.status." + FactoryMachineFilter.status(entry, true))
            : widget.kind == WidgetKind.MACHINES
                ? snapshot.device.fresh ? deviceCountFoot() : sourceQuality(snapshot.device)
                : powerStatus(power);
        if ("coordinates".equals(field))
            return entry == null ? "—" : "DIM " + entry.dim + " · " + entry.x + "/" + entry.y + "/" + entry.z;
        if (entry != null) {
            boolean known = !FactoryMachineFilter.ignored(entry) && entry.evidenceKind < 2;
            if ("instantInput".equals(field)) return known ? numeric(decimal(entry.instIn)) + " EU/t" : "?";
            if ("instantOutput".equals(field)) return known ? numeric(decimal(entry.instOut)) + " EU/t" : "?";
            if ("averageInput".equals(field)) return known ? FormatUtil.formatMetricDouble(entry.avgIn) + " EU/t" : "?";
            if ("averageOutput".equals(field))
                return known ? FormatUtil.formatMetricDouble(entry.avgOut) + " EU/t" : "?";
            if ("recipeInput".equals(field))
                return entry.recipeIn.isEmpty() ? FactoryDesktop.tr("unavailable") : entry.recipeIn;
            if ("recipeOutput".equals(field))
                return entry.recipeOut.isEmpty() ? FactoryDesktop.tr("unavailable") : entry.recipeOut;
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
                : entry.evidenceKind >= 2 ? "?" : numeric(decimal(input ? entry.instIn : entry.instOut)) + " EU/t";
            return requiresDevice(widget) ? FactoryDesktop.tr("device.choose")
                : valid ? FormatUtil.formatMetricDouble(input ? power.current.instIn : power.current.instOut) + " EU/t"
                    : "—";
        }
        if (widget.kind == WidgetKind.WIRELESS && ("stored".equals(field) || "netRate".equals(field))) {
            BigDecimal value = "netRate".equals(field) ? gridRate(widget) : grid().stored;
            return value == null ? "—" : numeric(value) + ("netRate".equals(field) ? " EU/t" : " EU");
        }
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
        int columns = r.h < 150 && r.w >= 260 ? 2 : 1;
        int cellWidth = (r.w - 20) / columns;
        for (int i = 0; i < states.length; i++) {
            int x = r.x + 10 + i % columns * cellWidth, y = r.y + 32 + i / columns * 38;
            if (y + 25 > r.y + r.h - 8) break;
            int color = i == 0 ? accent() : i >= 2 ? AMBER : MUTED;
            FactoryDashboardArt.status(x + 8, y + 6, i, counts[i] > 0, phase(), effects(), color);
            text(font, FactoryDesktop.tr("metric." + states[i]), x + 23, y + 2, MUTED, cellWidth - 62);
            text(font, Integer.toString(counts[i]), x + cellWidth - 33, y + 2, color, 29);
            Rect bar = new Rect(x + 23, y + 18, cellWidth - 31, 3);
            FactoryDashboardArt.bar(bar, devices.isEmpty() ? null : counts[i] / (double) devices.size(), color);
            FactoryDashboardArt.flow(
                new Rect(bar.x, bar.y + 4, bar.w, 1),
                i == 0 && counts[i] > 0,
                1,
                (phase() + i * .19) % 1,
                effects(),
                color);
        }
        hits.add(new Hit(r, WorkbenchHud::openDevices));
    }

    private static BigDecimal metric(WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        String field = widget.metricField;
        if (requiresDevice(widget)) {
            DesktopDeviceSources.Reading reading = widget.device == null ? null
                : DesktopDeviceSources.read(widget.device);
            Entry e = reading == null ? null : reading.entry;
            return deviceMetric(field, e);
        }
        if (widget.kind == WidgetKind.MACHINES) {
            if (!snapshot.device.fresh) return null;
            return BigDecimal.valueOf(
                "running".equals(field) ? running
                    : "idle".equals(field) ? idle
                        : "stopped".equals(field) ? stopped : "unknown".equals(field) ? unknown : devices.size());
        }
        if (widget.kind == WidgetKind.WIRELESS) {
            WirelessGridReading reading = grid();
            return "netRate".equals(field) ? gridRate(widget) : reading.stored;
        }
        if (power == null || power.slot != 2 || !"ok".equals(power.status) || !EngineeringDashboardCache.fresh())
            return null;
        if ("monitored".equals(field)) return BigDecimal.valueOf(power.monitored);
        if ("unknown".equals(field)) return BigDecimal.valueOf(power.unknown);
        if ("loadedChunks".equals(field))
            return power.review == null ? null : BigDecimal.valueOf(power.review.loadedChunks);
        if ("totalChunks".equals(field))
            return power.review == null ? null : BigDecimal.valueOf(power.review.totalChunks);
        return null;
    }

    static BigDecimal deviceMetric(String field, Entry entry) {
        if (entry == null || FactoryMachineFilter.ignored(entry) || entry.evidenceKind >= 2) return null;
        if ("input".equals(field) || "instantInput".equals(field)) return decimal(entry.instIn);
        if ("output".equals(field) || "instantOutput".equals(field)) return decimal(entry.instOut);
        if ("averageInput".equals(field)) return decimal(entry.avgIn);
        if ("averageOutput".equals(field)) return decimal(entry.avgOut);
        return null;
    }

    private static boolean requiresDevice(WidgetSpec widget) {
        return widget.kind == WidgetKind.DEVICE || widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT;
    }

    private static WirelessGridReading grid() {
        return terminalGrid == null ? WirelessGridReading.readTerminal() : terminalGrid;
    }

    private static BigDecimal gridRate(WidgetSpec widget) {
        return grid().rate(widget.averageRate, widget.averageWindowSeconds);
    }

    private static String gridState(WidgetSpec widget) {
        return tr("grid." + grid().state(widget.averageRate, widget.averageWindowSeconds));
    }

    private static String gridRateLabel(WidgetSpec widget) {
        return tr(widget.averageRate ? "grid.rate.average" : "grid.rate.instant")
            + (widget.averageRate ? " (" + widget.averageWindowSeconds + "s)" : "");
    }

    private static String numeric(BigDecimal value) {
        if (value == null) return "—";
        if (value.abs()
            .compareTo(BigDecimal.TEN.pow(18)) >= 0)
            return "≈" + value.round(new java.math.MathContext(4))
                .stripTrailingZeros()
                .toEngineeringString();
        if (value.scale() <= 0 || value.stripTrailingZeros()
            .scale() <= 0) return FormatUtil.formatMetric(value.toBigInteger());
        double number = value.doubleValue();
        return Double.isFinite(number) ? FormatUtil.formatMetricDouble(number)
            : value.round(new java.math.MathContext(4))
                .toEngineeringString();
    }

    private static String unit(WidgetSpec widget) {
        if (widget.kind == WidgetKind.WIRELESS) return "netRate".equals(widget.metricField) ? "EU/t" : "EU";
        return widget.kind == WidgetKind.INPUT || widget.kind == WidgetKind.OUTPUT || widget.kind == WidgetKind.DEVICE
            ? "EU/t"
            : FactoryDesktop.tr("metric." + widget.metricField);
    }

    private static void openWidget(WidgetSpec widget, Rect r) {
        Runnable action = widget.kind == WidgetKind.MACHINES || requiresDevice(widget) ? WorkbenchHud::openDevices
            : widget.kind == WidgetKind.ATTENTION ? WorkbenchHud::openAttention
                : widget.kind == WidgetKind.REGION ? () -> WorkbenchHud.switchTask(1) : null;
        if (action != null) hits.add(new Hit(r, action));
    }

    private static void drawInstrument(FontRenderer f, Rect r, WidgetSpec widget, EngineeringSnapshot power,
        EngineeringTerminalHudSnapshot snapshot) {
        if (requiresDevice(widget)) {
            DesktopDeviceSources.Reading reading = widget.device == null ? null
                : DesktopDeviceSources.read(widget.device);
            if (reading == null || reading.entry == null) {
                text(
                    f,
                    FactoryDesktop.tr(reading == null ? "device.choose" : "disconnected"),
                    r.x + 10,
                    r.y + 35,
                    AMBER,
                    r.w - 20);
                if (reading != null)
                    text(f, FactoryDesktop.tr("reason." + reading.reason), r.x + 10, r.y + 53, MUTED, r.w - 20);
                bindingAction(f, r, widget, reading == null ? 60 : 74);
                return;
            }
        }
        BigDecimal amount = metric(widget, power, snapshot);
        String value = numeric(amount), suffix = unit(widget);
        boolean stateRing = widget.kind == WidgetKind.MACHINES && widget.userTarget == null
            && (widget.presentation == WidgetPresentation.DEFAULT || widget.presentation == WidgetPresentation.GAUGE);
        if (widget.kind == WidgetKind.WIRELESS && widget.presentation == WidgetPresentation.GAUGE
            && widget.userTarget == null
            && r.h >= 170
            && r.w >= 150) {
            drawGridDial(f, r, widget);
            return;
        }
        if (stateRing && r.h >= 190 && r.w >= 150) {
            drawMachineDial(f, r, widget, snapshot, amount);
            openWidget(widget, r);
            return;
        }
        Double fraction = widget.userTarget == null || amount == null ? null
            : amount.divide(BigDecimal.valueOf(widget.userTarget), java.math.MathContext.DECIMAL64)
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE)
                .doubleValue();
        if (stateRing)
            fraction = snapshot.device.fresh && !devices.isEmpty() ? running / (double) devices.size() : null;
        int color = widget.kind == WidgetKind.OUTPUT
            || widget.kind == WidgetKind.WIRELESS && "netRate".equals(widget.metricField)
                && amount != null
                && amount.signum() < 0 ? AMBER : accent();
        boolean ring = widget.presentation == WidgetPresentation.GAUGE && r.h >= 170;
        int valueY = r.y + 32;
        if (ring) {
            int diameter = Math.min(r.w - 32, r.h - 70);
            double cx = r.x + r.w / 2.0, cy = r.y + 32 + diameter / 2.0;
            FactoryDashboardArt.dial(cx, cy, diameter / 2.0 - 5, fraction, color, effects());
            FactoryDashboardArt.halo(cx, cy, diameter / 2.0 + 1, phase(), effects(), color);
            centered(f, value, (int) cx, (int) cy - 10, diameter - 38, color, diameter >= 130 ? 2.1f : 1.4f);
            centered(f, suffix, (int) cx, (int) cy + 13, diameter - 38, MUTED, .85f);
        } else {
            DashboardVisuals.label(f, value, r.x + 11, valueY, r.w - 22, color, r.w >= 240 ? 2.1f : 1.6f);
            text(f, suffix, r.x + 12, valueY + 25, MUTED, r.w - 24);
            BigDecimal activity = widget.kind == WidgetKind.WIRELESS ? gridRate(widget)
                : widget.kind == WidgetKind.MACHINES ? BigDecimal.valueOf(running) : amount;
            FactoryDashboardArt.flow(
                new Rect(r.x + 12, valueY + 38, r.w - 24, 2),
                activity != null && activity.signum() != 0,
                activity == null ? 0 : activity.signum(),
                phase(),
                effects(),
                color);
            if (widget.presentation == WidgetPresentation.BAR && r.h >= 105)
                FactoryDashboardArt.bar(new Rect(r.x + 12, valueY + 45, r.w - 24, 5), fraction, color);
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
                    : widget.kind == WidgetKind.WIRELESS ? gridState(widget) + " · " + gridRateLabel(widget)
                        : widget.kind == WidgetKind.DEVICE ? FactoryDesktop.tr("metric." + widget.metricField)
                            : powerStatus(power);
        if (widget.kind == WidgetKind.WIRELESS && !ring) {
            WirelessGridReading reading = grid();
            boolean net = "netRate".equals(widget.metricField);
            if (r.h >= 115 && widget.presentation != WidgetPresentation.BAR) text(
                f,
                (net ? tr("grid.stored") : gridRateLabel(widget)) + "  "
                    + numeric(net ? reading.stored : gridRate(widget))
                    + (net ? " EU" : " EU/t"),
                r.x + 12,
                r.y + 77,
                net || gridRate(widget) == null || gridRate(widget).signum() >= 0 ? TEXT : AMBER,
                r.w - 24);
            if (r.h >= 130) text(f, reading.source, r.x + 12, r.y + r.h - 29, MUTED, r.w - 24);
        }
        if (widget.kind == WidgetKind.MACHINES && r.h >= 115 && !ring) text(
            f,
            snapshot.device.fresh ? deviceCountFoot() : sourceQuality(snapshot.device),
            r.x + 12,
            r.y + 77,
            MUTED,
            r.w - 24);
        if (requiresDevice(widget) && r.h >= 120) text(
            f,
            widget.device.source.toString()
                .substring(0, 8),
            r.x + 12,
            r.y + r.h - 29,
            MUTED,
            r.w - 24);
        text(f, foot, r.x + 11, r.y + r.h - 15, MUTED, r.w - 22);
        if (r.contains(localMouseX, localMouseY)) hover = FactoryDesktop.label(widget) + " \u00B7 "
            + value
            + " "
            + suffix
            + " \u00B7 "
            + foot
            + (widget.kind == WidgetKind.WIRELESS
                ? "\n" + gridRateLabel(widget) + "\n" + grid().source + "\n" + tr("grid.net.hint")
                : "");
        openWidget(widget, r);
    }

    private static void centered(FontRenderer font, String value, int x, int y, int width, int color, float scale) {
        float fit = Math.min(scale, width / (float) Math.max(1, font.getStringWidth(value)));
        DashboardVisuals.label(font, value, x - (int) (font.getStringWidth(value) * fit) / 2, y, width, color, fit);
    }

    private static void drawMachineDial(FontRenderer f, Rect r, WidgetSpec widget,
        EngineeringTerminalHudSnapshot snapshot, BigDecimal amount) {
        boolean fresh = snapshot.device.fresh;
        int diameter = Math.min(240, Math.min(r.w - 38, r.h - 100));
        int cx = r.x + r.w / 2, cy = r.y + 37 + diameter / 2;
        Double fraction = fresh && !devices.isEmpty() ? running / (double) devices.size() : null;
        FactoryDashboardArt.dial(cx, cy, diameter / 2.0 - 5, fraction, accent(), effects());
        FactoryDashboardArt.halo(cx, cy, diameter / 2.0 + 1, phase(), effects(), accent());
        centered(
            f,
            fresh ? numeric(amount) : "\u2014",
            cx,
            cy - 18,
            diameter - 48,
            accent(),
            diameter >= 150 ? 3f : 1.6f);
        centered(f, FactoryDesktop.tr("metric." + widget.metricField), cx, cy + 12, diameter - 48, TEXT, .9f);
        centered(
            f,
            (fresh ? running : "?") + " / " + (fresh ? devices.size() : "?") + " " + tr("running"),
            cx,
            cy + 29,
            diameter - 40,
            MUTED,
            .8f);
        String[] states = { "idle", "stopped", "unknown" };
        int[] counts = { idle, stopped, unknown };
        int statWidth = (r.w - 24) / 3, statY = r.y + r.h - 43;
        for (int i = 0; i < states.length; i++) {
            int x = r.x + 12 + i * statWidth;
            drawRect(x, statY - 7, x + statWidth - 6, statY - 6, theme.track);
            DashboardVisuals.label(f, FactoryDesktop.tr("metric." + states[i]), x, statY, statWidth - 6, MUTED, .8f);
            text(f, fresh ? Integer.toString(counts[i]) : "\u2014", x, statY + 12, i > 0 ? AMBER : TEXT, statWidth - 6);
        }
        text(f, sourceQuality(snapshot.device), r.x + 12, r.y + r.h - 13, MUTED, r.w - 24);
        if (r.contains(localMouseX, localMouseY))
            hover = FactoryDesktop.label(widget) + "\n" + deviceCountFoot() + "\n" + sourceQuality(snapshot.device);
    }

    private static void drawGridDial(FontRenderer f, Rect r, WidgetSpec widget) {
        WirelessGridReading reading = grid();
        int diameter = Math.min(250, Math.min(r.w - 42, r.h - 90));
        int cx = r.x + r.w / 2, cy = r.y + 38 + diameter / 2;
        int color = gridRate(widget) != null && gridRate(widget).signum() < 0 ? AMBER : accent();
        // This is an open-ended stock, so its ornamental ring has no fill percentage or capacity scale.
        FactoryDashboardArt.stockDial(
            cx,
            cy,
            diameter / 2.0 - 5,
            reading.stored != null,
            paintingMotion.grid.advance(motion.now(), gridRate(widget), widget.effects.animated, widget.effects.speed),
            widget.effects,
            color);
        boolean net = "netRate".equals(widget.metricField);
        centered(
            f,
            numeric(net ? gridRate(widget) : reading.stored),
            cx,
            cy - 17,
            diameter - 49,
            color,
            diameter >= 160 ? 2.7f : 1.7f);
        centered(f, net ? "EU/t" : "EU", cx, cy + 13, diameter - 52, MUTED, 1);
        centered(f, gridState(widget), cx, cy + 31, diameter - 50, TEXT, .85f);
        int secondaryY = r.y + r.h - 43;
        drawRect(r.x + 13, secondaryY - 8, r.x + r.w - 13, secondaryY - 7, theme.edge);
        text(f, (net ? tr("grid.stored") : gridRateLabel(widget)), r.x + 14, secondaryY, MUTED, r.w / 2 - 18);
        String secondary = numeric(net ? reading.stored : gridRate(widget)) + (net ? " EU" : " EU/t");
        text(f, secondary, r.x + r.w / 2, secondaryY, color, r.w / 2 - 14);
        text(f, reading.source, r.x + 14, r.y + r.h - 16, MUTED, r.w - 28);
        if (r.contains(localMouseX, localMouseY)) hover = tr("grid.stored") + ": "
            + (reading.stored == null ? "\u2014" : reading.stored.toPlainString())
            + " EU\n"
            + gridRateLabel(widget)
            + ": "
            + (gridRate(widget) == null ? "\u2014" : gridRate(widget).toPlainString())
            + " EU/t\n"
            + reading.source
            + "\n"
            + tr("grid.open.ended");
    }

    private static void drawGrid(FontRenderer f, Rect r) {
        drawInstrument(
            f,
            r,
            painting,
            EngineeringDashboardCache.snapshot(),
            EngineeringTerminalHudSnapshot.capture(Minecraft.getMinecraft().thePlayer));
    }

    private static void drawTrend(FontRenderer f, Rect r, int mx, int my) {
        button(
            f,
            new Rect(r.x + r.w - 57, r.y + 3, 24, 16),
            rate ? Icon.DELTA : Icon.INVENTORY,
            tr(rate ? "net.change" : "stock"),
            () -> {
                rate = !rate;
                rebuildPlot();
            });
        button(
            f,
            new Rect(r.x + r.w - 30, r.y + 3, 24, 16),
            Icon.HISTORY,
            window == 0 ? tr("original") : "1/" + (1 << window),
            () -> {
                window = (window + 1) % 3;
                rebuildPlot();
            });
        if (plot == null || plot.knownPoints == 0 || !grid().fresh) {
            text(f, tr("no.history"), r.x + 12, r.y + 42, AMBER, r.w - 24);
            text(f, tr("no.history.hint"), r.x + 12, r.y + 60, MUTED, r.w - 24);
            return;
        }
        int lineColor = rate ? AMBER : CYAN;
        String quantity = tr(rate ? "net.change" : "stock");
        FactoryChartGeometry geometry = new FactoryChartGeometry(r);
        Rect graph = geometry.plot;
        String current = exact.length == 0 ? null : exact[exact.length - 1];
        DashboardVisuals.label(
            f,
            current == null ? "\u2014" : numeric(new BigDecimal(current)),
            r.x + 12,
            geometry.valueY,
            Math.max(40, r.w - 114),
            lineColor,
            r.w >= 300 ? 1.8f : 1.4f);
        DashboardVisuals.label(f, quantity + " / " + plotUnit, r.x + r.w - 104, geometry.valueY + 4, 92, MUTED, .85f);
        text(
            f,
            plotWindow + " \u00B7 " + ticks.length + " " + tr("samples") + " \u00B7 " + tr("game.time"),
            r.x + 12,
            geometry.metadataY,
            MUTED,
            r.w - 24);
        int divisions = graph.h >= 36 ? 2 : 1;
        BigDecimal minimum = new BigDecimal(plot.minimum), maximum = new BigDecimal(plot.maximum);
        for (int i = 0; i <= divisions; i++) {
            int yy = graph.y + graph.h * i / divisions;
            drawRect(graph.x, yy, graph.x + graph.w, yy + 1, theme.track);
            BigDecimal value = maximum.subtract(
                maximum.subtract(minimum)
                    .multiply(BigDecimal.valueOf(i / (double) divisions)));
            String axisLabel = FactoryChartGeometry.axisLabel(
                value,
                maximum.subtract(minimum)
                    .divide(BigDecimal.valueOf(divisions), java.math.MathContext.DECIMAL64));
            float axisScale = Math
                .min(.8f, (geometry.axisWidth - 7) / (float) Math.max(1, f.getStringWidth(axisLabel)));
            DashboardVisuals.label(f, axisLabel, geometry.axisX, yy - 4, geometry.axisWidth - 7, MUTED, axisScale);
        }
        for (int i = 1; i <= 3; i++) {
            int xx = graph.x + graph.w * i / 4;
            drawRect(xx, graph.y, xx + 1, graph.y + graph.h, theme.grid);
        }
        drawRect(graph.x - 1, graph.y, graph.x, graph.y + graph.h + 1, EDGE);
        if (plot.zero > 0 && plot.zero < 1) {
            int zeroY = graph.y + (int) Math.round(graph.h * (1 - plot.zero));
            drawRect(graph.x, zeroY, graph.x + graph.w, zeroY + 1, MUTED);
        }
        boolean bars = painting.presentation == WidgetPresentation.BARS
            || painting.presentation == WidgetPresentation.COMBO;
        FactoryChartGeometry.Bars barGeometry = bars ? paintingMotion.bars(plot.normalized, ticks, graph.w) : null;
        if (bars) FactoryDashboardArt.bars(
            graph,
            barGeometry,
            plot.zero,
            lineColor,
            painting.presentation == WidgetPresentation.COMBO,
            phase(),
            effects());
        if (painting.presentation != WidgetPresentation.BARS)
            FactoryDashboardArt.trend(graph, plot.normalized, ticks, lineColor, effects());
        // A point remains visible for a lone or zero-height bar, and missing evidence stays a gap.
        if (painting.presentation == WidgetPresentation.BARS && plot.knownPoints == 1)
            FactoryDashboardArt.trend(graph, plot.normalized, ticks, lineColor, effects());
        String left = FactoryChartGeometry.age(ticks[ticks.length - 1], ticks[0]);
        text(f, left, graph.x, geometry.timeY, MUTED, graph.w / 2 - 2);
        String right = "0 s";
        text(f, right, graph.x + graph.w - f.getStringWidth(right), geometry.timeY, MUTED, graph.w / 2);
        if (graph.w >= 210) {
            long middle = FactoryChartGeometry.middleTick(ticks[0], ticks[ticks.length - 1]);
            centered(
                f,
                FactoryChartGeometry.age(ticks[ticks.length - 1], middle),
                graph.x + graph.w / 2,
                geometry.timeY,
                graph.w / 3,
                MUTED,
                .85f);
        }
        boolean aggregated = bars && barGeometry.aggregated;
        text(f, plotSource, r.x + 12, geometry.footerY, MUTED, r.w - 24);
        if (r.contains(mx, my))
            hover = quantity + ": " + (current == null ? "\u2014" : current) + " " + plotUnit + "\n" + plotSource;
        if (graph.contains(mx, my) && exact.length > 0) {
            int at = FactoryChartGeometry.nearest(ticks, FactoryChartGeometry.timePosition(mx - graph.x, graph.w));
            int xx = graph.x + (int) Math
                .round(FactoryChartGeometry.timeX(FactoryTrend.timePosition(at, ticks.length, ticks), graph.w));
            drawRect(xx, graph.y, xx + 1, graph.y + graph.h, theme.muted);
            hover = quantity + ": "
                + (exact[at] == null ? "\u2014" : exact[at])
                + " "
                + plotUnit
                + "\n"
                + FactoryChartGeometry.age(ticks[ticks.length - 1], ticks[at])
                + " \u00B7 "
                + tr("game.time")
                + "\n"
                + plotSource
                + (aggregated ? "\n" + tr("time.bins") : "");
        }
    }

    private static void drawRegion(FontRenderer f, Rect r, EngineeringSnapshot s) {
        boolean valid = s != null && s.slot == 2 && "ok".equals(s.status) && EngineeringDashboardCache.fresh();
        text(
            f,
            valid ? tr(s.coverageBoundOnly ? "bound" : "loaded.region") : powerStatus(s),
            r.x + 10,
            r.y + 31,
            valid ? TEXT : AMBER,
            r.w - 20);
        String chunks = valid && s.review != null ? s.review.loadedChunks + " / " + s.review.totalChunks : "—";
        DashboardVisuals.label(f, chunks, r.x + 10, r.y + 49, r.w - 20, CYAN, 1.6f);
        FactoryDashboardArt.bar(
            new Rect(r.x + 10, r.y + 74, r.w - 20, 5),
            valid && s.review != null && s.review.totalChunks > 0
                ? s.review.loadedChunks / (double) s.review.totalChunks
                : null,
            CYAN);
        text(
            f,
            valid ? tr("observed") + " " + s.monitored + " / " + tr("unknown") + " " + s.unknown : tr("scope.device"),
            r.x + 9,
            r.y + 91,
            MUTED,
            r.w - 18);
        hits.add(new Hit(r, () -> WorkbenchHud.switchTask(1)));
    }

    private static void drawReadingRing(FontRenderer f, double cx, double cy, int diameter, Double fraction,
        String value) {
        FactoryDashboardArt.dial(cx, cy, diameter / 2.0 - 2, fraction, CYAN, effects());
        float textScale = diameter < 38 ? .75f : 1;
        int available = diameter - 6;
        DashboardVisuals.label(
            f,
            value,
            (int) cx - Math.min(available, (int) (f.getStringWidth(value) * textScale)) / 2,
            (int) cy - 4,
            available,
            CYAN,
            textScale);
    }

    private static void drawResources(FontRenderer f, Rect r, int mx, int my) {
        button(
            f,
            new Rect(r.x + r.w - 82, r.y + 3, 24, 16),
            resourceKind == 1 ? Icon.INVENTORY : Icon.FLUID,
            tr(resourceKind == 1 ? "items" : "fluids"),
            () -> {
                resourceKind = resourceKind == 1 ? 2 : 1;
                resourcePages.clear();
            });
        final String cardId = painting == null ? "resources" : painting.id;
        final int visible = Math.max(1, Math.min(8, (r.h - 100) / 18));
        button(
            f,
            new Rect(r.x + r.w - 55, r.y + 3, 23, 16),
            Icon.PREVIOUS,
            tr("previous"),
            () -> resourcePages.previous(cardId, resources.size(), visible));
        button(
            f,
            new Rect(r.x + r.w - 30, r.y + 3, 23, 16),
            Icon.NEXT,
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
        if (!PortableSuperDashboard.available(p) || !p.aeConnected) {
            text(f, tr("no.resources"), r.x + 9, r.y + 58, AMBER, r.w - 18);
            return;
        }
        Rect localSearch = new Rect(r.x + 6, r.y + 60, Math.max(40, r.w - 39), 17);
        searchRect = screenRect(localSearch);
        button(
            f,
            localSearch,
            (search.focused ? "> " : "") + (search.value.isEmpty() ? tr("search") : search.value),
            () -> search.focused = true);
        button(
            f,
            new Rect(r.x + r.w - 30, r.y + 60, 24, 17),
            Icon.SORT,
            tr("sort." + resourceSort),
            () -> resourceSort = (resourceSort + 1) % 3);
        int count = resources.size();
        int start = resourcePages.start(cardId, count, visible);
        for (int index = start; index < Math.min(count, start + visible); index++) {
            Row row = resources.get(index);
            Rect line = new Rect(r.x + 6, r.y + 82 + (index - start) * 18, r.w - 12, 17);
            if (line.contains(mx, my) || row.key.equals(resourceKey)) fill(line, EDGE);
            String amount = row.amount + (row.kind == 2 ? " mB" : "");
            drawResourceIcon(f, row, line.x + 2, line.y, 16);
            FactoryDashboardArt.flow(
                new Rect(line.x + 22, line.y + 15, line.w - 45, 1),
                rateKnown(p, row) && row.rate != 0,
                row.rate < 0 ? -1 : 1,
                (phase() + (index - start) * .11) % 1,
                effects(),
                row.rate < 0 ? AMBER : accent());
            text(f, row.name, line.x + 22, line.y + 4, TEXT, line.w / 3 - 23);
            text(f, amount, line.x + line.w / 3, line.y + 4, CYAN, line.w / 3 - 3);
            text(
                f,
                rateKnown(p, row) ? FormatUtil.formatMetricDouble(row.rate) + (row.kind == 2 ? " mB/min" : "/min")
                    : "—",
                line.x + line.w * 2 / 3,
                line.y + 4,
                rateKnown(p, row) ? AMBER : MUTED,
                line.w / 3 - 23);
            if (line.contains(mx, my)) hover = row.name + " · "
                + amount
                + " · "
                + (rateKnown(p, row) ? FormatUtil.formatMetricDouble(row.rate) + (row.kind == 2 ? " mB/min" : "/min")
                    : tr("not.monitored"))
                + " · "
                + tr("net.only");
            hits.add(new Hit(line, () -> { resourceKey = resourceKey.equals(row.key) ? "" : row.key; }));
            ResourceBinding binding = WidgetResources.binding(p, row);
            if (binding != null) button(
                f,
                new Rect(line.x + line.w - 18, line.y, 17, 17),
                Icon.ADD,
                FactoryDesktop.tr("resource.add"),
                () -> FactoryDesktop.mountResource(binding));
        }
        if (count == 0) text(f, tr("no.resources"), r.x + 9, r.y + 86, MUTED, r.w - 18);
        else text(
            f,
            (start + 1) + "–" + Math.min(count, start + visible) + " / " + count,
            r.x + 9,
            r.y + 84 + visible * 18,
            MUTED,
            r.w - 18);
    }

    private static void drawResource(FontRenderer f, Rect r, WidgetSpec widget, int mx, int my) {
        WidgetResources.Reading reading = WidgetResources.read(widget.resource);
        Row row = reading.row;
        String name = row == null ? widget.resource.name : row.name;
        boolean compact = r.h < 112;
        int iconSize = compact ? 20 : 24;
        drawResourceIcon(f, row, r.x + 10, r.y + 30, iconSize);
        if (compact) {
            DashboardVisuals
                .label(f, row == null ? "\u2014" : compact(row.amount), r.x + 36, r.y + 34, r.w - 46, accent(), 1.4f);
        } else {
            text(f, name, r.x + 42, r.y + 32, TEXT, r.w - 52);
            text(f, tr(widget.resource.kind == 2 ? "fluids" : "items"), r.x + 42, r.y + 45, MUTED, r.w - 52);
            DashboardVisuals.label(
                f,
                row == null ? "\u2014" : compact(row.amount),
                r.x + 10,
                r.y + 63,
                r.w - 20,
                accent(),
                r.w >= 150 ? 1.9f : 1.7f);
        }
        boolean rateKnown = row != null && rateKnown(reading.panel, row);
        String change = rateKnown ? FormatUtil.formatMetricDouble(row.rate) + (row.kind == 2 ? " mB/min" : "/min")
            : "\u2014";
        if (!compact) {
            text(f, tr("net.change"), r.x + 10, r.y + 88, MUTED, r.w - 20);
            text(f, change, r.x + 10, r.y + 101, rateKnown && row.rate < 0 ? AMBER : accent(), r.w - 20);
            FactoryDashboardArt.flow(
                new Rect(r.x + 10, r.y + 82, r.w - 20, 2),
                rateKnown && row.rate != 0,
                rateKnown && row.rate < 0 ? -1 : 1,
                phase(),
                effects(),
                rateKnown && row.rate < 0 ? AMBER : accent());
        }
        String state = row == null ? tr("resource." + reading.reason) : reading.panel.binding.label();
        if (row == null) text(f, state, r.x + 10, compact ? r.y + 53 : r.y + r.h - 15, AMBER, r.w - 20);
        else if (r.h >= 150) text(f, state, r.x + 10, r.y + r.h - 15, MUTED, r.w - 20);
        if (r.contains(mx, my)) hover = name + "\n"
            + (row == null ? state
                : row.amount + (row.kind == 2 ? " mB" : " " + tr("items"))
                    + "\n"
                    + tr("net.only")
                    + ": "
                    + change
                    + "\n"
                    + state)
            + "\n"
            + widget.resource.screen
            + "\n"
            + widget.resource.key;
    }

    /** Use the original NBT-preserving packet icon; missing identity never becomes a guessed stack. */
    private static void drawResourceIcon(FontRenderer f, Row row, int x, int y, int size) {
        try (GlScope ignored = new GlScope()) {
            GL11.glColor4f(1, 1, 1, 1);
            if (row != null && row.icon != null && row.kind == 1 && row.icon.item != null)
                resourceItem.setItem(row.icon.item)
                    .draw(GuiContext.getDefault(), x, y, size, size, iconTheme);
            else if (row != null && row.icon != null && row.kind == 2 && row.icon.fluid != null)
                resourceFluid.setFluid(row.icon.fluid)
                    .draw(GuiContext.getDefault(), x, y, size, size, iconTheme);
            else {
                fill(new Rect(x, y, size, size), theme.track);
                text(f, "?", x + size / 2 - 2, y + size / 2 - 4, MUTED, size - 2);
            }
        }
    }

    private static String powerScope(EngineeringSnapshot power) {
        if (power == null) return tr("no.power");
        return tr(power.coverageBoundOnly ? "bound" : "loaded.region") + " · "
            + tr("observed")
            + " "
            + power.monitored
            + (power.review == null ? "" : " · " + tr("modeled") + " " + power.review.modeled);
    }

    private static Rect screenRect(Rect r) {
        int left = (int) Math.floor(paintingTransform.toScreenX(r.x));
        int top = (int) Math.floor(paintingTransform.toScreenY(r.y));
        int right = (int) Math.ceil(paintingTransform.toScreenX(r.x + r.w));
        int bottom = (int) Math.ceil(paintingTransform.toScreenY(r.y + r.h));
        return new Rect(left, top, right - left, bottom - top);
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
        int shown = Math.min(attention.size(), Math.min(10, Math.max(0, (r.h - 87) / 19)));
        for (int i = 0; i < shown; i++) {
            Entry e = attention.get(i);
            int y = r.y + 70 + i * 19;
            drawRect(r.x + 8, y + 2, r.x + 11, y + 8, AMBER);
            text(f, e.displayName.isEmpty() ? e.name : e.displayName, r.x + 17, y, TEXT, r.w - 26);
        }
        if (attention.size() > shown) text(
            f,
            StatCollector.translateToLocalFormatted("gtswn.factory.cockpit.list.more", attention.size() - shown),
            r.x + 9,
            r.y + 72 + shown * 19,
            MUTED,
            r.w - 18);
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
        return WidgetResources.rateKnown(panel, row);
    }

    private static String axis(String value) {
        BigDecimal number = new BigDecimal(value);
        return value.length() <= 9 ? value
            : "≈" + number.round(new java.math.MathContext(3))
                .stripTrailingZeros()
                .toEngineeringString();
    }

    private static String powerStatus(EngineeringSnapshot s) {
        return s == null || s.slot != 2 ? tr("no.power")
            : !"ok".equals(s.status) ? StatCollector.translateToLocal("gtswn.engineering.dash.status." + s.status)
                : !EngineeringDashboardCache.fresh() ? tr("stale")
                    : s.truncated || s.unknown > 0 || s.review != null && s.review.discovering ? tr("partial")
                        : tr("received");
    }

    private static String compact(String value) {
        try {
            return numeric(new BigDecimal(value));
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private static void card(FontRenderer f, Rect r, String title) {
        FactoryDashboardArt.card(r, effects(), phase());
        int header = Math.min(22, Math.max(9, r.h / 4));
        drawRect(r.x + 1, r.y + 1, r.x + r.w - 1, r.y + header, theme.header);
        int border = painting == null || painting.borderColor == WidgetSpec.DEFAULT_BORDER_COLOR ? EDGE
            : painting.borderColor;
        drawRect(r.x + 1, r.y + 1, r.x + Math.min(r.w - 1, 28), r.y + 2, border);
        drawRect(r.x + 1, r.y + header, r.x + r.w - 1, r.y + header + 1, border);
        FactoryDashboardArt.header(r, effects(), phase());
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
        fill(r, theme.track);
        text(f, title, r.x + 5, r.y + (r.h - 8) / 2, TEXT, r.w - 10);
        hits.add(new Hit(r, action));
    }

    private static void button(FontRenderer f, Rect r, Icon icon, String title, Runnable action) {
        boolean hovered = r.contains(localMouseX, localMouseY);
        fill(r, hovered ? EDGE : theme.track);
        if (paintingRenderer == null) text(f, title, r.x + 5, r.y + (r.h - 8) / 2, TEXT, r.w - 10);
        else DashboardIcons.draw(paintingRenderer, icon, r.x + (r.w - 12) / 2.0, r.y + (r.h - 12) / 2.0, 12, TEXT);
        if (hovered) controlHover = title;
        hits.add(new Hit(r, action));
    }

    /** The desktop's existing select-then-activate gate owns the first click. */
    private static void bindingAction(FontRenderer f, Rect r, WidgetSpec widget, int top) {
        Runnable choose = () -> FactoryDesktop.requestBinding(widget.id);
        String key = widget.kind.supportsScreenBinding()
            ? widget.screen == null ? "binding.screen.choose" : "binding.screen.change"
            : widget.device == null ? "binding.choose" : "binding.change";
        button(f, new Rect(r.x + 10, r.y + top, Math.min(28, r.w - 20), 18), Icon.LINK, FactoryDesktop.tr(key), choose);
    }

    private static WidgetEffects effects() {
        return painting == null ? WidgetEffects.DEFAULT : painting.effects;
    }

    private static double phase() {
        return paintingMotion == null ? 0 : paintingMotion.phase;
    }

    private static int accent() {
        int color = effects().accentColor == WidgetEffects.DEFAULT.accentColor ? theme.accent : effects().accentColor;
        return FactoryDashboardArt.readableAccent(color);
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
            this.rect = screenRect(rect);
            this.action = action;
        }
    }

    private static BigDecimal decimal(long value) {
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal decimal(double value) {
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }
}
