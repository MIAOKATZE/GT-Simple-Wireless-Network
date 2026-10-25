package com.miaokatze.gtswn.common.panel;

import static org.junit.Assert.*;

import org.junit.Test;

public class AEMonitorWindowSpanTest {

    @Test
    public void nonDefaultIntervalsExposeActualCoverageAndUseActualRateDenominator() {
        for (int interval : new int[] { 20, 100, 200 }) {
            AEMonitorDataSet data = new AEMonitorDataSet();
            for (int i = 0; i <= 70; i++) data.addSample("test", i * interval * 3L, i * interval, 0L);
            assertEquals(61, data.size("test", 0));
            assertEquals(interval * 60L, data.spanTicks("test", 0));
            assertEquals(3600.0D, data.averageRate("test"), 0.0D);
            assertEquals(data.averageRate("test"), data.averageRate300s("test"), 0.0D);
            assertEquals(
                interval == 20 ? "1m" : interval == 100 ? "5m" : "10m",
                WindowLabel.actualSpan(data.query("test", 0)));
        }
    }

    @Test
    public void partialIrregularAndRestoredWindowsUseObservedTicks() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        data.addSample("test", 10L, 1000L, 0L);
        assertEquals(0L, data.spanTicks("test", 0));
        assertEquals(0.0D, data.averageRate("test"), 0.0D);
        data.addSample("test", 70L, 1240L, 0L);
        data.addSample("test", 160L, 1600L, 0L);
        assertEquals(600L, data.spanTicks("test", 0));
        assertEquals(300.0D, data.averageRate("test"), 0.0D);
        assertEquals("30s", WindowLabel.actualSpan(data.query("test", 0)));
        AEMonitorDataSet restored = new AEMonitorDataSet();
        restored.readFromNBT(data.toNBT());
        assertEquals(600L, restored.spanTicks("test", 0));
        assertEquals(300.0D, restored.averageRate("test"), 0.0D);
    }
}
