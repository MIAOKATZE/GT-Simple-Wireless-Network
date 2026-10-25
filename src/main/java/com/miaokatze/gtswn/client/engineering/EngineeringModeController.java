package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import com.miaokatze.gtswn.client.TerminalBaubleController;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalActions;
import com.miaokatze.gtswn.client.engineering.terminal.EngineeringTerminalHudSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot;
import com.miaokatze.gtswn.common.engineering.EngineeringSnapshot.Node;
import com.miaokatze.gtswn.network.PacketSyncDeviceTerminalData.Entry;
import com.miaokatze.miaokatzecore.api.camera.CameraIntersections;
import com.miaokatze.miaokatzecore.api.camera.CameraPose;
import com.miaokatze.miaokatzecore.api.camera.HeightSlice;
import com.miaokatze.miaokatzecore.api.camera.OrthographicProjection;
import com.miaokatze.miaokatzecore.api.camera.RayInterval;
import com.miaokatze.miaokatzecore.api.camera.ZoomMotion;
import com.miaokatze.miaokatzecore.client.camera.CameraControl;
import com.miaokatze.miaokatzecore.client.camera.CameraControls;
import com.miaokatze.miaokatzecore.client.camera.CameraSettings;
import com.miaokatze.miaokatzecore.client.camera.WorldCameraHooks;
import com.miaokatze.miaokatzecore.client.camera.WorldCameras;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** GTSWN owns engineering intent; Core owns projection, input capture, slicing and camera restoration. */
public final class EngineeringModeController implements WorldCameras.Source, CameraControls {

    public static final String OWNER = "gtswn:engineering";
    public static final EngineeringModeController INSTANCE = new EngineeringModeController();
    public static final KeyBinding TOGGLE = new KeyBinding(
        "key.gtswn.engineering",
        Keyboard.KEY_F12,
        "key.categories.gtswn.factory");
    private final ZoomMotion zoom = new ZoomMotion(1, .025, 8);
    public static final KeyBinding DASHBOARD = new KeyBinding(
        "key.gtswn.dashboard",
        Keyboard.KEY_NONE,
        "key.categories.gtswn.factory");
    private WorldCameras.Lease lease;
    private World world;
    private net.minecraft.entity.player.EntityPlayer sessionPlayer;
    private Object sessionConnection;
    private int sessionDimension;
    private double x, y, z, lastX, lastY, lastZ;
    private double yaw = 45, pitch = 60;
    private boolean following;
    private final FactoryXrayState xray = new FactoryXrayState();
    private CameraSettings settings;
    private int settingsWidth, settingsHeight;
    private long settingsRevision = -1;
    private boolean settingsDesktop;
    private boolean hudHidden;
    private Target selected;
    private boolean pinned;
    private boolean cameraPanel;
    private boolean preferenceSession;
    private String sliceProfile = "";

