package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.TerminalKeyNames;
import com.miaokatze.gtswn.client.TerminalWheelGeometry;
import com.miaokatze.gtswn.client.TerminalWheelHold;
import com.miaokatze.gtswn.client.TerminalWheelInput;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;

/** Thirty-two real annular MUI2 regions sharing their drawing and transformed input geometry. */
public final class GuiTerminalWheel extends GtswnMuiScreen {

    private static final int WIDTH = 384;
    private static final int HEIGHT = 365;
    private static final int CENTER_X = WIDTH / 2;
    private static final int CENTER_Y = 218;
    private static final int INNER = 49;
    private Sector charging;
    private int chargeTicks;
    private final ItemStack[] terminals;
    private final ItemStack[] placeholders = new ItemStack[8];
    private int selected;
    // The initial short C press opens the wheel; only later short releases cycle the selection.
    private final TerminalWheelHold hold = new TerminalWheelHold();
    private GuiScreen inputScreen;
    private final boolean engineering = EngineeringModeController.isActive();

    public GuiTerminalWheel() {
        super("terminal_wheel", WIDTH, HEIGHT);
        terminals = TerminalBaubles.terminals(mc.thePlayer);
        for (int i = 0; i < 8; i++) placeholders[i] = TerminalPresentation.placeholder(i);
        getMainPanel().background(IDrawable.EMPTY);
        getMainPanel().child(
            GtswnMuiWidgets.canvas(
                "wheel_backdrop",
                0,
                0,
                WIDTH,
                HEIGHT,
                (context, mouseX, mouseY, partialTicks) -> drawBackdrop()));
        for (int i = 0; i < 8; i++) for (int zone = 0; zone < 4; zone++) {
            getMainPanel().child(
                new Sector(i, zone).name("terminal_sector_" + i + "_" + zone)
                    .pos(0, 0)
                    .size(WIDTH, HEIGHT)
                    .background(IDrawable.EMPTY));
        }
        if (engineering) {
            getMainPanel().child(
                GtswnMuiWidgets.button(
                    "engineering_follow",
                    () -> GuiEngineeringTools.tr(EngineeringModeController.INSTANCE.following() ? "following" : "free"),
                    16,
                    56,
                    84,
                    20,
                    () -> {
                        closeWheel();
                        EngineeringModeController.INSTANCE.toggleFollowing();
                    }));
            getMainPanel().child(
                GtswnMuiWidgets.button("engineering_return", GuiEngineeringTools.tr("return"), 106, 56, 84, 20, () -> {
                    closeWheel();
                    EngineeringModeController.INSTANCE.returnToPlayer();
                }));
            getMainPanel().child(
                GtswnMuiWidgets
                    .button("engineering_camera", GuiEngineeringTools.tr("dash.camera"), 196, 56, 84, 20, () -> {
                        closeWheel();
                        EngineeringModeController.INSTANCE.toggleCameraPanel();
                    }));
            getMainPanel().child(
                GtswnMuiWidgets
                    .button("engineering_exit", GuiEngineeringTools.tr("tools.exit"), 286, 56, 82, 20, () -> {
                        closeWheel();
                        EngineeringModeController.close();
                    }));
        }
    }

    @Override
    public void onOpen() {
        super.onOpen();
        // MUI invokes onOpen from InitGuiEvent.Post, after Minecraft assigned the wrapper as currentScreen.
        inputScreen = mc.currentScreen;
        if (inputScreen != null) inputScreen.allowUserInput = false;
        TerminalWheelInput.open();
    }

    @Override
    public void onClose() {
        cancelCharge();
        TerminalWheelInput.close();
        super.onClose();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (inputScreen != null && mc.currentScreen == inputScreen) inputScreen.allowUserInput = false;
        applyHoldAction(hold.tick(TerminalBaubleController.bindingDown(TerminalBaubleController.keyCode())));
        ItemStack[] current = TerminalBaubles.terminals(mc.thePlayer);
        System.arraycopy(current, 0, terminals, 0, terminals.length);
        if (charging != null) {
            if (!Display.isActive() || !Mouse.isButtonDown(0)
                || !charging.isHovering()
                || terminals[charging.index] == null) cancelCharge();
            else if (++chargeTicks >= 21) {
                Sector complete = charging;
                charging = null;
                activate(complete.index, complete.zone);
            }
        }
    }

