package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.render.LinkNodeInstallAnimation;
import com.miaokatze.gtswn.client.render.LinkNodeVisuals;
import com.miaokatze.gtswn.client.render.LinkNodeVisuals.Cell;
import com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.PacketLinkNodeInstalled;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.ICoverable;

/** Bounded client-only decorative mesh. It never changes cover bounds or collision. */
public final class LinkNodeVisualHandler {

    public static final LinkNodeVisualHandler INSTANCE = new LinkNodeVisualHandler();
    private static final double RANGE_SQUARED = 32 * 32;
    private final Set<TileEntity> hosts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Source> visible = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final TextureSet[][] textures = new TextureSet[2][2];
    private final LinkNodeInstallAnimation installations = new LinkNodeInstallAnimation();
    private final Map<String, PacketLinkNodeInstalled> installationTargets = new LinkedHashMap<>();
    private World world;
    private int discoveryCursor;

    private LinkNodeVisualHandler() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld) {
            clear();
            world = mc.theWorld;
        }
        if (world == null || mc.renderViewEntity == null || mc.isGamePaused()) return;
        updateInstallations();
        Entity camera = mc.renderViewEntity;
        List<?> loaded = world.loadedTileEntityList;
        for (int i = 0, count = Math.min(192, loaded.size()); i < count; i++) {
            Object object = loaded.get(Math.floorMod(discoveryCursor++, loaded.size()));
            if (object instanceof TileEntity && object instanceof ICoverable && hasNode((TileEntity) object)) {
                hosts.add((TileEntity) object);
            }
        }
        hosts.removeIf(host -> !present(host) || !hasNode(host));
        visible.clear();
        List<TileEntity> candidates = new ArrayList<>(hosts);
        candidates.sort(
            java.util.Comparator.comparingDouble(host -> host.getDistanceFrom(camera.posX, camera.posY, camera.posZ)));
        for (int i = 0, count = Math.min(128, candidates.size()); i < count; i++) {
            TileEntity host = candidates.get(i);
            if (host.getDistanceFrom(camera.posX, camera.posY, camera.posZ) > RANGE_SQUARED) continue;
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                Object cover = ((ICoverable) host).getCoverAtSide(side);
                if (cover instanceof GTswnCoverWirelessBase && ((GTswnCoverWirelessBase) cover).isValid()
                    && air(host, side.ordinal())) {
                    visible.add(new Source(host, side.ordinal(), cover instanceof GTswn_Cover_EnergyWireless));
                    if (visible.size() >= 128) break;
                }
            }
            if (visible.size() >= 128) break;
        }
        if (Config.quantumParticleDensity() == 0 || mc.gameSettings.particleSetting >= 2) {
            particles.clear();
            return;
        }
        particles.removeIf(
            particle -> !particle.source.valid() || !particle.source.particlesReady(0)
                || ++particle.age >= particle.life
                || particle.source.host.getDistanceFrom(camera.posX, camera.posY, camera.posZ) > RANGE_SQUARED);
        int budget = 16;
        for (Source source : visible) {
            if (budget <= 0 || particles.size() >= 256) break;
            if (!source.particlesReady(0)) continue;
            double chance = .28 * Config.quantumParticleDensity() * (mc.gameSettings.particleSetting == 1 ? .5 : 1);
            if (world.rand.nextDouble() < chance) {
                particles.add(new Particle(source));
                budget--;
            }
        }
    }

    /** Called only on the client thread by the installation event packet. */
    public void acceptInstallation(PacketLinkNodeInstalled message) {
        World current = Minecraft.getMinecraft().theWorld;
        if (!message.valid || current == null || current.provider.dimensionId != message.dimension) return;
        if (world != current) {
            clear();
            world = current;
        }
        String key = LinkNodeInstallAnimation.key(message.x, message.y, message.z, message.side);
        if (!installations.accept(key, message.nonce, world.getTotalWorldTime(), message.energy)) return;
        installationTargets.put(key, message);
        updateInstallations();
    }

    private void updateInstallations() {
        long tick = world.getTotalWorldTime();
        installations.prune(tick);
        installationTargets.entrySet()
            .removeIf(entry -> !installations.contains(entry.getKey()));
        for (Map.Entry<String, PacketLinkNodeInstalled> entry : installationTargets.entrySet()) {
            PacketLinkNodeInstalled target = entry.getValue();
            if (!world.getChunkProvider()
                .chunkExists(target.x >> 4, target.z >> 4)) continue;
            TileEntity host = world.getTileEntity(target.x, target.y, target.z);
            if (!(host instanceof ICoverable) || !present(host)) continue;
            Object cover = ((ICoverable) host).getCoverAtSide(ForgeDirection.getOrientation(target.side));
            if (!(cover instanceof GTswnCoverWirelessBase) || !((GTswnCoverWirelessBase) cover).isValid()) continue;
            boolean energy = cover instanceof GTswn_Cover_EnergyWireless;
            if (energy != target.energy) continue;
            installations.resolve(entry.getKey(), cover, energy, tick);
            // Avoid waiting for the normal rotating discovery budget after a real installation.
            hosts.add(host);
        }
    }

    private static boolean hasNode(TileEntity host) {
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            Object cover = ((ICoverable) host).getCoverAtSide(direction);
            if (cover instanceof GTswnCoverWirelessBase && ((GTswnCoverWirelessBase) cover).isValid()) return true;
        }
        return false;
    }

    private boolean present(TileEntity host) {
        return !host.isInvalid() && host.getWorldObj() == world
            && world.getChunkProvider()
                .chunkExists(host.xCoord >> 4, host.zCoord >> 4)
            && world.getTileEntity(host.xCoord, host.yCoord, host.zCoord) == host;
    }

    private boolean air(TileEntity host, int side) {
        ForgeDirection direction = ForgeDirection.getOrientation(side);
        int x = host.xCoord + direction.offsetX, y = host.yCoord + direction.offsetY;
        int z = host.zCoord + direction.offsetZ;
        return y >= 0 && y < 256
            && world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)
            && world.isAirBlock(x, y, z);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world == world) clear();
    }

    private void clear() {
        hosts.clear();
        visible.clear();
        particles.clear();
        installations.clear();
        installationTargets.clear();
        discoveryCursor = 0;
        world = null;
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        if (world == null || world != mc.theWorld || camera == null || visible.isEmpty()) return;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        boolean low = "low".equals(Config.quantumParticleQuality);
        boolean high = "high".equals(Config.quantumParticleQuality);
        double seconds = (world.getTotalWorldTime() + event.partialTicks) / 20D;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(x, y, z);
        List<Source> eligible = new ArrayList<>();
        boolean[] activeTypes = new boolean[2];
        for (Source source : visible) {
            if (!source.valid() || !front(source, camera) || !inView(source, frustum)) continue;
            eligible.add(source);
            activeTypes[source.energy ? 0 : 1] = true;
        }
        if (eligible.isEmpty()) return;
        eligible.sort(
            java.util.Comparator.comparingDouble((Source source) -> source.host.getDistanceFrom(x, y, z))
                .reversed());
        float oldX = OpenGlHelper.lastBrightnessX, oldY = OpenGlHelper.lastBrightnessY;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-x, -y, -z);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDepthMask(false);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
            for (int type = 0; type < 2; type++) {
                if (!activeTypes[type]) continue;
                TextureSet texture = textures[low ? 0 : 1][type];
                if (texture == null) textures[low ? 0 : 1][type] = texture = new TextureSet(low ? 32 : 64, type == 0);
                texture.update(seconds);
            }
            // Keep back-to-front order across both materials. Adjacent matching materials share a batch.
            for (int pass = 0; pass < 2; pass++) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, pass == 0 ? GL11.GL_ONE_MINUS_SRC_ALPHA : GL11.GL_ONE);
                Tessellator tess = Tessellator.instance;
                int previousType = -1;
                for (Source source : eligible) {
                    int type = source.energy ? 0 : 1;
                    if (type != previousType) {
                        if (previousType >= 0) tess.draw();
                        TextureSet texture = textures[low ? 0 : 1][type];
                        mc.getTextureManager()
                            .bindTexture(pass == 0 ? texture.baseLocation : texture.glowLocation);
                        tess.startDrawingQuads();
                        tess.setColorRGBA_F(1, 1, 1, pass == 0 ? Config.linkNodeOpacity : 1);
                        previousType = type;
                    }
                    surface(source, high, pass == 0 ? Config.linkNodeOpacity : 1, event.partialTicks);
                }
                if (previousType >= 0) tess.draw();
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (high) relief(seconds, eligible, event.partialTicks);
            if (!low && mc.gameSettings.particleSetting < 2) {
                drawParticles(event.partialTicks, false, frustum);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                drawParticles(event.partialTicks, true, frustum);
            }
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, oldX, oldY);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void surface(Source source, boolean high, float opacity, float partial) {
        double elapsed = source.installElapsed(partial);
        if (!high && elapsed >= LinkNodeInstallAnimation.DURATION_TICKS) {
            Tessellator.instance.setColorRGBA_F(1, 1, 1, opacity);
            quad(source, .001, -.5, -.5, .5, .5);
            return;
        }
        for (Cell cell : LinkNodeVisuals.CELLS) {
            float alpha = (float) LinkNodeInstallAnimation.alpha(cell.ring, elapsed);
            if (alpha <= 0) continue;
            Tessellator.instance.setColorRGBA_F(1, 1, 1, opacity * alpha);
            double depth = high
                ? Math.max(.001, LinkNodeVisuals.surfaceDepth(cell, Config.linkNodeDepth, Config.linkNodeRelief))
                : .001;
            for (int edge = 0; edge < 6; edge++) {
                double angle = edge * Math.PI / 3, next = (edge + 1) * Math.PI / 3;
                double u = cell.u + Math.cos(angle) * LinkNodeVisuals.CELL_RADIUS;
                double v = cell.v + Math.sin(angle) * LinkNodeVisuals.CELL_RADIUS;
                double nu = cell.u + Math.cos(next) * LinkNodeVisuals.CELL_RADIUS;
                double nv = cell.v + Math.sin(next) * LinkNodeVisuals.CELL_RADIUS;
                vertex(source, cell.u, cell.v, depth, cell.u + .5, cell.v + .5);
                vertex(source, u, v, depth, u + .5, v + .5);
                vertex(source, nu, nv, depth, nu + .5, nv + .5);
                vertex(source, cell.u, cell.v, depth, cell.u + .5, cell.v + .5);
            }
        }
    }

    private boolean front(Source source, Entity camera) {
        double[] n = LinkNodeVisuals.normal(source.side);
        return (camera.posX - source.host.xCoord - .5) * n[0]
            + (camera.posY + camera.getEyeHeight() - source.host.yCoord - .5) * n[1]
            + (camera.posZ - source.host.zCoord - .5) * n[2] > .48;
    }

    private static boolean inView(Source source, Frustrum frustum) {
        return frustum.isBoundingBoxInFrustum(
            AxisAlignedBB.getBoundingBox(
                source.host.xCoord - 1,
                source.host.yCoord - 1,
                source.host.zCoord - 1,
                source.host.xCoord + 2,
                source.host.yCoord + 2,
                source.host.zCoord + 2));
    }

    private void relief(double seconds, List<Source> eligible, float partial) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        for (Source source : eligible) {
            double elapsed = source.installElapsed(partial);
            for (Cell cell : LinkNodeVisuals.CELLS) {
                double depth = Math
                    .max(.001, LinkNodeVisuals.surfaceDepth(cell, Config.linkNodeDepth, Config.linkNodeRelief));
                double light = LinkNodeVisuals.brightness(cell, seconds, false, source.energy);
                tess.setColorRGBA_F(
                    source.energy ? 1 : .87F,
                    source.energy ? .7F : .3F,
                    source.energy ? .14F : 1,
                    (float) (Config.linkNodeOpacity * (.25 + light * .6)
                        * LinkNodeInstallAnimation.alpha(cell.ring, elapsed)));
                for (int edge = 0; edge < 6; edge++) {
                    double angle = edge * Math.PI / 3, next = (edge + 1) * Math.PI / 3;
                    double radius = LinkNodeVisuals.CELL_RADIUS * .93;
                    double u = cell.u + Math.cos(angle) * radius, v = cell.v + Math.sin(angle) * radius;
                    double nu = cell.u + Math.cos(next) * radius, nv = cell.v + Math.sin(next) * radius;
                    vertex(source, u, v, .001, 0, 0);
                    vertex(source, nu, nv, .001, 0, 0);
                    vertex(source, nu, nv, depth, 0, 0);
                    vertex(source, u, v, depth, 0, 0);
                }
            }
        }
        tess.draw();
    }

    private static void quad(Source source, double depth, double u0, double v0, double u1, double v1) {
        vertex(source, u0, v0, depth, 0, 0);
        vertex(source, u1, v0, depth, 1, 0);
        vertex(source, u1, v1, depth, 1, 1);
        vertex(source, u0, v1, depth, 0, 1);
    }

    private static void vertex(Source source, double u, double v, double depth, double tu, double tv) {
        double[] point = LinkNodeVisuals.point(source.side, u, v, depth);
        Tessellator.instance.addVertexWithUV(
            source.host.xCoord + point[0],
            source.host.yCoord + point[1],
            source.host.zCoord + point[2],
            tu,
            tv);
    }

    private void drawParticles(float partial, boolean glow, Frustrum frustum) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        for (Particle particle : particles) {
            if (!particle.source.valid() || !particle.source.particlesReady(partial)
                || !inView(particle.source, frustum)) continue;
            double progress = (particle.age + partial) / particle.life;
            double depth = LinkNodeVisuals.particleDepth(
                Config.quantumParticleQuality,
                particle.u,
                particle.v,
                Config.linkNodeDepth,
                Config.linkNodeRelief,
                particle.source.energy,
                progress);
            double[] point = LinkNodeVisuals.point(particle.source.side, particle.u, particle.v, depth);
            double radius = particle.radius * (glow ? 1.85 : 1);
            float alpha = (float) (Math.min(1, (particle.age + partial + 1) / 3)
                * Math.min(1, (particle.life - particle.age - partial) / 4));
            tess.setColorRGBA_F(
                particle.source.energy ? 1 : .95F,
                particle.source.energy ? .78F : .35F,
                particle.source.energy ? .25F : 1,
                alpha * (glow ? .22F : .82F));
            cube(
                particle.source.host.xCoord + point[0],
                particle.source.host.yCoord + point[1],
                particle.source.host.zCoord + point[2],
                radius);
        }
        tess.draw();
    }

    private static void cube(double x, double y, double z, double radius) {
        int[][] faces = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 }, { 0, 2, 6, 4 },
            { 1, 5, 7, 3 } };
        for (int[] face : faces) {
            for (int corner : face) Tessellator.instance.addVertex(
                x + ((corner & 1) == 0 ? -radius : radius),
                y + ((corner & 4) == 0 ? -radius : radius),
                z + ((corner & 2) == 0 ? -radius : radius));
        }
    }

    private final class Source {

        private final TileEntity host;
        private final int side;
        private final boolean energy;
        private final Object installedCover;
        private final String installationKey;

        private Source(TileEntity host, int side, boolean energy) {
            this.host = host;
            this.side = side;
            this.energy = energy;
            installationKey = LinkNodeInstallAnimation.key(host.xCoord, host.yCoord, host.zCoord, side);
            installedCover = ((ICoverable) host).getCoverAtSide(ForgeDirection.getOrientation(side));
        }

        private double installElapsed(float partial) {
            return installations.elapsed(installationKey, installedCover, world.getTotalWorldTime(), partial);
        }

        private boolean particlesReady(float partial) {
            return installElapsed(partial) >= LinkNodeInstallAnimation.DURATION_TICKS;
        }

        private boolean valid() {
            if (!present(host) || !air(host, side)) return false;
            Object cover = ((ICoverable) host).getCoverAtSide(ForgeDirection.getOrientation(side));
            return cover == installedCover && cover instanceof GTswnCoverWirelessBase
                && ((GTswnCoverWirelessBase) cover).isValid()
                && (cover instanceof GTswn_Cover_EnergyWireless) == energy;
        }
    }

    private final class Particle {

        private final Source source;
        private final double u, v, radius;
        private final int life;
        private int age;

        private Particle(Source source) {
            this.source = source;
            double[] xy = LinkNodeVisuals.sampleColumn(world.rand);
            u = xy[0];
            v = xy[1];
            radius = .024 + world.rand.nextDouble() * .02;
            life = 24 + world.rand.nextInt(17);
        }
    }

    private static final class TextureSet {

        private final int size;
        private final boolean energy;
        private final DynamicTexture base, glow;
        private final ResourceLocation baseLocation, glowLocation;
        private long frame = Long.MIN_VALUE;

        private TextureSet(int size, boolean energy) {
            this.size = size;
            this.energy = energy;
            base = new DynamicTexture(size, size);
            glow = new DynamicTexture(size, size);
            baseLocation = Minecraft.getMinecraft()
                .getTextureManager()
                .getDynamicTextureLocation("link_node_base", base);
            glowLocation = Minecraft.getMinecraft()
                .getTextureManager()
                .getDynamicTextureLocation("link_node_glow", glow);
        }

        private void update(double seconds) {
            long next = (long) Math.floor(seconds * (size == 32 ? 3 : 10));
            if (next == frame) return;
            frame = next;
            System.arraycopy(
                LinkNodeVisuals.pixels(size, seconds, energy, false),
                0,
                base.getTextureData(),
                0,
                size * size);
            System.arraycopy(
                LinkNodeVisuals.pixels(size, seconds, energy, true),
                0,
                glow.getTextureData(),
                0,
                size * size);
            base.updateDynamicTexture();
            glow.updateDynamicTexture();
        }
    }
}
