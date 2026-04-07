package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/**
 * 机器注册器基类
 * 用于分散和管理 GregTech 元机器实体 (MTE) 的注册逻辑。
 * 通过模板方法模式，允许子类定义具体的注册项，并统一处理物品索引设置和创造模式栏添加。
 */
public class MachineRegistrar {

    // 存储待注册的机器任务列表
    private final List<MachineRegistration> registrations = new ArrayList<>();

    /**
     * 执行所有机器的注册
     * 首先调用 setupRegistrations 收集任务，然后逐一执行
     */
    public final void registerAll() {
        setupRegistrations();
        for (MachineRegistration registration : registrations) {
            registration.register();
        }
    }

    /**
     * 设置注册项
     * 子类应重写此方法，在其中调用 registerMachine 添加具体的机器
     */
    protected void setupRegistrations() {
        // 子类覆盖此方法添加注册
    }

    /**
     * 添加一个机器注册任务
     * 
     * @param mteSupplier 机器实例的提供者（通常使用构造函数引用）
     * @param container   对应的物品索引枚举项
     */
    protected void registerMachine(Supplier<IMetaTileEntity> mteSupplier, IItemContainer container) {
        registrations.add(new MachineRegistration(mteSupplier, container));
    }

    /**
     * 机器注册内部类
     * 封装了单个机器的注册逻辑：创建实例、设置物品索引、添加到创造模式栏
     */
    private static class MachineRegistration {

        private final Supplier<IMetaTileEntity> mteSupplier;
        private final IItemContainer container;

        public MachineRegistration(Supplier<IMetaTileEntity> mteSupplier, IItemContainer container) {
            this.mteSupplier = mteSupplier;
            this.container = container;
        }

        /**
         * 执行注册动作
         */
        public void register() {
            IMetaTileEntity mte = mteSupplier.get();
            if (mte != null) {
                // 将生成的机器实例绑定到物品索引枚举
                container.set(mte);
                // 将该机器物品添加到创造模式物品栏
                CreativeTabManager.addItemToTab(container.get(1));
            }
        }
    }
}
