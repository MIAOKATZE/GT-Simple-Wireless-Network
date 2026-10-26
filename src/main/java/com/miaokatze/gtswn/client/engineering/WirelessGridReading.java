package com.miaokatze.gtswn.client.engineering;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.hud.HudController;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;

/** Read-only user grid projection from the portable terminal's original HUD sampler. */
final class WirelessGridReading {

    final BigDecimal stored, netRate;
    final String state;
    final String source;
    final boolean fresh;
    final long receivedAtMillis;
    final List<NetworkInfoSample> history;
    private final Map<Integer, BigDecimal> averages = new HashMap<>();

    private WirelessGridReading(BigDecimal stored, BigDecimal netRate, String state) {
        this(stored, netRate, state, "", false, 0, Collections.emptyList());
    }

    private WirelessGridReading(BigDecimal stored, BigDecimal netRate, String state, String source, boolean fresh,
        long receivedAtMillis, List<NetworkInfoSample> history) {
        this.stored = stored;
        this.netRate = netRate;
        this.state = state;
        this.source = source;
        this.fresh = fresh;
        this.receivedAtMillis = receivedAtMillis;
        this.history = history;
    }

    static WirelessGridReading readTerminal() {
        HudController.WirelessReading value = EngineeringTerminalHudSnapshot.wirelessGrid();
        if (value == null) return new WirelessGridReading(null, null, "missing");
        WirelessGridReading reading = readTerminal(
            value.stored,
            value.history,
            value.sourceLabel,
            value.receivedAtMillis);
        return new WirelessGridReading(
            reading.stored,
            reading.netRate,
            reading.state,
            reading.source,
            EngineeringTerminalHudSnapshot.wirelessGridFresh(),
            reading.receivedAtMillis,
            reading.history);
    }

    static WirelessGridReading readTerminal(BigInteger stored, List<NetworkInfoSample> history, String source,
        long receivedAtMillis) {
        NetworkInfoSample newest = history.isEmpty() ? null : history.get(history.size() - 1);
        BigDecimal rate = newest != null && Double.isFinite(newest.eut) ? BigDecimal.valueOf(newest.eut) : null;
        return new WirelessGridReading(
            new BigDecimal(stored),
            rate,
            rate == null ? "warming" : rate.signum() > 0 ? "charging" : rate.signum() < 0 ? "discharging" : "stable",
            source,
            true,
            receivedAtMillis,
            history);
    }

    BigDecimal rate(boolean average, int seconds) {
        if (!average) return netRate;
        int window = Math.max(1, Math.min(3600, seconds));
        if (!averages.containsKey(window)) averages.put(window, averageRate(history, window));
        return averages.get(window);
    }

    String state(boolean average, int seconds) {
        return "missing".equals(state) ? state : state(rate(average, seconds));
    }

    private static String state(BigDecimal value) {
        return value == null ? "warming"
            : value.signum() > 0 ? "charging" : value.signum() < 0 ? "discharging" : "stable";
    }

    /**
     * Time-weighted slope over the cached contiguous suffix. Clip the oldest interval at the window boundary;
     * sparse samples therefore have their real tick weight. Never bridge a missing or reversed interval.
     */
    static BigDecimal averageRate(List<NetworkInfoSample> history, int seconds) {
        if (history.size() < 2) return null;
        NetworkInfoSample latest = history.get(history.size() - 1);
        long window = Math.max(1, Math.min(3600, seconds)) * 20L;
        long cutoff = latest.tick < Long.MIN_VALUE + window ? Long.MIN_VALUE : latest.tick - window;
        BigDecimal change = BigDecimal.ZERO;
        long elapsed = 0;
        for (int i = history.size() - 1; i > 0; i--) {
            NetworkInfoSample end = history.get(i), start = history.get(i - 1);
            if (!Double.isFinite(end.eut) || end.tick <= start.tick || end.tick <= cutoff) break;
            long duration = end.tick - start.tick;
            if (duration <= 0) break;
            long overlap = end.tick - Math.max(start.tick, cutoff);
            BigDecimal delta = new BigDecimal(end.eu.subtract(start.eu));
            change = change.add(
                delta.multiply(BigDecimal.valueOf(overlap))
                    .divide(BigDecimal.valueOf(duration), MathContext.DECIMAL128));
            elapsed += overlap;
            if (start.tick <= cutoff) break;
        }
        return elapsed == 0 ? null : change.divide(BigDecimal.valueOf(elapsed), MathContext.DECIMAL128);
    }

    /** Caller supplies the existing screen cache's freshness/identity gate. Missing is never zero. */
    static WirelessGridReading read(Panel panel, boolean available) {
        if (!available || panel == null || panel.binding == null || panel.state != 0)
            return new WirelessGridReading(null, null, "missing");
        BigDecimal stored = null;
        try {
            stored = new BigDecimal(new BigInteger(panel.currentEnergy));
        } catch (NumberFormatException ignored) {}
        BigDecimal rate = null;
        if (panel.render != null) {
            int count = panel.render.eu.size();
            NetworkInfoSample newest = count == 0 ? null : panel.render.eu.get(count - 1);
            boolean cold = panel.render.config.hasKey("portableCold") ? panel.render.config.getBoolean("portableCold")
                : count < 2;
            // A nonzero first observation already contains a real upstream slope. A cold zero does not.
            if (newest != null && Double.isFinite(newest.eut)) {
                double value = newest.eut;
                // Scalar receipts update more often than the history/configuration generation.
                for (Row row : panel.rows) if (row.kind == 0 && "eu".equals(row.key)) {
                    value = row.rate;
                    break;
                }
                if (Double.isFinite(value) && (!cold || value != 0)) rate = BigDecimal.valueOf(value);
            }
        }
        return new WirelessGridReading(
            stored,
            rate,
            rate == null ? "warming" : rate.signum() > 0 ? "charging" : rate.signum() < 0 ? "discharging" : "stable");
    }
}
