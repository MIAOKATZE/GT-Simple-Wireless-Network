// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.mixins;

import static org.junit.Assert.*;

import java.io.InputStream;

import org.junit.Test;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;

/** Reads each selected release's real dependency bytes; this does not claim live Mixin application. */
public class GtnhDependencyBytecodeContractTest {

    @Test
    public void coverLegacyGuiBridgeAndModernFactoryContractsStillExist() throws Exception {
        ClassNode cover = read("gregtech/common/covers/Cover");
        method(cover, "<init>", "(Lgregtech/api/covers/CoverContext;Lgregtech/api/interfaces/ITexture;)V");
        method(
            cover,
            "createWindow",
            "(Lgregtech/api/gui/modularui/CoverUIBuildContext;)Lcom/gtnewhorizons/modularui/api/screen/ModularWindow;");
        method(
            cover,
            "createCoverContainer",
            "(Lnet/minecraft/entity/player/EntityPlayer;)Lcom/gtnewhorizons/modularui/common/internal/wrapper/ModularUIContainer;");
        method(
            cover,
            "createCoverWindow",
            "(Lnet/minecraft/entity/player/EntityPlayer;)Lcom/gtnewhorizons/modularui/api/screen/ModularWindow;");
        method(
            cover,
            "buildUI",
            "(Lgregtech/api/modularui2/CoverGuiData;Lcom/cleanroommc/modularui/value/sync/PanelSyncManager;Lcom/cleanroommc/modularui/screen/UISettings;)Lcom/cleanroommc/modularui/screen/ModularPanel;");
    }

    @Test
    public void coverClipboardRedirectsHaveExactlyOneActualInvocation() throws Exception {
        ClassNode tool = read("gregtech/common/items/behaviors/BehaviourCoverTool");
        assertEquals(
            1,
            calls(
                tool,
                "saveCoverToNBT",
                "gregtech/common/covers/Cover",
                "writeToNBT",
                "(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraft/nbt/NBTTagCompound;"));
        assertEquals(
            1,
            calls(
                tool,
                "onItemUseFirst",
                "gregtech/api/interfaces/tileentity/ICoverable",
                "updateAttachedCover",
                "(ILnet/minecraftforge/common/util/ForgeDirection;Lnet/minecraft/nbt/NBTTagCompound;)V"));
    }

    @Test
    public void monitoringTickGroupHasOneDispatchAndEnergyHooksExist() throws Exception {
        ClassNode base = read("gregtech/api/metatileentity/BaseMetaTileEntity");
        String descriptor = "(Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;J)V";
        assertEquals(
            1,
            calls(
                base,
                "doPostTick",
                "gregtech/api/interfaces/metatileentity/IMetaTileEntity",
                "onPostTick",
                descriptor)
                + calls(base, "doPostTick", "gregtech/api/metatileentity/MetaTileEntity", "onPostTick", descriptor));
        method(read("gregtech/api/metatileentity/MetaTileEntity"), "setEUVar", "(J)V");
        method(
            read("gregtech/common/misc/WirelessNetworkManager"),
            "addEUToGlobalEnergyMap",
            "(Ljava/util/UUID;Ljava/math/BigInteger;)Z");
    }

    @Test
    public void quantumSecurityHooksKeepNativeMembershipAndSecurityCalls() throws Exception {
        ClassNode grid = read("appeng/me/Grid");
        method(grid, "add", "(Lappeng/me/GridNode;)V");
        method(grid, "remove", "(Lappeng/me/GridNode;)V");
        method(
            grid,
            "postEvent",
            "(Lappeng/api/networking/events/MENetworkEvent;)Lappeng/api/networking/events/MENetworkEvent;");
        assertEquals(
            1,
            calls(
                read("appeng/me/GridConnection"),
                "<init>",
                "appeng/util/Platform",
                "securityCheck",
                "(Lappeng/me/GridNode;Lappeng/me/GridNode;)Z"));
    }

    private static ClassNode read(String name) throws Exception {
        ClassNode type = new ClassNode();
        try (InputStream input = GtnhDependencyBytecodeContractTest.class.getClassLoader()
            .getResourceAsStream(name + ".class")) {
            assertNotNull("Actual dependency class: " + name, input);
            new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return type;
    }

    private static void method(ClassNode type, String name, String descriptor) {
        int matches = 0;
        for (MethodNode method : type.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) matches++;
        }
        assertEquals(type.name + "." + name + descriptor, 1, matches);
    }

    private static int calls(ClassNode type, String method, String owner, String name, String descriptor) {
        int matches = 0;
        for (MethodNode candidate : type.methods) {
            if (!candidate.name.equals(method)) continue;
            for (AbstractInsnNode instruction : candidate.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(descriptor)) matches++;
            }
        }
        return matches;
    }
}
