// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.ae2;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import appeng.me.Grid;
import appeng.me.GridStorage;

/** Read the real native storage without invoking a node's serialization or native save callbacks. */
@Mixin(value = Grid.class, remap = false)
public interface MonitorGridStorageAccessor {

    @Accessor(value = "myStorage", remap = false)
    GridStorage gtswn$getMonitorStorage();
}
