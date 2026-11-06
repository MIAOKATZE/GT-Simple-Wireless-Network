package com.miaokatze.gtswn.network;

import static org.junit.Assert.*;

import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketTerminalBaubleActionTest {

    @Test
    public void nonSelectionActionsPermitUnspecifiedSelection() {
        for (int action : new int[] { PacketTerminalBaubleAction.EQUIP, PacketTerminalBaubleAction.RETURN }) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PacketTerminalBaubleAction(action, -1).toBytes(bytes);
                PacketTerminalBaubleAction decoded = new PacketTerminalBaubleAction();
                decoded.fromBytes(bytes);
                assertEquals(action, decoded.action);
                assertEquals(-1, decoded.selection);
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void invalidSelectionAndMalformedPacketCannotBecomeAction() {
        for (int selection : new int[] { -1, 8, 127 }) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PacketTerminalBaubleAction(PacketTerminalBaubleAction.SELECT, selection).toBytes(bytes);
                PacketTerminalBaubleAction decoded = new PacketTerminalBaubleAction();
                decoded.fromBytes(bytes);
                assertEquals(-1, decoded.action);
            } finally {
                bytes.release();
            }
        }
        ByteBuf bytes = Unpooled.buffer();
        try {
            bytes.writeByte(0);
            PacketTerminalBaubleAction decoded = new PacketTerminalBaubleAction();
            decoded.fromBytes(bytes);
            assertEquals(-1, decoded.action);
        } finally {
            bytes.release();
        }
    }

    @Test
    public void selectAndForcedTakeRoundTripEverySectorAndRejectMissingSector() {
        for (int action : new int[] { PacketTerminalBaubleAction.SELECT, PacketTerminalBaubleAction.TAKE,
            PacketTerminalBaubleAction.WHEEL_OPEN, PacketTerminalBaubleAction.WHEEL_OUTER_FIRST,
            PacketTerminalBaubleAction.WHEEL_OUTER_SECOND, PacketTerminalBaubleAction.WHEEL_INNER,
            PacketTerminalBaubleAction.WHEEL_BEGIN_CHARGE, PacketTerminalBaubleAction.WHEEL_CANCEL_CHARGE }) {
            for (int sector = 0; sector < 8; sector++) {
                ByteBuf bytes = Unpooled.buffer();
                try {
                    new PacketTerminalBaubleAction(action, sector).toBytes(bytes);
                    assertEquals(2, bytes.readableBytes());
                    PacketTerminalBaubleAction decoded = new PacketTerminalBaubleAction();
                    decoded.fromBytes(bytes);
                    assertEquals(action, decoded.action);
                    assertEquals(sector, decoded.selection);
                    assertTrue(PacketTerminalBaubleAction.isValid(action, sector));
                } finally {
                    bytes.release();
                }
            }
            for (int invalid : new int[] { -1, 8, 127 }) {
                ByteBuf bytes = Unpooled.buffer();
                try {
                    new PacketTerminalBaubleAction(action, invalid).toBytes(bytes);
                    PacketTerminalBaubleAction decoded = new PacketTerminalBaubleAction();
                    decoded.fromBytes(bytes);
                    assertEquals(-1, decoded.action);
                    assertFalse(PacketTerminalBaubleAction.isValid(action, invalid));
                } finally {
                    bytes.release();
                }
            }
        }
        assertFalse(PacketTerminalBaubleAction.isValid(10, 0));
        assertFalse(PacketTerminalBaubleAction.isValid(-1, 0));
    }
}
