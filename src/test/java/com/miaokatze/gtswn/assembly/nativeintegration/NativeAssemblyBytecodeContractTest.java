package com.miaokatze.gtswn.assembly.nativeintegration;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.spongepowered.asm.lib.AnnotationVisitor;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.ClassVisitor;
import org.spongepowered.asm.lib.MethodVisitor;
import org.spongepowered.asm.lib.Opcodes;

/** Verifies the real pinned dependency, not a copied recipe-loop fixture. Runtime mixin application is separate. */
public class NativeAssemblyBytecodeContractTest {

    private static final String AL = "gregtech/common/tileentities/machines/multi/MTEAssemblyLine";
    private static final String MULTI = "gregtech/api/metatileentity/implementations/MTEMultiBlockBase";
    private static final String RECIPE = "gregtech/api/util/GTRecipe$RecipeAssemblyLine";

    @Test
    public void candidateGateOccursExactlyOnceBeforeNativeProcessingFieldsAndConsumption() throws Exception {
        MethodCalls calls = inspect(AL, "checkProcessing");
        for (String name : new String[] { "getItemConsumptionAmountArray", "maxParallelCalculatedByInputItems",
            "maxParallelCalculatedByInputFluids", "consumeInputItems", "consumeInputFluids" }) {
            assertEquals(name, 1, calls.count(RECIPE + "." + name));
        }
        int gate = calls.index(RECIPE + ".getItemConsumptionAmountArray");
        assertTrue(gate < calls.index("FIELD.lEUt"));
        assertTrue(gate < calls.index("FIELD.mMaxProgresstime"));
        assertTrue(gate < calls.index("FIELD.mOutputItems"));
        assertTrue(gate < calls.index(RECIPE + ".consumeInputItems"));
        assertTrue(calls.index(RECIPE + ".consumeInputItems") < calls.index(RECIPE + ".consumeInputFluids"));
    }

    @Test
    public void completionObservationPrecedesTheSameTickNextRecipeCheck() throws Exception {
        MethodCalls calls = inspect(MULTI, "runMachine");
        assertEquals(1, calls.count(MULTI + ".addItemOutputs"));
        assertEquals(1, calls.count(MULTI + ".outputAfterRecipe"));
        assertTrue(calls.index(MULTI + ".addItemOutputs") < calls.index(MULTI + ".outputAfterRecipe"));
        assertTrue(calls.index(MULTI + ".outputAfterRecipe") < calls.index(MULTI + ".checkRecipe"));
    }

    @Test
    public void structuralCompletionAndInputCallbackEntrancesAreUnambiguous() throws Exception {
        assertEquals(1, inspect(MULTI, "checkStructure").count(MULTI + ".onStructureCheckFinished"));
        MethodCalls check = inspect(MULTI, "checkRecipe", "()Z");
        assertEquals(1, check.count(MULTI + ".startRecipeProcessing"));
        assertEquals(1, check.count(MULTI + ".endRecipeProcessing"));
    }

    @Test
    public void everyRequiredMixinSelectorHasExactlyOneRealBytecodeTarget() throws Exception {
        for (String mixin : new String[] { "OrderedAssemblyControllerMixin", "OrderedAssemblyRegistrationMixin",
            "OrderedAssemblyLifecycleMixin" }) {
            String target = mixin.equals("OrderedAssemblyControllerMixin") ? AL : MULTI;
            for (Hook hook : hooks(mixin)) {
                assertEquals(hook.name + " require", 1, hook.require);
                assertEquals(hook.name + " expect", 1, hook.expect);
                assertEquals(hook.name + " allow", 1, hook.allow);
                assertEquals(hook.name + " actual exact descriptor matches", 1, injectionPoints(target, hook));
            }
        }
    }

