package com.miaokatze.gtswn.common.testworld;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtswn.common.api.enums.GTSWNItemList;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless;
import com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless;
import com.miaokatze.gtswn.common.covers.WirelessNodeIndexCodec;
import com.miaokatze.gtswn.common.covers.WirelessNodeRegistry;
import com.miaokatze.gtswn.config.Config;

import gregtech.api.enums.ItemList;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.common.covers.Cover;
import gregtech.common.misc.WirelessNetworkManager;

/** Real GT hatches and installed covers, advanced only by the world's ordinary tile ticks. */
final class WirelessEuScenario implements TestScenario {

    private final boolean uplink;
    private UUID owner;
    private BaseMetaTileEntity host;
    private Cover cover;
    private BigInteger funded, debit;
    private long bufferBeforeReload, initialMachineEU, minimumMachineEU;
    private TestEuScope scope;

    WirelessEuScenario(boolean uplink) {
        this.uplink = uplink;
    }

    @Override
    public String id() {
        return uplink ? "eu.uplink" : "eu.downlink";
    }

    @Override
    public String description() {
        return uplink ? "Dynamo hatch natural tick drain, configured loss and network upload"
            : "Energy cover funding, natural injection, NBT/index recovery, refund and insufficient balance";
    }

    @Override
    public int timeoutTicks() {
        return Math.max(200, Math.min(12000, Config.interactionRateTicks + 160));
    }

    @Override
    public void start(TestContext ctx) {
        host = null;
        cover = null;
        scope = null;
        owner = TestWorldFixtures.owner(id());
        scope = new TestEuScope(owner);
        TestWorldProbes.addOwner(owner);
        TestWorldFixtures.require(
            ctx,
            WirelessNetworkManager.getUserEU(owner)
                .signum() == 0,
            "Fresh synthetic UUID has no balance");
        if (uplink && (Config.interactionRateTicks < 1 || Config.interactionRateTicks > 10000)) {
            ctx.skip("Configured upload interval outside bounded 1..10000 tick test window");
            return;
        }
        host = TestWorldFixtures.machine(
            ctx.world(),
            uplink ? TestWorldFixtures.DYNAMO_X : TestWorldFixtures.ENERGY_X,
            TestWorldFixtures.EU_Z,
            uplink ? ItemList.Hatch_Dynamo_LV.get(1) : ItemList.Hatch_Energy_LV.get(1),
            owner);
        cover = TestWorldFixtures.cover(
            host,
            uplink ? GTSWNItemList.GTswn_Cover_Dynamo_Wireless : GTSWNItemList.GTswn_Cover_Energy_Wireless);
        if (uplink) {
            minimumMachineEU = ((MetaTileEntity) host.getMetaTileEntity()).getMinimumStoredEU();
            host.increaseStoredEnergyUnits(Math.min(minimumMachineEU + 256, host.getEUCapacity()), true);
            initialMachineEU = host.getStoredEUuncapped();
            TestWorldFixtures.require(ctx, initialMachineEU > 0, "Dynamo fixture stores actual EU");
            ((GTswn_Cover_DynamoWireless) cover).configure();
        } else {
            funded = new BigInteger("1000000000000");
            WirelessNetworkManager.addEUToGlobalEnergyMap(owner, funded);
            ((GTswn_Cover_EnergyWireless) cover).configure(32, 1);
            long buffer = data(cover).getLong("storedEU");
            debit = BigDecimal.valueOf(buffer)
                .multiply(BigDecimal.ONE.add(new BigDecimal(Float.toString(Config.downlinkLossEU))))
                .setScale(0, RoundingMode.CEILING)
                .toBigIntegerExact();
            TestWorldFixtures.require(ctx, buffer > 0, "Real cover funded its buffer");
            TestWorldFixtures.require(
                ctx,
                WirelessNetworkManager.getUserEU(owner)
                    .equals(funded.subtract(debit)),
                "Actual network debit includes configured downlink loss with ceiling rounding");
        }
        ctx.event("fixture", "Real LV hatch installed; Config remains unchanged; owner is isolated synthetic UUID");
    }

    @Override
    public void tick(TestContext ctx) {
        if (uplink) tickUplink(ctx);
        else tickDownlink(ctx);
    }

