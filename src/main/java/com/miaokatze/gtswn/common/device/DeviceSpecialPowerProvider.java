package com.miaokatze.gtswn.common.device;

import java.lang.reflect.Field;
import java.util.Arrays;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTELargeNaquadahReactor;

/**
 * 特殊机器权威功率 provider（预选词条/数值链版）：对「实时输出不在标准流量词条」的机器，按
 * <b>预选字段数值链</b>读取权威实时功率，读到非零即停。EU 方向统一采集优先序 =
 * 基座/controller getter → hatch 聚合 → 真双零门内数值链（发电词条幅值 → 本 provider 符号值 →
 * 耗电词条幅值，见 {@link DeviceSampleScheduler#collectEuFlow}）。
 * <p>
 * 【GT5U 5.09.54.133 已核实事实（beta-3 复核）】
 * <ul>
 * <li>大型硅岩反应堆 {@code MTELargeNaquadahReactor}：{@code protected long trueOutput} 仍为
 * 权威输出（:70 声明、:241 计算、:253 getInfoData |trueOutput| 展示）；beta-2→beta-3 该类逻辑
 * 零改动（仅结构字符串格式化与 NEI 维度），{@code addAutoEnergy} 仍直写 Dynamo hatch
 * {@code setEUVar} 绕过网络记账（:304-320）。v1.8.1/v1.8.2 终端读不到 LNR 的真因是采样层
 * 词条命中不再咨询 provider，而其白名单幅值 {@code |mEUt|} 恒 0，非 GT5U 改动</li>
 * <li>戴森云控制器 {@code TileEntityDysonSwarm}（gtnhintergalactic 独立 mod jar，无编译依赖，
 * GT5U monolith 内源码 :195）：{@code private long euPerTick} 为权威实时输出 →
 * {@link Class#forName} 软检测，缺类/缺字段静默禁用该 entry</li>
 * </ul>
 * <p>
 * <b>预选词条/数值链语义</b>：每个注册类带有序候选字段名链（如 LNR 未来若再改字段，只需在链上
 * 追加新名即可同时兼容新旧版本）；类加载时对链做<b>一次性巡检</b>，解析存在的字段按原序缓存；
 * 运行期读取按链遍历，<b>读到非零即停</b>——字段缺失/改名/反射异常/权威值为 0 均视为该词条
 * 缺失，继续下一候选，全链缺失归 0（不编造数值、严禁抛出中断采样循环）。
 * <p>
 * 方向语义由调用方解释：provider 返回<b>带符号</b> EU/t（正值=发电→out 通道、负值=消耗→in 通道）。
 * Legacy 涡轮等未验证家族不接入；如需扩展须先按 GT5U 权威源码核实字段并补注册分支
 * （注释级扩展点）。
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
