package com.miaokatze.gtswn.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;

import com.miaokatze.gtswn.client.QuantumParticleDirections.Direction;
import com.miaokatze.gtswn.common.quantum.QuantumNetworkColor;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode;
import com.miaokatze.gtswn.config.Config;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-only, bounded voxel effects. Register the same instance on the Forge and FML buses. */
public final class QuantumVoxelParticleHandler {

    public static final QuantumVoxelParticleHandler INSTANCE = new QuantumVoxelParticleHandler();

    private static final int MAX_PARTICLES = 512;
    private static final int EMISSION_BUDGET = 32;
    private static final int SOURCE_BUDGET = 128;
    private static final int DISCOVERY_BUDGET = 256;
    private static final int[][] CUBE_FACES = { { 0, 1, 3, 2 }, { 4, 6, 7, 5 }, { 0, 4, 5, 1 }, { 2, 3, 7, 6 },
        { 0, 2, 6, 4 }, { 1, 5, 7, 3 } };
    private static final float[] FACE_SHADE = { .65F, 1F, .8F, .8F, .7F, .7F };

    private final List<Particle> particles = new ArrayList<>();
    private final Set<TileEntityNetworkQuantumNode> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
    private World currentWorld;
    private int discoveryCursor;
    private int sourceCursor;

    private QuantumVoxelParticleHandler() {}

