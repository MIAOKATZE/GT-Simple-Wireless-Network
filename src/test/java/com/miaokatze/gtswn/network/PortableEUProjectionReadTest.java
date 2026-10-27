package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.util.FormatUtil;

public class PortableEUProjectionReadTest {

    @Test
    public void scalarAndScientificFallbackShareOneBalanceRead() {
        BigInteger value = BigInteger.TEN.pow(600);
        int[] reads = { 0 };
        TileEntityNetworkInfoPanel source = new TileEntityNetworkInfoPanel() {

            @Override
            public BigInteger getCachedEu() {
                reads[0]++;
                return value;
            }

            @Override
            public List<NetworkInfoSample> getCachedSamples() {
                return Collections.emptyList();
            }
        };
        PacketSyncPortableMonitor.Panel target = new PacketSyncPortableMonitor.Panel();
        PortableMonitorRequestQueue.fillEU(target, source, true);
        assertEquals(1, reads[0]);
        assertEquals(value.toString(), target.currentEnergy);
        assertEquals(FormatUtil.formatScientific(value), target.rows.get(0).amount);
        reads[0] = 0;
        PortableMonitorRequestQueue.fillEU(new PacketSyncPortableMonitor.Panel(), source, false);
        assertEquals(0, reads[0]);
    }
}
