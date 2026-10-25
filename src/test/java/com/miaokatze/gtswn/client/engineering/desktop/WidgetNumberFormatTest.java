package com.miaokatze.gtswn.client.engineering.desktop;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Properties;

import org.junit.Test;

public class WidgetNumberFormatTest {

    @Test
    public void formatsSignedFractionsRoundingAndValuesBeyondDoubleWithoutLosingStockDigits() {
        assertEquals("1.5K", WidgetNumberFormat.METRIC.format(new BigDecimal("1500")));
        assertEquals("-1.5K", WidgetNumberFormat.METRIC.format(new BigDecimal("-1500")));
        assertEquals("0.0", WidgetNumberFormat.METRIC.format(BigDecimal.ZERO));
        assertEquals("1.00E3", WidgetNumberFormat.SCIENTIFIC.format(new BigDecimal("999.999")));
        assertEquals("1.25E-400", WidgetNumberFormat.SCIENTIFIC.format(new BigDecimal("1.25E-400")));
        assertEquals("1.00E400", WidgetNumberFormat.SCIENTIFIC.format(BigDecimal.TEN.pow(400)));
        assertFalse(
            WidgetNumberFormat.METRIC.format(BigDecimal.TEN.pow(400))
                .contains("Infinity"));
        assertEquals(
            "1,000,000,000,000,000,009",
            WidgetNumberFormat.NORMAL.format(new BigDecimal("1000000000000000009")));
        assertEquals("-1,234.567", WidgetNumberFormat.NORMAL.format(new BigDecimal("-1234.567")));
        assertEquals("-0.25", WidgetNumberFormat.NORMAL.format(new BigDecimal("-0.25")));
        for (WidgetNumberFormat format : WidgetNumberFormat.values()) assertEquals("\u2014", format.format(null));
    }

    @Test
    public void nondefaultNotationSurvivesEveryMutationAndStoreCopyWithBackwardCompatibleDefaults() {
        for (WidgetKind kind : WidgetKind.values()) {
            WidgetSpec original = new WidgetSpec("card", kind, 0, 0, 8, 8)
                .withNumberFormat(WidgetNumberFormat.SCIENTIFIC);
            WidgetSpec changed = original.withBounds(1, 2, 8, 8)
                .titled("Title")
                .configured("Title", Collections.<String>emptyList(), 2)
                .presented(original.presentation, original.metricField, null)
                .withRate(false, 120)
                .withAutoResource(true)
                .withSingleResource(true)
                .bordered(0x123456)
                .boundResource(null)
                .boundDevice(null)
                .boundScreen(null)
                .styled(WidgetEffects.DEFAULT)
                .withAutoDevice(true)
                .renamed("copy")
                .withId("card")
                .asTemplate();
            assertEquals(kind.name(), WidgetNumberFormat.SCIENTIFIC, changed.numberFormat);
            DesktopLayout layout = new DesktopLayout();
            assertTrue(layout.add(changed));
            assertEquals(
                WidgetNumberFormat.SCIENTIFIC,
                DesktopStore.decode(DesktopStore.encode(layout.copy()))
                    .find("card").numberFormat);
            assertEquals(
                WidgetNumberFormat.SCIENTIFIC,
                DesktopAutoBind.initialize(layout)
                    .find("card").numberFormat);
        }
        DesktopLayout old = new DesktopLayout();
        assertTrue(old.add(new WidgetSpec("card", WidgetKind.WIRELESS, 0, 0, 4, 3)));
        Properties legacy = DesktopStore.encode(old);
        legacy.remove("widget.0.numberFormat");
        assertEquals(
            WidgetNumberFormat.METRIC,
            DesktopStore.decode(legacy)
                .find("card").numberFormat);
        legacy.setProperty("widget.0.numberFormat", "UNKNOWN");
        assertEquals(
            WidgetNumberFormat.METRIC,
            DesktopStore.decode(legacy)
                .find("card").numberFormat);
    }
}
