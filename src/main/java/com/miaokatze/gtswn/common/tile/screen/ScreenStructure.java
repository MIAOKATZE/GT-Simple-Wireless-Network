package com.miaokatze.gtswn.common.tile.screen;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanel;
import com.miaokatze.gtswn.common.tile.TileEntityNetworkInfoPanelExtender;

/**
 * 信息屏多方块结构域（O2-01b = E 线 E2，四域拆分第一步）：BFS 连通重建 + 最大填满子矩形
 * 识别 + Extender 附着/解除 + 渲染包围盒。
 * <p>
 * 方法体自 {@code TileEntityNetworkInfoPanel} 逐字搬迁（E2 表 A 行段整体平移），零行为变更；
 * core 引用仅用于坐标/世界访问、{@code markDirty} 与 Extender 附着回调。本域不触 NBT/网络/owner
 * ——序列化仍由 TE 装配，经 {@link #getScreen()}/{@link #setScreen(NetworkScreen)} 搬运
 * {@code screen} 键。依赖方向 TE → 本域，单向。
 */
public final class ScreenStructure {

    private final TileEntityNetworkInfoPanel core;

    /** 已识别的最大填满子矩形（null 表示尚未重建） */
    private NetworkScreen screen;

    private boolean screenInitialized = false;

    public ScreenStructure(TileEntityNetworkInfoPanel core) {
        this.core = core;
    }

    public NetworkScreen getScreen() {
        return screen;
    }

    /**
     * NBT/S35 恢复通道：TE 装配（readFromNBT/readSyncData）专用，本域自身不做序列化。
     */
    public void setScreen(NetworkScreen screen) {
        this.screen = screen;
    }

    public boolean isInitialized() {
        return screenInitialized;
    }

    public void markInitialized() {
        this.screenInitialized = true;
    }

    public void rebuild() {
        int facing = core.getBlockMetadata();
        if (facing < 2 || facing > 5) {
            facing = 3;
        }

        Set<String> visited = new HashSet<>();
        // v1.4.6：新增 screenParts 只记录兼容的屏幕方块（主屏+Extender），用于后续矩形识别与 Extender 遍历
        // 修复 bug：原 BFS 把空气方块也加入 visited，污染 findLargestFilledRect 的 occupied 集合，
        // 导致算法返回包含空气行的更大矩形（如 3x3 错误扩展为 5x3）
        Set<String> screenParts = new HashSet<>();
        Queue<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { core.xCoord, core.yCoord, core.zCoord });
        int minX = core.xCoord;
        int maxX = core.xCoord;
        int minY = core.yCoord;
        int maxY = core.yCoord;
        int minZ = core.zCoord;
        int maxZ = core.zCoord;

        while (!queue.isEmpty()) {
            int[] pos = queue.remove();
            String key = key(pos[0], pos[1], pos[2]);
            if (!visited.add(key)) {
                continue;
            }
            TileEntity tile = core.getWorldObj()
                .getTileEntity(pos[0], pos[1], pos[2]);
            if (!isCompatibleScreenPart(tile, facing)) {
                continue;
            }
            screenParts.add(key); // v1.4.6：仅兼容方块才记录到 screenParts，避免空气方块污染矩形识别
            minX = Math.min(minX, pos[0]);
            maxX = Math.max(maxX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxY = Math.max(maxY, pos[1]);
            minZ = Math.min(minZ, pos[2]);
            maxZ = Math.max(maxZ, pos[2]);
            addPlaneNeighbors(queue, pos[0], pos[1], pos[2], facing);
        }

        // v1.4.5：改为识别"完全填满的子矩形"，而非整个包围盒
        // v1.4.6：在 screenParts（仅兼容方块）中找出包含 core 位置的最大填满子矩形
        int[] rect = findLargestFilledRect(screenParts, facing, core.xCoord, core.yCoord, core.zCoord); // v1.4.6：传
                                                                                                        // screenParts
                                                                                                        // 而非
                                                                                                        // visited，确保只识别真实屏幕方块
        NetworkScreen next = new NetworkScreen();
        next.minX = rect[0];
        next.minY = rect[1];
        next.minZ = rect[2];
        next.maxX = rect[3];
        next.maxY = rect[4];
        next.maxZ = rect[5];
        next.coreX = core.xCoord;
        next.coreY = core.yCoord;
        next.coreZ = core.zCoord;
        next.facing = facing;
        screen = next;

        // 遍历所有兼容的屏幕方块：子矩形内的 Extender 附着到 core，子矩形外的 Extender 解除附着
        // v1.4.6：用 screenParts 替代 visited，避免遍历到空气等不兼容方块
        for (String key : screenParts) {
            int[] pos = parseKey(key);
            TileEntity tile = core.getWorldObj()
                .getTileEntity(pos[0], pos[1], pos[2]);
            if (tile instanceof TileEntityNetworkInfoPanelExtender) {
                TileEntityNetworkInfoPanelExtender extender = (TileEntityNetworkInfoPanelExtender) tile;
                // 判断该 Extender 是否落在最终子矩形内
                boolean inRect = pos[0] >= next.minX && pos[0] <= next.maxX
                    && pos[1] >= next.minY
                    && pos[1] <= next.maxY
                    && pos[2] >= next.minZ
                    && pos[2] <= next.maxZ;
                if (inRect) {
                    extender.attachToCore(core, next);
                } else {
                    // 连通但在子矩形外的 Extender，解除附着避免残留 partOfScreen 状态
                    extender.detachFromCore();
                }
            }
            core.getWorldObj()
                .markBlockForUpdate(pos[0], pos[1], pos[2]);
        }
        core.markDirty();
        core.getWorldObj()
            .markBlockForUpdate(core.xCoord, core.yCoord, core.zCoord);
    }

