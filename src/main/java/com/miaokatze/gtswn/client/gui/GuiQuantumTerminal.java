package com.miaokatze.gtswn.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkColor;

/** Quantum network details and network color selection. Server snapshots remain authoritative. */
public class GuiQuantumTerminal extends GuiScreen {

    private static QuantumNetworkData latestData;
    private static final int POLL_INTERVAL_TICKS = 10;
    private static final int WIDTH = 300;
    private static final int HEIGHT = 224;
    private static final int COLOR_LEFT = 197;
    private static final int COLOR_TOP = 31;
    private static final int COLOR_ROW_HEIGHT = 11;
    private int guiLeft;
    private int guiTop;
    private int pollTimer;

    public GuiQuantumTerminal() {
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(heldTerminal());
        if (!matchesCachedAnchor(anchor)) {
            latestData = null;
        }
    }

    private static ItemStack heldTerminal() {
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack stack = player == null ? null : player.getHeldItem();
        return stack != null && stack.getItem() instanceof ItemNetworkQuantumTerminal ? stack : null;
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
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ItemStack held = heldTerminal();
        if (held == null) {
            mc.displayGuiScreen(null);
            return;
        }
        if (pollTimer++ % POLL_INTERVAL_TICKS == 0 && ItemNetworkQuantumTerminal.isBound(held)) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == mc.gameSettings.keyBindInventory.getKeyCode()) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GtswnGuiDrawing.drawNineSlice(GtswnGuiTextures.PANEL_QUANTUM, 4, guiLeft, guiTop, WIDTH, HEIGHT, zLevel);
        drawRect(guiLeft + 8, guiTop + 19, guiLeft + WIDTH - 8, guiTop + 20, GtswnGuiPalette.DIVIDER);
        drawRect(
            guiLeft + COLOR_LEFT - 5,
            guiTop + 25,
            guiLeft + COLOR_LEFT - 4,
            guiTop + HEIGHT - 10,
            GtswnGuiPalette.DIVIDER);
        drawLine(tr("gtswn.gui.quantum.network_details"), 8, GtswnGuiPalette.TEXT_TITLE);
        drawDetails();
        fontRendererObj
            .drawString(tr("gtswn.gui.quantum.color"), guiLeft + COLOR_LEFT, guiTop + 22, GtswnGuiPalette.TEXT_TITLE);
        int selected = QuantumNetworkColor.get(heldTerminal());
        for (int i = 0; i < QuantumNetworkColor.COUNT; i++) {
            int left = guiLeft + COLOR_LEFT;
            int top = guiTop + COLOR_TOP + i * COLOR_ROW_HEIGHT;
            boolean hovered = mouseX >= left && mouseX < guiLeft + WIDTH - 8
                && mouseY >= top
                && mouseY < top + COLOR_ROW_HEIGHT;
            if (i == selected || hovered) {
                drawRect(
                    left - 1,
                    top,
                    guiLeft + WIDTH - 8,
                    top + COLOR_ROW_HEIGHT,
                    i == selected ? 0xFF685083 : 0xFF493B5D);
            }
            drawRect(left + 2, top + 2, left + 9, top + 9, 0xFF000000 | QuantumNetworkColor.rgb(i));
            String name = fontRendererObj
                .trimStringToWidth(tr("gtswn.color." + QuantumNetworkColor.name(i)), WIDTH - COLOR_LEFT - 29);
            fontRendererObj.drawString(name, left + 14, top + 2, GtswnGuiPalette.TEXT_BODY);
            if (i == selected) {
                fontRendererObj.drawString("*", guiLeft + WIDTH - 17, top + 2, GtswnGuiPalette.TEXT_TITLE);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawDetails() {
        ItemStack held = heldTerminal();
        if (held == null || !ItemNetworkQuantumTerminal.isBound(held)) {
            drawLine(tr("gtswn.gui.quantum.unbound"), 32, GtswnGuiPalette.TEXT_BODY);
        } else if (latestData == null) {
            drawLine("...", 32, GtswnGuiPalette.TEXT_BODY);
        } else {
            QuantumNetworkData data = latestData;
            drawLine(tr("gtswn.gui.quantum.controller_pos") + ":", 32, GtswnGuiPalette.TEXT_BODY);
            drawLine(data.anchorX + ", " + data.anchorY + ", " + data.anchorZ, 44, GtswnGuiPalette.TEXT_BODY);
            drawLine(tr("gtswn.gui.quantum.dimension") + ": " + data.anchorDim, 62, GtswnGuiPalette.TEXT_BODY);
            drawLine(
                tr("gtswn.gui.quantum.node_count") + ": " + (data.online ? data.quantumNodeCount : 0),
                80,
                GtswnGuiPalette.TEXT_BODY);
            drawLine(
                tr("gtswn.gui.quantum.incorporation_count") + ": " + (data.online ? data.incorporationCount : 0),
                92,
                GtswnGuiPalette.TEXT_BODY);
            drawLine(tr("gtswn.gui.quantum.channels") + ":", 110, GtswnGuiPalette.TEXT_BODY);
            String channels = "0 / 0 (0%)";
            if (data.online) {
                int percentage = data.totalChannels > 0 ? (int) (data.usedChannels * 100L / data.totalChannels) : 0;
                channels = data.usedChannels + " / "
                    + (data.channelsInfinite ? "\u221e" : data.totalChannels + " (" + percentage + "%)");
            }
            drawLine(
                channels,
                122,
                data.online && !data.channelsInfinite && data.usedChannels > data.totalChannels
                    ? GtswnGuiPalette.STATE_OVERLOAD
                    : GtswnGuiPalette.TEXT_BODY);
            drawLine(tr("gtswn.gui.quantum.incorporation_channels") + ":", 140, GtswnGuiPalette.TEXT_BODY);
            drawLine(Integer.toString(data.online ? data.incorporationChannels : 0), 152, GtswnGuiPalette.TEXT_BODY);
            if (!data.online) {
                drawLine(tr("gtswn.gui.quantum.offline"), 164, GtswnGuiPalette.STATE_OVERLOAD);
            }
        }
        drawLine(tr("gtswn.gui.quantum.color_scope"), 181, GtswnGuiPalette.TEXT_BODY);
        drawLine(
            tr("gtswn.gui.quantum.color_current") + ": "
                + tr("gtswn.color." + QuantumNetworkColor.name(QuantumNetworkColor.get(held))),
            199,
            GtswnGuiPalette.TEXT_TITLE);
    }

    private void drawLine(String text, int y, int color) {
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(text, COLOR_LEFT - 19), guiLeft + 10, guiTop + y, color);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0 || heldTerminal() == null || mouseX < guiLeft + COLOR_LEFT || mouseX >= guiLeft + WIDTH - 8) {
            return;
        }
        int relativeY = mouseY - guiTop - COLOR_TOP;
        if (relativeY >= 0 && relativeY < QuantumNetworkColor.COUNT * COLOR_ROW_HEIGHT) {
            GTSWNPacketHandler.NETWORK.sendToServer(new PacketSetQuantumNetworkColor(relativeY / COLOR_ROW_HEIGHT));
        }
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
