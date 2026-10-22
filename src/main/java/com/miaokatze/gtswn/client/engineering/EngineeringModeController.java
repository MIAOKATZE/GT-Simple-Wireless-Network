package com.miaokatze.gtswn.client.engineering;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.miaokatze.gtswn.client.TerminalBaubleController;
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
        "key.categories.gtswn");
    private final ZoomMotion zoom = new ZoomMotion(1, .125, 8);
    private WorldCameras.Lease lease;
    private World world;
    private double x, y, z, lastX, lastY, lastZ;
    private double yaw = 45, pitch = 60;
    private boolean following;
    private HeightSlice slice = HeightSlice.disabled();
    private CameraSettings settings;
    private int settingsWidth, settingsHeight;
    private Target selected;
    private boolean pinned;
    private boolean cameraPanel;

    private EngineeringModeController() {}

    public static void register() {
        ClientRegistry.registerKeyBinding(TOGGLE);
        FMLCommonHandler.instance()
            .bus()
            .register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(new EngineeringHud());
    }

    public static boolean isActive() {
        return INSTANCE.lease != null && INSTANCE.lease.active();
    }

    public static void toggle() {
        if (isActive()) close();
        else open();
    }

    public static void open() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.thePlayer.isDead) return;
        if (mc.currentScreen != null) mc.displayGuiScreen(null);
        if (isActive()) return;
        INSTANCE.world = mc.theWorld;
        INSTANCE.returnToPlayer();
        INSTANCE.lease = WorldCameras.tryActivate(OWNER, mc.theWorld, 20, INSTANCE, INSTANCE::revoked);
        if (!isActive()) {
            INSTANCE.world = null;
            mc.thePlayer.addChatMessage(new ChatComponentText("世界相机正在由其他模块使用，工厂模式未开启。"));
        } else {
            EngineeringHud.activateOverview();
            EngineeringTerminalHudSnapshot.tick(EngineeringDashboardCache.slot() >= 0);
        }
    }

    public static void close() {
        WorldCameras.Lease old = INSTANCE.lease;
        if (old != null) old.close();
        else INSTANCE.revoked();
    }

    private void revoked() {
        lease = null;
        world = null;
        selected = null;
        pinned = false;
        cameraPanel = false;
        slice = HeightSlice.disabled();
        settings = null;
        EngineeringHud.reset();
        EngineeringTerminalHudSnapshot.close();
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

    public double mapPlaneY() {
        return slice.enabled() && slice.hasUpper() ? slice.maximumY() - .01 : y;
    }

    public void selectPosition(int bx, int by, int bz, String name) {
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
        if (entry == null || world == null || entry.dim != world.provider.dimensionId) return;
        selected = new Target(entry.x, entry.y, entry.z, entry.displayName.isEmpty() ? entry.name : entry.displayName);
        pinned = false;
    }

    @SubscribeEvent
    public void keys(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.thePlayer == null) return;
        if (TOGGLE.isPressed()) toggle();
        if (!isActive() || !Keyboard.getEventKeyState() || Keyboard.isRepeatEvent()) return;
        // The terminal binding owns its press even when the player has rebound it to another engineering shortcut.
        if (Keyboard.getEventKey() == TerminalBaubleController.keyCode()) return;
        switch (Keyboard.getEventKey()) {
            case Keyboard.KEY_ESCAPE:
                if (!EngineeringHud.escape()) close();
                break;
            case Keyboard.KEY_M:
                toggleFollowing();
                break;
            case Keyboard.KEY_HOME:
                returnToPlayer();
                break;
            case Keyboard.KEY_P:
                togglePin();
                break;
            case Keyboard.KEY_DELETE:
                selected = null;
                pinned = false;
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) EngineeringDashboardCache.tick();
        if (event.phase != TickEvent.Phase.END || !isActive()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != world || mc.thePlayer == null || mc.thePlayer.isDead) {
            close();
            return;
        }
        EngineeringHud.tickSources();
        EngineeringTerminalHudSnapshot.tick(EngineeringDashboardCache.slot() >= 0);
        lastX = x;
        lastY = y;
        lastZ = z;
        if (!EngineeringInputPolicy.worldInput(isActive(), mc.currentScreen != null, mc.inGameHasFocus)) return;
        if (pinned && selected != null) {
            x = selected.x + .5;
            y = selected.y + .5;
            z = selected.z + .5;
        } else if (following) {
            returnToPlayer();
            double forward = key(mc.gameSettings.keyBindForward) - key(mc.gameSettings.keyBindBack);
            double right = key(mc.gameSettings.keyBindRight) - key(mc.gameSettings.keyBindLeft);
            if (forward != 0 || right != 0) {
                // Native collision, gravity, jumping and friction still execute. Only horizontal intent is replaced.
                double speed = mc.thePlayer.getEntityAttribute(SharedMonsterAttributes.movementSpeed)
                    .getAttributeValue() * 2.16;
                if (mc.thePlayer.isSneaking()) speed *= .3;
                double[] motion = EngineeringMotion.playerDelta(yaw, forward, right, speed);
                mc.thePlayer.motionX = motion[0];
                mc.thePlayer.motionZ = motion[2];
                float direction = (float) Math.toDegrees(Math.atan2(-motion[0], motion[2]));
                mc.thePlayer.rotationYaw = direction;
                mc.thePlayer.renderYawOffset = direction;
            }
        } else {
            double[] delta = EngineeringMotion.delta(
                yaw,
                zoom.actual(),
                key(mc.gameSettings.keyBindForward) - key(mc.gameSettings.keyBindBack),
                key(mc.gameSettings.keyBindRight) - key(mc.gameSettings.keyBindLeft),
                key(mc.gameSettings.keyBindJump) - key(mc.gameSettings.keyBindSneak));
            x += delta[0];
            y += delta[1];
            z += delta[2];
        }
    }

    private static int key(KeyBinding key) {
        int code = key.getKeyCode();
        if (com.miaokatze.gtswn.client.TerminalWheelInput.suppresses(code)) return 0;
        return code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code) ? 1
            : code < 0 && code + 100 >= 0 && Mouse.isButtonDown(code + 100) ? 1 : 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void mouse(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!EngineeringInputPolicy.worldInput(isActive(), mc.currentScreen != null, mc.inGameHasFocus)
            || event.isCanceled()) return;
        if (EngineeringHud.mouse(event)) {
            event.setCanceled(true);
            return;
        }
        if (event.button == 0 || event.button == 1) {
            event.setCanceled(true);
            if (event.button == 0 && event.buttonstate) {
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
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        if (settingsWidth != resolution.getScaledWidth() || settingsHeight != resolution.getScaledHeight()) {
            settingsWidth = resolution.getScaledWidth();
            settingsHeight = resolution.getScaledHeight();
            settings = null;
        }
        double scale = EngineeringHud.scale(settingsWidth, settingsHeight);
        if (settings == null) settings = CameraSettings.builder()
            .render(
                CameraSettings.Render.builder()
                    .hideHand(true)
                    .disableFog(true)
                    .disableCull(true)
                    .disableVanillaFrustum(true)
                    .build())
            .input(
                CameraSettings.Input.builder()
                    .freeCursor(true)
                    .movement(following ? WorldCameras.MovementPolicy.HORIZONTAL : WorldCameras.MovementPolicy.ALL)
                    .consumeHotbarScroll(true)
                    .blockDebugReloads(true)
                    .exclusiveKeyboard(true)
                    .build())
            .hud(
                CameraSettings.Hud.builder()
                    .crosshair(CameraSettings.Hud.Crosshair.HIDDEN)
                    .panelVisible(cameraPanel)
                    .panelPosition((int) (244 * scale), (int) (56 * scale))
                    .panelWidth(Math.max(1, (int) (192 * scale)))
                    .build())
            .navigation(EngineeringInputPolicy.navigation(following))
            .guiPolicy(WorldCameras.GuiPolicy.KEEP)
            .slice(slice)
            .build();
        return settings;
    }

    @Override
    public boolean allowKey(int code) {
        Minecraft mc = Minecraft.getMinecraft();
        if (following
            && (code == mc.gameSettings.keyBindJump.getKeyCode() || code == mc.gameSettings.keyBindSneak.getKeyCode()))
            return true;
        return code == TOGGLE.getKeyCode() || code == TerminalBaubleController.keyCode()
            || code == Keyboard.KEY_ESCAPE
            || code == Keyboard.KEY_M
            || code == Keyboard.KEY_P
            || code == Keyboard.KEY_HOME
            || code == Keyboard.KEY_DELETE
            || code == Keyboard.KEY_F8
            || code == mc.gameSettings.keyBindScreenshot.getKeyCode()
            || code == mc.gameSettings.keyBindChat.getKeyCode();
    }

    @Override
    public boolean allowRawKey(int code) {
        // Vanilla opens the pause menu before FML KeyInputEvent. Handle world Esc at Core's earlier queue boundary.
        // Core bypasses this callback while a real GUI is open, so that GUI retains its normal Esc behavior.
        if (code == Keyboard.KEY_ESCAPE) {
            if (Keyboard.getEventKeyState() && !Keyboard.isRepeatEvent() && !EngineeringHud.escape()) close();
            return false;
        }
        return allowKey(code);
    }

    @Override
    public CameraControls controls() {
        return this;
    }

    @Override
    public boolean supports(CameraControl control) {
        Minecraft mc = Minecraft.getMinecraft();
        return EngineeringInputPolicy.worldInput(isActive(), mc.currentScreen != null, mc.inGameHasFocus)
            && EngineeringInputPolicy.pointerControl(
                control,
                following,
                WorldCameraHooks.pointerCaptured(),
                EngineeringHud.blocksNavigation(Mouse.getEventX(), Mouse.getEventY()));
    }

    @Override
    public void panWorld(double dx, double dy, double dz) {
        if (!supports(CameraControl.PAN) || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz))
            return;
        if (dx != 0 || dy != 0 || dz != 0) EngineeringHud.cancelAreaDrag();
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
        if (dyaw != 0 || dpitch != 0) EngineeringHud.cancelAreaDrag();
        if (Double.isFinite(dyaw)) yaw = (yaw + dyaw) % 360;
        if (Double.isFinite(dpitch)) pitch = Math.max(15, Math.min(85, pitch + dpitch));
    }

    @Override
    public void zoom(double factor) {
        if (Double.isFinite(factor) && factor > 0) zoom.multiply(factor, 0);
    }

    @Override
    public void resetView() {
        yaw = 45;
        pitch = 60;
        zoom.snap(1);
    }

    @Override
    public void requestSlice(HeightSlice value) {
        slice = value;
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
