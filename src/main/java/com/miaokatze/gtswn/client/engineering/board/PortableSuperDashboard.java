package com.miaokatze.gtswn.client.engineering.board;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.GuiContext;
import com.cleanroommc.modularui.utils.Platform;
import com.miaokatze.gtswn.client.PortableMonitorClientCache;
import com.miaokatze.gtswn.client.PortableMonitorClientCache.PinnedLine;
import com.miaokatze.gtswn.client.engineering.DisplayContinuity;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.client.gui.PortableMonitorCharts;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Read-only, foreground board of the original portable monitor's mounted information screens. */
public final class PortableSuperDashboard extends Gui {

    private static final int BACK = 0xF0151D25, CARD = 0xEB23323D, HEADER = 0xFF2D414D;
    private static final int TEXT = 0xFFE5EEF3, MUTED = 0xFF94A8B5, ACCENT = 0xFF66D9D0, WARN = 0xFFE9B969;
    private static final int TOP = 76;
    private static final Map<String, Card> cards = new LinkedHashMap<>();
    private static final Map<String, Integer> rowPages = new HashMap<>();
    private static final List<Hit> hits = new ArrayList<>();
    private static final List<String> visible = new ArrayList<>();
    private static final List<MonitorBindings.Binding> bindings = new ArrayList<>();
    private static ItemStack stack;
    private static UUID terminal;
    private static Object world, connection, player;
    private static PacketSyncPortableMonitor lastSnapshot;
    private static PortableBoardLayout layout;
    private static PortableBoardStore store;
    private static String identity, scope;
    private static boolean open, settings, saveFailed;
    private static int scroll, maxScroll, menuScroll, menuMax, width, height, lastTick;
    private static int requestedIndex = -1;
    private static String requestedId;
    private static int optionsVersion;
    private static long revision;
    private static List<String> basic = new ArrayList<>();
    private static boolean embedded;
    private static boolean factoryScreens;
    private static Runnable userClose;
    private static int embeddedX, embeddedY, virtualWidth, virtualHeight;
    private static int trackTop, trackBottom, dragY, dragScroll;
    private static boolean draggingScroll;
    private static EngineeringTerminalSources.Source sourceIdentity = EngineeringTerminalSources.Source.EMPTY;
    private static long initialReceipt;
    private static final DisplayContinuity<Map<String, Card>> DISPLAY = new DisplayContinuity<>();
    private static DisplayScope displayScope;

    private PortableSuperDashboard() {}

    public static void open(ItemStack source) {
        close();
        Minecraft mc = Minecraft.getMinecraft();
        UUID id = MonitorBindings.id(source);
        // Use the same explicitly identified provider as the factory overview.
        if (id == null || mc.thePlayer == null || EngineeringTerminalSources.resolve(mc.thePlayer, 0).stack != source)
            return;
        stack = source;
        sourceIdentity = EngineeringTerminalSources.resolve(mc.thePlayer, 0);
        PortableMonitorClientCache.snapshot(source);
        initialReceipt = PortableMonitorClientCache.receiptRevision(source);
        terminal = id;
        world = mc.theWorld;
        player = mc.thePlayer;
        connection = mc.getNetHandler();
        scope = (mc.func_147104_D() != null ? "server:" + mc.func_147104_D().serverIP
            : "local:" + (mc.getIntegratedServer() == null ? "unknown"
                : mc.getIntegratedServer()
                    .getFolderName()))
            + "/"
            + mc.thePlayer.getUniqueID();
        store = new PortableBoardStore(
            mc.mcDataDir.toPath()
                .resolve("config/gtswn/engineering-board"));
        lastTick = Integer.MIN_VALUE;
        revision = Long.MIN_VALUE;
        open = true;
        saveFailed = false;
        refreshBindings();
        displayScope = new DisplayScope(world, connection, player, sourceIdentity);
        DISPLAY.open(displayScope);
        Map<String, Card> retained = DISPLAY.display(displayScope);
        if (retained != null && !revoked()) cards.putAll(retained);
        else if (revoked()) DISPLAY.clear();
        // Rebuild formatting, but preserve the original server data and receipt times.
        for (Card card : cards.values()) card.builtOptions = -1;
    }

    public static boolean isOpen() {
        return open;
    }

    /** Host intent is distinct from close(), which also serves identity/lifecycle invalidation. */
    public static void onUserClose(Runnable callback) {
        userClose = callback;
    }

    private static void requestClose() {
        Runnable callback = userClose;
        close();
        if (callback != null) callback.run();
    }

