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

import com.miaokatze.gtswn.common.testworld.TestDimension;
import com.miaokatze.gtswn.common.testworld.TestWorldManager;
import com.miaokatze.gtswn.common.util.SavedDataUtil;

import appeng.tile.networking.TileController;

/** Controller-owned safety policy; saved coordinates only mirror the corresponding tile generation. */
public final class QuantumNetworkSecurityRegistry extends WorldSavedData {

    private static final String NAME = "gtswn_quantum_security";
    private final Set<Long> enrolled = new HashSet<>();
    private final Map<Long, String> identities = new HashMap<>();
    private final Set<Long> providerKeys = new HashSet<>();
    private final Set<Long> safetyEnabled = new HashSet<>();
    private final Map<Long, Boolean> pendingSafety = new HashMap<>();

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

    /** Test runner cleanup: remove only a key its synthetic station introduced, never other saved security state. */
    public void forgetTestProvider(World testWorld, long nativeKey) {
        if (!TestDimension.isTest(testWorld) || !TestWorldManager.isRunning(testWorld)
            || this != providerRegistry(testWorld))
            throw new IllegalStateException("Provider cleanup requires an active isolated test scenario");
        if (providerKeys.remove(nativeKey)) markDirty();
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
                safetyEnabled.remove(member);
                pendingSafety.remove(member);
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
            if (!(tile instanceof TileController) || !(tile instanceof QuantumControllerIdentity)) continue;
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
        boolean changed = enrolled.removeAll(members) | safetyEnabled.removeAll(members);
        for (long member : members) {
            changed |= identities.remove(member) != null;
            changed |= pendingSafety.remove(member) != null;
        }
        if (changed) markDirty();
    }

    /** Loaded tile NBT is authoritative. Missing legacy fields are OFF, regardless of enrollment history. */
    public boolean isSafetyEnabled(World world, Set<Long> members) {
        boolean enabled = false;
        for (long member : members) {
            int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                z = QuantumControllerRegistry.unpackZ(member);
            if (!world.blockExists(x, y, z)) {
                enabled |= safetyEnabled.contains(member) && identities.containsKey(member);
                continue;
            }
            TileEntity tile = world.getTileEntity(x, y, z);
            if (!(tile instanceof TileController) || !(tile instanceof QuantumControllerIdentity)) {
                remove(java.util.Collections.singleton(member));
                continue;
            }
            QuantumControllerIdentity identity = (QuantumControllerIdentity) tile;
            String saved = identities.getOrDefault(member, "");
            if (!saved.isEmpty() && !saved.equals(identity.gtswn$getControllerIdentity())) {
                safetyEnabled.remove(member);
                identities.remove(member);
                enrolled.remove(member);
                markDirty();
            }
            Boolean pending = pendingSafety.remove(member);
            if (pending != null && !saved.isEmpty() && saved.equals(identity.gtswn$getControllerIdentity())) {
                identity.gtswn$setNetworkSafetyEnabled(pending);
                tile.markDirty();
                markDirty();
            }
            enabled |= identity.gtswn$isNetworkSafetyEnabled();
        }
        // Newly added structure members inherit the whole structure policy, never an old coordinate's policy.
        synchronizeSafety(world, members, enabled);
        return enabled;
    }

    public void setSafety(World world, Set<Long> members, boolean enabled) {
        synchronizeSafety(world, members, enabled);
        for (long member : members) {
            if (!world.blockExists(
                QuantumControllerRegistry.unpackX(member),
                QuantumControllerRegistry.unpackY(member),
                QuantumControllerRegistry.unpackZ(member)) && identities.containsKey(member)) {
                if (!Boolean.valueOf(enabled)
                    .equals(pendingSafety.put(member, enabled))) markDirty();
            } else if (pendingSafety.remove(member) != null) markDirty();
        }
    }

    private void synchronizeSafety(World world, Set<Long> members, boolean enabled) {
        for (long member : members) {
            int x = QuantumControllerRegistry.unpackX(member), y = QuantumControllerRegistry.unpackY(member),
                z = QuantumControllerRegistry.unpackZ(member);
            if (world.blockExists(x, y, z)) {
                TileEntity tile = world.getTileEntity(x, y, z);
                if (!(tile instanceof TileController) || !(tile instanceof QuantumControllerIdentity)) continue;
                QuantumControllerIdentity identity = (QuantumControllerIdentity) tile;
                if (identity.gtswn$getControllerIdentity()
                    .isEmpty()) {
                    identity.gtswn$setControllerIdentity(
                        UUID.randomUUID()
                            .toString());
                    tile.markDirty();
                }
                if (!identity.gtswn$getControllerIdentity()
                    .equals(identities.put(member, identity.gtswn$getControllerIdentity()))) markDirty();
                if (identity.gtswn$isNetworkSafetyEnabled() != enabled) {
                    identity.gtswn$setNetworkSafetyEnabled(enabled);
                    tile.markDirty();
                }
            }
            if (enabled ? safetyEnabled.add(member) : safetyEnabled.remove(member)) markDirty();
        }
    }

    /** Preserve either protected network when moving, without allowing an OFF source to unlock an ON target. */
    public static void transfer(World sourceWorld, Set<Long> sourceMembers, World targetWorld,
        Set<Long> targetMembers) {
        QuantumNetworkSecurityRegistry source = get(sourceWorld);
        QuantumNetworkSecurityRegistry target = get(targetWorld);
        boolean enrolled = source.isEnrolled(sourceWorld, sourceMembers)
            || target.isEnrolled(targetWorld, targetMembers);
        boolean enabled = source.isSafetyEnabled(sourceWorld, sourceMembers)
            | target.isSafetyEnabled(targetWorld, targetMembers);
        source.remove(sourceMembers);
        if (enrolled) target.enroll(targetWorld, targetMembers);
        target.setSafety(targetWorld, targetMembers, enabled);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        enrolled.clear();
        identities.clear();
        providerKeys.clear();
        safetyEnabled.clear();
        pendingSafety.clear();
        NBTTagList policies = tag.getTagList("localSafetyEnabled", 10);
        for (int i = 0; i < policies.tagCount(); i++) {
            NBTTagCompound policy = policies.getCompoundTagAt(i);
            long position = policy.getLong("position");
            safetyEnabled.add(position);
        }
        NBTTagList pending = tag.getTagList("pendingSafety", 10);
        for (int i = 0; i < pending.tagCount(); i++) {
            NBTTagCompound entry = pending.getCompoundTagAt(i);
            pendingSafety.put(entry.getLong("position"), entry.getBoolean("enabled"));
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
        for (long position : safetyEnabled) {
            NBTTagCompound policy = new NBTTagCompound();
            policy.setLong("position", position);
            policies.appendTag(policy);
        }
        tag.setTag("localSafetyEnabled", policies);
        NBTTagList pending = new NBTTagList();
        for (Map.Entry<Long, Boolean> entry : pendingSafety.entrySet()) {
            NBTTagCompound policy = new NBTTagCompound();
            policy.setLong("position", entry.getKey());
            policy.setBoolean("enabled", entry.getValue());
            pending.appendTag(policy);
        }
        tag.setTag("pendingSafety", pending);
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
