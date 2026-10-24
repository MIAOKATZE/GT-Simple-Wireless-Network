package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.AEMonitorDataSet;

public class PortableMonitorWindowSpanTest {

    @Test
    public void selectedWindowReportsItsObservedCoverageInsteadOfNominalDuration() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        for (int i = 0; i <= 60; i++) data.addSample("sample", i * 10L, i * 200L, 0L);
        assertEquals("10m", PortableMonitorRequestQueue.aeWindow(data, "sample", AEMonitorDataSet.WINDOW_5_MIN));
        assertEquals("8m", PortableMonitorRequestQueue.aeWindow(data, "sample", AEMonitorDataSet.WINDOW_1_HOUR));
    }

    @Test
    public void missingOrSinglePointHistoryNeverClaimsNominalDuration() {
        AEMonitorDataSet data = new AEMonitorDataSet();
        assertEquals("—", PortableMonitorRequestQueue.aeWindow(null, "sample", 0));
        assertEquals("—", PortableMonitorRequestQueue.aeWindow(data, null, 0));
        assertEquals("—", PortableMonitorRequestQueue.aeWindow(data, "sample", 0));
        data.addSample("sample", 1L, 100L, 0L);
        assertEquals("—", PortableMonitorRequestQueue.aeWindow(data, "sample", 0));
    }
}
