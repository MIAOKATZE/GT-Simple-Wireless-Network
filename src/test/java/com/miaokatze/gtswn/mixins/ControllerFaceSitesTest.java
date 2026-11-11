// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.LdcInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;

/** Two invocations in one branch would satisfy the injection count but leave the other controller unhandled. */
public class ControllerFaceSitesTest {

    @Test
    public void pinnedConstructorHasOneFaceLookupPerControllerKind() throws Exception {
        ClassNode type = new ClassNode();
        try (InputStream input = getClass().getResourceAsStream("/appeng/me/pathfinding/PathingCalculation.class")) {
            assertNotNull("Missing AE2 class bytes", input);
            new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        List<String> controllers = new ArrayList<>();
        for (MethodNode method : type.methods) {
            if (!method.name.equals("<init>") || !method.desc.equals("(Lappeng/api/networking/IGrid;)V")) continue;
            String controller = null;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.owner.equals("appeng/api/networking/IGrid") && call.name.equals("getMachines")) {
                    AbstractInsnNode argument = call.getPrevious();
                    assertEquals("Expected controller class literal", Opcodes.LDC, argument.getOpcode());
                    controller = ((Type) ((LdcInsnNode) argument).cst).getInternalName();
                }
                if (call.owner.equals("java/util/Map") && call.name.equals("computeIfAbsent")
                    && call.desc.equals("(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;")) {
                    assertNotNull("Face lookup before a controller traversal", controller);
                    controllers.add(controller);
                }
            }
        }
        Collections.sort(controllers);
        assertEquals(
            Arrays
                .asList("appeng/tile/networking/TileController", "appeng/tile/networking/TileCreativeEnergyController"),
            controllers);
    }
}
