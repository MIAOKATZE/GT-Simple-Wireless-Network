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
}
