package com.miaokatze.gtswn.common.device;

import net.minecraftforge.fluids.FluidStack;

import gregtech.api.interfaces.IOutputHatch;
import gregtech.api.interfaces.IOutputHatchTransaction;
import gregtech.api.interfaces.IOutputTransaction;
import gregtech.api.util.GTUtility;

/** Delegates native transactional storage, recording committed steam without recording voided overflow. */
public final class DeviceSteamOutputTransaction
    implements IOutputHatchTransaction, IOutputTransaction.IRecipeCheckAware, IOutputTransaction.IProtectOutputAware {

    private final IOutputHatchTransaction delegate;
    private long accepted;
    private boolean recipeCheck;
    private boolean committed;

    public DeviceSteamOutputTransaction(IOutputHatchTransaction delegate) {
        this.delegate = delegate;
    }

    @Override
    public IOutputHatch getHatch() {
        return delegate.getHatch();
    }

    @Override
    public boolean isFiltered() {
        return delegate.isFiltered();
    }

    @Override
    public boolean isFilteredTo(GTUtility.FluidId id) {
        return delegate.isFilteredTo(id);
    }

    @Override
    public boolean hasAvailableSpace() {
        return delegate.hasAvailableSpace();
    }

    @Override
    public boolean needsTotalParallelData() {
        return delegate.needsTotalParallelData();
    }

    @Override
    public void complete(GTUtility.FluidId id) {
        delegate.complete(id);
    }

    @Override
    public boolean storePartial(GTUtility.FluidId id, FluidStack fluid, long total, long perParallel) {
        int before = fluid.amount;
        boolean steam = DeviceSteamAdapter.isSteam(fluid);
        boolean result = delegate.storePartial(id, fluid, total, perParallel);
        if (steam) accepted += Math.max(0, before - fluid.amount);
        return result;
    }

    @Override
    public void commit() {
        delegate.commit();
        if (!committed && !recipeCheck) DeviceFlowMonitor.steamCurrent(accepted, true);
        committed = true;
    }

    @Override
    public void setRecipeCheck(boolean value) {
        recipeCheck = value;
        if (delegate instanceof IOutputTransaction.IRecipeCheckAware)
            ((IOutputTransaction.IRecipeCheckAware) delegate).setRecipeCheck(value);
    }

    @Override
    public void setProtectOutput(boolean value) {
        if (delegate instanceof IOutputTransaction.IProtectOutputAware)
            ((IOutputTransaction.IProtectOutputAware) delegate).setProtectOutput(value);
    }
}
