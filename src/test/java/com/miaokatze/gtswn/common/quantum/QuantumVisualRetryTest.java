package com.miaokatze.gtswn.common.quantum;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;

import org.junit.Test;

import com.miaokatze.gtswn.network.telemetry.TelemetryTransport.Admission;

public class QuantumVisualRetryTest {

    @Test
    public void onlyRejectedRecipientsRemainForRetryWithoutAnotherWorldChange() throws Exception {
        Set<EntityPlayerMP> retry = new HashSet<>();
        // The retry transition only needs recipient identity, not a running world/channel.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        EntityPlayerMP player = (EntityPlayerMP) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), EntityPlayerMP.class);
        player.setEntityId(1301);
        QuantumIncorporationVisualSync.retainRejected(retry, player, Admission.REJECTED);
        assertTrue(retry.contains(player));
        QuantumIncorporationVisualSync.retainRejected(retry, player, Admission.REJECTED);
        assertEquals(1, retry.size());
        QuantumIncorporationVisualSync.retainRejected(retry, player, Admission.QUEUED);
        assertTrue(retry.isEmpty());
        QuantumIncorporationVisualSync.retainRejected(retry, player, Admission.REJECTED);
        QuantumIncorporationVisualSync.retainRejected(retry, player, Admission.SENT);
        assertTrue(retry.isEmpty());
    }
}
