package com.miaokatze.gtswn.common.quantum;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import com.miaokatze.gtswn.common.util.SavedDataUtil;

/** A security enrollment survives removing the security station and unloading its network. */
public final class QuantumNetworkSecurityRegistry extends WorldSavedData {

    private static final String NAME = "gtswn_quantum_security";
    private final Set<Long> enrolled = new HashSet<>();
    private final Map<Long, String> identities = new HashMap<>();
    private final Set<Long> providerKeys = new HashSet<>();

    public QuantumNetworkSecurityRegistry(String name) {
        super(name);
    }

    public static QuantumNetworkSecurityRegistry get(World world) {
        return SavedDataUtil
            .loadOrCreate(world, NAME, QuantumNetworkSecurityRegistry.class, QuantumNetworkSecurityRegistry::new);
    }

    /** Native station keys are global, including stations encoded before their grid has any controllers. */
    public static QuantumNetworkSecurityRegistry providerRegistry(World world) {
        WorldServer overworld = DimensionManager.getWorld(0);
        return get(overworld == null ? world : overworld);
    }

    public boolean isProviderEnrolled(long nativeKey) {
        return providerKeys.contains(nativeKey);
    }

    public void enrollProvider(long nativeKey) {
        if (providerKeys.add(nativeKey)) markDirty();
    }

    public boolean isEnrolled(Set<Long> members) {
        for (long member : members) if (enrolled.contains(member)) return true;
        return false;
    }

    /** A replaced controller cannot inherit an enrollment, including controllers enrolled before quantization. */
    public boolean isEnrolled(World world, Set<Long> members) {
        for (long member : members) {
            if (!enrolled.contains(member) || !world.blockExists(
                QuantumControllerRegistry.unpackX(member),
                QuantumControllerRegistry.unpackY(member),
                QuantumControllerRegistry.unpackZ(member))) continue;
            TileEntity tile = world.getTileEntity(
                QuantumControllerRegistry.unpackX(member),
                QuantumControllerRegistry.unpackY(member),
                QuantumControllerRegistry.unpackZ(member));
            String saved = identities.getOrDefault(member, "");
            if (!saved.isEmpty() && (!(tile instanceof QuantumControllerIdentity)
                || !saved.equals(((QuantumControllerIdentity) tile).gtswn$getControllerIdentity()))) {
                enrolled.remove(member);
                identities.remove(member);
                markDirty();
            }
        }
        return isEnrolled(members);
    }

    public void enroll(Set<Long> members) {
        if (enrolled.addAll(members)) markDirty();
    }

    public void enroll(World world, Set<Long> members) {
        enroll(members);
        for (long member : members) {
            int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                z = QuantumControllerRegistry.unpackZ(member);
            if (!world.blockExists(x, y, z)) continue;
            TileEntity tile = world.getTileEntity(x, y, z);
            if (!(tile instanceof QuantumControllerIdentity)) continue;
            QuantumControllerIdentity identity = (QuantumControllerIdentity) tile;
            if (identity.gtswn$getControllerIdentity()
                .isEmpty()) {
                identity.gtswn$setControllerIdentity(
                    UUID.randomUUID()
                        .toString());
                tile.markDirty();
            }
            String current = identity.gtswn$getControllerIdentity();
            if (!current.equals(identities.put(member, current))) markDirty();
        }
    }

    public void remove(Set<Long> members) {
        if (enrolled.removeAll(members)) {
            for (long member : members) identities.remove(member);
            markDirty();
        }
    }

    /** Move the latch, never weaken an already enrolled destination. No security station means locked offline. */
    public static void transfer(World sourceWorld, Set<Long> sourceMembers, World targetWorld,
        Set<Long> targetMembers) {
        QuantumNetworkSecurityRegistry source = get(sourceWorld);
        QuantumNetworkSecurityRegistry target = get(targetWorld);
        boolean required = source.isEnrolled(sourceWorld, sourceMembers)
            || target.isEnrolled(targetWorld, targetMembers);
        source.remove(sourceMembers);
        if (required) target.enroll(targetWorld, targetMembers);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        enrolled.clear();
        identities.clear();
        providerKeys.clear();
        NBTTagList providers = tag.getTagList("providers", 10);
        for (int i = 0; i < providers.tagCount(); i++) providerKeys.add(
            providers.getCompoundTagAt(i)
                .getLong("key"));
        int[] positions = tag.getIntArray("enrolled");
        for (int i = 0; i + 2 < positions.length; i += 3)
            enrolled.add(QuantumControllerRegistry.pack(positions[i], positions[i + 1], positions[i + 2]));
        NBTTagList list = tag.getTagList("identities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            identities.put(entry.getLong("position"), entry.getString("identity"));
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        NBTTagList providers = new NBTTagList();
        for (long nativeKey : providerKeys) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("key", nativeKey);
            providers.appendTag(entry);
        }
        tag.setTag("providers", providers);
        int[] positions = new int[enrolled.size() * 3];
        int index = 0;
        for (long member : enrolled) {
            positions[index++] = QuantumControllerRegistry.unpackX(member);
            positions[index++] = QuantumControllerRegistry.unpackY(member);
            positions[index++] = QuantumControllerRegistry.unpackZ(member);
        }
        tag.setIntArray("enrolled", positions);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, String> identity : identities.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("position", identity.getKey());
            entry.setString("identity", identity.getValue());
            list.appendTag(entry);
        }
        tag.setTag("identities", list);
    }
}
