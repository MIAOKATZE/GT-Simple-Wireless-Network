package com.miaokatze.gtswn.loader;

import com.miaokatze.gtswn.register.RegistrationManager;
import com.miaokatze.gtswn.register.TestMachineRegistrar;

/**
 * 机器加载器 - 负责初始化所有机器注册器
 */
public class MachineLoader {

    /**
     * 初始化所有机器
     */
    public static void initMachines() {
        RegistrationManager manager = RegistrationManager.getInstance();

        // 注册测试机器
        TestMachineRegistrar testMachineRegistrar = new TestMachineRegistrar();
        manager.addRegistrar(testMachineRegistrar::registerAll);

        // 执行所有注册
        manager.registerAll();
    }
}
