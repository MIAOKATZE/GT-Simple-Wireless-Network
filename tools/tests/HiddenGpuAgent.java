import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Test-only SDL window/input guard. Never use with an interactive client. */
public final class HiddenGpuAgent implements ClassFileTransformer {
    private static final String DISPLAY = "org/lwjglx/opengl/Display", MOUSE = "org/lwjglx/input/Mouse";
    private static final Map<String, String> hashes = new HashMap<>();
    private static final AtomicLong swaps = new AtomicLong();
    private static Path evidence;
    private static volatile boolean displayPatched, mousePatched;

    public static void main(String[] args) throws Exception {
        if (args[0].equals("--verify-class-loading")) {
            Class.forName(DISPLAY.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
            Class.forName(MOUSE.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
            if (!displayPatched || !mousePatched) throw new IllegalStateException("Guards not installed");
            System.out.println("VERIFIED_GUARDED_CLASSES_WITHOUT_INITIALIZATION");
            return;
        }
        audit(Path.of(args[0]));
        System.out.println("AUDIT_OK " + hashes);
    }

    public static void premain(String args, Instrumentation instrumentation) {
        try {
            evidence = Path.of(System.getProperty("gtswn.hiddenEvidence"));
            audit(Path.of(args));
            // RFB may resolve the injected hook through another loader. Bootstrap
            // System properties hold the shared counter, avoiding split static state.
            System.getProperties().put("gtswn.hiddenSwapCounter", swaps);
            append("PREMAIN_AUDIT_OK " + hashes);
            instrumentation.addTransformer(new HiddenGpuAgent());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> append("EXIT display=" + displayPatched
                + " mouse=" + mousePatched + " realSwapCalls=" + swaps.get()), "hidden-gpu-evidence"));
        } catch (Throwable failure) { fail(failure); }
    }

    private static void audit(Path jar) throws Exception {
        try (JarFile input = new JarFile(jar.toFile())) {
            for (String target : List.of(DISPLAY, MOUSE)) {
                byte[] bytes = input.getInputStream(input.getJarEntry(target + ".class")).readAllBytes();
                patch(target, bytes);
                hashes.put(target, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
        }
    }

    @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
            ProtectionDomain domain, byte[] bytes) {
        if (!hashes.containsKey(name)) return null;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!hash.equals(hashes.get(name))) throw new IllegalStateException("Changed guarded class " + name);
            byte[] patched = patch(name, bytes);
            if (name.equals(DISPLAY)) displayPatched = true; else mousePatched = true;
            append("TRANSFORM_OK " + name);
            return patched;
        } catch (Throwable failure) { fail(failure); return null; }
    }

    private static byte[] patch(String name, byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Map<String,Integer> actual = new TreeMap<>();
        int swapSites = 0, hiddenTrue = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof LdcInsnNode constant && constant.cst.equals("SDL.window.create.hidden")) {
                    AbstractInsnNode next = instruction.getNext();
                    if (next != null && next.getOpcode() == Opcodes.ICONST_1) hiddenTrue++;
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;
                boolean block = name.equals(DISPLAY) && call.owner.equals("org/lwjgl/sdl/SDLVideo")
                    && Set.of("SDL_ShowWindow", "SDL_RestoreWindow", "SDL_MaximizeWindow", "SDL_SetWindowFullscreen").contains(call.name);
                block |= name.equals(MOUSE) && ((call.owner.equals("org/lwjgl/sdl/SDLMouse")
                    && Set.of("SDL_WarpMouseInWindow", "SDL_SetWindowRelativeMouseMode", "SDL_SetCursor").contains(call.name))
                    || (call.owner.equals("org/lwjglx/input/Cursor") && call.name.equals("sdlSet")));
                if (block) {
                    actual.merge(call.name + call.desc, 1, Integer::sum);
                    InsnList replacement = new InsnList();
                    Type[] arguments = Type.getArgumentTypes(call.desc);
                    for (int index = arguments.length - 1; index >= 0; index--)
                        replacement.add(new InsnNode(arguments[index].getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
                    if (call.getOpcode() != Opcodes.INVOKESTATIC) replacement.add(new InsnNode(Opcodes.POP));
                    if (Type.getReturnType(call.desc).getSort() == Type.BOOLEAN) replacement.add(new InsnNode(Opcodes.ICONST_1));
                    else if (Type.getReturnType(call.desc).getSort() != Type.VOID) throw new IllegalStateException("Unexpected return " + call.desc);
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                } else if (name.equals(DISPLAY) && call.name.equals("SDL_GL_SwapWindow") && call.desc.equals("(J)Z")) {
                    // Keep the real native swap and its boolean result; count only after it returns.
                    method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, "HiddenGpuAgent", "swapped", "()V", false));
                    swapSites++;
                }
            }
            // Minecraft otherwise skips world rendering for hidden SDL windows. Input focus stays false.
            if (name.equals(DISPLAY) && method.name.equals("isVisible") && method.desc.equals("()Z")) {
                method.instructions.clear(); method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(new InsnNode(Opcodes.ICONST_1));
                method.instructions.add(new InsnNode(Opcodes.IRETURN));
            }
        }
        Map<String,Integer> expected = name.equals(DISPLAY)
            ? Map.of("SDL_ShowWindow(J)Z",1,"SDL_RestoreWindow(J)Z",1,"SDL_MaximizeWindow(J)Z",1,"SDL_SetWindowFullscreen(JZ)Z",1)
            : Map.of("SDL_WarpMouseInWindow(JFF)V",3,"SDL_SetWindowRelativeMouseMode(JZ)Z",1,"SDL_SetCursor(J)Z",1,"sdlSet()V",1);
        if (!actual.equals(expected) || (name.equals(DISPLAY) && (hiddenTrue != 1 || swapSites != 1)))
            throw new IllegalStateException("Unsupported lwjgl3ify guard sites " + name + ": " + actual
                + " hidden=" + hiddenTrue + " swaps=" + swapSites);
        ClassWriter output = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(output);
        return output.toByteArray();
    }

    public static void swapped() {
        Object counter = System.getProperties().get("gtswn.hiddenSwapCounter");
        if (!(counter instanceof AtomicLong shared)) {
            Runtime.getRuntime().halt(95);
            return;
        }
        if (shared.incrementAndGet() == 1) {
            try {
                Files.writeString(Path.of(System.getProperty("gtswn.hiddenEvidence")),
                    "FIRST_REAL_NATIVE_SWAP_RETURNED\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable failure) { Runtime.getRuntime().halt(94); }
        }
    }
    private static synchronized void append(String message) {
        try { if (evidence != null) Files.writeString(evidence, message + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (Throwable failure) { System.err.println(failure); Runtime.getRuntime().halt(97); }
    }
    private static void fail(Throwable failure) {
        failure.printStackTrace(); append("FAIL_CLOSED " + failure); Runtime.getRuntime().halt(96);
    }
}
