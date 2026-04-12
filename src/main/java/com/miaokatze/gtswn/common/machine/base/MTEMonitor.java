package com.miaokatze.gtswn.common.machine.base;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.screen.UIBuildContext;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.modularui.IAddUIWidgets;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;

/**
 * 监视器类机器基类
 * <p>
 * 专为信息显示和红石控制类机器设计，提供：
 * <ul>
 * <li>拥有者管理（自动绑定玩家 UUID）</li>
 * <li>显示模式切换（常规/科学计数）</li>
 * <li>红石信号输出支持</li>
 * <li>ModularUI 界面框架</li>
 * </ul>
 * <p>
 * 不包含：燃料系统、配方处理、流体储罐、物品加工等无关功能。
 * <p>
 * 使用示例：
 * 
 * <pre>
 * 
 * {
 *     &#64;code
 *     public class MTEWirelessEnergyMonitor extends MTEMonitor {
 * 
 *         public MTEWirelessEnergyMonitor(int aID, String aName, String aNameRegional) {
 *             super(aID, aName, aNameRegional, new String[] { "描述" });
 *         }
 * 
 *         &#64;Override
 *         public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
 *             return new MTEWirelessEnergyMonitor(mName, mDescriptionArray);
 *         }
 * 
 *         &#64;Override
 *         public boolean allowGeneralRedstoneOutput() {
 *             return true; // 启用红石输出
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class MTEMonitor extends MetaTileEntity implements IAddUIWidgets {

    // === 核心字段 ===

    /**
     * 机器拥有者的 UUID
     * <p>
     * 在 {@link #onFirstTick(IGregTechTileEntity)} 中自动初始化
     */
    protected UUID ownerUUID;

    /**
     * 显示模式
     * <ul>
     * <li>0 = 常规计数（带逗号分隔，如 1,234,567）</li>
     * <li>1 = 科学计数法（如 1.235×10^6）</li>
     * </ul>
     */
    protected int displayMode = 0;

    /**
     * 机器显示名称（用于 Tooltip）
     */
    protected final String[] mDescriptionArray;

    // === 构造函数 ===

    /**
     * ID 构造函数（注册时使用）
     *
     * @param aID           机器 ID
     * @param aName         机器内部名称（用于注册和 NBT）
     * @param aNameRegional 机器显示名称（本地化键或硬编码名称）
     * @param aDescription  机器描述数组（Tooltip 显示）
     */
    public MTEMonitor(int aID, String aName, String aNameRegional, String[] aDescription) {
        super(aID, aName, aNameRegional, 0); // 0 个物品槽
        this.mDescriptionArray = aDescription != null ? aDescription : new String[0];
    }

    /**
     * 拷贝构造函数（{@link #newMetaEntity(IGregTechTileEntity)} 时使用）
     *
     * @param aName        机器内部名称
     * @param aDescription 机器描述数组
     */
    public MTEMonitor(String aName, String[] aDescription) {
        super(aName, 0); // 0 个物品槽
        this.mDescriptionArray = aDescription != null ? aDescription : new String[0];
    }

    // === 必须实现的抽象方法 ===

    /**
     * 创建新的机器实例（用于方块放置和克隆）
     * <p>
     * GT 框架会在以下场景调用此方法：
     * <ul>
     * <li>玩家放置机器方块时</li>
     * <li>机器被活塞推动时</li>
     * <li>机器被复制时（如创造模式拿取）</li>
     * </ul>
     *
     * @param aTileEntity 关联的 TileEntity
     * @return 新的机器实例
     */
    @Override
    public abstract IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity);

    // === 默认禁用系统（final 防止子类误用）===

    /**
     * 污染值（监视器不产生污染）
     *
     * @return 始终返回 0
     */
    public final int getPollution() {
        return 0;
    }

    /**
     * 是否允许电力输出（监视器不输出电力）
     *
     * @return 始终返回 false
     */
    public final boolean isEnetOutput() {
        return false;
    }

    /**
     * 是否允许电力输入（监视器不输入电力）
     *
     * @return 始终返回 false
     */
    public final boolean isEnetInput() {
        return false;
    }

    /**
     * 最大储能（监视器无内部储能）
     *
     * @return 始终返回 0
     */
    public final long maxEUStore() {
        return 0;
    }

    /**
     * 最大电力输入（监视器不接受电力输入）
     *
     * @return 始终返回 0
     */
    public final long maxEUInput() {
        return 0;
    }

    /**
     * 最大电力输出（监视器不输出电力）
     *
     * @return 始终返回 0
     */
    public final long maxEUOutput() {
        return 0;
    }

    // === 红石控制（可选覆盖）===

    /**
     * 是否允许通用红石输出
     * <p>
     * 子类如需输出红石信号，必须重写此方法并返回 {@code true}
     *
     * @return 默认返回 false，子类按需启用
     */
    public boolean allowGeneralRedstoneOutput() {
        return false;
    }

    /**
     * 获取通用红石信号强度
     * <p>
     * 仅在 {@link #allowGeneralRedstoneOutput()} 返回 true 时生效
     *
     * @param side 查询的方向
     * @return 信号强度（0-15），默认返回 0
     */
    public byte getGeneralRS(ForgeDirection side) {
        return 0;
    }

    /**
     * 获取机器描述（Tooltip）
     * <p>
     * 返回构造函数中传入的描述数组
     *
     * @return 描述数组
     */
    @Override
    public String[] getDescription() {
        return mDescriptionArray;
    }

    // === GUI 系统（可选覆盖）===

    /**
     * 右键点击机器时打开 GUI
     * <p>
     * 这是监视器类机器的核心交互方式
     *
     * @param aBaseMetaTileEntity 关联的 BaseMetaTileEntity
     * @param aPlayer             点击的玩家
     * @return 始终返回 true（表示已处理点击）
     */
    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        if (aBaseMetaTileEntity.isClientSide()) {
            return true;
        }
        // 打开 GUI
        openGui(aPlayer);
        return true;
    }

    /**
     * 添加 UI 组件到 ModularUI 窗口
     * <p>
     * 子类可在此方法中添加文本、按钮、进度条等 Widget
     *
     * @param builder      UI 构建器
     * @param buildContext UI 构建上下文
     */
    public void addUIWidgets(ModularWindow.Builder builder, UIBuildContext buildContext) {
        // 默认空实现，子类按需添加 Widget
    }

    // === 生命周期钩子 ===

    /**
     * 首次 Tick 回调
     * <p>
     * 在此方法中自动初始化拥有者 UUID
     *
     * @param aBaseMetaTileEntity 关联的 BaseMetaTileEntity
     */
    @Override
    public void onFirstTick(IGregTechTileEntity aBaseMetaTileEntity) {
        super.onFirstTick(aBaseMetaTileEntity);

        // 仅在服务端初始化 ownerUUID
        if (ownerUUID == null && aBaseMetaTileEntity.isServerSide()) {
            ownerUUID = aBaseMetaTileEntity.getOwnerUuid();
        }
    }

    // === NBT 数据持久化 ===

    /**
     * 保存 NBT 数据
     * <p>
     * 自动保存：
     * <ul>
     * <li>ownerUUID - 拥有者 UUID</li>
     * <li>displayMode - 显示模式</li>
     * </ul>
     * 子类如需保存额外数据，应调用 {@code super.saveNBTData(aNBT)} 后继续保存
     *
     * @param aNBT NBT 标签复合体
     */
    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        // 保存拥有者 UUID
        if (ownerUUID != null) {
            aNBT.setString("ownerUUID", ownerUUID.toString());
        }

        // 保存显示模式
        aNBT.setInteger("displayMode", displayMode);
    }

    /**
     * 加载 NBT 数据
     * <p>
     * 自动恢复：
     * <ul>
     * <li>ownerUUID - 拥有者 UUID</li>
     * <li>displayMode - 显示模式</li>
     * </ul>
     * 子类如需加载额外数据，应调用 {@code super.loadNBTData(aNBT)} 后继续加载
     *
     * @param aNBT NBT 标签复合体
     */
    @Override
    public void loadNBTData(NBTTagCompound aNBT) {
        // 加载拥有者 UUID
        if (aNBT.hasKey("ownerUUID")) {
            try {
                ownerUUID = UUID.fromString(aNBT.getString("ownerUUID"));
            } catch (Exception e) {
                ownerUUID = null;
            }
        }

        // 加载显示模式
        displayMode = aNBT.getInteger("displayMode");
    }

    /**
     * 初始化默认模式（包括机器朝向）
     * <p>
     * 在机器首次放置时调用
     * 对于监视器类机器，不需要特殊初始化，GT 框架会自动处理朝向
     *
     * @param aNBT NBT 标签复合体
     */
    @Override
    public void initDefaultModes(NBTTagCompound aNBT) {
        // 监视器不需要特殊初始化，GT 框架会根据玩家朝向自动设置方块朝向
        // 这里保持空实现即可
    }

    /**
     * 验证朝向是否合法
     * <p>
     * 允许所有 6 个方向的朝向（包括上下）
     *
     * @param facing 待验证的朝向
     * @return 始终返回 true（允许所有朝向）
     */
    @Override
    public boolean isFacingValid(ForgeDirection facing) {
        return facing != null && facing != ForgeDirection.UNKNOWN;
    }

    /**
     * 获取 TileEntity 基础类型
     * <p>
     * GT 框架用于区分不同类型的 TileEntity
     *
     * @return 始终返回 0（标准机器类型）
     */
    @Override
    public byte getTileEntityBaseType() {
        return 0;
    }

    // === 工具方法 ===

    /**
     * 翻译文本（实例方法，可在非构造函数中使用）
     * <p>
     * 使用示例：
     * 
     * <pre>
     * 
     * {
     *     &#64;code
     *     String text = translate("gtswn.ui.energy", "1,234 EU");
     *     // 返回格式化后的翻译文本
     * }
     * </pre>
     *
     * @param key  翻译键
     * @param args 占位符参数（使用 %s, %d 等格式）
     * @return 翻译后的文本
     */
    protected String translate(String key, Object... args) {
        return StatCollector.translateToLocalFormatted(key, args);
    }

    /**
     * 获取拥有者 UUID
     *
     * @return 拥有者 UUID，未初始化时返回 null
     */
    protected UUID getOwnerUUID() {
        return ownerUUID;
    }

    /**
     * 获取显示模式
     *
     * @return 显示模式（0=常规计数，1=科学计数）
     */
    protected int getDisplayMode() {
        return displayMode;
    }

    /**
     * 设置显示模式
     * <p>
     * 调用此方法后应标记数据脏污以触发保存
     *
     * @param mode 显示模式（0=常规计数，1=科学计数）
     */
    protected void setDisplayMode(int mode) {
        this.displayMode = mode;
        if (getBaseMetaTileEntity() != null) {
            getBaseMetaTileEntity().markDirty();
        }
    }
}
