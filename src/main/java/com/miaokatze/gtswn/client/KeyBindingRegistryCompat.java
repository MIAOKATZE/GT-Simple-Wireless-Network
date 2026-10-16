package com.miaokatze.gtswn.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps input iteration stable when a key is registered after client initialization. */
public final class KeyBindingRegistryCompat {

    private KeyBindingRegistryCompat() {}

    /** Called once during registry initialization; input iteration itself allocates no list snapshot. */
    public static <T> List<T> protectRegistrationList(List<T> registrations) {
        return registrations instanceof CopyOnWriteArrayList ? registrations
            : new CopyOnWriteArrayList<>(registrations);
    }
}
