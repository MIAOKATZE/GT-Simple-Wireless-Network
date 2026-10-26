package com.miaokatze.gtswn.network;

import com.miaokatze.gtswn.network.telemetry.MonitorCapabilities;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.netty.buffer.ByteBuf;

/** ID36, only sent in response to the reserved legacy request with an echoed nonce. */
public final class PacketMonitorCapabilities implements IMessage {

    public long nonce;
    public int bits;

    public PacketMonitorCapabilities() {}

    public PacketMonitorCapabilities(long nonce, int bits) {
        this.nonce = nonce;
        this.bits = bits;
    }

    public void toBytes(ByteBuf out) {
        out.writeLong(nonce);
        out.writeInt(bits);
    }

    public void fromBytes(ByteBuf in) {
        nonce = 0;
        bits = 0;
        if (in.readableBytes() != 12) return;
        long value = in.readLong();
        int features = in.readInt();
        if (value <= 0 || (features & ~MonitorCapabilities.ALL) != 0) return;
        nonce = value;
        bits = features;
    }

    public static final class Handler implements IMessageHandler<PacketMonitorCapabilities, IMessage> {

        public IMessage onMessage(PacketMonitorCapabilities message, MessageContext context) {
            if (context.side.isClient() && message.nonce > 0) Client.receive(message);
            return null;
        }
    }

    @SideOnly(Side.CLIENT)
    private static final class Client {

        static void receive(PacketMonitorCapabilities message) {
            net.minecraft.client.Minecraft.getMinecraft()
                .func_152344_a(() -> MonitorCapabilities.receive(message.nonce, message.bits));
        }
    }
}
