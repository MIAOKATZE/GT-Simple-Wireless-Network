package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketRenameQuantumTerminalTest {

    private boolean valid(PacketRenameQuantumTerminal message) throws Exception {
        Field valid = PacketRenameQuantumTerminal.class.getDeclaredField("valid");
        valid.setAccessible(true);
        return valid.getBoolean(message);
    }

    @Test
    public void chineseFortyCharacterNameAndEmptyResetRoundTripExactly() throws Exception {
        for (String name : new String[] { "", "E 中文终端", new String(new char[40]).replace('\0', '中') }) {
            ByteBuf input = Unpooled.buffer(), output = Unpooled.buffer();
            try {
                new PacketRenameQuantumTerminal(19, 3, 9123412341234L, "旧名称", name).toBytes(input);
                PacketRenameQuantumTerminal decoded = new PacketRenameQuantumTerminal();
                decoded.fromBytes(input);
                assertTrue(valid(decoded));
                decoded.toBytes(output);
                assertEquals(input.readerIndex(), input.writerIndex());
                assertEquals(19, output.readInt());
                assertEquals(3, output.readUnsignedByte());
                assertEquals(9123412341234L, output.readLong());
                assertEquals("旧名称", read(output));
                assertEquals(name, read(output));
                assertFalse(output.isReadable());
            } finally {
                input.release();
                output.release();
            }
        }
    }

    private String read(ByteBuf buffer) {
        int length = buffer.readUnsignedShort();
        String value = buffer.toString(buffer.readerIndex(), length, io.netty.util.CharsetUtil.UTF_8);
        buffer.skipBytes(length);
        return value;
    }

    @Test
    public void malformedSlotsWindowsTokensLengthsTruncationAndTrailingBytesAreRejected() throws Exception {
        ByteBuf frame = Unpooled.buffer();
        try {
            new PacketRenameQuantumTerminal(19, 3, 42, "", "中文").toBytes(frame);
            for (int length = 0; length < frame.writerIndex(); length++) {
                PacketRenameQuantumTerminal decoded = new PacketRenameQuantumTerminal();
                decoded.fromBytes(frame.slice(0, length));
                assertFalse("truncated length=" + length, valid(decoded));
            }
            int[][] corruptions = { { 0, 0 }, { 0, 101 }, { 4, 9 }, { 5, 0 }, { 13, 65535 }, { 15, 65535 } };
            for (int[] corruption : corruptions) {
                ByteBuf copy = frame.copy();
                try {
                    if (corruption[0] == 0) copy.setInt(0, corruption[1]);
                    else if (corruption[0] == 4) copy.setByte(4, corruption[1]);
                    else if (corruption[0] == 5) copy.setLong(5, corruption[1]);
                    else copy.setShort(corruption[0], corruption[1]);
                    PacketRenameQuantumTerminal decoded = new PacketRenameQuantumTerminal();
                    decoded.fromBytes(copy);
                    assertFalse("corrupt field=" + corruption[0], valid(decoded));
                } finally {
                    copy.release();
                }
            }
            frame.writeByte(0);
            PacketRenameQuantumTerminal decoded = new PacketRenameQuantumTerminal();
            decoded.fromBytes(frame);
            assertFalse(valid(decoded));
            frame.clear();
            new PacketRenameQuantumTerminal(19, 3, 42, "", new String(new char[41]).replace('\0', 'a')).toBytes(frame);
            decoded.fromBytes(frame);
            assertFalse(valid(decoded));
        } finally {
            frame.release();
        }
    }
}
