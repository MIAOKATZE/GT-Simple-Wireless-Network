package com.miaokatze.gtswn.common.assembly;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.cleanroommc.modularui.utils.item.ItemStackHandler;

import appeng.api.implementations.ICraftingPatternItem;

/** The standard 4x9 pattern bank, separate from the single native input slot. */
public final class MatrixPatternInventory extends ItemStackHandler {

    public static final int SIZE = 36;
    private final MatrixAssemblyPort port;
    private boolean loading;

    MatrixPatternInventory(MatrixAssemblyPort port) {
        super(SIZE);
        this.port = port;
    }

    public boolean editable() {
        if (port.member()
            .getBaseMetaTileEntity()
            .isClientSide()) return true;
        return !port.quarantined() && port.group() != null
            && port.group()
                .editable();
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return editable() && stack != null && stack.getItem() instanceof ICraftingPatternItem;
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (!isItemValid(slot, stack)) return stack;
        return super.insertItem(slot, stack, simulate);
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return editable() ? super.extractItem(slot, amount, simulate) : null;
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        if (!loading && (!editable() || stack != null && !(stack.getItem() instanceof ICraftingPatternItem))) return;
        super.setStackInSlot(slot, stack);
    }

    @Override
    public int getSlotLimit(int slot) {
        return 1;
    }

    @Override
    protected void onContentsChanged(int slot) {
        if (!loading) port.changed();
    }

    void clearForDestruction(int slot) {
        super.setStackInSlot(slot, null);
    }

    NBTTagList save() {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < SIZE; i++) {
            ItemStack stack = getStackInSlot(i);
            if (stack == null) continue;
            NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
            tag.setInteger("slot", i);
            result.appendTag(tag);
        }
        return result;
    }

    void load(NBTTagList data) {
        if (data.tagCount() > SIZE) throw new IllegalArgumentException("pattern_count");
        loading = true;
        try {
            for (int i = 0; i < SIZE; i++) super.setStackInSlot(i, null);
            for (int i = 0; i < data.tagCount(); i++) {
                NBTTagCompound tag = data.getCompoundTagAt(i);
                int slot = tag.getInteger("slot");
                ItemStack stack = ItemStack.loadItemStackFromNBT(tag);
                if (slot < 0 || slot >= SIZE
                    || getStackInSlot(slot) != null
                    || stack == null
                    || !(stack.getItem() instanceof ICraftingPatternItem)
                    || stack.stackSize != 1) throw new IllegalArgumentException("invalid_pattern");
                super.setStackInSlot(slot, stack);
            }
        } finally {
            loading = false;
        }
    }
}
