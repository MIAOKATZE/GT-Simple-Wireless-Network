// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtswn.client.testworld;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import org.junit.Rule;
import org.junit.Test;

import com.miaokatze.gtswn.common.testworld.TestHarnessOptInRule;
import com.miaokatze.gtswn.common.testworld.TestWorldCommand;

import cpw.mods.fml.common.eventhandler.ASMEventHandler;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.ListenerList;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ClientTestPermissionForgeDispatchTest {

    @Rule
    public final TestHarnessOptInRule harness = new TestHarnessOptInRule(true);

    @Test
    public void forgeAsmPostsReachPublicListenerAndStillRequireTheOpTwoServerReceipt() throws Exception {
        Constructor<ClientTestPermission> constructor = ClientTestPermission.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ClientTestPermission listener = constructor.newInstance();
        TickEvent.ClientTickEvent idleTick = new TickEvent.ClientTickEvent(TickEvent.Phase.END);
        ClientChatReceivedEvent chatEvent = chat("unrelated");
        try (ForgeDispatch ticks = new ForgeDispatch(listener, "tick", idleTick);
            ForgeDispatch chats = new ForgeDispatch(listener, "chat", chatEvent)) {
            ticks.post(idleTick); // Real ASM wrapper invocation, not a direct listener method call.
            assertEquals(null, value(listener, "token"));

            String token = UUID.randomUUID()
                .toString();
            field(ClientTestPermission.class, "token").set(listener, token);
            chats.post(chat("[GTSWN-TEST-AUTH] " + UUID.randomUUID() + " actorLevel2=true actorLevel4=false"));
            chats.post(chat("[GTSWN-TEST-AUTH] " + token + " actorLevel2=false actorLevel4=false"));
            assertFalse((Boolean) value(listener, "accepted"));
            assertEquals(null, value(listener, "actorLevel2"));

            PermissionActor denied = actor(1);
            try {
                TestWorldCommand.execute(denied, new String[] { "test", "client", token });
                fail("OP1 must not obtain a client authorization receipt");
            } catch (CommandException expected) {
                assertTrue(denied.messages.isEmpty());
            }
            PermissionActor authorized = actor(2);
            TestWorldCommand.execute(authorized, new String[] { "test", "client", token });
            assertEquals(1, authorized.messages.size());
            ClientChatReceivedEvent receipt = chat(authorized.messages.get(0));
            chats.post(receipt);
            assertTrue(receipt.isCanceled());
            assertTrue((Boolean) value(listener, "accepted"));
            assertEquals(Boolean.TRUE, value(listener, "actorLevel2"));
            assertEquals(Boolean.FALSE, value(listener, "actorLevel4"));
            assertFalse((Boolean) value(listener, "dispatching"));
            ticks.post(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            assertEquals(token, value(listener, "token"));
            assertTrue((Boolean) value(listener, "accepted"));
        }
    }

    private static ClientChatReceivedEvent chat(String message) {
        return new ForgeChatEvent(new ChatComponentText(message));
    }

    @Test
    public void publicNestedObserverIsReachableDespiteItsPackagePrivateOuterScenario() throws Exception {
        Class<?> observerType = Class
            .forName("com.miaokatze.gtswn.common.testworld.QuantumAnchorLifecycleScenario$PlacementObserver");
        Object observer = allocate(observerType);
        Event event = (Event) allocate(Class.forName("net.minecraftforge.event.world.BlockEvent$PlaceEvent"));
        try (ForgeDispatch dispatch = new ForgeDispatch(observer, "placed", event)) {
            try {
                dispatch.post(event);
                fail("Empty fixture context should be reached through the real ASM wrapper");
            } catch (NullPointerException reachedObserver) {
                assertEquals(observerType.getName(), reachedObserver.getStackTrace()[0].getClassName());
                assertEquals("placed", reachedObserver.getStackTrace()[0].getMethodName());
            }
        }
    }

    private static Object value(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static PermissionActor actor(int level) throws Exception {
        PermissionActor actor = (PermissionActor) allocate(PermissionActor.class);
        actor.level = level;
        actor.messages = new ArrayList<>();
        return actor;
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        return unsafe.getMethod("allocateInstance", Class.class)
            .invoke(field(unsafe, "theUnsafe").get(null), type);
    }

    /** Reproduces the isCancelable override normally injected from ClientChatReceivedEvent's annotation. */
    public static final class ForgeChatEvent extends ClientChatReceivedEvent {

        ForgeChatEvent(IChatComponent message) {
            super(message);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    private static final class PermissionActor extends EntityPlayerMP {

        int level;
        List<String> messages;

        private PermissionActor() {
            super(null, null, null, null);
        }

        @Override
        public boolean canCommandSenderUseCommand(int required, String command) {
            return level >= required;
        }

        @Override
        public void addChatMessage(IChatComponent message) {
            messages.add(message.getUnformattedText());
        }
    }

    /** Plain JUnit lacks Forge's injected no-arg event constructors, so install the real wrapper directly. */
    private static final class ForgeDispatch implements AutoCloseable {

        private final EventBus bus = new EventBus((bus, event, listeners, index, failure) -> {
            if (failure instanceof Error) throw (Error) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            throw new AssertionError(failure);
        });
        private final int busId;
        private final ASMEventHandler wrapper;

        ForgeDispatch(Object listener, String method, Event event) throws Exception {
            busId = field(EventBus.class, "busID").getInt(bus);
            Class<?> eventType = event instanceof ClientChatReceivedEvent ? ClientChatReceivedEvent.class
                : event.getClass();
            wrapper = new ASMEventHandler(
                listener,
                listener.getClass()
                    .getMethod(method, eventType),
                null);
            event.getListenerList()
                .register(busId, wrapper.getPriority(), wrapper);
        }

        void post(Event event) {
            bus.post(event);
        }

        @Override
        public void close() {
            ListenerList.unregisterAll(busId, wrapper);
        }
    }
}
