package com.miaokatze.gtswn.common.testworld;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore;
import com.miaokatze.gtswn.common.device.DeviceTerminalDataStore.TerminalData;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkSecurityRegistry;

/** Exact fixture state access, absent from the production artifact. Never a gameplay permission hook. */
final class TestFixtureAccess {

    private TestFixtureAccess() {}

    private static void requireRunning(World world) {
        if (!TestDimension.isTest(world) || !TestWorldManager.isRunning(world))
            throw new IllegalStateException("Fixture access requires a running isolated test scenario");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass()
                .getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported fixture state: " + name, failure);
        }
    }

    private static void serialize(TerminalData data, String method, NBTTagCompound nbt) {
        try {
            java.lang.reflect.Method operation = TerminalData.class.getDeclaredMethod(method, NBTTagCompound.class);
            operation.setAccessible(true);
            operation.invoke(data, nbt);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Unsupported terminal fixture serialization: " + method, failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, TerminalData> terminals(DeviceTerminalDataStore store) {
        return (Map<UUID, TerminalData>) field(store, "terminals");
    }

    static NBTTagCompound snapshotTerminal(DeviceTerminalDataStore store, World world, UUID id) {
        requireRunning(world);
        TerminalData data = terminals(store).get(id);
        if (data == null) return null;
        NBTTagCompound result = new NBTTagCompound();
        serialize(data, "writeToNBT", result);
        return result;
    }

    static void restoreTerminal(DeviceTerminalDataStore store, World world, UUID id, NBTTagCompound snapshot) {
        requireRunning(world);
        if (snapshot == null) terminals(store).remove(id);
        else {
            TerminalData data = new TerminalData();
            serialize(data, "readFromNBT", snapshot);
            terminals(store).put(id, data);
        }
        store.markDirty();
    }

    static void removeUiKitTerminal(DeviceTerminalDataStore store, EntityPlayer player, World origin, UUID id) {
        if (!TestUiKit.ownsTerminal(player, origin, id))
            throw new IllegalStateException("UI terminal cleanup requires its exact persistent kit scope");
        if (terminals(store).remove(id) != null) store.markDirty();
    }

    @SuppressWarnings("unchecked")
    static void forgetProvider(QuantumNetworkSecurityRegistry registry, World world, long key) {
        requireRunning(world);
        if (registry != QuantumNetworkSecurityRegistry.providerRegistry(world))
            throw new IllegalStateException("Provider cleanup requires the original fixture registry");
        if (((Set<Long>) field(registry, "providerKeys")).remove(key)) registry.markDirty();
    }
}
