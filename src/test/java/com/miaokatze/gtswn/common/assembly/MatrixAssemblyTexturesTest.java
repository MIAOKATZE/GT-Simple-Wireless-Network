package com.miaokatze.gtswn.common.assembly;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.util.IIcon;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.util.client.ResourceUtils;
import gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer;
import gregtech.common.render.GTRenderedTexture;

public class MatrixAssemblyTexturesTest {

    @Test
    public void bothMembersParticipateInTheClientIconRegistrationLifecycle() throws Exception {
        for (Class<?> type : new Class<?>[] { MatrixItemInputBus.class, MatrixFluidInputHatch.class }) {
            assertEquals(
                Side.CLIENT,
                type.getDeclaredMethod("registerIcons", IIconRegister.class)
                    .getAnnotation(SideOnly.class)
                    .value());
        }
    }

    @Test
    public void bridgesAreRegisteredBeforeTheFirstFrontLookupAndReusedOnAtlasReload() throws Exception {
        MTEHatch[] members = { member(MatrixItemInputBus.class), member(MatrixFluidInputHatch.class) };
        RecordingRegister firstAtlas = new RecordingRegister();
        // Follow BlockMachines.registerBlockIcons: all MTE hooks run before the GT icon queue.
        for (MTEHatch member : members) member.registerIcons(firstAtlas);
        Field iconName = GTCustomBlockIconContainer.class.getDeclaredField("mIconName");
        iconName.setAccessible(true);
        Map<String, IIconContainer> bridges = new LinkedHashMap<>();
        for (Runnable task : GregTechAPI.sGTBlockIconload) {
            if (task instanceof GTCustomBlockIconContainer && (((String) iconName.get(task)).startsWith("gtswn:matrix/")
                || "gtswn:matrix_input_center".equals(iconName.get(task)))) {
                assertNull(
                    "duplicate bridge registration",
                    bridges.put((String) iconName.get(task), (IIconContainer) task));
            }
        }
        assertEquals("MTE hooks must queue the center and every bridge before rendering", 17, bridges.size());
        int queueSize = GregTechAPI.sGTBlockIconload.size();

        Field singleton = Minecraft.class.getDeclaredField("theMinecraft");
        singleton.setAccessible(true);
        Object previousMinecraft = singleton.get(null);
        IIconRegister previousRegister = GregTechAPI.sBlockIcons;
        Field icon = GTCustomBlockIconContainer.class.getDeclaredField("mIcon");
        icon.setAccessible(true);
        Map<IIconContainer, Object> previousIcons = new LinkedHashMap<>();
        for (IIconContainer bridge : bridges.values()) previousIcons.put(bridge, icon.get(bridge));
        try {
            Minecraft minecraft = allocate(Minecraft.class);
            Field resources = Minecraft.class.getDeclaredField("mcResourceManager");
            resources.setAccessible(true);
            // Plain JUnit does not apply Forge's access transformers to SimpleReloadableResourceManager.
            resources.set(
                minecraft,
                Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] { IReloadableResourceManager.class },
                    (proxy, method, args) -> {
                        // The bridges have no optional _OVERLAY resource.
                        if (method.getName()
                            .equals("getResource")) throw new FileNotFoundException(args[0].toString());
                        throw new AssertionError(method);
                    }));
            singleton.set(null, minecraft);
            ResourceUtils.clearCache();

            load(bridges, firstAtlas);
            ITexture[] first = members[0].getTexturesActive(null);
            assertSame(firstAtlas.icons.get("gtswn:matrix/bridge_0"), frontIcon(first));
            for (MTEHatch member : members) {
                ((MatrixAssemblyMember) member).matrixPort()
                    .readConnectionMask(63);
                ITexture[] active = member.getTexturesActive(null);
                ITexture[] inactive = member.getTexturesInactive(null);
                assertSame(firstAtlas.icons.get("gtswn:matrix/bridge_15"), frontIcon(active));
                assertSame(active[1], inactive[1]);
                assertSame(first[2], active[2]);
                assertSame(first[3], active[3]);
                member.registerIcons(firstAtlas);
            }
            assertEquals(
                "rendering and repeated MTE hooks must not queue more icons",
                queueSize,
                GregTechAPI.sGTBlockIconload.size());

            RecordingRegister reloadedAtlas = new RecordingRegister();
            load(bridges, reloadedAtlas);
            ITexture[] reloaded = members[0].getTexturesActive(null);
            assertSame(first[2], reloaded[2]);
            assertSame(reloadedAtlas.icons.get("gtswn:matrix/bridge_15"), frontIcon(reloaded));
            assertNotSame(firstAtlas.icons.get("gtswn:matrix/bridge_15"), frontIcon(reloaded));
            assertEquals(queueSize, GregTechAPI.sGTBlockIconload.size());
        } finally {
            for (Map.Entry<IIconContainer, Object> entry : previousIcons.entrySet())
                icon.set(entry.getKey(), entry.getValue());
            GregTechAPI.setBlockIconRegister(previousRegister);
            singleton.set(null, previousMinecraft);
            ResourceUtils.clearCache();
        }
    }

    private static IIcon frontIcon(ITexture[] layers) throws Exception {
        // Inspect the GT wrapper without starting Forge's optional CTM integration in plain JUnit.
        Field container = GTRenderedTexture.class.getDeclaredField("mIconContainer");
        container.setAccessible(true);
        return ((IIconContainer) container.get(layers[1])).getIcon();
    }

    private static void load(Map<String, IIconContainer> bridges, RecordingRegister register) {
        GregTechAPI.setBlockIconRegister(register);
        for (Map.Entry<String, IIconContainer> entry : bridges.entrySet()) {
            IIconContainer bridge = entry.getValue();
            assertEquals(TextureMap.locationBlocksTexture, bridge.getTextureFile());
            ((Runnable) bridge).run();
            assertSame(register.icons.get(entry.getKey()), bridge.getIcon());
        }
        assertEquals(17, register.icons.size());
    }

    private static <T extends MTEHatch & MatrixAssemblyMember> T member(Class<T> type) throws Exception {
        T member = allocate(type);
        Field port = type.getDeclaredField("matrix");
        port.setAccessible(true);
        port.set(member, new MatrixAssemblyPort(member));
        BaseMetaTileEntity tile = allocate(BaseMetaTileEntity.class);
        Field facing = BaseMetaTileEntity.class.getDeclaredField("mFacing");
        facing.setAccessible(true);
        facing.set(tile, ForgeDirection.NORTH);
        member.setBaseMetaTileEntity(tile);
        return member;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) singleton.get(null)).allocateInstance(type));
    }

    private static final class RecordingRegister implements IIconRegister {

        final Map<String, IIcon> icons = new LinkedHashMap<>();

        @Override
        public IIcon registerIcon(String name) {
            String path = "/assets/" + name.replace(":", "/textures/blocks/") + ".png";
            try (InputStream input = MatrixAssemblyTexturesTest.class.getResourceAsStream(path)) {
                assertNotNull("block atlas resource must exist: " + path, input);
                BufferedImage image = ImageIO.read(input);
                assertEquals(16, image.getWidth());
                assertEquals(16 * 16, image.getHeight());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            TextureAtlasSprite sprite = new TextureAtlasSprite(name) {};
            assertNull("each sprite is registered once per atlas", icons.put(name, sprite));
            return sprite;
        }
    }
}
