package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.config.Configuration;

import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.client.ui.MinecraftGuidanceRenderer;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Factory discovery policy and local completion storage. Core only renders the passive cue. */
@SideOnly(Side.CLIENT)
public final class FactoryDashboardHint {

    private static final FactoryDashboardHintState STATE = new FactoryDashboardHintState();
    private static Configuration preferences;
    private static UUID playerId;
    private static long started;

    private FactoryDashboardHint() {}

    /** Anchor is the dashboard button's final scaled-screen rectangle. Call after the button is drawn. */
    public static void render(UiRect anchor, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getMinecraft();
        // Passive drawing follows the button's visibility; losing OS focus does not hide the button either.
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        if (!ensure(mc) || !STATE.visible()) return;
        MinecraftGuidanceRenderer.render(
            mc,
            FactoryGuidanceStyle.PALETTE,
            anchor,
            screenWidth,
            screenHeight,
            text("title"),
            text("click"),
            (System.nanoTime() - started) / 1_000_000,
            true);
    }

    /** Call only after successfully opening the dashboard from factory mode, never for a failed click. */
    public static void opened() {
        if (!ensure(Minecraft.getMinecraft()) || !STATE.acknowledge(playerId)) return;
        preferences.get("opened", playerId.toString(), false)
            .set(true);
        preferences.save();
    }

    public static void close() {
        STATE.close();
        playerId = null;
        started = 0;
    }

    private static boolean ensure(Minecraft mc) {
        if (!EngineeringModeController.isActive() || mc.thePlayer == null || mc.theWorld == null) {
            close();
            return false;
        }
        UUID id = mc.thePlayer.getUniqueID();
        if (id.equals(playerId)) return true;
        if (preferences == null) {
            preferences = new Configuration(
                new File(
                    Loader.instance()
                        .getConfigDir(),
                    "gtswn/factory_dashboard_client.cfg"));
            preferences.load();
        }
        playerId = id;
        STATE.open(
            playerId,
            preferences.get("opened", playerId.toString(), false)
                .getBoolean(false));
        started = System.nanoTime();
        return true;
    }

    private static String text(String suffix) {
        return StatCollector.translateToLocal("gtswn.factory_dashboard_hint." + suffix);
    }
}
