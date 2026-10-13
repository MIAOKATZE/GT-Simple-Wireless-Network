package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.util.UUID;

import org.junit.Test;

public class DeviceScanForceWindowTest {

    @Test
    public void forceWindowIsSingleUseAndScopedToPlayerAndTerminal() {
        UUID player = UUID.randomUUID(), terminal = UUID.randomUUID();
        DeviceScanManager.openForceWindow(player, terminal, 100);
        assertFalse(DeviceScanManager.consumeForceWindow(UUID.randomUUID(), terminal, 101));
        assertFalse(DeviceScanManager.consumeForceWindow(player, UUID.randomUUID(), 101));
        assertTrue(DeviceScanManager.consumeForceWindow(player, terminal, 399));
        assertFalse(DeviceScanManager.consumeForceWindow(player, terminal, 399));
    }

    @Test
    public void deadlineAndServerCleanupPreventAnOldForceScan() {
        UUID player = UUID.randomUUID(), terminal = UUID.randomUUID();
        DeviceScanManager.openForceWindow(player, terminal, 100);
        assertFalse(DeviceScanManager.consumeForceWindow(player, terminal, 400));
        DeviceScanManager.openForceWindow(player, terminal, 500);
        DeviceScanManager.clear();
        assertFalse(DeviceScanManager.consumeForceWindow(player, terminal, 501));
    }

    @Test
    public void scanCountsBoundIgnoredMachinesAndForceIncludesOnlyNewCandidates() throws Exception {
        Class<?> stateType = Class.forName(DeviceScanManager.class.getName() + "$ScanState");
        Class<?> candidateType = Class.forName(DeviceScanManager.class.getName() + "$Candidate");
        java.lang.reflect.Constructor<?> stateConstructor = stateType
            .getDeclaredConstructor(net.minecraft.entity.player.EntityPlayerMP.class, UUID.class, boolean.class);
        stateConstructor.setAccessible(true);
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
            boolean.class);
        candidateConstructor.setAccessible(true);
        UUID owner = UUID.randomUUID(), terminal = UUID.randomUUID();
        java.util.List<Object> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) candidates.add(
            candidateConstructor.newInstance(
                "0:" + i + ":64:0",
                "Machine",
                0,
                i,
                64,
                0,
                i == 3 ? UUID.randomUUID() : owner,
                true,
                false,
                true));
        java.util.Set<String> ignored = new java.util.HashSet<>(
            java.util.Arrays.asList("0:0:64:0", "0:1:64:0", "0:3:64:0"));
        java.lang.reflect.Method merge = DeviceScanManager.class.getDeclaredMethod(
            "mergeScan",
            stateType,
            int.class,
            java.util.Set.class,
            java.util.Map.class,
            java.util.Set.class,
            java.util.List.class,
            int.class,
            java.util.Set.class);
        merge.setAccessible(true);
        for (boolean force : new boolean[] { false, true }) {
            Object state = stateConstructor.newInstance(null, terminal, force);
            Object result = merge.invoke(
                null,
                state,
                0,
                java.util.Collections.singleton(owner),
                java.util.Collections.emptyMap(),
                java.util.Collections.singleton("0:0:64:0"),
                candidates,
                10,
                ignored);
            java.lang.reflect.Field count = result.getClass()
                .getDeclaredField("totalIgnored");
            count.setAccessible(true);
            assertEquals(2, count.getInt(result));
            java.lang.reflect.Field entries = result.getClass()
                .getDeclaredField("candidates");
            entries.setAccessible(true);
            assertEquals(force ? 2 : 1, ((java.util.List<?>) entries.get(result)).size());
        }
    }
}
