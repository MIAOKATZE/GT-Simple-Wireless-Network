package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;

import net.minecraft.world.WorldServer;

import org.junit.Test;

public class DeviceSamplerSessionLifecycleTest {

    @Test
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void serverStopDropsWorldAndEveryTransientQueueWithoutOpeningAnotherWorld() throws Exception {
        DeviceSampleScheduler[] schedulers = { new DeviceSampleScheduler(), new DeviceSampleScheduler() };
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeType.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object world = unsafeType.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), WorldServer.class);
        for (DeviceSampleScheduler scheduler : schedulers) {
            field("samplingWorld").set(scheduler, world);
            field("intervalCounter").setInt(scheduler, 19);
            ((Collection) field("workQueue").get(scheduler)).add("0:1:2:3");
            ((Collection) field("samplingTerminals").get(scheduler)).add(null);
            ((Map) field("fifoStates").get(scheduler)).put("test", null);
        }
        DeviceSampleScheduler.clearSession();
        for (DeviceSampleScheduler scheduler : schedulers) {
            assertNull(field("samplingWorld").get(scheduler));
            assertEquals(0, field("intervalCounter").getInt(scheduler));
            assertTrue(((Collection) field("workQueue").get(scheduler)).isEmpty());
            assertTrue(((Collection) field("samplingTerminals").get(scheduler)).isEmpty());
            assertTrue(((Map) field("fifoStates").get(scheduler)).isEmpty());
        }
    }

    private static Field field(String name) throws Exception {
        Field field = DeviceSampleScheduler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
