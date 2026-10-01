package com.miaokatze.gtswn.mixins;

import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.miaokatze.gtswn.common.quantum.Ae2PathingCompat;

/**
 * mixin 配置插件（计划 gtswn_ae2_face_channel_fix_plan_20261001124511 §3.3）。
 *
 * <p>
 * 唯一职责：按共享探针 {@link Ae2PathingCompat#hasControllerFaceModel()} 门控
 * {@code PathingCalculationMixin} 的应用。AE2 ≤1050（beta-3 代际）下嵌套类
 * {@code PathingCalculation$ControllerFace} 不存在 → 谓词必 false → mixin 不应用，
 * 行为与 v1.8.13/v1.8.14 完全一致（旧存档零影响）。探测异常同走 false 保守侧。
 * </p>
 */
public class GtswnMixinPlugin implements IMixinConfigPlugin {

    private static final String PATHING_MIXIN_CLASS = "com.miaokatze.gtswn.mixins.ae2.PathingCalculationMixin";

    private static final Logger LOG = LogManager.getLogger("GTSWN");

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (PATHING_MIXIN_CLASS.equals(mixinClassName)) {
            boolean apply = Ae2PathingCompat.hasControllerFaceModel();
            // 可 grep 的应用/跳过证据（1050 真机负向验证也依赖此行）
            LOG.info("[GTSWN] AE2 face-model probe = {}; mixin {}", apply, apply ? "applied" : "skipped");
            return apply;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
