package com.miaokatze.gtswn.client.testworld;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Test;

import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalDelta;

import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.SimpleChannelHandlerWrapper;
import cpw.mods.fml.common.network.simpleimpl.SimpleIndexedCodec;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;

public class DevicePagedObserverPipelineTest {

    @Test
    public void actualFmlDecodeIsObservedBeforeRenamedConsumingDispatcherWithoutChangingMessage() {
        SimpleIndexedCodec codec = new SimpleIndexedCodec();
        codec.addDiscriminator(40, PacketSyncDeviceTerminalDelta.class);
        EmbeddedChannel channel = new EmbeddedChannel(codec);
        List<String> order = new ArrayList<>();
        List<Object> received = new ArrayList<>();
        channel.pipeline()
            .addAfter(
                channel.pipeline()
                    .context(codec)
                    .name(),
                "runtime-generated-dispatcher",
                new SimpleChannelHandlerWrapper<PacketSyncDeviceTerminalDelta, IMessage>((message, context) -> {
                    order.add("dispatch");
                    received.add(message);
                    return null;
                }, Side.CLIENT, PacketSyncDeviceTerminalDelta.class));
        try {
            assertNull(
                channel.pipeline()
                    .get(PacketSyncDeviceTerminalDelta.Handler.class.getName()));
            String anchor = DevicePagedClientCheck
                .attachDecodedObserver(channel.pipeline(), "observer", new ChannelInboundHandlerAdapter() {

                    @Override
                    public void channelRead(ChannelHandlerContext context, Object message) {
                        assertTrue(message instanceof PacketSyncDeviceTerminalDelta);
                        order.add("observe");
                        received.add(message);
                        context.fireChannelRead(message);
                    }
                });
            assertSame(
                codec,
                channel.pipeline()
                    .get(anchor));
            PacketSyncDeviceTerminalDelta sent = new PacketSyncDeviceTerminalDelta(
                UUID.randomUUID(),
                UUID.randomUUID(),
                7,
                -1,
                3,
                PacketSyncDeviceTerminalDelta.FULL,
                8,
                9,
                1025,
                new byte[] { 1, 0, 0, 0, 0 },
                2);
            ByteBuf bytes = Unpooled.buffer();
            bytes.writeByte(40);
            sent.toBytes(bytes);
            assertFalse(channel.writeInbound(new FMLProxyPacket(bytes, "gtswn")));
            assertEquals(Arrays.asList("observe", "dispatch"), order);
            assertEquals(2, received.size());
            assertSame(received.get(0), received.get(1));
            PacketSyncDeviceTerminalDelta decoded = (PacketSyncDeviceTerminalDelta) received.get(0);
            assertNotSame(sent, decoded);
            assertEquals(sent.getTerminalId(), decoded.getTerminalId());
            assertEquals(2, decoded.getWireVersion());
            assertEquals(1025, decoded.getEntryTotal());
            assertEquals(8, decoded.getPageIndex());
            assertEquals(9, decoded.getPageTotal());
            assertArrayEquals(sent.getPayload(), decoded.getPayload());
            channel.pipeline()
                .remove("observer");
            assertNull(
                channel.pipeline()
                    .get("observer"));
        } finally {
            channel.finish();
        }
        EmbeddedChannel missing = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
        try {
            try {
                DevicePagedClientCheck
                    .attachDecodedObserver(missing.pipeline(), "observer", new ChannelInboundHandlerAdapter());
                fail("Missing decoder must fail instead of observing raw packets");
            } catch (IllegalStateException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("decoder unavailable"));
                assertNull(
                    missing.pipeline()
                        .get("observer"));
            }
        } finally {
            missing.finish();
        }
    }
}
