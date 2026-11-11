package com.miaokatze.gtswn.client.testworld;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.Vec3;

import com.google.gson.GsonBuilder;
import com.miaokatze.gtswn.client.gui.GuiQuantumTerminal;
import com.miaokatze.gtswn.common.gui.TerminalGuiFactory;
import com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkData;
import com.miaokatze.gtswn.common.testworld.QuantumSafetyClientScenario;
import com.miaokatze.gtswn.common.testworld.TestDimension;
import com.miaokatze.gtswn.common.testworld.TestLabIdentity;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.network.GTSWNPacketHandler;
import com.miaokatze.gtswn.network.PacketRequestQuantumTerminalData;
import com.miaokatze.gtswn.network.PacketSetQuantumNetworkSafety;
import com.miaokatze.gtswn.network.PacketSyncQuantumTerminalDataLite;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.FMLIndexedMessageToMessageCodec;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.relauncher.Side;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/** Host-triggered real client observer. Every business action uses the unmodified production route. */
public final class QuantumSafetyClientCheck {

    private static final QuantumSafetyClientCheck INSTANCE = new QuantumSafetyClientCheck();
    private static final String OBSERVER = "gtswn-safety-client-observer";
    private static boolean registered;
    private final ArrayBlockingQueue<QuantumNetworkData> inbound = new ArrayBlockingQueue<>(256);
    private final ArrayBlockingQueue<Integer> outbound = new ArrayBlockingQueue<>(256);
    private final Map<String, Object> result = new LinkedHashMap<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private Minecraft mc;
    private Object connection;
    private File directory;
    private FMLEmbeddedChannel channel;
    private String run, phase = "IDLE";
    private QuantumNetworkData latestWire, retained;
    private NBTTagCompound original;
    private int ticks, stable, received, waitAfter, sentRequests, sentIntents, captureWait;
    private boolean reportWritten, capturePending, captured;
    private boolean airInputHeld, originalSneakKey, originalMovementSneak, originalEntitySneak;
    private float originalYaw, originalPitch;
    private boolean revocationReceipt, rejectionReceipt, staleIntentSent;
    private boolean buildRevokedReceipt, securityReadyReceipt, buildPrefaceComplete;
    private boolean allGrantsGui, allGrantsNodeDispatched;

