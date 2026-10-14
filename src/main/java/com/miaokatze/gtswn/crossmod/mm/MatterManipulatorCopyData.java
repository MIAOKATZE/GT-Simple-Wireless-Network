package com.miaokatze.gtswn.crossmod.mm;

import net.minecraft.nbt.NBTTagCompound;

/** Interface contains no optional MM types, so it is safe with MM absent. */
public interface MatterManipulatorCopyData {

    NBTTagCompound gtswn$getCopyData();

    void gtswn$setCopyData(NBTTagCompound data);
}
