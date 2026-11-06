package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
import com.miaokatze.miaokatzecore.client.fx.VoxelGeometry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.tileentity.ICoverable;

/** Enhanced material mesh and particles; legacy faces are rendered natively by GT. */
public final class LinkNodeVisualHandler {

    public static final LinkNodeVisualHandler INSTANCE = new LinkNodeVisualHandler();
    // 18 * 37 cells * 24 vertices * 8 ints = 127872 ints, below Tessellator's 131072-int retention bound.
    private static final int SOURCE_BATCH_LIMIT = 18;
    private final Set<TileEntity> hosts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Source> visible = new ArrayList<>();
    private final List<Source> eligible = new ArrayList<>();
    private final double[][] cellLight = new double[2][LinkNodeVisuals.CELLS.size()];
    private final double[] cellDepth = new double[LinkNodeVisuals.CELLS.size()];
    private static final float[] PARTICLE_SHADE = { 1, 1, 1, 1, 1, 1 };
    private final List<Particle> particles = new ArrayList<>();
    private final TextureSet[][] textures = new TextureSet[4][2];
    private final Map<Integer, TextureSet> voltageTextures = new HashMap<>();
    private final LinkNodeInstallAnimation installations = new LinkNodeInstallAnimation();
    private final Map<String, PacketLinkNodeInstalled> installationTargets = new LinkedHashMap<>();
    private World world;
    private int discoveryCursor;
    private String observedMaterial;

