
package com.miaokatze.gtswn.common.covers;

import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.miaokatze.gtswn.config.Config;
import com.miaokatze.gtswn.network.NodeRevealRequestQueue;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.relauncher.Side;
import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.covers.Cover;

/**
 * 无线链路节点覆盖板抽象基类
 * <p>
 * 提取 {@link GTswn_Cover_DynamoWireless} 与 {@link GTswn_Cover_EnergyWireless} 的公共逻辑：
 * <ul>
 * <li>公共字段：{@link #storedEU}（缓冲池 EU）、{@link #configured}（是否已配置）</li>
 * <li>公共行为：禁止红石敏感 / 复制粘贴工具 / tick rate 调整；alwaysLookConnected；每 tick 执行；有 GUI</li>
 * <li>{@link #getOwner(ICoverable)}：从机器获取拥有者 UUID（v1.2.1 修正参数类型从 Object 到 ICoverable）</li>
 * <li>{@link #onCoverRemoval()}：将缓冲池剩余 EU 发回无线电网（计算上行损耗）</li>
 * </ul>
 * <p>
 * 子类需实现模式特定的 {@link #doCoverThings}、{@link #onCoverRightClick}、configure 以及
 * NBT / 包同步（readDataFromNbt / saveDataToNbt / readDataFromPacket / writeDataToByteBuf）逻辑。
 * <p>
 * 设计说明：NBT 与包同步逻辑未上提到基类，因为两个子类的字段集合差异较大
 * （Dynamo: storedEU/configured/ticksSinceLastUpload；Energy:
 * voltage/amperage/capacity/storedEU/configured/ticksSinceLastRefill），
 * 强行上提会导致字段读写顺序耦合脆弱。公共字段 storedEU/configured 通过 protected 暴露给子类直接访问。
 * <p>
 * Abstract base for wireless link node covers, extracting common fields and behavior.
 * Subclasses implement mode-specific logic (doCoverThings, configure, NBT/packet sync).
 */
public abstract class GTswnCoverWirelessBase extends Cover {

    /** 当前缓冲池 EU / Current buffer EU */
    protected long storedEU = 0L;

    /** 是否已配置 / Whether the cover has been configured */
    protected boolean configured = false;

    /**
     * v1.7.21 节点显形修复（旧世界空索引自愈）：构造即挂起延迟注册。
     * <p>
     * GT5U 恢复链路（5.09.54.20）：{@code BaseMetaTileEntity.readFromNBT}（BaseMetaTileEntity.java:160）
     * → {@code setInitialValuesAsNBT} → {@code readCoverNBT}（:204）→
     * {@code CoverableTileEntity.readCoversNBT}（:142）→ {@code CoverRegistry.buildCoverFromNbt}
     * （CoverRegistry.java:114-127：工厂构造本覆盖板时 {@code coveredTile} 已绑定（Cover.java:69），
     * 随后 {@code readFromNbt} → {@code readDataFromNbt}，Cover.java:84）。
     * 因此每个 TE 反序列化（含每次区块加载）都会执行本构造器——这是旧世界恢复时唯一
     * 不被子类覆写遮蔽的钩子点（两个子类覆写 {@code readDataFromNbt} 且不调 super，
     * 基类 NBT 钩子是死代码）。
     * <p>
     * 挂起而非就地注册：TE 读 NBT 窗口内 {@code worldObj} 尚未赋值
     * （GT5U {@code BaseTileEntity.markDirty} :559 注释与 {@code isServerSide()} :166-172 的
     * effective-side 回退佐证），无法就地取世界/判 side；注册统一推迟到
     * {@code NodeRevealRequestQueue} 的 ServerTick(END) drain（空清单早退，零常驻开销）。
     * <p>
     * v1.7.21 node-reveal fix (stale-world empty index self-heal): enqueue a deferred
     * registration on construction — see class comment for the GT5U restore chain and why
     * the constructor is the only unshadowed hook.
     */
    public GTswnCoverWirelessBase(CoverContext context) {
        super(context, null);
        enqueueDeferredNodeRegistration();
    }

    /**
     * NBT 恢复路径的延迟注册挂起（服务端限定）：
     * <ul>
     * <li>{@code coveredTile} 未绑定（GT5U {@code addInstalledCoversInformation} 传 null 构造的
     * 纯信息路径）→ 跳过：注册本就需要世界与坐标；</li>
     * <li>{@code world} 已赋值且 {@code isRemote} → 跳过（客户端）；</li>
     * <li>{@code world == null}（区块加载读 NBT 窗口，双端都可能）→ 按
     * {@code FMLCommonHandler.getEffectiveSide()} 线程侧判定，客户端线程跳过
     * （与 GT5U {@code BaseTileEntity.isServerSide()} :166-172 同款判定，
     * 避免客户端静态清单泄漏）。</li>
     * </ul>
     * 通过上述守卫的条目（服务端，含 placeCover 正常附着路径的冗余条目）由
     * ServerTick(END) drain 消费，注册幂等，冗余无副作用。
     */
    private void enqueueDeferredNodeRegistration() {
        ICoverable tileEntity = coveredTile.get();
        if (tileEntity == null) {
            return;
        }
        World world = tileEntity.getWorld();
        if (world != null && world.isRemote) {
            return;
        }
        if (world == null && FMLCommonHandler.instance()
            .getEffectiveSide() == Side.CLIENT) {
            return;
        }
        NodeRevealRequestQueue.enqueueNodeRegistration(this);
    }

    @Override
    public boolean isRedstoneSensitive(long aTimer) {
        return false;
    }

    @Override
    public boolean allowsCopyPasteTool() {
        return false;
    }

