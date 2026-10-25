package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.engineering.board.PortableSuperDashboard;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.miaokatzecore.client.render.GlScope;

/** Focus-only host for the shared dashboard. It never acquires or changes a world camera. */
final class FactoryDashboardScreen extends GuiScreen {

    private final Minecraft client = Minecraft.getMinecraft();
    private final World world = client.theWorld;
    private final EntityPlayer player = client.thePlayer;
    private final Object connection = client.getNetHandler();
    private final int dimension = world.provider.dimensionId;
    private final String profile = FactoryDisplayPreferences.currentSliceProfile();
    private boolean initialized, closed;
    private boolean hidden = client.gameSettings.hideGUI;

    @Override
    public void initGui() {
        // Resize preserves the dashboard tree and edits; only the first mount restores preferences.
        if (initialized) return;
        initialized = true;
        WorkbenchHud.restorePreferences();
        WorkbenchHud.openDashboard();
        KeyBinding.unPressAllKeys();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void updateScreen() {
        if (closed) return;
        if (!sessionValid()) {
            if (client.currentScreen == this) client.displayGuiScreen(null);
            return;
        }
        syncVisibility();
        WorkbenchHud.tick();
        // Reuse the existing terminal request owner, permission gates and bounded polling queue.
        EngineeringTerminalHudSnapshot.tick(EngineeringModeController.dashboardHostVisible());
    }

    boolean sessionValid() {
        return !closed && client.theWorld == world
            && client.thePlayer == player
            && client.getNetHandler() == connection
            && world.provider.dimensionId == dimension
            && !player.isDead
            && player.getHealth() > 0;
    }

    String preferenceProfile() {
        return profile;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (!EngineeringModeController.dashboardHostVisible()) return;
        double scale = EngineeringHud.scale(width, height);
        try (GlScope ignored = new GlScope()) {
            GL11.glScaled(scale, scale, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            WorkbenchHud
                .render((int) (width / scale), (int) (height / scale), (int) (mouseX / scale), (int) (mouseY / scale));
        }
    }

    @Override
    public void handleMouseInput() {
        if (!EngineeringModeController.dashboardInputAllowed()) return;
        double scale = EngineeringHud.scale(width, height);
        int w = (int) (width / scale), h = (int) (height / scale);
        int x = Mouse.getEventX() * w / client.displayWidth;
        int y = (client.displayHeight - Mouse.getEventY() - 1) * h / client.displayHeight;
        WorkbenchHud.mouse(x, y, w, h, Mouse.getEventButton(), Mouse.getEventButtonState(), Mouse.getEventDWheel());
    }

    @Override
    public void handleKeyboardInput() {
        syncVisibility();
        // GuiScreen drains its own queue, bypassing Core's Minecraft.runTick raw-key boundary.
        // Handle the full F1 sequence here so neither keyTyped nor global dispatch can see it twice.
        if (EngineeringInputPolicy.visibilityKey(
            Keyboard.getEventKey(),
            Keyboard.getEventKeyState(),
            Keyboard.isRepeatEvent(),
            Display.isActive() && sessionValid() && client.currentScreen == this,
            () -> {
                client.gameSettings.hideGUI = !client.gameSettings.hideGUI;
                syncVisibility();
            })) return;
        super.handleKeyboardInput();
    }

    @Override
    protected void keyTyped(char character, int code) {
        // F1/Esc remain usable while hidden, but no input is accepted by an inactive native window.
        if (!Display.isActive() || !sessionValid() || client.currentScreen != this) return;
        if (code == Keyboard.KEY_ESCAPE) {
            if (!Keyboard.isRepeatEvent()) {
                if (hidden) client.displayGuiScreen(null);
                else WorkbenchHud.escape();
            }
            return;
        }
        if (hidden) {
            if (!Keyboard.isRepeatEvent()
                && (code == Keyboard.KEY_E || code == client.gameSettings.keyBindInventory.getKeyCode()))
                client.displayGuiScreen(null);
            return;
        }
        if (!WorkbenchHud.typing() && code != Keyboard.KEY_NONE
            && code == EngineeringModeController.DASHBOARD.getKeyCode()) {
            if (!Keyboard.isRepeatEvent()) WorkbenchHud.switchTask(1);
            return;
        }
        WorkbenchHud.key(code, character, true);
    }

    private void syncVisibility() {
        if (hidden == client.gameSettings.hideGUI) return;
        hidden = client.gameSettings.hideGUI;
        WorkbenchHud.suspendInput();
        KeyBinding.unPressAllKeys();
    }

    @Override
    public void onGuiClosed() {
        if (closed) return;
        closed = true;
        WorkbenchHud.rememberPreferences();
        FactoryDisplayPreferences.save();
        WorkbenchHud.suspendInput();
        PortableSuperDashboard.close();
        WorkbenchHud.reset();
        EngineeringTerminalHudSnapshot.close();
        FactoryDashboardHint.close();
        FactoryShortcuts.fenceDashboardExit();
    }
}
