// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.parts.CableBusContainer;
import appeng.tile.networking.TileCableBus;

/** Restore the original live container after a failed quantum-node replacement. */
@Mixin(value = TileCableBus.class, remap = false)
public interface TileCableBusAccessor {

    @Accessor("cb")
    void gtswn$setCableBus(CableBusContainer container);
}
