package com.miaokatze.gtswn.network.telemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketMonitorCapabilities;
import com.miaokatze.gtswn.network.PacketRequestWirelessEU;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Negotiation uses a legacy, safely rejected non-UUID request; new IDs are never probes. */
public final class MonitorCapabilities {

    public static final String PROBE = "gtswn:monitor-capabilities:1";
    public static final int PORTABLE = 1, DIT = 2, ENGINEERING = 4, QUANTUM = 8, PANELS = 16, PROFILE = 32,
        PROFILES = PROFILE, EU_SOURCE = 64, ALL = 127;
    private static final Map<EntityPlayerMP, Long> PENDING = new ConcurrentHashMap<>();
    private static final Map<EntityPlayerMP, Object> CONNECTIONS = new ConcurrentHashMap<>();
    private static volatile int enabled;

    public static void configure(int features) {
        enabled = features & ALL;
    }

    public static void configureFeatures(int features) {
        configure(features);
    }

    public static void enqueue(EntityPlayerMP player, long nonce) {
        if (player != null && nonce > 0 && PENDING.size() < 1024) PENDING.put(player, nonce);
    }

    public static boolean serverSupports(EntityPlayerMP player, int feature) {
        return player != null && player.playerNetServerHandler != null
            && CONNECTIONS.get(player) == player.playerNetServerHandler.netManager
            && (enabled & feature) == feature;
    }

    @SideOnly(Side.CLIENT)
    public static boolean clientSupports(int feature) {
        return Client.supports(feature);
    }

    @SideOnly(Side.CLIENT)
    public static void receive(long nonce, int bits) {
        Client.receive(nonce, bits);
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Map.Entry<EntityPlayerMP, Long> entry : PENDING.entrySet()) {
            if (!PENDING.remove(entry.getKey(), entry.getValue())) continue;
            EntityPlayerMP player = entry.getKey();
            if (!connected(player)) continue;
            CONNECTIONS.put(player, player.playerNetServerHandler.netManager);
            TelemetryTransport.control(player, new PacketMonitorCapabilities(entry.getValue(), enabled));
        }
    }

    @SubscribeEvent
    @SideOnly(Side.CLIENT)
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) Client.tick();
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.player);
        CONNECTIONS.remove(event.player);
    }

    public static boolean connected(EntityPlayerMP player) {
        return player != null && player.playerNetServerHandler != null
            && player.playerNetServerHandler.netManager.isChannelOpen();
    }

    public static void clear() {
        PENDING.clear();
        CONNECTIONS.clear();
    }

    @SideOnly(Side.CLIENT)
    private static final class Client {

        private static Object connection;
        private static long nonce;
        private static int bits, attempts, ticks;

        static boolean supports(int feature) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            return minecraft != null && connection != null
                && connection == minecraft.getNetHandler()
                && (bits & feature) == feature;
        }

        static void receive(long echo, int features) {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
            if (minecraft != null && connection != null
                && connection == minecraft.getNetHandler()
                && echo == nonce
                && nonce > 0) bits = features & ALL;
        }

        static void tick() {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null) return;
            Object current = mc.getNetHandler();
            if (connection != current) {
                connection = current;
                bits = attempts = ticks = 0;
                nonce = (System.nanoTime() & Long.MAX_VALUE) | 1L;
            }
            if (current == null || mc.thePlayer == null || bits != 0 || attempts >= 3) return;
            if (ticks++ % 40 == 0) {
                attempts++;
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestWirelessEU(PROBE, nonce));
            }
        }
    }
}
