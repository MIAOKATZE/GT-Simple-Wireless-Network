package com.miaokatze.gtswn.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketLinkNodeInstalledTest {

    @Test
    public void bothTypesAndAllFacesRoundTripWithDimensionAndEventIdentity() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int side = 0; side < 6; side++) {
                for (boolean energy : new boolean[] { false, true }) {
                    buffer.clear();
                    new PacketLinkNodeInstalled(-1, -17, 42, 30, side, energy, 9876).toBytes(buffer);
                    assertEquals(26, buffer.readableBytes());
                    PacketLinkNodeInstalled packet = new PacketLinkNodeInstalled();
                    packet.fromBytes(buffer);
                    assertTrue(packet.valid);
                    assertEquals(-1, packet.dimension);
                    assertEquals(-17, packet.x);
                    assertEquals(42, packet.y);
                    assertEquals(30, packet.z);
                    assertEquals(side, packet.side);
                    assertEquals(energy, packet.energy);
                    assertEquals(9876, packet.nonce);
                }
            }
        } finally {
            buffer.release();
        }
    }

    @Test
    public void truncatedAndInvalidFieldsCannotTriggerAnAnimation() {
        ByteBuf buffer = Unpooled.buffer();
        try {
            for (int length = 0; length < 26; length++) {
                buffer.clear();
                buffer.writeZero(length);
                PacketLinkNodeInstalled packet = new PacketLinkNodeInstalled();
                packet.fromBytes(buffer);
                assertFalse(packet.valid);
            }
            for (int[] invalid : new int[][] { { -1, 0, 1 }, { 256, 0, 1 }, { 42, 6, 1 }, { 42, 0, 0 } }) {
                buffer.clear();
                new PacketLinkNodeInstalled(0, 1, invalid[0], 2, invalid[1], true, invalid[2]).toBytes(buffer);
                PacketLinkNodeInstalled packet = new PacketLinkNodeInstalled();
                packet.fromBytes(buffer);
                assertFalse(packet.valid);
            }
            buffer.clear();
            new PacketLinkNodeInstalled(0, 1, 42, 2, 0, true, 1).toBytes(buffer);
            buffer.setByte(17, 2);
            PacketLinkNodeInstalled packet = new PacketLinkNodeInstalled();
            packet.fromBytes(buffer);
            assertFalse(packet.valid);
        } finally {
            buffer.release();
        }
    }
}
