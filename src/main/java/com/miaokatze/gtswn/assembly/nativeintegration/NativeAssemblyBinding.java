package com.miaokatze.gtswn.assembly.nativeintegration;

import gregtech.api.util.GTRecipe.RecipeAssemblyLine;

/** The owning assembly supplies its lease, staged recipe identity and batch observations. */
public interface NativeAssemblyBinding {

    boolean validateNativeState(NativeAssemblySession session);

    boolean acceptsNativeCandidate(NativeAssemblySession session, RecipeAssemblyLine recipe);

    /** complete means that both native consumption calls returned and their real deltas match. */
    void onNativeConsumption(NativeAssemblySession session, RecipeAssemblyLine recipe, int parallel,
        long[] itemConsumed, long[] fluidConsumed, boolean complete);

    /** A local native completion boundary, never an AE delivery receipt. */
    void onNativeFinished(NativeAssemblySession session);

    void onNativeStopped(NativeAssemblySession session, String reason);
}
