package com.miaokatze.gtswn.common.tile.screen;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MathHelper;
import net.minecraft.world.IBlockAccess;

import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;

/**
 * 信息屏多方块结构的朝向/边缘判定工具（O2-01 第一步 = E 线 E1，合并第一轮 SWN-OPT-05）。
 * <p>
 * {@code BlockNetworkInfoPanel} 与 {@code BlockNetworkInfoPanelExtender} 此前各持一份
 * 逐字一致的双份拷贝（五工具双份合计约 182 行），本类单源收敛，方法体逐字搬迁、零行为变更；
 * {@code findNeighborFacing}（拓展屏独占）不迁，仍留 {@code BlockNetworkInfoPanelExtender}。
 * E2（O2-01b）已随 ScreenStructure 域迁入收编本工具类至 {@code common.tile.screen}
 * （纯位置搬迁，方法体与可见性零变更）；注意本类 {@link #isCompatibleScreenPart} 为方块侧
 * 贴图边掩码口径（任意主屏均兼容），与 ScreenStructure 的 BFS 口径（仅 core 自身）有意不同。
 */
public final class ScreenStructureUtil {

    private ScreenStructureUtil() {}

    /** 放置时按放置者水平朝向取屏幕朝向 */
    public static int getHorizontalFacingFromEntity(EntityLivingBase entity) {
        int direction = MathHelper.floor_double(entity.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        switch (direction) {
            case 0:
                return 2;
            case 1:
                return 5;
            case 2:
                return 3;
            case 3:
                return 4;
            default:
                return 3;
        }
    }

    /** 元数据越界（旧档/异常来源）兜底到南面 3 */
    public static int normalizeFacing(int meta) {
        if (meta >= 2 && meta <= 5) {
            return meta;
        }
        return 3;
    }

    /** 计算多方块屏幕连接贴图边掩码（上下左右四边是否缺少同朝向邻居） */
    public static int getEdgeMask(IBlockAccess world, int x, int y, int z, int facing) {
        int mask = 0;
        if (!isCompatibleScreenPart(world, x, y + 1, z, facing)) {
            mask |= 1;
        }
        if (!isCompatibleScreenPart(world, x, y - 1, z, facing)) {
            mask |= 2;
        }
        // v1.5.15：修复方向性各异性——左右边缘需按朝向镜像（与主屏一致）。
        // bit2 (mask4)=左侧无邻居，bit3 (mask8)=右侧无邻居。
        // 观察者面对屏幕时左手边方向：
        // facing=3 (S) → X-，facing=2 (N) → X+（镜像）
        // facing=4 (W) → Z-，facing=5 (E) → Z+（镜像）
        if (facing == 2) {
            // N: 180°镜像，左右互换
            if (!isCompatibleScreenPart(world, x + 1, y, z, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x - 1, y, z, facing)) {
                mask |= 8;
            }
        } else if (facing == 3) {
            // S: 不变
            if (!isCompatibleScreenPart(world, x - 1, y, z, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x + 1, y, z, facing)) {
                mask |= 8;
            }
        } else if (facing == 4) {
            // W: 不变
            if (!isCompatibleScreenPart(world, x, y, z - 1, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x, y, z + 1, facing)) {
                mask |= 8;
            }
        } else {
            // E (facing==5): +90°镜像，左右互换
            if (!isCompatibleScreenPart(world, x, y, z + 1, facing)) {
                mask |= 4;
            }
            if (!isCompatibleScreenPart(world, x, y, z - 1, facing)) {
                mask |= 8;
            }
        }
        return mask;
    }

    /** 判定目标坐标是否为同朝向的屏幕部件（主屏或拓展屏） */
    public static boolean isCompatibleScreenPart(IBlockAccess world, int x, int y, int z, int facing) {
        TileEntity tile = world.getTileEntity(x, y, z);
        return (tile instanceof TileEntityNetworkInfoPanel || tile instanceof TileEntityNetworkInfoPanelExtender)
            && normalizeFacing(tile.getBlockMetadata()) == facing;
    }

    /** 面编号取对面（屏幕面 ↔ 背面） */
    public static int opposite(int side) {
        switch (side) {
            case 2:
                return 3;
            case 3:
                return 2;
            case 4:
                return 5;
            case 5:
                return 4;
            case 0:
                return 1;
            case 1:
                return 0;
            default:
                return side;
        }
    }
}
