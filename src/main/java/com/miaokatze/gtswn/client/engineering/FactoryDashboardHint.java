package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.config.Configuration;

import com.miaokatze.gtswn.client.TerminalKeyNames;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.client.ui.MinecraftGuidanceRenderer;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Mod-owned onboarding and persistence; Core draws passive cards without capturing any input. */
@SideOnly(Side.CLIENT)
public final class FactoryDashboardHint {

    static final FactoryDashboardHint INSTANCE = new FactoryDashboardHint();
    private static final long START = System.nanoTime();
    private static final Map<UUID, FactoryDashboardHintState> PLAYERS = new HashMap<>();
    private static Configuration preferences;
    private static UUID playerId;
    private static Object connection;
    private static FactoryDashboardHintState state;

    private FactoryDashboardHint() {}

    public static void tick(Minecraft mc) {
        if (ensure(mc)) state.observeFactory(EngineeringModeController.isActive(), now());
    }

    /** Kept for the factory button host; the cue now has a consistent top-of-screen position. */
    public static void render(UiRect anchor, int width, int height) {
        render(width, height);
    }

    public static void render(int width, int height) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ensure(mc) || mc.gameSettings.hideGUI || width < 32 || height < 42) return;
        boolean dashboard = WorkbenchHud.dashboardVisible();
        if (mc.currentScreen != null && !(mc.currentScreen instanceof FactoryDashboardScreen)) return;
        if (dashboard) {
            // Persist only once the theme cue is drawable, independently from discovery completion.
            if (state.showTheme(now())) save("theme_shown");
            if (state.themeVisible(now())) card(mc, width, height, text("theme.title"), text("theme.detail"));
        } else if (state.discoveryVisible(now())) {
            int code = EngineeringModeController.isActive() ? FactoryShortcuts.dashboardKeyCode() : 0;
            if (code == 0) code = EngineeringModeController.DASHBOARD.getKeyCode();
            String key = code == 0 ? text("unbound") : TerminalKeyNames.name(code);
            card(mc, width, height, text("title"), text(code == 0 ? "click" : "key", key));
        }
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT || EngineeringModeController.isActive()
            || mc.currentScreen != null) return;
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        render(resolution.getScaledWidth(), resolution.getScaledHeight());
    }

    /** Successful rendered dashboard frame, shared by factory and direct-screen hosts. */
    public static void opened() {
        if (ensure(Minecraft.getMinecraft()) && state.dashboardOpened()) save("opened");
    }

    /** Host cleanup must not erase the pending five-second normal-world reminder. */
    public static void close() {
        tick(Minecraft.getMinecraft());
    }

    private static boolean ensure(Minecraft mc) {
        if (mc.thePlayer == null || mc.theWorld == null || mc.thePlayer.isDead || mc.thePlayer.getHealth() <= 0) {
            disconnect();
            return false;
        }
        UUID id = mc.thePlayer.getUniqueID();
        if (id.equals(playerId) && connection == mc.getNetHandler()) return true;
        disconnect();
        if (preferences == null) {
            preferences = new Configuration(
                new File(
                    Loader.instance()
                        .getConfigDir(),
                    "gtswn/factory_dashboard_client.cfg"));
            preferences.load();
        }
        playerId = id;
        connection = mc.getNetHandler();
        state = PLAYERS.computeIfAbsent(
            id,
            key -> new FactoryDashboardHintState(
                preferences.get("opened", key.toString(), false)
                    .getBoolean(false),
                preferences.get("theme_shown", key.toString(), false)
                    .getBoolean(false)));
        return true;
    }

    private static void disconnect() {
        if (state != null) state.disconnect();
        playerId = null;
        connection = null;
        state = null;
    }

    private static void save(String category) {
        preferences.get(category, playerId.toString(), false)
            .set(true);
        preferences.save();
    }

    private static void card(Minecraft mc, int width, int height, String title, String detail) {
        int w = Math.min(
            width - 12,
            Math.max(mc.fontRenderer.getStringWidth(title), mc.fontRenderer.getStringWidth(detail)) + 20);
        int h = mc.fontRenderer.FONT_HEIGHT * 2 + 16;
        MinecraftGuidanceRenderer.card(
            mc,
            FactoryGuidanceStyle.PALETTE,
            new UiRect((width - w) / 2, 6, w, h),
            width,
            height,
            title,
            detail,
            now());
    }

    private static String text(String suffix, Object... args) {
        return StatCollector.translateToLocalFormatted("gtswn.factory_dashboard_hint." + suffix, args);
    }

    private static long now() {
        return (System.nanoTime() - START) / 1_000_000;
    }
}
