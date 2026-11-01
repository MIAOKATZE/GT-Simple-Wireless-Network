package com.miaokatze.gtswn.common.assembly;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.miaokatze.gtswn.assembly.nativeintegration.OrderedAssemblyMember;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** A physical owner of one native input position. Management slots never enter native inventory. */
public interface MatrixAssemblyMember extends OrderedAssemblyMember {

    MatrixAssemblyPort matrixPort();

    IGregTechTileEntity getBaseMetaTileEntity();

    boolean matrixFluid();

    ItemStack matrixItem();

    FluidStack matrixFluidStack();

    void matrixSetItem(ItemStack item);

    void matrixSetFluid(FluidStack fluid);
}
