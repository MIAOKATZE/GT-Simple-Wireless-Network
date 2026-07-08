package com.miaokatze.gtswn.common.tile;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import com.miaokatze.gtswn.common.panel.NetworkInfoDataSet;
import com.miaokatze.gtswn.common.panel.NetworkInfoDataStore;
import com.miaokatze.gtswn.common.panel.NetworkInfoSample;
import com.miaokatze.gtswn.common.panel.NetworkScreen;
import com.miaokatze.gtswn.common.util.EUDataSet;
import com.miaokatze.gtswn.common.util.FormatUtil;
import com.miaokatze.gtswn.common.util.GTTierUtil;

import gregtech.common.misc.WirelessNetworkManager;

public class TileEntityNetworkInfoPanel extends TileEntity {

    private static final long SAMPLE_INTERVAL_TICKS = 100L;
    private static final long MAX_CONTINUOUS_GAP_TICKS = 200L;

    private UUID ownerUUID;
    private String ownerName = "";
    private String datasetId = UUID.randomUUID()
        .toString();

    private boolean showBriefEnergy = true;
    private boolean showBriefStatus = true;
    private boolean showChartEnergy = true;
    private boolean showChartStatus = true;
    private int trackingWindow = NetworkInfoDataSet.WINDOW_5_MIN;
    private int briefRatio = 28;
    private int chartLayoutMode = 0;

    private long lastSampleTick = -1L;
    private BigInteger cachedEu = BigInteger.ZERO;
    private double cachedEut = 0.0D;
    private String cachedStatus = "No data";
    private final EUDataSet eutDataSet = new EUDataSet();
    private final List<NetworkInfoSample> cachedSamples = new ArrayList<>();
    private NetworkScreen screen;
    private boolean screenInitialized = false;

    @Override
    public void updateEntity() {
        if (worldObj == null) {
            return;
        }
        if (!worldObj.isRemote) {
            if (!screenInitialized) {
                rebuildScreen();
                screenInitialized = true;
            }
            long tick = worldObj.getTotalWorldTime();
            if (ownerUUID != null && (lastSampleTick < 0L || tick - lastSampleTick >= SAMPLE_INTERVAL_TICKS)) {
                sampleNetwork(tick);
                lastSampleTick = tick;
            }
        }
    }

