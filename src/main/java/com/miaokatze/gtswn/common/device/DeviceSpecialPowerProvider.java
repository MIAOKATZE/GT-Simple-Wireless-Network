package com.miaokatze.gtswn.common.device;

import java.lang.reflect.Field;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor;

/**
 * 特殊机器权威功率 provider（实施计划任务2）：对「实时输出不在标准流量词条」的机器，读取其
 * <b>权威实时功率字段</b>，区分消耗/产出。EU 方向统一采集优先序 =
 * 基座/controller getter → hatch 聚合 → <b>本 provider 权威字段</b> → 白名单 |mEUt|/|lEUt| 幅值
 * 兜底（接入点在 {@link DeviceSampleScheduler#collectEuFlow} 的真双零门内：getter 与 hatch
 * 聚合合计仍双零时，provider 命中注册类即按权威字段符号定方向，替代白名单兜底对该类的命中）。
 * <p>
 * 【GT5U 5.09.54.20 已核实事实】
 * <ul>
 * <li>大型硅岩反应堆 {@code MTELargeNaquadahReactor}（GT5U 权威源码 :70）：{@code protected long
 * trueOutput} 为权威输出（:242-249 计算）；{@code addAutoEnergy} 直写 Dynamo hatch
 * {@code setEUVar} 绕过网络记账（:309-334），标准 getter/hatch 流量词条读不到 → 走权威字段。
 * 该类为 GT5U 编译期内（{@code api} 依赖），{@code instanceof} + 反射取字段</li>
 * <li>戴森云控制器 {@code TileEntityDysonSwarm}（gtnhintergalactic 独立 mod jar，无编译依赖，
 * GT5U monolith 内源码 :195）：{@code private long euPerTick} 为权威实时输出（:274-275 计算，
 * energyFlowOnRunningTick → addEnergyOutput_EM :303-307）→ {@link Class#forName} 软检测 +
 * 反射（镜像 {@code DeviceMachineTypes} GTSR 模式，仅加载不初始化）</li>
 * </ul>
 * <p>
 * 语义：权威字段<b>正值=发电 → out 通道、负值=消耗 → in 通道</b>；字段为 0 或机器非运行
 * （非 RUNNING 态根本不进采集）→ 不编造数值，调用方落回原有白名单兜底。仅覆盖注册类：
 * 未命中 / 缺类 / 缺字段 / 改名 / 反射异常一律静默返回 0 并原序回退，<b>严禁抛出中断采样循环</b>。
 * Class/Field 引用静态缓存、仅类加载时解析一次（解析全程 try/catch 包裹，不会抛
 * ExceptionInInitializerError）。Legacy 涡轮等未验证家族不接入；如需扩展须先按 GT5U 权威
 * 源码核实字段并在 {@link #readAuthoritativeEut} 补注册分支（注释级扩展点）。
 */
public final class DeviceSpecialPowerProvider {

    /** 大型硅岩反应堆权威输出字段（protected long trueOutput）；解析失败为 null = provider 对该类禁用 */
    private static final Field LNR_TRUE_OUTPUT = resolveLnrTrueOutput();

    /** 戴森云控制器类（gtnhintergalactic 未安装时为 null）；仅加载不初始化，避免早期类加载副作用 */
    private static final Class<?> DYSON_SWARM_CLASS = lookupDysonSwarm();

    /** 戴森云权威功率字段（private long euPerTick）；类缺失 / 解析失败为 null = provider 对该类禁用 */
    private static final Field DYSON_EU_PER_TICK = resolveDysonEuPerTick();

    private DeviceSpecialPowerProvider() {}

    /**
     * 读取特殊机器权威带符号 EU/t：正值=发电（→out 通道）、负值=消耗（→in 通道）。
     * 未命中注册类 / 字段不可用 / 读取异常 / 权威值为 0 → 一律返回 0（不编造数值、不抛出）。
     */
    public static long readAuthoritativeEut(IMetaTileEntity mte) {
        if (mte == null) {
            return 0L;
        }
        if (mte instanceof MTELargeNaquadahReactor) {
            return readLong(LNR_TRUE_OUTPUT, mte);
        }
        if (DYSON_SWARM_CLASS != null && DYSON_SWARM_CLASS.isInstance(mte)) {
            return readLong(DYSON_EU_PER_TICK, mte);
        }
        return 0L;
    }

    /** 缓存字段反射读值；字段为 null（解析失败）或读取抛错（改名/访问被拒/实例不匹配）静默归 0 */
    private static long readLong(Field field, Object instance) {
        if (field == null) {
            return 0L;
        }
        try {
            return field.getLong(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 字段被改名 / 签名变化 / 安全策略拒绝等：provider 本次不可用，归 0 走原有兜底，不抛穿
            return 0L;
        }
    }

    /** LNR 为 GT5U 编译期内类，直接类字面量取 protected 字段并 setAccessible；失败归 null 不抛出 */
    private static Field resolveLnrTrueOutput() {
        try {
            Field field = MTELargeNaquadahReactor.class.getDeclaredField("trueOutput");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** 镜像 DeviceMachineTypes GTSR 软检测模式：仅加载不初始化；独立 mod 缺失 / 链接失败归 null */
    private static Class<?> lookupDysonSwarm() {
        try {
            return Class.forName(
                "gtnhintergalactic.tile.multi.TileEntityDysonSwarm",
                false,
                DeviceSpecialPowerProvider.class.getClassLoader());
        } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** 依赖 {@link #DYSON_SWARM_CLASS}（静态初始化序在其后）；字段缺失 / 改名 / 访问被拒归 null */
    private static Field resolveDysonEuPerTick() {
        if (DYSON_SWARM_CLASS == null) {
            return null;
        }
        try {
            Field field = DYSON_SWARM_CLASS.getDeclaredField("euPerTick");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
