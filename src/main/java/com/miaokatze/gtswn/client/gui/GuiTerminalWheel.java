// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.gui;

import java.util.ArrayList;
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
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.TerminalKeyNames;
import com.miaokatze.gtswn.client.TerminalWheelGeometry;
import com.miaokatze.gtswn.client.TerminalWheelHold;
import com.miaokatze.gtswn.client.TerminalWheelInput;
import com.miaokatze.gtswn.client.TerminalWheelLayout;
import com.miaokatze.gtswn.client.TerminalWheelModeHold;
import com.miaokatze.gtswn.client.engineering.EngineeringModeController;
import com.miaokatze.gtswn.client.engineering.FactoryDisplayPreferences;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;

/** Thirty-two real annular MUI2 regions sharing their drawing and transformed input geometry. */
public final class GuiTerminalWheel extends GtswnMuiScreen {

    private TerminalWheelLayout layout = new TerminalWheelLayout(384, 365, false);
    private final List<Widget<?>> engineeringControls = new ArrayList<>();
    private final ItemStack[] terminals;
    private final ItemStack[] placeholders = new ItemStack[8];
    private int selected;
    // The initial short C press opens the wheel; only later short releases cycle the selection.
    private final TerminalWheelHold hold = new TerminalWheelHold(FactoryDisplayPreferences.wheelMaintain());
    private final TerminalWheelModeHold modeHold = new TerminalWheelModeHold();
    private final ModeSwitch modeSwitch = new ModeSwitch();
    private GuiScreen inputScreen;
    private final boolean engineering = EngineeringModeController.isActive();