    /** Scheduling belongs to the client tick, never to draw/hover/resize. */
    public static void tick() {
        update();
    }

    /** Shares the original fair scheduler; a separate board's hidden-card preference is presentation only. */
    public static void factoryScreens(boolean reading) {
        if (factoryScreens == reading) return;
        factoryScreens = reading;
        requestedIndex = -1;
        requestedId = null;
    }

    /** Read-only projection of the existing authorized, fairly polled screen cards. */
    public static List<Panel> panels() {
        List<Panel> result = new ArrayList<>();
        if (!displayValid() || layout == null) return result;
        for (String id : layout.order) {
            if (layout.hidden.contains(id)) continue;
            Card card = cards.get(id);
            if (card == null || card.summary == null) continue;
            if (card.summary.state != 0 || card.detail == null) result.add(card.summary);
            else result.add(card.detail);
        }
        return java.util.Collections.unmodifiableList(result);
    }

    public static int boundScreens() {
        return bindings.size();
    }

    /** All terminal mounts, including cards hidden only in the original board's layout. */
    public static List<String> mountedScreenIds() {
        List<String> result = new ArrayList<>();
        if (open) for (MonitorBindings.Binding binding : bindings) result.add(binding.panelId.toString());
        return java.util.Collections.unmodifiableList(result);
    }

    /** Existing receipts only; this projection never requests a screen or reads a world tile. */
    public static List<Panel> mountedPanels() {
        List<Panel> result = new ArrayList<>();
        if (displayValid()) for (MonitorBindings.Binding binding : bindings) {
            Card card = cards.get(binding.panelId.toString());
            if (card != null && card.summary != null) {
                result.add(card.summary.state == 0 && card.detail != null ? card.detail : card.summary);
            } else {
                Panel waiting = new Panel();
                waiting.binding = binding;
                result.add(waiting);
            }
        }
        return java.util.Collections.unmodifiableList(result);
    }

    /** Layout identities include screens whose first reply has not arrived yet. */
    public static List<String> screenIds() {
        List<String> result = new ArrayList<>();
        if (open && layout != null) for (String id : layout.order) if (!layout.hidden.contains(id)) result.add(id);
        return java.util.Collections.unmodifiableList(result);
    }

    public static boolean available(Panel panel) {
        if (!displayValid() || Minecraft.getMinecraft()
            .isGamePaused() || panel == null || panel.state != 0 || panel.binding == null) return false;
        Card card = cards.get(panel.binding.panelId.toString());
        return card != null && card.detail == panel
            && card.receipt > initialReceipt
            && !expired(card)
            && PortableMonitorClientCache.snapshot(stack) != null;
    }

    /** Data-only projection. Historical rows never satisfy available() or create new subscriptions. */
    public static boolean displayable(Panel panel) {
        if (!displayValid() || panel == null || panel.state != 0 || panel.binding == null) return false;
        Card card = cards.get(panel.binding.panelId.toString());
        return card != null && card.detail == panel;
    }

    public static String displayStatus(Panel panel) {
        if (!displayable(panel)) return "waiting";
        if (Minecraft.getMinecraft()
            .isGamePaused()) return "paused";
        return available(panel) ? "live" : "stale";
    }

    static boolean sameDisplaySource(Panel old, Panel next) {
        long config = next.historyPatch == null ? next.configVersion : next.historyPatch.configVersion;
        return old.binding.panelId.equals(next.binding.panelId) && old.binding.dimension == next.binding.dimension
            && old.binding.x == next.binding.x
            && old.binding.y == next.binding.y
            && old.binding.z == next.binding.z
            && (config <= 0 || old.configVersion <= 0 || old.configVersion == config)
            // Summary and detail deliberately use different dictionaries; changing projection is not revocation.
            && (old.detail != next.detail || old.metadataVersion == next.metadataVersion)
            && (next.metadataUnchanged || old.ownerName.equals(next.ownerName) && old.window.equals(next.window)
                && (old.detail != next.detail || old.aeWindow.equals(next.aeWindow)));
    }

    private static boolean revoked() {
        return PortableMonitorClientCache.mutationStatus(stack) == 2
            && PortableMonitorClientCache.snapshot(stack) == null;
    }

