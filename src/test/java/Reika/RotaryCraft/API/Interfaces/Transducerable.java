// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package Reika.RotaryCraft.API.Interfaces;

import java.util.ArrayList;

import net.minecraft.world.World;

/** Test-only optional API method shape required by AE2's untransformed RotaryCraft ancestor. */
public interface Transducerable {

    ArrayList<String> getMessages(World world, int x, int y, int z, int side);
}
