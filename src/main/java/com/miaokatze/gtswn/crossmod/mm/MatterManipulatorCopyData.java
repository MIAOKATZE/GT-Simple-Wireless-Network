// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.crossmod.mm;

import net.minecraft.nbt.NBTTagCompound;

/** Interface contains no optional MM types, so it is safe with MM absent. */
public interface MatterManipulatorCopyData {

    NBTTagCompound gtswn$getCopyData();

    void gtswn$setCopyData(NBTTagCompound data);
}
