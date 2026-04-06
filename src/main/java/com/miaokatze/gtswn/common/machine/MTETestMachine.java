package com.miaokatze.gtswn.common.machine;

import static gregtech.api.enums.GTValues.V;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.render.TextureFactory;

public class MTETestMachine extends MTEBasicGenerator {

    public static final gregtech.api.interfaces.IIconContainer TEX_EV = new gregtech.api.enums.Textures.BlockIcons.CustomIcon(
        "gtswn:MTETEST_1");
    public static final gregtech.api.interfaces.IIconContainer TEX_IV = new gregtech.api.enums.Textures.BlockIcons.CustomIcon(
        "gtswn:MTETEST_2");
    public static final gregtech.api.interfaces.IIconContainer TEX_LuV = new gregtech.api.enums.Textures.BlockIcons.CustomIcon(
        "gtswn:MTETEST_3");

    // Constructor (for direct ID registration)
    @SuppressWarnings("unused")
    public MTETestMachine(int aID, String aName, int aTier) {
        super(aID, aName, aName, aTier, new String[] { "A simple test solar-like generator." });
    }

    // Constructor: allows passing regional name
    public MTETestMachine(int aID, String aName, String aNameRegional, int aTier) {
        super(aID, aName, aNameRegional, aTier, new String[] { "A simple test solar-like generator." });
    }

    // Copy/factory constructor
    public MTETestMachine(String aName, int aTier, String[] aDescription,
        gregtech.api.interfaces.ITexture[][][] aTextures) {
        super(aName, aTier, aDescription, aTextures);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tileEntity) {
        return new MTETestMachine(mName, mTier, mDescriptionArray, mTextures);
    }

    @Override
    public RecipeMap<?> getRecipeMap() {
        // This generator does not use any RecipeMap (it generates energy like a solar generator),
        // so return null to indicate no recipe backend.
        return null;
    }

    @Override
    public int getEfficiency() {
        // Not used for this generator, but must be provided. Use 100% efficiency as default.
        return 100;
    }

    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
    }

    @Override
    public void onPostTick(IGregTechTileEntity tmte, long aTick) {
        super.onPostTick(tmte, aTick);

        if (!tmte.isServerSide()) return;

        // Update every 20 ticks (approximately 1 second)
        if (aTick % 20L != 0L) return;

        World world = tmte.getWorld();
        int x = tmte.getXCoord();
        int y = tmte.getYCoord();
        int z = tmte.getZCoord();

        boolean isDay = world.isDaytime();
        boolean canSeeSky = world.canBlockSeeTheSky(x, y + 1, z);

        if (!isDay || !canSeeSky) {
            tmte.setActive(false);
            return;
        }

        float weatherFactor = 1.0f;
        if (world.isRaining() || world.isThundering()) weatherFactor = 0.5f;

        long producedEU = calculateSolarEU(world, weatherFactor);

        // Since MTEBasicGenerator's onPostTick may be called every 10 ticks,
        // here we add producedEU per 20 ticks
        if (producedEU > 0) {
            tmte.increaseStoredEnergyUnits(producedEU, true);
        }

        tmte.setActive(true);
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        // Do not open a container GUI for this simple solar-like generator.
        // Returning true signals the click was handled.
        return true;
    }

    protected long calculateSolarEU(World world, float weatherFactor) {
        // Voltage and amperage output based on tier
        long baseVoltage = V[mTier];
        long amperes = 1L; // Default 1A, can be configured
        long euPerTick = baseVoltage * amperes;

        // If you want smooth power generation based on light intensity, you can use world.getSunBrightness(1.0F)
        float sunBr = 1.0f;
        try {
            sunBr = world.getSunBrightness(1.0F);
        } catch (Throwable t) {
            // ignore on API mismatch
        }

        long eu = (long) (euPerTick * weatherFactor * sunBr);
        // Return EU to be added per 20 ticks, so multiply by 20
        return eu * 20L;
    }

    @Override
    public long maxEUOutput() {
        return V[mTier];
    }

    @Override
    public long maxAmperesOut() {
        return 1L;
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
    }

    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
    }

    @Override
    public int getPollution() {
        return 0;
    }

    // region Texture - use GregTech's solar panel iconset
    @Override
    public ITexture[] getFront(byte aColor) {
        // Use default machine casing for front
        return new ITexture[] { super.getFront(aColor)[0] };
    }

    @Override
    public ITexture[] getBack(byte aColor) {
        return new ITexture[] { super.getBack(aColor)[0] };
    }

    @Override
    public ITexture[] getTop(byte aColor) {
        // Top = machine casing base + solar overlay
        return new ITexture[] { super.getTop(aColor)[0], TextureFactory.of(getSolarIconByTier()) };
    }

    @Override
    public ITexture[] getBottom(byte aColor) {
        return new ITexture[] { super.getBottom(aColor)[0] };
    }

    @Override
    public ITexture[] getSides(byte aColor) {
        return new ITexture[] { super.getSides(aColor)[0] };
    }

    @Override
    public ITexture[] getFrontActive(byte aColor) {
        return getFront(aColor);
    }

    @Override
    public ITexture[] getBackActive(byte aColor) {
        return getBack(aColor);
    }

    @Override
    public ITexture[] getTopActive(byte aColor) {
        return getTop(aColor);
    }

    @Override
    public ITexture[] getSidesActive(byte aColor) {
        return getSides(aColor);
    }

    private gregtech.api.interfaces.IIconContainer getSolarIconByTier() {
        if (mTier == 4) return TEX_EV;
        if (mTier == 5) return TEX_IV;
        if (mTier == 6) return TEX_LuV;
        return TEX_EV;
    }
    // endregion
}
