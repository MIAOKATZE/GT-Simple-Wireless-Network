// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.assembly.nativeintegration;

import java.util.UUID;

/** A physical input position. Materials remain owned by the implementing hatch. */
public interface OrderedAssemblyMember {

    UUID orderedMemberId();

    NativeAssemblyBinding orderedAssemblyBinding();

    void orderedAssemblyStructureChecked(NativeAssemblySession session, boolean formed);
}