    private static boolean displayValid() {
        if (!open) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != player || mc.theWorld != world
            || mc.getNetHandler() != connection
            || mc.thePlayer == null
            || mc.thePlayer.isDead
            || mc.thePlayer.getHealth() <= 0
            || !sourceIdentity.sameIdentity(EngineeringTerminalSources.resolve(mc.thePlayer, 0))
            || revoked()) {
            DISPLAY.clear();
            cards.clear();
            return false;
        }
        return true;
    }

    private static boolean expired(Card card) {
        return card.received == 0 || System.currentTimeMillis() - card.received
            > Math.max(10000L, Math.max(1, bindings.size()) * Math.max(1100L, Config.hudUpdateInterval * 50L) * 2);
    }

    /** Called only after owner/session/sequence validation, including incomplete raw-history replies. */
    public static void invalidateMetadata(PacketSyncPortableMonitor response) {
        if (response == null || displayScope == null
            || !displayScope.source.binding.startsWith(response.terminalId.toString() + ":")) return;
        Map<String, Card> retained = DISPLAY.display(displayScope);
        if (retained == null) return;
        java.util.Set<String> present = new java.util.HashSet<>();
        for (Panel panel : response.panels) {
            String id = panel.binding.panelId.toString();
            present.add(id);
            Card card = retained.get(id);
            if (card != null && card.detail != null
                && (panel.state != 0 || !sameDisplaySource(card.detail, panel)
                    || card.summary != null && !sameDisplaySource(card.summary, panel))) {
                card.detail = null;
                card.received = card.receipt = 0;
                card.builtOptions = -1;
            }
        }
        retained.keySet()
            .retainAll(present);
        if (response.terminalId.equals(terminal)) cards.keySet()
            .retainAll(present);
    }

    public static void revoke(UUID id) {
        if (id == null) return;
        if (displayScope != null && displayScope.source.binding.startsWith(id.toString() + ":")) {
            DISPLAY.clear();
            // The open board keeps its captured source identity for any later reauthorized receipt.
        }
        if (id.equals(terminal)) cards.clear();
    }

    public static void resetSession() {
        DISPLAY.clear();
        displayScope = null;
        close();
    }

    public static void close() {
        if (stack != null) PortableMonitorClientCache.dashboardViewing(stack, -1);
        DISPLAY.close();
        open = settings = false;
        factoryScreens = false;
        draggingScroll = false;
        userClose = null;
        stack = null;
        terminal = null;
        sourceIdentity = EngineeringTerminalSources.Source.EMPTY;
        world = connection = player = null;
        lastSnapshot = null;
        identity = null;
        cards.clear();
        rowPages.clear();
        bindings.clear();
        hits.clear();
        visible.clear();
        basic = java.util.Collections.emptyList();
        scroll = maxScroll = menuScroll = 0;
        requestedIndex = -1;
        requestedId = null;
    }

    private static void refreshBindings() {
        long nextRevision = MonitorBindings.revision(stack);
        if (revision == nextRevision) return;
        if (revision != Long.MIN_VALUE) DISPLAY.clear();
        revision = nextRevision;
        bindings.clear();
        bindings.addAll(MonitorBindings.read(stack));
        List<String> ids = new ArrayList<>();
        for (MonitorBindings.Binding binding : bindings) ids.add(binding.panelId.toString());
        String nextIdentity = PortableBoardLayout.identity(scope, terminal.toString(), ids);
        if (!nextIdentity.equals(identity)) {
            identity = nextIdentity;
            layout = store.load(identity, ids);
            // A binding edit cannot leave previously authorized rows or graphs on screen.
            cards.clear();
            initialReceipt = PortableMonitorClientCache.receiptRevision(stack);
            rowPages.clear();
            lastSnapshot = null;
            scroll = 0;
            requestedIndex = -1;
            requestedId = null;
            PortableMonitorClientCache.dashboardViewing(stack, -1);
            optionsVersion++;
        }
    }

    private static boolean update() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!open) return false;
        if (mc.thePlayer != player || mc.theWorld != world || mc.getNetHandler() != connection) {
            DISPLAY.clear();
            close();
            return false;
        }
        if (!displayValid()) return false;
        if (mc.isGamePaused()) return true;
        if (lastTick == mc.thePlayer.ticksExisted) return true;
        lastTick = mc.thePlayer.ticksExisted;
        EngineeringTerminalSources.Source currentSource = EngineeringTerminalSources.resolve(mc.thePlayer, 0);
        ItemStack current = currentSource.stack;
        if (!sourceIdentity.sameIdentity(currentSource) || !terminal.equals(MonitorBindings.id(current))) {
            DISPLAY.clear();
            close();
            return false;
        }
        stack = current;
        refreshBindings();
        EngineeringTerminalHudSnapshot.Source source = EngineeringTerminalHudSnapshot.capture(mc.thePlayer).portable;
        basic = terminal.equals(source.terminalId) && PortableMonitorClientCache.receiptRevision(stack) > initialReceipt
            ? source.basicLines
            : java.util.Collections.emptyList();
        PacketSyncPortableMonitor snapshot = PortableMonitorClientCache.snapshot(stack);
        if (snapshot != null && snapshot != lastSnapshot) {
            lastSnapshot = snapshot;
            long now = System.currentTimeMillis();
            if (requestedId != null) cards.computeIfAbsent(requestedId, ignored -> new Card()).attempted = now;
            java.util.Set<String> currentPanels = new java.util.HashSet<>();
            for (Panel panel : snapshot.panels) currentPanels.add(panel.binding.panelId.toString());
            cards.keySet()
                .retainAll(currentPanels);
            for (Panel panel : snapshot.panels) {
                String id = panel.binding.panelId.toString();
                if (!layout.order.contains(id)) continue;
                Card card = cards.computeIfAbsent(id, ignored -> new Card());
                if (card.detail != null && !sameDisplaySource(card.detail, panel)) {
                    card.detail = null;
                    card.received = card.receipt = 0;
                    card.builtOptions = -1;
                }
                card.summary = panel;
                if (panel.state != 0) {
                    card.detail = null;
                    card.received = 0;
                } else if (panel.detail) {
                    card.detail = panel;
                    card.received = PortableMonitorClientCache.receivedAtMillis(stack);
                    card.receipt = PortableMonitorClientCache.receiptRevision(stack);
                    card.builtOptions = -1;
                }
            }
            DISPLAY
                .receive(displayScope, new LinkedHashMap<>(cards), PortableMonitorClientCache.receivedAtMillis(stack));
            requestedIndex = -1;
        }
        // Hold one target until an ACK. Frontmost cards get a bounded age bonus, while every other
        // visible-in-layout card eventually becomes older; unloaded screens cannot starve the rest.
        if (requestedIndex < 0 || !factoryScreens && layout.hidden.contains(requestedId)) {
            requestedIndex = nextSelection();
            requestedId = requestedIndex < 0 ? null : bindings.get(requestedIndex).panelId.toString();
        }
        PortableMonitorClientCache.dashboardViewing(stack, requestedIndex);
        if (requestedIndex >= 0) PortableMonitorClientCache.poll(stack);
        return true;
    }

    private static int nextSelection() {
        if (bindings.isEmpty()) return -1;
        Map<String, Long> attempted = new HashMap<>();
        for (Map.Entry<String, Card> entry : cards.entrySet())
            attempted.put(entry.getKey(), entry.getValue().attempted);
        String selected = factoryScreens
            ? layout.nextSource(mountedScreenIds(), java.util.Collections.emptyList(), attempted, true)
            : layout.nextSource(layout.order, visible, attempted);
        for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).panelId.toString()
            .equals(selected)) return i;
        return -1;
    }

    /** Same board, translated into the factory canvas; no GuiScreen or MUI overlay is opened. */
    public static void renderEmbedded(int x, int y, int w, int h, int mouseX, int mouseY, int canvasWidth,
        int canvasHeight) {
        embedded = true;
        embeddedX = x;
        embeddedY = y - 50;
        virtualWidth = canvasWidth;
        virtualHeight = canvasHeight;
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y - 50, 0);
            render(w, h + 50, mouseX - x, mouseY - y + 50);
        } finally {
            GL11.glPopMatrix();
            embedded = false;
        }
    }

    public static void render(int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (!displayValid() || layout == null) return;
        width = screenWidth;
        height = screenHeight;
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        hits.clear();
        visible.clear();
        int frameBottom = layout.order.isEmpty() && !settings
            ? Math.min(height - 10, TOP + (layout.overview ? 84 : 0) + 40)
            : height - 10;
        drawRect(10, 50, width - 10, frameBottom, BACK);
        drawRect(10, 50, width - 10, 73, HEADER);
        text(font, tr("title"), 19, 58, ACCENT, width - 198);
        button(font, width - 166, 53, 96, tr("settings"), () -> settings = !settings, false);
        button(font, width - 65, 53, 47, tr("close"), PortableSuperDashboard::requestClose, false);
        int viewBottom = height - 26;
        if (layout.overview) {
            drawRect(18, TOP, width - 18, TOP + 78, CARD);
            text(font, tr("overview"), 26, TOP + 6, ACCENT, width - 52);
            int y = TOP + 21;
            if (basic.isEmpty()) text(font, tr("overview.waiting"), 26, y, MUTED, width - 52);
            else for (int i = 0; i < Math.min(4, basic.size()); i++) {
                text(font, basic.get(i), 26, y + i * 12, TEXT, width - 52);
            }
        }
        int start = TOP + (layout.overview ? 84 : 0);
        GuiContext context = GuiContext.getDefault();
        Minecraft mc = Minecraft.getMinecraft();
        int actualWidth = embedded
            ? (int) (width * new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaledWidth()
                / (double) virtualWidth)
            : Math.abs(context.transformX(width, 0) - context.transformX(0, 0));
        int columns = layout.effectiveColumns((int) ((width - 36) * actualWidth / (double) width)), gap = 8;
        int cardWidth = (width - 36 - gap * (columns - 1)) / columns;
        int[] columnY = new int[columns];
        Arrays.fill(columnY, start);
        int requiredBottom = start;
        try (GlScope ignored = new GlScope()) {
            scissor(16, start, width - 16, viewBottom);
            try {
                Platform.setupDrawFont();
                for (String id : layout.order) {
                    if (layout.hidden.contains(id)) continue;
                    Card card = cards.computeIfAbsent(id, key -> new Card());
                    build(card);
                    int col = 0;
                    for (int i = 1; i < columns; i++) if (columnY[i] < columnY[col]) col = i;
                    int cardHeight = cardHeight(card);
                    int x = 18 + col * (cardWidth + gap), y = columnY[col] - scroll;
                    columnY[col] += cardHeight + gap;
                    requiredBottom = Math.max(requiredBottom, columnY[col]);
                    if (y + cardHeight > start && y < viewBottom) {
                        visible.add(id);
                        drawCard(font, id, card, x, y, cardWidth, cardHeight);
                    }
                }
            } finally {
                if (!embedded) Stencil.remove();
            }
        }
        maxScroll = Math.max(0, requiredBottom - viewBottom);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        if (layout.order.isEmpty()) text(font, tr("empty"), 26, start + 12, MUTED, width - 52);
        else if (layout.hidden.size() == layout.order.size())
            text(font, tr("hidden.all"), 26, start + 12, MUTED, width - 52);
        if (maxScroll > 0) {
            int track = Math.max(1, viewBottom - start), thumb = Math.max(14, track * track / (track + maxScroll));
            trackTop = start;
            trackBottom = viewBottom;
            int at = start + (track - thumb) * scroll / Math.max(1, maxScroll);
            drawRect(width - 15, start, width - 12, viewBottom, HEADER);
            drawRect(width - 15, at, width - 12, at + thumb, ACCENT);
        }
        text(font, saveFailed ? tr("save.failed") : tr("hint"), 20, height - 21, saveFailed ? WARN : MUTED, width - 40);
        if (settings) drawSettings(font);
    }

    private static int cardHeight(Card card) {
        int line = layout.size == 0 ? 12 : 14;
        return 84 + card.lines.size() * line
            + card.graphs.size() * (layout.size == 0 ? 60 : layout.size == 1 ? 86 : 116)
            + (card.pages > 1 ? 22 : 0);
    }

    private static void build(Card card) {
        if (card.builtOptions == optionsVersion) return;
        card.builtOptions = optionsVersion;
        card.lines.clear();
        card.graphs.clear();
        card.pages = 1;
        Panel panel = card.detail;
        if (panel == null) return;
        if (layout.eu) for (Row row : panel.rows) if (row.kind == 0) {
            card.lines.addAll(
                PortableMonitorClientCache.pinnedEUValueLines(
                    row,
                    panel.displayMode,
                    layout.amount,
                    layout.instant,
                    layout.average,
                    false,
                    tr("instant"),
                    tr("average")));
            break;
        }
        if (layout.eu && layout.trends) card.graphs.add(graph(panel, true));
        if (layout.ae && layout.trends && !panel.chartKey.isEmpty()) card.graphs.add(graph(panel, false));
        if (layout.stock) {
            List<Row> stockRows = new ArrayList<>();
            for (Row row : panel.rows) if (row.kind != 0) stockRows.add(row);
            card.pages = Math.max(1, (stockRows.size() + 7) / 8);
            int page = Math.min(rowPages.getOrDefault(panel.binding.panelId.toString(), 0), card.pages - 1);
            card.page = page;
            for (int i = page * 8; i < Math.min(stockRows.size(), (page + 1) * 8); i++) card.lines.add(
                PortableMonitorClientCache.pinnedStockText(
                    stockRows.get(i),
                    panel.displayMode,
                    layout.amount,
                    layout.instant,
                    layout.average));
        }
    }

    private static PinnedLine graph(Panel panel, boolean eu) {
        double[] values = eu ? panel.euChart : panel.aeChart, rates = eu ? panel.euRateChart : panel.aeRateChart;
        int divisor = layout.window == 0 ? 1 : layout.window == 1 ? 2 : 4;
        if (divisor > 1) {
            values = Arrays
                .copyOfRange(values, Math.max(0, values.length - Math.max(2, values.length / divisor)), values.length);
            rates = Arrays
                .copyOfRange(rates, Math.max(0, rates.length - Math.max(2, rates.length / divisor)), rates.length);
        }
        String label = eu ? "EU / EU/t" : "AE";
        double change = 0;
        for (Row row : panel.rows) if (eu ? row.kind == 0 : row.key.equals(panel.chartKey)) {
            if (!eu) label = row.name + " / " + (row.kind == 2 ? "mB/min" : "/min");
            change = row.rate;
            break;
        }
        label += " [" + (eu ? panel.window : panel.aeWindow) + "]";
        if (divisor > 1) label += " · " + tr("recent") + " 1/" + divisor;
        return new PinnedLine(label, values, rates, change, "", panel.palette, eu);
    }

    private static void drawCard(FontRenderer font, String id, Card card, int x, int y, int w, int h) {
        drawRect(x, y, x + w, y + h, CARD);
        drawRect(x, y, x + w, y + 21, HEADER);
        int index = layout.order.indexOf(id) + 1;
        text(font, tr("screen") + " " + index, x + 8, y + 7, ACCENT, w - 82);
        button(font, x + w - 69, y + 2, 20, "↑", () -> changeOrder(id, -1), true);
        button(font, x + w - 47, y + 2, 20, "↓", () -> changeOrder(id, 1), true);
        button(font, x + w - 25, y + 2, 20, "×", () -> {
            layout.hidden.add(id);
            changed();
        }, true);
        Panel summary = card.summary;
        MonitorBindings.Binding binding = summary == null ? binding(id) : summary.binding;
        text(font, binding == null ? tr("unknown") : binding.label(), x + 8, y + 27, MUTED, w - 16);
        text(font, tr("window") + ": " + (summary == null ? "?" : summary.window), x + 8, y + 39, MUTED, w - 16);
        text(
            font,
            tr("owner") + ": "
                + (summary == null || summary.ownerName.isEmpty() ? tr("unknown") : summary.ownerName)
                + " · AE "
                + (summary == null ? "?" : summary.aeConnected ? tr("online") : tr("offline")),
            x + 8,
            y + 51,
            MUTED,
            w - 16);
        long age = card.received == 0 ? -1 : Math.max(0, (System.currentTimeMillis() - card.received) / 1000);
        boolean expired = age < 0 || System.currentTimeMillis() - card.received
            > Math.max(10000L, Math.max(1, bindings.size()) * Math.max(1100L, Config.hudUpdateInterval * 50L) * 2);
        String status = summary == null ? tr("waiting")
            : summary.state != 0 ? PortableMonitorClientCache.stateText(summary.state)
                : StatCollector.translateToLocal("gtswn.telemetry.display." + displayStatus(card.detail))
                    + (age < 0 ? "" : " · " + age + "s");
        text(font, status, x + 8, y + 65, expired || summary != null && summary.state != 0 ? WARN : ACCENT, w - 16);
        if (summary == null || summary.state != 0 || card.detail == null) return;
        int at = y + 82;
        for (String line : card.lines) {
            text(font, line, x + 8, at, TEXT, w - 16);
            at += layout.size == 0 ? 12 : 14;
        }
        int graphHeight = layout.size == 0 ? 60 : layout.size == 1 ? 86 : 116;
        for (PinnedLine graph : card.graphs) {
            drawRect(x + 7, at, x + w - 7, at + graphHeight - 4, 0x80202A32);
            PortableMonitorCharts.drawCompact(graph, x + 9, at + 3, w - 18, graphHeight - 6, font);
            if (graph.chart.length < 2 && graph.rateChart.length < 2)
                text(font, tr("samples.waiting"), x + 13, at + 25, MUTED, w - 26);
            at += graphHeight;
        }
        if (card.pages > 1) {
            final int previous = Math.floorMod(card.page - 1, card.pages), next = (card.page + 1) % card.pages;
            button(font, x + 8, at, 30, "<", () -> page(id, previous), true);
            text(font, (card.page + 1) + " / " + card.pages, x + 46, at + 5, MUTED, w - 100);
            button(font, x + w - 38, at, 30, ">", () -> page(id, next), true);
        }
    }

    private static MonitorBindings.Binding binding(String id) {
        for (MonitorBindings.Binding binding : bindings) if (binding.panelId.toString()
            .equals(id)) return binding;
        return null;
    }

    private static void page(String id, int page) {
        rowPages.put(id, page);
        optionsVersion++;
    }

    private static void changeOrder(String id, int delta) {
        layout.move(id, delta);
        changed();
    }

    private static void changed() {
        optionsVersion++;
        saveFailed = !store.save(identity, layout);
    }

    private static void drawSettings(FontRenderer font) {
        int w = Math.min(280, width - 36), x = width - w - 18, top = 76, bottom = height - 27;
        drawRect(x - 2, top - 2, x + w + 2, bottom + 2, ACCENT);
        drawRect(x, top, x + w, bottom, BACK);
        try (GlScope ignored = new GlScope()) {
            scissor(x, top, x + w, bottom);
            try {
                int y = top + 5 - menuScroll;
                setting(
                    font,
                    x + 6,
                    y,
                    w - 12,
                    tr("columns") + ": " + (layout.columns == 0 ? tr("auto") : layout.columns),
                    () -> {
                        layout.columns = (layout.columns + 1) % 5;
                        changed();
                    });
                y += 22;
                setting(font, x + 6, y, w - 12, tr("size") + ": " + tr("size." + layout.size), () -> {
                    layout.size = (layout.size + 1) % 3;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, tr("window") + ": " + tr("window." + layout.window), () -> {
                    layout.window = (layout.window + 1) % 3;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("overview", layout.overview), () -> {
                    layout.overview = !layout.overview;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("eu", layout.eu), () -> {
                    layout.eu = !layout.eu;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("ae", layout.ae), () -> {
                    layout.ae = !layout.ae;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("stock", layout.stock), () -> {
                    layout.stock = !layout.stock;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("amount", layout.amount), () -> {
                    layout.amount = !layout.amount;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("instant", layout.instant), () -> {
                    layout.instant = !layout.instant;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("average", layout.average), () -> {
                    layout.average = !layout.average;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, toggleLabel("trends", layout.trends), () -> {
                    layout.trends = !layout.trends;
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, tr("show.all") + " (" + layout.hidden.size() + ")", () -> {
                    layout.hidden.clear();
                    changed();
                });
                y += 22;
                setting(font, x + 6, y, w - 12, tr("reset"), () -> {
                    layout = new PortableBoardLayout();
                    List<String> ids = new ArrayList<>();
                    for (MonitorBindings.Binding binding : bindings) ids.add(binding.panelId.toString());
                    layout.reconcile(ids);
                    rowPages.clear();
                    scroll = 0;
                    changed();
                });
                y += 24;
                menuMax = Math.max(0, y + menuScroll - bottom);
            } finally {
                if (!embedded) Stencil.remove();
            }
        }
        menuScroll = Math.min(menuScroll, menuMax);
    }

    private static void setting(FontRenderer font, int x, int y, int w, String label, Runnable action) {
        button(font, x, y, w, label, action, false);
    }

    private static String toggleLabel(String key, boolean value) {
        return (value ? "[+] " : "[-] ") + tr(key);
    }

    private static void button(FontRenderer font, int x, int y, int w, String label, Runnable action, boolean card) {
        drawRect(x, y, x + w, y + 18, 0xFF38515B);
        text(font, label, x + 5, y + 5, TEXT, w - 10);
        hits.add(new Hit(x, y, w, action, card, settings && !card && y >= 76));
    }

    public static boolean click(int mouseX, int mouseY, int button) {
        if (!open || mouseY < 50) return false;
        if (button == 0) for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.card && (settings || mouseY < TOP + (layout.overview ? 84 : 0) || mouseY >= height - 26)) continue;
            if (hit.menu && (mouseY < 76 || mouseY >= height - 27)) continue;
            if (mouseX >= hit.x && mouseX < hit.x + hit.w && mouseY >= hit.y && mouseY < hit.y + 18) {
                hit.action.run();
                break;
            }
        }
        return true;
    }

    public static boolean pointer(int x, int y, int button, boolean pressed) {
        if (draggingScroll) {
            if (button == 0 && !pressed) draggingScroll = false;
            else scroll = Math.max(
                0,
                Math.min(
                    maxScroll,
                    dragScroll
                        + (y - dragY) * (maxScroll + trackBottom - trackTop) / Math.max(1, trackBottom - trackTop)));
            return true;
        }
        if (open && !settings
            && maxScroll > 0
            && button == 0
            && pressed
            && x >= width - 18
            && x < width - 8
            && y >= trackTop
            && y < trackBottom) {
            draggingScroll = true;
            dragY = y;
            dragScroll = scroll;
            return true;
        }
        return false;
    }

    public static boolean pointerCaptured() {
        return draggingScroll;
    }

    public static boolean key(int code, boolean pressed) {
        int direction = code == org.lwjgl.input.Keyboard.KEY_PRIOR ? -1
            : code == org.lwjgl.input.Keyboard.KEY_NEXT ? 1 : 0;
        if (direction == 0 && code != org.lwjgl.input.Keyboard.KEY_HOME && code != org.lwjgl.input.Keyboard.KEY_END)
            return false;
        if (pressed) {
            if (code == org.lwjgl.input.Keyboard.KEY_HOME) {
                if (settings) menuScroll = 0;
                else scroll = 0;
            } else if (code == org.lwjgl.input.Keyboard.KEY_END) {
                if (settings) menuScroll = menuMax;
                else scroll = maxScroll;
            } else if (settings)
                menuScroll = Math.max(0, Math.min(menuMax, menuScroll + direction * Math.max(1, height - 112)));
            else scroll = Math.max(0, Math.min(maxScroll, scroll + direction * Math.max(1, trackBottom - trackTop)));
        }
        return true;
    }

    public static boolean wheel(int mouseX, int mouseY, int delta) {
        if (!open || mouseY < 50) return false;
        int amount = delta > 0 ? -32 : delta < 0 ? 32 : 0;
        int menuWidth = Math.min(280, width - 36);
        if (settings && mouseX >= width - menuWidth - 20 && mouseY >= 76)
            menuScroll = Math.max(0, Math.min(menuMax, menuScroll + amount));
        else scroll = Math.max(0, Math.min(maxScroll, scroll + amount));
        return true;
    }

    private static void text(FontRenderer font, String text, int x, int y, int color, int max) {
        font.drawStringWithShadow(font.trimStringToWidth(text, Math.max(1, max)), x, y, color);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal("gtswn.engineering.board." + key);
    }

    private static void scissor(int left, int top, int right, int bottom) {
        if (embedded) {
            Minecraft mc = Minecraft.getMinecraft();
            double sx = mc.displayWidth / (double) virtualWidth, sy = mc.displayHeight / (double) virtualHeight;
            int x = (int) Math.floor((embeddedX + left) * sx);
            int y = (int) Math.floor(mc.displayHeight - (embeddedY + bottom) * sy);
            int w = (int) Math.ceil((right - left) * sx), h = (int) Math.ceil((bottom - top) * sy);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, Math.max(0, w), Math.max(0, h));
            return;
        }
        // The MUI host scales and centres the board; use that same matrix for clipping.
        Stencil.apply(left, top, Math.max(0, right - left), Math.max(0, bottom - top), GuiContext.getDefault());
    }

    private static final class DisplayScope {

        final Object world, connection, player;
        final EngineeringTerminalSources.Source source;

        DisplayScope(Object world, Object connection, Object player, EngineeringTerminalSources.Source source) {
            this.world = world;
            this.connection = connection;
            this.player = player;
            this.source = source;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof DisplayScope)) return false;
            DisplayScope s = (DisplayScope) other;
            return world == s.world && connection == s.connection
                && player == s.player
                && source.sameIdentity(s.source);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(world);
        }
    }

    private static final class Card {

        Panel summary, detail;
        long received, attempted, receipt;
        int builtOptions = -1, pages = 1, page;
        final List<String> lines = new ArrayList<>();
        final List<PinnedLine> graphs = new ArrayList<>();
    }

    private static final class Hit {

        final int x, y, w;
        final Runnable action;
        final boolean card, menu;

        Hit(int x, int y, int w, Runnable action, boolean card, boolean menu) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.action = action;
            this.card = card;
            this.menu = menu;
        }
    }
}
