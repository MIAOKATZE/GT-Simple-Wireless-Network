package com.miaokatze.gtswn.common.device;

import static org.junit.Assert.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.renderer.RenderBlocks;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.Test;

import com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchDynamo;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.covers.Cover;

public class DeviceMachineIdentityTest {

    /** A server-loadable MTE whose unrelated rendering signature is unavailable on its defining loader. */
    public static class IdentityMulti extends MTEMultiBlockBase {

        public IdentityMulti() {
            super("identity_test");
        }

        public void unrelatedRendering(RenderBlocks renderer) {}

        @Override
        public String[] getDescription() {
            return new String[0];
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new IdentityMulti();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection facing, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }

        @Override
        public List<MTEHatch> getExoticAndNormalEnergyHatchList() {
            List<MTEHatch> result = new ArrayList<>(mExoticEnergyHatches);
            result.addAll(mEnergyHatches);
            return result;
        }
    }

    private MTEMultiBlockBase serverController() throws Exception {
        String name = IdentityMulti.class.getName();
        String resource = name.replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream stream = getClass().getClassLoader()
            .getResourceAsStream(resource)) {
            bytes = com.google.common.io.ByteStreams.toByteArray(stream);
        }
        ClassLoader serverLoader = new ClassLoader(getClass().getClassLoader()) {

            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (requested.equals("net.minecraft.client.renderer.RenderBlocks"))
                    throw new ClassNotFoundException("Client rendering is absent on the test server");
                if (requested.equals(name)) {
                    Class<?> defined = findLoadedClass(requested);
                    if (defined == null) defined = defineClass(requested, bytes, 0, bytes.length);
                    if (resolve) resolveClass(defined);
                    return defined;
                }
                return super.loadClass(requested, resolve);
            }
        };
        return (MTEMultiBlockBase) serverLoader.loadClass(name)
            .getConstructor()
            .newInstance();
    }

    private static class IdentityTile extends BaseMetaTileEntity {

        IdentityTile(IMetaTileEntity machine, Cover cover) {
            setMetaTileEntity(machine);
            covers[ForgeDirection.UP.ordinal()] = cover;
        }
    }

    private MTEHatchEnergy energy() {
        MTEHatchEnergy hatch = new MTEHatchEnergy("energy", 1, new String[0], null);
        hatch.setBaseMetaTileEntity(
            new IdentityTile(hatch, new GTswn_Cover_EnergyWireless(new CoverContext(null, ForgeDirection.UP, null))));
        return hatch;
    }

    private MTEHatchDynamo dynamo() {
        MTEHatchDynamo hatch = new MTEHatchDynamo("dynamo", 1, new String[0], null);
        hatch.setBaseMetaTileEntity(
            new IdentityTile(hatch, new GTswn_Cover_DynamoWireless(new CoverContext(null, ForgeDirection.UP, null))));
        return hatch;
    }

    @Test
    public void renderingSignatureCannotBreakNormalOrExoticIdentity() throws Exception {
        MTEMultiBlockBase multi = serverController();
        assertEquals(0, DeviceMachineIdentity.linkNodeType(multi));
        multi.mEnergyHatches.add(energy());
        assertEquals(1, DeviceMachineIdentity.linkNodeType(multi));
        multi.mDynamoHatches.add(dynamo());
        assertEquals(3, DeviceMachineIdentity.linkNodeType(multi));
        multi.mEnergyHatches.clear();
        multi.mDynamoHatches.clear();
        multi.getExoticEnergyHatches()
            .add(energy());
        assertEquals(1, DeviceMachineIdentity.linkNodeType(multi));
        multi.getExoticDynamoHatches()
            .add(dynamo());
        assertEquals(3, DeviceMachineIdentity.linkNodeType(multi));
    }

    @Test
    public void concreteMethodInspectionReproducesTheMissingClientClassTrigger() throws Exception {
        MTEMultiBlockBase multi = serverController();
        try {
            multi.getClass()
                .getMethod("getExoticAndNormalEnergyHatchList");
            fail("Concrete-class method reflection must resolve its absent rendering signature");
        } catch (NoClassDefFoundError expected) {
            assertTrue(
                expected.getMessage()
                    .contains("RenderBlocks"));
        }
        assertEquals(0, DeviceMachineIdentity.linkNodeType(multi));
    }
}
