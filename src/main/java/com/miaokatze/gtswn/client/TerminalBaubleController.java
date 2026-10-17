package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.miaokatze.gtswn.client.gui.GuiDeviceInfoTerminal;
import com.miaokatze.gtswn.client.gui.GuiPortableMonitor;
import com.miaokatze.gtswn.client.gui.GuiTerminalWheel;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;
import com.miaokatze.gtswn.network.PacketTerminalBaubleState;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client input and presentation only. Inventory transactions and borrowed-stack validation stay server-side. */
public final class TerminalBaubleController {

    public static final TerminalBaubleController INSTANCE = new TerminalBaubleController();
    private static final KeyBinding KEY = new KeyBinding(
        "key.gtswn.terminal_wheel",
        Keyboard.KEY_C,
        "gtswn.monitor.key.category");
    private EntityPlayer sessionPlayer;
    private Object sessionWorld;
    private int heldSlot = -1;
    private int borrowedCategory = -1;

    private TerminalBaubleController() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(KEY);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
    }

    public static int keyCode() {
        return KEY.getKeyCode();
    }

    public static String keyName() {
        return GameSettings.getKeyDisplayString(keyCode());
    }

    public static boolean bindingDown(int code) {
        return code < 0 ? code + 100 >= 0 && Mouse.isButtonDown(code + 100)
            : code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        press();
    }

    @SubscribeEvent
    public void onMouse(InputEvent.MouseInputEvent event) {
        press();
    }

    private void press() {
        while (KEY.isPressed()) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) continue;
            if (canReturn(mc.thePlayer)) {
                send(PacketTerminalBaubleAction.RETURN, 0);
            } else if (TerminalBaubles.isTerminal(mc.thePlayer.getHeldItem())) {
                send(PacketTerminalBaubleAction.EQUIP, 0);
            } else {
                ClientGUI.open(new GuiTerminalWheel());
            }
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (sessionPlayer != mc.thePlayer || sessionWorld != mc.theWorld) {
            sessionPlayer = mc.thePlayer;
            sessionWorld = mc.theWorld;
            heldSlot = -1;
            borrowedCategory = -1;
        }
    }

    private boolean canReturn(EntityPlayer player) {
        // Tool usage legitimately changes NBT. Only the server can verify the borrowed object itself.
        return heldSlot >= 0 && player == sessionPlayer
            && player.worldObj == sessionWorld
            && player.inventory.currentItem == heldSlot
            && TerminalBaubles.category(player.getHeldItem()) == borrowedCategory;
    }

    public void receive(int action, int selection, int slot, ItemStack stack) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (action == PacketTerminalBaubleState.OPEN_MONITOR && stack != null) {
            ClientGUI.open(new GuiPortableMonitor(stack));
        } else if (action == PacketTerminalBaubleState.OPEN_DEVICE && stack != null) {
            ClientGUI.open(new GuiDeviceInfoTerminal(ItemDeviceInfoTerminal.readTerminalId(stack), stack));
        } else if (action == PacketTerminalBaubleState.EQUIPPED_HINT) {
            mc.thePlayer.addChatMessage(new ChatComponentText(text("gtswn.terminal_wheel.open_hint", keyName())));
        } else if (action == PacketTerminalBaubleState.SESSION) {
            sessionPlayer = mc.thePlayer;
            sessionWorld = mc.theWorld;
            heldSlot = slot;
            borrowedCategory = slot < 0 ? -1 : TerminalBaubles.category(stack);
        }
    }

    public static void select(int selection) {
        send(PacketTerminalBaubleAction.SELECT, selection);
    }

    private static void send(int action, int selection) {
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalBaubleAction(action, selection));
    }

    public static String text(String key, Object... args) {
        return StatCollector.translateToLocalFormatted(key, args);
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || mc.currentScreen != null
            || mc.thePlayer == null
            || mc.gameSettings.hideGUI) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        int category = TerminalBaubles.category(held);
        if (category < 0) return;
        List<String> lines = new ArrayList<>();
        lines.add(held.getDisplayName());
        lines.add(
            text(canReturn(mc.thePlayer) ? "gtswn.terminal_wheel.return" : "gtswn.terminal_wheel.equip", keyName()));
        String use = GameSettings.getKeyDisplayString(mc.gameSettings.keyBindUseItem.getKeyCode());
        String sneak = GameSettings.getKeyDisplayString(mc.gameSettings.keyBindSneak.getKeyCode());
        if (category == 0) {
            lines.add(text("gtswn.terminal_controls.monitor.toggle", use));
            lines.add(text("gtswn.terminal_controls.monitor.open", sneak, use));
            lines.add(text("gtswn.terminal_controls.monitor.bind", use, use));
        } else if (category == 1) {
            lines.add(text("gtswn.terminal_controls.link.bind", use));
            lines.add(text("gtswn.terminal_controls.link.mode", sneak, use));
            lines.add(text("gtswn.terminal_controls.link.open", use));
            lines.add(text("gtswn.terminal_controls.link.reveal", sneak, use));
        } else if (category == 2) {
            lines.add(text("gtswn.terminal_controls.device.open", use, sneak, use));
            lines.add(text("gtswn.terminal_controls.device.bind", use, sneak, use));
            lines.add(text("gtswn.terminal_controls.device.scan", use, sneak, use));
            lines.add(text("gtswn.terminal_controls.device.travel", use));
        } else {
            lines.add(text("gtswn.terminal_controls.quantum.bind", use, sneak, use));
            lines.add(text("gtswn.terminal_controls.quantum.node", use, sneak, use));
            lines.add(text("gtswn.terminal_controls.quantum.open", sneak, use));
            lines.add(text("gtswn.terminal_controls.quantum.incorporate", use, use));
            lines.add(text("gtswn.terminal_controls.quantum.reveal", sneak, use));
        }
        drawHud(mc, lines);
    }

    private static void drawHud(Minecraft mc, List<String> lines) {
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        FontRenderer font = mc.fontRenderer;
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, font.getStringWidth(line));
        float scale = Math.min(1, (resolution.getScaledWidth() - 20f) / (widest + 20));
        scale = Math.min(scale, Math.max(20, resolution.getScaledHeight() / 3f) / (lines.size() * 11 + 12));
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(resolution.getScaledWidth() / 2f, 6, 0);
            GL11.glScalef(scale, scale, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            int half = (widest + 16) / 2;
            Gui.drawRect(-half, 0, half, lines.size() * 11 + 10, 0xB510171F);
            Gui.drawRect(-half, 0, half, 1, GtswnMuiTheme.TEXT_ACCENT);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                font.drawStringWithShadow(
                    line,
                    -font.getStringWidth(line) / 2,
                    6 + i * 11,
                    i == 0 ? GtswnMuiTheme.TEXT_TITLE : i == 1 ? GtswnMuiTheme.TEXT_ACCENT : GtswnMuiTheme.TEXT_BODY);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