    /** Called on the real client thread by the host after the OP2 console issued this exact business run. */
    public static void start(String runId) {
        DebugTestGate.requireEnabled();
        UUID.fromString(runId);
        QuantumSafetyClientCheck test = INSTANCE;
        if (!"IDLE".equals(test.phase)) throw new IllegalStateException("Safety client check is already active");
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null)
            throw new IllegalStateException("Actual TCP actor must already be connected");
        if (mc.getNetHandler()
            .getNetworkManager()
            .isLocalChannel()) throw new IllegalStateException("Safety client check requires real TCP transport");
        if (UiDebugSuite.INSTANCE.running()) throw new IllegalStateException("Another UI probe is active");
        if (!registered) {
            FMLCommonHandler.instance()
                .bus()
                .register(test);
            registered = true;
        }
        test.mc = mc;
        test.connection = mc.getNetHandler();
        test.run = runId;
        test.phase = "WAIT_FIXTURE";
        test.result.clear();
        test.events.clear();
        test.inbound.clear();
        test.outbound.clear();
        test.original = null;
        test.latestWire = test.retained = null;
        test.ticks = test.stable = test.received = test.waitAfter = test.sentRequests = test.sentIntents = test.captureWait = 0;
        test.reportWritten = test.capturePending = test.captured = false;
        test.airInputHeld = false;
        test.revocationReceipt = test.rejectionReceipt = test.staleIntentSent = false;
        test.buildRevokedReceipt = test.securityReadyReceipt = test.buildPrefaceComplete = false;
        test.allGrantsGui = test.allGrantsNodeDispatched = false;
        test.directory = new File(mc.mcDataDir, "gtswn-ui-reports/quantum-safety-" + runId);
        test.result.put("runId", runId);
        test.result.put("taskId", "quantum.safety_client");
        test.result.put("lane", "client-server");
        test.result.put("artifact", TestLabIdentity.ARTIFACT);
        test.result.put("status", "RUNNING");
        test.result.put(
            "actor",
            mc.thePlayer.getUniqueID()
                .toString());
        test.result.put(
            "scope",
            "Actual native MUI button dispatch, decoded Lite7 input and production NETWORK C2S5/29; server independently checks actor ACL, context and resource state");
        try {
            Files.createDirectories(test.directory.toPath());
            test.channel = NetworkRegistry.INSTANCE.getChannel("gtswn", Side.CLIENT);
            if (test.channel == null) throw new IllegalStateException("Native FML client channel unavailable");
            ChannelHandlerContext codec = test.channel.pipeline()
                .context(FMLIndexedMessageToMessageCodec.class);
            if (codec == null) throw new IllegalStateException("Native FML decoder unavailable");
            test.result.put("decoderContext", codec.name());
            test.result.put(
                "pipelineBeforeObserver",
                new ArrayList<>(
                    test.channel.pipeline()
                        .names()));
            test.channel.pipeline()
                .addAfter(codec.name(), OBSERVER, new ChannelDuplexHandler() {

                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                        if (message instanceof PacketSyncQuantumTerminalDataLite) {
                            QuantumNetworkData data = ((PacketSyncQuantumTerminalDataLite) message).getData();
                            if (data != null && !test.inbound.offer(data))
                                throw new IllegalStateException("Safety inbound observer capacity exceeded");
                        }
                        ctx.fireChannelRead(message);
                    }

                    @Override
                    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise)
                        throws Exception {
                        int discriminator = message instanceof PacketRequestQuantumTerminalData ? 5
                            : message instanceof PacketSetQuantumNetworkSafety ? 29 : 0;
                        if (discriminator != 0 && !test.outbound.offer(discriminator))
                            throw new IllegalStateException("Safety outbound observer capacity exceeded");
                        ctx.write(message, promise);
                    }
                });
            test.result.put(
                "pipelineWithObserver",
                new ArrayList<>(
                    test.channel.pipeline()
                        .names()));
        } catch (Exception | LinkageError failure) {
            test.finish("FAIL", failure.toString());
        }
    }

    public static Map<String, Object> automationSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>(INSTANCE.result);
        snapshot.put("runId", INSTANCE.run);
        snapshot.put("phase", INSTANCE.phase);
        snapshot.put("reportWritten", INSTANCE.reportWritten);
        snapshot.put("reportDirectory", INSTANCE.directory == null ? null : INSTANCE.directory.getAbsolutePath());
        return snapshot;
    }

    /** Host-validated immutable native receipt; this only coordinates the fixture phase. */
    public static void acceptFixtureReceipt(String businessUUID, String actorUUID, String fixturePhase) {
        QuantumSafetyClientCheck test = INSTANCE;
        UUID.fromString(businessUUID);
        UUID.fromString(actorUUID);
        require(
            !"IDLE".equals(test.phase) && businessUUID.equals(test.run)
                && test.mc.thePlayer != null
                && actorUUID.equals(
                    test.mc.thePlayer.getUniqueID()
                        .toString())
                && test.mc.getNetHandler() == test.connection,
            "Fixture receipt must match the actual current run/actor/connection");
        if ("build-revoked".equals(fixturePhase)) {
            require(
                "WAIT_BUILD_REVOKE".equals(test.phase),
                "BUILD revocation follows actual teammate binding/node/full-read evidence");
            test.buildRevokedReceipt = true;
        } else if ("security-ready".equals(fixturePhase)) {
            require(
                test.buildRevokedReceipt && "WAIT_SECURITY_READY".equals(test.phase),
                "SECURITY preparation cannot precede actual revoked BUILD denial");
            test.securityReadyReceipt = true;
        } else if ("revoked".equals(fixturePhase)) {
            require(
                test.revocationReceipt || "WAIT_REVOKE".equals(test.phase),
                "Revocation receipt must follow the actual GUI sequence and polling stop");
            test.revocationReceipt = true;
        } else if ("rejected".equals(fixturePhase)) {
            require(
                test.revocationReceipt && test.staleIntentSent,
                "Rejection receipt cannot precede the real stale intent");
            test.rejectionReceipt = true;
        } else throw new IllegalStateException("Unknown native fixture receipt phase: " + fixturePhase);
        test.observe(
            "fixture_receipt",
            "Host delivered immutable native coordination receipt phase=" + fixturePhase
                + "; independent real packet/ACL/context/resource assertions remain required");
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || "IDLE".equals(phase)) return;
        try {
            if (++ticks > 1800) throw new IllegalStateException("Safety client timeout phase=" + phase);
            require(mc.thePlayer != null && mc.getNetHandler() == connection, "Actual actor connection changed");
            QuantumNetworkData input;
            while ((input = inbound.poll()) != null) {
                latestWire = input;
                received++;
                observe(
                    "native_lite7",
                    "online=" + input.online
                        + " safety="
                        + input.networkSafetyEnabled
                        + " capability="
                        + input.canChangeNetworkSafety
                        + " token="
                        + input.networkSafetyRequestToken);
            }
            Integer discriminator;
            while ((discriminator = outbound.poll()) != null) {
                if (discriminator == 5) sentRequests++;
                else sentIntents++;
            }
            if ("WAIT_FIXTURE".equals(phase)) {
                ItemStack held = mc.thePlayer.inventory.mainInventory[0];
                if (held == null || !(held.getItem() instanceof ItemNetworkQuantumTerminal)
                    || !held.hasTagCompound()
                    || !run.equals(
                        held.getTagCompound()
                            .getString(QuantumSafetyClientScenario.RUN)))
                    return;
                require(TestDimension.isTest(mc.theWorld), "Server-issued safety fixture must be in test dimension");
                NBTTagCompound tag = held.getTagCompound();
                require(
                    tag.hasKey("GTSWN_QuantumSafety_ActorLevel2") && tag.hasKey("GTSWN_QuantumSafety_ActorLevel4")
                        && !tag.getBoolean("GTSWN_QuantumSafety_ActorLevel2")
                        && !tag.getBoolean("GTSWN_QuantumSafety_ActorLevel4"),
                    "Server receipt must explicitly prove actual actor OP2=false OP4=false");
                result.put("actorLevel2", false);
                result.put("actorLevel4", false);
                original = (NBTTagCompound) tag.getCompoundTag("GTSWN_QuantumSafety_Original")
                    .copy();
                require(run.equals(original.getString("Run")), "Original-state receipt belongs to this exact run");
                mc.thePlayer.inventory.currentItem = 0;
                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(0));
                require(
                    !ItemNetworkQuantumTerminal.isBound(held),
                    "BUILD teammate must actually bind the native-encoded unbound terminal");
                clickBlock(8, 65, 12, 3, Vec3.createVectorHelper(8.5, 65.5, 13));
                phase = "WAIT_BUILD_BIND";
                observe(
                    "teammate_bind_dispatched",
                    "Native controller C08 dispatched; fixture does not bind actor terminal");
                return;
            }
            if ("WAIT_RESTORE".equals(phase)) {
                ItemStack current = mc.thePlayer.inventory.mainInventory[0];
                if (current != null && current.hasTagCompound()
                    && run.equals(
                        current.getTagCompound()
                            .getString(QuantumSafetyClientScenario.RUN)))
                    return;
                boolean slot = original.hasKey("Stack", 10) ? current != null && original.getCompoundTag("Stack")
                    .equals(current.writeToNBT(new NBTTagCompound())) : current == null;
                double feetY = mc.thePlayer.boundingBox.minY;
                boolean selected = mc.thePlayer.inventory.currentItem == original.getInteger("Selected");
                boolean position = mc.thePlayer.dimension == original.getInteger("Dimension")
                    && Math.abs(mc.thePlayer.posX - original.getDouble("X")) < .05
                    && Math.abs(feetY - original.getDouble("Y")) < .05
                    && Math.abs(mc.thePlayer.posZ - original.getDouble("Z")) < .05;
                Map<String, Object> restoration = new LinkedHashMap<>();
                restoration.put("expectedFeetY", original.getDouble("Y"));
                restoration.put("actualFeetY", feetY);
                restoration.put("actualEyeY", mc.thePlayer.posY);
                restoration.put("actualYOffset", mc.thePlayer.yOffset);
                restoration.put("expectedDimension", original.getInteger("Dimension"));
                restoration.put("actualDimension", mc.thePlayer.dimension);
                restoration.put("expectedX", original.getDouble("X"));
                restoration.put("actualX", mc.thePlayer.posX);
                restoration.put("expectedZ", original.getDouble("Z"));
                restoration.put("actualZ", mc.thePlayer.posZ);
                restoration.put("exactSlotNbtMatches", slot);
                restoration.put("expectedSelected", original.getInteger("Selected"));
                restoration.put("actualSelected", mc.thePlayer.inventory.currentItem);
                restoration.put("positionMatches", position);
                result.put("restorationObservation", restoration);
                if (!slot || !position || !selected) return;
                result.put("slotRestoredFromServer", true);
                result.put("positionRestoredFromServer", true);
                require(
                    sentIntents == 4 && sentRequests >= 2,
                    "Actual client FML outbound observer must see four C2S29 intents and C2S5 polling");
                finish(
                    "PASS",
                    "Native GUI ON/OFF/ON, stale same-context intent rejected after native card revocation, two real Lite7 denials, original slot and position restored");
                return;
            }
            require(TestDimension.isTest(mc.theWorld), "Actor left owned test dimension before restoration");
            ItemStack held = mc.thePlayer.inventory.mainInventory[0];
            require(
                held != null && held.hasTagCompound()
                    && run.equals(
                        held.getTagCompound()
                            .getString(QuantumSafetyClientScenario.RUN))
                    && mc.thePlayer.inventory.currentItem == 0,
                "Run-issued slot/terminal changed");
            if ("WAIT_BUILD_BIND".equals(phase)) {
                if (!ItemNetworkQuantumTerminal.isBound(held) || !"build_bound".equals(
                    held.getTagCompound()
                        .getString(QuantumSafetyClientScenario.PHASE)))
                    return;
                result.put("buildOnlyNativeBinding", true);
                clickBlock(10, 64, 14, 1, Vec3.createVectorHelper(10.5, 65, 14.5));
                phase = "WAIT_BUILD_LINK";
                observe(
                    "teammate_node_dispatched",
                    "Native support-face C08 dispatched for node 10:65:14; fixture never places node");
                return;
            }
            if ("WAIT_BUILD_LINK".equals(phase)) {
                if (!"build_linked".equals(
                    held.getTagCompound()
                        .getString(QuantumSafetyClientScenario.PHASE))
                    || mc.theWorld.getTileEntity(10, 65, 14) == null) return;
                result.put("buildOnlyNativeNodeLinked", true);
                beginAirInput();
                return;
            }
            if ("BUILD_READ".equals(phase)) {
                if (!authorizedBuild()) {
                    stable = 0;
                    return;
                }
                if (++stable < 12) return;
                result.put("buildOnlyFullData", true);
                result.put("buildOnlyToggleDenied", true);
                result.put("buildOnlyChannels", latestWire.totalChannels);
                result.put("buildOnlyNodeCount", latestWire.quantumNodeCount);
                mc.thePlayer.closeScreen();
                stable = 0;
                phase = "STOP_BUILD_POLLING";
                return;
            }
            if ("STOP_BUILD_POLLING".equals(phase)) {
                if (++stable >= 10) phase = "WAIT_BUILD_REVOKE";
                return;
            }
            if ("WAIT_BUILD_REVOKE".equals(phase)) {
                if (!buildRevokedReceipt) return;
                waitAfter = received;
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
                phase = "WAIT_BUILD_DENIED";
                return;
            }
            if ("WAIT_BUILD_DENIED".equals(phase)) {
                if (received <= waitAfter || !denied()) return;
                require(latest() == latestWire, "Revoked BUILD response must be applied by the actual client handler");
                result.put("buildOnlyRevokedReadDenied", true);
                phase = "WAIT_SECURITY_READY";
                return;
            }
            if ("WAIT_SECURITY_READY".equals(phase)) {
                if (!securityReadyReceipt) return;
                allGrantsGui = true;
                beginAirInput();
                return;
            }
            if ("ALL_GRANTS_READ".equals(phase)) {
                if (!authorizedBusiness(true)) {
                    stable = 0;
                    return;
                }
                if (++stable < 12) return;
                result.put("allNativeGrantsFullData", true);
                mc.thePlayer.closeScreen();
                clickBlock(10, 64, 16, 1, Vec3.createVectorHelper(10.5, 65, 16.5));
                allGrantsNodeDispatched = true;
                allGrantsGui = false;
                buildPrefaceComplete = true;
                beginAirInput();
                observe(
                    "all_grants_node_dispatched",
                    "After real full-grants GUI/5/Lite, actual C08 targets second node10:65:16; await native proof and SECURITY-only transition");
                return;
            }
            if ("WAIT_AIR_RECEIPT".equals(phase)) {
                if (!"air_ready".equals(
                    held.getTagCompound()
                        .getString(QuantumSafetyClientScenario.PHASE)))
                    return;
                mc.playerController.sendUseItem(mc.thePlayer, mc.theWorld, held);
                phase = "WAIT_GUI";
                observe(
                    "use_item_dispatched",
                    "Server witnessed actual sneak and air ray; production sendUseItem dispatched once, GUI opening not yet asserted");
                return;
            }
            if ("WAIT_GUI".equals(phase)) {
                if (!"gui_ready".equals(
                    held.getTagCompound()
                        .getString(QuantumSafetyClientScenario.PHASE))
                    || sentRequests == 0
                    || !(allGrantsGui ? authorizedBusiness(true)
                        : buildPrefaceComplete ? authorized(false) : authorizedBuild()))
                    return;
                restoreAirInput();
                result.put("nativeGuiOpened", true);
                result.put("serverSneakAndAirWitness", true);
                result.put("serverGuiDataRequestWitness", true);
                if (allGrantsNodeDispatched && buildPrefaceComplete) {
                    require(
                        mc.theWorld.getTileEntity(10, 65, 16) != null,
                        "Actual all-grants C08 node must synchronize to client before management chain");
                    result.put("allNativeGrantsNodeLinked", true);
                }
                phase = allGrantsGui ? "ALL_GRANTS_READ" : buildPrefaceComplete ? "INITIAL_OFF" : "BUILD_READ";
                stable = 0;
                observe(
                    "native_gui_opened",
                    "Actual Quantum GUI, native C2S5 and applied Lite7 observed after server container/sneak witness; temporary input restored");
                return;
            }
            if ("INITIAL_OFF".equals(phase) || "WAIT_ON".equals(phase)
                || "WAIT_OFF".equals(phase)
                || "WAIT_ON_AGAIN".equals(phase)) {
                boolean expected = "WAIT_ON".equals(phase) || "WAIT_ON_AGAIN".equals(phase);
                if (!authorized(expected) || received <= waitAfter) {
                    stable = 0;
                    return;
                }
                if (++stable < 12) return;
                if ("WAIT_ON_AGAIN".equals(phase)) {
                    retained = latestWire;
                    result.put("retainedToken", retained.networkSafetyRequestToken);
                    result.put("retainedIdentity", retained.networkSafetyIdentity);
                    capturePending = true;
                    phase = "CAPTURE";
                } else {
                    String next = "INITIAL_OFF".equals(phase) ? "WAIT_ON"
                        : "WAIT_ON".equals(phase) ? "WAIT_OFF" : "WAIT_ON_AGAIN";
                    if (!click()) return;
                    waitAfter = received;
                    stable = 0;
                    phase = next;
                }
            } else if ("CAPTURE".equals(phase)) {
                if (++captureWait > 100)
                    throw new IllegalStateException("No natural rendered frame for final safety ON");
                if (!captured) return;
                mc.thePlayer.closeScreen();
                stable = 0;
                phase = "STOP_POLLING";
                observe(
                    "stop_polling",
                    "Normal closeScreen stops native GUI C2S5 polling; host console may now revoke card");
            } else if ("STOP_POLLING".equals(phase)) {
                if (++stable >= 10) phase = "WAIT_REVOKE";
            } else if ("WAIT_REVOKE".equals(phase)) {
                if (!revocationReceipt) return;
                waitAfter = received;
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketSetQuantumNetworkSafety(0, retained, false));
                staleIntentSent = true;
                phase = "WAIT_REJECT";
                observe(
                    "stale_intent",
                    "Production NETWORK sends retained token/identity/slot C2S29 target OFF; no C2S5 until its own finally reply");
            } else if ("WAIT_REJECT".equals(phase)) {
                if (received <= waitAfter || !denied()) return;
                result.put("staleIntentDeniedLite", true);
                waitAfter = received;
                GTSWNPacketHandler.NETWORK.sendToServer(new PacketRequestQuantumTerminalData());
                phase = "VERIFY_DENIAL";
                observe("explicit_request", "Only after stale29's own Lite7 denial, production NETWORK sends C2S5");
            } else if ("VERIFY_DENIAL".equals(phase) && received > waitAfter && denied()) {
                require(
                    latest() == latestWire,
                    "Production client latest-data field must contain the actual denied Lite7 snapshot");
                result.put("explicitRequestDeniedLite", true);
                result.put("replyTokenCleared", true);
                result.put("replyLiteWireMetricsZero", true);
                result.put("nativeButtonClicks", 3);
                result.put("outboundSafetyIntents", sentIntents);
                result.put("outboundDataRequests", sentRequests);
                phase = "WAIT_SERVER_REJECT";
                observe(
                    "client_complete",
                    "Both actual Lite7 denial snapshots clear token/capability/identity and every metric encoded by Lite7; full data isolation is covered by the backend assemble matrix");
            } else if ("WAIT_SERVER_REJECT".equals(phase) && rejectionReceipt) {
                phase = "WAIT_RESTORE";
                observe(
                    "server_rejected",
                    "Server independently verified unchanged policy/resources after stale intent; host console may complete/restore");
            }
        } catch (Exception | LinkageError failure) {
            finish("FAIL", failure.toString());
        }
    }

    private void beginAirInput() {
        originalSneakKey = mc.gameSettings.keyBindSneak.getIsKeyPressed();
        originalMovementSneak = mc.thePlayer.movementInput.sneak;
        originalEntitySneak = mc.thePlayer.isSneaking();
        originalYaw = mc.thePlayer.rotationYaw;
        originalPitch = mc.thePlayer.rotationPitch;
        airInputHeld = true;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
        mc.thePlayer.movementInput.sneak = true;
        mc.thePlayer.setSneaking(true);
        mc.thePlayer.rotationPitch = -90;
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, 1));
        mc.thePlayer.sendQueue
            .addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(originalYaw, -90, mc.thePlayer.onGround));
        phase = "WAIT_AIR_RECEIPT";
        observe(
            "air_input",
            "Actual sneak/look packets dispatched; awaiting real server air guard before production use");
    }

    private void clickBlock(int x, int y, int z, int side, Vec3 hit) {
        boolean sneak = mc.thePlayer.movementInput.sneak;
        boolean key = mc.gameSettings.keyBindSneak.getIsKeyPressed();
        float yaw = mc.thePlayer.rotationYaw, pitch = mc.thePlayer.rotationPitch;
        double dx = hit.xCoord - mc.thePlayer.posX, dz = hit.zCoord - mc.thePlayer.posZ;
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = (float) -Math
            .toDegrees(Math.atan2(hit.yCoord - mc.thePlayer.posY, Math.sqrt(dx * dx + dz * dz)));
        require(
            mc.thePlayer.getDistanceSq(hit.xCoord, hit.yCoord, hit.zCoord) <= 20.25,
            "Actual C08 target remains within native 4.5-block reach");
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
        mc.thePlayer.movementInput.sneak = false;
        mc.thePlayer.setSneaking(false);
        mc.thePlayer.rotationYaw = targetYaw;
        mc.thePlayer.rotationPitch = targetPitch;
        mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, 2));
        mc.thePlayer.sendQueue
            .addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(targetYaw, targetPitch, mc.thePlayer.onGround));
        try {
            mc.playerController
                .onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), x, y, z, side, hit);
        } finally {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), key);
            mc.thePlayer.movementInput.sneak = sneak;
            mc.thePlayer.setSneaking(sneak);
            mc.thePlayer.rotationYaw = yaw;
            mc.thePlayer.rotationPitch = pitch;
            mc.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, sneak ? 1 : 2));
            mc.thePlayer.sendQueue
                .addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(yaw, pitch, mc.thePlayer.onGround));
        }
    }

    private boolean authorizedBuild() throws Exception {
        return authorizedBusiness(false);
    }

    private boolean authorizedBusiness(boolean management) throws Exception {
        if (!UiModularSnapshot.supports(mc.currentScreen)
            || !(UiModularSnapshot.wrappedScreen(mc.currentScreen) instanceof GuiQuantumTerminal)) return false;
        QuantumNetworkData data = latest();
        return data != null && data == latestWire
            && data.online
            && data.networkSafetyEnabled
            && !data.managementOnly
            && data.anchorDim == mc.thePlayer.dimension
            && data.anchorX == 8
            && data.anchorY == 65
            && data.anchorZ == 12
            && !data.networkSafetyIdentity.isEmpty()
            && data.totalChannels > 0
            && data.quantumNodeCount > 0
            && data.canChangeNetworkSafety == management
            && (management ? data.networkSafetyRequestToken != 0 : data.networkSafetyRequestToken == 0)
            && TerminalGuiFactory.canToggleSafety(data) == management;
    }

    private boolean authorized(boolean enabled) throws Exception {
        if (!UiModularSnapshot.supports(mc.currentScreen)
            || !(UiModularSnapshot.wrappedScreen(mc.currentScreen) instanceof GuiQuantumTerminal)) return false;
        QuantumNetworkData data = latest();
        return data != null && data == latestWire
            && data.online
            && data.networkSafetyEnabled == enabled
            && TerminalGuiFactory.canToggleSafety(data);
    }

    private boolean click() {
        String path = UiModularSnapshot.namedPath(mc, mc.currentScreen, "quantum.safety");
        if (path == null) return false;
        Map<String, Object> action = UiModularInput.click(mc, mc.currentScreen, path);
        result.put("buttonClick" + ("INITIAL_OFF".equals(phase) ? 1 : "WAIT_ON".equals(phase) ? 2 : 3), action);
        require(
            "SENT".equals(action.get("status")) && Boolean.TRUE.equals(action.get("pressAttempted"))
                && Boolean.TRUE.equals(action.get("targetObservedPressed"))
                && "SENT".equals(action.get("releaseStatus"))
                && Boolean.TRUE.equals(action.get("contextRestored")),
            "Native production screen press/release failed: " + action);
        observe(
            "native_button",
            "Production quantum.safety screen press/release; expected next policy="
                + (!latestWire.networkSafetyEnabled));
        return true;
    }

    private boolean denied() {
        QuantumNetworkData data = latestWire;
        return data != null && data.globalSafetyEnabled
            && !data.online
            && !data.canChangeNetworkSafety
            && data.networkSafetyRequestToken == 0
            && data.networkSafetyIdentity.isEmpty()
            && !data.managementOnly
            && data.usedChannels == 0
            && data.totalChannels == 0
            && data.quantumNodeCount == 0
            && data.incorporationCount == 0
            && data.incorporationChannels == 0;
    }

    private static QuantumNetworkData latest() throws Exception {
        Field field = GuiQuantumTerminal.class.getDeclaredField("latestData");
        field.setAccessible(true);
        return (QuantumNetworkData) field.get(null);
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException(detail);
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !capturePending || !"CAPTURE".equals(phase)) return;
        try {
            require(authorized(true), "Final natural frame must display actual authorized ON snapshot");
            ScreenShotHelper.saveScreenshot(
                directory,
                "quantum-safety-on.png",
                mc.displayWidth,
                mc.displayHeight,
                mc.getFramebuffer());
            File png = new File(directory, "screenshots/quantum-safety-on.png");
            require(png.isFile(), "Natural framebuffer screenshot absent");
            result.put("screenshot", png.getAbsolutePath());
            result.put("muiSnapshot", UiModularSnapshot.capture(mc, mc.currentScreen));
            captured = true;
            capturePending = false;
        } catch (Exception | LinkageError failure) {
            finish("FAIL", failure.toString());
        }
    }

    private void observe(String kind, String detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("tick", ticks);
        event.put("kind", kind);
        event.put("detail", detail);
        events.add(event);
    }

    private void restoreAirInput() {
        if (!airInputHeld) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), originalSneakKey);
        if (mc.thePlayer != null) {
            mc.thePlayer.movementInput.sneak = originalMovementSneak;
            mc.thePlayer.setSneaking(originalEntitySneak);
            mc.thePlayer.rotationYaw = originalYaw;
            mc.thePlayer.rotationPitch = originalPitch;
            if (mc.getNetHandler() == connection) {
                mc.thePlayer.sendQueue
                    .addToSendQueue(new C0BPacketEntityAction(mc.thePlayer, originalEntitySneak ? 1 : 2));
                mc.thePlayer.sendQueue.addToSendQueue(
                    new C03PacketPlayer.C05PacketPlayerLook(originalYaw, originalPitch, mc.thePlayer.onGround));
            }
        }
        airInputHeld = false;
        result.put("temporaryAirInputRestored", true);
    }

    private void finish(String status, String detail) {
        restoreAirInput();
        result.put("revocationReceipt", revocationReceipt);
        result.put("rejectionReceipt", rejectionReceipt);
        result.put("staleIntentSent", staleIntentSent);
        if (mc != null && mc.thePlayer != null) {
            ItemStack item = mc.thePlayer.inventory.mainInventory[0];
            result.put(
                "terminalPhaseAtFinish",
                item != null && item.hasTagCompound() ? item.getTagCompound()
                    .getString(QuantumSafetyClientScenario.PHASE) : null);
        }
        result.put("status", status);
        result.put("detail", detail);
        result.put("ticks", ticks);
        result.put("events", events);
        try {
            Files.createDirectories(directory.toPath());
            Files.write(
                new File(directory, "result.json").toPath(),
                new GsonBuilder().setPrettyPrinting()
                    .create()
                    .toJson(result)
                    .getBytes(StandardCharsets.UTF_8));
            reportWritten = true;
        } catch (Exception failure) {
            reportWritten = false;
            result.put("reportFailure", failure.toString());
        } finally {
            if (channel != null && channel.pipeline()
                .get(OBSERVER) != null) channel.pipeline()
                    .remove(OBSERVER);
            channel = null;
            phase = "IDLE";
            capturePending = false;
        }
        if (mc != null && mc.thePlayer != null)
            mc.thePlayer.addChatMessage(new ChatComponentText("[GTSWN-SAFETY-CLIENT] " + status + " " + detail));
    }
}