    @Override
    public boolean allowsTickRateAddition() {
        return false;
    }

    @Override
    public boolean alwaysLookConnected() {
        return true;
    }

    @Override
    public int getMinimumTickRate() {
        return 1; // 每 tick 执行 / Run every tick
    }

    @Override
    public boolean hasCoverGUI() {
        return true;
    }

    /**
     * 从机器获取拥有者 UUID
     * <p>
     * v1.2.1 修正：参数类型从 Object 改为 ICoverable，消除不必要的不确定类型，
     * 与 {@code coveredTile.get()} 的返回类型对齐。
     *
     * @param te 机器（必须实现 ICoverable，通常为 BaseMetaTileEntity）
     * @return 拥有者 UUID；当机器不是 BaseMetaTileEntity 时返回 null
     */
    protected static UUID getOwner(ICoverable te) {
        if (te instanceof BaseMetaTileEntity igte) {
            return igte.getOwnerUuid();
        }
        return null;
    }

    /**
     * 卸载时：将缓冲池剩余 EU 发回无线电网（计算上行损耗）
     * <p>
     * 两个子类的卸载逻辑完全一致，故上提到基类。电网实际增加量 = storedEU × (1 - uplinkLossEU)。
     * <p>
     * v1.8.x 节点显形：开头插入幂等出册（先于退款执行，退款逻辑原样保留、不因出册提前 return），
     * 使任何移除路径（终端拆卸 / 机器破坏 / 覆盖板替换）都同步摘除节点索引。
     * <p>
     * On removal: unregister from the node index first (idempotent), then return remaining buffer
     * to network (with uplink loss). Network receives storedEU × (1 - uplinkLossEU).
     */
    @Override
    public void onCoverRemoval() {
        unregisterFromNodeRegistry();
        if (this.storedEU > 0) {
            ICoverable tileEntity = coveredTile.get();
            UUID owner = getOwner(tileEntity);
            if (owner != null) {
                long actualAdded = (long) (this.storedEU * (1.0 - Config.uplinkLossEU));
                if (actualAdded > 0) {
                    addEUToGlobalEnergyMap(owner, actualAdded);
                }
            }
            this.storedEU = 0;
        }
    }

    /**
     * 节点类型标识（显形协议包内 type 字节来源）：{@code 0}=能源无线 {@code 1}=动力无线，
     * 值域与 {@link WirelessNodeRegistry#TYPE_ENERGY} / {@link WirelessNodeRegistry#TYPE_DYNAMO} 一致。
     * <p>
     * Node type id for the reveal protocol: 0 = energy, 1 = dynamo.
     */
    public abstract byte nodeTypeId();

    /**
     * 节点索引注册的单一幂等入口（v1.7.21）：{@link #onPlayerAttach}（GT5U
     * {@code CoverPlacer.placeCover} 附着回调）与 NBT 恢复路径（构造挂起 →
     * ServerTick(END) drain 回调）共用。
     * <p>
     * 守卫与返回值：TE 未绑定（GT5U 纯信息构造路径，永不可注册）或客户端
     * （{@code world.isRemote}）→ {@code true}（终态跳过）；世界未赋值（区块加载窗口内
     * drain 时仍为 null 的极端情形）→ {@code false}（可重试，由队列侧有界重试，
     * 超限告警丢弃并等待下次区块加载重治愈）；注册成功 → {@code true}。
     * 注册表 {@code register} 本身幂等（坐标已册且类型相同即无效果）。
     * <p>
     * Single idempotent registration entry shared by the attach path and the
     * deferred NBT-restore path; all guards live here. Returns {@code false} only
     * for the retryable "world not bound yet" case.
     */
    public boolean registerIntoNodeIndex() {
        ICoverable tileEntity = coveredTile.get();
        if (tileEntity == null) {
            return true;
        }
        World world = tileEntity.getWorld();
        if (world == null) {
            return false;
        }
        if (world.isRemote) {
            return true;
        }
        WirelessNodeRegistry.get(world)
            .register(world, tileEntity.getXCoord(), tileEntity.getYCoord(), tileEntity.getZCoord(), nodeTypeId());
        return true;
    }

    /**
     * 附着时（GT5U {@code CoverPlacer.placeCover} 在 attachCover 之后回调）：
     * 经 {@link #registerIntoNodeIndex()} 幂等入册节点索引（服务端限定，守卫在入口内）。
     * <p>
     * On attach: register this cover position into the per-world node index via the
     * shared idempotent entry (server side only; guards inside the entry).
     */
    @Override
    public void onPlayerAttach(EntityPlayer player, ItemStack coverItem) {
        registerIntoNodeIndex();
    }

    /**
     * 基础 TE 被破坏时（GT5U 仅服务端生存破坏路径回调）：出册节点索引。
     * 区块卸载（onCoverUnload）不挂钩——索引保留，配合显形查询期 UNLOADED 跳过语义。
     * <p>
     * On base TE destroyed: unregister from the node index (idempotent).
     */
    @Override
    public void onBaseTEDestroyed() {
        unregisterFromNodeRegistry();
    }

    /**
     * 从所在世界的节点注册表出册（幂等：未册无效果；仅服务端；TE 已失效时静默跳过）。
     */
    private void unregisterFromNodeRegistry() {
        ICoverable tileEntity = coveredTile.get();
        if (tileEntity == null || tileEntity.getWorld().isRemote) {
            return;
        }
        World world = tileEntity.getWorld();
        WirelessNodeRegistry.get(world)
            .unregister(world, tileEntity.getXCoord(), tileEntity.getYCoord(), tileEntity.getZCoord());
    }
}
