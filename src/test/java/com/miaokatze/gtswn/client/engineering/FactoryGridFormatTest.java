package com.miaokatze.gtswn.client.engineering;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;

import org.junit.Test;

import com.miaokatze.gtswn.client.engineering.desktop.WidgetKind;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetNumberFormat;
import com.miaokatze.gtswn.client.engineering.desktop.WidgetSpec;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;

public class FactoryGridFormatTest {

    @Test
    public void onlyPowerGetsTheExistingUnsignedAmpTierSuffixAndExtremeValuesRemainUnknown() {
        WidgetSpec widget = new WidgetSpec("grid", WidgetKind.WIRELESS, 0, 0, 4, 3);
        assertEquals("1.5K EU", FactoryDashboard.gridValue(widget, new BigDecimal("1500"), false));
        String power = FactoryDashboard.gridValue(widget, new BigDecimal("-1024"), true);
        assertTrue(power.startsWith("-1.0K EU/t ("));
        assertTrue(power.contains("2.0A HV"));
        assertFalse(power.contains("--"));
        assertFalse(power.contains("-2.0A"));
        assertTrue(
            FactoryDashboard.gridTier(new BigDecimal("1E20"))
                .contains("A MAX"));
        assertEquals(" (A ?)", FactoryDashboard.gridTier(new BigDecimal("1E400")));
        assertEquals(" (A ?)", FactoryDashboard.gridTier(new BigDecimal("1E30")));
        assertEquals("", FactoryDashboard.gridTier(null));
        assertEquals("\u2014 EU/t", FactoryDashboard.gridValue(widget, null, true));
        for (double missing : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            WirelessGridReading reading = WirelessGridReading.readTerminal(
                BigInteger.TEN,
                Collections.singletonList(new NetworkInfoSample(1, 1, BigInteger.TEN, missing)),
                "grid",
                1);
            assertNull(reading.netRate);
            assertEquals("\u2014 EU/t", FactoryDashboard.gridValue(widget, reading.netRate, true));
        }
    }

    @Test
    public void axesFollowNotationAndRetainDistinctLargeTicksWithoutChangingUnits() {
        BigDecimal lower = new BigDecimal("15000000000000"), upper = new BigDecimal("15100000000000");
        BigDecimal range = upper.subtract(lower);
        for (WidgetNumberFormat format : WidgetNumberFormat.values()) {
            String a = FactoryChartGeometry.axisLabel(lower, range, format);
            String b = FactoryChartGeometry.axisLabel(upper, range, format);
            assertNotEquals(a, b);
            assertFalse(a.contains("A"));
            assertFalse(b.contains("A"));
        }
        assertTrue(
            FactoryChartGeometry.axisLabel(lower, range, WidgetNumberFormat.METRIC)
                .endsWith("T"));
        assertTrue(
            FactoryChartGeometry.axisLabel(lower, range, WidgetNumberFormat.SCIENTIFIC)
                .contains("E13"));
        assertEquals("15,000,000,000,000", FactoryChartGeometry.axisLabel(lower, range, WidgetNumberFormat.NORMAL));
    }
}
