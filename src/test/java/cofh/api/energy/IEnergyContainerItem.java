package cofh.api.energy;

import net.minecraft.item.ItemStack;

/** Test-only shape of CoFH's optional public API; never bundled in the mod jar. */
public interface IEnergyContainerItem {

    int receiveEnergy(ItemStack container, int maxReceive, boolean simulate);

    int extractEnergy(ItemStack container, int maxExtract, boolean simulate);

    int getEnergyStored(ItemStack container);

    int getMaxEnergyStored(ItemStack container);
}
