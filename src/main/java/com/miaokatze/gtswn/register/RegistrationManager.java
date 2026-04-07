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

    /**
     * 依次执行所有已添加的注册任务
     * 增加了异常隔离机制，确保单个任务失败不会中断整体注册流程
     */
    public void registerAll() {
        for (Runnable registrar : registrars) {
            try {
                registrar.run();
            } catch (Throwable t) {
                // 记录错误但继续执行后续任务，防止模组因局部问题完全崩溃
                com.miaokatze.gtswn.main.GTSimpleWirelessNetwork.LOG.error("注册任务执行失败", t);
            }
        }
    }

    /**
     * 清空所有待执行的注册任务
     * 通常在测试或重置状态时使用
     */
    public void clear() {
        registrars.clear();
    }
}
