package com.miaokatze.gtswn.client.testworld;

import java.lang.ref.Reference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.client.gui.GuiTextField;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Optional bridge to the inspected lwjgl3ify 3.0.37 public text-event API. Never writes its input buffer. */
final class UiTextInput {

    private UiTextInput() {}

    static Plan prepare() throws Refused {
        try {
            Class<?> events;
            try {
                events = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents");
            } catch (ClassNotFoundException absent) {
                if (Loader.isModLoaded("lwjgl3ify"))
                    throw new Refused("lwjgl3ify is installed but its inspected text API is unavailable");
                return new Plan();
            }
            ModContainer mod = Loader.instance()
                .getIndexedModList()
                .get("lwjgl3ify");
            String version = mod == null ? events.getPackage()
                .getImplementationVersion() : mod.getVersion();
            if (!"3.0.37".equals(version)) throw new Refused(
                "Unsupported lwjgl3ify text-input version: " + version + "; inspected version is 3.0.37");
            Class<?> event = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
            Class<?> handler = Class.forName("me.eigenraven.lwjgl3ify.client.TextFieldHandler");
            Plan plan = new Plan();
            plan.version = version;
            plan.eventConstructor = event.getConstructor(String.class);
            plan.publish = events.getMethod("injectTextEvent", event);
            plan.buffer = handler.getField("textBuffer");
            if (plan.buffer.getType() != StringBuilder.class) throw new Refused("Unexpected lwjgl3ify text-buffer API");
            plan.focused = handler.getDeclaredField("focusedTextInput");
            plan.depth = handler.getDeclaredField("textInputDepth");
            plan.focused.setAccessible(true);
            plan.depth.setAccessible(true);
            plan.requireEmpty(); // Resolve and validate the whole adapter before deleting existing field text.
            return plan;
        } catch (Refused refusal) {
            throw refusal;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unsupported) {
            throw new Refused(
                "Cannot safely prepare native text input: " + unsupported.getClass()
                    .getSimpleName());
        }
    }

    interface KeyDispatch {

        void send(char character) throws Exception;
    }

    static final class Plan {

        private String version = "ABSENT";
        private Constructor<?> eventConstructor;
        private Method publish;
        private Field buffer, focused, depth;
        private GuiTextField targetField;

        void validateBeforeDeletion(GuiTextField field) throws Exception {
            if (publish == null) return;
            requireEmpty();
            Object reference = focused.get(null);
            if (!(reference instanceof Reference<?>) || ((Reference<?>) reference).get() != field
                || depth.getInt(null) <= 0)
                throw new Refused(
                    "Native text-input focus/session is not attached to the real target field; original text retained");
            targetField = field;
        }

        private String pending() throws IllegalAccessException {
            return buffer == null ? "" : ((StringBuilder) buffer.get(null)).toString();
        }

        private void requireEmpty() throws IllegalAccessException, Refused {
            if (!pending().isEmpty())
                throw new Refused("Native text buffer contains pending input; original field text retained");
        }

        void send(char character, KeyDispatch key, BooleanSupplier stillFocused) throws Exception {
            if (publish == null) {
                key.send(character);
                return;
            }
            requireEmpty();
            String own = Character.toString(character);
            boolean keyAttempted = false;
            try {
                publish.invoke(null, eventConstructor.newInstance(own));
                if (!own.equals(pending()))
                    throw new Refused("Native text event was not accepted by the production listener; NOT_VERIFIED");
                keyAttempted = true;
                key.send(character);
            } finally {
                // A key callback may have partially succeeded before throwing. Never replay it. Only an
                // event published before any key attempt may receive its first normal dispatch here.
                String remaining = pending();
                Object reference = focused.get(null);
                boolean sameNativeFocus = reference instanceof Reference<?>
                    && ((Reference<?>) reference).get() == targetField
                    && depth.getInt(null) > 0;
                if (!keyAttempted && own.equals(remaining) && sameNativeFocus && stillFocused.getAsBoolean()) {
                    keyAttempted = true;
                    key.send(character);
                }
                if (!pending().isEmpty()) throw new Refused(
                    "Native text event was not consumed; NOT_VERIFIED; pending input retained for the production stop/close lifecycle");
            }
        }

        Map<String, Object> evidence() {
            return UiDebugSnapshot.map(
                "status",
                "SENT",
                "inputEvidence",
                publish == null ? "PRODUCTION_GUI_KEYTYPED" : "SYNTHETIC_TEXT_EVENT_AND_KEYTYPED",
                "provider",
                publish == null ? "VANILLA" : "lwjgl3ify",
                "version",
                version,
                "functionalStatus",
                "NOT_VERIFIED");
        }
    }

    static final class Refused extends Exception {

        Refused(String message) {
            super(message);
        }
    }
}
