package com.miaokatze.gtswn.assembly.nativeintegration;

import java.util.UUID;

/** A physical input position. Materials remain owned by the implementing hatch. */
public interface OrderedAssemblyMember {

    UUID orderedMemberId();

    NativeAssemblyBinding orderedAssemblyBinding();

    void orderedAssemblyStructureChecked(NativeAssemblySession session, boolean formed);
}
