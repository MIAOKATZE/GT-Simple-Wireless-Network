package com.miaokatze.gtswn.client;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.hud.MonitorBindings;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestPortableMonitor;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-main-thread cache shared by the UI and HUD; polling remains independent of frame rate. */
public final class PortableMonitorClientCache {

    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    private static EntityPlayer sessionPlayer;
    private static int nextSequence;
    private static long nextGlobalRequest;

    private PortableMonitorClientCache() {}

    private static void session() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != sessionPlayer) {
            CACHE.clear();
            nextGlobalRequest = 0;
            sessionPlayer = player;
        }
    }

    public static void poll(ItemStack stack) {
        session();
        UUID id = MonitorBindings.id(stack);
        if (id == null || sessionPlayer == null) return;
        if (CACHE.size() >= 32 && !CACHE.containsKey(id)) CACHE.clear();
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        cache.stack = stack;
        long now = System.currentTimeMillis();
        if (now < nextGlobalRequest || cache.inFlight && now - cache.sentAt < 5000
            || cache.action == 0 && !cache.dirty && now - cache.sentAt < Math.max(1000, Config.hudUpdateInterval * 50L))
            return;
        if (!cache.inFlight) {
            PacketRequestPortableMonitor request = new PacketRequestPortableMonitor(
                id,
                ++nextSequence,
                cache.action,
                cache.value);
            request.selected = cache.selected;
            request.charts = Config.hudRenderMode == 2;
            String prefix = id + "/";
            int pinCount = 0;
            for (String entry : Config.hudPinnedEntries) {
                if (!entry.startsWith(prefix) || pinCount >= PacketRequestPortableMonitor.MAX_PINS) continue;
                int split = entry.indexOf('/', prefix.length());
                if (split < 0) continue;
                try {
                    UUID panel = UUID.fromString(entry.substring(prefix.length(), split));
                    String key = entry.substring(split + 1);
                    if (key.isEmpty() || key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 256) continue;
                    request.pins.computeIfAbsent(panel, ignored -> new HashSet<>())
                        .add(key);
                    pinCount++;
                } catch (IllegalArgumentException ignored) {}
            }
            if (request.selected < 0 && request.pins.isEmpty() && request.action == 0) return;
            cache.sequence = request.sequence;
            cache.request = request;
            cache.inFlight = true;
            cache.dirty = false;
            cache.action = 0;
        }
        cache.sentAt = now;
        nextGlobalRequest = now + 1100;
        GTSWNPacketHandler.NETWORK.sendToServer(cache.request);
    }

    public static void action(ItemStack stack, int action, int value) {
        session();
        UUID id = MonitorBindings.id(stack);
        if (id == null) return;
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        if (cache.action != 0) return;
        cache.action = action;
        cache.value = value;
        poll(stack);
    }

    public static void viewing(ItemStack stack, int index) {
        session();
        UUID id = MonitorBindings.id(stack);
        if (id == null) return;
        Cached cache = CACHE.computeIfAbsent(id, ignored -> new Cached());
        if (cache.selected != index) {
            cache.selected = index;
            cache.dirty = true;
        }
    }

    /** Pending mutations survive closing the UI or disabling the HUD. Refreshes stay demand-driven. */
    public static final class TickListener {

        @SubscribeEvent
        public void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            session();
            for (Cached cache : CACHE.values()) {
                if (cache.action != 0 || cache.inFlight && cache.request.action != 0) poll(cache.stack);
            }
        }
    }

    public static void receive(PacketSyncPortableMonitor response) {
        session();
        Cached cache = CACHE.get(response.terminalId);
        if (cache == null || !cache.inFlight || cache.sequence != response.sequence) return;
        cache.snapshot = response;
        cache.receivedAt = System.currentTimeMillis();
        cache.inFlight = false;
    }

    public static PacketSyncPortableMonitor snapshot(ItemStack stack) {
        session();
        Cached cache = CACHE.get(MonitorBindings.id(stack));
        return cache == null ? null : cache.snapshot;
    }

    public static boolean stale(ItemStack stack) {
        Cached cache = CACHE.get(MonitorBindings.id(stack));
        return cache == null
            || System.currentTimeMillis() - cache.receivedAt > Math.max(10000, Config.hudUpdateInterval * 150L);
    }

    public static String pinKey(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row) {
        return terminal + "/" + panel.binding.panelId + "/" + row.key;
    }

    public static boolean pinned(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row) {
        return Arrays.asList(Config.hudPinnedEntries)
            .contains(pinKey(terminal, panel, row));
    }

    public static boolean togglePin(UUID terminal, PacketSyncPortableMonitor.Panel panel,
        PacketSyncPortableMonitor.Row row) {
        List<String> entries = new ArrayList<>(Arrays.asList(Config.hudPinnedEntries));
        String key = pinKey(terminal, panel, row);
        if (!entries.remove(key)) {
            if (entries.size() >= PacketRequestPortableMonitor.MAX_PINS) return false;
            entries.add(key);
        }
        Config.hudPinnedEntries = entries.toArray(new String[0]);
        Cached cache = CACHE.get(terminal);
        if (cache != null) cache.dirty = true;
        return Config.saveHudConfiguration();
    }

    public static List<PinnedLine> pinnedLines(ItemStack stack) {
        PacketSyncPortableMonitor snapshot = snapshot(stack);
        if (snapshot == null) return Collections.emptyList();
        Cached cache = CACHE.get(snapshot.terminalId);
        int options = Arrays.hashCode(Config.hudPinnedEntries) * 31 + (Config.hudTierEnabled ? 1 : 0)
            + (Config.hudAEAmountEnabled ? 2 : 0)
            + (Config.hudAERateEnabled ? 4 : 0)
            + (stale(stack) ? 8 : 0)
            + (Config.hudEUEnabled ? 16 : 0)
            + (Config.hudInstantEnabled ? 32 : 0)
            + (Config.hudAverageEnabled ? 64 : 0)
            + (Config.hudCompact ? 128 : 0);
        if (cache.renderSnapshot == snapshot && cache.renderOptions == options) return cache.renderLines;
        List<PinnedLine> lines = new ArrayList<>();
        for (PacketSyncPortableMonitor.Panel panel : snapshot.panels) {
            String prefix = snapshot.terminalId + "/" + panel.binding.panelId + "/";
            if (panel.state != 0 || stale(stack)) {
                for (String key : Config.hudPinnedEntries) {
                    if (key.startsWith(prefix)) lines.add(
                        new PinnedLine(
                            "\u00a77" + panel.binding.label()
                                + " \u00a76"
                                + stateText(panel.state == 0 ? 4 : panel.state),
                            new double[0]));
                }
                continue;
            }
            for (PacketSyncPortableMonitor.Row row : panel.rows) {
                if (!pinned(snapshot.terminalId, panel, row)) continue;
                String text = pinnedRowText(row);
                if (text != null) lines.add(
                    new PinnedLine(
                        "\u00a73[" + (snapshot.panels.indexOf(panel) + 1) + "] " + text,
                        row.kind == 0 ? panel.euChart : panel.aeChart));
            }
        }
        cache.renderSnapshot = snapshot;
        cache.renderOptions = options;
        cache.renderLines = lines;
        return lines;
    }

    /** Viewer always presents complete content, independently of HUD field switches. */
    public static String viewerRowText(PacketSyncPortableMonitor.Row row) {
        if (row.kind == 0) return "\u00a7bEU: \u00a7f" + energyAmount(row.amount)
            + " | "
            + StatCollector.translateToLocal("gtswn.monitor.field.instant")
            + ": "
            + rate(row.rate, "EU/t")
            + " | "
            + StatCollector.translateToLocal("gtswn.monitor.field.average")
            + ": "
            + rate(row.average, "EU/t");
        return "\u00a7b" + row.name
            + ": \u00a7f"
            + row.amount
            + (row.kind == 2 ? " mB" : "")
            + " "
            + rate(row.rate, row.kind == 2 ? "mB/min" : "/min");
    }

    /** Explicit field options make the EU text formatter independently testable without a client world. */
    public static String pinnedEUText(PacketSyncPortableMonitor.Row row, boolean energy, boolean instant,
        boolean average, boolean compact, boolean tier, String instantLabel, String averageLabel) {
        if (!energy && !instant && !average) return null;
        List<String> parts = new ArrayList<>();
        if (energy) parts.add("\u00a7bEU: \u00a7f" + energyAmount(row.amount));
        if (instant) parts.add("\u00a7b" + (compact ? "\u0394t" : instantLabel) + ": " + rate(row.rate, "EU/t"));
        if (average) parts.add("\u00a7b" + (compact ? "\u0394avg" : averageLabel) + ": " + rate(row.average, "EU/t"));
        if (tier && (instant || average)) parts.add(GTTierUtil.formatGTPower(instant ? row.rate : row.average));
        return String.join(" | ", parts);
    }

    public static String energyAmount(String amount) {
        try {
            return FormatUtil.formatScientific(new BigInteger(amount));
        } catch (NumberFormatException ignored) {
            return amount;
        }
    }

    private static String pinnedRowText(PacketSyncPortableMonitor.Row row) {
        String amount = row.amount;
        if (row.kind == 0) {
            return pinnedEUText(
                row,
                Config.hudEUEnabled,
                Config.hudInstantEnabled,
                Config.hudAverageEnabled,
                Config.hudCompact,
                Config.hudTierEnabled,
                StatCollector.translateToLocal("gtswn.monitor.field.instant"),
                StatCollector.translateToLocal("gtswn.monitor.field.average"));
        }
        return "\u00a7b" + row.name
            + (Config.hudAEAmountEnabled ? ": \u00a7f" + amount + (row.kind == 2 ? " mB" : "") : "")
            + (Config.hudAERateEnabled ? " " + rate(row.rate, row.kind == 2 ? "mB/min" : "/min") : "");
    }

    public static String gridStatus(PacketSyncPortableMonitor.Panel panel, PacketSyncPortableMonitor.Row row) {
        if (panel.euChart.length < 2)
            return "\u00a76" + StatCollector.translateToLocal("gtswn.hud.network.status.calculating");
        if (row.rate == 0) return "\u00a77" + StatCollector.translateToLocal("gtswn.hud.network.status.silent");
        if (Math.abs(row.rate) < 1) return "\u00a77" + StatCollector.translateToLocal("gtswn.hud.network.status.lt1");
        return StatCollector
            .translateToLocal(row.rate > 0 ? "gtswn.monitor.grid.increasing" : "gtswn.monitor.grid.decreasing");
    }

    public static String rate(double value, String unit) {
        return (value > 0 ? "\u00a7a+" : value < 0 ? "\u00a7c-" : "\u00a77")
            + FormatUtil.formatScientificDouble(Math.abs(value))
            + " "
            + unit;
    }

    public static String stateText(int state) {
        return StatCollector.translateToLocal("gtswn.monitor.state." + state);
    }

    public static final class PinnedLine {

        public final String text;
        public final double[] chart;

        PinnedLine(String text, double[] chart) {
            this.text = text;
            this.chart = chart;
        }
    }

    private static final class Cached {

        PacketSyncPortableMonitor snapshot;
        int sequence, action, value;
        long sentAt, receivedAt;
        boolean inFlight;
        boolean dirty;
        int selected = -1;
        ItemStack stack;
        PacketRequestPortableMonitor request;
        PacketSyncPortableMonitor renderSnapshot;
        int renderOptions;
        List<PinnedLine> renderLines;
    }
}
