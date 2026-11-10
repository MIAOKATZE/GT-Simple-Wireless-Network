package com.miaokatze.gtswn.common.testworld;

import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

/** Outbound acknowledgments for synthetic test actors; no channel, socket, login or network registration. */
final class SilentActorConnection extends NetHandlerPlayServer {

    SilentActorConnection(FakePlayer actor) {
        // Construction only binds the handler. No channel is opened or event-loop work submitted.
        super(MinecraftServer.getServer(), new NetworkManager(false), actor);
    }

    @Override
    public void sendPacket(Packet packet) {
        // A FakePlayer has no client. Keep normal server interactions and discard only their outbound packets.
    }
}
