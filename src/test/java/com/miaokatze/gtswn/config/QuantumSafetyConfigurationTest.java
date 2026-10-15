package com.miaokatze.gtswn.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.command.CommandHandler;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.common.config.Configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.miaokatze.gtswn.common.command.CommandGTSWN;
import com.miaokatze.gtswn.common.command.CommandGTSWNClient;

import cpw.mods.fml.relauncher.FMLInjectionData;

public class QuantumSafetyConfigurationTest {

    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    private final Map<Field, Object> originalConfig = new HashMap<>();
    private final CommandGTSWN command = new CommandGTSWN();
    private final List<String> messages = new ArrayList<>();
    private Field minecraftHome;
    private Object originalHome;
    private File file;

    @Before
    public void setUp() throws Exception {
        for (Field field : Config.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true);
                originalConfig.put(field, field.get(null));
            }
        }
        minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        originalHome = minecraftHome.get(null);
        minecraftHome.set(null, directory.getRoot());
        file = directory.newFile("gtswn_ae.cfg");
        Configuration initial = new Configuration(file);
        initial.get("unrelated", "preserved", 42)
            .set(42);
        initial.save();
        Config.synchronizeAEConfiguration(new Configuration(file));
    }

    @After
    public void restore() throws Exception {
        for (Map.Entry<Field, Object> entry : originalConfig.entrySet()) {
            entry.getKey()
                .set(null, entry.getValue());
        }
        minecraftHome.set(null, originalHome);
    }

    @Test
    public void realServerCommandTogglesAndPersistsWithoutTouchingUnrelatedSettings() {
        CommandHandler handler = new CommandHandler();
        handler.registerCommand(command);
        assertEquals(1, handler.executeCommand(sender(true), "/gtswn safety off"));
        assertFalse(Config.quantumSafetyEnabled);
        assertFalse(persistedSafety());
        assertEquals(
            42,
            new Configuration(file).get("unrelated", "preserved", 0)
                .getInt());
        Config.quantumSafetyEnabled = true;
        Config.synchronizeAEConfiguration(new Configuration(file));
        assertFalse(Config.quantumSafetyEnabled);
        assertEquals(1, handler.executeCommand(sender(true), "/gtswn safety on"));
        assertTrue(Config.quantumSafetyEnabled);
        assertTrue(persistedSafety());
        Config.quantumSafetyEnabled = false;
        Config.synchronizeAEConfiguration(new Configuration(file));
        assertTrue(Config.quantumSafetyEnabled);
    }

    @Test
    public void oldConfigurationDefaultsOnEvenAfterAnOffSession() throws Exception {
        assertTrue(Config.saveQuantumSafetyEnabled(false));
        File oldConfig = directory.newFile("old-config.cfg");
        Config.synchronizeAEConfiguration(new Configuration(oldConfig));
        assertTrue(Config.quantumSafetyEnabled);
        assertTrue(
            new Configuration(oldConfig).get("ae2", "quantumSafetyEnabled", false)
                .getBoolean());
    }

    @Test
    public void dispatcherEnforcesLevelFourAndAcceptsConsoleWithoutAPlayer() {
        assertEquals(4, command.getRequiredPermissionLevel());
        CommandHandler handler = new CommandHandler();
        handler.registerCommand(command);
        assertFalse(command.canCommandSenderUseCommand(sender(false)));
        assertEquals(0, handler.executeCommand(sender(false), "/gtswn safety off"));
        assertTrue(Config.quantumSafetyEnabled);
        assertTrue(persistedSafety());
        assertTrue(command.canCommandSenderUseCommand(sender(true)));
        assertEquals(1, handler.executeCommand(sender(true), "/gtswn safety off"));
        assertFalse(Config.quantumSafetyEnabled);
    }

    @Test
    public void invalidArgumentsNeverMutateRuntimeOrConfiguration() {
        String[][] invalid = { { "safety" }, { "safety", "false" }, { "safety", "0" }, { "safety", "OFF" },
            { "safety", "status" }, { "safety", "off", "extra" } };
        for (String[] args : invalid) {
            try {
                command.processCommand(sender(true), args);
                fail("Expected usage error for " + Arrays.toString(args));
            } catch (WrongUsageException expected) {
                assertEquals("gtswn.command.safety.usage", expected.getMessage());
            }
            assertTrue(Config.quantumSafetyEnabled);
            assertTrue(persistedSafety());
        }
    }

    @Test
    public void failedSaveReportsFailureAndPreservesRuntimeState() throws Exception {
        Field path = Config.class.getDeclaredField("aeConfigFile");
        path.setAccessible(true);
        path.set(null, new File(directory.newFile("not-a-directory"), "gtswn_ae.cfg"));
        command.processCommand(sender(true), new String[] { "safety", "off" });
        assertTrue(Config.quantumSafetyEnabled);
        assertTrue(persistedSafety());
        assertTrue(
            messages.stream()
                .anyMatch(message -> message.contains("gtswn.command.safety.save_failed")));
        assertFalse(
            messages.stream()
                .anyMatch(message -> message.contains("gtswn.command.safety.saved")));
        path.set(null, null);
        assertFalse(Config.saveQuantumSafetyEnabled(false));
        assertTrue(Config.quantumSafetyEnabled);
    }

    @Test
    public void clientForwardsAllSafetyArgumentsWithoutMutatingItsLocalConfiguration() {
        List<String> forwarded = new ArrayList<>();
        CommandGTSWNClient client = new CommandGTSWNClient() {

            @Override
            protected void forwardServerCommand(ICommandSender sender, String[] args) {
                forwarded.add(String.join(" ", args));
            }
        };
        client.processCommand(sender(false), new String[] { "safety", "off" });
        client.processCommand(sender(false), new String[] { "safety", "invalid", "extra" });
        assertEquals(Arrays.asList("safety off", "safety invalid extra"), forwarded);
        assertTrue(Config.quantumSafetyEnabled);
        assertTrue(persistedSafety());
        assertTrue(
            command.getCommandUsage(sender(true))
                .contains("safety <on|off>"));
        assertEquals(
            Arrays.asList("on", "off"),
            command.addTabCompletionOptions(sender(true), new String[] { "safety", "" }));
        assertEquals(
            Arrays.asList("on", "off"),
            client.addTabCompletionOptions(sender(false), new String[] { "safety", "" }));
    }

    private boolean persistedSafety() {
        return new Configuration(file).get("ae2", "quantumSafetyEnabled", false)
            .getBoolean();
    }

    private ICommandSender sender(boolean op) {
        return (ICommandSender) Proxy.newProxyInstance(
            ICommandSender.class.getClassLoader(),
            new Class<?>[] { ICommandSender.class },
            (proxy, method, args) -> {
                if ("canCommandSenderUseCommand".equals(method.getName())) {
                    return op && ((Integer) args[0]) == 4;
                }
                if ("addChatMessage".equals(method.getName())) {
                    messages.add(((IChatComponent) args[0]).getUnformattedText());
                }
                if ("getCommandSenderName".equals(method.getName())) return "Server";
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
    }
}
