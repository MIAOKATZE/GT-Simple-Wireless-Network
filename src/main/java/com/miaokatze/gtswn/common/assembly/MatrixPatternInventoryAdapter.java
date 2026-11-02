package com.miaokatze.gtswn.common.assembly;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtswn.assembly.nativeintegration.NativeAssemblyAdapter;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/** A stable interface-terminal view of the current group's shared pattern bank. */
final class MatrixPatternInventoryAdapter implements IInventory {

    private final MatrixAssemblyPort port;

    MatrixPatternInventoryAdapter(MatrixAssemblyPort port) {
        this.port = port;
    }

    private MatrixAssemblyPort owner() {
        OrderedAssemblyGroup group = port.group();
        return group == null ? port
            : group.master()
                .matrixPort();
    }

    private MatrixPatternInventory patterns() {
        return owner().patterns();
    }

    @Override
    public int getSizeInventory() {
        // AE checks this before doing any player transfer. Its pickup/shift-click paths copy to the player
        // before clearing the slot, so rejecting only the final setter would duplicate locked patterns.
        // Rendering uses IInterfaceViewable's fixed 4x9 dimensions and may still read these slots.
        MatrixAssemblyPort owner = owner();
        return NativeAssemblyAdapter.isLive((IMetaTileEntity) port.member())
            && (owner == port || NativeAssemblyAdapter.isLive((IMetaTileEntity) owner.member()))
            && !port.quarantined()
            && owner.patterns()
                .editable() ? MatrixPatternInventory.SIZE : 0;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemStack stack = patterns().getStackInSlot(slot);
        return stack == null ? null : stack.copy();
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        return getSizeInventory() == 0 ? null : patterns().extractItem(slot, amount, false);
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return decrStackSize(slot, Integer.MAX_VALUE);
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (getSizeInventory() != 0) patterns().setStackInSlot(slot, stack == null ? null : stack.copy());
    }

    @Override
    public String getInventoryName() {
        return ((IMetaTileEntity) port.member()).getLocalName();
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 1;
    }

    @Override
    public void markDirty() {
        MatrixAssemblyPort owner = owner();
        if (NativeAssemblyAdapter.isLive((IMetaTileEntity) owner.member())) owner.changed();
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return false;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return getSizeInventory() != 0 && patterns().isItemValid(slot, stack);
    }
}
