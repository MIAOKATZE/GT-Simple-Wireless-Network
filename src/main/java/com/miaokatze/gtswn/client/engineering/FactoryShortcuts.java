package com.miaokatze.gtswn.client.engineering;

import java.io.File;
import java.util.EnumMap;
import java.util.Locale;
import java.util.function.ToIntFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.config.Configuration;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.TerminalInputReleaseBarrier;
import com.miaokatze.gtswn.client.TerminalKeyNames;
import com.miaokatze.gtswn.main.GTSimpleWirelessNetwork;

/** Factory-only bindings never enter vanilla's global key table; Core owns their active-mode dispatch. */
public final class FactoryShortcuts {

    public enum Action {

        TOGGLE(Keyboard.KEY_F12),
        TERMINAL_0,
        TERMINAL_1,
        TERMINAL_2,
        TERMINAL_3,
        TERMINAL_4,
        TERMINAL_5,
        TERMINAL_6,
        TERMINAL_7,
        DASHBOARD,
        CAMERA,
        RETURN(Keyboard.KEY_HOME),
        FOLLOW(Keyboard.KEY_M),
        PIN(Keyboard.KEY_P),
        CLEAR(Keyboard.KEY_DELETE),
        MONITOR,
        POWER,
        MAP,
        REFRESH,
        ALERTS,
        GRID_MC,
        GRID_GT,
        SELECT_AREA,
        SCOPE,
        BASELINE,
        STOP,
        OVERVIEW,
        FILTER;

        final int defaultKey;

        Action() {
            this(Keyboard.KEY_NONE);
        }

        Action(int defaultKey) {
            this.defaultKey = defaultKey;
        }

        String description() {
            return this == TOGGLE ? "key.gtswn.engineering" : "key.gtswn.factory." + name().toLowerCase(Locale.ROOT);
        }
    }

    /** Kept independent of Minecraft state so precedence can be checked without a running client. */
    static final class Policy {

        static EnumMap<Action, Integer> defaults() {
            EnumMap<Action, Integer> defaults = new EnumMap<>(Action.class);
            for (Action action : Action.values()) if (action != Action.TOGGLE) defaults.put(action, action.defaultKey);
            return defaults;
        }

        static Action action(int code, int wheelCode, ToIntFunction<Action> keyCode) {
            // The wheel keeps its original binding, including when rebound onto a factory shortcut.
            if (code <= Keyboard.KEY_NONE || code >= Keyboard.KEYBOARD_SIZE
                || code == Keyboard.KEY_ESCAPE
                || code == wheelCode) return null;
            for (Action action : Action.values()) if (keyCode.applyAsInt(action) == code) return action;
            return null;
        }
    }

    private static final EnumMap<Action, Integer> BINDINGS = new EnumMap<>(Action.class);
    private static final TerminalInputReleaseBarrier BARRIER = new TerminalInputReleaseBarrier();
    private static Configuration configuration;
    private static Action binding;

    private FactoryShortcuts() {}

    static void register() {
        if (!BINDINGS.isEmpty()) return;
        BINDINGS.putAll(Policy.defaults());
        File file = configFile();
        // Missing defaults remain in memory until the player explicitly changes a binding.
        if (!file.isFile()) return;
        try {
            configuration = new Configuration(file);
            configuration.load();
            for (Action action : BINDINGS.keySet()) BINDINGS.put(
                action,
                configuration.get(
                    "shortcuts",
                    action.name()
                        .toLowerCase(Locale.ROOT),
                    action.defaultKey)
                    .getInt(action.defaultKey));
        } catch (RuntimeException e) {
            GTSimpleWirelessNetwork.LOG.error("Could not load factory shortcut settings", e);
        }
    }

