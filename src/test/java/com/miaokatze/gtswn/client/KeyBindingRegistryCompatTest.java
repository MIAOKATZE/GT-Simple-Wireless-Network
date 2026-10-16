package com.miaokatze.gtswn.client;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class KeyBindingRegistryCompatTest {

    @Test
    public void registrationDuringInputBreaksOldListButPreservesProtectedInput() throws InterruptedException {
        List<String> original = new ArrayList<>(Arrays.asList("first", "second"));
        try {
            processInputDuringRegistration(original);
            fail("The old ArrayList must reproduce the reported input iteration failure");
        } catch (ConcurrentModificationException expected) {
            assertEquals(Arrays.asList("first", "second", "late"), original);
        }

        List<String> protectedList = KeyBindingRegistryCompat
            .protectRegistrationList(new ArrayList<>(Arrays.asList("first", "second")));
        assertEquals(Arrays.asList("first", "second"), processInputDuringRegistration(protectedList));
        assertEquals(Arrays.asList("first", "second", "late"), new ArrayList<>(protectedList));
    }

    @Test
    public void initializationPreservesExistingKeyObjectsAndOrder() {
        Object first = new Object();
        Object second = new Object();
        List<Object> protectedList = KeyBindingRegistryCompat
            .protectRegistrationList(new ArrayList<>(Arrays.asList(first, second)));
        assertSame(first, protectedList.get(0));
        assertSame(second, protectedList.get(1));
        assertSame(protectedList, KeyBindingRegistryCompat.protectRegistrationList(protectedList));
    }

    @Test
    public void listRegistrationResetAndSortingOperationsRemainSupported() {
        List<String> protectedList = KeyBindingRegistryCompat
            .protectRegistrationList(new ArrayList<>(Arrays.asList("b", "a")));
        Collections.sort(protectedList);
        assertEquals(Arrays.asList("a", "b"), protectedList);
        protectedList.set(1, "c");
        protectedList.remove("a");
        protectedList.add(0, "d");
        assertEquals(Arrays.asList("d", "c"), protectedList);
        protectedList.clear();
        protectedList.add("reset");
        assertEquals(Collections.singletonList("reset"), protectedList);
    }

    private static List<String> processInputDuringRegistration(List<String> registrations) throws InterruptedException {
        Iterator<String> input = registrations.iterator();
        List<String> handled = new ArrayList<>();
        handled.add(input.next());
        AtomicReference<Throwable> registrationFailure = new AtomicReference<>();
        Thread registrar = new Thread(() -> {
            try {
                registrations.add("late");
            } catch (Throwable failure) {
                registrationFailure.set(failure);
            }
        }, "gtswn-key-registration-test");
        registrar.setDaemon(true);
        registrar.start();
        registrar.join(5000);
        assertFalse("Registration thread did not finish", registrar.isAlive());
        assertNull(registrationFailure.get());
        while (input.hasNext()) {
            handled.add(input.next());
        }
        return handled;
    }
}
