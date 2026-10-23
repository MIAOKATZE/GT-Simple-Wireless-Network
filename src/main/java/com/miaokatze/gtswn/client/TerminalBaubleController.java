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
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.miaokatze.gtswn.client.engineering.FactoryEntryHint;
import com.miaokatze.gtswn.client.engineering.FactoryEntryHintLayout;
import com.miaokatze.gtswn.client.gui.GuiDeviceInfoTerminal;
import com.miaokatze.gtswn.client.gui.GuiPortableMonitor;
import com.miaokatze.gtswn.client.gui.GuiTerminalWheel;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.device.DeviceMachineTypes;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.items.ItemDeviceInfoTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.items.PortableWirelessNetworkMonitor;
import com.miaokatze.gtswn.common.items.WirelessEnergyTap;
import com.miaokatze.gtswn.common.quantum.QuantumBusTarget;
import com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.common.util.LaserHatchUtil;
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
import gregtech.api.interfaces.tileentity.IBasicEnergyContainer;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.util.GTUtility;

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
        FactoryEntryHint.tick(mc);
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
        if (com.miaokatze.gtswn.client.engineering.EngineeringModeController.isActive()) return;
        ItemStack held = mc.thePlayer.getHeldItem();
        int category = TerminalBaubles.category(held);
        if (category < 0) {
            FactoryEntryHint.draw(mc, null);
            return;
        }
        HudContext context = new HudContext(mc, held);
        List<String> controls = new ArrayList<>();
        if (category == 0) {
            monitorHints(context, controls);
        } else if (category == 1) {
            linkHints(context, controls);
        } else if (category == 2) {
            deviceHints(context, controls);
        } else {
            quantumHints(context, controls);
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(4, controls.size()); i += 2) {
            lines.add(i + 1 < controls.size() ? row(controls.get(i), controls.get(i + 1)) : controls.get(i));
        }
        lines.add(
            text(
                "gtswn.terminal_wheel.press",
                "\u00a7e" + keyName(),
                "\u00a7b"
                    + text(canReturn(mc.thePlayer) ? "gtswn.terminal_action.return" : "gtswn.terminal_action.equip")));
        FactoryEntryHint.draw(mc, drawHud(mc, lines));
    }

    private static void hint(List<String> controls, boolean preferred, String key, String label) {
        controls.add(preferred ? 0 : controls.size(), action(key, label));
    }

    private static void monitorHints(HudContext context, List<String> controls) {
        if (!context.air && !context.block) return;
        boolean bound = PortableWirelessNetworkMonitor.isMonitorBound(context.held);
        boolean own = bound && context.mc.thePlayer.getUniqueID()
            .toString()
            .equals(PortableWirelessNetworkMonitor.getOwnerUUID(context.held));
        // An arbitrary block can consume ordinary use. A monitor-owned panel has its own explicit binding path.
        if (context.air) hint(controls, !context.sneaking, context.use, bound ? "toggle_hud" : "monitor_bind");
        else if (context.target instanceof TileEntityNetworkInfoPanel && own)
            hint(controls, !context.sneaking, context.use, "bind_screen");
        hint(controls, context.sneaking, context.sneak + "+" + context.use, own ? "monitor_open" : "monitor_rebind");
    }

    private static void linkHints(HudContext context, List<String> controls) {
        if (!context.air && !context.block) return;
        boolean output = WirelessEnergyTap.getOutputModeStatic(context.held);
        if (context.block) {
            String linkAction = linkTargetAction(context, output);
            if (linkAction != null) hint(controls, !context.sneaking, context.use, linkAction);
        }
        hint(
            controls,
            context.sneaking && (!context.alt || context.block),
            context.sneak + "+" + context.use,
            output ? "link_to_energy" : "link_to_dynamo");
        if (context.air) {
            hint(controls, context.alt && !context.shift, "Alt+" + context.right, "link_open");
            hint(controls, context.alt && context.shift, context.revealKeys(), "reveal");
        }
    }

    private static String linkTargetAction(HudContext context, boolean output) {
        if (!(context.target instanceof IBasicEnergyContainer) || !(context.target instanceof ICoverable)) return null;
        ICoverable coverable = (ICoverable) context.target;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (WirelessEnergyTap.isOurGTswnCover(coverable.getCoverItemAtSide(side))) return "link_remove";
        }
        if (!context.validFace() || context.hit.hitVec == null) return null;
        ForgeDirection side = GTUtility.determineWrenchingSide(
            ForgeDirection.getOrientation(context.hit.sideHit),
            (float) (context.hit.hitVec.xCoord - context.hit.blockX),
            (float) (context.hit.hitVec.yCoord - context.hit.blockY),
            (float) (context.hit.hitVec.zCoord - context.hit.blockZ));
        IBasicEnergyContainer container = (IBasicEnergyContainer) context.target;
        if (coverable.hasCoverAtSide(side) || container.getEUCapacity() <= 0) return null;
        if (!output && container.getInputVoltage() <= 0
            && container.getOutputVoltage() <= 0
            && !LaserHatchUtil.isLaserHatch(context.target)) return null;
        return output ? "link_dynamo" : "link_energy";
    }

    private static void deviceHints(HudContext context, List<String> controls) {
        boolean machine = context.target instanceof IGregTechTileEntity
            && DeviceMachineTypes.isWorkingMachine(((IGregTechTileEntity) context.target).getMetaTileEntity());
        // The item NBT controls interception, while the ACK controls whether a teleport can actually be sent.
        boolean visual = ItemDeviceInfoTerminal.isVisualizationEnabled(context.held);
        String plain = null;
        if (visual) plain = DeviceTerminalVisualizationState.isActiveHeld()
            && DeviceTerminalAnchorRenderer.INSTANCE.hasCurrentTarget() ? "device_travel" : "device_open";
        else if (machine) plain = "device_bind";
        else if (context.air) plain = "device_open";
        if (plain != null) hint(
            controls,
            !context.alt && !context.shift && !context.sneaking,
            context.air || context.block ? context.use : context.right,
            plain);
        hint(
            controls,
            !context.alt && (context.shift || context.sneaking),
            context.sneak + "+" + (context.air || context.block ? context.use : context.right),
            machine ? "device_track" : "device_settings");
        hint(controls, context.alt && !context.shift, "Alt+" + context.right, "device_scan");
        hint(
            controls,
            context.alt && context.shift,
            "Alt+Shift+" + (context.air || context.block ? context.use : context.right),
            "device_visual");
    }

    private static void quantumHints(HudContext context, List<String> controls) {
        boolean bound = ItemNetworkQuantumTerminal.isBound(context.held);
        if (context.air) {
            hint(controls, context.sneaking && !context.alt, context.sneak + "+" + context.use, "quantum_open");
            if (bound) hint(controls, context.alt && context.shift, context.revealKeys(), "reveal");
            return;
        }
        if (!context.block) return;
        boolean incorporated = QuantumIncorporationClientState
            .registered(context.mc.theWorld, context.hit.blockX, context.hit.blockY, context.hit.blockZ);
        if (context.target instanceof TileController) {
            hint(controls, !context.alt && !context.sneaking, context.use, "quantum_bind");
            // Controller quantization is server-owned. The label states the condition instead of inventing client
            // state.
            hint(controls, !context.alt && context.sneaking, context.sneak + "+" + context.use, "quantum_remove");
            int[] anchor = ItemNetworkQuantumTerminal.getAnchor(context.held);
            boolean sameAnchor = anchor != null && anchor[0] == context.mc.theWorld.provider.dimensionId
                && anchor[1] == context.hit.blockX
                && anchor[2] == context.hit.blockY
                && anchor[3] == context.hit.blockZ;
            // AE2 syncs metadata 1 for powered, non-conflicting controllers. This is necessary, not a client grid
            // query.
            if (bound && !sameAnchor
                && context.mc.theWorld.getBlockMetadata(context.hit.blockX, context.hit.blockY, context.hit.blockZ)
                    == 1)
                hint(controls, context.alt && !context.shift, "Alt+" + context.right, "quantum_migrate");
            return;
        }
        if (!incorporated) {
            if (context.target instanceof TileEntityNetworkQuantumNode)
                hint(controls, !context.alt && context.sneaking, context.sneak + "+" + context.use, "quantum_pickup");
            if (bound) {
                String placement = quantumPlacementAction(context);
                if (placement != null) hint(controls, !context.alt && !context.sneaking, context.use, placement);
            }
        }
        if (incorporated) hint(controls, context.alt && !context.shift, "Alt+" + context.use, "quantum_release");
        else if (bound && QuantumIncorporationRegistry
            .isEligible(context.mc.theWorld, context.hit.blockX, context.hit.blockY, context.hit.blockZ))
            hint(controls, context.alt && !context.shift, "Alt+" + context.use, "quantum_incorporate");
    }

    private static String quantumPlacementAction(HudContext context) {
        if (!context.validFace()) return null;
        QuantumBusTarget swap = QuantumBusTarget.resolve(
            context.mc.theWorld,
            context.hit.blockX,
            context.hit.blockY,
            context.hit.blockZ,
            context.hit.sideHit);
        if (swap != null)
            return QuantumIncorporationClientState.registered(context.mc.theWorld, swap.x, swap.y, swap.z) ? null
                : "quantum_replace";
        ForgeDirection side = ForgeDirection.getOrientation(context.hit.sideHit);
        int x = context.hit.blockX + side.offsetX, y = context.hit.blockY + side.offsetY,
            z = context.hit.blockZ + side.offsetZ;
        return context.mc.theWorld.blockExists(x, y, z) && context.mc.theWorld.getBlock(x, y, z)
            .isReplaceable(context.mc.theWorld, x, y, z) ? "quantum_node" : null;
    }

    private static final class HudContext {

        private final Minecraft mc;
        private final ItemStack held;
        private final MovingObjectPosition hit;
        private final TileEntity target;
        private final boolean air, block, alt, shift, sneaking;
        private final String use, sneak, right;

        private HudContext(Minecraft mc, ItemStack held) {
            this.mc = mc;
            this.held = held;
            hit = mc.objectMouseOver;
            air = TerminalRevealCharge.isPointingAtAir(mc);
            block = hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mc.theWorld.blockExists(hit.blockX, hit.blockY, hit.blockZ);
            target = block ? mc.theWorld.getTileEntity(hit.blockX, hit.blockY, hit.blockZ) : null;
            alt = QuantumIncorporationClientHandler.isAltDown();
            shift = QuantumIncorporationClientHandler.isShiftDown();
            sneaking = mc.thePlayer.isSneaking();
            use = TerminalKeyNames.name(mc.gameSettings.keyBindUseItem.getKeyCode());
            sneak = TerminalKeyNames.name(mc.gameSettings.keyBindSneak.getKeyCode());
            right = TerminalKeyNames.name(-99);
        }

        private boolean validFace() {
            return block && hit.sideHit >= 0 && hit.sideHit < ForgeDirection.VALID_DIRECTIONS.length;
        }

        private String revealKeys() {
            // Reveal starts on physical Shift+right mouse and the server also requires the actual sneak binding.
            return "Alt+Shift+" + ("Shift".equals(sneak) || right.equals(sneak) ? "" : sneak + "+") + right;
        }
    }

    private static FactoryEntryHintLayout.Bounds drawHud(Minecraft mc, List<String> lines) {
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        return drawHud(
            mc.fontRenderer,
            lines,
            10,
            8,
            resolution.getScaledWidth() - 20,
            Math.max(20, resolution.getScaledHeight() / 3));
    }

    /** Shared hint card inside either the ordinary HUD or an already transformed world canvas. */
    public static FactoryEntryHintLayout.Bounds drawHud(FontRenderer font, List<String> lines, int x, int y, int width,
        int height) {
        int widest = 0;
        for (String line : lines) widest = Math.max(widest, font.getStringWidth(line));
        float scale = Math.min(1, Math.max(1, width) / (float) (widest + 20));
        scale = Math.min(scale, Math.max(1, height) / (float) (lines.size() * 11 + 12));
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x + width / 2f, y, 0);
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
        int half = (widest + 16) / 2;
        return new FactoryEntryHintLayout.Bounds(
            (int) Math.floor(x + width / 2f - half * scale),
            y,
            (int) Math.ceil(half * 2 * scale),
            (int) Math.ceil((lines.size() * 11 + 6) * scale));
    }
}
