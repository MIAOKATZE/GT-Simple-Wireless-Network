package com.miaokatze.gtswn.common.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.IChatComponent;

import org.junit.Rule;
import org.junit.Test;

import com.miaokatze.gtswn.common.command.CommandGTSWN;

public class TestLabPermissionBoundaryTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    private ICommandSender sender(int granted, List<String> messages) {
        return (ICommandSender) Proxy.newProxyInstance(
            ICommandSender.class.getClassLoader(),
            new Class<?>[] { ICommandSender.class },
            (proxy, method, args) -> {
                if ("canCommandSenderUseCommand".equals(method.getName())) return granted >= (Integer) args[0];
                if ("addChatMessage".equals(method.getName()))
                    messages.add(((IChatComponent) args[0]).getUnformattedText());
                if ("getCommandSenderName".equals(method.getName())) return "TestConsole";
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            });
    }

    @Test
    public void opTwoCanUseLegacyAndUnifiedDelegation() {
        List<String> messages = new ArrayList<>();
        ICommandSender op = sender(2, messages);
        CommandGTSWN command = new CommandGTSWN();
        assertTrue(command.canCommandSenderUseCommand(op));
        command.processCommand(op, new String[] { "test", "help" });
        new TestLabBootstrap().execute(op, new String[] { "help" });
        assertEquals(6, messages.size());
    }

    @Test
    public void testCommandsRejectBelowTwoBeforeAnyWorldOrPlayerAccess() {
        ICommandSender visitor = sender(1, new ArrayList<>());
        assertFalse(new CommandGTSWN().canCommandSenderUseCommand(visitor));
        try {
            TestWorldCommand.execute(visitor, new String[] { "test", "kit", "ui" });
            fail("OP1 must not reach the kit");
        } catch (CommandException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("permission"));
        }
        try {
            TestWorldCommand.execute(visitor, new String[] { "test", "paged", "complete", "invalid" });
            fail("OP1 must not reach paged control");
        } catch (CommandException expected) {
            assertTrue(
                expected.getMessage()
                    .contains("permission"));
        }
    }

    @Test
    public void opTwoNeverGainsProductionAdministratorOperations() {
        ICommandSender op = sender(2, new ArrayList<>());
        for (String action : new String[] { "safety", "cleanup_info_data", "global_energy_trans",
            "global_energy_join" }) {
            try {
                new CommandGTSWN().processCommand(op, new String[] { action, "off" });
                fail("OP2 must not reach " + action);
            } catch (CommandException expected) {
                assertTrue(
                    expected.getMessage()
                        .contains("permission"));
            }
        }
    }
}
