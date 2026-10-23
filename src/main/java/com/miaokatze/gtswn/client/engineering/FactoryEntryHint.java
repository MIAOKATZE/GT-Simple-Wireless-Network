package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.config.Configuration;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.common.baubles.TerminalBaubles;
import com.miaokatze.gtswn.common.engineering.EngineeringTerminalSources;

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
    private static int warmup;
    private static int poll;

    private FactoryEntryHint() {}

    public static void tick(Minecraft mc) {
        if (player != mc.thePlayer || world != mc.theWorld) {
            player = mc.thePlayer;
            world = mc.theWorld;
            state = player == null ? null : state(player.getUniqueID());
            warmup = 40;
            poll = 0;
        }
        if (state == null || world == null) return;
        boolean baseline = warmup > 0;
        if (warmup > 0) warmup--;
        if (poll++ % 5 != 0) return;
        // Eight accessories, four times per second. Binding reads bounded scalar identity fields only.
        state.observe(equipment(player), now(), baseline);
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

    /** Call after successful factory activation, never merely after pressing its binding. */
    public static void opened() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !EngineeringModeController.isActive()) return;
        UUID id = mc.thePlayer.getUniqueID();
        FactoryEntryHintState current = state(id);
        current.observe(equipment(mc.thePlayer), now(), true);
        if (!current.opened(now())) return;
        preferences.get("opened", id.toString(), false)
            .set(true);
        preferences.save();
    }

    public static void draw(Minecraft mc, FactoryEntryHintLayout.Bounds terminal) {
        if (state == null || player != mc.thePlayer
            || world != mc.theWorld
            || !state.visible(now())
            || mc.currentScreen != null
            || mc.gameSettings.hideGUI
            || EngineeringModeController.isActive()) return;
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        FontRenderer font = mc.fontRenderer;
        int code = EngineeringModeController.TOGGLE.getKeyCode();
        String key = code == 0 ? text("unbound") : FactoryShortcuts.hint(FactoryShortcuts.Action.TOGGLE);
        String title = text("title", key);
        String detail = text(state.firstPending() ? "first" : "reminder");
        int desiredWidth = Math.min(208, Math.max(font.getStringWidth(title), font.getStringWidth(detail)) + 20);
        FactoryEntryHintLayout.Bounds box = FactoryEntryHintLayout
            .place(resolution.getScaledWidth(), resolution.getScaledHeight(), desiredWidth, terminal);
        double breath = .5 + .5 * Math.sin(now() / 420.0);
        int accent = ((int) (165 + breath * 90) << 24) | 0x73D7D0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            Gui.drawRect(box.x, box.y, box.right(), box.bottom(), 0xCF142124);
            Gui.drawRect(box.x, box.y, box.right(), box.y + 1, accent);
            Gui.drawRect(box.x, box.y, box.x + 2, box.bottom(), accent);
            font.drawStringWithShadow(fit(font, title, box.width - 20), box.x + 7, box.y + 5, 0xE8F5F4);
            font.drawStringWithShadow(fit(font, detail, box.width - 14), box.x + 7, box.y + 19, 0x9EBCBE);
            int arrowY = box.y + 7 + (int) Math.round(breath * 2);
            Gui.drawRect(box.right() - 11, arrowY, box.right() - 6, arrowY + 1, accent);
            Gui.drawRect(box.right() - 10, arrowY + 1, box.right() - 7, arrowY + 2, accent);
            Gui.drawRect(box.right() - 9, arrowY + 2, box.right() - 8, arrowY + 3, accent);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static String fit(FontRenderer font, String value, int width) {
        if (width <= 0) return "";
        if (font.getStringWidth(value) <= width) return value;
        if (font.getStringWidth("...") > width) return font.trimStringToWidth(value, width);
        return font.trimStringToWidth(value, width - font.getStringWidth("...")) + "...";
    }

    private static String text(String suffix, Object... args) {
        return StatCollector.translateToLocalFormatted("gtswn.factory_entry." + suffix, args);
    }

    private static long now() {
        return (System.nanoTime() - START_NANOS) / 1_000_000;
    }
}