    private EngineeringModeController() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(TOGGLE);
        ClientRegistry.registerKeyBinding(DASHBOARD);
        FactoryShortcuts.register();
        FactoryControlSettings.register();
        FactoryDisplayPreferences.register();
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(new EngineeringHud());
    }

    public static boolean isActive() {
        return INSTANCE.lease != null && INSTANCE.lease.active();
    }

    /** A normal-view dashboard borrows vanilla GUI focus, never a world-camera lease. */
    public static boolean dashboardHostVisible() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean live = mc.thePlayer != null && !mc.thePlayer.isDead
            && mc.thePlayer.getHealth() > 0
            && mc.theWorld != null;
        boolean direct = mc.currentScreen instanceof FactoryDashboardScreen
            && ((FactoryDashboardScreen) mc.currentScreen).sessionValid();
        return EngineeringInputPolicy
            .dashboardHostVisible(live, mc.gameSettings.hideGUI, direct, isActive(), mc.currentScreen != null);
    }

    /** Losing OS focus releases interaction ownership without erasing the visible dashboard. */
    public static boolean dashboardInputAllowed() {
        Minecraft mc = Minecraft.getMinecraft();
        return EngineeringInputPolicy.dashboardInputAllowed(
            dashboardHostVisible(),
            Display.isActive(),
            mc.currentScreen instanceof FactoryDashboardScreen,
            mc.inGameHasFocus);
    }

    public static WorkbenchLayout.Rect worldViewport(int width, int height) {
        return WorkbenchHud.layout(width, height).worldViewport;
    }

    public static void openDashboard() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null
            || mc.thePlayer.isDead
            || mc.thePlayer.getHealth() <= 0
            || mc.currentScreen != null) return;
        if (isActive()) WorkbenchHud.openDashboard();
        else mc.displayGuiScreen(new FactoryDashboardScreen());
    }

    static boolean closeDashboardScreen() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof FactoryDashboardScreen)) return false;
        mc.displayGuiScreen(null);
        return true;
    }

    public static void toggle() {
        if (isActive()) close();
        else open();
    }

    static void toggleFromBinding() {
        if (isActive()) {
            FactoryEntryHint.bindingUsed();
            close();
        } else {
            open();
            if (isActive()) FactoryEntryHint.bindingUsed();
        }
    }

    public static void open() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.thePlayer.isDead) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (isActive()) return;
        INSTANCE.world = mc.theWorld;
        INSTANCE.sessionPlayer = mc.thePlayer;
        INSTANCE.sessionConnection = mc.getNetHandler();
        INSTANCE.sessionDimension = mc.theWorld.provider.dimensionId;
        INSTANCE.returnToPlayer();
        INSTANCE.lease = WorldCameras.tryActivate(OWNER, mc.theWorld, 20, INSTANCE, INSTANCE::revoked);
        if (!isActive()) {
            INSTANCE.world = null;
            mc.thePlayer.addChatMessage(new ChatComponentText("世界相机正在由其他模块使用，工厂模式未开启。"));
        } else {
            INSTANCE.sliceProfile = FactoryDisplayPreferences.currentSliceProfile();
            INSTANCE.restorePreferences();
            EngineeringHud.restorePreferences();
            WorkbenchHud.restorePreferences();
            INSTANCE.hudHidden = mc.gameSettings.hideGUI;
            INSTANCE.preferenceSession = true;
            EngineeringTerminalHudSnapshot
                .tick(EngineeringDashboardCache.slot() >= 0 || EngineeringTerminalActions.deviceVisualizationEnabled());
        }
    }

    public static void close() {
        WorldCameras.Lease old = INSTANCE.lease;
        if (old != null) old.close();
        else INSTANCE.revoked();
    }

    private void revoked() {
        rememberPreferences();
        FactoryDisplayPreferences.save();
        preferenceSession = false;
        sliceProfile = "";
        FactoryShortcuts.cancelBinding();
        lease = null;
        world = null;
        sessionPlayer = null;
        sessionConnection = null;
        selected = null;
        pinned = false;
        cameraPanel = false;
        xray.reset();
        settings = null;
        EngineeringHud.reset();
        EngineeringTerminalHudSnapshot.close();
        FactoryDashboardHint.close();
    }

    static String preferenceProfile() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen instanceof FactoryDashboardScreen)
            return ((FactoryDashboardScreen) mc.currentScreen).preferenceProfile();
        return INSTANCE.sliceProfile;
    }

    private void restorePreferences() {
        yaw = FactoryDisplayPreferences.number(FactoryDisplayPreferences.Key.YAW);
        pitch = FactoryDisplayPreferences.number(FactoryDisplayPreferences.Key.PITCH);
        zoom.snap(FactoryDisplayPreferences.number(FactoryDisplayPreferences.Key.ZOOM));
        following = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.FOLLOWING);
        cameraPanel = FactoryDisplayPreferences.flag(FactoryDisplayPreferences.Key.CAMERA_PANEL);
        xray.reset();
        if (!sliceProfile.isEmpty()) xray.restore(
            FactoryDisplayPreferences.decode(FactoryDisplayPreferences.text(sliceProfile + "_current")),
            FactoryDisplayPreferences.decode(FactoryDisplayPreferences.text(sliceProfile + "_remembered")));
        settings = null;
    }

    private void rememberPreferences() {
        if (!preferenceSession) return;
        WorkbenchHud.rememberPreferences();
        EngineeringHud.rememberPreferences();
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.YAW, yaw);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.PITCH, pitch);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.ZOOM, zoom.target());
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.FOLLOWING, following);
        FactoryDisplayPreferences.put(FactoryDisplayPreferences.Key.CAMERA_PANEL, cameraPanel);
        if (!sliceProfile.isEmpty()) {
            FactoryDisplayPreferences
                .text(sliceProfile + "_current", FactoryDisplayPreferences.encode(xray.requested()));
            FactoryDisplayPreferences
                .text(sliceProfile + "_remembered", FactoryDisplayPreferences.encode(xray.remembered()));
        }
        FactoryDisplayPreferences.saveIfDue();
    }

    public static void focus(int x, int y, int z) {
        if (!isActive()) open();
        if (!isActive()) return;
        INSTANCE.following = false;
        INSTANCE.x = INSTANCE.lastX = x + .5;
        INSTANCE.y = INSTANCE.lastY = y + .5;
        INSTANCE.z = INSTANCE.lastZ = z + .5;
        INSTANCE.settings = null;
    }

    public void returnToPlayer() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        pinned = false;
        x = lastX = mc.thePlayer.posX;
        y = lastY = mc.thePlayer.posY;
        z = lastZ = mc.thePlayer.posZ;
    }

    public void toggleFollowing() {
        following = !following;
        pinned = false;
        if (following) returnToPlayer();
        settings = null;
    }

    public boolean following() {
        return following;
    }

    public boolean cameraPanel() {
        return cameraPanel;
    }

    public void toggleCameraPanel() {
        cameraPanel = !cameraPanel;
        settings = null;
    }

    public boolean xrayEnabled() {
        return xray.requested()
            .enabled();
    }

    public void toggleXray() {
        xray.toggle(y);
        settings = null;
    }

    public double mapPlaneY() {
        HeightSlice slice = xray.requested();
        return slice.enabled() && slice.hasUpper() ? slice.maximumY() - .01 : y;
    }

    public void selectPosition(int bx, int by, int bz, String name) {
        WorkbenchHud.worldSelectionChanged();
        selected = new Target(bx, by, bz, name);
        pinned = false;
    }

    public Target selected() {
        return selected;
    }

    public boolean pinned() {
        return pinned;
    }

    public void clearSelection() {
        selected = null;
        pinned = false;
    }

    public void togglePin() {
        if (selected == null) return;
        pinned = !pinned;
        if (pinned) focus(selected.x, selected.y, selected.z);
    }

    public void select(Entry entry) {
        if (entry == null || world == null || entry.dim != world.provider.dimensionId) {
            clearSelection();
            return;
        }
        selected = new Target(entry.x, entry.y, entry.z, entry.displayName.isEmpty() ? entry.name : entry.displayName);
        pinned = false;
    }

    @SubscribeEvent
    public void keys(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null || !mc.inGameHasFocus) return;
        // Active input is handled at Core's raw queue boundary, so it cannot also fire here.
        if (isActive() || !Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) return;
        int code = Keyboard.getEventKey();
        if (FactoryShortcuts.suppresses(code)) return;
        if (code != Keyboard.KEY_NONE && code == TOGGLE.getKeyCode() && code != TerminalBaubleController.keyCode())
            toggleFromBinding();
        else if (dashboardBinding(code)) openDashboard();
    }

    private static boolean dashboardBinding(int code) {
        return EngineeringInputPolicy
            .dashboardBinding(code, DASHBOARD.getKeyCode(), TOGGLE.getKeyCode(), TerminalBaubleController.keyCode());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (isActive()) syncHudVisibility();
        // Vanilla supplies acceleration, friction, collision, sprinting and server-authoritative movement.
        // Only the heading changes: native forward must point towards the top of the camera's screen.
        if (event.phase == TickEvent.Phase.START && isActive() && following && !FactoryShortcuts.isBinding()) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null && EngineeringInputPolicy
                .worldNavigation(true, mc.currentScreen != null, mc.inGameHasFocus, WorkbenchHud.dashboardVisible()))
                mc.thePlayer.rotationYaw = EngineeringMotion.playerYaw(yaw);
        }
        if (event.phase == TickEvent.Phase.END) {
            FactoryShortcuts.poll();
            EngineeringDashboardCache.tick();
        }
        if (event.phase != TickEvent.Phase.END || !isActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != world || mc.theWorld.provider.dimensionId != sessionDimension
            || mc.thePlayer == null
            || mc.thePlayer != sessionPlayer
            || mc.getNetHandler() != sessionConnection
            || mc.thePlayer.isDead) {
            close();
            return;
        }
        EngineeringHud.tickSources();
        WorkbenchHud.tick();
        EngineeringTerminalHudSnapshot.tick(
            WorkbenchHud.dashboardVisible() || EngineeringDashboardCache.slot() >= 0
                || EngineeringTerminalActions.deviceVisualizationEnabled());
        rememberPreferences();
        lastX = x;
        lastY = y;
        lastZ = z;
        if (!EngineeringInputPolicy
            .worldNavigation(isActive(), mc.currentScreen != null, mc.inGameHasFocus, WorkbenchHud.dashboardVisible())
            || FactoryShortcuts.isBinding()
            || WorkbenchHud.typing()) return;
        if (pinned && selected != null) {
            x = selected.x + .5;
            y = selected.y + .5;
            z = selected.z + .5;
        } else if (following) {
            returnToPlayer();
        } else {
            double[] delta = EngineeringMotion.delta(
                yaw,
                zoom.actual(),
                key(mc.gameSettings.keyBindForward) - key(mc.gameSettings.keyBindBack),
                key(mc.gameSettings.keyBindRight) - key(mc.gameSettings.keyBindLeft),
                key(mc.gameSettings.keyBindJump) - key(mc.gameSettings.keyBindSneak),
                FactoryControlSettings.value(FactoryControlSettings.Setting.KEYBOARD_PAN));
            x += delta[0];
            y += delta[1];
            z += delta[2];
        }
    }

    private static int key(KeyBinding key) {
        if (WorkbenchHud.dashboardVisible() || WorkbenchHud.typing()) return 0;
        int code = key.getKeyCode();
        if (FactoryShortcuts.suppresses(code) || FactoryShortcuts.action(code) != null
            || com.miaokatze.gtswn.client.TerminalWheelInput.suppresses(code)) return 0;
        return code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code) ? 1
            : code < 0 && code + 100 >= 0 && Mouse.isButtonDown(code + 100) ? 1 : 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void mouse(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!EngineeringInputPolicy.worldInput(isActive(), mc.currentScreen != null, mc.inGameHasFocus)
            || event.isCanceled()) return;
        if (FactoryShortcuts.isBinding()) {
            event.setCanceled(true);
            return;
        }
        if (EngineeringHud.mouse(event)) {
            event.setCanceled(true);
            return;
        }
        if (WorkbenchHud.dashboardVisible()) {
            event.setCanceled(true);
            return;
        }
        if (event.button == 0 || event.button == 1) {
            event.setCanceled(true);
            if (event.button == 0 && event.buttonstate) {
                WorkbenchHud.worldSelectionChanged();
                Target target = pick(event.x, event.y);
                if (target != null && selected != null && target.matches(selected)) {
                    if (pinned) pinned = false;
                    else selected = null;
                } else {
                    selected = target;
                    pinned = false;
                }
            }
        }
    }

    private Target pick(int displayX, int displayY) {
        RayInterval interval = WorldCameraHooks.pixelRayInterval(OWNER, world, displayX, displayY);
        if (interval == null) return null;
        double min = interval.minDistance(), max = interval.maxDistance();
        Vec3 start = point(interval, min + .0001), end = point(interval, max);
        MovingObjectPosition hit = world.func_147447_a(start, end, false, true, false);
        Target result = null;
        double nearest = max;
        if (hit != null) {
            TileEntity te = world.getTileEntity(hit.blockX, hit.blockY, hit.blockZ);
            if (te instanceof IGregTechTileEntity) {
                result = new Target(
                    hit.blockX,
                    hit.blockY,
                    hit.blockZ,
                    ((IGregTechTileEntity) te).getMetaTileEntity() == null ? "GT"
                        : ((IGregTechTileEntity) te).getMetaTileEntity()
                            .getLocalName());
                nearest = start.distanceTo(hit.hitVec) + min;
            }
        }
        // Bound controllers remain selectable from the existing terminal snapshot; no world-wide TE scan.
        for (Entry entry : EngineeringTerminalHudSnapshot.capture(Minecraft.getMinecraft().thePlayer).device.devices) {
            if (entry.dim != world.provider.dimensionId) continue;
            double distance = CameraIntersections
                .aabb(interval.ray(), entry.x, entry.y, entry.z, entry.x + 1, entry.y + 1, entry.z + 1, min, nearest);
            if (Double.isFinite(distance)) {
                nearest = distance;
                result = new Target(
                    entry.x,
                    entry.y,
                    entry.z,
                    entry.displayName.isEmpty() ? entry.name : entry.displayName);
            }
        }
        EngineeringSnapshot snapshot = EngineeringDashboardCache.snapshot();
        if (snapshot != null) for (Node node : snapshot.nodes) {
            if (node.dim != world.provider.dimensionId) continue;
            double distance = CameraIntersections
                .aabb(interval.ray(), node.x, node.y, node.z, node.x + 1, node.y + 1, node.z + 1, min, nearest);
            if (Double.isFinite(distance)) {
                nearest = distance;
                result = new Target(node.x, node.y, node.z, node.name);
            }
        }
        return result;
    }

    private static Vec3 point(RayInterval interval, double distance) {
        return Vec3.createVectorHelper(
            interval.ray()
                .originX()
                + interval.ray()
                    .directionX() * distance,
            interval.ray()
                .originY()
                + interval.ray()
                    .directionY() * distance,
            interval.ray()
                .originZ()
                + interval.ray()
                    .directionZ() * distance);
    }

    @Override
    public WorldCameras.View sample(float partialTicks, int width, int height) {
        if (!isActive()) return null;
        zoom.advance(.25, .85);
        Minecraft mc = Minecraft.getMinecraft();
        double anchorX = lastX + (x - lastX) * partialTicks;
        double anchorY = lastY + (y - lastY) * partialTicks;
        double anchorZ = lastZ + (z - lastZ) * partialTicks;
        if (following && mc.thePlayer != null) {
            anchorX = mc.thePlayer.lastTickPosX + (mc.thePlayer.posX - mc.thePlayer.lastTickPosX) * partialTicks;
            anchorY = mc.thePlayer.lastTickPosY + (mc.thePlayer.posY - mc.thePlayer.lastTickPosY) * partialTicks;
            anchorZ = mc.thePlayer.lastTickPosZ + (mc.thePlayer.posZ - mc.thePlayer.lastTickPosZ) * partialTicks;
        }
        return new WorldCameras.View(
            new CameraPose(anchorX, anchorY, anchorZ, yaw, pitch),
            OrthographicProjection.scaled(width, height, 16 * zoom.actual(), 512),
            true);
    }

    @Override
    public CameraSettings settings() {
        Minecraft mc = Minecraft.getMinecraft();
        syncHudVisibility();
        boolean desktop = WorkbenchHud.dashboardVisible();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        if (settingsWidth != resolution.getScaledWidth() || settingsHeight != resolution.getScaledHeight()
            || settingsRevision != FactoryControlSettings.revision()
            || settingsDesktop != desktop) {
            settingsWidth = resolution.getScaledWidth();
            settingsHeight = resolution.getScaledHeight();
            settingsRevision = FactoryControlSettings.revision();
            settingsDesktop = desktop;
            settings = null;
        }
        double scale = EngineeringHud.scale(settingsWidth, settingsHeight);
        if (settings == null) settings = CameraSettings.builder()
            .render(
                CameraSettings.Render.builder()
                    .hideHand(true)
                    .renderPlayerBody(true)
                    .disableFog(true)
                    .disableCull(true)
                    .disableVanillaFrustum(true)
                    .build())
            .input(
                CameraSettings.Input.builder()
                    .freeCursor(true)
                    .movement(EngineeringInputPolicy.movement(following, desktop))
                    .consumeHotbarScroll(true)
                    .blockDebugReloads(true)
                    .exclusiveKeyboard(true)
                    .build())
            .hud(EngineeringInputPolicy.hud(cameraPanel, scale, desktop, hudHidden))
            .navigation(
                EngineeringInputPolicy.navigation(
                    following,
                    FactoryControlSettings.value(FactoryControlSettings.Setting.ROTATION),
                    FactoryControlSettings.value(FactoryControlSettings.Setting.WHEEL_ZOOM),
                    desktop))
            .guiPolicy(WorldCameras.GuiPolicy.KEEP)
            .slice(xray.requested())
            .build();
        return settings;
    }

    @Override
    public boolean allowKey(int code) {
        // F1 is handled once at the raw queue boundary, before vanilla or any focused editor.
        if (code == Keyboard.KEY_F1) return false;
        if (WorkbenchHud.typing()) return false;
        if (FactoryShortcuts.suppresses(code) || FactoryShortcuts.action(code) != null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        if (EngineeringInputPolicy.playerMovement(following, WorkbenchHud.dashboardVisible())
            && (code == mc.gameSettings.keyBindForward.getKeyCode() || code == mc.gameSettings.keyBindBack.getKeyCode()
                || code == mc.gameSettings.keyBindRight.getKeyCode()
                || code == mc.gameSettings.keyBindLeft.getKeyCode()
                || code == mc.gameSettings.keyBindJump.getKeyCode()
                || code == mc.gameSettings.keyBindSneak.getKeyCode()
                || code == mc.gameSettings.keyBindSprint.getKeyCode()))
            return true;
        return code == TerminalBaubleController.keyCode() || code == Keyboard.KEY_ESCAPE
            || code == Keyboard.KEY_F8
            || code == mc.gameSettings.keyBindScreenshot.getKeyCode()
            || code == mc.gameSettings.keyBindChat.getKeyCode();
    }

    @Override
    public boolean allowRawKey(int code) {
        syncHudVisibility();
        if (EngineeringInputPolicy
            .visibilityKey(code, Keyboard.getEventKeyState(), Keyboard.isRepeatEvent(), Display.isActive(), () -> {
                Minecraft mc = Minecraft.getMinecraft();
                mc.gameSettings.hideGUI = !mc.gameSettings.hideGUI;
                syncHudVisibility();
            })) return false;
        if (!FactoryShortcuts.isBinding() && !WorkbenchHud.typing()
            && dashboardBinding(code)
            && FactoryShortcuts.action(code) == null) {
            KeyBinding.setKeyBindState(code, false);
            if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) {
                if (WorkbenchHud.dashboardOpen()) WorkbenchHud.switchTask(1);
                else WorkbenchHud.openDashboard();
            }
            return false;
        }
        if (hudHidden) {
            if (code == Keyboard.KEY_ESCAPE) {
                if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) {
                    if (WorkbenchHud.dashboardOpen()) WorkbenchHud.switchTask(1);
                    else close();
                }
                return false;
            }
            if (WorkbenchHud.dashboardOpen() && (code == Keyboard.KEY_E
                || code == Minecraft.getMinecraft().gameSettings.keyBindInventory.getKeyCode())) {
                if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent()) WorkbenchHud.switchTask(1);
                return false;
            }
            FactoryShortcuts.Action action = FactoryShortcuts.action(code);
            if (action != null && action != FactoryShortcuts.Action.TOGGLE
                && action != FactoryShortcuts.Action.RETURN
                && action != FactoryShortcuts.Action.FOLLOW
                && action != FactoryShortcuts.Action.XRAY
                && action != FactoryShortcuts.Action.PIN) {
                KeyBinding.setKeyBindState(code, false);
                return false;
            }
            if (FactoryShortcuts.consumeKey(code, Keyboard.getEventKeyState(), Keyboard.isRepeatEvent())) return false;
            return allowKey(code);
        }
        if (!FactoryShortcuts.isBinding()
            && WorkbenchHud.key(code, Keyboard.getEventCharacter(), Keyboard.getEventKeyState())) return false;
        if (FactoryShortcuts.consumeKey(code, Keyboard.getEventKeyState(), Keyboard.isRepeatEvent())) return false;
        // Vanilla opens the pause menu before FML KeyInputEvent. Handle world Esc at Core's earlier queue boundary.
        // Core bypasses this callback while a real GUI is open, so that GUI retains its normal Esc behavior.
        if (code == Keyboard.KEY_ESCAPE) {
            if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent() && !EngineeringHud.escape()) close();
            return false;
        }
        return allowKey(code);
    }

    private void syncHudVisibility() {
        boolean hidden = Minecraft.getMinecraft().gameSettings.hideGUI;
        if (hudHidden == hidden) return;
        hudHidden = hidden;
        settings = null;
        WorkbenchHud.suspendInput();
        // Releases from before F1 must not leave vanilla movement stuck after focus is relinquished.
        KeyBinding.unPressAllKeys();
    }

    @Override
    public CameraControls controls() {
        return this;
    }

    @Override
    public boolean supports(CameraControl control) {
        Minecraft mc = Minecraft.getMinecraft();
        return EngineeringInputPolicy
            .worldNavigation(isActive(), mc.currentScreen != null, mc.inGameHasFocus, WorkbenchHud.dashboardVisible())
            && !FactoryShortcuts.isBinding()
            && EngineeringInputPolicy.pointerControl(
                control,
                following,
                WorldCameraHooks.pointerCaptured(),
                EngineeringHud.blocksNavigation(Mouse.getEventX(), Mouse.getEventY()),
                WorkbenchHud.dashboardVisible());
    }

    @Override
    public void panWorld(double dx, double dy, double dz) {
        if (!supports(CameraControl.PAN) || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz))
            return;
        if (dx != 0 || dy != 0 || dz != 0) EngineeringHud.cancelAreaDrag();
        double sensitivity = FactoryControlSettings.value(FactoryControlSettings.Setting.MOUSE_PAN);
        dx *= sensitivity;
        dy *= sensitivity;
        dz *= sensitivity;
        pinned = false;
        x += dx;
        y += dy;
        z += dz;
        lastX += dx;
        lastY += dy;
        lastZ += dz;
    }

    @Override
    public void rotate(double dyaw, double dpitch) {
        if (WorkbenchHud.dashboardVisible()) return;
        if (dyaw != 0 || dpitch != 0) EngineeringHud.cancelAreaDrag();
        if (Double.isFinite(dyaw)) yaw = (yaw + dyaw) % 360;
        if (Double.isFinite(dpitch)) pitch = Math.max(15, Math.min(85, pitch + dpitch));
    }

    @Override
    public void zoom(double factor) {
        if (WorkbenchHud.dashboardVisible()) return;
        if (Double.isFinite(factor) && factor > 0) {
            if (factor != 1) EngineeringHud.cancelAreaDrag();
            zoom.multiply(factor, 0);
        }
    }

    @Override
    public void resetView() {
        if (WorkbenchHud.dashboardVisible()) return;
        yaw = 45;
        pitch = 60;
        zoom.snap(1);
    }

    @Override
    public void requestSlice(HeightSlice value) {
        if (WorkbenchHud.dashboardVisible()) return;
        xray.request(value);
        settings = null;
    }

    public static final class Target {

        public final int x, y, z;
        public final String name;

        Target(int x, int y, int z, String name) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
        }

        boolean matches(Target other) {
            return x == other.x && y == other.y && z == other.z;
        }
    }
}
