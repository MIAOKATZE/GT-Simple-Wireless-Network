package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/**
 * 机器注册器 - 用于分散注册 GT 机器
 */
public class MachineRegistrar {

    private final List<MachineRegistration> registrations = new ArrayList<>();

    /**
     * 注册所有机器
     */
    public final void registerAll() {
        setupRegistrations();
        for (MachineRegistration registration : registrations) {
            registration.register();
        }
    }

    /**
     * 设置注册项 - 子类实现
     */
    protected void setupRegistrations() {
        // 子类覆盖此方法添加注册
    }

    /**
     * 添加机器注册
     */
    protected void registerMachine(Supplier<IMetaTileEntity> mteSupplier, IItemContainer container) {
        registrations.add(new MachineRegistration(mteSupplier, container));
    }

    /**
     * 机器注册内部类
     */
    private static class MachineRegistration {

        private final Supplier<IMetaTileEntity> mteSupplier;
        private final IItemContainer container;

        public MachineRegistration(Supplier<IMetaTileEntity> mteSupplier, IItemContainer container) {
            this.mteSupplier = mteSupplier;
            this.container = container;
        }

        public void register() {
            IMetaTileEntity mte = mteSupplier.get();
            if (mte != null) {
                container.set(mte);
                // 将物品添加到创造模式物品栏
                CreativeTabManager.addItemToTab(container.get(1));
            }
        }
    }
}
