package com.miaokatze.gtswn.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketDeviceLinkAction;
import com.miaokatze.gtswn.network.PacketSyncDeviceLinkResult;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.gtswn.network.PacketSyncTerminalLinkSettings;

/** Session-scoped receipts and server preference; no optimistic node state or secondary device cache. */
public final class TerminalLinkClientState {

    private static Object world, player, connection;
    private static boolean auto;
    private static boolean pending;
    private static long sequence = System.currentTimeMillis() << 10;
    private static final Map<String, PacketSyncDeviceLinkResult> RESULTS = new LinkedHashMap<>();

    private TerminalLinkClientState() {}

    private static void session() {
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld || player != mc.thePlayer || connection != mc.getNetHandler()) {
            RESULTS.clear();
            auto = false;
            pending = false;
            world = mc.theWorld;
            player = mc.thePlayer;
            connection = mc.getNetHandler();
        }
    }

    public static boolean autoEnabled() {
        session();
        return auto;
    }

    public static void receive(PacketSyncTerminalLinkSettings message) {
        session();
        auto = message.enabled;
    }

    public static void receive(PacketSyncDeviceLinkResult message) {
        session();
        String key = message.terminalId + ":" + message.key;
        PacketSyncDeviceLinkResult prior = RESULTS.get(key);
        if (prior == null || prior.requestId != message.requestId || !prior.pending) return;
        RESULTS.put(key, message);
        pending = message.pending;
        if (!message.pending && Minecraft.getMinecraft().thePlayer != null)
            Minecraft.getMinecraft().thePlayer.addChatMessage(
                new net.minecraft.util.ChatComponentTranslation(
                    "gtswn.device.link.result",
                    message.success,
                    message.skipped,
                    message.failed));
    }

    public static PacketSyncDeviceLinkResult result(UUID terminal, Entry entry) {
        session();
        return RESULTS.get(terminal + ":" + entry.key);
    }

    public static boolean pending() {
        session();
        return pending;
    }

    public static void request(UUID terminal, Entry entry, boolean desired) {
        if (pending()) return;
        PacketDeviceLinkAction request = new PacketDeviceLinkAction(
            terminal,
            entry.key,
            entry.metaTileId,
            ++sequence,
            desired);
        if (RESULTS.size() >= 128) RESULTS.remove(
            RESULTS.keySet()
                .iterator()
                .next());
        RESULTS.put(terminal + ":" + entry.key, new PacketSyncDeviceLinkResult(request, true, 0, 0, 0));
        pending = true;
        GTSWNPacketHandler.NETWORK.sendToServer(request);
    }
}
