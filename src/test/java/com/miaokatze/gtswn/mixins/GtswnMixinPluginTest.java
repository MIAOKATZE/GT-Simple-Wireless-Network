package com.miaokatze.gtswn.mixins;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class GtswnMixinPluginTest {

    private static final String PREFIX = "com.miaokatze.gtswn.mixins.";
    private static final AtomicBoolean TARGET_INITIALIZED = new AtomicBoolean();
    private final GtswnMixinPlugin plugin = new GtswnMixinPlugin();

    @Test
    public void absentOptionalTargetsNeverApplyTheirMixins() {
        for (String integration : new String[] { "mm", "baubles", "journeymap", "waila" }) {
            assertFalse(
                integration,
                plugin.shouldApplyMixin("missing.optional.mod.Target", PREFIX + integration + ".Test"));
        }
    }

    @Test
    public void presentOptionalTargetsRemainEnabled() {
        for (String integration : new String[] { "mm", "baubles", "journeymap", "waila" }) {
            assertTrue(integration, plugin.shouldApplyMixin("java.lang.String", PREFIX + integration + ".Test"));
        }
    }

    @Test
    public void presenceProbeDoesNotInitializeAnOptionalModClass() {
        assertFalse(TARGET_INITIALIZED.get());
        assertTrue(
            plugin.shouldApplyMixin(
                "com.miaokatze.gtswn.mixins.GtswnMixinPluginTest$InitializationTrap",
                PREFIX + "mm.Test"));
        assertFalse("Optional APIs must not initialize during the early presence probe", TARGET_INITIALIZED.get());
    }

    @Test
    public void requiredTargetsAreNotSilentlyDisabledByTheOptionalGuard() {
        for (String integration : new String[] { "ae2", "gregtech", "minecraft" }) {
            assertTrue(
                integration,
                plugin.shouldApplyMixin("missing.required.mod.Target", PREFIX + integration + ".Test"));
        }
    }

    @Test
    public void matterManipulatorStaticRemovalRetainsTheVirtualPartFilter() throws Exception {
        assertTrue(
            Modifier.isStatic(
                com.miaokatze.gtswn.mixins.mm.AEAnalysisResultMixin.class
                    .getDeclaredMethod(
                        "gtswn$transferablePart",
                        appeng.api.parts.IPartHost.class,
                        net.minecraftforge.common.util.ForgeDirection.class)
                    .getModifiers()));
        final int[] targets = { 0 }, getPartCalls = { 0 };
        try (InputStream input = getClass().getClassLoader()
            .getResourceAsStream("com/recursive_pineapple/matter_manipulator/common/building/AEAnalysisResult.class")) {
            assertNotNull("The real pinned MatterManipulator class is on the test classpath", input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM5) {

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                    if (!name.equals("removePart")) return null;
                    assertTrue("Formal MM removal is static", (access & Opcodes.ACC_STATIC) != 0);
                    targets[0]++;
                    return new MethodVisitor(Opcodes.ASM5) {

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                            if (owner.equals("appeng/api/parts/IPartHost") && name.equals("getPart")) getPartCalls[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertEquals("One formal removePart target", 1, targets[0]);
        assertEquals("The removable part still passes through the filtered call site", 1, getPartCalls[0]);
    }

    public static final class InitializationTrap {

        static {
            TARGET_INITIALIZED.set(true);
        }
    }
}
