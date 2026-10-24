package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.config.Configuration;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;
import com.miaokatze.miaokatzecore.api.ui.component.UiRect;
import com.miaokatze.miaokatzecore.client.ui.MinecraftGuidanceRenderer;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Ordinary-world discovery affordance. Observes local accessories without owning world input. */
@SideOnly(Side.CLIENT)
public final class FactoryEntryHint {

    private static final long START_NANOS = System.nanoTime();
    private static final Map<UUID, FactoryEntryHintState> PLAYERS = new HashMap<>();
    private static Configuration preferences;
    private static EntityPlayer player;
    private static Object world;
    private static FactoryEntryHintState state;
    private static int poll;

    private FactoryEntryHint() {}

    public static void tick(Minecraft mc) {
        if (player != mc.thePlayer || world != mc.theWorld) {
            player = mc.thePlayer;
            world = mc.theWorld;
            state = player == null ? null : state(player.getUniqueID());
            poll = 0;
        }
        if (state == null || world == null) return;
        if (poll++ % 5 != 0) return;
        // Eight accessories, four times per second. Binding reads bounded scalar identity fields only.
        state.observe(equipment(player));
    }

    private static String equipment(EntityPlayer current) {
        ItemStack[] terminals = TerminalBaubles.terminals(current);
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < terminals.length; i++) {
            if (terminals[i] == null) continue;
            String binding = EngineeringTerminalSources.binding(terminals[i]);
            signature.append(i)
                .append(':')
                .append(binding.length())
                .append(':')
                .append(binding)
                .append(';');
        }
        return signature.length() == 0 ? null : signature.toString();
    }

    private static FactoryEntryHintState state(UUID id) {
        return PLAYERS.computeIfAbsent(id, key -> {
            load();
            return new FactoryEntryHintState(
                preferences.get("opened", key.toString(), false)
                    .getBoolean(false));
        });
    }

    private static void load() {
        if (preferences != null) return;
        preferences = new Configuration(
            new File(
                Loader.instance()
                    .getConfigDir(),
                "gtswn/factory_entry_client.cfg"));
        preferences.load();
    }

    /** Call for an accepted factory toggle binding while its camera is active, before closing or after opening. */
    public static void bindingUsed() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !EngineeringModeController.isActive()) return;
        UUID id = mc.thePlayer.getUniqueID();
        FactoryEntryHintState current = state(id);
        current.observe(equipment(mc.thePlayer));
        if (!current.confirmBinding()) return;
        preferences.get("opened", id.toString(), false)
            .set(true);
        preferences.save();
    }

    public static void draw(Minecraft mc, FactoryEntryHintLayout.Bounds terminal) {
        if (state == null || player != mc.thePlayer
            || world != mc.theWorld
            || !state.visible()
            || mc.currentScreen != null
            || mc.gameSettings.hideGUI
            || EngineeringModeController.isActive()) return;
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        FontRenderer font = mc.fontRenderer;
        int code = EngineeringModeController.TOGGLE.getKeyCode();
        String key = code == 0 ? text("unbound") : FactoryShortcuts.hint(FactoryShortcuts.Action.TOGGLE);
        String title = text("title", key);
        String detail = text("first");
        int desiredWidth = Math.min(208, Math.max(font.getStringWidth(title), font.getStringWidth(detail)) + 20);
        FactoryEntryHintLayout.Bounds box = FactoryEntryHintLayout
            .place(resolution.getScaledWidth(), resolution.getScaledHeight(), desiredWidth, terminal);
        MinecraftGuidanceRenderer.card(
            mc,
            FactoryGuidanceStyle.PALETTE,
            new UiRect(box.x, box.y, box.width, box.height),
            resolution.getScaledWidth(),
            resolution.getScaledHeight(),
            title,
            detail,
            now());
    }

    private static String text(String suffix, Object... args) {
        return StatCollector.translateToLocalFormatted("gtswn.factory_entry." + suffix, args);
    }

    private static long now() {
        return (System.nanoTime() - START_NANOS) / 1_000_000;
    }
}