    public void bindOwner(UUID uuid, String name) {
        if (uuid != null && ownerUUID == null) {
            ownerUUID = uuid;
            ownerName = name == null ? "" : name;
            markDirty();
        }
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getDatasetId() {
        return datasetId;
    }

    public BigInteger getCachedEu() {
        return cachedEu;
    }

    public double getCachedEut() {
        return cachedEut;
    }

    public String getCachedStatus() {
        return cachedStatus;
    }

    public List<NetworkInfoSample> getCachedSamples() {
        return cachedSamples;
    }

    public NetworkScreen getScreen() {
        return screen;
    }

    public boolean isShowBriefEnergy() {
        return showBriefEnergy;
    }

    public boolean isShowBriefStatus() {
        return showBriefStatus;
    }

    public boolean isShowChartEnergy() {
        return showChartEnergy;
    }

    public boolean isShowChartStatus() {
        return showChartStatus;
    }

    public int getTrackingWindow() {
        return trackingWindow;
    }

    public int getBriefRatio() {
        return briefRatio;
    }

    public int getChartLayoutMode() {
        return chartLayoutMode;
    }

    public void applyConfigAction(int action) {
        switch (action) {
            case 0:
                showBriefEnergy = !showBriefEnergy;
                break;
            case 1:
                showBriefStatus = !showBriefStatus;
                break;
            case 2:
                showChartEnergy = !showChartEnergy;
                break;
            case 3:
                showChartStatus = !showChartStatus;
                break;
            case 4:
                trackingWindow = (trackingWindow + 1) % 4;
                refreshCachedSamples();
                break;
            case 5:
                briefRatio = Math.max(20, briefRatio - 5);
                break;
            case 6:
                briefRatio = Math.min(60, briefRatio + 5);
                break;
            case 7:
                chartLayoutMode = (chartLayoutMode + 1) % 2;
                break;
            default:
                return;
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void readPlacementData(NBTTagCompound tag) {
        if (tag.hasKey("DatasetId")) {
            datasetId = tag.getString("DatasetId");
        }
        if (tag.hasKey("OwnerUUID")) {
            try {
                ownerUUID = UUID.fromString(tag.getString("OwnerUUID"));
            } catch (IllegalArgumentException e) {
                ownerUUID = null;
            }
        }
        if (tag.hasKey("OwnerName")) {
            ownerName = tag.getString("OwnerName");
        }
    }

    public void writePlacementData(NBTTagCompound tag) {
        tag.setString("DatasetId", datasetId);
        if (ownerUUID != null) {
            tag.setString("OwnerUUID", ownerUUID.toString());
        }
        tag.setString("OwnerName", ownerName == null ? "" : ownerName);
    }

    private void sampleNetwork(long tick) {
        BigInteger eu = WirelessNetworkManager.getUserEU(ownerUUID);
        NetworkInfoDataStore store = NetworkInfoDataStore.get(worldObj);
        NetworkInfoDataSet dataSet = store.getOrCreate(datasetId);
        boolean coldStarting = eutDataSet.isEmpty();
        if (lastSampleTick > 0L && tick - lastSampleTick > MAX_CONTINUOUS_GAP_TICKS) {
            eutDataSet.clear();
            coldStarting = true;
        }
        cachedEu = eu == null ? BigInteger.ZERO : eu;
        eutDataSet.add(cachedEu, tick);
        cachedEut = eutDataSet.calculateEUT();
        dataSet.add(cachedEu, tick, System.currentTimeMillis(), cachedEut);
        store.markDirty();
        cachedStatus = formatStatus(cachedEut, coldStarting || eutDataSet.size() < 2, eutDataSet.isLongTermSilent());
        refreshCachedSamples(dataSet);
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    private void refreshCachedSamples() {
        if (worldObj == null || worldObj.isRemote) {
            return;
        }
        refreshCachedSamples(
            NetworkInfoDataStore.get(worldObj)
                .getOrCreate(datasetId));
    }

    private void refreshCachedSamples(NetworkInfoDataSet dataSet) {
        cachedSamples.clear();
        cachedSamples.addAll(dataSet.query(trackingWindow));
    }

    private String formatStatus(double eut, boolean coldStarting, boolean longTermSilent) {
        if (coldStarting) {
            return tr("gtswn.network_info.status.cold");
        }
        if (longTermSilent) {
            return tr("gtswn.network_info.status.longtermsilent");
        }
        if (Math.abs(eut) < 0.000001D) {
            return tr("gtswn.network_info.status.silent");
        }
        if (Math.abs(eut) < 1.0D) {
            return tr("gtswn.network_info.status.lessthan1");
        }
        String key = eut > 0 ? "gtswn.network_info.status.up" : "gtswn.network_info.status.down";
        return StatCollector.translateToLocalFormatted(
            key,
            FormatUtil.formatNormalDouble(Math.abs(eut)),
            GTTierUtil.formatGTPower(eut));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    public String getWindowName() {
        switch (trackingWindow) {
            case NetworkInfoDataSet.WINDOW_1_HOUR:
                return "1h";
            case NetworkInfoDataSet.WINDOW_8_HOUR:
                return "8h";
            case NetworkInfoDataSet.WINDOW_24_HOUR:
                return "24h";
            case NetworkInfoDataSet.WINDOW_5_MIN:
            default:
                return "5m";
        }
    }

    public void rebuildScreen() {
        int facing = getBlockMetadata();
        if (facing < 2 || facing > 5) {
            facing = 3;
        }

        Set<String> visited = new HashSet<>();
        Queue<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { xCoord, yCoord, zCoord });
        int minX = xCoord;
        int maxX = xCoord;
        int minY = yCoord;
        int maxY = yCoord;
        int minZ = zCoord;
        int maxZ = zCoord;

        while (!queue.isEmpty()) {
            int[] pos = queue.remove();
            String key = key(pos[0], pos[1], pos[2]);
            if (!visited.add(key)) {
                continue;
            }
            TileEntity tile = worldObj.getTileEntity(pos[0], pos[1], pos[2]);
            if (!isCompatibleScreenPart(tile, facing)) {
                continue;
            }
            minX = Math.min(minX, pos[0]);
            maxX = Math.max(maxX, pos[0]);
            minY = Math.min(minY, pos[1]);
            maxY = Math.max(maxY, pos[1]);
            minZ = Math.min(minZ, pos[2]);
            maxZ = Math.max(maxZ, pos[2]);
            addPlaneNeighbors(queue, pos[0], pos[1], pos[2], facing);
        }

        NetworkScreen next = new NetworkScreen();
        next.minX = minX;
        next.maxX = maxX;
        next.minY = minY;
        next.maxY = maxY;
        next.minZ = minZ;
        next.maxZ = maxZ;
        next.coreX = xCoord;
        next.coreY = yCoord;
        next.coreZ = zCoord;
        next.facing = facing;
        screen = next;

        for (String key : visited) {
            int[] pos = parseKey(key);
            TileEntity tile = worldObj.getTileEntity(pos[0], pos[1], pos[2]);
            if (tile instanceof TileEntityNetworkInfoPanelExtender) {
                ((TileEntityNetworkInfoPanelExtender) tile).attachToCore(this, next);
            }
        }
        markDirty();
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    public void detachScreen() {
        if (screen == null || worldObj == null) {
            return;
        }
        for (int x = screen.minX; x <= screen.maxX; x++) {
            for (int y = screen.minY; y <= screen.maxY; y++) {
                for (int z = screen.minZ; z <= screen.maxZ; z++) {
                    TileEntity tile = worldObj.getTileEntity(x, y, z);
                    if (tile instanceof TileEntityNetworkInfoPanelExtender) {
                        ((TileEntityNetworkInfoPanelExtender) tile).detachFromCore();
                    }
                }
            }
        }
    }

    private boolean isCompatibleScreenPart(TileEntity tile, int facing) {
        if (tile instanceof TileEntityNetworkInfoPanel) {
            return tile == this && tile.getBlockMetadata() == facing;
        }
        return tile instanceof TileEntityNetworkInfoPanelExtender && tile.getBlockMetadata() == facing;
    }

    private void addPlaneNeighbors(Queue<int[]> queue, int x, int y, int z, int facing) {
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

    public static void rebuildNearbyScreens(World world, int x, int y, int z) {
        for (int dx = -16; dx <= 16; dx++) {
            for (int dy = -16; dy <= 16; dy++) {
                for (int dz = -16; dz <= 16; dz++) {
                    TileEntity tile = world.getTileEntity(x + dx, y + dy, z + dz);
                    if (tile instanceof TileEntityNetworkInfoPanel) {
                        ((TileEntityNetworkInfoPanel) tile).rebuildScreen();
                    }
                }
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        readPlacementData(tag);
        showBriefEnergy = !tag.hasKey("showBriefEnergy") || tag.getBoolean("showBriefEnergy");
        showBriefStatus = !tag.hasKey("showBriefStatus") || tag.getBoolean("showBriefStatus");
        showChartEnergy = !tag.hasKey("showChartEnergy") || tag.getBoolean("showChartEnergy");
        showChartStatus = !tag.hasKey("showChartStatus") || tag.getBoolean("showChartStatus");
        trackingWindow = tag.getInteger("trackingWindow");
        briefRatio = tag.hasKey("briefRatio") ? tag.getInteger("briefRatio") : 28;
        chartLayoutMode = tag.getInteger("chartLayoutMode");
        lastSampleTick = tag.getLong("lastSampleTick");
        eutDataSet.loadFromNBT(tag, "eutMeasurementHistory");
        if (tag.hasKey("screen")) {
            screen = NetworkScreen.fromNBT(tag.getCompoundTag("screen"));
        }
        readSyncData(tag);
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        writePlacementData(tag);
        tag.setBoolean("showBriefEnergy", showBriefEnergy);
        tag.setBoolean("showBriefStatus", showBriefStatus);
        tag.setBoolean("showChartEnergy", showChartEnergy);
        tag.setBoolean("showChartStatus", showChartStatus);
        tag.setInteger("trackingWindow", trackingWindow);
        tag.setInteger("briefRatio", briefRatio);
        tag.setInteger("chartLayoutMode", chartLayoutMode);
        tag.setLong("lastSampleTick", lastSampleTick);
        eutDataSet.saveToNBT(tag, "eutMeasurementHistory");
        if (screen != null) {
            tag.setTag("screen", screen.toNBT());
        }
        writeSyncData(tag);
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeSyncData(tag);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readSyncData(pkt.func_148857_g());
    }

    private void writeSyncData(NBTTagCompound tag) {
        tag.setString("OwnerName", ownerName == null ? "" : ownerName);
        tag.setString("DatasetId", datasetId);
        tag.setString("cachedEu", cachedEu == null ? "0" : cachedEu.toString());
        tag.setDouble("cachedEut", cachedEut);
        tag.setString("cachedStatus", cachedStatus == null ? "" : cachedStatus);
        tag.setBoolean("showBriefEnergy", showBriefEnergy);
        tag.setBoolean("showBriefStatus", showBriefStatus);
        tag.setBoolean("showChartEnergy", showChartEnergy);
        tag.setBoolean("showChartStatus", showChartStatus);
        tag.setInteger("trackingWindow", trackingWindow);
        tag.setInteger("briefRatio", briefRatio);
        tag.setInteger("chartLayoutMode", chartLayoutMode);
        if (screen != null) {
            tag.setTag("screen", screen.toNBT());
        }
        NBTTagList list = new NBTTagList();
        for (NetworkInfoSample sample : cachedSamples) {
            list.appendTag(sample.toNBT());
        }
        tag.setTag("samples", list);
    }

    private void readSyncData(NBTTagCompound tag) {
        if (tag.hasKey("OwnerName")) {
            ownerName = tag.getString("OwnerName");
        }
        if (tag.hasKey("DatasetId")) {
            datasetId = tag.getString("DatasetId");
        }
        if (tag.hasKey("cachedEu")) {
            try {
                cachedEu = new BigInteger(tag.getString("cachedEu"));
            } catch (NumberFormatException e) {
                cachedEu = BigInteger.ZERO;
            }
        }
        cachedEut = tag.getDouble("cachedEut");
        if (tag.hasKey("cachedStatus")) {
            cachedStatus = tag.getString("cachedStatus");
        }
        showBriefEnergy = !tag.hasKey("showBriefEnergy") || tag.getBoolean("showBriefEnergy");
        showBriefStatus = !tag.hasKey("showBriefStatus") || tag.getBoolean("showBriefStatus");
        showChartEnergy = !tag.hasKey("showChartEnergy") || tag.getBoolean("showChartEnergy");
        showChartStatus = !tag.hasKey("showChartStatus") || tag.getBoolean("showChartStatus");
        trackingWindow = tag.getInteger("trackingWindow");
        briefRatio = tag.hasKey("briefRatio") ? tag.getInteger("briefRatio") : 28;
        chartLayoutMode = tag.getInteger("chartLayoutMode");
        if (tag.hasKey("screen")) {
            screen = NetworkScreen.fromNBT(tag.getCompoundTag("screen"));
        }
        cachedSamples.clear();
        NBTTagList list = tag.getTagList("samples", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.tagCount(); i++) {
            cachedSamples.add(NetworkInfoSample.fromNBT(list.getCompoundTagAt(i)));
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