    public GuiTerminalWheel() {
        super(
            new ModularPanel("terminal_wheel").sizeRel(1f)
                .pos(0, 0)
                .background(IDrawable.EMPTY));
        terminals = TerminalBaubles.terminals(mc.thePlayer);
        for (int i = 0; i < 8; i++) placeholders[i] = TerminalPresentation.placeholder(i);
        getMainPanel().background(IDrawable.EMPTY);
        getMainPanel().child(
            GtswnMuiWidgets
                .canvas("wheel_backdrop", 0, 0, 1, 1, (context, mouseX, mouseY, partialTicks) -> drawBackdrop())
                .sizeRel(1f));
        for (int i = 0; i < 8; i++) for (int zone = 0; zone < 4; zone++) {
            getMainPanel().child(
                new Sector(i, zone).name("terminal_sector_" + i + "_" + zone)
                    .pos(0, 0)
                    .sizeRel(1f)
                    .background(IDrawable.EMPTY));
        }
        getMainPanel().child(
            modeSwitch.name("wheel_mode_switch")
                .pos(0, 0)
                .sizeRel(1f)
                .background(IDrawable.EMPTY));
        if (engineering) {
            addEngineeringControl(
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
            addEngineeringControl(
                GtswnMuiWidgets.button("engineering_return", GuiEngineeringTools.tr("return"), 106, 56, 84, 20, () -> {
                    closeWheel();
                    EngineeringModeController.INSTANCE.returnToPlayer();
                }));
            addEngineeringControl(
                GtswnMuiWidgets
                    .button("engineering_camera", GuiEngineeringTools.tr("dash.camera"), 196, 56, 84, 20, () -> {
                        closeWheel();
                        EngineeringModeController.INSTANCE.toggleCameraPanel();
                    }));
            addEngineeringControl(
                GtswnMuiWidgets
                    .button("engineering_exit", GuiEngineeringTools.tr("tools.exit"), 286, 56, 82, 20, () -> {
                        closeWheel();
                        EngineeringModeController.close();
                    }));
        }
    }

    private void addEngineeringControl(Widget<?> button) {
        engineeringControls.add(button);
        getMainPanel().child(button);
    }

    @Override
    public void onResize(int width, int height) {
        modeHold.cancel();
        layout = new TerminalWheelLayout(width, height, engineering);
        int buttonWidth = Math.min(84, Math.max(1, (width - 30) / 4));
        int left = (width - buttonWidth * 4 - 18) / 2;
        for (int i = 0; i < engineeringControls.size(); i++) engineeringControls.get(i)
            .pos(left + i * (buttonWidth + 6), layout.controlsTop)
            .size(buttonWidth, 20);
        super.onResize(width, height);
    }

    @Override
    public void onOpen() {
        super.onOpen();
        // MUI invokes onOpen from InitGuiEvent.Post, after Minecraft assigned the wrapper as currentScreen.
        inputScreen = mc.currentScreen;
        if (inputScreen != null) inputScreen.allowUserInput = false;
        if (hold.maintain()) hold.mode(true, TerminalBaubleController.bindingDown(TerminalBaubleController.keyCode()));
        TerminalWheelInput.open();
    }

    @Override
    public void onClose() {
        modeHold.cancel();
        TerminalWheelInput.close();
        super.onClose();
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (inputScreen != null && mc.currentScreen == inputScreen) inputScreen.allowUserInput = false;
        updateModeHold();
        applyHoldAction(hold.tick(TerminalBaubleController.bindingDown(TerminalBaubleController.keyCode())));
        ItemStack[] current = TerminalBaubles.terminals(mc.thePlayer);
        System.arraycopy(current, 0, terminals, 0, terminals.length);

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
        if (button == 0) modeHold.cancel();
        TerminalWheelInput.released(button - 100);
        if (button - 100 == TerminalBaubleController.keyCode()) applyHoldAction(hold.release());
        return super.onMouseRelease(button);
    }

    private void beginWheelPress() {
        applyHoldAction(
            hold.press(
                TerminalKeyNames.isDown(Keyboard.KEY_LSHIFT) || TerminalKeyNames.isDown(Keyboard.KEY_LCONTROL),
                TerminalKeyNames.isDown(Keyboard.KEY_RSHIFT) || TerminalKeyNames.isDown(Keyboard.KEY_RCONTROL)));
    }

    private void applyHoldAction(int action) {
        if (action == TerminalWheelHold.CLOSE) closeWheel();
        else if (action == TerminalWheelHold.TAKE) confirm(selected, true);
        else if (action == TerminalWheelHold.CYCLE) {
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

    private void closeWheel() {
        modeHold.cancel();
        TerminalWheelInput.close();
        mc.displayGuiScreen(null);
    }

    private void drawBackdrop() {
        updateModeHold();
        Gui.drawRect(
            layout.helpLeft,
            layout.helpTop,
            layout.helpLeft + layout.helpWidth,
            layout.helpTop + 36,
            0xA8101010);
        String key = TerminalBaubleController.keyName();
        String inventory = TerminalKeyNames.name(mc.gameSettings.keyBindInventory.getKeyCode());
        if (hold.maintain()) {
            helpLine(
                TerminalBaubleController.row(
                    TerminalBaubleController.action(key, "maintain_open"),
                    TerminalBaubleController.action(TerminalKeyNames.name(-100), "select")),
                layout.helpTop + 7);
            helpLine(
                TerminalBaubleController
                    .row(
                        TerminalBaubleController.action(
                            TerminalKeyNames.name(Keyboard.KEY_SPACE) + "/"
                                + TerminalKeyNames.name(Keyboard.KEY_RETURN),
                            "confirm"),
                        TerminalBaubleController.action(inventory + "/Esc", "exit")),
                layout.helpTop + 21);
        } else {
            helpLine(
                TerminalBaubleController.row(
                    TerminalBaubleController.action(key, "cycle"),
                    TerminalBaubleController.action(key, "hold_confirm"),
                    TerminalBaubleController.action(TerminalKeyNames.name(-100), "select")),
                layout.helpTop + 7);
            helpLine(
                TerminalBaubleController.row(
                    TerminalBaubleController.action("Ctrl+" + key + "/Shift+" + key, "take"),
                    TerminalBaubleController.action(
                        TerminalKeyNames.name(Keyboard.KEY_SPACE) + "/" + TerminalKeyNames.name(Keyboard.KEY_RETURN),
                        "confirm"),
                    TerminalBaubleController.action(inventory + "/Esc", "exit")),
                layout.helpTop + 21);
        }
        annulus(0, Math.PI * 2, 0, layout.centerDiscRadius(), 0xF017202A);
        annulus(0, Math.PI * 2, layout.centerDiscRadius() - 2, layout.centerDiscRadius(), 0xFF92703D);
        if (layout.centerDiscRadius() >= 24) drawName(
            layout.centerDiscRadius() >= 40 ? modeName()
                : TerminalBaubleController.text(
                    hold.maintain() ? "gtswn.terminal_wheel.mode.maintain_short"
                        : "gtswn.terminal_wheel.mode.toggle_short"),
            layout.centerX,
            layout.centerY - (layout.centerDiscRadius() >= 40 ? 17 : 5),
            Math.max(20, (int) (layout.centerDiscRadius() * 1.65)),
            GtswnMuiTheme.TEXT_TITLE);
        if (layout.centerDiscRadius() >= 40) center(
            TerminalBaubleController.text("gtswn.terminal_wheel.mode.hold"),
            layout.centerX,
            layout.centerY + 11,
            GtswnMuiTheme.TEXT_MUTED);
        if (hold.progress() > 0 || modeHold.progress() > 0) {
            boolean changingMode = modeHold.progress() > 0;
            float progress = changingMode ? modeHold.progress() : hold.progress();
            annulus(
                -Math.PI / 2,
                -Math.PI / 2 + Math.PI * 2 * progress,
                layout.centerDiscRadius() - 5,
                layout.centerDiscRadius() - 2,
                GtswnMuiTheme.TEXT_ACCENT);
            if (layout.centerDiscRadius() >= 24) center(
                TerminalBaubleController.text(
                    changingMode ? "gtswn.terminal_wheel.mode.progress" : "gtswn.terminal_wheel.hold_progress",
                    Math.round(progress * 100)),
                layout.centerX,
                layout.centerY + (layout.centerDiscRadius() >= 40 ? 25 : 11),
                GtswnMuiTheme.TEXT_ACCENT);
        }
    }

    private void helpLine(String text, int y) {
        float scale = Math.min(1f, layout.helpTextWidth / Math.max(1, fontRendererObj.getStringWidth(text)));
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(layout.centerX, y, 0);
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
        annulus(start, end, inner, outer, color, -1, -1);
    }

    private void sectorAnnulus(int index, int zone, double inner, double outer, int color) {
        annulus(
            TerminalWheelGeometry.start(index, zone, outer),
            TerminalWheelGeometry.end(index, zone, outer),
            inner,
            outer,
            color,
            index,
            zone);
    }

    private void annulus(double start, double end, double inner, double outer, int color, int index, int zone) {
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
            double ratio = part / (double) steps;
            double outerAngle = start + (end - start) * ratio;
            double innerAngle = outerAngle;
            if (index >= 0) {
                double innerStart = TerminalWheelGeometry.start(index, zone, inner);
                innerAngle = innerStart + (TerminalWheelGeometry.end(index, zone, inner) - innerStart) * ratio;
            }
            tess.addVertex(
                layout.centerX + Math.cos(outerAngle) * outer,
                layout.centerY + Math.sin(outerAngle) * outer,
                0);
            tess.addVertex(
                layout.centerX + Math.cos(innerAngle) * inner,
                layout.centerY + Math.sin(innerAngle) * inner,
                0);
        }
        tess.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void drawIcon(ItemStack stack, int x, int y, boolean present) {
        int size = layout.mainIconSize;
        TerminalPresentation.drawIcon(mc, fontRendererObj, stack, x - size / 2, y - size / 2, present, size);
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

    private String modeName() {
        return TerminalBaubleController.text(
            hold.maintain() ? "gtswn.terminal_wheel.mode.maintain" : "gtswn.terminal_wheel.mode.toggle",
            TerminalBaubleController.keyName());
    }

    private void updateModeHold() {
        boolean valid = inputScreen != null && mc.currentScreen == inputScreen
            && Display.isActive()
            && Mouse.isButtonDown(0)
            && modeSwitch.isHovering();
        if (modeHold.update(System.nanoTime() / 1000000L, valid)) {
            boolean maintain = !hold.maintain();
            FactoryDisplayPreferences.wheelMaintain(maintain);
            hold.mode(maintain, TerminalBaubleController.bindingDown(TerminalBaubleController.keyCode()));
        }
    }

    /** The center is a native MUI hit region; the full-panel bounds never catch sector clicks. */
    private final class ModeSwitch extends Widget<ModeSwitch> implements Interactable {

        private ModeSwitch() {
            tooltipDynamic(
                tooltip -> tooltip.add(modeName())
                    .newLine()
                    .add(TerminalBaubleController.text("gtswn.terminal_wheel.mode.tooltip")));
            tooltip().setAutoUpdate(true);
        }

        @Override
        public boolean isInside(IViewportStack viewport, int mouseX, int mouseY, boolean absolute) {
            int x = absolute ? viewport.unTransformX(mouseX, mouseY) : mouseX;
            int y = absolute ? viewport.unTransformY(mouseX, mouseY) : mouseY;
            double dx = x - layout.centerX, dy = y - layout.centerY;
            double radius = layout.centerDiscRadius();
            return dx * dx + dy * dy <= radius * radius;
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0) return Result.STOP;
            modeHold.press(System.nanoTime() / 1000000L);
            return Result.SUCCESS;
        }

        @Override
        public boolean onMouseRelease(int button) {
            if (button == 0) modeHold.cancel();
            return true;
        }

        @Override
        public void onMouseDrag(int button, long timeSinceClick) {
            if (button == 0 && !isHovering()) modeHold.cancel();
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

        @Override
        public boolean isInside(IViewportStack viewport, int mouseX, int mouseY, boolean absolute) {
            int x = absolute ? viewport.unTransformX(mouseX, mouseY) : mouseX;
            int y = absolute ? viewport.unTransformY(mouseX, mouseY) : mouseY;
            return layout.contains(index, zone, x, y);
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0) return Result.STOP;
            selected = index;
            if (terminals[index] == null) return Result.STOP;
            activate(index, zone);
            return Result.SUCCESS;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            GtswnMuiWidgets.drawLegacy(() -> {
                boolean present = terminals[index] != null;
                boolean active = selected == index || isHovering();
                boolean hover = isHovering();
                double angle = TerminalWheelGeometry.centerAngle(index, zone);
                double inner = layout.inner(zone);
                double outer = layout.outer(zone);
                int fill = active ? 0xF0473A29 : present ? 0xED202C37 : 0xB5151D26;
                int edge = active ? GtswnMuiTheme.TEXT_ACCENT : 0xFF56616C;
                sectorAnnulus(index, zone, inner, outer, fill);
                sectorAnnulus(index, zone, outer - 2, outer, edge);
                double radius = layout.centerRadius(zone);
                int x = layout.centerX + (int) Math.round(Math.cos(angle) * radius);
                int y = layout.centerY + (int) Math.round(Math.sin(angle) * radius);
                if (zone == TerminalWheelGeometry.MAIN) drawTerminal(present);
                else drawActionIcon(x, y, !present ? 3 : hover ? 1 : active ? 2 : 0);
            });
        }

        private TerminalWheelLayout contentLayout;
        private TerminalWheelLayout.Bounds contentBounds;
        private String contentName;
        private List<String> nameLines;
        private int shownLines;

        private void drawTerminal(boolean present) {
            String name = stack(index).getDisplayName();
            if (layout != contentLayout || !name.equals(contentName)) arrangeTerminal(name);
            if (contentBounds == null) return;
            int x = contentBounds.x + contentBounds.width / 2;
            drawIcon(stack(index), x, contentBounds.y + layout.mainIconSize / 2, present);
            int y = contentBounds.y + layout.mainIconSize + 3;
            for (int i = 0; i < shownLines; i++) GuiTerminalWheel.this
                .center(nameLines.get(i), x, y + i * 9, present ? GtswnMuiTheme.TEXT_BODY : 0xFF697580);
        }

        private void arrangeTerminal(String name) {
            contentLayout = layout;
            contentName = name;
            contentBounds = null;
            shownLines = 0;
            // Try the complete name at native font size before limiting unusually long custom names.
            for (int maxLines = Integer.MAX_VALUE; maxLines
                >= 1; maxLines = maxLines == Integer.MAX_VALUE ? 3 : maxLines - 1) {
                for (int width = 112; width >= 18; width -= 2) {
                    List<String> lines = fontRendererObj.listFormattedStringToWidth(name, width);
                    int count = Math.min(maxLines, lines.size());
                    TerminalWheelLayout.Bounds bounds = layout.terminalContent(index, width, count * 9);
                    if (bounds == null) continue;
                    contentBounds = bounds;
                    nameLines = lines;
                    shownLines = count;
                    return;
                }
            }
            // Extremely small viewports still retain a real terminal icon and the full dynamic tooltip.
            contentBounds = layout.content(index, layout.mainIconSize, layout.mainIconSize);
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
