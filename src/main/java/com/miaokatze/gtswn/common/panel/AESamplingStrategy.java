package com.miaokatze.gtswn.common.panel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * AE 采样策略（G4 = v1.7.14）：{@link AEMonitorController} 采样段的存量收集抽象。
 * <p>
 * 策略双实现并存 + 运行时同 tick 对账（决策 D10）：
 * <ul>
 * <li>{@link PerKey}：现状逐 key findPrecise 路径的封装（每 key 一次独立查询），语义与
 * G4 改造前 {@code sampleAENetwork} 逐段查询逐字一致，兼作对账基准与降级兜底</li>
 * <li>{@link SingleIteration}：一次迭代 AE2 storage list 匹配全部被监视 key——每屏
 * gridProxy 各自独立、跨维度屏各自世界，收集天然按本屏节点分组（分组键=grid 身份而非
 * 全局）；AE2 网络重组（断电/拆线）后节点失效抛 GridAccessException 走全 0 返回，
 * 由控制器侧对账失配降级兜底</li>
 * </ul>
 * 策略实现无状态，均为单例；选择/切换/对账/降级状态由 {@link AEMonitorController} 持有。
 * AE2 gridProxy 只读查询经 {@link AEMonitorController.AEQuery} 接口注入（proxy 调用权
 * 留 TE，域对象不触碰 gridProxy——深查 §三-2 硬约束）。
 */
public interface AESamplingStrategy {

    /**
     * 收集全部被监视 key 的当前 AE 存量。
     *
     * @param query  AE2 gridProxy 只读查询口
     * @param items  待查询物品（调用方按 chartItem → 监控列表顺序合入）
     * @param fluids 待查询流体（chartFluid → 监控列表）
     * @return aeKey → 存量；aeKey 生成失败的 stack 跳过，网络中不存在的 key 以 0 计
     *         （与逐 key findPrecise 未命中返回 0 的现状语义一致）；同 aeKey 的多个 stack
     *         以首个为准（与原"首段先取采样锁写入、后段锁失败跳过"的口径一致）
     */
    Map<String, Long> collect(AEMonitorController.AEQuery query, List<ItemStack> items, List<FluidStack> fluids);

    /** 策略名（对账/降级日志用） */
    String name();

    /**
     * 逐 key 查询实现（现状路径封装）：每 key 一次 {@link AEMonitorController.AEQuery#itemAmount} /
     * {@link AEMonitorController.AEQuery#fluidAmount}（TE 内 findPrecise），putIfAbsent 保持
     * 同 aeKey 首个 stack 优先。
     */
    final class PerKey implements AESamplingStrategy {

        public static final PerKey INSTANCE = new PerKey();

        private PerKey() {}

        @Override
        public Map<String, Long> collect(AEMonitorController.AEQuery query, List<ItemStack> items,
            List<FluidStack> fluids) {
            Map<String, Long> result = new HashMap<>();
            for (ItemStack stack : items) {
                String key = AEMonitorController.aeKey(stack);
                if (key == null) continue;
                result.putIfAbsent(key, Long.valueOf(query.itemAmount(stack)));
            }
            for (FluidStack fluid : fluids) {
                String key = AEMonitorController.aeKey(fluid);
                if (key == null) continue;
                result.putIfAbsent(key, Long.valueOf(query.fluidAmount(fluid)));
            }
            return result;
        }

        @Override
        public String name() {
            return "per-key";
        }
    }

    /**
     * 一次迭代实现：经 {@link AEMonitorController.AEQuery#bulkAmounts} 单遍遍历 AE2
     * item/fluid storage list，以 aeKey 字符串匹配收集全部被监视 key（TE 侧实现，
     * 未命中 key 以 0 计）。同 item+meta 异 NBT 变体的迭代序歧义由控制器同 tick 对账兜底。
     */
    final class SingleIteration implements AESamplingStrategy {

        public static final SingleIteration INSTANCE = new SingleIteration();

        private SingleIteration() {}

        @Override
        public Map<String, Long> collect(AEMonitorController.AEQuery query, List<ItemStack> items,
            List<FluidStack> fluids) {
            return query.bulkAmounts(items, fluids);
        }

        @Override
        public String name() {
            return "single-iteration";
        }
    }
}