    private void tickUplink(TestContext ctx) {
        if (ctx.tick() < Config.interactionRateTicks + 40L) return;
        long remaining = host.getStoredEUuncapped();
        long inBuffer = data(cover).getLong("storedEU");
        long drained = initialMachineEU - remaining;
        BigInteger actual = WirelessNetworkManager.getUserEU(owner);
        TestWorldFixtures.require(ctx, drained > 0, "Natural cover ticks drain the real dynamo hatch");
        BigInteger expected = BigDecimal.valueOf(drained)
            .multiply(BigDecimal.ONE.subtract(new BigDecimal(Float.toString(Config.uplinkLossEU))))
            .setScale(0, RoundingMode.FLOOR)
            .toBigIntegerExact();
        boolean uncreditable = expected.signum() == 0;
        TestWorldFixtures.require(
            ctx,
            uncreditable ? actual.signum() == 0 : actual.signum() > 0,
            "Natural upload interval credited the network, or configured loss rounded credit to zero");
        // The small finite fixture drains fully before the first default upload; any later residual is explicit.
        TestWorldFixtures.require(ctx, remaining == minimumMachineEU, "Dynamo preserves the host's minimum EU reserve");
        TestWorldFixtures.require(
            ctx,
            inBuffer == (uncreditable ? drained : 0),
            "Upload consumed cover buffer, or retained buffer whose configured credit rounds to zero");
        if (Config.interactionRateTicks >= 16) TestWorldFixtures
            .require(ctx, actual.equals(expected), "Network credit equals actual drained EU minus uplink loss");
        else ctx.event(
            "limitation",
            "Short custom upload interval may round several batches; exact aggregate rounding not asserted");
        TestWorldFixtures.require(ctx, indexed(ctx), "Deferred lifecycle registered installed dynamo node");
        host.detachCover(TestWorldFixtures.COVER_SIDE);
        TestWorldFixtures.require(ctx, !indexed(ctx), "Actual detach removed wireless node index");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.EU_UPLINK) > 0,
            "Production upload hook observed real interval attempt");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_ADD) > 0
                && TestWorldProbes.count(TestWorldProbes.Type.WIRELESS_NODE_REMOVE) > 0,
            "Production wireless-node hooks observed actual lifecycle");
        ctx.pass("Real hatch drained and uploaded by natural ticks; loss/index/detach assertions passed");
    }

    private void tickDownlink(TestContext ctx) {
        if (ctx.tick() == 40) {
            TestWorldFixtures.require(
                ctx,
                host.getStoredEUuncapped() > 0,
                "Natural cover ticks injected EU into the real energy hatch");
            TestWorldFixtures.require(ctx, indexed(ctx), "Deferred installed-cover queue populated wireless index");
            bufferBeforeReload = data(cover).getLong("storedEU");
            host = (BaseMetaTileEntity) TestWorldFixtures.reload(ctx.world(), host);
            cover = host.getCoverAtSide(TestWorldFixtures.COVER_SIDE);
            TestWorldFixtures.require(
                ctx,
                cover instanceof GTswn_Cover_EnergyWireless,
                "Host NBT reload recreated the production cover");
            TestWorldFixtures.require(
                ctx,
                data(cover).getLong("storedEU") == bufferBeforeReload,
                "NBT replay preserved cover EU without funding a second copy");
        }
        if (ctx.tick() != 80) return;
        TestWorldFixtures.require(ctx, indexed(ctx), "Restored cover re-registered through natural deferred lifecycle");
        long refundBuffer = data(cover).getLong("storedEU");
        BigInteger before = WirelessNetworkManager.getUserEU(owner);
        BigInteger refund = BigDecimal.valueOf(refundBuffer)
            .multiply(BigDecimal.ONE.subtract(new BigDecimal(Float.toString(Config.uplinkLossEU))))
            .setScale(0, RoundingMode.FLOOR)
            .toBigIntegerExact();
        host.detachCover(TestWorldFixtures.COVER_SIDE);
        TestWorldFixtures.require(
            ctx,
            WirelessNetworkManager.getUserEU(owner)
                .equals(before.add(refund)),
            "Actual detach refunds remaining cover EU with configured uplink loss");
        TestWorldFixtures.require(ctx, !indexed(ctx), "Detach removed wireless position index");
        emptyBalance();
        cover = TestWorldFixtures.cover(host, GTSWNItemList.GTswn_Cover_Energy_Wireless);
        ((GTswn_Cover_EnergyWireless) cover).configure(32, 1);
        TestWorldFixtures
            .require(ctx, data(cover).getLong("storedEU") == 0, "Insufficient balance does not create cover EU");
        TestWorldFixtures.require(
            ctx,
            WirelessNetworkManager.getUserEU(owner)
                .signum() == 0,
            "Rejected funding leaves balance unchanged");
        TestWorldFixtures.require(
            ctx,
            TestWorldProbes.count(TestWorldProbes.Type.EU_COMMIT) > 0
                && TestWorldProbes.count(TestWorldProbes.Type.EU_REJECT) > 0
                && TestWorldProbes.count(TestWorldProbes.Type.EU_DOWNLINK) > 0,
            "Actual GT transaction and downlink hooks observed commit and insufficient-funds rejection");
        ctx.pass("Natural injection, NBT/index lifecycle, configured loss, refund and insufficient funds passed");
    }

    private boolean indexed(TestContext ctx) {
        return WirelessNodeRegistry.get(ctx.world())
            .snapshot(ctx.world())
            .containsKey(WirelessNodeIndexCodec.pack(host.xCoord, host.yCoord, host.zCoord));
    }

    private static NBTTagCompound data(Cover cover) {
        return cover.writeToNBT(new NBTTagCompound())
            .getCompoundTag("d");
    }

    private void emptyBalance() {
        BigInteger balance = WirelessNetworkManager.getUserEU(owner);
        if (balance.signum() > 0) WirelessNetworkManager.addEUToGlobalEnergyMap(owner, balance.negate());
    }

    @Override
    public void cleanup(TestContext ctx) {
        if (host != null && host.getWorldObj() == ctx.world() && !host.isInvalid())
            host.detachCover(TestWorldFixtures.COVER_SIDE);
        if (scope != null) scope.close();
        scope = null;
        host = null;
        cover = null;
    }
}
