package com.miaokatze.gtswn.common.command;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IChatComponent;

import org.junit.Test;

public class CommandMonitorProfileTest {

    @Test
    public void nonOperatorPlayerCanUsePublicProfileEntryEvenWhenNativePermissionIsFalse() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafe.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        NonOperator player = (NonOperator) unsafe.getMethod("allocateInstance", Class.class)
            .invoke(singleton.get(null), NonOperator.class);
        assertTrue(new CommandMonitorProfile().canCommandSenderUseCommand(player));
        assertEquals(0, player.nativePermissionQueries);
    }

    @Test
    public void nonPlayerKeepsNativeGateAndCannotRunProfileBusinessOperations() {
        List<IChatComponent> messages = new ArrayList<>();
        CommandMonitorProfile command = new CommandMonitorProfile();
        assertFalse(command.canCommandSenderUseCommand(nonPlayer(false, messages)));
        ICommandSender console = nonPlayer(true, messages);
        assertTrue(command.canCommandSenderUseCommand(console));
        command.processCommand(console, new String[] { "ui" });
        assertEquals(1, messages.size());
        assertEquals(
            "Use this command as an authenticated player.",
            messages.get(0)
                .getUnformattedText());
    }

    private static ICommandSender nonPlayer(boolean nativePermission, List<IChatComponent> messages) {
        return (ICommandSender) Proxy.newProxyInstance(
            ICommandSender.class.getClassLoader(),
            new Class<?>[] { ICommandSender.class },
            (proxy, method, args) -> {
                if ("canCommandSenderUseCommand".equals(method.getName())) {
                    assertEquals(0, args[0]);
                    assertEquals("monitorprofile", args[1]);
                    return nativePermission;
                }
                if ("addChatMessage".equals(method.getName())) {
                    messages.add((IChatComponent) args[0]);
                    return null;
                }
                if ("getEntityWorld".equals(method.getName()))
                    throw new AssertionError("A non-player must be rejected before accessing profile storage");
                return null;
            });
    }

    private static final class NonOperator extends EntityPlayerMP {

        int nativePermissionQueries;

        private NonOperator() {
            super(null, null, null, null);
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            nativePermissionQueries++;
            return false;
        }
    }
}