    private static File configFile() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtswn/factory_shortcuts_client.cfg");
    }

    private static int keyCode(Action action) {
        return action == Action.TOGGLE ? EngineeringModeController.TOGGLE.getKeyCode()
            : BINDINGS.getOrDefault(action, Keyboard.KEY_NONE);
    }

    private static void saveBindings() {
        try {
            if (configuration == null) configuration = new Configuration(configFile());
            for (Action action : BINDINGS.keySet()) configuration.get(
                "shortcuts",
                action.name()
                    .toLowerCase(Locale.ROOT),
                action.defaultKey)
                .set(BINDINGS.get(action));
            configuration.save();
        } catch (RuntimeException e) {
            GTSimpleWirelessNetwork.LOG.error("Could not save factory shortcut settings", e);
        }
    }

    public static String hint(Action action) {
        int code = keyCode(action);
        if (code == Keyboard.KEY_NONE) return StatCollector.translateToLocal("gtswn.factory.binding.unbound");
        String name = code >= Keyboard.KEYBOARD_SIZE ? Integer.toString(code) : TerminalKeyNames.name(code);
        if (code < Keyboard.KEY_NONE || code >= Keyboard.KEYBOARD_SIZE || code == Keyboard.KEY_ESCAPE)
            return StatCollector.translateToLocalFormatted("gtswn.factory.binding.keyboard_only", name);
        if (code == TerminalBaubleController.keyCode())
            return StatCollector.translateToLocalFormatted("gtswn.factory.binding.wheel_conflict", name);
        if (action(code) != action)
            return StatCollector.translateToLocalFormatted("gtswn.factory.binding.conflict", name);
        return name;
    }

    public static void beginBinding(Action action) {
        if (action == null || (action != Action.TOGGLE && !BINDINGS.containsKey(action))
            || !EngineeringModeController.isActive()) return;
        binding = action;
        BARRIER.open();
        KeyBinding.unPressAllKeys();
    }

    public static void beginToggleBinding() {
        beginBinding(Action.TOGGLE);
    }

    public static String bindingPrompt() {
        return binding == null ? ""
            : StatCollector.translateToLocalFormatted(
                "gtswn.factory.binding.prompt",
                StatCollector.translateToLocal(binding.description()));
    }

    public static boolean isBinding() {
        return binding != null;
    }

    public static void cancelBinding() {
        if (binding == null) return;
        binding = null;
        fenceHeldKeys();
    }

    private static void fenceHeldKeys() {
        int buttons = Mouse.isCreated() ? Mouse.getButtonCount() : 0;
        int[] codes = new int[Keyboard.KEYBOARD_SIZE + buttons];
        for (int code = 0; code < Keyboard.KEYBOARD_SIZE; code++) codes[code] = code;
        for (int button = 0; button < buttons; button++) codes[Keyboard.KEYBOARD_SIZE + button] = button - 100;
        BARRIER.close(codes, TerminalKeyNames::isDown);
        KeyBinding.unPressAllKeys();
    }

    static void poll() {
        BARRIER.poll(TerminalKeyNames::isDown);
    }

    static boolean suppresses(int code) {
        return BARRIER.suppresses(code, TerminalKeyNames::isDown);
    }

    static Action action(int code) {
        return Policy.action(code, TerminalBaubleController.keyCode(), FactoryShortcuts::keyCode);
    }

    /** Called only from Core's raw queue boundary, before KeyBinding updates and FML input dispatch. */
    static boolean consumeKey(int code, boolean pressed, boolean repeat) {
        if (binding != null) {
            if (pressed && !repeat && code != Keyboard.KEY_NONE) {
                if (code == Keyboard.KEY_ESCAPE) cancelBinding();
                else {
                    int captured = code == Keyboard.KEY_DELETE ? Keyboard.KEY_NONE : code;
                    if (binding == Action.TOGGLE) {
                        EngineeringModeController.TOGGLE.setKeyCode(captured);
                        KeyBinding.resetKeyBindingArrayAndHash();
                        Minecraft.getMinecraft().gameSettings.saveOptions();
                    } else {
                        BINDINGS.put(binding, captured);
                        saveBindings();
                    }
                    binding = null;
                    fenceHeldKeys();
                }
            }
            return true;
        }
        if (suppresses(code)) return true;
        Action action = action(code);
        if (action == null) return false;
        // A shortcut rebound onto movement must not retain a state pressed before factory mode opened.
        KeyBinding.setKeyBindState(code, false);
        if (pressed && !repeat) {
            if (action == Action.TOGGLE) EngineeringModeController.toggle();
            else WorkbenchHud.shortcut(action);
        }
        return true;
    }
}
