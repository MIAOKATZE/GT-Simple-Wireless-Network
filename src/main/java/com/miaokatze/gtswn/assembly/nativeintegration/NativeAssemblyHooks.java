package com.miaokatze.gtswn.assembly.nativeintegration;

import java.util.HashSet;
import java.util.Set;

/** Application evidence supplied by the mixin plugin, rather than class-presence guesses. */
public final class NativeAssemblyHooks {

    private static final String PREFIX = "com.miaokatze.gtswn.mixins.gregtech.";
    private static final Set<String> APPLIED = new HashSet<>();
    private static final String[] REQUIRED = { "OrderedAssemblyControllerMixin", "OrderedAssemblyRegistrationMixin",
        "OrderedAssemblyLifecycleMixin" };

    private NativeAssemblyHooks() {}

    public static synchronized void applied(String mixinClassName) {
        for (String required : REQUIRED) {
            if (mixinClassName.equals(PREFIX + required)) APPLIED.add(required);
        }
    }

    public static synchronized boolean ready() {
        for (String required : REQUIRED) {
            if (!APPLIED.contains(required)) return false;
        }
        return true;
    }

    public static synchronized String missing() {
        StringBuilder missing = new StringBuilder();
        for (String required : REQUIRED) {
            if (APPLIED.contains(required)) continue;
            if (missing.length() > 0) missing.append(", ");
            missing.append(required);
        }
        return missing.toString();
    }
}
