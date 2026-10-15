package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.gui.ContainerTerminal;
import com.miaokatze.gtswn.common.gui.TerminalNameRules;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRenameQuantumTerminal;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkColor;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkSafety;

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
    private GuiTextField renameField;
    private GuiButton renameConfirm;
    private GuiButton renameCancel;
    private String renameExpectedName;
    private boolean previousRepeatEvents;
    private boolean renameRepeatEnabled;

    public GuiQuantumTerminal() {
        this(new ContainerTerminal(Minecraft.getMinecraft().thePlayer, true));
    }

    public GuiQuantumTerminal(ContainerTerminal container) {
        super(container);
        terminalContainer = container;
        xSize = WIDTH;
        ySize = HEIGHT;
        // Capabilities and response tokens belong to the current server session and held stack.
        latestData = null;
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
        if (renameField != null) layoutRenameEditor(renameField.getText());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (renameField != null) renameField.updateCursorCounter();
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
        for (int index = 0; index < terminalContainer.inventorySlots.size(); index++) {
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
        drawHeader(tr("gtswn.gui.quantum.network_details"));
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
        refreshSafetyButton();
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (renameField != null) {
            drawRenameEditor(mouseX, mouseY);
            return;
        }
        if (effectsPage) {
            for (Object entry : buttonList) {
                GuiButton setting = (GuiButton) entry;
                if (isNumericSetting(setting.id) && setting.mousePressed(mc, mouseX, mouseY)) {
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
        if (mouseX >= guiLeft + 178 && mouseX < guiLeft + 292 && mouseY >= guiTop + 128 && mouseY < guiTop + 156) {
            drawHoveringText(
                java.util.Arrays.asList(tr("gtswn.gui.quantum.safety_scope"), tr(safetyReason(latestData))),
                mouseX,
                mouseY,
                fontRendererObj);
            return;
        }
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
            if (data.managementOnly) {
                drawLine(tr("gtswn.gui.quantum.safety_management_only"), 56, GtswnGuiPalette.STATE_OVERLOAD);
                return;
            }
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
        if (renameField != null) {
            renameField.mouseClicked(mouseX, mouseY, button);
            if (button == 0 && renameConfirm.mousePressed(mc, mouseX, mouseY)) submitRename();
            else if (button == 0 && renameCancel.mousePressed(mc, mouseX, mouseY)) closeRenameEditor();
            return;
        }
        if (effectsPage && button == 1) {
            for (Object entry : buttonList) {
                GuiButton setting = (GuiButton) entry;
                if (isNumericSetting(setting.id) && setting.mousePressed(mc, mouseX, mouseY)) {
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
            || latestData != null && latestData.managementOnly
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
        if (terminalContainer.quantum && !effectsPage) {
            drawSupplyText(tr(safetyReason(latestData)), 178, 147, 114);
        }
        String label = tr(terminalContainer.quantum ? "gtswn.gui.terminal.anchor" : "gtswn.gui.terminal.tube");
        drawSupplyText(label, 178, 165, 97);
        drawSupplyText(tr("gtswn.gui.terminal.preload"), 184, 188, 108);
        drawSupplyText(tr("gtswn.gui.terminal.capacity"), 184, 202, 108);
        drawRect(278, 160, 296, 178, 0xFF241B31);
        ItemStack icon = terminalContainer.getSlot(0)
            .getStack();
        boolean empty = icon == null;
        if (empty) icon = terminalContainer.kind.reference();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        try {
            if (icon != null) {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, 279, 161);
                if (empty) drawRect(279, 161, 295, 177, 0xA0241B31);
                else itemRender.renderItemOverlayIntoGUI(
                    fontRendererObj,
                    mc.getTextureManager(),
                    icon,
                    279,
                    161,
                    Integer.toString(terminalContainer.supplyCount));
            }
        } finally {
            GL11.glPopAttrib();
        }
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    private void drawSupplyText(String text, int x, int y, int maxWidth) {
        float scale = Math.min(1.0F, (float) maxWidth / Math.max(1, fontRendererObj.getStringWidth(text)));
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(scale, scale, 1);
            fontRendererObj.drawString(text, 0, 0, GtswnGuiPalette.TEXT_BODY);
        } finally {
            GL11.glPopMatrix();
        }
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
        if (terminalContainer.quantum)
            buttonList.add(new GtswnGuiButton(103, guiLeft + 114, guiTop + 5, 58, 16, tr("gtswn.gui.quantum.rename")));
        buttonList.add(new GtswnGuiButton(100, guiLeft + 174, guiTop + 5, 58, 16, tr("gtswn.gui.terminal.details")));
        buttonList.add(new GtswnGuiButton(101, guiLeft + 234, guiTop + 5, 58, 16, tr("gtswn.gui.terminal.effects")));
        if (!effectsPage) {
            if (terminalContainer.quantum) {
                buttonList.add(new GtswnGuiButton(102, guiLeft + 178, guiTop + 128, 114, 16, ""));
                refreshSafetyButton();
            }
            return;
        }
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
            tr("gtswn.gui.terminal.material") + ": " + Config.tapMaterial, tr("gtswn.gui.terminal.reset"),
            tr("gtswn.gui.terminal.voltage_colors") + ": " + (Config.tapVoltageColorsEnabled ? "ON" : "OFF"),
            tr("gtswn.gui.terminal.voltage_ratio") + ": " + Config.tapVoltageColorPercent + "%" };
        for (int i = 0; i < labels.length; i++) {
            if (!tap && (i == 4 || i >= 6)) continue;
            int position = tap && i >= 5 ? (i == 5 ? 7 : i - 1) : i;
            int rows = tap ? 4 : 3;
            buttonList.add(
                new GtswnGuiButton(
                    110 + i,
                    guiLeft + 10 + (position / rows) * 144,
                    guiTop + 26 + (position % rows) * (tap ? 26 : 34),
                    134,
                    tap ? 22 : 28,
                    labels[i]));
        }
    }

    private void refreshSafetyButton() {
        for (Object entry : buttonList) {
            GuiButton button = (GuiButton) entry;
            if (button.id != 102) continue;
            button.displayString = tr("gtswn.gui.quantum.safety") + ": "
                + (latestData == null ? "..." : latestData.networkSafetyEnabled ? "ON" : "OFF");
            button.enabled = canToggleSafety(latestData);
        }
    }

    static boolean canToggleSafety(QuantumNetworkData data) {
        return data != null && data.online
            && data.globalSafetyEnabled
            && data.canChangeNetworkSafety
            && !data.networkSafetyIdentity.isEmpty()
            && data.networkSafetyRequestToken != 0;
    }

    static String safetyReason(QuantumNetworkData data) {
        if (data == null) return "gtswn.gui.quantum.safety_waiting";
        if (!data.globalSafetyEnabled) return "gtswn.gui.quantum.safety_global_off";
        if (!data.online) return "gtswn.gui.quantum.safety_offline";
        if (!data.canChangeNetworkSafety) return "gtswn.gui.quantum.safety_permission";
        if (data.networkSafetyIdentity.isEmpty() || data.networkSafetyRequestToken == 0)
            return "gtswn.gui.quantum.safety_waiting";
        return "gtswn.gui.quantum.safety_authorized";
    }

    private void drawEffects() {
        drawHeader(tr("gtswn.gui.terminal.effects"));
    }

    private void drawHeader(String text) {
        int available = terminalContainer.quantum ? 100 : COLOR_LEFT - 19;
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(text, available),
            guiLeft + 10,
            guiTop + 8,
            GtswnGuiPalette.TEXT_TITLE);
    }

    private void openRenameEditor() {
        ItemStack held = heldTerminal();
        if (!terminalContainer.quantum || held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal))
            return;
        renameExpectedName = ContainerTerminal.customName(held);
        previousRepeatEvents = Keyboard.areRepeatEventsEnabled();
        Keyboard.enableRepeatEvents(true);
        renameRepeatEnabled = true;
        layoutRenameEditor(held.getDisplayName());
        renameField.setSelectionPos(0);
    }

    private void layoutRenameEditor(String text) {
        int left = guiLeft + 10, top = guiTop + 56;
        renameField = new GuiTextField(fontRendererObj, left + 12, top + 29, 256, 18);
        renameField.setMaxStringLength(TerminalNameRules.MAX_LENGTH);
        renameField.setText(text);
        renameField.setFocused(true);
        renameField.setCanLoseFocus(false);
        renameConfirm = new GtswnGuiButton(104, left + 12, top + 80, 122, 20, tr("gui.done"));
        renameCancel = new GtswnGuiButton(105, left + 146, top + 80, 122, 20, tr("gui.cancel"));
    }

    private void drawRenameEditor(int mouseX, int mouseY) {
        int left = guiLeft + 10, top = guiTop + 56;
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_CURRENT_BIT);
        GL11.glTranslatef(0, 0, 400);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        try {
            drawRect(0, 0, width, height, 0xB0000000);
            GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.PANEL_QUANTUM, 4, left, top, 280, 112, zLevel);
            fontRendererObj
                .drawString(tr("gtswn.gui.quantum.rename_title"), left + 12, top + 10, GtswnGuiPalette.TEXT_TITLE);
            renameField.drawTextBox();
            fontRendererObj
                .drawString(tr("gtswn.gui.quantum.rename_hint"), left + 12, top + 57, GtswnGuiPalette.TEXT_BODY);
            renameConfirm.enabled = terminalContainer.renameToken() != 0;
            renameConfirm.drawButton(mc, mouseX, mouseY);
            renameCancel.drawButton(mc, mouseX, mouseY);
        } finally {
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
    }

    private void submitRename() {
        long token = terminalContainer.renameToken();
        if (token == 0) return;
        GTSWNPacketHandler.NETWORK.sendToServer(
            new PacketRenameQuantumTerminal(
                terminalContainer.windowId,
                terminalContainer.heldIndex(),
                token,
                renameExpectedName,
                renameField.getText()));
        closeRenameEditor();
    }

    private void closeRenameEditor() {
        if (renameField == null) return;
        renameField = null;
        renameExpectedName = null;
        if (renameRepeatEnabled) {
            Keyboard.enableRepeatEvents(previousRepeatEvents);
            renameRepeatEnabled = false;
        }
    }

    @Override
    protected void keyTyped(char character, int keyCode) {
        if (renameField != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) closeRenameEditor();
            else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) submitRename();
            else renameField.textboxKeyTyped(character, keyCode);
            return;
        }
        super.keyTyped(character, keyCode);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int button) {
        if (renameField == null) super.mouseMovedOrUp(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        if (renameField == null) super.mouseClickMove(mouseX, mouseY, button, elapsed);
    }

    @Override
    public void onGuiClosed() {
        closeRenameEditor();
        super.onGuiClosed();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 103) {
            openRenameEditor();
            return;
        }
        if (button.id == 100 || button.id == 101) {
            effectsPage = button.id == 101;
            rebuildButtons();
            return;
        }
        if (button.id == 102) {
            if (terminalContainer.quantum && !effectsPage
                && button.enabled
                && canToggleSafety(latestData)
                && matchesCachedAnchor(ItemNetworkQuantumTerminal.getAnchor(heldTerminal()))) {
                GTSWNPacketHandler.NETWORK.sendToServer(
                    new PacketSetQuantumNetworkSafety(
                        mc.thePlayer.inventory.currentItem,
                        latestData,
                        !latestData.networkSafetyEnabled));
                button.enabled = false;
            }
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
                if (tap)
                    Config.tapRenderDistance = Config.adjustRenderDistance(Config.tapRenderDistance, decreaseSetting);
                else Config.quantumRenderDistance = Config
                    .adjustRenderDistance(Config.quantumRenderDistance, decreaseSetting);
                break;
            case 114:
                if (!tap) return;
                String[] materials = { "old", "oldplus", "low", "medium", "high" };
                int index = java.util.Arrays.asList(materials)
                    .indexOf(Config.tapMaterial);
                Config.tapMaterial = materials[(index + 1) % materials.length];
                break;
            case 115:
                showSaveResult(Config.resetParticleConfiguration(tap));
                rebuildButtons();
                return;
            case 116:
                if (!tap) return;
                Config.tapVoltageColorsEnabled = !Config.tapVoltageColorsEnabled;
                break;
            case 117:
                if (!tap) return;
                Config.tapVoltageColorPercent = decreaseSetting
                    ? (Config.tapVoltageColorPercent <= 25 ? 100 : Config.tapVoltageColorPercent - 25)
                    : (Config.tapVoltageColorPercent >= 100 ? 25 : Config.tapVoltageColorPercent + 25);
                break;
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

    private boolean isNumericSetting(int id) {
        return id >= 111 && id <= 113 || id == 117 && !terminalContainer.quantum;
    }

    private static int nextSize(int value) {
        return value >= 400 ? 10 : Math.min(400, value + 10);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