    /**
     * 在已连通的屏幕方块集合中，找出包含 core 位置的、完全被填满的最大子矩形。
     * <p>
     * 算法思路：
     * <ol>
     * <li>将 3D 方块坐标投影到 2D 平面（facing 2/3 时水平轴=X，facing 4/5 时水平轴=Z，垂直轴=Y）</li>
     * <li>枚举垂直行区间 [top, bottom]，增量维护每列是否在该行区间内全部被占用</li>
     * <li>约束：core 的垂直坐标必须在 [top, bottom] 内，且 core 的水平列必须被占用</li>
     * <li>从 core 水平坐标向左右扩展连续被占用的最远边界，计算面积</li>
     * <li>取面积最大的子矩形作为结果</li>
     * </ol>
     * <p>
     * 复杂度 O(rows² × cols)，屏幕规模小（通常 ≤16×16）完全可行。
     *
     * @param screenParts 已连通且兼容的屏幕方块坐标集合（不含空气等不兼容方块，key 格式 "x,y,z"）
     * @param facing      朝向（2/3 为 X 方向展开，4/5 为 Z 方向展开）
     * @param coreX       主屏 X 坐标
     * @param coreY       主屏 Y 坐标
     * @param coreZ       主屏 Z 坐标
     * @return int[6] = {minX, minY, minZ, maxX, maxY, maxZ} 最大填满子矩形的 3D 边界
     */
    private static int[] findLargestFilledRect(Set<String> screenParts, int facing, int coreX, int coreY, int coreZ) {
        // 确定投影轴：facing 2/3 时水平轴=X，facing 4/5 时水平轴=Z；垂直轴始终=Y
        boolean xAxis = (facing == 2 || facing == 3);
        int coreH = xAxis ? coreX : coreZ;
        int coreV = coreY;

        // 收集所有已占用方块的 2D 坐标，并求包围范围
        java.util.Set<Long> occupied = new java.util.HashSet<>();
        int hMin = coreH, hMax = coreH, vMin = coreV, vMax = coreV;
        for (String key : screenParts) {
            int[] pos = parseKey(key);
            int h = xAxis ? pos[0] : pos[2];
            int v = pos[1];
            // 用 (long)h << 32 | (v & 0xFFFFFFFFL) 编码 2D 坐标，避免 Long.signum 问题
            occupied.add(((long) h << 32) | (v & 0xFFFFFFFFL));
            hMin = Math.min(hMin, h);
            hMax = Math.max(hMax, h);
            vMin = Math.min(vMin, v);
            vMax = Math.max(vMax, v);
        }

        int cols = hMax - hMin + 1;
        boolean[] colOk = new boolean[cols];

        int bestArea = 1;
        int bestLeft = coreH, bestRight = coreH, bestTop = coreV, bestBottom = coreV;

        // 枚举行(垂直)区间 [top, bottom]
        for (int top = vMin; top <= vMax; top++) {
            // 每个 top 起始重置列占用状态
            java.util.Arrays.fill(colOk, true);
            for (int bottom = top; bottom <= vMax; bottom++) {
                // 增量更新：bottom 行加入后，列 c 仍为 true 当且仅当 (c, bottom) 被占用
                for (int c = hMin; c <= hMax; c++) {
                    int idx = c - hMin;
                    if (colOk[idx]) {
                        long code = ((long) c << 32) | (bottom & 0xFFFFFFFFL);
                        if (!occupied.contains(code)) {
                            colOk[idx] = false;
                        }
                    }
                }
                // 约束：core 的垂直坐标必须在 [top, bottom] 内
                if (coreV < top || coreV > bottom) {
                    continue;
                }
                // 约束：core 的水平列必须被占用
                if (!colOk[coreH - hMin]) {
                    continue;
                }
                // 从 coreH 向左右扩展连续 true 的最远边界
                int left = coreH;
                while (left - 1 >= hMin && colOk[left - 1 - hMin]) {
                    left--;
                }
                int right = coreH;
                while (right + 1 <= hMax && colOk[right + 1 - hMin]) {
                    right++;
                }
                int area = (bottom - top + 1) * (right - left + 1);
                if (area > bestArea) {
                    bestArea = area;
                    bestLeft = left;
                    bestRight = right;
                    bestTop = top;
                    bestBottom = bottom;
                }
            }
        }

        // 映射回 3D 边界
        if (xAxis) {
            return new int[] { bestLeft, bestTop, coreZ, bestRight, bestBottom, coreZ };
        } else {
            return new int[] { coreX, bestTop, bestLeft, coreX, bestBottom, bestRight };
        }
    }

