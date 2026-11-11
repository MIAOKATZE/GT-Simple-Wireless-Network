// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.gui.Gui;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.widgets.ButtonWidget;

/** Opaque, bordered controls scoped to the device terminal and its own dialogs. */
final class DeviceTerminalUi {

    static final IDrawable NORMAL = box(0xFF71889A, 0xFF2B3A48);
    static final IDrawable HOVER = box(0xFFE7BF74, 0xFF405B6C);
    static final IDrawable SELECTED = box(0xFFF0B04C, 0xFF57452B);
    static final IDrawable SELECTED_HOVER = box(0xFFFFD58C, 0xFF6D5733);
    static final IDrawable DISABLED = box(0xFF57616A, 0xFF252C34);
    static final IDrawable INPUT = box(0xFF71889A, 0xFF14212B);
    static final IDrawable INPUT_HOVER = box(0xFFE7BF74, 0xFF1A2C39);

    private DeviceTerminalUi() {}

    static IDrawable box(int border, int fill) {
        return (context, x, y, width, height, theme) -> drawBox(x, y, width, height, border, fill);
    }

    static void drawBox(int x, int y, int width, int height, int border, int fill) {
        Gui.drawRect(x, y, x + width, y + height, border);
        Gui.drawRect(x + 1, y + 1, x + width - 1, y + height - 1, fill);
    }

    static ButtonWidget<?> button(String name, String label, int x, int y, int width, int height, Runnable action) {
        return button(name, () -> label, x, y, width, height, action);
    }

    static ButtonWidget<?> button(String name, Supplier<String> label, int x, int y, int width, int height,
        Runnable action) {
        return button(name, label, x, y, width, height, () -> true, action);
    }

    static ButtonWidget<?> button(String name, Supplier<String> label, int x, int y, int width, int height,
        BooleanSupplier actionable, Runnable action) {
        return new BoxedButton(actionable).name(name)
            .pos(x, y)
            .size(width, height)
            .background(NORMAL)
            .hoverBackground(HOVER)
            .overlay(
                IKey.dynamic(label)
                    .color(GtswnGuiPalette.TEXT_BODY))
            .onMousePressed(button -> {
                if (button != 0) return false;
                action.run();
                return true;
            });
    }

    /** Inactive actions keep their border, label and tooltip without accepting a click. */
    private static final class BoxedButton extends ButtonWidget<BoxedButton> {

        private final BooleanSupplier actionable;

        BoxedButton(BooleanSupplier actionable) {
            this.actionable = actionable;
        }

        @Override
        public Result onMousePressed(int mouseButton) {
            return actionable.getAsBoolean() ? super.onMousePressed(mouseButton) : Result.STOP;
        }

        @Override
        public IDrawable getBackground() {
            return actionable.getAsBoolean() ? super.getBackground() : DISABLED;
        }

        @Override
        public IDrawable getHoverBackground() {
            return actionable.getAsBoolean() ? super.getHoverBackground() : DISABLED;
        }
    }
}
