package com.miaokatze.gtswn.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.utils.Platform;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.miaokatze.gtswn.common.gui.GtswnMuiTheme;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Native controls and a bounded drawing-only leaf for existing chart and status renderers. */
@SideOnly(Side.CLIENT)
public final class GtswnMuiWidgets {

    private GtswnMuiWidgets() {}

    public static ButtonWidget<?> button(String name, String label, int x, int y, int width, int height,
        Runnable action) {
        return button(name, () -> label, x, y, width, height, action);
    }

    public static ButtonWidget<?> button(String name, Supplier<String> label, int x, int y, int width, int height,
        Runnable action) {
        return button(name, label, x, y, width, height, () -> true, action);
    }

    /** A disabled action stays visible; MUI's setEnabled(false) would remove the entire control. */
    public static ButtonWidget<?> button(String name, Supplier<String> label, int x, int y, int width, int height,
        BooleanSupplier actionable, Runnable action) {
        return new ActionButton(actionable).name(name)
            .pos(x, y)
            .size(width, height)
            .background(GtswnMuiTheme.BUTTON)
            .hoverBackground(GtswnMuiTheme.BUTTON_HOVER)
            .overlay(IKey.dynamic(label))
            .onMousePressed(button -> {
                if (button != 0) return false;
                action.run();
                return true;
            });
    }

    public static TextFieldWidget textField(String name, String initial, int x, int y, int width, int height,
        int maxLength) {
        TextFieldWidget field = new TextFieldWidget().name(name)
            .pos(x, y)
            .size(width, height)
            .background(GtswnMuiTheme.LIST_PANEL)
            .value(new StringValue(initial))
            .setMaxLength(maxLength)
            .autoUpdateOnChange(true);
        field.setText(initial);
        return field;
    }

    public static Widget<?> canvas(String name, int x, int y, int width, int height, Painter painter) {
        return new CanvasWidget(painter).name(name)
            .pos(x, y)
            .size(width, height)
            .background(IDrawable.EMPTY);
    }

    /** Legacy FontRenderer drawing needs the texture/alpha state that native text widgets set themselves. */
    public static void drawLegacy(Runnable painter) {
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            // MUI's stencil shape pass leaves colour-only drawing active before viewport children are drawn.
            Platform.setupDrawFont();
            painter.run();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static final class ActionButton extends ButtonWidget<ActionButton> {

        private final BooleanSupplier actionable;

        private ActionButton(BooleanSupplier actionable) {
            this.actionable = actionable;
        }

        @Override
        public Result onMousePressed(int mouseButton) {
            return actionable.getAsBoolean() ? super.onMousePressed(mouseButton) : Result.STOP;
        }

        @Override
        public IDrawable getBackground() {
            return actionable.getAsBoolean() ? super.getBackground() : GtswnMuiTheme.BUTTON_DISABLED;
        }

        @Override
        public IDrawable getHoverBackground() {
            return actionable.getAsBoolean() ? super.getHoverBackground() : GtswnMuiTheme.BUTTON_DISABLED;
        }
    }

    private static final class CanvasWidget extends Widget<CanvasWidget> {

        private final Painter painter;

        private CanvasWidget(Painter painter) {
            this.painter = painter;
        }

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            drawLegacy(
                () -> painter.draw(context, context.getMouseX(), context.getMouseY(), context.getPartialTicks()));
        }

        @Override
        public boolean canHover() {
            return false;
        }

        @Override
        public boolean canHoverThrough() {
            return true;
        }
    }

    @FunctionalInterface
    public interface Painter {

        void draw(ModularGuiContext context, int mouseX, int mouseY, float partialTicks);
    }
}