    @Override
    public boolean onKeyPressed(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE || key == mc.gameSettings.keyBindInventory.getKeyCode()) {
            closeWheel();
            return true;
        }
        if (key == TerminalBaubleController.keyCode()) {
            beginWheelPress();
            return true;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER || key == Keyboard.KEY_SPACE) {
            confirm(selected, false);
            return true;
        }
        return true;
    }

    @Override
    public boolean onKeyRelease(char character, int key) {
        TerminalWheelInput.released(key);
        if (key == TerminalBaubleController.keyCode()) applyHoldAction(hold.release());
        return true;
    }

    @Override
    public boolean onMousePressed(int button) {
        if (button - 100 == mc.gameSettings.keyBindInventory.getKeyCode()) {
            closeWheel();
            return true;
        }
        if (button - 100 == TerminalBaubleController.keyCode()) {
            beginWheelPress();
            return true;
        }
        return super.onMousePressed(button);
    }

    @Override
    public boolean onMouseRelease(int button) {
        if (button == 0) cancelCharge();
        TerminalWheelInput.released(button - 100);
        if (button - 100 == TerminalBaubleController.keyCode()) applyHoldAction(hold.release());
        return super.onMouseRelease(button);
    }

    private void beginWheelPress() {
        applyHoldAction(
            hold.press(TerminalKeyNames.isDown(Keyboard.KEY_LSHIFT), TerminalKeyNames.isDown(Keyboard.KEY_RSHIFT)));
    }

    private void applyHoldAction(int action) {
        if (action == TerminalWheelHold.TAKE) confirm(selected, true);
        else if (action == TerminalWheelHold.CYCLE) {
            cancelCharge();
            selected = TerminalWheelGeometry.next(selected);
        } else if (action == TerminalWheelHold.CONFIRM) confirm(selected, false);
    }

    private void confirm(int index, boolean take) {
        if (terminals[index] == null) return;
        closeWheel();
        if (take) TerminalBaubleController.take(index);
        else if (engineering) com.miaokatze.gtswn.client.engineering.EngineeringHud.activateTerminal(index);
        else TerminalBaubleController.wheelAction(index, TerminalWheelGeometry.MAIN);
    }

    private void activate(int index, int zone) {
        if (terminals[index] == null) return;
        closeWheel();
        if (zone == TerminalWheelGeometry.MAIN && engineering)
            com.miaokatze.gtswn.client.engineering.EngineeringHud.activateTerminal(index);
        else if (index >= 3 && zone == TerminalWheelGeometry.INNER) {
            EngineeringModeController.open();
            if (EngineeringModeController.isActive())
                com.miaokatze.gtswn.client.engineering.EngineeringHud.showTerminalNodes(index);
        } else TerminalBaubleController.wheelAction(index, zone);
    }

    private void cancelCharge() {
        if (charging == null) return;
        TerminalBaubleController.cancelWheelCharge(charging.index);
        charging = null;
        chargeTicks = 0;
    }

    private void closeWheel() {
        cancelCharge();
        TerminalWheelInput.close();
        mc.displayGuiScreen(null);
    }

    private void drawBackdrop() {
        Gui.drawRect(6, 10, WIDTH - 6, 46, 0xA8101010);
        String key = TerminalBaubleController.keyName();
        String inventory = TerminalKeyNames.name(mc.gameSettings.keyBindInventory.getKeyCode());
        helpLine(
            TerminalBaubleController.row(
                TerminalBaubleController.action(key, "cycle"),
                TerminalBaubleController.action(key, "hold_confirm"),
                TerminalBaubleController.action(TerminalKeyNames.name(-100), "select")),
            17);
        helpLine(
            TerminalBaubleController.row(
                TerminalBaubleController.action("Shift+" + key, "take"),
                TerminalBaubleController.action(
                    TerminalKeyNames.name(Keyboard.KEY_SPACE) + "/" + TerminalKeyNames.name(Keyboard.KEY_RETURN),
                    "confirm"),
                TerminalBaubleController.action(inventory + "/Esc", "exit")),
            31);
        annulus(0, Math.PI * 2, 0, INNER - 5, 0xF017202A);
        annulus(0, Math.PI * 2, INNER - 7, INNER - 5, 0xFF92703D);
        ItemStack stack = stack(selected);
        drawName(stack.getDisplayName(), CENTER_X, CENTER_Y - 19, 85, GtswnMuiTheme.TEXT_TITLE);
        center(
            TerminalBaubleController.text("gtswn.terminal_wheel.slot", selected + 1),
            CENTER_X,
            CENTER_Y + 11,
            GtswnMuiTheme.TEXT_MUTED);
        if (engineering && terminals[selected] != null && charging == null && hold.progress() <= 0)
            drawName(identity(terminals[selected], selected), CENTER_X, CENTER_Y + 24, 88, GtswnMuiTheme.TEXT_MUTED);
        if (terminals[selected] == null) center(
            TerminalBaubleController.text("gtswn.terminal_wheel.empty"),
            CENTER_X,
            CENTER_Y + 25,
            GtswnMuiTheme.TEXT_MUTED);
        else if (charging != null || hold.progress() > 0) {
            float progress = charging == null ? hold.progress() : Math.min(1, chargeTicks / 20f);
            annulus(
                -Math.PI / 2,
                -Math.PI / 2 + Math.PI * 2 * progress,
                INNER - 10,
                INNER - 7,
                GtswnMuiTheme.TEXT_ACCENT);
            center(
                TerminalBaubleController.text("gtswn.terminal_wheel.hold_progress", Math.round(progress * 100)),
                CENTER_X,
                CENTER_Y + 25,
                GtswnMuiTheme.TEXT_ACCENT);
        }
    }

    private void helpLine(String text, int y) {
        float scale = Math.min(1f, (WIDTH - 24f) / Math.max(1, fontRendererObj.getStringWidth(text)));
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(CENTER_X, y, 0);
            GL11.glScalef(scale, scale, 1);
            center(text, 0, 0, GtswnMuiTheme.TEXT_BODY);
        } finally {
            GL11.glPopMatrix();
        }
    }

    public static String identity(ItemStack stack, int slot) {
        return TerminalPresentation.identity(stack, slot);
    }

    private ItemStack stack(int index) {
        return terminals[index] == null ? placeholders[index] : terminals[index];
    }

    private void center(String text, int x, int y, int color) {
        fontRendererObj.drawStringWithShadow(text, x - fontRendererObj.getStringWidth(text) / 2, y, color);
    }

    private void drawName(String text, int x, int y, int width, int color) {
        List<String> lines = fontRendererObj.listFormattedStringToWidth(text, width);
        for (int line = 0; line < Math.min(2, lines.size()); line++) {
            String content = lines.get(line);
            if (line == 1 && lines.size() > 2) content = fontRendererObj.trimStringToWidth(content, width - 10) + "...";
            center(content, x, y + line * 10, color);
        }
    }

    private void annulus(double start, double end, double inner, double outer, int color) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glColor4f(
            (color >> 16 & 255) / 255f,
            (color >> 8 & 255) / 255f,
            (color & 255) / 255f,
            (color >>> 24) / 255f);
        Tessellator tess = Tessellator.instance;
        tess.startDrawing(GL11.GL_QUAD_STRIP);
        int steps = Math.max(2, (int) Math.ceil((end - start) * 28));
        for (int part = 0; part <= steps; part++) {
            double angle = start + (end - start) * part / steps;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            tess.addVertex(CENTER_X + cos * outer, CENTER_Y + sin * outer, 0);
            tess.addVertex(CENTER_X + cos * inner, CENTER_Y + sin * inner, 0);
        }
        tess.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void drawIcon(ItemStack stack, int x, int y, boolean present) {
        TerminalPresentation.drawIcon(mc, fontRendererObj, stack, x - 16, y - 16, present, 32);
    }

    private static String actionName(int index, int zone) {
        String[][] actions = { { "monitor", "take", "hud", "settings" }, { "link", "energy", "dynamo", "reveal" },
            { "device", "take", "scan", "visual" }, { "quantum", "take", "reveal", "nodes" } };
        return actions[Math.min(index, 3)][zone];
    }

    private static String iconName(String action) {
        if ("take".equals(action)) return "export";
        if ("settings".equals(action)) return "settings";
        if ("nodes".equals(action)) return "world";
        if ("visual".equals(action)) return "box-select";
        if ("energy".equals(action) || "dynamo".equals(action) || "scan".equals(action)) return action;
        return "visible";
    }

    private static String trAction(String action, String suffix) {
        return TerminalBaubleController.text("gtswn.terminal_wheel.action." + action + suffix);
    }

    private void smallName(String text, int x, int y, int color) {
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(0.72f, 0.72f, 1);
            if (fontRendererObj.getStringWidth(text) > 68) text = fontRendererObj.trimStringToWidth(text, 58) + "...";
            center(text, 0, 0, color);
        } finally {
            GL11.glPopMatrix();
        }
    }

    private final class Sector extends Widget<Sector> implements Interactable {

        private final int index;
        private final int zone;
        private final String action;
        private final ResourceLocation[] icons = new ResourceLocation[4];

        private Sector(int index, int zone) {
            this.index = index;
            this.zone = zone;
            this.action = actionName(index, zone);
            String[] states = { "normal", "hover", "active", "disabled" };
            for (int i = 0; i < states.length; i++) icons[i] = new ResourceLocation(
                "gtswn",
                "textures/gui/terminal_wheel/" + iconName(action) + "_" + states[i] + ".png");
            tooltipDynamic(tooltip -> {
                tooltip.add(trAction(action, ""))
                    .newLine()
                    .add(trAction(action, ".desc"));
                tooltip.newLine()
                    .add(stack(index).getDisplayName());
                tooltip.newLine()
                    .add(TerminalBaubleController.text("gtswn.terminal_wheel.slot", index + 1));
                if (terminals[index] == null) tooltip.newLine()
                    .add(TerminalBaubleController.text("gtswn.terminal_wheel.missing"));
                else {
                    tooltip.newLine()
                        .add(identity(terminals[index], index));
                    if (requiresCharge()) tooltip.newLine()
                        .add(TerminalBaubleController.text("gtswn.terminal_wheel.charge_hint"));
                    if ("reveal".equals(action)) tooltip.newLine()
                        .add(TerminalBaubleController.text("gtswn.terminal_wheel.reveal_conditions"));
                    if ("scan".equals(action)) tooltip.newLine()
                        .add(TerminalBaubleController.text("gtswn.terminal_wheel.scan_conditions"));
                    if ("nodes".equals(action)) tooltip.newLine()
                        .add(TerminalBaubleController.text("gtswn.terminal_wheel.nodes_conditions"));
                    if (zone == TerminalWheelGeometry.MAIN && engineering) tooltip.newLine()
                        .add(TerminalBaubleController.text("gtswn.terminal_wheel.engineering_source"));
                }
            });
            tooltip().setAutoUpdate(true);
        }

        private boolean requiresCharge() {
            return "scan".equals(action) || "reveal".equals(action);
        }

        @Override
        public boolean isInside(IViewportStack viewport, int mouseX, int mouseY, boolean absolute) {
            int x = absolute ? viewport.unTransformX(mouseX, mouseY) : mouseX;
            int y = absolute ? viewport.unTransformY(mouseX, mouseY) : mouseY;
            return TerminalWheelGeometry.contains(index, zone, x - CENTER_X, y - CENTER_Y);
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0) return Result.STOP;
            selected = index;
            if (terminals[index] == null) return Result.STOP;
            cancelCharge();
            if (requiresCharge()) {
                charging = this;
                chargeTicks = 0;
                TerminalBaubleController.beginWheelCharge(index);
            } else activate(index, zone);
            return Result.SUCCESS;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            GtswnMuiWidgets.drawLegacy(() -> {
                boolean present = terminals[index] != null;
                boolean active = charging == this || (zone == TerminalWheelGeometry.MAIN && selected == index);
                boolean hover = isHovering();
                double angle = TerminalWheelGeometry.centerAngle(index, zone);
                double start = TerminalWheelGeometry.start(index, zone);
                double end = TerminalWheelGeometry.end(index, zone);
                double inner = TerminalWheelGeometry.inner(zone);
                double outer = TerminalWheelGeometry.outer(zone);
                annulus(start, end, inner, outer, !present ? 0xB5151D26 : hover || active ? 0xF0473A29 : 0xED202C37);
                annulus(
                    start,
                    end,
                    outer - 1,
                    outer,
                    !present ? 0xFF39434C : hover || active ? GtswnMuiTheme.TEXT_ACCENT : 0xFF56616C);
                double radius = TerminalWheelGeometry.centerRadius(zone);
                int x = CENTER_X + (int) Math.round(Math.cos(angle) * radius);
                int y = CENTER_Y + (int) Math.round(Math.sin(angle) * radius);
                if (zone == TerminalWheelGeometry.MAIN) {
                    int iconX = CENTER_X + (int) Math.round(Math.cos(angle) * (radius - 3));
                    int iconY = CENTER_Y + (int) Math.round(Math.sin(angle) * (radius - 3));
                    drawIcon(stack(index), iconX, iconY - 8, present);
                    smallName(stack(index).getDisplayName(), x, y + 11, present ? GtswnMuiTheme.TEXT_BODY : 0xFF697580);
                    if (index >= 3) smallName(
                        TerminalBaubleController
                            .text("gtswn.color." + QuantumNetworkColor.name(QuantumNetworkColor.get(stack(index)))),
                        x,
                        y + 19,
                        present ? GtswnMuiTheme.TEXT_ACCENT : 0xFF697580);
                } else drawActionIcon(x, y, !present ? 3 : active ? 2 : hover ? 1 : 0);
            });
        }

        private void drawActionIcon(int x, int y, int state) {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            try {
                mc.getTextureManager()
                    .bindTexture(icons[state]);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(1, 1, 1, 1);
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                tess.addVertexWithUV(x - 6, y + 6, 0, 0, 1);
                tess.addVertexWithUV(x + 6, y + 6, 0, 1, 1);
                tess.addVertexWithUV(x + 6, y - 6, 0, 1, 0);
                tess.addVertexWithUV(x - 6, y - 6, 0, 0, 0);
                tess.draw();
            } finally {
                GL11.glPopAttrib();
            }
        }
    }
}
