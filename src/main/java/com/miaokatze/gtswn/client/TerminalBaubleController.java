package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
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
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketTerminalBaubleAction;
import com.miaokatze.gtswn.network.PacketTerminalBaubleState;

import appeng.tile.networking.TileController;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Client input and presentation only. Inventory transactions and borrowed-stack validation stay server-side. */
@SideOnly(Side.CLIENT)
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
        return TerminalKeyNames.name(keyCode());
    }

    public static boolean bindingDown(int code) {
        return TerminalKeyNames.isDown(code);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void guiKeyRelease(com.cleanroommc.modularui.api.event.KeyboardInputEvent.Pre event) {
        if (!Keyboard.getEventKeyState()) TerminalWheelInput.released(Keyboard.getEventKey());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public void guiMouseRelease(com.cleanroommc.modularui.api.event.MouseInputEvent.Pre event) {
        if (Mouse.getEventButton() >= 0 && !Mouse.getEventButtonState())
            TerminalWheelInput.released(Mouse.getEventButton() - 100);
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
        TerminalWheelInput.poll();
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

    public static void take(int selection) {
        send(PacketTerminalBaubleAction.TAKE, selection);
    }

    private static void send(int action, int selection) {
        GTSWNPacketHandler.NETWORK.sendToServer(new PacketTerminalBaubleAction(action, selection));
    }

    public static String text(String key, Object... args) {
        return StatCollector.translateToLocalFormatted(key, args);
    }

    public static String action(String key, String action) {
        return "\u00a7e" + key + " \u00a7b" + text("gtswn.terminal_action." + action);
    }

    public static String row(String... actions) {
        return String.join(" \u00a77\u00b7 ", actions);
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || mc.currentScreen != null
            || mc.thePlayer == null
            || mc.theWorld == null
            || mc.gameSettings.hideGUI) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        int category = TerminalBaubles.category(held);
        if (category < 0) return;
        List<String> lines = new ArrayList<>();
        String use = TerminalKeyNames.name(mc.gameSettings.keyBindUseItem.getKeyCode());
        String sneak = TerminalKeyNames.name(mc.gameSettings.keyBindSneak.getKeyCode());
        String shifted = sneak + "+" + use;
        String altUse = "Alt+" + use;
        String altShifted = "Alt+" + shifted;
        boolean shift = bindingDown(mc.gameSettings.keyBindSneak.getKeyCode());
        boolean alt = bindingDown(Keyboard.KEY_LMENU) || bindingDown(Keyboard.KEY_RMENU);
        boolean block = mc.objectMouseOver != null
            && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
        TileEntity target = block
            ? mc.theWorld.getTileEntity(mc.objectMouseOver.blockX, mc.objectMouseOver.blockY, mc.objectMouseOver.blockZ)
            : null;
        if (category == 0) {
            boolean bound = PortableWirelessNetworkMonitor.isMonitorBound(held);
            boolean own = bound && mc.thePlayer.getUniqueID()
                .toString()
                .equals(PortableWirelessNetworkMonitor.getOwnerUUID(held));
            String plainAction = target instanceof TileEntityNetworkInfoPanel ? "bind_screen"
                : bound ? "toggle_hud" : "monitor_bind";
            String shiftAction = own ? "monitor_open" : "monitor_rebind";
            lines.add(
                shift ? row(action(shifted, shiftAction), action(use, plainAction))
                    : row(action(use, plainAction), action(shifted, shiftAction)));
        } else if (category == 1) {
            if (alt) {
                lines.add(
                    shift ? row(action(altShifted, "reveal"), action(altUse, "link_open"))
                        : row(action(altUse, "link_open"), action(altShifted, "reveal")));
            } else {
                String plain = action(block ? use : altUse, block ? "link_config" : "link_open");
                lines.add(shift ? row(action(shifted, "link_mode"), plain) : row(plain, action(shifted, "link_mode")));
                lines.add(
                    block ? row(action(altUse, "link_open"), action(altShifted, "reveal"))
                        : action(altShifted, "reveal"));
            }
        } else if (category == 2) {
            boolean visual = DeviceTerminalVisualizationState.isActiveHeld();
            boolean machine = target instanceof IGregTechTileEntity;
            if (alt) {
                lines.add(
                    shift ? row(action(altShifted, "device_visual"), action(altUse, "device_scan"))
                        : row(action(altUse, "device_scan"), action(altShifted, "device_visual")));
            } else {
                String plainAction = visual && DeviceTerminalAnchorRenderer.INSTANCE.hasCurrentTarget()
                    ? "device_travel"
                    : machine && !visual ? "device_bind" : "device_open";
                String shiftAction = machine ? "device_track" : "device_settings";
                lines.add(
                    shift ? row(action(shifted, shiftAction), action(use, plainAction))
                        : row(action(use, plainAction), action(shifted, shiftAction)));
                lines.add(row(action(altUse, "device_scan"), action(altShifted, "device_visual")));
            }
        } else {
            if (alt) {
                String altAction = target instanceof TileController ? "quantum_migrate" : "quantum_incorporate";
                lines.add(
                    shift || !block ? row(action(altShifted, "reveal"), action(shifted, "quantum_open"))
                        : row(action(altUse, altAction), action(shifted, "quantum_open")));
            } else if (!block) {
                lines.add(row(action(shifted, "quantum_open"), action(altShifted, "reveal")));
            } else {
                String plainAction = target instanceof TileController ? "quantum_bind" : "quantum_node";
                String shiftAction = target instanceof TileEntityNetworkQuantumNode ? "quantum_pickup"
                    : target instanceof TileController ? "quantum_remove" : "quantum_open";
                lines.add(
                    shift ? row(action(shifted, shiftAction), action(use, plainAction))
                        : row(action(use, plainAction), action(shifted, shiftAction)));
                lines.add(row(action(altUse, "quantum_incorporate"), action(altShifted, "reveal")));
            }
        }
        lines.add(
            text(
                "gtswn.terminal_wheel.press",
                "\u00a7e" + keyName(),
                "\u00a7b"
                    + text(canReturn(mc.thePlayer) ? "gtswn.terminal_action.return" : "gtswn.terminal_action.equip")));
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
            GL11.glTranslatef(resolution.getScaledWidth() / 2f, 8, 0);
            GL11.glScalef(scale, scale, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            int half = (widest + 16) / 2;
            Gui.drawRect(-half, 0, half, lines.size() * 11 + 6, 0x90101010);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                font.drawStringWithShadow(line, -font.getStringWidth(line) / 2, 4 + i * 11, GtswnMuiTheme.TEXT_BODY);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
