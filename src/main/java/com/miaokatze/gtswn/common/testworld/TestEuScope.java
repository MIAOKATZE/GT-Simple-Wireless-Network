package com.miaokatze.gtswn.common.testworld;

import java.math.BigInteger;
import java.util.UUID;

import gregtech.common.misc.GlobalEnergyWorldSavedData;
import gregtech.common.misc.GlobalVariableStorage;
import gregtech.common.misc.spaceprojects.SpaceProjectManager;
import gregtech.common.misc.spaceprojects.SpaceProjectWorldSavedData;

/** Exact-key lifecycle for an isolated synthetic UUID; unrelated teams and balances are never accessed. */
final class TestEuScope implements AutoCloseable {

    private final UUID owner;
    private final boolean hadTeam, hadBalance;
    private final BigInteger balance;

    TestEuScope(UUID owner) {
        this.owner = owner;
        UUID leader = SpaceProjectManager.spaceTeams.get(owner);
        if (leader != null && !owner.equals(leader))
            throw new IllegalStateException("Synthetic test UUID belongs to a foreign team; refusing network access");
        hadTeam = SpaceProjectManager.spaceTeams.containsKey(owner);
        if (hadTeam && leader == null) throw new IllegalStateException(
            "Synthetic test UUID has invalid preexisting team entry; refusing mutation");
        hadBalance = GlobalVariableStorage.GlobalEnergy.containsKey(owner);
        balance = GlobalVariableStorage.GlobalEnergy.get(owner);
        if (balance != null && balance.signum() != 0)
            throw new IllegalStateException("Synthetic test UUID has nonzero preexisting balance; refusing mutation");
        TestWorldProbes.addOwner(owner);
    }

    @Override
    public void close() {
        if (hadBalance) GlobalVariableStorage.GlobalEnergy.put(owner, balance);
        else GlobalVariableStorage.GlobalEnergy.remove(owner);
        if (!hadTeam && owner.equals(SpaceProjectManager.spaceTeams.get(owner)))
            SpaceProjectManager.spaceTeams.remove(owner);
        if (GlobalEnergyWorldSavedData.INSTANCE != null) GlobalEnergyWorldSavedData.INSTANCE.markDirty();
        if (SpaceProjectWorldSavedData.INSTANCE != null) SpaceProjectWorldSavedData.INSTANCE.markDirty();
    }
}
