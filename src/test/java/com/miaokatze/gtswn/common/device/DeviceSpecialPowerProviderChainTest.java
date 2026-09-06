package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;

import org.junit.Test;

/**
 * 特殊机器权威 provider 预选词条/数值链纯 Java 单测（v1.8.3）：用测试桩类验证
 * {@link DeviceSpecialPowerProvider#resolveChain}（一次性巡检：缺失候选跳过、原序保留）与
 * {@link DeviceSpecialPowerProvider#readChain}（读到非零即停；字段异常/为 0 视为词条缺失
 * 继续下一候选；全链零归 0）。不依赖 Minecraft/GT5U 类。
 */
public class DeviceSpecialPowerProviderChainTest {

    /** 数值链测试桩：两个 long 候选字段（字段名即链候选名） */
    @SuppressWarnings("unused")
    private static class ChainStub {

        long primary = 0L;
        long secondary = 0L;
    }

    /** 异常注入桩：链上混入异类字段，反射读值抛 IllegalArgumentException → 该词条缺失跳过 */
    private static class OtherStub {

        long noise = 0L;
    }

    private static long get(ChainStub stub, String name) throws Exception {
        Field field = ChainStub.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(stub);
    }

    /** 一次性巡检：缺失候选静默跳过、存在候选按原序进链 */
    @Test
    public void resolveChainSkipsMissingAndKeepsOrder() throws Exception {
        Field[] chain = DeviceSpecialPowerProvider
            .resolveChain(ChainStub.class, "missing", "secondary", "anotherMissing", "primary");
        assertEquals(2, chain.length);
        assertEquals("secondary", chain[0].getName());
        assertEquals("primary", chain[1].getName());
        // 全部候选缺失 → 空链（entry 存活但无词条可用，读取恒 0）
        assertArrayEquals(new Field[0], DeviceSpecialPowerProvider.resolveChain(ChainStub.class, "missing"));
    }

    /** 数值链读取：读到非零即停；全链零归 0（不编造数值） */
    @Test
    public void readChainStopsAtFirstNonZero() throws Exception {
        ChainStub stub = new ChainStub();
        Field[] chain = DeviceSpecialPowerProvider.resolveChain(ChainStub.class, "primary", "secondary");
        // 全零 → 0
        assertEquals(0L, DeviceSpecialPowerProvider.readChain(chain, stub));
        // 首词条非零 → 即停取首词条
        stub.primary = 600L;
        stub.secondary = 800L;
        assertEquals(600L, DeviceSpecialPowerProvider.readChain(chain, stub));
        // 首词条为 0（词条缺失）→ 下一候选接管
        stub.primary = 0L;
        assertEquals(800L, DeviceSpecialPowerProvider.readChain(chain, stub));
        // 负值同样非零即停（方向语义由调用方按符号解释）
        stub.primary = 0L;
        stub.secondary = -800L;
        assertEquals(-800L, DeviceSpecialPowerProvider.readChain(chain, stub));
    }

    /** 链上混入异常词条（异类字段反射抛错）→ 视为缺失跳过，后续候选照常接管 */
    @Test
    public void readChainSkipsBrokenEntries() throws Exception {
        ChainStub stub = new ChainStub();
        stub.secondary = 42L;
        Field broken = OtherStub.class.getDeclaredField("noise");
        broken.setAccessible(true);
        Field[] chain = { broken, ChainStub.class.getDeclaredField("secondary") };
        assertEquals(42L, DeviceSpecialPowerProvider.readChain(chain, stub));
    }
}
