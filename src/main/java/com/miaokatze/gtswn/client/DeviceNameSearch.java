// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Locale;

import net.minecraft.util.EnumChatFormatting;

import org.apache.logging.log4j.LogManager;

/** The input widgets supply text; optional NEC-Rework owns pinyin matching and its settings. */
public final class DeviceNameSearch {

    private static MethodHandle contains = findNech();

    private DeviceNameSearch() {}

    private static MethodHandle findNech() {
        try {
            Class<?> api = Class.forName("com.asdflj.nech.API");
            return MethodHandles.publicLookup()
                .findVirtual(api, "contains", MethodType.methodType(boolean.class, String.class, String.class))
                .bindTo(
                    api.getField("INSTANCE")
                        .get(null));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | LinkageError incompatible) {
            warn(incompatible);
            return null;
        }
    }

    public static boolean matches(String query, String... names) {
        String needle = query == null ? ""
            : query.trim()
                .toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            String text = EnumChatFormatting.getTextWithoutFormattingCodes(name)
                .toLowerCase(Locale.ROOT);
            if (text.contains(needle)) return true;
            if (contains != null) {
                try {
                    if ((boolean) contains.invokeExact(text, needle)) return true;
                } catch (Throwable failure) {
                    if (failure instanceof Error && !(failure instanceof LinkageError)) throw (Error) failure;
                    contains = null;
                    warn(failure);
                }
            }
        }
        return false;
    }

    private static void warn(Throwable cause) {
        LogManager.getLogger("gtswn")
            .warn("NEC-Rework name search unavailable; using literal search", cause);
    }
}
