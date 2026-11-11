// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.testworld;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** One-use OP2 receipt over the existing server command/chat connection, before any client test action. */
public final class ClientTestPermission {

    private static final ClientTestPermission INSTANCE = new ClientTestPermission();
    private static boolean registered;
    private String token;
    private String[] arguments;
    private Object connection;
    private int remaining;
    private boolean accepted;
    private boolean dispatching;
    private Boolean actorLevel2, actorLevel4;

    private ClientTestPermission() {}

    static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    static void request(ICommandSender sender, String[] args) {
        register();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.getNetHandler() == null)
            throw new IllegalStateException("An actual OP2 server connection is required");
        if (INSTANCE.token != null) throw new IllegalStateException("Waiting for server OP2 authorization");
        INSTANCE.token = UUID.randomUUID()
            .toString();
        INSTANCE.arguments = args.clone();
        INSTANCE.connection = mc.getNetHandler();
        INSTANCE.remaining = 100;
        INSTANCE.accepted = false;
        INSTANCE.actorLevel2 = INSTANCE.actorLevel4 = null;
        mc.thePlayer.sendChatMessage("/gtswn test client " + INSTANCE.token);
        sender.addChatMessage(new ChatComponentText("[GTSWN-UI] Waiting for server OP2 authorization."));
    }

    @SubscribeEvent
    public void chat(ClientChatReceivedEvent event) {
        String text = event.message.getUnformattedText();
        String prefix = "[GTSWN-TEST-AUTH] " + token + " actorLevel2=true actorLevel4=";
        if (token != null && (text.equals(prefix + "true") || text.equals(prefix + "false"))) {
            actorLevel2 = Boolean.TRUE;
            actorLevel4 = Boolean.valueOf(text.substring(prefix.length()));
            accepted = true;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || token == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || connection != mc.getNetHandler()) {
            clear();
            return;
        }
        if (accepted) {
            String[] ready = arguments;
            clear();
            dispatching = true;
            try {
                new CommandUiDebug().processAuthorized(mc.thePlayer, ready);
            } catch (RuntimeException failure) {
                mc.thePlayer.addChatMessage(new ChatComponentText("[GTSWN-UI] " + failure.getMessage()));
            } finally {
                dispatching = false;
            }
        } else if (--remaining <= 0) {
            clear();
            mc.thePlayer.addChatMessage(
                new ChatComponentText(
                    "[GTSWN-UI] Server OP2 authorization was not received; no test action was started. Enable matching testlab variants on both ends and use an OP2 actor."));
        }
    }

    static Boolean actorLevel2() {
        return INSTANCE.actorLevel2;
    }

    static Boolean actorLevel4() {
        return INSTANCE.actorLevel4;
    }

    static boolean pending() {
        return INSTANCE.token != null;
    }

    static void requireReceipt() {
        if (!INSTANCE.dispatching)
            throw new IllegalStateException("Request this test through /gtswnui and wait for the server OP2 receipt");
    }

    private void clear() {
        token = null;
        arguments = null;
        connection = null;
        accepted = false;
    }
}
