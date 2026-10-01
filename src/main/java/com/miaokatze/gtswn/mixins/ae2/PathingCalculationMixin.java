package com.miaokatze.gtswn.mixins.ae2;

import java.util.Map;
import java.util.function.Function;

import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import appeng.me.pathfinding.PathingCalculation;

/**
 * 修复 AE2 rv3-beta-1073+ 下「无方向控制器连接共享单一 ControllerFace(32)」的回归
 * （计划 gtswn_ae2_face_channel_fix_plan_20261001124511 §3.1）。
 *
 * <p>
 * {@code PathingCalculation} 构造器内两处（TileController 段与 TileCreativeEnergyController
 * 段）{@code faces.computeIfAbsent(gc.getDirection(node), ignored -> new ControllerFace(...))}
 * 按 {@link ForgeDirection} EnumMap 分桶：gtswn 量子节点经 {@code createGridConnection}
 * 的桥接连接方向恒为 UNKNOWN，全部落入同一桶 → 多节点合计只能分配 32 频道。
 * </p>
 *
 * <p>
 * 修法：UNKNOWN 方向时绕过分桶，直接调 {@code mappingFunction.apply(null)} 造出一个
 * 全新 ControllerFace（原 lambda 形参 {@code ignored} 忽略实参，捕获仅 this 字段）→
 * 每条无方向连接独占一个 face(32)，且不写入 EnumMap。其余方向走原 computeIfAbsent 语义。
 * </p>
 *
 * <p>
 * 零包私有访问：handler 用擦除签名（Map/Object/Function），不引用 ControllerFace 类型。
 * 一个 handler 默认命中构造器内两处调用点（字节码签名一致）。应用与否由
 * {@code GtswnMixinPlugin} 按共享探针门控；应用失败（目标变动）即启动崩溃，无静默错配。
 * </p>
 */
@Mixin(value = PathingCalculation.class, remap = false)
public abstract class PathingCalculationMixin {

    @Redirect(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;",
            remap = false))
    private Object gtswn$independentFaceForUnknownDirection(Map<?, ?> faces, Object direction,
        Function<?, ?> mappingFunction) {
        if (direction == ForgeDirection.UNKNOWN) {
            // 原 lambda 忽略实参：apply(null) 造出独立新面，不经 EnumMap 分桶 → 不共享
            return mappingFunction.apply(null);
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> castFaces = (Map<Object, Object>) faces;
        return castFaces.computeIfAbsent(direction, (Function<Object, Object>) mappingFunction);
    }
}
