package com.miaokatze.gtswn.common.quantum;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;

/** Palette indices are persisted; zero preserves the original purple appearance. */
public final class QuantumNetworkColor {

    public static final int DEFAULT = 0;
    public static final int COUNT = 16;
    public static final String NBT_COLOR = "QT_Color";
    private static final int[] RGB = { 0xAE63FF, 0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA,
        0x474F52, 0x9D9D97, 0x169C9C, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21 };
    private static final String[] NAMES = { "purple", "white", "orange", "magenta", "lightBlue", "yellow", "lime",
        "pink", "gray", "lightGray", "cyan", "blue", "brown", "green", "red", "black" };

    private QuantumNetworkColor() {}

    public static String name(int index) {
        return NAMES[normalize(index)];
    }

    public static boolean isValid(int index) {
        return index >= 0 && index < COUNT;
    }

    public static int normalize(int index) {
        return isValid(index) ? index : DEFAULT;
    }

    public static int rgb(int index) {
        return RGB[normalize(index)];
    }

    public static int get(ItemStack stack) {
        return stack == null || stack.getTagCompound() == null ? DEFAULT
            : normalize(
                stack.getTagCompound()
                    .getInteger(NBT_COLOR));
    }

    public static void set(ItemStack stack, int index) {
        if (stack == null) return;
        if (stack.getTagCompound() == null) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setInteger(NBT_COLOR, normalize(index));
    }

    public static int resolve(ItemStack stack) {
        int[] anchor = ItemNetworkQuantumTerminal.getAnchor(stack);
        if (anchor == null) return get(stack);
        WorldServer world = DimensionManager.getWorld(anchor[0]);
        return world == null ? get(stack)
            : QuantumNetworkColorRegistry.get(world)
                .color(anchor[1], anchor[2], anchor[3]);
    }

    public static int resolve(int dim, int x, int y, int z) {
        WorldServer world = DimensionManager.getWorld(dim);
        return world == null ? DEFAULT
            : QuantumNetworkColorRegistry.get(world)
                .color(x, y, z);
    }

    public static void bind(ItemStack stack, World world, int x, int y, int z) {
        QuantumNetworkColorRegistry registry = QuantumNetworkColorRegistry.get(world);
        int previous = registry.color(x, y, z);
        registry.initialize(world, x, y, z, get(stack));
        int color = registry.color(x, y, z);
        set(stack, color);
        if (previous != color)
            QuantumNetworkColorSync.changed(world.provider.dimensionId, registry.structure(world, x, y, z), color);
    }
}
