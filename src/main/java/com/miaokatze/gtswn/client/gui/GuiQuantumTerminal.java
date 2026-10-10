package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkColor;

/** Quantum network details and network color selection. Server snapshots remain authoritative. */
public class GuiQuantumTerminal extends GuiContainer {

    private static QuantumNetworkData latestData;
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final int WIDTH = 300;
    private static final int HEIGHT = 240;
    private static final int COLOR_LEFT = 178;
    private static final int COLOR_TOP = 30;
    private static final int COLOR_ROW_HEIGHT = 12;
    private final ContainerTerminal terminalContainer;
    private boolean effectsPage;
    private int pollTimer;
    private boolean decreaseSetting;

    public GuiQuantumTerminal() {
        this(new ContainerTerminal(Minecraft.getMinecraft().thePlayer, true));
    }

    public GuiQuantumTerminal(ContainerTerminal container) {
        super(container);
        terminalContainer = container;
        xSize = WIDTH;
        ySize = HEIGHT;
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(heldTerminal());
        if (!matchesCachedAnchor(anchor)) {
            latestData = null;
        }
    }

    private static ItemStack heldTerminal() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack stack = player == null ? null : player.getHeldItem();
        return stack != null && (stack.getItem() instanceof ItemNetworkQuantumTerminal
            || stack.getItem() instanceof com.miaokatze.gtswn.common.items.WirelessEnergyTap) ? stack : null;
    }

    private static boolean matchesCachedAnchor(int[] anchor) {
        return anchor != null && latestData != null
            && latestData.anchorDim == anchor[0]
            && latestData.anchorX == anchor[1]
            && latestData.anchorY == anchor[2]
            && latestData.anchorZ == anchor[3];
    }

    public static void receiveData(QuantumNetworkData data) {
        latestData = data;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        super.initGui();
        guiLeft = (width - WIDTH) / 2;
        guiTop = (height - HEIGHT) / 2;
        rebuildButtons();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ItemStack held = heldTerminal();
        if (held == null) {
            mc.thePlayer.closeScreen();
            return;
        }
        if (terminalContainer.quantum && !effectsPage
            && pollTimer++ % POLL_INTERVAL_TICKS == 0
            && ItemNetworkQuantumTerminal.isBound(held)) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
        }
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.PANEL_QUANTUM, 4, guiLeft, guiTop, WIDTH, HEIGHT, zLevel);
        for (int index = 1; index < terminalContainer.inventorySlots.size(); index++) {
            net.minecraft.inventory.Slot slot = terminalContainer.getSlot(index);
            drawRect(
                guiLeft + slot.xDisplayPosition - 1,
                guiTop + slot.yDisplayPosition - 1,
                guiLeft + slot.xDisplayPosition + 17,
                guiTop + slot.yDisplayPosition + 17,
                0xFF241B31);
        }
        if (effectsPage) {
            drawEffects();
            return;
        }
        if (!terminalContainer.quantum) {
            drawLinkDetails();
            return;
        }
        drawRect(guiLeft + 8, guiTop + 19, guiLeft + WIDTH - 8, guiTop + 20, GtswnGuiPalette.DIVIDER);
        drawRect(
            guiLeft + COLOR_LEFT - 5,
            guiTop + 25,
            guiLeft + COLOR_LEFT - 4,
            guiTop + 132,
            GtswnGuiPalette.DIVIDER);
        drawLine(tr("gtswn.gui.quantum.network_details"), 8, GtswnGuiPalette.TEXT_TITLE);
        drawDetails();
        fontRendererObj
            .drawString(tr("gtswn.gui.quantum.color"), guiLeft + COLOR_LEFT, guiTop + 22, GtswnGuiPalette.TEXT_TITLE);
        int selected = QuantumNetworkColor.get(heldTerminal());
        for (int i = 0; i < QuantumNetworkColor.COUNT; i++) {
            int left = guiLeft + COLOR_LEFT + (i / 8) * 57;
            int top = guiTop + COLOR_TOP + (i % 8) * COLOR_ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < left + 56 && mouseY >= top && mouseY < top + COLOR_ROW_HEIGHT;
            if (i == selected || hovered) {
                drawRect(left - 1, top, left + 56, top + COLOR_ROW_HEIGHT, i == selected ? 0xFF685083 : 0xFF493B5D);
            }
            drawRect(left + 2, top + 1, left + 9, top + 8, 0xFF000000 | QuantumNetworkColor.rgb(i));
            String name = fontRendererObj.trimStringToWidth(tr("gtswn.color." + QuantumNetworkColor.name(i)), 32);
            fontRendererObj.drawString(name, left + 14, top + 1, GtswnGuiPalette.TEXT_BODY);
            if (i == selected) {
                fontRendererObj.drawString("*", left + 48, top + 1, GtswnGuiPalette.TEXT_TITLE);
            }
        }

    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (effectsPage) {
            for (Object entry : buttonList) {
                GuiButton setting = (GuiButton) entry;
                if (setting.id >= 111 && setting.id <= 113 && setting.mousePressed(mc, mouseX, mouseY)) {
                    drawHoveringText(
                        java.util.Collections.singletonList(tr("gtswn.gui.terminal.numeric_hint")),
                        mouseX,
                        mouseY,
                        fontRendererObj);
                    return;
                }
            }
            return;
        }
        if (!terminalContainer.quantum) return;
        int column = (mouseX - guiLeft - COLOR_LEFT) / 57;
        int relativeY = mouseY - guiTop - COLOR_TOP;
        if (mouseX >= guiLeft + COLOR_LEFT && mouseX < guiLeft + WIDTH - 8
            && column < 2
            && relativeY >= 0
            && relativeY < 8 * COLOR_ROW_HEIGHT) {
            int color = column * 8 + relativeY / COLOR_ROW_HEIGHT;
            drawHoveringText(
                java.util.Arrays
                    .asList(tr("gtswn.color." + QuantumNetworkColor.name(color)), tr("gtswn.gui.quantum.color_scope")),
                mouseX,
                mouseY,
                fontRendererObj);
        }
    }

    private void drawDetails() {
        ItemStack held = heldTerminal();
        if (held == null || !ItemNetworkQuantumTerminal.isBound(held)) {
            drawLine(tr("gtswn.gui.quantum.unbound"), 32, GtswnGuiPalette.TEXT_BODY);
        } else if (latestData == null) {
            drawLine("...", 32, GtswnGuiPalette.TEXT_BODY);
        } else {
            QuantumNetworkData data = latestData;
            drawLine(
                data.anchorX + ", " + data.anchorY + ", " + data.anchorZ + " @ " + data.anchorDim,
                30,
                GtswnGuiPalette.TEXT_BODY);
            drawLine(
                tr("gtswn.gui.quantum.node_count") + ": " + (data.online ? data.quantumNodeCount : 0),
                43,
                GtswnGuiPalette.TEXT_BODY);
            drawLine(
                tr("gtswn.gui.quantum.incorporation_count") + ": " + (data.online ? data.incorporationCount : 0),
                56,
                GtswnGuiPalette.TEXT_BODY);
            drawLine(tr("gtswn.gui.quantum.channels"), 69, GtswnGuiPalette.TEXT_BODY);
            String channels = "0 / 0 (0%)";
            if (data.online) {
                int percentage = data.totalChannels > 0 ? (int) (data.usedChannels * 100L / data.totalChannels) : 0;
                channels = data.usedChannels + " / "
                    + (data.channelsInfinite ? "\u221e" : data.totalChannels + " (" + percentage + "%)");
            }
            drawLine(
                channels,
                81,
                data.online && !data.channelsInfinite && data.usedChannels > data.totalChannels
                    ? GtswnGuiPalette.STATE_OVERLOAD
                    : GtswnGuiPalette.TEXT_BODY);
            drawLine(
                tr("gtswn.gui.quantum.incorporation_channels") + ": " + (data.online ? data.incorporationChannels : 0),
                94,
                GtswnGuiPalette.TEXT_BODY);
            if (!data.online) drawLine(tr("gtswn.gui.quantum.offline"), 107, GtswnGuiPalette.STATE_OVERLOAD);
        }
        drawLine(
            tr("gtswn.gui.quantum.color_current") + ": "
                + tr("gtswn.color." + QuantumNetworkColor.name(QuantumNetworkColor.get(held))),
            120,
            GtswnGuiPalette.TEXT_TITLE);
    }

    private void drawLine(String text, int y, int color) {

        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(text, COLOR_LEFT - 19), guiLeft + 10, guiTop + y, color);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (effectsPage && button == 1) {
            for (Object entry : buttonList) {
                GuiButton setting = (GuiButton) entry;
                if (setting.id >= 111 && setting.id <= 113 && setting.mousePressed(mc, mouseX, mouseY)) {
                    decreaseSetting = true;
                    try {
                        actionPerformed(setting);
                    } finally {
                        decreaseSetting = false;
                    }
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
        if (effectsPage || !terminalContainer.quantum
            || button != 0
            || heldTerminal() == null
            || mouseX < guiLeft + COLOR_LEFT
            || mouseX >= guiLeft + WIDTH - 8) {
            return;
        }
        int relativeY = mouseY - guiTop - COLOR_TOP;
        int column = (mouseX - guiLeft - COLOR_LEFT) / 57;
        if (column >= 0 && column < 2 && relativeY >= 0 && relativeY < 8 * COLOR_ROW_HEIGHT) {
            GTSWNPacketHandler.NETWORK
                .sendToServer(new PacketSetQuantumNetworkColor(column * 8 + relativeY / COLOR_ROW_HEIGHT));
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj.drawString(tr("container.inventory"), 10, 138, GtswnGuiPalette.TEXT_BODY);
        String label = tr(terminalContainer.quantum ? "gtswn.gui.terminal.anchor" : "gtswn.gui.terminal.tube");
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(label, 103), 184, 144, GtswnGuiPalette.TEXT_BODY);
        drawRect(262, 160, 280, 178, 0xFF241B31);
        ItemStack icon = terminalContainer.kind.reference();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            if (icon != null) {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, 263, 161);
                if (terminalContainer.supplyCount == 0) drawRect(263, 161, 279, 177, 0xA0241B31);
            }
        } finally {
            GL11.glPopAttrib();
        }
        GL11.glDisable(GL11.GL_LIGHTING);
        fontRendererObj.drawString(terminalContainer.supplyCount + " / 640", 190, 186, GtswnGuiPalette.TEXT_TITLE);
        fontRendererObj.drawString(tr("gtswn.gui.terminal.transfer"), 184, 203, GtswnGuiPalette.TEXT_BODY);
    }

    private void drawLinkDetails() {
        drawLine(tr("gtswn.gui.terminal.link_details"), 8, GtswnGuiPalette.TEXT_TITLE);
        EntityPlayer player = mc.thePlayer;
        drawLine(tr("gtswn.gui.terminal.player") + ": " + player.getCommandSenderName(), 35, GtswnGuiPalette.TEXT_BODY);
        drawLine(tr("gtswn.gui.terminal.dimension") + ": " + player.dimension, 53, GtswnGuiPalette.TEXT_BODY);
        drawLine(tr("gtswn.gui.terminal.level") + ": " + player.experienceLevel, 71, GtswnGuiPalette.TEXT_BODY);
        drawLine(tr("gtswn.gui.terminal.team"), 84, GtswnGuiPalette.TEXT_TITLE);
        String team = terminalContainer.team();
        fontRendererObj.drawString(team, guiLeft + 10, guiTop + 99, GtswnGuiPalette.TEXT_BODY);
        drawLine("EU: " + terminalContainer.balance(), 116, GtswnGuiPalette.TEXT_BODY);
    }

    private void rebuildButtons() {
        buttonList.clear();
        buttonList.add(new GtswnGuiButton(100, guiLeft + 174, guiTop + 5, 58, 16, tr("gtswn.gui.terminal.details")));
        buttonList.add(new GtswnGuiButton(101, guiLeft + 234, guiTop + 5, 58, 16, tr("gtswn.gui.terminal.effects")));
        if (!effectsPage) return;
        boolean tap = !terminalContainer.quantum;
        String[] labels = {
            tr("gtswn.gui.terminal.enabled") + ": "
                + ((tap ? Config.tapParticlesEnabled : Config.quantumParticlesEnabled) ? "ON" : "OFF"),
            tr("gtswn.gui.terminal.density") + ": "
                + (tap ? Config.tapParticleDensityPercent : Config.quantumParticleDensityPercent)
                + "%",
            tr("gtswn.gui.terminal.size") + ": "
                + (tap ? Config.tapParticleSizePercent : Config.quantumParticleSizePercent)
                + "%",
            tr("gtswn.gui.terminal.distance") + ": " + (tap ? Config.tapRenderDistance : Config.quantumRenderDistance),
            tr("gtswn.gui.terminal.material") + ": " + Config.tapMaterial, tr("gtswn.gui.terminal.reset") };
        for (int i = 0; i < labels.length; i++) {
            if (i == 4 && !tap) continue;
            buttonList.add(
                new GtswnGuiButton(
                    110 + i,
                    guiLeft + 10 + (i / 3) * 144,
                    guiTop + 26 + (i % 3) * 34,
                    134,
                    28,
                    labels[i]));
        }
    }

    private void drawEffects() {
        drawLine(tr("gtswn.gui.terminal.effects"), 8, GtswnGuiPalette.TEXT_TITLE);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 100 || button.id == 101) {
            effectsPage = button.id == 101;
            rebuildButtons();
            return;
        }
        boolean tap = !terminalContainer.quantum;
        switch (button.id) {
            case 110:
                if (tap) Config.tapParticlesEnabled = !Config.tapParticlesEnabled;
                else Config.quantumParticlesEnabled = !Config.quantumParticlesEnabled;
                break;
            case 111:
                if (tap) Config.tapParticleDensityPercent = (decreaseSetting
                    ? Math.max(0, Config.tapParticleDensityPercent - 25)
                    : nextDensity(Config.tapParticleDensityPercent));
                else Config.quantumParticleDensityPercent = (decreaseSetting
                    ? Math.max(0, Config.quantumParticleDensityPercent - 25)
                    : nextDensity(Config.quantumParticleDensityPercent));
                break;
            case 112:
                if (tap)
                    Config.tapParticleSizePercent = (decreaseSetting ? Math.max(10, Config.tapParticleSizePercent - 10)
                        : nextSize(Config.tapParticleSizePercent));
                else Config.quantumParticleSizePercent = (decreaseSetting
                    ? Math.max(10, Config.quantumParticleSizePercent - 10)
                    : nextSize(Config.quantumParticleSizePercent));
                break;
            case 113:
                if (tap) Config.tapRenderDistance = (decreaseSetting ? Math.max(1, Config.tapRenderDistance - 16)
                    : nextDistance(Config.tapRenderDistance));
                else Config.quantumRenderDistance = (decreaseSetting ? Math.max(1, Config.quantumRenderDistance - 16)
                    : nextDistance(Config.quantumRenderDistance));
                break;
            case 114:
                String[] materials = { "old", "oldplus", "low", "medium", "high" };
                int index = java.util.Arrays.asList(materials)
                    .indexOf(Config.tapMaterial);
                Config.tapMaterial = materials[(index + 1) % materials.length];
                break;
            case 115:
                showSaveResult(Config.resetParticleConfiguration(tap));
                rebuildButtons();
                return;
            default:
                return;
        }
        showSaveResult(Config.saveParticleConfiguration());
        rebuildButtons();
    }

    private void showSaveResult(boolean saved) {
        if (!saved) mc.thePlayer
            .addChatMessage(new net.minecraft.util.ChatComponentTranslation("gtswn.command.effects.save_failed"));
    }

    private static int nextDensity(int value) {
        return value >= 200 ? 0 : Math.min(200, value + 25);
    }

    private static int nextSize(int value) {
        return value >= 400 ? 10 : Math.min(400, value + 10);
    }

    private static int nextDistance(int value) {
        return value >= 256 ? 1 : Math.min(256, value + 16);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
