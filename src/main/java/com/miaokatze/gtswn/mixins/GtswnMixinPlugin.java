package com.miaokatze.gtswn.mixins;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** RC2 mixins with quiet class-presence guards for optional integrations. */
public class GtswnMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.miaokatze.gtswn.mixins.baubles.")
            || mixinClassName.startsWith("com.miaokatze.gtswn.mixins.journeymap.")
            || mixinClassName.startsWith("com.miaokatze.gtswn.mixins.waila.")) {
            // Optional mod; test resource presence before FML's mod list becomes available.
            boolean present = getClass().getClassLoader()
                .getResource(targetClassName.replace('.', '/') + ".class") != null;
            return present;
        }
        if (mixinClassName.startsWith("com.miaokatze.gtswn.mixins.mm.")) {
            // This runs before FML's mod list is ready. Probe class bytes without loading MM types.
            boolean present = getClass().getClassLoader()
                .getResource(targetClassName.replace('.', '/') + ".class") != null;
            return present;
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
