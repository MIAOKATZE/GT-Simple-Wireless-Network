package com.miaokatze.gtswn.network;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PacketSyncTerminalLinkSettings implements IMessage {

    public boolean valid, enabled;

    public PacketSyncTerminalLinkSettings() {}

    public PacketSyncTerminalLinkSettings(boolean enabled) {
        this.valid = true;
        this.enabled = enabled;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(enabled ? 1 : 0);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        valid = buf.readableBytes() == 1;
        if (!valid) return;
        int value = buf.readUnsignedByte();
        valid = value <= 1;
        enabled = value == 1;
    }

    public static final class Handler implements IMessageHandler<PacketSyncTerminalLinkSettings, IMessage> {

        @Override
        public IMessage onMessage(PacketSyncTerminalLinkSettings message, MessageContext context) {
            if (context.side.isClient() && message.valid) Client.receive(message);
            return null;
        }
    }

    @cpw.mods.fml.relauncher.SideOnly(cpw.mods.fml.relauncher.Side.CLIENT)
    private static final class Client {

        static void receive(PacketSyncTerminalLinkSettings message) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            Object connection = mc.getNetHandler(), player = mc.thePlayer, world = mc.theWorld;
            mc.func_152344_a(() -> {
                if (connection != null && connection == mc.getNetHandler()
                    && player == mc.thePlayer
                    && world == mc.theWorld) com.miaokatze.gtswn.client.TerminalLinkClientState.receive(message);
            });
        }
    }

}
