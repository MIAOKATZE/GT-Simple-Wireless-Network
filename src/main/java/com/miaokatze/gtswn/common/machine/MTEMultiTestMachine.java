package com.miaokatze.gtswn.common.machine;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.onElementPass;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.transpose;
import static gregtech.api.enums.HatchElement.*;
import static gregtech.api.enums.Textures.BlockIcons.*;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.miaokatze.gtswn.api.recipe.GTSWNRecipeMaps;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;

/**
 * 测试用多方块机器 (HV)
 * <p>
 * 该机器用于验证多方块机器的注册流程、结构检测逻辑以及专属配方系统的集成。
 * 它采用 3x3x3 的空心不锈钢结构，并支持标准的 GregTech 仓室（能源、维护、输入/输出总线等）。
 */
public class MTEMultiTestMachine extends MTEEnhancedMultiBlockBase<MTEMultiTestMachine>
    implements IConstructable, ISurvivalConstructable {

    /** 结构定义的唯一标识符，用于在 StructureLib 中索引特定的结构片段 */
    private static final String STRUCTURE_PIECE_MAIN = "main";

    /** 记录结构中成功匹配的不锈钢外壳数量，用于完整性校验 */
    private int mCasingAmount = 0;

    /**
     * 3x3x3 空心结构定义矩阵。
     * <ul>
     * <li>{@code ~}: 控制器位置（位于底层中心）</li>
     * <li>{@code h}: 机器外壳或仓室位置（支持不锈钢方块及各类仓室）</li>
     * <li>{@code -}: 空气或任意方块（此处定义为中层中心的空气）</li>
     * </ul>
     */
    private static final String[][] shape = new String[][] { { "hhh", "hhh", "hhh" }, // 顶层：全外壳
        { "h~h", "h-h", "hhh" }, // 中层：包含控制器和内部空间
        { "hhh", "hhh", "hhh" } // 底层：全外壳
    };

    /**
     * 构建多方块结构的定义对象。
     * <p>
     * 使用 StructureLib 的链式调用将字符 {@code 'h'} 映射到具体的物理方块和仓室逻辑。
     * 这里定义了机器可以接受的所有仓室类型，并指定了外壳的材质索引。
     */
    private static final IStructureDefinition<MTEMultiTestMachine> STRUCTURE_DEFINITION = IStructureDefinition
        .<MTEMultiTestMachine>builder()
        .addShape(STRUCTURE_PIECE_MAIN, transpose(shape))
        .addElement(
            'h',
            buildHatchAdder(MTEMultiTestMachine.class)
                // 声明该位置至少可以是以下仓室之一，或者是普通的外壳方块
                .atLeast(InputHatch, OutputHatch, InputBus, OutputBus, Maintenance, Energy)
                // 指定将识别到的仓室添加到机器列表的方法引用
                .adder(MTEMultiTestMachine::addToMachineList)
                // 在游戏内使用软锤查看结构时，该位置的提示点编号
                .dot(1)
                // 设置外壳方块的材质纹理索引（不锈钢机器方块）
                .casingIndex(GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings4, 0))
                // 如果不是仓室，则检查是否为指定的外壳方块，并在匹配成功时触发 onCasingAdded 计数
                .buildAndChain(
                    onElementPass(
                        MTEMultiTestMachine::onCasingAdded,
                        com.gtnewhorizon.structurelib.structure.StructureUtility
                            .ofBlock(GregTechAPI.sBlockCasings4, 0))))
        .build();

    public MTEMultiTestMachine(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEMultiTestMachine(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEMultiTestMachine(mName);
    }

    @Override
    public RecipeMap<?> getRecipeMap() {
        // 返回模组专属配方表，使 NEI 正确识别机器类别
        return GTSWNRecipeMaps.MultiTestMachineRecipes;
    }

    /**
     * 【关键】创建并返回配方处理逻辑实例。
     * <p>
     * 在 GT5U 的现代多方块架构中，必须重写此方法以启用基于总线的配方扫描。
     * 如果不重写，基类将无法识别输入总线（Input Bus）中的物品，导致始终提示“未找到配方”。
     *
     * @return 一个新的 ProcessingLogic 实例
     */
    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic();
    }

    /**
     * 配方检查与执行入口。
     * <p>
     * 每 tick 调用一次。此处直接委托给基类的处理逻辑，基类会自动利用上一步创建的
     * {@link ProcessingLogic} 去扫描仓室并匹配 {@link #getRecipeMap()} 返回的配方表。
     *
     * @return 配方检查结果（成功、无配方或电力不足等）
     */
    @Override
    public CheckRecipeResult checkProcessing() {
        // 调用基类逻辑自动寻找并验证配方
        CheckRecipeResult result = super.checkProcessing();
        if (result.wasSuccessful()) {
            return result;
        }
        return CheckRecipeResultRegistry.NO_RECIPE;
    }

    /**
     * 结构完整性检测方法。
     * <p>
     * 该方法在机器每次更新或被玩家交互时调用。它会重置计数器并调用 StructureLib
     * 进行空间扫描。只有当外壳数量达标且必需仓室（能源、维护）存在时，机器才会被视为“已成型”。
     *
     * @param aBaseMetaTileEntity 机器所在的 TileEntity
     * @param aStack              玩家手持的物品（可用于动态结构调整，此处未使用）
     * @return 结构是否完整且合法
     */
    @Override
    public boolean checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack) {
        mCasingAmount = 0;
        // 检查结构并验证仓室 (偏移量 1, 1, 0 对应底层中心)
        return checkPiece(STRUCTURE_PIECE_MAIN, 1, 1, 0) && mCasingAmount >= 8
            && mMaintenanceHatches.size() > 0
            && mEnergyHatches.size() > 0;
    }

    /**
     * 结构扫描回调：每当 StructureLib 匹配到一个外壳方块时调用。
     */
    private void onCasingAdded() {
        mCasingAmount++;
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        // 创造模式构建支持
        buildPiece(STRUCTURE_PIECE_MAIN, stackSize, hintsOnly, 1, 1, 0);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (mMachine) return -1;
        // 生存模式构建支持
        return survivalBuildPiece(STRUCTURE_PIECE_MAIN, stackSize, 1, 1, 0, elementBudget, env, false, true);
    }

    @Override
    public IStructureDefinition<MTEMultiTestMachine> getStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    public String[] getStructureDescription(ItemStack stackSize) {
        return new String[] { EnumChatFormatting.AQUA + "结构说明:", "1. 使用不锈钢机器方块搭建 3x3x3 空心结构", "2. 控制器位于底层中心",
            "3. 需要安装至少一个能源仓和维护仓", "4. 可以安装输入/输出仓、输入/输出总线" };
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity aBaseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean active, boolean redstoneLevel) {
        if (side == facing) {
            if (active) {
                return new ITexture[] {
                    Textures.BlockIcons
                        .getCasingTextureForId(GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings4, 0)),
                    TextureFactory.builder()
                        .addIcon(OVERLAY_FRONT_ASSEMBLY_LINE)
                        .extFacing()
                        .build(),
                    TextureFactory.builder()
                        .addIcon(OVERLAY_FRONT_ASSEMBLY_LINE_GLOW)
                        .extFacing()
                        .glow()
                        .build() };
            }
            return new ITexture[] {
                Textures.BlockIcons
                    .getCasingTextureForId(GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings4, 0)),
                TextureFactory.builder()
                    .addIcon(OVERLAY_FRONT_ASSEMBLY_LINE)
                    .extFacing()
                    .build() };
        }
        return new ITexture[] {
            Textures.BlockIcons.getCasingTextureForId(GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings4, 0)) };
    }

    @Override
    public String[] getInfoData() {
        return new String[] { EnumChatFormatting.BLUE + "测试多方块机器 (HV)", EnumChatFormatting.GRAY + "状态: "
            + (mMachine ? EnumChatFormatting.GREEN + "已成型" : EnumChatFormatting.RED + "未成型") };
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        final MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType("测试多方块机器")
            .addInfo("用于验证多方块机器的注册流程、结构检测及配方系统。")
            .addInfo(EnumChatFormatting.AQUA + "结构说明:")
            .addInfo("1. 使用不锈钢机器方块搭建 3x3x3 空心结构")
            .addInfo("2. 控制器位于底层中心")
            .addInfo("3. 需要安装至少一个能源仓和维护仓")
            .beginStructureBlock(3, 3, 3, true)
            .addController("底层中心")
            .addCasingInfoMin("不锈钢机器方块", 8, false)
            .addEnergyHatch("任意不锈钢方块", 1)
            .addMaintenanceHatch("任意不锈钢方块", 1);
        return tt;
    }
}
