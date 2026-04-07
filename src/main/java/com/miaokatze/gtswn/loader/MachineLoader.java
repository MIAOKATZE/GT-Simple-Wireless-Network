package com.miaokatze.gtswn.loader;

import com.miaokatze.gtswn.register.RegistrationManager;
import com.miaokatze.gtswn.register.TestMachineRegistrar;

/**
 * 机器加载器
 * 负责初始化并执行所有元机器实体 (MTE) 的注册逻辑。
 * 通过 RegistrationManager 统一管理注册流程，确保注册顺序可控。
 */
public class MachineLoader {

    /**
     * 初始化所有机器
     * 该方法应在模组预初始化 (PreInit) 或初始化 (Init) 阶段调用
     */
    public static void initMachines() {
        RegistrationManager manager = RegistrationManager.getInstance();

        // 创建测试机器的注册器实例
        TestMachineRegistrar testMachineRegistrar = new TestMachineRegistrar();

        // 将注册任务添加到管理器中
        manager.addRegistrar(testMachineRegistrar::registerAll);

        // 统一执行所有已添加的注册任务
        manager.registerAll();
    }
}
