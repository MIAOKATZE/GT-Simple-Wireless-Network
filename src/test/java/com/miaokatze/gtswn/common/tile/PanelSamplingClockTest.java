package com.miaokatze.gtswn.common.tile;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.DimensionManager;

import org.junit.Test;

public class PanelSamplingClockTest {

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
    }

    private static WorldServer world(long tick) throws Exception {
        WorldServer world = allocate(WorldServer.class);
        NBTTagCompound data = new NBTTagCompound();
        data.setLong("Time", tick);
        Field info = World.class.getDeclaredField("worldInfo");
        info.setAccessible(true);
        info.set(world, new WorldInfo(data));
        return world;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void retainedOldWorldCannotSupplyTheNewServersFirstObservationClock() throws Exception {
        Field singleton = null;
        for (Field field : MinecraftServer.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == MinecraftServer.class) {
                singleton = field;
                singleton.setAccessible(true);
                break;
            }
        }
        assertNotNull(singleton);
        Field loadedField = DimensionManager.class.getDeclaredField("worlds");
        loadedField.setAccessible(true);
        Map<Integer, WorldServer> loaded = (Map<Integer, WorldServer>) loadedField.get(null);
        Object previousServer = singleton.get(null);
        WorldServer previousWorld = loaded.get(0);
        Method clock = TileEntityNetworkInfoPanel.class.getDeclaredMethod("cachedOverworldTick");
        clock.setAccessible(true);
        WorldServer old = world(100_000), current = world(20);
        DedicatedServer first = allocate(DedicatedServer.class), second = allocate(DedicatedServer.class);
        first.worldServers = new WorldServer[] { old };
        second.worldServers = new WorldServer[] { current };
        try {
            singleton.set(null, first);
            loaded.put(0, old);
            assertEquals(100_000L, ((Long) clock.invoke(null)).longValue());
            singleton.set(null, second);
            loaded.put(0, current);
            assertEquals(20L, ((Long) clock.invoke(null)).longValue());
            assertEquals("Keep the former world strongly reachable during the entire assertion", 100_000L,
                old.getTotalWorldTime());
            loaded.remove(0);
            assertEquals("Missing loaded overworld must not trigger dimension initialization", -1L,
                ((Long) clock.invoke(null)).longValue());
            singleton.set(null, null);
            assertEquals(-1L, ((Long) clock.invoke(null)).longValue());
        } finally {
            singleton.set(null, previousServer);
            if (previousWorld == null) loaded.remove(0);
            else loaded.put(0, previousWorld);
        }
    }
}
