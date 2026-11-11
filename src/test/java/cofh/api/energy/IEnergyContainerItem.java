// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package cofh.api.energy;

import net.minecraft.item.ItemStack;

/** Test-only shape of CoFH's optional public API; never bundled in the mod jar. */
public interface IEnergyContainerItem {

    int receiveEnergy(ItemStack container, int maxReceive, boolean simulate);

    int extractEnergy(ItemStack container, int maxExtract, boolean simulate);

    int getEnergyStored(ItemStack container);

    int getMaxEnergyStored(ItemStack container);
}
