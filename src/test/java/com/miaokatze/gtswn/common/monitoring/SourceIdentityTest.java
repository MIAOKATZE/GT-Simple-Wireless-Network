package com.miaokatze.gtswn.common.monitoring;

import static org.junit.Assert.*;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.Test;

import com.miaokatze.gtswn.common.device.DeviceRegistryData;

public class SourceIdentityTest {

    private static final class NativeTile extends net.minecraft.tileentity.TileEntity implements MonitorDeviceIdentity {

        private String identity = "";

        @Override
        public String gtswn$getMonitorDeviceIdentity() {
            return identity;
        }

        @Override
        public void gtswn$setMonitorDeviceIdentity(String value) {
            identity = value;
        }
    }

    @Test
    public void registeredReplacementNeverReusesSameCoordinateIdentity() {
        DeviceRegistryData registry = new DeviceRegistryData("test");
        UUID owner = UUID.randomUUID();
        String key = "0:1:64:2";
        registry.registerPlacement(key, owner, "machine", 123);
        UUID initial = registry.getEntry(key).sourceId;
        registry.register(key, owner, "renamed", 123);
        assertEquals(initial, registry.getEntry(key).sourceId);
        NBTTagCompound tag = new NBTTagCompound();
        registry.writeToNBT(tag);
        DeviceRegistryData restored = new DeviceRegistryData("restored");
        restored.readFromNBT(tag);
        assertEquals(initial, restored.getEntry(key).sourceId);
        restored.registerPlacement(key, owner, "renamed", 123);
        assertNotEquals(initial, restored.getEntry(key).sourceId);
    }

    @Test
    public void missingPlacementEventStillChangesNativeTileIdentity() {
        DeviceRegistryData registry = new DeviceRegistryData("test");
        UUID owner = UUID.randomUUID();
        String key = "0:1:64:2";
        registry.register(key, owner, "machine", 123);
        net.minecraft.tileentity.TileEntity original = new NativeTile();
        UUID first = registry.bindLiveInstance(key, original).sourceId;
        assertEquals(first, registry.bindLiveInstance(key, original).sourceId);
        net.minecraft.tileentity.TileEntity replaced = new NativeTile();
        assertNotEquals(first, registry.bindLiveInstance(key, replaced).sourceId);
    }

    @Test
    public void missingNativeIdentityInstrumentationFailsClosed() {
        DeviceRegistryData registry = new DeviceRegistryData("test");
        registry.register("0:1:64:2", UUID.randomUUID(), "machine", 123);
        assertNull(registry.bindLiveInstance("0:1:64:2", new net.minecraft.tileentity.TileEntity()));
    }

    @Test
    public void sourceIdentityIsNotPlayerOrCoordinateAndCannotJoinEpochs() {
        UUID epoch = UUID.randomUUID();
        SourceKey source = new SourceKey(SourceKey.Kind.DIT, epoch, "native", 1);
        assertEquals(source, SourceKey.read(source.write()));
        assertNotEquals(source, new SourceKey(SourceKey.Kind.DIT, epoch, "native", 2));
        assertNotEquals(source, new SourceKey(SourceKey.Kind.AE, epoch, "native", 1));
        assertNotEquals(source, new SourceKey(SourceKey.Kind.DIT, UUID.randomUUID(), "native", 1));
    }

}
