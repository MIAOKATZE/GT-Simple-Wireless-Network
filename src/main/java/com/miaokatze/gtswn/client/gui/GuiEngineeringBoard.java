package com.miaokatze.gtswn.client.gui;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;

/** The existing multi-screen board, hosted by a real MUI screen with one transformed input surface. */
public final class GuiEngineeringBoard extends GtswnMuiScreen {

    public GuiEngineeringBoard() {
        super("engineering_board", 640, 400);
        getMainPanel().background(IDrawable.EMPTY);
        getMainPanel().child(
            new BoardSurface().name("portable_board_surface")
                .pos(0, 0)
                .size(640, 400)
                .background(IDrawable.EMPTY));
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (!PortableSuperDashboard.isOpen()) mc.displayGuiScreen(null);
    }

    @Override
    public void onClose() {
        PortableSuperDashboard.close();
        super.onClose();
    }

    private final class BoardSurface extends Widget<BoardSurface> implements Interactable {

        @Override
        public void draw(ModularGuiContext context, WidgetThemeEntry<?> theme) {
            GtswnMuiWidgets
                .drawLegacy(() -> PortableSuperDashboard.render(640, 400, context.getMouseX(), context.getMouseY()));
        }

        @Override
        public Result onMousePressed(int button) {
            PortableSuperDashboard.click(getContext().getMouseX(), getContext().getMouseY(), button);
            return Result.SUCCESS;
        }

        @Override
        public boolean onMouseScroll(UpOrDown direction, int amount) {
            PortableSuperDashboard.wheel(getContext().getMouseX(), getContext().getMouseY(), direction.modifier * 120);
            return true;
        }
    }
}
