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

import appeng.tile.networking.TileController;

/** A security enrollment survives removing the security station and unloading its network. */
public final class QuantumNetworkSecurityRegistry extends WorldSavedData {

    private static final String NAME = "gtswn_quantum_security";
    private final Set<Long> enrolled = new HashSet<>();
    private final Map<Long, String> identities = new HashMap<>();
    private final Set<Long> providerKeys = new HashSet<>();
    private final Set<Long> safetyDisabled = new HashSet<>();
    private final Map<Long, Set<Long>> trustedProviders = new HashMap<>();

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
                safetyDisabled.remove(member);
                trustedProviders.remove(member);
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
        boolean changed = enrolled.removeAll(members) | safetyDisabled.removeAll(members);
        for (long member : members) {
            changed |= identities.remove(member) != null;
            changed |= trustedProviders.remove(member) != null;
        }
        if (changed) markDirty();
    }

    public boolean isSafetyEnabled(World world, Set<Long> members) {
        isEnrolled(world, members);
        for (long member : members) {
            if (!safetyDisabled.contains(member)) continue;
            if (hasSafetyIdentity(world, member)) return false;
            safetyDisabled.remove(member);
            trustedProviders.remove(member);
            markDirty();
        }
        return true;
    }

    private boolean hasSafetyIdentity(World world, long member) {
        String saved = identities.getOrDefault(member, "");
        if (!enrolled.contains(member) || saved.isEmpty()) return false;
        int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
            z = QuantumControllerRegistry.unpackZ(member);
        if (!world.blockExists(x, y, z)) return true;
        TileEntity tile = world.getTileEntity(x, y, z);
        return tile instanceof TileController && tile instanceof QuantumControllerIdentity
            && saved.equals(((QuantumControllerIdentity) tile).gtswn$getControllerIdentity());
    }

    public Set<Long> trustedProviderKeys(World world, Set<Long> members) {
        isEnrolled(world, members);
        Set<Long> keys = new HashSet<>();
        for (long member : members) {
            if (safetyDisabled.contains(member) && hasSafetyIdentity(world, member)
                && trustedProviders.containsKey(member)) keys.addAll(trustedProviders.get(member));
        }
        return keys;
    }

    public void setSafety(World world, Set<Long> members, boolean enabled, Set<Long> providerKeys) {
        enroll(world, members);
        for (long member : members) {
            if (enabled) {
                safetyDisabled.remove(member);
                trustedProviders.remove(member);
            } else {
                safetyDisabled.add(member);
                trustedProviders.put(member, new HashSet<>(providerKeys));
            }
        }
        markDirty();
    }

    /** Move the latch, never weaken an already enrolled destination. No security station means locked offline. */
    public static void transfer(World sourceWorld, Set<Long> sourceMembers, World targetWorld,
        Set<Long> targetMembers) {
        QuantumNetworkSecurityRegistry source = get(sourceWorld);
        QuantumNetworkSecurityRegistry target = get(targetWorld);
        boolean sourceRequired = source.isEnrolled(sourceWorld, sourceMembers);
        boolean targetRequired = target.isEnrolled(targetWorld, targetMembers);
        boolean enabled = source.isSafetyEnabled(sourceWorld, sourceMembers)
            || targetRequired && target.isSafetyEnabled(targetWorld, targetMembers);
        Set<Long> trusted = source.trustedProviderKeys(sourceWorld, sourceMembers);
        trusted.addAll(target.trustedProviderKeys(targetWorld, targetMembers));
        boolean required = sourceRequired || targetRequired;
        source.remove(sourceMembers);
        if (required) target.setSafety(targetWorld, targetMembers, enabled, trusted);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        enrolled.clear();
        identities.clear();
        providerKeys.clear();
        safetyDisabled.clear();
        trustedProviders.clear();
        NBTTagList policies = tag.getTagList("localSafetyDisabled", 10);
        for (int i = 0; i < policies.tagCount(); i++) {
            NBTTagCompound policy = policies.getCompoundTagAt(i);
            long position = policy.getLong("position");
            safetyDisabled.add(position);
            Set<Long> keys = new HashSet<>();
            NBTTagList trusted = policy.getTagList("providers", 10);
            for (int j = 0; j < trusted.tagCount(); j++) keys.add(
                trusted.getCompoundTagAt(j)
                    .getLong("key"));
            trustedProviders.put(position, keys);
        }
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
        NBTTagList policies = new NBTTagList();
        for (long position : safetyDisabled) {
            NBTTagCompound policy = new NBTTagCompound();
            policy.setLong("position", position);
            NBTTagList trusted = new NBTTagList();
            for (long key : trustedProviders.getOrDefault(position, java.util.Collections.emptySet())) {
                NBTTagCompound entry = new NBTTagCompound();
                entry.setLong("key", key);
                trusted.appendTag(entry);
            }
            policy.setTag("providers", trusted);
            policies.appendTag(policy);
        }
        tag.setTag("localSafetyDisabled", policies);
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
