// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.common.baubles;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import baubles.api.expanded.BaubleExpandedSlots;
import baubles.common.BaublesConfig;
import baubles.common.container.InventoryBaubles;
import baubles.common.container.SlotBauble;
import baubles.common.lib.PlayerHandler;

/** Runs the native container with the constructor-argument correction; this is not a Mixin transformer test. */
public class TerminalBaubleContainerIndexTest {

    private static final String TARGET = "baubles.common.container.ContainerPlayerExpanded";
    private static final String SLOT_CTOR = "(Lnet/minecraft/inventory/IInventory;Ljava/lang/String;III)V";

    @Test
    public void hiddenUnknownCellsKeepTheirInventoryPositionsInBothLayoutsAndGtitRingExpansion() throws Exception {
        List<String> assigned = assignments();
        List<String> previous = new ArrayList<>(assigned);
        boolean oldUnused = BaublesConfig.showUnusedSlots, oldLayout = BaublesConfig.useOldGuiRendering;
        int oldColumns = BaublesConfig.maxColumns;
        NativeLoader baselineLoader = new NativeLoader(false), fixedLoader = new NativeLoader(true);
        Class<?> baselineType = baselineLoader.loadClass(TARGET), fixedType = fixedLoader.loadClass(TARGET);
        assertEquals(
            "Both native layout branches must have their constructor argument corrected",
            2,
            fixedLoader.sites);
        Item markerItem = new Item();
        try {
            for (int hole : new int[] { -1, 0, 20, 26 }) {
                List<String> original = new ArrayList<>(
                    Arrays.asList(
                        TerminalBaubleSlotPolicy.withRequiredSlots(
                            new String[] { "amulet", "ring", "ring", "belt", "Terminal", "quiver", "charm_pouch",
                                "wings", "focus_pouch", "cape", "gauntlet", "charm", "title", "head",
                                "heartcanister_red", "heartcanister_yellow", "heartcanister_green", "universal" })));
                if (hole >= 0) original.add(hole, "unknown");
                for (boolean gtit : new boolean[] { false, true }) {
                    List<String> configured = new ArrayList<>();
                    boolean ringsAdded = false;
                    for (String type : original) {
                        if (!gtit || !"ring".equals(type)) configured.add(type);
                        else if (!ringsAdded) {
                            for (int i = 0; i < 10; i++) configured.add("ring");
                            ringsAdded = true;
                        }
                    }
                    for (boolean showUnused : new boolean[] { false, true })
                        for (boolean legacy : new boolean[] { false, true }) {
                            assigned.clear();
                            assigned.addAll(configured);
                            BaublesConfig.showUnusedSlots = showUnused;
                            BaublesConfig.useOldGuiRendering = legacy;
                            BaublesConfig.maxColumns = 4;
                            Player player = (Player) allocate(Player.class);
                            player.id = UUID.randomUUID();
                            player.worldObj = (WorldServer) allocate(WorldServer.class);
                            player.inventory = new InventoryPlayer(player);
                            NativeInventory inventory = new NativeInventory(player);
                            inventory.blockEvents = true;
                            PlayerHandler.setPlayerBaubles(player, inventory);
                            try {
                                Container baseline = construct(baselineType, player),
                                    fixed = construct(fixedType, player);
                                assertEquals(baseline.inventorySlots.size(), fixed.inventorySlots.size());
                                int fullIndex = 0;
                                for (int i = 0; i < fixed.inventorySlots.size(); i++) {
                                    Slot slot = (Slot) fixed.inventorySlots.get(i);
                                    Slot before = (Slot) baseline.inventorySlots.get(i);
                                    assertEquals(before.slotNumber, slot.slotNumber);
                                    assertEquals(before.xDisplayPosition, slot.xDisplayPosition);
                                    assertEquals(before.yDisplayPosition, slot.yDisplayPosition);
                                    if (!(slot instanceof SlotBauble bauble)) continue;
                                    while (!showUnused && "unknown".equals(configured.get(fullIndex))) fullIndex++;
                                    assertEquals(
                                        "Full assignment index, including repeated quantum cells",
                                        fullIndex,
                                        slot.getSlotIndex());
                                    assertEquals(configured.get(fullIndex), bauble.getSlotType());
                                    assertSame(inventory, slot.inventory);
                                    ItemStack marker = new ItemStack(markerItem);
                                    marker.stackTagCompound = new NBTTagCompound();
                                    marker.stackTagCompound.setInteger("originalIndex", fullIndex);
                                    slot.putStack(marker);
                                    assertSame(marker, inventory.getStackInSlot(fullIndex));
                                    assertEquals(
                                        fullIndex,
                                        slot.getStack().stackTagCompound.getInteger("originalIndex"));
                                    fullIndex++;
                                }
                            } finally {
                                PlayerHandler.clearPlayerBaubles(player);
                            }
                        }
                }
            }
        } finally {
            assigned.clear();
            assigned.addAll(previous);
            BaublesConfig.showUnusedSlots = oldUnused;
            BaublesConfig.useOldGuiRendering = oldLayout;
            BaublesConfig.maxColumns = oldColumns;
        }
    }

