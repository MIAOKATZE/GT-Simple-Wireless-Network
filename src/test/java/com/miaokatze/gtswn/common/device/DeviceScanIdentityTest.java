package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.Test;

public class DeviceScanIdentityTest {

    @Test
    public void replacementOwnershipTransferAndTeamDepartureExpireACapturedMachine() {
        Object tile = new Object(), machine = new Object();
        UUID owner = UUID.randomUUID(), stranger = UUID.randomUUID();
        Set<UUID> team = Collections.singleton(owner);
        assertTrue(DeviceScanManager.sameScanMachine(tile, machine, 123, owner, tile, machine, 123, owner, team));
        assertFalse(
            DeviceScanManager.sameScanMachine(tile, machine, 123, owner, new Object(), machine, 123, owner, team));
        assertFalse(DeviceScanManager.sameScanMachine(tile, machine, 123, owner, tile, new Object(), 123, owner, team));
        assertFalse(DeviceScanManager.sameScanMachine(tile, machine, 123, owner, tile, machine, 124, owner, team));
        assertFalse(DeviceScanManager.sameScanMachine(tile, machine, 123, owner, tile, machine, 123, stranger, team));
        assertFalse(
            DeviceScanManager.sameScanMachine(
                tile,
                machine,
                123,
                owner,
                tile,
                machine,
                123,
                owner,
                Collections.singleton(stranger)));
    }

    @Test
    public void aStaleOwnRegistryEntryCannotOverrideALoadedForeignOrNonworkingMachine() throws Exception {
        UUID owner = UUID.randomUUID();
        Class<?> stateType = Class.forName(DeviceScanManager.class.getName() + "$ScanState");
        Class<?> candidateType = Class.forName(DeviceScanManager.class.getName() + "$Candidate");
        java.lang.reflect.Constructor<?> stateConstructor = stateType
            .getDeclaredConstructor(net.minecraft.entity.player.EntityPlayerMP.class, UUID.class, boolean.class);
        java.lang.reflect.Constructor<?> candidateConstructor = candidateType.getDeclaredConstructor(
            String.class,
            String.class,
            int.class,
            int.class,
            int.class,
            int.class,
            UUID.class,
            boolean.class,
            boolean.class,
            boolean.class,
            int.class);
        stateConstructor.setAccessible(true);
        candidateConstructor.setAccessible(true);
        java.lang.reflect.Method merge = DeviceScanManager.class.getDeclaredMethod(
            "mergeScan",
            stateType,
            int.class,
            Set.class,
            java.util.Map.class,
            Set.class,
            List.class,
            int.class,
            Set.class);
        merge.setAccessible(true);
        for (boolean foreign : new boolean[] { false, true }) {
            Object candidate = candidateConstructor.newInstance(
                "0:1:64:2",
                "Loaded",
                0,
                1,
                64,
                2,
                foreign ? UUID.randomUUID() : owner,
                foreign,
                false,
                true,
                123);
            Object result = merge.invoke(
                null,
                stateConstructor.newInstance(null, UUID.randomUUID(), false),
                0,
                Collections.singleton(owner),
                Collections
                    .singletonMap("0:1:64:2", new DeviceRegistryData.DeviceEntry(owner, "Stale own machine", 123)),
                Collections.emptySet(),
                Collections.singletonList(candidate),
                10,
                Collections.emptySet());
            java.lang.reflect.Field candidates = result.getClass()
                .getDeclaredField("candidates");
            candidates.setAccessible(true);
            assertTrue(((List<?>) candidates.get(result)).isEmpty());
        }
    }
}