    private void clear() {
        particles.clear();
        nodes.clear();
        discoveryCursor = 0;
        sourceCursor = 0;
        currentWorld = null;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        World world = minecraft.theWorld;
        if (currentWorld != world) {
            clear();
            currentWorld = world;
        }
        if (world == null || minecraft.thePlayer == null) return;
        if (minecraft.gameSettings.particleSetting >= 2 || Config.quantumParticleDensity() == 0) {
            particles.clear();
            return;
        }
        if (minecraft.isGamePaused()) return;
        Entity viewer = minecraft.renderViewEntity;
        if (viewer == null) return;
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle particle = iterator.next();
            if (!particle.valid(world) || ++particle.age >= particle.lifetime) {
                iterator.remove();
            } else {
                particle.previousX = particle.x;
                particle.previousY = particle.y;
                particle.previousZ = particle.z;
                particle.x += particle.vx;
                particle.y += particle.vy;
                particle.z += particle.vz;
            }
        }
        discover(world);
        nodes.removeIf(node -> !present(world, node));
        List<TileEntity> sources = new ArrayList<>(nodes);
        for (TileEntity tile : QuantumIncorporationClientState.tiles(world)) sources.add(tile);
        if (sources.isEmpty()) return;
        int remaining = EMISSION_BUDGET;
        int count = Math.min(SOURCE_BUDGET, sources.size());
        for (int i = 0; i < count && remaining > 0 && particles.size() < MAX_PARTICLES; i++) {
            TileEntity tile = sources.get(Math.floorMod(sourceCursor++, sources.size()));
            if (!withinRenderDistance(
                tile.xCoord + .5D,
                tile.yCoord + .5D,
                tile.zCoord + .5D,
                viewer.posX,
                viewer.posY,
                viewer.posZ,
                Config.quantumRenderDistance)) continue;
            boolean node = tile instanceof TileEntityNetworkQuantumNode;
            List<Direction> directions;
            if (node) {
                TileEntityNetworkQuantumNode quantum = (TileEntityNetworkQuantumNode) tile;
                if (!quantum.isLinkedClient()) continue;
                directions = QuantumParticleDirections.nodeDirections(quantum.getConnectedSidesMask());
            } else {
                // The authoritative incorporation cache already enforces complete block bounds.
                directions = QuantumParticleDirections.openFaces(airMask(world, tile));
            }
            double chance = (minecraft.gameSettings.particleSetting == 1 ? .055D : .12D)
                * Config.quantumParticleDensity();
            // Each available direction gets its own roll, so another air face increases emission.
            int first = directions.size() == 0 ? 0 : world.rand.nextInt(directions.size());
            for (int j = 0; j < directions.size() && remaining > 0 && particles.size() < MAX_PARTICLES; j++) {
                Direction direction = directions.get((first + j) % directions.size());
                int emissions = emissionCount(chance, world.rand.nextDouble());
                for (int emission = 0; emission < emissions && remaining > 0
                    && particles.size() < MAX_PARTICLES; emission++) {
                    particles.add(new Particle(world, tile, direction, node));
                    remaining--;
                }
            }
        }
    }

    private void discover(World world) {
        // Vanilla's already-loaded list is read incrementally; no chunk lookup or force-loading to discover nodes.
        List<?> loaded = world.loadedTileEntityList;
        int count = Math.min(DISCOVERY_BUDGET, loaded.size());
        for (int i = 0; i < count; i++) {
            Object tile = loaded.get(Math.floorMod(discoveryCursor++, loaded.size()));
            if (tile instanceof TileEntityNetworkQuantumNode) nodes.add((TileEntityNetworkQuantumNode) tile);
        }
    }

    private static boolean present(World world, TileEntity tile) {
        return !tile.isInvalid() && tile.getWorldObj() == world
            && world.getChunkProvider()
                .chunkExists(tile.xCoord >> 4, tile.zCoord >> 4)
            && world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    private static int airMask(World world, TileEntity tile) {
        int mask = 0;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (airFace(world, tile, side.ordinal())) mask |= 1 << side.ordinal();
        }
        return mask;
    }

    private static boolean airFace(World world, TileEntity tile, int side) {
        ForgeDirection direction = ForgeDirection.getOrientation(side);
        int x = tile.xCoord + direction.offsetX;
        int y = tile.yCoord + direction.offsetY;
        int z = tile.zCoord + direction.offsetZ;
        return y >= 0 && y < 256
            && world.getChunkProvider()
                .chunkExists(x >> 4, z >> 4)
            && world.isAirBlock(x, y, z);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world == currentWorld) clear();
    }

    @SubscribeEvent
    public void unloadChunk(ChunkEvent.Unload event) {
        if (event.world != currentWorld) return;
        int chunkX = event.getChunk().xPosition;
        int chunkZ = event.getChunk().zPosition;
        nodes.removeIf(node -> (node.xCoord >> 4) == chunkX && (node.zCoord >> 4) == chunkZ);
        particles
            .removeIf(particle -> (particle.source.xCoord >> 4) == chunkX && (particle.source.zCoord >> 4) == chunkZ);
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        Entity camera = minecraft.renderViewEntity;
        if (particles.isEmpty() || currentWorld != minecraft.theWorld
            || camera == null
            || minecraft.gameSettings.particleSetting >= 2
            || Config.quantumParticleDensity() == 0) return;
        double cameraX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * event.partialTicks;
        double cameraY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * event.partialTicks;
        double cameraZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * event.partialTicks;
        Frustrum frustum = new Frustrum();
        frustum.setPosition(cameraX, cameraY, cameraZ);
        float lightX = OpenGlHelper.lastBrightnessX;
        float lightY = OpenGlHelper.lastBrightnessY;
        // Outpost's mesh strategy: one state scope for the entire frame, alpha body + additive glow.
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-cameraX, -cameraY, -cameraZ);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDepthMask(false);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            batch(event.partialTicks, false, frustum, cameraX, cameraY, cameraZ);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            batch(event.partialTicks, true, frustum, cameraX, cameraY, cameraZ);
        } finally {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** Stochastic rounding retains density above 100% rather than capping a probability. */
    public static int emissionCount(double expected, double random) {
        int whole = (int) Math.floor(expected);
        return whole + (random < expected - whole ? 1 : 0);
    }

    public static double particleSizeMultiplier(int percent) {
        return 1.5D * percent / 100D;
    }

    public static boolean withinRenderDistance(double x, double y, double z, double cameraX, double cameraY,
        double cameraZ, int distance) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz <= (double) distance * distance;
    }

    private void batch(float partialTicks, boolean glow, Frustrum frustum, double cameraX, double cameraY,
        double cameraZ) {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        for (Particle particle : particles) {
            int rgb = QuantumNetworkColor.rgb(
                particle.node ? ((TileEntityNetworkQuantumNode) particle.source).getColorIndex()
                    : QuantumIncorporationClientState.colorIndex(
                        currentWorld,
                        particle.source.xCoord,
                        particle.source.yCoord,
                        particle.source.zCoord));
            float red = ((rgb >> 16) & 255) / 255F;
            float green = ((rgb >> 8) & 255) / 255F;
            float blue = (rgb & 255) / 255F;
            double progress = (particle.age + partialTicks) / particle.lifetime;
            float alpha = (float) (Math.min(1, (particle.age + partialTicks + 1) / 3) * (1 - progress));
            double radius = particle.radius * particleSizeMultiplier(Config.quantumParticleSizePercent)
                * (glow ? 1.65D : 1D);
            double x = particle.previousX + (particle.x - particle.previousX) * partialTicks;
            double y = particle.previousY + (particle.y - particle.previousY) * partialTicks;
            double z = particle.previousZ + (particle.z - particle.previousZ) * partialTicks;
            if (!withinRenderDistance(x, y, z, cameraX, cameraY, cameraZ, Config.quantumRenderDistance)
                || !particle.valid(currentWorld)
                || !frustum.isBoundingBoxInFrustum(
                    AxisAlignedBB
                        .getBoundingBox(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius)))
                continue;
            for (int side = 0; side < CUBE_FACES.length; side++) {
                float shade = FACE_SHADE[side];
                tessellator.setColorRGBA_F(red * shade, green * shade, blue * shade, alpha * (glow ? .14F : .65F));
                for (int corner : CUBE_FACES[side]) {
                    tessellator.addVertex(
                        x + ((corner & 1) == 0 ? -radius : radius),
                        y + ((corner & 4) == 0 ? -radius : radius),
                        z + ((corner & 2) == 0 ? -radius : radius));
                }
            }
        }
        tessellator.draw();
    }

    private static final class Particle {

        private final TileEntity source;
        private final boolean node;
        private final int side;
        private final int mask;
        private final int lifetime;
        private final double vx, vy, vz, radius;
        private double previousX, previousY, previousZ, x, y, z;
        private int age;

        private Particle(World world, TileEntity source, Direction direction, boolean node) {
            this.source = source;
            this.node = node;
            side = direction.side;
            mask = node ? ((TileEntityNetworkQuantumNode) source).getConnectedSidesMask() & 63 : 0;
            lifetime = 16 + world.rand.nextInt(13);
            radius = .018D + world.rand.nextDouble() * .022D;
            double[] vector = QuantumParticleDirections.sample(direction, world.rand);
            double speed = .018D + world.rand.nextDouble() * .025D;
            vx = vector[0] * speed;
            vy = vector[1] * speed;
            vz = vector[2] * speed;
            // Randomize node origin and face positions independently so neighboring emitters do not form rows.
            x = source.xCoord + .5D;
            y = source.yCoord + .5D;
            z = source.zCoord + .5D;
            if (node) {
                x += (world.rand.nextDouble() - .5D) * .18D;
                y += (world.rand.nextDouble() - .5D) * .18D;
                z += (world.rand.nextDouble() - .5D) * .18D;
            } else {
                x += direction.x == 0 ? (world.rand.nextDouble() - .5D) * .86D : direction.x * .515D;
                y += direction.y == 0 ? (world.rand.nextDouble() - .5D) * .86D : direction.y * .515D;
                z += direction.z == 0 ? (world.rand.nextDouble() - .5D) * .86D : direction.z * .515D;
            }
            previousX = x;
            previousY = y;
            previousZ = z;
        }

        private boolean valid(World world) {
            if (!present(world, source)) return false;
            if (node) {
                TileEntityNetworkQuantumNode quantum = (TileEntityNetworkQuantumNode) source;
                int currentMask = quantum.getConnectedSidesMask() & 63;
                return quantum.isLinkedClient()
                    && (side < 0 ? currentMask == 63 : mask != 63 && (currentMask & (1 << side)) == 0);
            }
            return QuantumIncorporationClientState.registered(world, source.xCoord, source.yCoord, source.zCoord)
                && airFace(world, source, side);
        }
    }
}
