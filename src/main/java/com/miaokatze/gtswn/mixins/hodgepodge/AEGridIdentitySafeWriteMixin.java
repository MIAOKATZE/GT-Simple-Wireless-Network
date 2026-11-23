// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins.hodgepodge;

import org.spongepowered.asm.mixin.Mixin;

import com.miaokatze.gtswn.common.monitoring.ae.AEGridIdentityData;
import com.mitchej123.hodgepodge.mixins.interfaces.SafeWriteNBT;

@Mixin(value = AEGridIdentityData.class, remap = false)
public abstract class AEGridIdentitySafeWriteMixin implements SafeWriteNBT {
}
