package com.miaokatze.gtswn.common.util;

import static org.junit.Assert.assertEquals;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Random;

import org.junit.Test;

public class ScientificThresholdTextTest {

    @Test
    public void ordinaryThresholdDoesNotChangeWhenItsDisplayedTextIsParsed() {
        assertEquals("1.2345678E7", FormatUtil.formatEditableScientific(12345678L));
        assertEquals(12345678L, Math.round(Double.parseDouble(FormatUtil.formatEditableScientific(12345678L))));
        assertEquals(-12345678L, Math.round(Double.parseDouble(FormatUtil.formatEditableScientific(-12345678L))));
    }

    @Test
    public void notationIsIndependentOfThePlayersDecimalSeparator() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("1.5E6", FormatUtil.formatEditableScientific(1500000L));
            assertEquals("-1.2345678E7", FormatUtil.formatEditableScientific(-12345678L));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void smallValuesAndPowersOfTenRemainCompact() {
        for (long value : new long[] { 0, 1, -1, 9999, -9999 })
            assertEquals(Long.toString(value), FormatUtil.formatEditableScientific(value));
        assertEquals("1E4", FormatUtil.formatEditableScientific(10000));
        assertEquals("-1E4", FormatUtil.formatEditableScientific(-10000));
        assertEquals("1E18", FormatUtil.formatEditableScientific(1000000000000000000L));
    }

    @Test
    public void formattingRetainsTheFullLongDomainWithoutDoubleRounding() {
        long[] boundaries = { Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE, Long.MAX_VALUE - 1,
            9007199254740993L, -9007199254740993L, 999999999999999999L };
        for (long value : boundaries) assertExact(value);
        Random random = new Random(98231);
        for (int i = 0; i < 10000; i++) assertExact(random.nextLong());
    }

    private static void assertExact(long value) {
        assertEquals(value, new BigDecimal(FormatUtil.formatEditableScientific(value)).longValueExact());
    }
}
