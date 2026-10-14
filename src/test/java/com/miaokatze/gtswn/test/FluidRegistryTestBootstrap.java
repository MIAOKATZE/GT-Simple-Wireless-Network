package com.miaokatze.gtswn.test;

import java.lang.reflect.Field;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.Loader;

/** Minimal headless preconditions for the real Forge fluid registry, without a LaunchClassLoader. */
public final class FluidRegistryTestBootstrap {

    private static boolean initialized;

    private FluidRegistryTestBootstrap() {}

    public static synchronized FluidStack water(int amount) {
        if (!initialized) initialize();
        return new FluidStack(FluidRegistry.WATER, amount);
    }

    private static void initialize() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field singleton = unsafeClass.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            Object unsafe = singleton.get(null);
            Field water = Blocks.class.getDeclaredField("water");
            Field lava = Blocks.class.getDeclaredField("lava");
            Field loader = Loader.class.getDeclaredField("instance");
            water.setAccessible(true);
            lava.setAccessible(true);
            loader.setAccessible(true);
            Object previousWater = water.get(null), previousLava = lava.get(null), previousLoader = loader.get(null);
            try {
                if (previousWater == null)
                    set(unsafe, unsafeClass, water, new NativeFluidBlock(Material.water, "water"));
                if (previousLava == null) set(unsafe, unsafeClass, lava, new NativeFluidBlock(Material.lava, "lava"));
                if (previousLoader == null) {
                    // uniqueName only calls activeModContainer(), for which an absent controller is valid.
                    Object headlessLoader = unsafeClass.getMethod("allocateInstance", Class.class)
                        .invoke(unsafe, Loader.class);
                    set(unsafe, unsafeClass, loader, headlessLoader);
                }
                if (FluidRegistry.WATER == null || FluidRegistry.LAVA == null)
                    throw new AssertionError("Native fluid registry bootstrap failed");
                initialized = true;
            } finally {
                set(unsafe, unsafeClass, water, previousWater);
                set(unsafe, unsafeClass, lava, previousLava);
                set(unsafe, unsafeClass, loader, previousLoader);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Headless fluid registry preconditions failed", failure);
        }
    }

    private static void set(Object unsafe, Class<?> type, Field field, Object value)
        throws ReflectiveOperationException {
        Object base = type.getMethod("staticFieldBase", Field.class)
            .invoke(unsafe, field);
        long offset = (long) type.getMethod("staticFieldOffset", Field.class)
            .invoke(unsafe, field);
        type.getMethod("putObjectVolatile", Object.class, long.class, Object.class)
            .invoke(unsafe, base, offset, value);
    }

    private static final class NativeFluidBlock extends Block {

        NativeFluidBlock(Material material, String name) {
            super(material);
            setBlockName(name);
        }
    }
}
