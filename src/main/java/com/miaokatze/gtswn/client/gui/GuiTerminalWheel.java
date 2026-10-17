package com.miaokatze.gtswn.client.gui;

import java.util.List;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.TerminalWheelGeometry;
import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;

/** Eight real annular hit regions, with the same framework transform for painting and input. */
public final class GuiTerminalWheel extends GtswnMuiScreen {

    private static final int WIDTH = 384;
    private static final int HEIGHT = 365;
    private static final int CENTER_X = WIDTH / 2;
    private static final int CENTER_Y = 218;
    private static final int INNER = 49;
    private static final int OUTER = 136;
    private final ItemStack[] terminals;
    private final ItemStack[] placeholders = new ItemStack[8];
    private int selected;
    private boolean openingReleasePending = true;

    public GuiTerminalWheel() {
        super("terminal_wheel", WIDTH, HEIGHT);
        terminals = TerminalBaubles.terminals(mc.thePlayer);
        placeholders[0] = GTSWNItemList.Portable_Wireless_Network_Monitor.get(1);
        placeholders[1] = GTSWNItemList.Wireless_Energy_Tap.get(1);
        placeholders[2] = GTSWNItemList.Device_Info_Terminal.get(1);
        for (int i = 3; i < 8; i++) placeholders[i] = GTSWNItemList.ME_Network_Quantum_Terminal.get(1);
        getMainPanel().background(IDrawable.EMPTY);
        getMainPanel().child(
            GtswnMuiWidgets.canvas(
                "wheel_backdrop",
                0,
                0,
                WIDTH,
                HEIGHT,
                (context, mouseX, mouseY, partialTicks) -> drawBackdrop()));
        for (int i = 0; i < 8; i++) {
            getMainPanel().child(
                new Sector(i).name("terminal_sector_" + i)
                    .pos(0, 0)
                    .size(WIDTH, HEIGHT)
                    .background(IDrawable.EMPTY));
        }
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!TerminalBaubleController.bindingDown(TerminalBaubleController.keyCode())) openingReleasePending = false;
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
            if (!openingReleasePending && !Keyboard.isRepeatEvent()) selected = TerminalWheelGeometry.next(selected);
            return true;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER || key == Keyboard.KEY_SPACE) {
            if (!openingReleasePending) confirm(selected);
            return true;
        }
        return true;
    }

    @Override
    public boolean onKeyRelease(char character, int key) {
        if (key == TerminalBaubleController.keyCode()) openingReleasePending = false;
        return true;
    }

    @Override
    public boolean onMousePressed(int button) {
        if (button - 100 == mc.gameSettings.keyBindInventory.getKeyCode()) {
            closeWheel();
            return true;
        }
        if (button - 100 == TerminalBaubleController.keyCode()) {
            if (!openingReleasePending) selected = TerminalWheelGeometry.next(selected);
            return true;
        }
        return super.onMousePressed(button);
    }

    @Override
    public boolean onMouseRelease(int button) {
        if (button - 100 == TerminalBaubleController.keyCode()) openingReleasePending = false;
        return super.onMouseRelease(button);
    }

    private void confirm(int index) {
        if (terminals[index] == null) return;
        closeWheel();
        TerminalBaubleController.select(index);
    }

    private void closeWheel() {
        mc.displayGuiScreen(null);
    }

    private void drawBackdrop() {
        Gui.drawRect(22, 4, WIDTH - 22, 60, 0xDA10171F);
        Gui.drawRect(22, 4, WIDTH - 22, 5, GtswnMuiTheme.TEXT_ACCENT);
        center(TerminalBaubleController.text("gtswn.terminal_wheel.title"), CENTER_X, 12, GtswnMuiTheme.TEXT_TITLE);
        String inventory = GameSettings.getKeyDisplayString(mc.gameSettings.keyBindInventory.getKeyCode());
        String help = TerminalBaubleController.text("gtswn.terminal_wheel.cycle", TerminalBaubleController.keyName())
            + "  |  "
            + TerminalBaubleController.text("gtswn.terminal_wheel.confirm");
        center(help, CENTER_X, 28, GtswnMuiTheme.TEXT_BODY);
        center(
            TerminalBaubleController.text("gtswn.terminal_wheel.click") + "  |  "
                + TerminalBaubleController.text("gtswn.terminal_wheel.cancel", inventory),
            CENTER_X,
            43,
            GtswnMuiTheme.TEXT_MUTED);
        annulus(0, Math.PI * 2, 0, INNER - 5, 0xF017202A);
        annulus(0, Math.PI * 2, INNER - 7, INNER - 5, 0xFF92703D);
        ItemStack stack = stack(selected);
        drawName(stack.getDisplayName(), CENTER_X, CENTER_Y - 19, 85, GtswnMuiTheme.TEXT_TITLE);
        center(
            TerminalBaubleController.text("gtswn.terminal_wheel.slot", selected + 1),
            CENTER_X,
            CENTER_Y + 11,
            GtswnMuiTheme.TEXT_MUTED);
        if (terminals[selected] == null) center(
            TerminalBaubleController.text("gtswn.terminal_wheel.empty"),
            CENTER_X,
            CENTER_Y + 25,
            GtswnMuiTheme.TEXT_MUTED);
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
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x - 12, y - 12, 0);
            GL11.glScalef(1.5f, 1.5f, 1);
            if (present) {
                RenderHelper.enableGUIStandardItemLighting();
                RenderItem.getInstance()
                    .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, 0, 0);
                RenderHelper.disableStandardItemLighting();
            } else {
                // RenderItem resets alpha internally. Empty previews use the item's actual icon with explicit alpha.
                IIcon icon = stack.getItem()
                    .getIcon(stack, 0);
                if (icon == null) return;
                mc.getTextureManager()
                    .bindTexture(TextureMap.locationItemsTexture);
                GL11.glDisable(GL11.GL_LIGHTING);
                GL11.glDisable(GL11.GL_DEPTH_TEST);
                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                GL11.glColor4f(0.7f, 0.75f, 0.8f, 0.24f);
                Tessellator tess = Tessellator.instance;
                tess.startDrawingQuads();
                tess.addVertexWithUV(0, 16, 0, icon.getMinU(), icon.getMaxV());
                tess.addVertexWithUV(16, 16, 0, icon.getMaxU(), icon.getMaxV());
                tess.addVertexWithUV(16, 0, 0, icon.getMaxU(), icon.getMinV());
                tess.addVertexWithUV(0, 0, 0, icon.getMinU(), icon.getMinV());
                tess.draw();
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private final class Sector extends Widget<Sector> implements Interactable {

        private final int index;

        private Sector(int index) {
            this.index = index;
            tooltipDynamic(tooltip -> {
                tooltip.add(stack(index).getDisplayName())
                    .newLine();
                tooltip.add(TerminalBaubleController.text("gtswn.terminal_wheel.slot", index + 1));
                if (terminals[index] == null) tooltip.newLine()
                    .add(TerminalBaubleController.text("gtswn.terminal_wheel.empty"));
            });
            tooltip().setAutoUpdate(true);
        }

        @Override
        public boolean isInside(IViewportStack viewport, int mouseX, int mouseY, boolean absolute) {
            int x = absolute ? viewport.unTransformX(mouseX, mouseY) : mouseX;
            int y = absolute ? viewport.unTransformY(mouseX, mouseY) : mouseY;
            return TerminalWheelGeometry.sector(x - CENTER_X, y - CENTER_Y, INNER, OUTER) == index;
        }

        @Override
        public Result onMousePressed(int button) {
            if (button != 0) return Result.STOP;
            selected = index;
            confirm(index);
            return Result.SUCCESS;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            GtswnMuiWidgets.drawLegacy(() -> {
                boolean present = terminals[index] != null;
                boolean active = selected == index || isHovering();
                double angle = TerminalWheelGeometry.angle(index);
                double start = angle - TerminalWheelGeometry.STEP / 2 + TerminalWheelGeometry.GAP;
                double end = angle + TerminalWheelGeometry.STEP / 2 - TerminalWheelGeometry.GAP;
                annulus(start, end, INNER, OUTER, active ? 0xF0473A29 : present ? 0xED202C37 : 0xB5151D26);
                annulus(start, end, OUTER - 2, OUTER, active ? GtswnMuiTheme.TEXT_ACCENT : 0xFF56616C);
                int x = CENTER_X + (int) Math.round(Math.cos(angle) * 99);
                int y = CENTER_Y + (int) Math.round(Math.sin(angle) * 99);
                drawIcon(stack(index), x, y - 10, present);
                drawName(stack(index).getDisplayName(), x, y + 7, 76, present ? GtswnMuiTheme.TEXT_BODY : 0xFF697580);
            });
        }
    }
}
