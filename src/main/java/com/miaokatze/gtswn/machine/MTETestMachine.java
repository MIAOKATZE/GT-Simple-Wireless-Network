package com.miaokatze.gtswn.machine;

import static gregtech.api.enums.GTValues.V;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.render.TextureFactory;

/**
 * MTETestMachine - 一个用于测试的太阳能等效发电机（示例实现）
 */
public class MTETestMachine extends MTEBasicGenerator {

    // 构造（用于直接按 ID 注册）
    @SuppressWarnings("unused")
    public MTETestMachine(int aID, String aName, int aTier) {
        super(aID, aName, aName, aTier, new String[] { "A simple test solar-like generator." });
    }

    // 构造：允许传入区域化显示名（regional name）
    public MTETestMachine(int aID, String aName, String aNameRegional, int aTier) {
        super(aID, aName, aNameRegional, aTier, new String[] { "A simple test solar-like generator." });
    }

    // 复制/工厂构造
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

        // 每 20 tick（大约 1 秒）更新一次
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

        // 由于 MTEBasicGenerator 的 onPostTick 可能每 10 tick 调用一次，
        // 这里按每 20 tick 增加 producedEU。
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
        // 基于 tier 的电压与安培数输出。
        long baseVoltage = V[mTier];
        long amperes = 1L; // 默认 1A，可做配置
        long euPerTick = baseVoltage * amperes;

        // 如果希望按光照强度平滑产电，可使用 world.getSunBrightness(1.0F)
        float sunBr = 1.0f;
        try {
            sunBr = world.getSunBrightness(1.0F);
        } catch (Throwable t) {
            // ignore on API mismatch
        }

        long eu = (long) (euPerTick * weatherFactor * sunBr);
        // 这里返回每 20 tick 应增加的 EU，故乘以 20
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

    private Textures.BlockIcons getSolarIconByTier() {
        // Map mTier to a suitable SOLARPANEL_* icon. Use a simple if/else chain to avoid
        // static analysis warnings about switch expressions while keeping broad compatibility.
        if (mTier == 1) return Textures.BlockIcons.SOLARPANEL_8V;
        if (mTier == 2) return Textures.BlockIcons.SOLARPANEL_LV;
        if (mTier == 3) return Textures.BlockIcons.SOLARPANEL_MV;
        if (mTier == 4) return Textures.BlockIcons.SOLARPANEL_HV;
        if (mTier == 5) return Textures.BlockIcons.SOLARPANEL_EV;
        if (mTier == 6) return Textures.BlockIcons.SOLARPANEL_IV;
        if (mTier == 7) return Textures.BlockIcons.SOLARPANEL_LuV;
        if (mTier == 8) return Textures.BlockIcons.SOLARPANEL_ZPM;
        if (mTier == 9) return Textures.BlockIcons.SOLARPANEL_UV;
        if (mTier == 10) return Textures.BlockIcons.SOLARPANEL_UHV;
        if (mTier == 11) return Textures.BlockIcons.SOLARPANEL_UEV;
        if (mTier == 12) return Textures.BlockIcons.SOLARPANEL_UIV;
        return Textures.BlockIcons.SOLARPANEL;
    }
    // endregion
}