    /**
     * 渲染包围盒（原 TileEntity.getRenderBoundingBox 覆写体；screen==null 兜底 1×1×1）。
     */
    public AxisAlignedBB renderBounds(int x, int y, int z) {
        if (screen == null) {
            return AxisAlignedBB.getBoundingBox(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
        }
        return AxisAlignedBB
            .getBoundingBox(
                screen.minX,
                screen.minY,
                screen.minZ,
                screen.maxX + 1.0D,
                screen.maxY + 1.0D,
                screen.maxZ + 1.0D)
            .expand(0.25D, 0.25D, 0.25D);
    }

    /**
     * 破坏/卸载时遍历屏幕范围解除全部 Extender 附着（原 detachScreen）。
     */
    public void detach() {
        if (screen == null || core.getWorldObj() == null) {
            return;
        }
        for (int x = screen.minX; x <= screen.maxX; x++) {
            for (int y = screen.minY; y <= screen.maxY; y++) {
                for (int z = screen.minZ; z <= screen.maxZ; z++) {
                    TileEntity tile = core.getWorldObj()
                        .getTileEntity(x, y, z);
                    if (tile instanceof TileEntityNetworkInfoPanelExtender) {
                        ((TileEntityNetworkInfoPanelExtender) tile).detachFromCore();
                    }
                }
            }
        }
    }

    /**
     * BFS 兼容判定：主屏仅接受 core 自身（不跨相邻面板泄漏），Extender 按朝向匹配。
     * 注意与 {@link ScreenStructureUtil#isCompatibleScreenPart}（方块侧贴图边掩码，任意主屏均兼容）
     * 语义有意不同，勿合并。
     */
    private boolean isCompatibleScreenPart(TileEntity tile, int facing) {
        if (tile instanceof TileEntityNetworkInfoPanel) {
            return tile == core && tile.getBlockMetadata() == facing;
        }
        return tile instanceof TileEntityNetworkInfoPanelExtender && tile.getBlockMetadata() == facing;
    }

    private static void addPlaneNeighbors(Queue<int[]> queue, int x, int y, int z, int facing) {
        queue.add(new int[] { x, y + 1, z });
        queue.add(new int[] { x, y - 1, z });
        if (facing == 2 || facing == 3) {
            queue.add(new int[] { x + 1, y, z });
            queue.add(new int[] { x - 1, y, z });
        } else {
            queue.add(new int[] { x, y, z + 1 });
            queue.add(new int[] { x, y, z - 1 });
        }
    }

    /** 3 参委托 4 参（v1.5.15 语义） */
    public static void rebuildNearby(World world, int x, int y, int z) {
        // v1.5.15：原 3 参数版本委托给 4 参数版本，保持向后兼容
        rebuildNearby(world, x, y, z, 16);
    }

    /**
     * v1.5.15：可指定扫描范围的重建方法，替代固定 ±16 全空间扫描。
     *
     * @param range 扫描半径（方块数）
     */
    public static void rebuildNearby(World world, int x, int y, int z, int range) {
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    TileEntity tile = world.getTileEntity(x + dx, y + dy, z + dz);
                    if (tile instanceof TileEntityNetworkInfoPanel) {
                        ((TileEntityNetworkInfoPanel) tile).rebuildScreen();
                    }
                }
            }
        }
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static int[] parseKey(String key) {
        String[] parts = key.split(",");
        return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
    }
}
