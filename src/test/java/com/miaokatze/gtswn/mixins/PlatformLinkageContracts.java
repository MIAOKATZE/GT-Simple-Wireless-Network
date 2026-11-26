// SPDX-License-Identifier: AGPL-3.0-only
package com.miaokatze.gtswn.mixins;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Checks actual modern GTSWN bytecode against each original dev API; never initializes game classes. */
public final class PlatformLinkageContracts {

    private final Map<String, ClassNode> platform = new HashMap<>();
    private final List<String> failures = new ArrayList<>();
    private int references, selectors;

    public static void main(String[] args) throws Exception {
        PlatformLinkageContracts contract = new PlatformLinkageContracts();
        for (String path : args[2].split(File.pathSeparator)) {
            try (JarFile jar = new JarFile(path)) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.getName()
                        .startsWith("META-INF/")
                        || entry.getName()
                            .equals("module-info.class"))
                        continue;
                    if (!entry.getName()
                        .endsWith(".class")) continue;
                    try (InputStream in = jar.getInputStream(entry)) {
                        ClassNode type = read(in);
                        contract.platform.put(type.name, type);
                    }
                }
            }
        }
        for (String root : args[1].split(File.pathSeparator)) {
            if (!new File(root).isDirectory()) continue;
            try (Stream<java.nio.file.Path> paths = Files.walk(new File(root).toPath())) {
                for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths.filter(
                    p -> p.toString()
                        .endsWith(".class"))::iterator) {
                    try (InputStream in = Files.newInputStream(path)) {
                        contract.check(read(in));
                    }
                }
            }
        }
        if (!contract.failures.isEmpty()) throw new AssertionError(String.join("\n", contract.failures));
        if (contract.references < 100 || contract.selectors < 10)
            throw new AssertionError("Incomplete platform coverage");
        System.out.println(
            "GTNH " + args[0]
                + ": "
                + contract.references
                + " external member links and "
                + contract.selectors
                + " Mixin selectors verified; no game/GL execution");
    }

    private static ClassNode read(InputStream in) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static boolean owned(String owner) {
        return owner.startsWith("appeng/") || owner.startsWith("baubles/")
            || owner.startsWith("com/recursive_pineapple/matter_manipulator/")
            || owner.startsWith("mcp/mobius/waila/")
            || owner.startsWith("com/mitchej123/hodgepodge/")
            || owner.startsWith("com/gtnewhorizon/structurelib/")
            || owner.startsWith("com/gtnewhorizons/modularui/")
            || owner.startsWith("gregtech/")
            || owner.startsWith("bartworks/")
            || owner.startsWith("betterquesting/")
            || owner.startsWith("com/cleanroommc/modularui/")
            || owner.startsWith("com/gtnewhorizon/gtnhlib/")
            || owner.startsWith("codechicken/nei/")
            || owner.startsWith("com/gtnewhorizons/angelica/")
            || owner.startsWith("com/slprime/chromatictooltips/");
    }

    private ClassNode type(String name) {
        ClassNode node = platform.get(name);
        if (node != null || owned(name)) return node;
        try (InputStream in = getClass().getClassLoader()
            .getResourceAsStream(name + ".class")) {
            return in == null ? null : read(in);
        } catch (Exception e) {
            throw new AssertionError(name, e);
        }
    }

    private Integer member(String owner, String name, String desc, boolean field, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return null;
        ClassNode node = type(owner);
        if (node == null) return null;
        if (field) {
            for (FieldNode f : node.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f.access;
        } else {
            for (MethodNode m : node.methods)
                if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m.access;
            if ("<init>".equals(name)) return null;
        }
        Integer result = member(node.superName, name, desc, field, visited);
        if (result != null) return result;
        for (String itf : node.interfaces) {
            result = member(itf, name, desc, field, visited);
            if (result != null) return result;
        }
        return null;
    }

    private void link(String source, String owner, String name, String desc, boolean field, boolean isStatic) {
        if (!owned(owner)) return;
        references++;
        Integer access = member(owner, name, desc, field, new HashSet<>());
        if (access == null || ((access & Opcodes.ACC_STATIC) != 0) != isStatic)
            failures.add(source + " -> " + owner + "." + name + desc + " missing or staticness changed");
    }

    private void check(ClassNode source) {
        for (MethodNode method : source.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    link(
                        source.name,
                        call.owner,
                        call.name,
                        call.desc,
                        false,
                        call.getOpcode() == Opcodes.INVOKESTATIC);
                } else if (instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode call = (InvokeDynamicInsnNode) instruction;
                    for (Object argument : call.bsmArgs) {
                        if (argument instanceof Handle) linkHandle(source.name, (Handle) argument);
                    }
                } else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode call = (FieldInsnNode) instruction;
                    link(
                        source.name,
                        call.owner,
                        call.name,
                        call.desc,
                        true,
                        call.getOpcode() == Opcodes.GETSTATIC || call.getOpcode() == Opcodes.PUTSTATIC);
                }
            }
        }
        List<AnnotationNode> annotations = new ArrayList<>();
        if (source.visibleAnnotations != null) annotations.addAll(source.visibleAnnotations);
        if (source.invisibleAnnotations != null) annotations.addAll(source.invisibleAnnotations);
        for (AnnotationNode annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                String key = (String) annotation.values.get(i);
                if (!key.equals("targets") && !key.equals("value")) continue;
                for (Object value : (List<?>) annotation.values.get(i + 1)) {
                    String owner = value instanceof org.objectweb.asm.Type
                        ? ((org.objectweb.asm.Type) value).getInternalName()
                        : value.toString()
                            .replace('.', '/');
                    if (!owned(owner)) continue;
                    for (MethodNode method : source.methods)
                        checkSelectors(source.name, owner, method.visibleAnnotations);
                }
            }
        }
    }

    private void linkHandle(String source, Handle handle) {
        int tag = handle.getTag();
        boolean field = tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC;
        boolean isStatic = tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC || tag == Opcodes.H_INVOKESTATIC;
        link(source, handle.getOwner(), handle.getName(), handle.getDesc(), field, isStatic);
    }

    private void checkSelectors(String source, String owner, List<AnnotationNode> annotations) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            if (annotation.values == null) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (!annotation.values.get(i)
                    .equals("method")) continue;
                Object values = annotation.values.get(i + 1);
                for (Object value : values instanceof List<?> ? (List<?>) values
                    : java.util.Collections.singletonList(values)) {
                    String selector = value.toString();
                    int descriptor = selector.indexOf('(');
                    String name = descriptor < 0 ? selector : selector.substring(0, descriptor);
                    String desc = descriptor < 0 ? null : selector.substring(descriptor);
                    selectors++;
                    if (member(owner, name, desc, false, new HashSet<>()) == null)
                        failures.add(source + " Mixin selector missing: " + owner + "." + selector);
                }
            }
        }
    }
}
