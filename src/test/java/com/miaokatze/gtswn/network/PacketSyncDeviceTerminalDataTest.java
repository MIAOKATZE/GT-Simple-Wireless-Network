package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * {@link PacketSyncDeviceTerminalData} 纯逻辑序列化单测（零 Minecraft 世界启动）。
 * 覆盖包头、条目字段写读顺序、双向流量及负 net 派生值。
 */
public class PacketSyncDeviceTerminalDataTest {

    @Test
    public void roundTripPreservesHeaderFieldOrderAndDerivedNet() {
        UUID terminalId = UUID.fromString("12345678-1234-5678-1234-567812345678");
        Entry consume = new Entry(
            "-1:-2:3:-4",
            "Input Machine",
            (byte) 1,
            (byte) 0,
            400L,
            100L,
            250.5D,
            100.25D,
            -1,
            -2,
            3,
            -4,
            "iron ore",
            "dust");
        Entry generate = new Entry(
            "0:10:64:20",
            "Generator",
            (byte) 2,
            (byte) 1,
            0L,
            900L,
            0D,
            750.5D,
            0,
            10,
            64,
            20,
            "",
            "eu/t output");
        List<Entry> entries = Arrays.asList(consume, generate);
        PacketSyncDeviceTerminalData original = new PacketSyncDeviceTerminalData(terminalId, 42L, 1, 3, 2, entries);

        ByteBuf buffer = Unpooled.buffer();
        original.toBytes(buffer);
        PacketSyncDeviceTerminalData decoded = new PacketSyncDeviceTerminalData();
        decoded.fromBytes(buffer);

        assertEquals(terminalId, decoded.getTerminalId());
        assertEquals(42L, decoded.getVersion());
        assertEquals(1, decoded.getPageIndex());
        assertEquals(3, decoded.getPageTotal());
        assertEquals(2, decoded.getEntryTotal());
        assertEquals(
            2,
            decoded.getEntries()
                .size());

        Entry first = decoded.getEntries()
            .get(0);
        assertEquals("-1:-2:3:-4", first.key);
        assertEquals("Input Machine", first.name);
        assertEquals((byte) 1, first.state);
        assertEquals((byte) 0, first.powerType);
        assertEquals(400L, first.instIn);
        assertEquals(100L, first.instOut);
        assertEquals(250.5D, first.avgIn, 0D);
        assertEquals(100.25D, first.avgOut, 0D);
        assertEquals(-1, first.dim);
        assertEquals(-2, first.x);
        assertEquals(3, first.y);
        assertEquals(-4, first.z);
        assertEquals("iron ore", first.recipeIn);
        assertEquals("dust", first.recipeOut);
        assertEquals(-300L, first.instNet());
        assertEquals(-150.25D, first.avgNet(), 0D);

        Entry second = decoded.getEntries()
            .get(1);
        assertEquals("0:10:64:20", second.key);
        assertEquals("Generator", second.name);
        assertEquals((byte) 2, second.state);
        assertEquals((byte) 1, second.powerType);
        assertEquals(0L, second.instIn);
        assertEquals(900L, second.instOut);
        assertEquals(0D, second.avgIn, 0D);
        assertEquals(750.5D, second.avgOut, 0D);
        assertEquals(0, second.dim);
        assertEquals(10, second.x);
        assertEquals(64, second.y);
        assertEquals(20, second.z);
        assertEquals("", second.recipeIn);
        assertEquals("eu/t output", second.recipeOut);
        assertEquals(900L, second.instNet());
        assertEquals(750.5D, second.avgNet(), 0D);
        assertTrue("字段序回归应保留两条目顺序", first.key.startsWith("-1:") && second.key.startsWith("0:"));
    }
}
