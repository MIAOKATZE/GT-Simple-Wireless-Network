package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/** Controller member keys remain stable across chunk unloads and different binding points. */
public final class QuantumNetworkColorRegistry extends WorldSavedData {

    private static final String NAME = "gtswn_quantum_colors";
    private final Map<Long, Integer> colors = new HashMap<>();

    public QuantumNetworkColorRegistry(String name) {
        super(name);
    }

    public static QuantumNetworkColorRegistry get(World world) {
        return SavedDataUtil
            .loadOrCreate(world, NAME, QuantumNetworkColorRegistry.class, QuantumNetworkColorRegistry::new);
    }

    public int color(int x, int y, int z) {
        return colors.getOrDefault(QuantumControllerRegistry.pack(x, y, z), 0);
    }

    public Set<Long> structure(World world, int x, int y, int z) {
        QuantumControllerRegistry controllers = QuantumControllerRegistry.get(world);
        Set<Long> result = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long start = QuantumControllerRegistry.pack(x, y, z);
        if (!controllers.isQuantized(start)) return result;
        result.add(start);
        queue.add(start);
        while (!queue.isEmpty() && result.size() < 512) {
            long key = queue.remove();
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                long next = QuantumControllerRegistry.pack(
                    QuantumControllerRegistry.unpackX(key) + side.offsetX,
                    QuantumControllerRegistry.unpackY(key) + side.offsetY,
                    QuantumControllerRegistry.unpackZ(key) + side.offsetZ);
                if (controllers.isQuantized(next) && result.add(next)) queue.add(next);
            }
        }
        return result;
    }

    public void initialize(World world, int x, int y, int z, int preferred) {
        Set<Long> members = structure(world, x, y, z);
        int index = QuantumNetworkColor.normalize(preferred);
        Long chosen = null;
        for (long key : members) if (colors.containsKey(key) && (chosen == null || key < chosen)) chosen = key;
        if (chosen != null) index = colors.get(chosen);
        boolean changed = false;
        for (long key : members) if (!Integer.valueOf(index)
            .equals(colors.put(key, index))) changed = true;
        if (changed) markDirty();
    }

    public void recolor(Set<Long> members, int index) {
        for (long key : members) colors.put(key, QuantumNetworkColor.normalize(index));
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        colors.clear();
        NBTTagList list = tag.getTagList("colors", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            colors.put(entry.getLong("pos"), QuantumNetworkColor.normalize(entry.getInteger("color")));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, Integer> entry : colors.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setLong("pos", entry.getKey());
            value.setInteger("color", entry.getValue());
            list.appendTag(value);
        }
        tag.setTag("colors", list);
    }
}
