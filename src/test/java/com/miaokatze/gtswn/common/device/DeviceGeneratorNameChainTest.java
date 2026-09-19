package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 发电白名单名称链谓词纯 JVM 单测（v1.8.9 S6）：直测 package-private
 * {@link DeviceMachineTypes#nameChainHitsGenerator(Class)}。
 * <p>
 * 【测试路线选择】桩类为测试文件内的私有静态嵌套类，simple name 与白名单/排除集
 * 条目精确相等（嵌套类 {@code getSimpleName()} 即声明名），不 extends 任何真实
 * MTE——真实基类（MTEMultiBlockBase 等）的上溯链验证需要 MC 类加载参与，且谓词
 * 逻辑只依赖 simple name 与 {@code getSuperclass()} 形状，与桩的实际父类无关；
 * 因此以纯名称链入口覆盖全部判定分支（包含命中 / 继承链命中 / 排除命中 /
 * 排除优先于包含 / null / 深度上限），成本最低且真实触达判定代码。
 * 谓词对 null 入参与 {@code Object} 根自然终止的行为一并断言。
 */
public class DeviceGeneratorNameChainTest {

    // ==================== 包含集桩（12 项，simple name 与 GENERATOR_CLASS_NAMES 精确相等） ====================

    private static class MTEUniversalChemicalFuelEngine {
    }

    private static class MTEUniversalChemicalFuelEngineLegacy {
    }

    private static class MTEMultiNqGeneratorLegacy {
    }

    private static class MTELargeTurbineLegacy {
    }

    private static class MTELargeTurbineBaseLegacy {
    }

    private static class MTELargerTurbineBaseLegacy {
    }

    private static class MTELargeNeutralizationEngine {
    }

    private static class MTELargeRocketEngine {
    }

    private static class MTELargeSemifluidGenerator {
    }

    private static class MTENuclearReactor {
    }

    private static class TileEntityDysonSwarm {
    }

    private static class AntimatterGenerator {
    }

    /** 包含集桩的"真子类"：模拟 MTELargeTurbineSteamLegacy extends MTELargeTurbineLegacy 的继承链命中 */
    private static class MTELargeTurbineLegacyStubChild extends MTELargeTurbineLegacy {
    }

    // ==================== 排除集桩（12 项，simple name 与 EXCLUDED_GENERATOR_CLASS_NAMES 精确相等） ====================

    private static class MTEWormholeGenerator {
    }

    private static class MTELargeFusionComputer {
    }

    private static class MTEBECGenerator {
    }

    private static class MTEHighTempGasCooledReactor {
    }

    private static class MTEThoriumHighTempReactor {
    }

    private static class MTETesseractGenerator {
    }

    private static class MTELapotronicSuperCapacitor {
    }

    private static class MTEActiveTransformer {
    }

    private static class MTETeslaTower {
    }

    private static class MTEPowerSubStation {
    }

    private static class MTELargeBoiler {
    }

    private static class MTEThermalBoiler {
    }

    /** 排除集桩的"真子类"：继承排除基类的子类仍须判非发电 */
    private static class MTEWormholeGeneratorStubChild extends MTEWormholeGenerator {
    }

    /**
     * 排除优先桩场景：本类 simple name 命中包含集、其父链命中排除集（嵌套作用域内
     * simple name 遮蔽外层同名桩是合法 Java 语义）——谓词须在上溯越过包含命中层后
     * 仍被更上层的排除名一票否决。模拟"发电名夹在耗电陷阱基类之下"的畸形层级。
     */
    private static class InclusionBelowExclusion {

        static class MTEMultiNqGeneratorLegacy extends MTELargeBoiler {
        }
    }

    /**
     * 深链桩场景：根（外层 MTENuclearReactor 桩，名命中包含集）到查询点的父链长度
     * 超过 NAME_CHAIN_MAX_DEPTH=32——D20 距根 21 层（界内，须 true）；D34 距根 35 层
     * （被深度上限截断，包含名不可达，须 false 且不抛异常）。
     */
    private static class DeepChain {

        static class D01 extends MTENuclearReactor {
        }

        static class D02 extends D01 {
        }

        static class D03 extends D02 {
        }

        static class D04 extends D03 {
        }

        static class D05 extends D04 {
        }

        static class D06 extends D05 {
        }

        static class D07 extends D06 {
        }

        static class D08 extends D07 {
        }

        static class D09 extends D08 {
        }

        static class D10 extends D09 {
        }

        static class D11 extends D10 {
        }

        static class D12 extends D11 {
        }

        static class D13 extends D12 {
        }

        static class D14 extends D13 {
        }

        static class D15 extends D14 {
        }

        static class D16 extends D15 {
        }

        static class D17 extends D16 {
        }

        static class D18 extends D17 {
        }

        static class D19 extends D18 {
        }

        static class D20 extends D19 {
        }

        static class D21 extends D20 {
        }

        static class D22 extends D21 {
        }

        static class D23 extends D22 {
        }

        static class D24 extends D23 {
        }

        static class D25 extends D24 {
        }

        static class D26 extends D25 {
        }

        static class D27 extends D26 {
        }

        static class D28 extends D27 {
        }

        static class D29 extends D28 {
        }

        static class D30 extends D29 {
        }

        static class D31 extends D30 {
        }

        static class D32 extends D31 {
        }

        static class D33 extends D32 {
        }

        static class D34 extends D33 {
        }
    }

    // ==================== 断言 ====================

    /** 12 个包含项：simple name 精确相等即命中 true */
    @Test
    public void includedStubNamesEachHit() {
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTEUniversalChemicalFuelEngine.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTEUniversalChemicalFuelEngineLegacy.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTEMultiNqGeneratorLegacy.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargeTurbineLegacy.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargeTurbineBaseLegacy.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargerTurbineBaseLegacy.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargeNeutralizationEngine.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargeRocketEngine.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargeSemifluidGenerator.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTENuclearReactor.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(TileEntityDysonSwarm.class));
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(AntimatterGenerator.class));
    }

    /** 继承链命中：子类自身名不在任何集合，经 getSuperclass() 上溯命中包含基名 */
    @Test
    public void includedBaseNameHitsThroughSubclassChain() {
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(MTELargeTurbineLegacyStubChild.class));
    }

    /** 12 个排除项：链上命中排除名一律 false */
    @Test
    public void excludedStubNamesEachMiss() {
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEWormholeGenerator.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTELargeFusionComputer.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEBECGenerator.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEHighTempGasCooledReactor.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEThoriumHighTempReactor.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTETesseractGenerator.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTELapotronicSuperCapacitor.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEActiveTransformer.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTETeslaTower.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEPowerSubStation.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTELargeBoiler.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEThermalBoiler.class));
    }

    /** 继承排除基类的子类仍为 false（排除沿链生效，非仅比对叶子类名） */
    @Test
    public void subclassOfExcludedBaseStaysFalse() {
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(MTEWormholeGeneratorStubChild.class));
    }

    /** 排除优先：包含命中层之下（更上溯处）出现排除名时，整条链一票否决为 false */
    @Test
    public void exclusionOutranksInclusionOnSameChain() {
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(InclusionBelowExclusion.MTEMultiNqGeneratorLegacy.class));
    }

    /** null 入参：谓词与公开入口均 false（TE 摘除瞬间 mte 可能为 null） */
    @Test
    public void nullInputIsFalse() {
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(null));
        assertFalse(DeviceMachineTypes.isGeneratorMachine(null));
    }

    /** 深度上限：界内可命中、超限截断后 false 且不抛异常、Object 根自然终止 */
    @Test
    public void deepChainIsCappedWithoutException() {
        // D20 → D01 → MTENuclearReactor（根）：链长 21 < 32，包含名可达
        assertTrue(DeviceMachineTypes.nameChainHitsGenerator(DeepChain.D20.class));
        // D34 → ... → D01 → MTENuclearReactor（根）：根位于第 35 层，被 32 层上限截断
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(DeepChain.D34.class));
        // 正常短链（根即 Object）自然终止
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(Object.class));
    }

    /** 无关类不受污染：非白名单链一律 false（防宽匹配回归） */
    @Test
    public void unrelatedClassesAreFalse() {
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(String.class));
        assertFalse(DeviceMachineTypes.nameChainHitsGenerator(DeviceGeneratorNameChainTest.class));
    }
}
