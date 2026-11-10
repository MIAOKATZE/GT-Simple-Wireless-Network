package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.After;
import org.junit.Test;

public class DeviceScanSessionTest {

    @After
    public void clear() {
        DeviceScanManager.clear();
    }

    @Test
    public void workerFinishingAfterClearCannotRetainOldResultAndNewSessionStillPublishes() throws Exception {
        Object oldResult = result();
        CountDownLatch ready = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            ready.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Worker not released");
                publish(oldResult);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            DeviceScanManager.clear();
        } finally {
            release.countDown();
            worker.join(5000);
        }
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        assertTrue(results().isEmpty());
        Object fresh = result();
        publish(fresh);
        assertSame(fresh, results().poll());
    }

    @Test
    public void clearRemovesAlreadyPublishedResultsAndCancellationRejectsPublication() throws Exception {
        Object queued = result();
        publish(queued);
        assertEquals(1, results().size());
        DeviceScanManager.clear();
        assertTrue(results().isEmpty());
        Object cancelled = result();
        Object state = field(cancelled.getClass(), "state").get(cancelled);
        field(state.getClass(), "cancelled").setBoolean(state, true);
        publish(cancelled);
        assertTrue(results().isEmpty());
    }

    private static Object result() throws Exception {
        Class<?> stateType = Class.forName(DeviceScanManager.class.getName() + "$ScanState");
        Constructor<?> state = stateType.getDeclaredConstructor(EntityPlayerMP.class, UUID.class);
        state.setAccessible(true);
        Class<?> resultType = Class.forName(DeviceScanManager.class.getName() + "$ScanResult");
        Constructor<?> result = resultType.getDeclaredConstructor(stateType, int.class, int.class, List.class);
        result.setAccessible(true);
        return result.newInstance(state.newInstance(null, UUID.randomUUID()), 0, 0, Collections.emptyList());
    }

    private static void publish(Object result) throws Exception {
        Method publish = DeviceScanManager.class.getDeclaredMethod("publishResult", result.getClass());
        publish.setAccessible(true);
        publish.invoke(null, result);
    }

    private static Queue<?> results() throws Exception {
        return (Queue<?>) field(DeviceScanManager.class, "RESULTS").get(null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
