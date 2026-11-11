package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Constructor;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.util.FakePlayer;

import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/** Scoped synthetic current connection for server callback tests. No socket, login, TCP or permission override. */
final class NativePacketCallbackSession implements AutoCloseable {

    private final EntityPlayerMP actor;
    private final NetHandlerPlayServer original;
    private final EmbeddedChannel channel;
    private boolean closed;

    NativePacketCallbackSession(TestContext ctx, EntityPlayerMP actor) {
        TestWorldFixtures.requireTestWorld(ctx.world());
        if (!(actor instanceof FakePlayer) || actor.worldObj != ctx.world()
            || !MinecraftServer.getServer()
                .getConfigurationManager().playerEntityList.isEmpty())
            throw new IllegalStateException(
                "Synthetic callback session requires a FakePlayer, the isolated world and an otherwise empty server");
        this.actor = actor;
        original = actor.playerNetServerHandler;
        NetworkManager connection = new NetworkManager(false);
        actor.playerNetServerHandler = new NetHandlerPlayServer(MinecraftServer.getServer(), connection, actor);
        channel = new EmbeddedChannel(connection);
        connection.setConnectionState(EnumConnectionState.PLAY);
        MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.add(actor);
        ctx.event(
            "callback_session",
            "Exact synthetic actor=" + actor.getUniqueID()
                + "; EmbeddedChannel/current handler/owned player-list enrollment; no TCP or login");
    }

    MessageContext context() throws ReflectiveOperationException {
        if (closed) throw new IllegalStateException("Synthetic callback session is closed");
        Constructor<MessageContext> constructor = MessageContext.class
            .getDeclaredConstructor(INetHandler.class, Side.class);
        constructor.setAccessible(true);
        return constructor.newInstance(actor.playerNetServerHandler, Side.SERVER);
    }

    @Override
    public void close() {
        if (closed) return;
        channel.close(); // Retire the current session before restoring the owned actor-list entry.
        Object packet;
        while ((packet = channel.readOutbound()) != null) ReferenceCountUtil.release(packet);
        MinecraftServer.getServer()
            .getConfigurationManager().playerEntityList.removeIf(candidate -> candidate == actor);
        actor.playerNetServerHandler = original;
        closed = true;
    }
}