    private LinkNodeVisualHandler() {}

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld) {
            clear();
            world = mc.theWorld;
        }
        if (world != null) {
            if (observedMaterial != null && !observedMaterial.equals(Config.tapMaterial)) {
                // Native cover textures are baked into chunk geometry. All setting entry points converge here.
                mc.renderGlobal.loadRenderers();
            }
            observedMaterial = Config.tapMaterial;
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
            if (!withinDistance(host, camera.posX, camera.posY, camera.posZ)) continue;
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
        if (Config.tapParticleDensity() == 0 || mc.gameSettings.particleSetting >= 2) {
            particles.clear();
            return;
        }
        particles.removeIf(
            particle -> !particle.source.valid() || !particle.source.particlesReady(0)
                || !withinDistance(particle.source.host, camera.posX, camera.posY, camera.posZ)
                || ++particle.age >= particle.life);
        int budget = 12;
        for (Source source : visible) {
            if (budget <= 0 || particles.size() >= 192) break;
            if (!source.particlesReady(0)) continue;
            double chance = .21 * Config.tapParticleDensity() * (mc.gameSettings.particleSetting == 1 ? .5 : 1);
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
        eligible.clear();
        particles.clear();
        installations.clear();
        installationTargets.clear();
        discoveryCursor = 0;
        observedMaterial = null;
        world = null;
        for (TextureSet[] material : textures) {
            for (int type = 0; type < material.length; type++) {
                if (material[type] != null) material[type].delete();
                material[type] = null;
            }
        }
        for (TextureSet texture : voltageTextures.values()) texture.delete();
        voltageTextures.clear();
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity camera = mc.renderViewEntity;
        if (world == null || world != mc.theWorld || camera == null || visible.isEmpty()) return;
        double x = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double y = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double z = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        String material = Config.tapMaterial;
        boolean high = "high".equals(material);
        boolean legacy = LinkNodeVisuals.legacyMaterial(material);
        double seconds = (world.getTotalWorldTime() + event.partialTicks) / 20D;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(x, y, z);
        eligible.clear();
        for (Source source : visible) {
            if (!source.valid() || !withinDistance(source.host, x, y, z)
                || !front(source, camera)
                || !inView(source, frustum)) continue;
            source.prepareFrame(event.partialTicks);
            eligible.add(source);
        }
        if (eligible.isEmpty()) return;
        eligible.sort(
            java.util.Comparator.comparingDouble((Source source) -> source.host.getDistanceFrom(x, y, z))
                .reversed());
        if (high) {
            for (int i = 0; i < LinkNodeVisuals.CELLS.size(); i++) {
                Cell cell = LinkNodeVisuals.CELLS.get(i);
                cellDepth[i] = Math
                    .max(.001, LinkNodeVisuals.surfaceDepth(cell, Config.linkNodeDepth, Config.linkNodeRelief));
                cellLight[0][i] = LinkNodeVisuals.brightness(cell, seconds, false, true);
                cellLight[1][i] = LinkNodeVisuals.brightness(cell, seconds, false, false);
            }
        }
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
            if (!legacy) {
                for (Source source : eligible) texture(source, material).update(seconds);
            }
            // Legacy faces belong solely to GT's block renderer; only enhanced materials draw these passes.
            // Keep back-to-front order across all types and tiers. Adjacent matching textures share a batch.
            for (int pass = 0; !legacy && pass < 2; pass++) {
                GL11.glEnable(GL11.GL_TEXTURE_2D);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, pass == 0 ? GL11.GL_ONE_MINUS_SRC_ALPHA : GL11.GL_ONE);
                Tessellator tess = Tessellator.instance;
                TextureSet previousTexture = null;
                int sourcesInBatch = 0;
                for (Source source : eligible) {
                    TextureSet texture = texture(source, material);
                    if (texture != previousTexture || high && sourcesInBatch == SOURCE_BATCH_LIMIT) {
                        if (previousTexture != null) tess.draw();
                        if (texture != previousTexture) {
                            mc.getTextureManager()
                                .bindTexture(pass == 0 ? texture.baseLocation : texture.glowLocation);
                        }
                        tess.startDrawingQuads();
                        tess.setColorRGBA_F(1, 1, 1, pass == 0 ? Config.linkNodeOpacity : 1);
                        previousTexture = texture;
                        sourcesInBatch = 0;
                    }
                    surface(source, high, pass == 0 ? Config.linkNodeOpacity : 1, event.partialTicks);
                    sourcesInBatch++;
                }
                if (previousTexture != null) tess.draw();
            }
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            if (high) relief(eligible);
            if (Config.tapParticleDensity() > 0 && mc.gameSettings.particleSetting < 2) {
                prepareParticles(event.partialTicks, frustum, x, y, z);
                drawParticles(false);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                drawParticles(true);
            }
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, oldX, oldY);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private TextureSet texture(Source source, String material) {
        int rgb = source.voltageRgb();
        int type = source.energy ? 0 : 1;
        if (rgb >= 0) {
            // RGB comes solely from the finite GT tier palette. Never allocate textures per host or face.
            int rings = LinkNodeVisuals.voltageRings(Config.tapVoltageColorPercent);
            int key = rgb << 3 | (rings - 1) << 1 | type;
            return voltageTextures
                .computeIfAbsent(key, ignored -> new TextureSet(material, source.energy, rgb, rings * 25));
        }
        int materialIndex = LinkNodeVisuals.textureIndex(material);
        TextureSet texture = textures[materialIndex][type];
        if (texture == null) textures[materialIndex][type] = texture = new TextureSet(material, source.energy, -1, 75);
        return texture;
    }

    private void surface(Source source, boolean high, float opacity, float partial) {
        double elapsed = source.frameElapsed;
        if (!high && elapsed >= LinkNodeInstallAnimation.DURATION_TICKS) {
            Tessellator.instance.setColorRGBA_F(1, 1, 1, opacity);
            quad(source, .001, -.5, -.5, .5, .5);
            return;
        }
        for (int i = 0; i < LinkNodeVisuals.CELLS.size(); i++) {
            Cell cell = LinkNodeVisuals.CELLS.get(i);
            float alpha = (float) source.frameAlpha[cell.ring];
            if (alpha <= 0) continue;
            Tessellator.instance.setColorRGBA_F(1, 1, 1, opacity * alpha);
            double depth = high ? cellDepth[i] : .001;
            for (int edge = 0; edge < 6; edge++) {
                double u = cell.surfaceU(edge), v = cell.surfaceV(edge);
                double nu = cell.surfaceU(edge + 1), nv = cell.surfaceV(edge + 1);
                vertex(source, cell.u, cell.v, depth, cell.u + .5, cell.v + .5);
                vertex(source, u, v, depth, u + .5, v + .5);
                vertex(source, nu, nv, depth, nu + .5, nv + .5);
                vertex(source, cell.u, cell.v, depth, cell.u + .5, cell.v + .5);
            }
        }
    }

    private boolean front(Source source, Entity camera) {
        ForgeDirection normal = ForgeDirection.getOrientation(source.side);
        return (camera.posX - source.host.xCoord - .5) * normal.offsetX
            + (camera.posY + camera.getEyeHeight() - source.host.yCoord - .5) * normal.offsetY
            + (camera.posZ - source.host.zCoord - .5) * normal.offsetZ > .48;
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

    private static boolean withinDistance(TileEntity host, double x, double y, double z) {
        return host.getDistanceFrom(x, y, z) <= (double) Config.tapRenderDistance * Config.tapRenderDistance;
    }

    private void relief(List<Source> eligible) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        int sourcesInBatch = 0;
        for (Source source : eligible) {
            if (sourcesInBatch == SOURCE_BATCH_LIMIT) {
                tess.draw();
                tess.startDrawingQuads();
                sourcesInBatch = 0;
            }
            sourcesInBatch++;
            int voltageRgb = source.voltageRgb();
            for (int i = 0; i < LinkNodeVisuals.CELLS.size(); i++) {
                Cell cell = LinkNodeVisuals.CELLS.get(i);
                double depth = cellDepth[i];
                double light = cellLight[source.energy ? 0 : 1][i];
                int rgb = LinkNodeVisuals.cellRgb(cell.ring, source.energy, voltageRgb, Config.tapVoltageColorPercent);
                boolean tinted = LinkNodeVisuals.voltageCell(cell.ring, voltageRgb, Config.tapVoltageColorPercent);
                tess.setColorRGBA_F(
                    tinted ? (rgb >> 16 & 255) / 255F : source.energy ? 1 : .87F,
                    tinted ? (rgb >> 8 & 255) / 255F : source.energy ? .7F : .3F,
                    tinted ? (rgb & 255) / 255F : source.energy ? .14F : 1,
                    (float) (Config.linkNodeOpacity * (.25 + light * .6) * source.frameAlpha[cell.ring]));
                for (int edge = 0; edge < 6; edge++) {
                    double u = cell.wallU(edge), v = cell.wallV(edge);
                    double nu = cell.wallU(edge + 1), nv = cell.wallV(edge + 1);
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
        Tessellator.instance.addVertexWithUV(
            source.host.xCoord + LinkNodeVisuals.pointCoordinate(source.side, 0, u, v, depth),
            source.host.yCoord + LinkNodeVisuals.pointCoordinate(source.side, 1, u, v, depth),
            source.host.zCoord + LinkNodeVisuals.pointCoordinate(source.side, 2, u, v, depth),
            tu,
            tv);
    }

    private void prepareParticles(float partial, Frustrum frustum, double cameraX, double cameraY, double cameraZ) {
        for (Particle particle : particles) {
            particle.render = false;
            if (!particle.source.valid() || !particle.source.particlesReady(partial)
                || !inView(particle.source, frustum)) continue;
            double age = particle.age + partial;
            double progress = age / particle.life;
            double depth = LinkNodeVisuals.particleDepth(
                Config.tapMaterial,
                particle.u,
                particle.v,
                Config.linkNodeDepth,
                Config.linkNodeRelief,
                particle.source.energy,
                progress);
            particle.x = particle.source.host.xCoord
                + LinkNodeVisuals.pointCoordinate(particle.source.side, 0, particle.u, particle.v, depth);
            particle.y = particle.source.host.yCoord
                + LinkNodeVisuals.pointCoordinate(particle.source.side, 1, particle.u, particle.v, depth);
            particle.z = particle.source.host.zCoord
                + LinkNodeVisuals.pointCoordinate(particle.source.side, 2, particle.u, particle.v, depth);
            double dx = particle.x - cameraX, dy = particle.y - cameraY, dz = particle.z - cameraZ;
            if (dx * dx + dy * dy + dz * dz > (double) Config.tapRenderDistance * Config.tapRenderDistance) continue;
            particle.render = true;
            int rgb = LinkNodeVisuals.particleVoltageRgb(
                particle.u,
                particle.v,
                particle.source.voltageRgb(),
                Config.tapVoltageColorPercent);
            boolean energy = particle.source.energy;
            particle.red = rgb >= 0 ? (rgb >> 16 & 255) / 255F : energy ? 1 : .95F;
            particle.green = rgb >= 0 ? (rgb >> 8 & 255) / 255F : energy ? .78F : .35F;
            particle.blue = rgb >= 0 ? (rgb & 255) / 255F : energy ? .25F : 1;
            particle.bodyAlpha = (float) LinkNodeVisuals.particleOpacity(age, particle.life, false);
            particle.glowAlpha = (float) LinkNodeVisuals.particleOpacity(age, particle.life, true);
            particle.renderRadius = particle.radius * 1.5 * Config.tapParticleSizePercent / 100D;
        }
    }

    private void drawParticles(boolean glow) {
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        for (Particle particle : particles) {
            if (!particle.render) continue;
            VoxelGeometry.tessellatedCube(
                tess,
                particle.x,
                particle.y,
                particle.z,
                particle.renderRadius * (glow ? 1.85 : 1),
                particle.red,
                particle.green,
                particle.blue,
                glow ? particle.glowAlpha : particle.bodyAlpha,
                PARTICLE_SHADE);
        }
        tess.draw();
    }

    private final class Source {

        private final TileEntity host;
        private final int side;
        private final boolean energy;
        private final int machineRgb;
        private final Object installedCover;
        private final String installationKey;
        private final double[] frameAlpha = new double[4];
        private double frameElapsed;

        private void prepareFrame(float partial) {
            frameElapsed = installElapsed(partial);
            for (int ring = 0; ring < frameAlpha.length; ring++) {
                frameAlpha[ring] = LinkNodeInstallAnimation.alpha(ring, frameElapsed);
            }
        }

        private Source(TileEntity host, int side, boolean energy) {
            this.host = host;
            this.side = side;
            this.energy = energy;
            machineRgb = LinkNodeVoltageTier.rgb(host);
            installationKey = LinkNodeInstallAnimation.key(host.xCoord, host.yCoord, host.zCoord, side);
            installedCover = ((ICoverable) host).getCoverAtSide(ForgeDirection.getOrientation(side));
        }

        private int voltageRgb() {
            return LinkNodeVisuals.voltageRgb(Config.tapMaterial, Config.tapVoltageColorsEnabled, machineRgb);
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
        private boolean render;
        private double x, y, z, renderRadius;
        private float red, green, blue, bodyAlpha, glowAlpha;

        private Particle(Source source) {
            this.source = source;
            double[] xy = LinkNodeVisuals.sampleColumn(world.rand);
            u = xy[0];
            v = xy[1];
            radius = .0238;
            life = 24 + world.rand.nextInt(17);
        }
    }

    private static final class TextureSet {

        private final int size;
        private final boolean energy;
        private final int voltageRgb;
        private final int voltagePercent;
        private final DynamicTexture base, glow;
        private final ResourceLocation baseLocation, glowLocation;
        private long frame = Long.MIN_VALUE;

        private TextureSet(String material, boolean energy, int voltageRgb, int voltagePercent) {
            this.size = LinkNodeVisuals.resolution(material);
            this.energy = energy;
            this.voltageRgb = voltageRgb;
            this.voltagePercent = voltagePercent;
            int size = this.size;
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
            LinkNodeVisuals.writePixels(
                size,
                seconds,
                energy,
                voltageRgb,
                voltagePercent,
                base.getTextureData(),
                glow.getTextureData());
            base.updateDynamicTexture();
            glow.updateDynamicTexture();
        }

        private void delete() {
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(baseLocation);
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(glowLocation);
        }
    }
}
