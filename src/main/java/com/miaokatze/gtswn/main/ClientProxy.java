package com.miaokatze.gtswn.main;

/**
 * 客户端代理类
 * 继承自 CommonProxy，用于处理仅在客户端（Client Side）执行的逻辑。
 * 例如：渲染注册、按键绑定、GUI 打开等。
 */
public class ClientProxy extends CommonProxy {

    // 如果需要为客户端实现不同的行为（如注册渲染器），请在此处重写 CommonProxy 的方法。
    // 记得在重写的方法中调用 super 方法以确保服务端逻辑正常执行。

}
