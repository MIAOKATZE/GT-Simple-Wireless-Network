package com.miaokatze.gtswn.common.device;

import java.lang.reflect.Field;
import java.util.Arrays;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor;

/**
 * Explicit per-machine power models for output paths outside generic storage observations.
 * GT205 MTELargeNaquadahReactor.trueOutput and Intergalactic DysonSwarm.euPerTick are known native model fields.
 * The sampler labels these values as models and only uses them when no directly observed energy is available.
 * Field lookup is performed once; missing optional machines or renamed fields yield zero.
 */
public final class DeviceSpecialPowerProvider {

    /** 单个注册类的数值链 entry：type 不可用（软检测类缺失）时整条 entry 禁用 */
    private static final class ProviderEntry {

        /** instanceof 判定类型（GT5U 编译期内类为 class 字面量；软检测类可为 null = 禁用） */
        final Class<?> type;

        /** 一次性巡检解析出的存在字段链（原序；解析失败/缺失的候选名不进链） */
        final Field[] chain;

        ProviderEntry(Class<?> type, Field[] chain) {
            this.type = type;
            this.chain = chain;
        }
    }

    /** 注册表：新增特殊机器 = 追加 entry（类 + 有序候选字段名链） */
    private static final ProviderEntry[] ENTRIES = {
        new ProviderEntry(MTELargeNaquadahReactor.class, resolveChain(MTELargeNaquadahReactor.class, "trueOutput")),
        buildSoftEntry("gtnhintergalactic.tile.multi.TileEntityDysonSwarm", "euPerTick") };

    private DeviceSpecialPowerProvider() {}

    /**
     * 读取特殊机器权威带符号 EU/t（数值链：读到非零即停）：正值=发电（→out 通道）、
     * 负值=消耗（→in 通道）。未命中注册类 / 软检测类缺失 / 全链字段缺失或异常 / 权威值全 0
     * → 一律返回 0（不编造数值、不抛出）。
     */
    public static long readAuthoritativeEut(IMetaTileEntity mte) {
        if (mte == null) {
            return 0L;
        }
        for (ProviderEntry entry : ENTRIES) {
            if (entry.type == null || !entry.type.isInstance(mte)) {
                continue;
            }
            // 一个 MTE 至多命中一个注册类：链上全零即结束（后续 entry 不再看）
            return readChain(entry.chain, mte);
        }
        return 0L;
    }

    /** 数值链遍历：按序反射读字段，读到非零即停；字段异常/为 0 视为词条缺失继续下一候选，全链零归 0 */
    static long readChain(Field[] chain, Object instance) {
        for (Field field : chain) {
            long value = readLong(field, instance);
            if (value != 0L) {
                return value;
            }
        }
        return 0L;
    }

    /** 一次性巡检：按候选名序解析存在字段（setAccessible），缺失/改名/访问被拒的候选静默跳过不进链 */
    static Field[] resolveChain(Class<?> type, String... candidateNames) {
        Field[] resolved = new Field[candidateNames.length];
        int count = 0;
        for (String name : candidateNames) {
            Field field = resolveField(type, name);
            if (field != null) {
                resolved[count++] = field;
            }
        }
        return count == candidateNames.length ? resolved : Arrays.copyOf(resolved, count);
    }

    /** 单字段解析（getDeclaredField + setAccessible）；失败归 null 不抛出 */
    private static Field resolveField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // 字段被改名 / 签名变化 / 安全策略拒绝等：该候选词条缺失，链上跳过
            return null;
        }
    }

    /** 软检测 entry 组装（仅加载不初始化）：类缺失 → type=null 整条禁用；类在而字段全缺 → 空链 */
    private static ProviderEntry buildSoftEntry(String className, String... candidateNames) {
        Class<?> type = lookupClass(className);
        return new ProviderEntry(type, type != null ? resolveChain(type, candidateNames) : new Field[0]);
    }

    /** 镜像 DeviceMachineTypes GTSR 模式：Class.forName 仅加载不初始化；独立 mod 缺失 / 链接失败归 null */
    private static Class<?> lookupClass(String name) {
        try {
            return Class.forName(name, false, DeviceSpecialPowerProvider.class.getClassLoader());
        } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** 缓存字段反射读值；读取抛错（改名/访问被拒/实例不匹配）静默归 0（调用方按词条缺失继续走链） */
    private static long readLong(Field field, Object instance) {
        try {
            return field.getLong(instance);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0L;
        }
    }
}
