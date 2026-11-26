// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.assembly;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.assembly.nativeintegration.OrderedAssemblyMember;

import appeng.helpers.ICustomNameObject;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** A physical owner of one native input position. Management slots never enter native inventory. */
public interface MatrixAssemblyMember extends OrderedAssemblyMember, ICustomNameObject {

    MatrixAssemblyPort matrixPort();

    @Override
    default String getCustomName() {
        return matrixPort().customName();
    }

    @Override
    default boolean hasCustomName() {
        return getCustomName() != null;
    }

    @Override
    default void setCustomName(String name) {
        matrixPort().customName(name);
    }

    IGregTechTileEntity getBaseMetaTileEntity();

    boolean matrixFluid();

    default boolean matrixMirror() {
        return false;
    }

    ItemStack[] matrixNativeItems();

    FluidStack matrixNativeFluid();

    ItemStack matrixItem();

    FluidStack matrixFluidStack();

    void matrixSetItem(ItemStack item);

    void matrixSetFluid(FluidStack fluid);
}