    private static List<Hook> hooks(String mixin) throws Exception {
        List<Hook> hooks = new ArrayList<>();
        String resource = "com/miaokatze/gtswn/mixins/gregtech/" + mixin + ".class";
        try (InputStream input = NativeAssemblyBytecodeContractTest.class.getClassLoader()
            .getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                    String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {

                        @Override
                        public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                            if (!annotation.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                                && !annotation.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) return null;
                            Hook hook = new Hook();
                            hook.name = mixin + "." + method;
                            hooks.add(hook);
                            return new AnnotationVisitor(Opcodes.ASM9) {

                                @Override
                                public void visit(String name, Object value) {
                                    if (name.equals("require")) hook.require = (Integer) value;
                                    if (name.equals("expect")) hook.expect = (Integer) value;
                                    if (name.equals("allow")) hook.allow = (Integer) value;
                                }

                                @Override
                                public AnnotationVisitor visitArray(String name) {
                                    return new AnnotationVisitor(Opcodes.ASM9) {

                                        @Override
                                        public void visit(String ignored, Object value) {
                                            if (name.equals("method")) hook.selectors.add((String) value);
                                        }

                                        @Override
                                        public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
                                            return hook.atVisitor();
                                        }
                                    };
                                }

                                @Override
                                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                                    return name.equals("at") ? hook.atVisitor() : null;
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertFalse("Required injection methods found for " + mixin, hooks.isEmpty());
        return hooks;
    }

    private static int injectionPoints(String target, Hook hook) throws Exception {
        final int[] matches = { 0 };
        try (InputStream input = NativeAssemblyBytecodeContractTest.class.getClassLoader()
            .getResourceAsStream(target + ".class")) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {

                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                    String[] exceptions) {
                    if (!hook.selectors.contains(method + descriptor)) return null;
                    if (hook.at.equals("HEAD")) matches[0]++;
                    return new MethodVisitor(Opcodes.ASM9) {

                        @Override
                        public void visitInsn(int opcode) {
                            if (hook.at.equals("RETURN") && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
                                matches[0]++;
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            if (hook.at.equals("INVOKE") && hook.target.equals("L" + owner + ";" + name + desc))
                                matches[0]++;
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return matches[0];
    }

    private static MethodCalls inspect(String className, String method) throws Exception {
        return inspect(className, method, null);
    }

    private static MethodCalls inspect(String className, String method, String descriptor) throws Exception {
        String path = className + ".class";
        URL resource = NativeAssemblyBytecodeContractTest.class.getClassLoader()
            .getResource(path);
        assertNotNull("Real GT5U class on the test classpath", resource);
        assertTrue(
            "This contract is for the pinned .265 dependency: " + resource,
            resource.toString()
                .contains(".265"));
        MethodCalls calls = new MethodCalls();
        try (InputStream input = resource.openStream()) {
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                    String[] exceptions) {
                    if (!name.equals(method) || (descriptor != null && !descriptor.equals(desc))) return null;
                    return new MethodVisitor(Opcodes.ASM9) {

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            calls.add(owner + "." + name);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            if (opcode == Opcodes.PUTFIELD) calls.add("FIELD." + name);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return calls;
    }

    private static final class MethodCalls {

        private final List<String> ordered = new ArrayList<>();
        private final Map<String, Integer> counts = new HashMap<>();

        void add(String operation) {
            ordered.add(operation);
            counts.put(operation, counts.getOrDefault(operation, 0) + 1);
        }

        int count(String operation) {
            return counts.getOrDefault(operation, 0);
        }

        int index(String operation) {
            int index = ordered.indexOf(operation);
            assertTrue("Missing actual bytecode operation: " + operation + " in " + ordered, index >= 0);
            return index;
        }
    }

    private static final class Hook {

        String name;
        String at = "";
        String target = "";
        int require;
        int expect;
        int allow;
        final List<String> selectors = new ArrayList<>();

        AnnotationVisitor atVisitor() {
            return new AnnotationVisitor(Opcodes.ASM9) {

                @Override
                public void visit(String name, Object value) {
                    if (name.equals("value")) at = (String) value;
                    if (name.equals("target")) target = (String) value;
                }
            };
        }
    }
}
