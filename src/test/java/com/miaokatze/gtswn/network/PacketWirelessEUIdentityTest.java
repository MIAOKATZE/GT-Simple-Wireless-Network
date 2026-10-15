package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Field;

import org.junit.Test;

import cpw.mods.fml.common.network.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketWirelessEUIdentityTest {

    private static final String OWNER = "12345678-1234-1234-1234-123456789abc";

    @Test
    public void legacyConstructorsAndLegacyFramesRetainExactOriginalStringPrefix() throws Exception {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketRequestWirelessEU(OWNER).toBytes(buffer);
            PacketRequestWirelessEU request = new PacketRequestWirelessEU();
            request.fromBytes(buffer);
            assertEquals(OWNER, field(request, "ownerUUID"));
            assertEquals(0, request.requestToken);
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            new PacketResponseWirelessEU("98765432109876543210").toBytes(buffer);
            PacketResponseWirelessEU response = new PacketResponseWirelessEU();
            response.fromBytes(buffer);
            assertEquals("98765432109876543210", field(response, "euStr"));
            assertEquals(0, response.requestToken);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void tokenRoundTripsAfterUnchangedOwnerAndBalancePrefixes() throws Exception {
        ByteBuf buffer = Unpooled.buffer();
        try {
            new PacketRequestWirelessEU(OWNER, 9876543210L).toBytes(buffer);
            buffer.markReaderIndex();
            assertEquals(OWNER, ByteBufUtils.readUTF8String(buffer));
            assertEquals(8, buffer.readableBytes());
            buffer.resetReaderIndex();
            PacketRequestWirelessEU request = new PacketRequestWirelessEU();
            request.fromBytes(buffer);
            assertEquals(OWNER, field(request, "ownerUUID"));
            assertEquals(9876543210L, request.requestToken);
            buffer.clear();
            new PacketResponseWirelessEU("12345", request.requestToken).toBytes(buffer);
            PacketResponseWirelessEU response = new PacketResponseWirelessEU();
            response.fromBytes(buffer);
            assertEquals("12345", field(response, "euStr"));
            assertEquals(request.requestToken, response.requestToken);
        } finally {
            buffer.release();
        }
    }

    @Test
    public void malformedTokenTailsCannotBecomeLegacyOrCurrentReplies() throws Exception {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int length = 1; length <= 16; length++) {
                if (length == 8) continue;
                buffer.clear();
                ByteBufUtils.writeUTF8String(buffer, OWNER);
                buffer.writeZero(length);
                PacketRequestWirelessEU request = new PacketRequestWirelessEU();
                request.fromBytes(buffer);
                assertNull(field(request, "ownerUUID"));
                buffer.clear();
                ByteBufUtils.writeUTF8String(buffer, "12345");
                buffer.writeZero(length);
                PacketResponseWirelessEU response = new PacketResponseWirelessEU();
                response.fromBytes(buffer);
                assertNull(field(response, "euStr"));
            }
            for (long token : new long[] { 0, -1, Long.MIN_VALUE }) {
                buffer.clear();
                ByteBufUtils.writeUTF8String(buffer, OWNER);
                buffer.writeLong(token);
                PacketRequestWirelessEU request = new PacketRequestWirelessEU();
                request.fromBytes(buffer);
                assertNull(field(request, "ownerUUID"));
                buffer.clear();
                ByteBufUtils.writeUTF8String(buffer, "12345");
                buffer.writeLong(token);
                PacketResponseWirelessEU response = new PacketResponseWirelessEU();
                response.fromBytes(buffer);
                assertNull(field(response, "euStr"));
            }
        } finally {
            buffer.release();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
