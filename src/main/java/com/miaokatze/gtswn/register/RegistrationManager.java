// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.register;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一注册管理器
 * 采用单例模式，负责收集和管理模组内所有类型的注册任务（如机器、物品等）。
 * 通过维护一个 Runnable 列表，确保注册逻辑可以按添加顺序统一执行。
 */
public class RegistrationManager {

    // 单例实例
    private static final RegistrationManager INSTANCE = new RegistrationManager();

    // 待执行的注册任务列表
    private final List<Runnable> registrars = new ArrayList<>();

    /**
     * 获取管理器单例实例
     */
    public static RegistrationManager getInstance() {
        return INSTANCE;
    }

    /**
     * 添加一个注册任务
     * 
     * @param registrar 待执行的注册逻辑（通常是一个方法引用或 Lambda 表达式）
     */
    public void addRegistrar(Runnable registrar) {
        registrars.add(registrar);
    }

    /** Required registrations fail startup immediately; partial content must not be reported as ready. */
    public void registerAll() {
        for (Runnable registrar : registrars) registrar.run();
    }

    /**
     * 清空所有待执行的注册任务
     * 通常在测试或重置状态时使用
     */
    public void clear() {
        registrars.clear();
    }
}
