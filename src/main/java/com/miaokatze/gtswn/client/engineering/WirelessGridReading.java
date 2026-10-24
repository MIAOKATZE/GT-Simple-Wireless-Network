package com.miaokatze.gtswn.client.engineering;

import java.math.BigDecimal;
import java.math.BigInteger;

import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Panel;
import com.miaokatze.gtswn.network.PacketSyncPortableMonitor.Row;

/** A projection of one authorized information screen, never a sum of observed machines. */
final class WirelessGridReading {

    final BigDecimal stored, netRate;
    final String state;

    private WirelessGridReading(BigDecimal stored, BigDecimal netRate, String state) {
        this.stored = stored;
        this.netRate = netRate;
        this.state = state;
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
