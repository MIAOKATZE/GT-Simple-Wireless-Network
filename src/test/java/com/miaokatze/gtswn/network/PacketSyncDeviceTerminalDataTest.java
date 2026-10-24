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
            "eu/t output",
            1234,
            (byte) 1,
            (byte) 3,
            "Generator alias",
            (byte) 12,
            true,
            (byte) 14);
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
        assertEquals("Generator alias", second.displayName);
        assertEquals((byte) 12, second.nameColor);
        assertTrue(second.nameBold);
        assertEquals(1234, second.metaTileId);
        assertEquals((byte) 2, second.capabilityMask);
        assertEquals((byte) 1, second.evidenceKind);
        assertEquals((byte) 3, second.linkNodeType);
        assertTrue("字段序回归应保留两条目顺序", first.key.startsWith("-1:") && second.key.startsWith("0:"));
    }

    @Test
    public void schemaMismatchAndOldUnmarkedPacketsCannotBecomeValidEntries() {
        ByteBuf old = Unpooled.buffer();
        cpw.mods.fml.common.network.ByteBufUtils.writeUTF8String(
            old,
            UUID.randomUUID()
                .toString());
        old.writeLong(99);
        PacketSyncDeviceTerminalData decoded = new PacketSyncDeviceTerminalData();
        decoded.fromBytes(old);
        org.junit.Assert.assertNull(decoded.getTerminalId());
        assertTrue(
            decoded.getEntries()
                .isEmpty());

        ByteBuf current = Unpooled.buffer();
        new PacketSyncDeviceTerminalData(UUID.randomUUID(), 1, 0, 1, 0, java.util.Collections.emptyList())
            .toBytes(current);
        current.setByte(4, 1);
        decoded.fromBytes(current);
        org.junit.Assert.assertNull(decoded.getTerminalId());
        assertTrue(
            decoded.getEntries()
                .isEmpty());
    }

    @Test
    public void truncatedPageCannotMasqueradeAsACompletePrefix() {
        Entry row = new Entry("0:1:64:2", "Machine", (byte) 1, (byte) 0, 1, 0, 1, 0, 0, 1, 64, 2, "", "");
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketSyncDeviceTerminalData(UUID.randomUUID(), 1, 0, 1, 2, Arrays.asList(row, row)).toBytes(bytes);
            bytes.writerIndex(bytes.writerIndex() - 1);
            PacketSyncDeviceTerminalData decoded = new PacketSyncDeviceTerminalData();
            decoded.fromBytes(bytes);
            org.junit.Assert.assertNull(decoded.getTerminalId());
            assertTrue(
                decoded.getEntries()
                    .isEmpty());
        } finally {
            bytes.release();
        }
    }

    @Test
    public void ignoredWireEntryCannotLeakAnyPowerOrRecipeValues() {
        Entry ignored = new Entry(
            "0:1:64:2",
            "Native",
            (byte) 1,
            (byte) 0,
            20,
            30,
            40,
            50,
            0,
            1,
            64,
            2,
            "recipe",
            "output",
            123,
            (byte) 1,
            (byte) 3,
            "Alias",
            (byte) 7,
            true,
            (byte) 15,
            (byte) 2);
        ByteBuf bytes = Unpooled.buffer();
        try {
            new PacketSyncDeviceTerminalData(
                UUID.randomUUID(),
                1,
                0,
                1,
                1,
                java.util.Collections.singletonList(ignored)).toBytes(bytes);
            PacketSyncDeviceTerminalData decoded = new PacketSyncDeviceTerminalData();
            decoded.fromBytes(bytes);
            Entry entry = decoded.getEntries()
                .get(0);
            assertEquals(3, entry.state);
            assertEquals(2, entry.recognitionMode);
            assertEquals(0, entry.capabilityMask);
            assertEquals(0, entry.instIn + entry.instOut);
            assertEquals(0, entry.avgIn + entry.avgOut, 0);
            assertEquals(0, entry.evidenceKind);
            assertEquals("", entry.recipeIn);
            assertEquals("", entry.recipeOut);
            assertEquals("Alias", entry.displayName);
            assertEquals(123, entry.metaTileId);
        } finally {
            bytes.release();
        }
    }
}
