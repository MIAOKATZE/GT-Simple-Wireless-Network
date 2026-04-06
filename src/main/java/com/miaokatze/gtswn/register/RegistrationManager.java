package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一注册管理器 - 管理所有类型的注册器
 */
public class RegistrationManager {

    private static final RegistrationManager INSTANCE = new RegistrationManager();
    private final List<Runnable> registrars = new ArrayList<>();

    public static RegistrationManager getInstance() {
        return INSTANCE;
    }

    /**
     * 添加注册器
     */
    public void addRegistrar(Runnable registrar) {
        registrars.add(registrar);
    }

    /**
     * 执行所有注册
     */
    public void registerAll() {
        for (Runnable registrar : registrars) {
            registrar.run();
        }
    }

    /**
     * 清空注册器列表
     */
    public void clear() {
        registrars.clear();
    }
}
