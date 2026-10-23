package com.miaokatze.gtswn.common.engineering;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.junit.Test;

import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.Source;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources.SourceOrigin;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.PacketEngineeringDeviceAction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class PacketEngineeringDeviceActionTest {

    private static ItemStack device() {
        ItemStack stack = new ItemStack(new ItemDeviceInfoTerminal());
        ItemDeviceInfoTerminal.getOrCreateTerminalId(stack);
        return stack;
    }

    @Test
    public void fixedWireActionsRetainOnlyExpectedOriginAndBinding() {
        for (byte action : new byte[] { PacketEngineeringDeviceAction.SCAN,
            PacketEngineeringDeviceAction.TOGGLE_VISUALIZATION }) {
            Source source = new Source(device(), SourceOrigin.INVENTORY);
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PacketEngineeringDeviceAction(action, source).toBytes(bytes);
                assertEquals(18, bytes.readableBytes());
                assertEquals(action, bytes.getByte(0));
                PacketEngineeringDeviceAction decoded = new PacketEngineeringDeviceAction();
                decoded.fromBytes(bytes);
                assertTrue(decoded.valid());
                assertTrue(decoded.matches(source));
                assertFalse(decoded.matches(new Source(source.stack, SourceOrigin.BAUBLE)));
                assertFalse(decoded.matches(new Source(device(), SourceOrigin.INVENTORY)));
                assertFalse(decoded.matches(Source.EMPTY));
                source.stack.stackTagCompound.setString(
                    ItemDeviceInfoTerminal.NBT_UUID,
                    UUID.randomUUID()
                        .toString());
                assertFalse(decoded.matches(new Source(source.stack, SourceOrigin.INVENTORY)));
            } finally {
                bytes.release();
            }
        }
    }

    @Test
    public void aNewAccessoryProviderInvalidatesQueuedInventoryIntentEvenWithSameBinding() {
        ItemStack stack = device();
        Source expected = EngineeringTerminalSources.resolve(new ItemStack[8], new ItemStack[] { stack }, 2);
        PacketEngineeringDeviceAction request = new PacketEngineeringDeviceAction(
            PacketEngineeringDeviceAction.SCAN,
            expected);
        ItemStack[] accessories = new ItemStack[8];
        accessories[2] = stack.copy();
        assertFalse(request.matches(EngineeringTerminalSources.resolve(accessories, new ItemStack[] { stack }, 2)));
        accessories[2] = new ItemStack(new ItemDeviceInfoTerminal());
        assertFalse(request.matches(EngineeringTerminalSources.resolve(accessories, new ItemStack[] { stack }, 2)));
    }

    @Test
    public void malformedLengthActionOriginAndZeroIdentityNeverBecomeRequests() {
        Source source = new Source(device(), SourceOrigin.BAUBLE);
        for (int length : new int[] { 0, 17, 19, 1024 }) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                bytes.writeZero(length);
                PacketEngineeringDeviceAction decoded = new PacketEngineeringDeviceAction();
                decoded.fromBytes(bytes);
                assertFalse(decoded.valid());
            } finally {
                bytes.release();
            }
        }
        for (int corruption = 0; corruption < 4; corruption++) {
            ByteBuf bytes = Unpooled.buffer();
            try {
                new PacketEngineeringDeviceAction(PacketEngineeringDeviceAction.SCAN, source).toBytes(bytes);
                if (corruption == 0) bytes.setByte(0, 2);
                else if (corruption == 1) bytes.setByte(1, 255);
                else if (corruption == 2) bytes.setByte(1, SourceOrigin.NONE.ordinal());
                else bytes.setZero(2, 16);
                PacketEngineeringDeviceAction decoded = new PacketEngineeringDeviceAction();
                decoded.fromBytes(bytes);
                assertFalse(decoded.valid());
            } finally {
                bytes.release();
            }
        }
    }
}