    private static Container construct(Class<?> type, Player player) throws Exception {
        return (Container) type.getConstructor(InventoryPlayer.class, boolean.class, EntityPlayer.class)
            .newInstance(player.inventory, false, player);
    }

    @SuppressWarnings("unchecked")
    private static List<String> assignments() throws Exception {
        Field field = BaubleExpandedSlots.class.getDeclaredField("assignedSlots");
        field.setAccessible(true);
        return (List<String>) field.get(null);
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field field = unsafe.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field.get(null), type);
    }

    public static final class Player extends EntityPlayerMP {

        UUID id;

        private Player() {
            super(null, null, null, null);
        }

        @Override
        public String getCommandSenderName() {
            return id.toString();
        }
    }

    private static final class NativeInventory extends InventoryBaubles {

        NativeInventory(EntityPlayer player) {
            super(player);
        }

        @Override
        public void syncSlotToClients(int slot) {}
    }

    /** Apply the same argument mapping offline; suppress only crafting callbacks requiring a running game. */
    private static final class NativeLoader extends ClassLoader {

        private final boolean correct;
        int sites;

        NativeLoader(boolean correct) {
            super(TerminalBaubleContainerIndexTest.class.getClassLoader());
            this.correct = correct;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(TARGET) && !name.startsWith(TARGET + "$")) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            try (InputStream input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                ClassReader reader = new ClassReader(input);
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {

                    @Override
                    public MethodVisitor visitMethod(int access, String method, String desc, String signature,
                        String[] exceptions) {
                        MethodVisitor output = super.visitMethod(access, method, desc, signature, exceptions);
                        if (name.equals(TARGET) && method.equals("onCraftMatrixChanged")) {
                            output.visitCode();
                            output.visitInsn(Opcodes.RETURN);
                            output.visitMaxs(0, 0);
                            output.visitEnd();
                            return null;
                        }
                        if (!correct || !name.equals(TARGET) || !method.equals("<init>")) return output;
                        return new MethodVisitor(Opcodes.ASM5, output) {

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String called, String descriptor,
                                boolean isInterface) {
                                if (owner.equals("baubles/common/container/SlotBauble") && called.equals("<init>")
                                    && descriptor.equals(SLOT_CTOR)) {
                                    sites++;
                                    super.visitVarInsn(Opcodes.ISTORE, 30);
                                    super.visitVarInsn(Opcodes.ISTORE, 31);
                                    super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        "baubles/api/expanded/BaubleExpandedSlots",
                                        "getCurrentSlotAssignments",
                                        "()[Ljava/lang/String;",
                                        false);
                                    super.visitFieldInsn(
                                        Opcodes.GETSTATIC,
                                        "baubles/common/BaublesConfig",
                                        "showUnusedSlots",
                                        "Z");
                                    super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        "com/miaokatze/gtswn/common/baubles/TerminalBaubleSlotPolicy",
                                        "inventoryIndex",
                                        "(I[Ljava/lang/String;Z)I",
                                        false);
                                    super.visitVarInsn(Opcodes.ILOAD, 31);
                                    super.visitVarInsn(Opcodes.ILOAD, 30);
                                }
                                super.visitMethodInsn(opcode, owner, called, descriptor, isInterface);
                            }
                        };
                    }
                }, 0);
                byte[] bytes = writer.toByteArray();
                loaded = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(loaded);
                return loaded;
            } catch (Exception error) {
                throw new ClassNotFoundException(name, error);
            }
        }
    }
}
