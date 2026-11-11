// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.assembly.nativeintegration;

/** Added only to the verified native controller by its required mixin. */
public interface NativeAssemblyController {

    NativeAssemblySession orderedAssemblySession();
}
