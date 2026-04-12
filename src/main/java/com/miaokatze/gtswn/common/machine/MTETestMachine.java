package com.miaokatze.gtswn.common.machine;

import static gregtech.api.enums.GTValues.V;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.miaokatze.gtswn.register.TextureManager;

import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicGenerator;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.render.TextureFactory;

/**
 * 测试用元机器实体 (MTE)
 * 继承自 MTEBasicGenerator，实现了一个类似太阳能发电机的逻辑。
 * 该机器不依赖配方表，而是根据游戏内的时间、天气和光照强度自动产生电力。
 */
public class MTETestMachine extends MTEBasicGenerator {

    /**
     * 构造函数：用于直接通过 ID 注册
     */
    @SuppressWarnings("unused")
    public MTETestMachine(int aID, String aName, int aTier) {
        super(aID, aName, aName, aTier, new String[] { "A simple test solar-like generator." });
    }

    /**
     * 构造函数：允许传入自定义的区域化名称（用于本地化显示）
     */
    public MTETestMachine(int aID, String aName, String aNameRegional, int aTier) {
        super(aID, aName, aNameRegional, aTier, new String[] { "A simple test solar-like generator." });
    }

    /**
     * 拷贝/工厂构造函数：用于在 TileEntity 加载时创建新的实例
     */
    public MTETestMachine(String aName, int aTier, String[] aDescription,
        gregtech.api.interfaces.ITexture[][][] aTextures) {
        super(aName, aTier, aDescription, aTextures);
    }

    /**
     * 创建一个新的元机器实体实例
     */
    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity tileEntity) {
        return new MTETestMachine(mName, mTier, mDescriptionArray, mTextures);
    }

    /**
     * 获取配方映射表
     * 由于本发电机不依赖任何配方（类似太阳能板），因此返回 null
     */
    @Override
    public RecipeMap<?> getRecipeMap() {
        return null;
    }

    /**
     * 获取效率值
     * 对此类发电机不适用，但必须提供返回值，默认为 100%
     */
    @Override
    public int getEfficiency() {
        return 100;
    }

    /**
     * 首次 Tick 时的回调
     */
    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);
    }

    /**
     * 每个 Tick 结束后的回调，用于处理发电逻辑
     */
    @Override
    public void onPostTick(IGregTechTileEntity tmte, long aTick) {
        super.onPostTick(tmte, aTick);

        if (!tmte.isServerSide()) return;

        // 每 20 ticks (约 1 秒) 更新一次发电状态
        if (aTick % 20L != 0L) return;

        World world = tmte.getWorld();
        int x = tmte.getXCoord();
        int y = tmte.getYCoord();
        int z = tmte.getZCoord();

        boolean isDay = world.isDaytime();
        boolean canSeeSky = world.canBlockSeeTheSky(x, y + 1, z);

        // 如果不是白天或无法看到天空，则停止工作
        if (!isDay || !canSeeSky) {
            tmte.setActive(false);
            return;
        }

        float weatherFactor = 1.0f;
        if (world.isRaining() || world.isThundering()) weatherFactor = 0.5f;

        long producedEU = calculateSolarEU(world, weatherFactor);

        // 增加内部存储的电力
        if (producedEU > 0) {
            tmte.increaseStoredEnergyUnits(producedEU, true);
        }

        tmte.setActive(true);
    }

    /**
     * 右键点击回调
     * 此简单发电机不提供 GUI 界面，返回 true 表示已处理点击事件
     */
    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        return true;
    }

    /**
     * 计算太阳能发电量
     *
     * @param world         世界对象
     * @param weatherFactor 天气系数（晴天 1.0，雨天 0.5）
     * @return 本次计算产生的 EU 总量
     */
    protected long calculateSolarEU(World world, float weatherFactor) {
        // 根据等级获取基础电压和电流
        long baseVoltage = V[mTier];
        long amperes = 1L; // 默认 1A
        long euPerTick = baseVoltage * amperes;

        // 尝试获取当前的太阳亮度系数
        float sunBr = 1.0f;
        try {
            sunBr = world.getSunBrightness(1.0F);
        } catch (Throwable t) {
            // 忽略 API 不匹配导致的异常
        }

        long eu = (long) (euPerTick * weatherFactor * sunBr);
        // 由于是每 20 ticks 结算一次，所以结果乘以 20
        return eu * 20L;
    }

    /**
     * 最大输出电压
     */
    @Override
    public long maxEUOutput() {
        return V[mTier];
    }

    /**
     * 最大输出电流
     */
    @Override
    public long maxAmperesOut() {
        return 1L;
    }

    /**
     * 保存 NBT 数据
     */
    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        super.saveNBTData(aNBT);
    }

    /**
     * 加载 NBT 数据
     */
    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        super.loadNBTData(aNBT);
    }

    /**
     * 获取污染排放量
     */
    @Override
    public int getPollution() {
        return 0;
    }

    // region 纹理相关 - 使用 GregTech 的太阳能板图标集
    @Override
    public ITexture[] getFront(byte aColor) {
        return new ITexture[] { super.getFront(aColor)[0] };
    }

    @Override
    public ITexture[] getBack(byte aColor) {
        return new ITexture[] { super.getBack(aColor)[0] };
    }

    @Override
    public ITexture[] getTop(byte aColor) {
        // 顶部 = 机器外壳基底 + 太阳能板覆盖层
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

    /**
     * 根据机器等级获取对应的太阳能板图标
     */
    private gregtech.api.interfaces.IIconContainer getSolarIconByTier() {
        if (mTier == 4) return TextureManager.TEX_TEST_EV;
        if (mTier == 5) return TextureManager.TEX_TEST_IV;
        if (mTier == 6) return TextureManager.TEX_TEST_LUV;
        return TextureManager.TEX_TEST_EV;
    }
    // endregion
}
