package ic2.test;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import ic2.api.item.IElectricItem;
import ic2.api.item.IElectricItemManager;
import ic2.api.item.ISpecialElectricItem;

/** Isolated IC2-shaped fixture: the production recipe still enforces its normal battery policy. */
public class FakeRechargeableBattery extends Item implements IElectricItem, ISpecialElectricItem {

    public boolean providesEnergy = true;
    public boolean rechargeable = true;

    @Override
    public boolean canProvideEnergy(ItemStack stack) {
        return providesEnergy;
    }

    @Override
    public Item getChargedItem(ItemStack stack) {
        return this;
    }

    @Override
    public Item getEmptyItem(ItemStack stack) {
        return this;
    }

    @Override
    public double getMaxCharge(ItemStack stack) {
        return 1000;
    }

    @Override
    public int getTier(ItemStack stack) {
        return 1;
    }

    @Override
    public double getTransferLimit(ItemStack stack) {
        return 32;
    }

    @Override
    public IElectricItemManager getManager(ItemStack stack) {
        return manager;
    }

    private final IElectricItemManager manager = new IElectricItemManager() {

        @Override
        public double charge(ItemStack stack, double amount, int tier, boolean ignoreLimit, boolean simulate) {
            if (!rechargeable || tier < getTier(stack)) return 0;
            double received = Math.min(1000 - getCharge(stack), ignoreLimit ? amount : Math.min(amount, 32));
            if (!simulate) setCharge(stack, getCharge(stack) + received);
            return received;
        }

        @Override
        public double discharge(ItemStack stack, double amount, int tier, boolean ignoreLimit, boolean external,
            boolean simulate) {
            double used = Math.min(getCharge(stack), ignoreLimit ? amount : Math.min(amount, 32));
            if (!simulate) setCharge(stack, getCharge(stack) - used);
            return used;
        }

        @Override
        public double getCharge(ItemStack stack) {
            return stack.hasTagCompound() ? stack.getTagCompound()
                .getDouble("charge") : 0;
        }

        @Override
        public boolean canUse(ItemStack stack, double amount) {
            return getCharge(stack) >= amount;
        }

        @Override
        public boolean use(ItemStack stack, double amount, EntityLivingBase entity) {
            return false;
        }

        @Override
        public void chargeFromArmor(ItemStack stack, EntityLivingBase entity) {}

        @Override
        public String getToolTip(ItemStack stack) {
            return getCharge(stack) + "/1000 EU";
        }
    };

    public static void setCharge(ItemStack stack, double charge) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound()
            .setDouble("charge", charge);
    }
}
