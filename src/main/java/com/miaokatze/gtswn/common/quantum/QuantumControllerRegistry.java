package com.miaokatze.gtswn.common.quantum;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtswn.common.testworld.TestWorldProbes;
import com.miaokatze.gtswn.common.testworld.TestWorldProbes.Type;
import com.miaokatze.gtswn.common.util.DebugTestGate;
import com.miaokatze.gtswn.common.util.SavedDataUtil;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.core.AEConfig;
import appeng.core.features.AEFeature;
import appeng.tile.networking.TileController;

/**
 * 量子化控制器注册表（WorldSavedData，每世界一份）。
 * <p>
 * 职责（见 plan_20260722152445.md §5.3）：
 * <ul>
 * <li>记录本世界全部「已量子化」的 ME 控制器方块坐标</li>
 * <li>量子化 = 该控制器被 {@code AENetworkProxy.setValidSides} 过滤为
 * 仅允许同伴控制器 / 能量接收器 / 石英纤维朝向面连接（能量交互白名单）</li>
 * <li>perWorldStorage 每维度独立存储，NBT 结构按 §5.3 保留 dim 字段以便未来扩展</li>
 * </ul>
 * <p>
 * 坐标存储：内部用 long 打包（仿 1.8+ BlockPos 位布局），NBT 落盘为 int[N][4] = {dim, x, y, z}。
 * 量子化状态只存世界，不存终端/节点——终端 NBT 仅是绑定指针。
 */
public class QuantumControllerRegistry extends WorldSavedData {

    /** WorldSavedData 注册名（mapName），落盘文件 gtswn_quantum_controllers.dat */
    public static final String DATA_NAME = "gtswn_quantum_controllers";

    /** NBT 键名：int[N][4] 坐标数组 */
    private static final String NBT_CONTROLLERS = "controllers";

    /** 洪泛安全上限：AE2 控制器结构理论最大 7x7x7=343，留余量防异常数据死循环 */
    private static final int FLOOD_LIMIT = 512;

    /** 已量子化控制器坐标集（long 打包值） */
    private final Set<Long> controllers = new HashSet<>();

    private final Map<Long, String> identities = new HashMap<>();
    private final Map<Long, TileEntity> loadedTiles = new HashMap<>();
    private static final int ANCHOR_SCHEMA = 3;
    private final Map<Long, String> memberGenerations = new HashMap<>();
    private final Map<String, CanonicalAnchor> canonicalAnchors = new HashMap<>();
    private final Map<String, Integer> generationCounts = new HashMap<>();
    private final Map<Long, String> legacyAnchorAliases = new HashMap<>();
    /** Historical component evidence lets a removed default point find its surviving companions. */
    private final Map<String, Set<Long>> recoveryGroups = new HashMap<>();
    private final Map<String, Long> recoveryDefaults = new HashMap<>();
    private final Map<Long, String> recoveryAliases = new HashMap<>();
    private final Set<String> recoveryDirtyGenerations = new HashSet<>();
    private final Map<String, CanonicalAnchor> recoveredAnchors = new HashMap<>();
    private long recoveryRevision = -1L;
    /** Explicitly disabled structures retain physical continuity, but are never active anchors. */
    private final Set<String> suspendedGenerations = new HashSet<>();
    private final Map<Long, Integer> suspendedNativeOwners = new HashMap<>();
    private World world;

    /** 运行期结构 revision；不持久化，仅用于让统计缓存感知量子化结构变化。 */
    private long revision;

    /** 全部坐标集快照（懒构建，revision 变化时失效重建，供 tick 巡检复用）。 */
    private Set<Long> snapshot = null;

    /** 快照对应的 revision（-1 = 快照未构建/已失效）。 */
    private long snapshotRevision = -1L;

    /** Each saved graph component shares one immutable membership snapshot until the revision changes. */
    private final Map<Long, Set<Long>> structureByMember = new HashMap<>();

    private long structureRevision = -1L;

    /**
     * 本世界维度 ID（运行时字段，不持久化到独立 tag）。
     * 由 {@link #get(World)} 赋值，仅用于 writeToNBT 时按 §5.3 结构填 dim 冗余字段。
     */
    private int dimensionId = 0;

    public QuantumControllerRegistry(String name) {
        super(name);
    }

    /**
     * 获取指定世界的注册表（不存在则创建并挂载到 perWorldStorage）。
     * 模板经 {@link SavedDataUtil#loadOrCreate} 单源化（O2-15，原三份逐字符相同模板之一）。
     */
    public static QuantumControllerRegistry get(World world) {
        QuantumControllerRegistry data = SavedDataUtil
            .loadOrCreate(world, DATA_NAME, QuantumControllerRegistry.class, QuantumControllerRegistry::new);
        data.dimensionId = world.provider.dimensionId;
        data.world = world;
        return data;
    }

    // ==================== 坐标打包工具（long） ====================
    // 位布局仿 1.8+ BlockPos：x 占高 26bit | y 占中 12bit | z 占低 26bit。
    // 1.7.10 世界边界 ±30,000,000 < 2^25，26bit（含 1 符号位）足够；y ∈ [0,255]，12bit 足够。
    // 负数处理：打包时按掩码取低 N 位（补码含符号位），解包时左移把符号位顶到 long 最高位后算术右移还原。

    /** 打包方块坐标为 long */
    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | ((long) (z & 0x3FFFFFF));
    }

    /** 解包 X（算术右移自动补符号） */
    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    /** 解包 Y（先左移 26 位把符号位顶到最高位，再算术右移 52 位） */
    public static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    /** 解包 Z（先左移 38 位把符号位顶到最高位，再算术右移 38 位） */
    public static int unpackZ(long packed) {
        return (int) ((packed << 38) >> 38);
    }

    // ==================== 入册 / 出册 / 查询 ====================

    /** 入册单个坐标（已存在则无效果）；修改后标记 dirty 触发落盘 */
    public void quantize(int x, int y, int z) {
        long position = pack(x, y, z);
        quantizeAll(Collections.singleton(position), position);
    }

    /** 批量入册（打包坐标集，通常为 floodControllers 的洪泛结果） */
    public void quantizeAll(Set<Long> packedCoords) {
        Set<Long> pending = new HashSet<>(packedCoords);
        // Keep the original seed order without restarting a hash-table scan for every component.
        for (long first : new ArrayList<>(pending)) {
            if (!pending.contains(first)) continue;
            Set<Long> component = coordinateComponent(packedCoords, first);
            pending.removeAll(component);
            quantizeAll(component, first);
        }
    }

    public boolean canExtendStructure(Set<Long> members) {
        return inheritedGeneration(members, false) != null;
    }

    /** Generations are historical hints. A physically joined component selects one current default once. */
    private String inheritedGeneration(Set<Long> members, boolean explicitResume) {
        Set<String> generations = new HashSet<>();
        boolean paused = false;
        for (long member : members) {
            for (int side = -1; side < ForgeDirection.VALID_DIRECTIONS.length; side++) {
                long position = member;
                if (side >= 0) {
                    ForgeDirection direction = ForgeDirection.VALID_DIRECTIONS[side];
                    position = pack(
                        unpackX(member) + direction.offsetX,
                        unpackY(member) + direction.offsetY,
                        unpackZ(member) + direction.offsetZ);
                }
                if (!controllers.contains(position)) continue;
                String generation = memberGenerations.get(position);
                if (generation == null) continue;
                generations.add(generation);
                if (suspendedGenerations.contains(generation)) paused = true;
            }
        }
        if (!explicitResume && paused) return null;
        Map<Long, Integer> votes = QuantumNetworkColorSync.bindingVotes(dimensionId, members);
        String inherited = "";
        int most = -1;
        long selectedPosition = Long.MAX_VALUE;
        for (String generation : generations) {
            int count = 0;
            long position = Long.MAX_VALUE;
            for (long member : members) if (generation.equals(memberGenerations.get(member))) {
                count += votes.getOrDefault(member, 0);
                position = Math.min(position, member);
            }
            CanonicalAnchor canonical = canonicalAnchors.get(generation);
            if (canonical != null && generation.equals(memberGenerations.get(canonical.position)))
                position = canonical.position;
            if (count > most || (count == most && (position < selectedPosition
                || (position == selectedPosition && generation.compareTo(inherited) < 0)))) {
                inherited = generation;
                most = count;
                selectedPosition = position;
            }
        }
        return inherited;
    }

    public boolean quantizeAll(Set<Long> packedCoords, long firstClick) {
        return quantizeAll(packedCoords, firstClick, true);
    }

    public boolean quantizeAutomatically(Set<Long> packedCoords, long firstClick) {
        return quantizeAll(packedCoords, firstClick, false);
    }

    private boolean quantizeAll(Set<Long> packedCoords, long firstClick, boolean explicitResume) {
        if (packedCoords.isEmpty() || !packedCoords.contains(firstClick)
            || coordinateComponent(packedCoords, firstClick).size() != packedCoords.size()) return false;
        String generation = inheritedGeneration(packedCoords, explicitResume);
        if (generation == null) return false;
        if (suspendedGenerations.contains(generation)) {
            Set<Long> component = registeredStructure(firstSuspendedMember(packedCoords, generation));
            Set<Long> original = new HashSet<>(component);
            Set<Long> historical = recoveryGroups.get(generation);
            if (historical != null) for (long position : historical)
                if (generation.equals(memberGenerations.get(position))) original.add(position);
            // Do not mistake an unloaded remainder for a deliberate structure edit.
            for (long member : component) if (!packedCoords.contains(member)
                && (world == null || !world.blockExists(unpackX(member), unpackY(member), unpackZ(member))))
                return false;
            // Resumption is an explicit registration of the current physical component. Its coordinates,
            // not obsolete tile identities or native owners, define which saved bindings can recover.
            // Remove only physically missing members. Other detached fragments retain their paused bindings.
            boolean detached = original.size() > component.size();
            for (long member : component) if (!packedCoords.contains(member)) {
                TileEntity tile = world.getTileEntity(unpackX(member), unpackY(member), unpackZ(member));
                if (!(tile instanceof TileController) || tile.isInvalid()) dequantize(member);
                else detached = true;
            }
            if (detached) {
                String previous = generation;
                generation = UUID.randomUUID()
                    .toString();
                int moved = 0;
                for (long member : packedCoords) if (previous.equals(memberGenerations.get(member))) {
                    memberGenerations.put(member, generation);
                    if (previous.equals(legacyAnchorAliases.get(member))) legacyAnchorAliases.put(member, generation);
                    moved++;
                }
                generationCounts.put(previous, generationCounts.get(previous) - moved);
                generationCounts.put(generation, moved);
                CanonicalAnchor previousAnchor = canonicalAnchors.get(previous);
                canonicalAnchors.put(
                    generation,
                    new CanonicalAnchor(
                        previousAnchor != null && packedCoords.contains(previousAnchor.position)
                            ? previousAnchor.position
                            : electAnchor(packedCoords),
                        generation));
                recoveryDirtyGenerations.add(generation);
            }
            for (long member : packedCoords) {
                suspendedNativeOwners.remove(member);
                if (controllers.contains(member)) {
                    loadedTiles.remove(member);
                    identities.remove(member);
                    rememberIdentity(member);
                }
            }
            suspendedGenerations.remove(generation);
            CanonicalAnchor canonical = canonicalAnchors.get(generation);
            if (canonical != null && !packedCoords.contains(canonical.position)) canonicalAnchors.remove(generation);
            revision++;
            markDirty();
            QuantumTerminalSyncEpoch.bindingsChanged();
        }
        if (generation.isEmpty()) generation = UUID.randomUUID()
            .toString();
        // Explicit registration applies to the physical component, including paused losing generations.
        // Automatic placement still retains the original object/identity fence until the normal sweep.
        if (explicitResume && world != null)
            for (long member : packedCoords) if (controllers.contains(member) && !isCurrentController(world, member)
                && !refreshController(world, member)) return false;
        return quantizeAs(packedCoords, firstClick, generation, false);
    }

    private long firstSuspendedMember(Set<Long> members, String generation) {
        for (long member : members) {
            if (generation.equals(memberGenerations.get(member))) return member;
            for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
                long adjacent = pack(
                    unpackX(member) + direction.offsetX,
                    unpackY(member) + direction.offsetY,
                    unpackZ(member) + direction.offsetZ);
                if (generation.equals(memberGenerations.get(adjacent))) return adjacent;
            }
        }
        throw new IllegalStateException("Suspended generation has no candidate member");
    }

    private Integer nativeOwner(long position) {
        if (world == null || !world.blockExists(unpackX(position), unpackY(position), unpackZ(position))) return null;
        TileEntity tile = world.getTileEntity(unpackX(position), unpackY(position), unpackZ(position));
        if (!(tile instanceof TileController)) return null;
        IGridNode node = ((IGridHost) tile).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getPlayerID();
    }

    /** Disable without destroying the generation or its independently owned device references. */
    public boolean suspendStructure(long member) {
        Set<Long> members = registeredStructure(member);
        String generation = memberGenerations.get(member);
        if (members.isEmpty() || generation == null) return false;
        if (suspendedGenerations.contains(generation)) return true;
        for (long position : members) {
            if (!generation.equals(memberGenerations.get(position))) return false;
            if (world != null && !isCurrentController(world, position)) return false;
        }
        for (long position : members) {
            Integer owner = nativeOwner(position);
            if (owner != null) suspendedNativeOwners.put(position, owner);
        }
        suspendedGenerations.add(generation);
        revision++;
        markDirty();
        QuantumTerminalSyncEpoch.bindingsChanged();
        return true;
    }

    public boolean isSuspendedAnchor(long member, String expectedGeneration) {
        String generation = resolveBindingGeneration(member, expectedGeneration);
        return generation != null && suspendedGenerations.contains(generation);
    }

    public boolean isSuspendedController(long member) {
        return controllers.contains(member) && suspendedGenerations.contains(memberGenerations.get(member));
    }

    /** Explicit migration is the sole operation allowed to move a live generation's canonical position. */
    public boolean quantizeTransferred(Set<Long> members, long firstClick, String generation) {
        if (members.isEmpty() || !members.contains(firstClick) || generation == null || generation.isEmpty())
            return false;
        for (long member : members) if (controllers.contains(member)) return false;
        String inherited = inheritedGeneration(members, false);
        if (inherited == null || !inherited.isEmpty()) return false;
        return quantizeAs(members, firstClick, generation, true);
    }

    private boolean quantizeAs(Set<Long> packedCoords, long firstClick, String generation, boolean move) {
        // Expansion cannot adopt a replacement or an in-place identity mutation before the periodic sweep.
        // Check the old evidence before mutating any membership and never refresh it while adding companions.
        for (long packed : packedCoords)
            if (controllers.contains(packed) && world != null && !isCurrentController(world, packed)) return false;
        boolean coalesced = false;
        for (long packed : packedCoords) {
            String previous = memberGenerations.get(packed);
            if (previous == null || previous.equals(generation)) continue;
            memberGenerations.put(packed, generation);
            suspendedNativeOwners.remove(packed);
            if (previous.equals(legacyAnchorAliases.get(packed))) legacyAnchorAliases.put(packed, generation);
            int remaining = generationCounts.getOrDefault(previous, 1) - 1;
            if (remaining <= 0) {
                generationCounts.remove(previous);
                canonicalAnchors.remove(previous);
                suspendedGenerations.remove(previous);
            } else generationCounts.put(previous, remaining);
            generationCounts.put(generation, generationCounts.getOrDefault(generation, 0) + 1);
            coalesced = true;
        }
        Set<Long> added = new HashSet<>(packedCoords);
        added.removeAll(this.controllers);
        if (this.controllers.addAll(packedCoords) || coalesced) {
            recoveryDirtyGenerations.add(generation);
            if (move || !canonicalAnchors.containsKey(generation)) {
                long selected = move ? firstClick
                    : added.size() == packedCoords.size() ? initialAnchor(packedCoords, firstClick)
                        : electAnchor(packedCoords);
                canonicalAnchors.put(generation, new CanonicalAnchor(selected, generation));
            }
            for (long packed : packedCoords) {
                if (!memberGenerations.containsKey(packed)) {
                    memberGenerations.put(packed, generation);
                    generationCounts.put(generation, generationCounts.getOrDefault(generation, 0) + 1);
                }
            }
            for (long packed : added) rememberIdentity(packed);
            this.revision++;
            markDirty();
            QuantumTerminalSyncEpoch.bindingsChanged();
            if (DebugTestGate.probesActive()) for (long packed : added) TestWorldProbes
                .at(world, unpackX(packed), unpackY(packed), unpackZ(packed), Type.QUANTUM_CONTROLLER_ADD, "");
        }
        if (!canonicalAnchors.containsKey(generation)) {
            canonicalAnchors.put(generation, new CanonicalAnchor(electAnchor(packedCoords), generation));
            recoveryDirtyGenerations.add(generation);
            anchorChanged();
        }
        return true;
    }

    public static final class CanonicalAnchor {

        public final long position;
        public final String generation;

        private CanonicalAnchor(long position, String generation) {
            this.position = position;
            this.generation = generation;
        }
    }

    public CanonicalAnchor canonicalAnchor(long member) {
        String generation = memberGenerations.get(member);
        if (!controllers.contains(member) || suspendedGenerations.contains(generation)) return null;
        CanonicalAnchor anchor = canonicalGeneration(generation);
        if (anchor != null && !registeredStructure(member).contains(anchor.position)) {
            // A removed middle member can split one saved generation into independent structures.
            String previous = generation;
            generation = UUID.randomUUID()
                .toString();
            int moved = 0;
            for (long position : registeredStructure(member)) if (previous.equals(memberGenerations.get(position))) {
                memberGenerations.put(position, generation);
                if (previous.equals(legacyAnchorAliases.get(position))) legacyAnchorAliases.put(position, generation);
                moved++;
            }
            generationCounts.put(previous, generationCounts.getOrDefault(previous, moved) - moved);
            generationCounts.put(generation, moved);
            recoveryDirtyGenerations.add(generation);
            anchor = null;
        }
        if (anchor == null && generation != null) {
            Set<Long> members = new HashSet<>();
            for (long position : registeredStructure(member))
                if (generation.equals(memberGenerations.get(position))) members.add(position);
            anchor = new CanonicalAnchor(electAnchor(members), generation);
            canonicalAnchors.put(generation, anchor);
            recoveryDirtyGenerations.add(generation);
            anchorChanged();
        }
        return anchor;
    }

    public boolean sameGeneration(long first, long second) {
        String generation = memberGenerations.get(first);
        return generation != null && generation.equals(memberGenerations.get(second));
    }

    /** Only coordinates captured from the pre-generation save may adopt a typed migration without a generation. */
    public Set<Long> legacyMembers(Set<Long> members, String generation) {
        Set<Long> result = new HashSet<>();
        for (long member : members) if (generation.equals(legacyAnchorAliases.get(member))) result.add(member);
        return result;
    }

    /** Include historical air-coordinate aliases that currently resolve to this exact live component. */
    public Set<Long> migrationBindingMembers(Set<Long> members) {
        Set<Long> result = new HashSet<>(members);
        for (long position : recoveryAliases.keySet()) {
            CanonicalAnchor current = resolveAnchor(position, "");
            if (current != null && members.contains(current.position)) result.add(position);
        }
        return result;
    }

    /** Coalesce active member aliases without changing the caller's actual native grid guard. */
    public AnchorKey canonicalKey(AnchorKey key) {
        CanonicalAnchor anchor = canonicalAnchor(pack(key.getX(), key.getY(), key.getZ()));
        if (anchor == null || anchor.position == pack(key.getX(), key.getY(), key.getZ())) return key;
        return new AnchorKey(
            key.getDimension(),
            unpackX(anchor.position),
            unpackY(anchor.position),
            unpackZ(anchor.position));
    }

    public CanonicalAnchor resolveAnchor(long member, String expectedGeneration) {
        // A current coordinate always wins over historical component evidence.
        if (controllers.contains(member)) return canonicalAnchor(member);
        String group = recoveryAliases.get(member);
        if (group == null) return null;
        if (recoveryRevision != revision) {
            recoveredAnchors.clear();
            recoveryRevision = revision;
        }
        if (recoveredAnchors.containsKey(group)) return recoveredAnchors.get(group);
        Map<String, Integer> votes = new HashMap<>();
        Map<String, CanonicalAnchor> candidates = new HashMap<>();
        Set<Long> evidence = new HashSet<>();
        Set<String> visited = new HashSet<>();
        Queue<String> pending = new ArrayDeque<>();
        pending.add(group);
        while (!pending.isEmpty()) {
            String current = pending.remove();
            if (!visited.add(current)) continue;
            Set<Long> positions = recoveryGroups.get(current);
            if (positions == null) continue;
            for (long position : positions) if (evidence.add(position) && !controllers.contains(position)) {
                String next = recoveryAliases.get(position);
                if (next != null && !visited.contains(next)) pending.add(next);
            }
        }
        for (long position : evidence) {
            CanonicalAnchor anchor = canonicalAnchor(position);
            if (anchor != null) {
                candidates.put(anchor.generation, anchor);
                votes.put(anchor.generation, votes.getOrDefault(anchor.generation, 0) + 1);
            }
        }
        CanonicalAnchor selected = null;
        int most = -1;
        for (CanonicalAnchor anchor : candidates.values()) {
            int count = votes.get(anchor.generation);
            if (count > most || (count == most && (selected == null || anchor.position < selected.position))) {
                selected = anchor;
                most = count;
            }
        }
        recoveredAnchors.put(group, selected);
        recoveryRevision = revision;
        return selected;
    }

    private void rememberRecoveryGroup(long member) {
        String generation = memberGenerations.get(member);
        if (generation == null) return;
        Set<Long> previous = recoveryGroups.get(generation);
        // Once captured, a removal batch reuses the component evidence rather than flooding per member.
        if (previous != null && !recoveryDirtyGenerations.contains(generation)) return;
        Set<Long> members = new HashSet<>(registeredStructure(member));
        if (previous != null) members.addAll(previous);
        recoveryGroups.put(generation, Collections.unmodifiableSet(members));
        CanonicalAnchor canonical = canonicalAnchors.get(generation);
        if (canonical != null && generation.equals(memberGenerations.get(canonical.position)))
            recoveryDefaults.put(generation, canonical.position);
        for (long position : members) recoveryAliases.put(position, generation);
        recoveryDirtyGenerations.remove(generation);
    }

    private String resolveBindingGeneration(long member, String expectedGeneration) {
        String generation = memberGenerations.get(member);
        if (!controllers.contains(member) || generation == null) return null;
        // A binding is a coordinate pointer. Generation remains a typed migration hint, never a permanent
        // veto after an explicitly re-quantized controller has appeared at that same coordinate.
        return generation;
    }

    private long electAnchor(Set<Long> members) {
        return electAnchor(members, QuantumNetworkColorSync.bindingVotes(dimensionId, members));
    }

    private long initialAnchor(Set<Long> members, long firstClick) {
        Map<Long, Integer> votes = QuantumNetworkColorSync.bindingVotes(dimensionId, members);
        Long previousDefault = null;
        for (long member : members) {
            Long candidate = recoveryDefaults.get(recoveryAliases.get(member));
            if (candidate != null && members.contains(candidate)
                && (previousDefault == null || candidate < previousDefault)) previousDefault = candidate;
        }
        if (previousDefault != null) return previousDefault;
        boolean historical = !votes.isEmpty();
        for (long member : members) historical |= recoveryAliases.containsKey(member);
        return historical ? electAnchor(members, votes) : firstClick;
    }

    private void anchorChanged() {
        revision++;
        markDirty();
        QuantumTerminalSyncEpoch.bindingsChanged();
    }

    /** Elect once from indexed bindings; fixed coordinate ordering makes empty votes and ties stable. */
    static long electAnchor(Set<Long> members, Map<Long, Integer> votes) {
        long selected = 0L;
        int most = -1;
        for (long member : members) {
            int count = votes.getOrDefault(member, 0);
            if (count > most || (count == most && Long.compare(member, selected) < 0)) {
                selected = member;
                most = count;
            }
        }
        return selected;
    }

    private CanonicalAnchor canonicalGeneration(String generation) {
        CanonicalAnchor anchor = canonicalAnchors.get(generation);
        return anchor != null && !suspendedGenerations.contains(generation)
            && controllers.contains(anchor.position)
            && generation.equals(memberGenerations.get(anchor.position)) ? anchor : null;
    }

    /** 出册单个坐标；不存在则无效果 */
    public void dequantize(int x, int y, int z) {
        dequantize(pack(x, y, z));
    }

    /** 出册打包坐标（供 tick 巡检遍历打包值时直接使用） */
    public void dequantize(long packed) {
        if (controllers.contains(packed)) rememberRecoveryGroup(packed);
        if (this.controllers.remove(packed)) {
            if (world != null && !world.isRemote) QuantumNetworkSecurityRegistry.get(world)
                .remove(Collections.singleton(packed));
            identities.remove(packed);
            legacyAnchorAliases.remove(packed);
            loadedTiles.remove(packed);
            suspendedNativeOwners.remove(packed);
            String generation = memberGenerations.remove(packed);
            if (generation != null) {
                int remaining = generationCounts.getOrDefault(generation, 1) - 1;
                if (remaining <= 0) {
                    generationCounts.remove(generation);
                    canonicalAnchors.remove(generation);
                    suspendedGenerations.remove(generation);
                } else generationCounts.put(generation, remaining);
            }
            this.revision++;
            markDirty();
            QuantumTerminalSyncEpoch.bindingsChanged();
            if (DebugTestGate.probesActive()) TestWorldProbes
                .at(world, unpackX(packed), unpackY(packed), unpackZ(packed), Type.QUANTUM_CONTROLLER_REMOVE, "");
        }
    }

    /** 获取本次运行期结构 revision（不参与存档）。 */
    public long getRevision() {
        return this.revision;
    }

    /** 查询指定坐标是否已量子化 */
    public boolean isQuantized(int x, int y, int z) {
        return isQuantized(pack(x, y, z));
    }

    /** 查询打包坐标是否已量子化 */
    public boolean isQuantized(long packed) {
        return this.controllers.contains(packed) && !suspendedGenerations.contains(memberGenerations.get(packed));
    }

    /**
     * 获取全部已量子化坐标的快照（打包值集合）。
     * <p>
     * 返回副本以避免遍历期间被修改（tick 巡检中可能并发出册）。
     * v1.6.20：revision 未变化时复用上次拷贝，避免每 20t 每世界重复分配；
     * revision 变化的路径（quantize / quantizeAll / dequantize / readFromNBT）全部递增，
     * 快照惰性重建——巡检循环内的自修改只影响下一轮，与逐点拷贝语义等价。
     * <p>
     * B2-13：快照冻结为 {@link Collections#unmodifiableSet}（与 QuantumNetworkStatsCache /
     * QuantumNetworkData 缓存口径对齐）——复用窗口内任何持引用的写入都会污染共享实例，
     * 冻结后此类误用立即抛异常而非静默漂移；调用方只读遍历，零改动。
     */
    public Set<Long> getAll() {
        if (this.snapshot == null || this.snapshotRevision != this.revision) {
            Set<Long> active = new HashSet<>(controllers);
            if (!suspendedGenerations.isEmpty()) active.removeIf(this::isSuspendedController);
            this.snapshot = Collections.unmodifiableSet(active);
            this.snapshotRevision = this.revision;
        }
        return this.snapshot;
    }

    /**
     * Use the saved coordinate graph, including the missing member and unloaded chunks.
     * All members share an immutable snapshot until the registry's structural revision changes.
     */
    public Set<Long> registeredStructure(long start) {
        if (structureRevision != revision) {
            structureByMember.clear();
            structureRevision = revision;
        }
        Set<Long> cached = structureByMember.get(start);
        if (cached != null) return cached;
        if (!controllers.contains(start)) return Collections.emptySet();
        Set<Long> result = new HashSet<>();
        Queue<Long> queue = new ArrayDeque<>();
        result.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long current = queue.remove();
            for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
                long next = pack(
                    unpackX(current) + direction.offsetX,
                    unpackY(current) + direction.offsetY,
                    unpackZ(current) + direction.offsetZ);
                if (controllers.contains(next) && result.add(next)) queue.add(next);
            }
        }
        // Never mutate a published component: removal and migration retain the old snapshot.
        Set<Long> component = Collections.unmodifiableSet(result);
        for (long member : result) structureByMember.put(member, component);
        return component;
    }

    private void rememberIdentity(long position) {
        if (world == null || !world.blockExists(unpackX(position), unpackY(position), unpackZ(position))) return;
        TileEntity tile = world.getTileEntity(unpackX(position), unpackY(position), unpackZ(position));
        if (!(tile instanceof TileController) || !(tile instanceof QuantumControllerIdentity)) return;
        QuantumControllerIdentity identity = (QuantumControllerIdentity) tile;
        if (identity.gtswn$getControllerIdentity()
            .isEmpty()) {
            identity.gtswn$setControllerIdentity(
                UUID.randomUUID()
                    .toString());
            tile.markDirty();
        }
        identities.put(position, identity.gtswn$getControllerIdentity());
        loadedTiles.put(position, tile);
    }

    /** Refresh physical evidence without treating replacement/owner changes as logical network retirement. */
    public boolean refreshController(World world, long position) {
        if (!controllers.contains(position)
            || !world.blockExists(unpackX(position), unpackY(position), unpackZ(position))) return false;
        TileEntity tile = world.getTileEntity(unpackX(position), unpackY(position), unpackZ(position));
        if (!(tile instanceof TileController) || tile.isInvalid()) return false;
        this.world = world;
        identities.remove(position);
        loadedTiles.remove(position);
        rememberIdentity(position);
        anchorChanged();
        return true;
    }

    /** A missing identity in an old save is adopted once, without loading any chunks. */
    public boolean isCurrentController(World world, long position) {
        if (!controllers.contains(position)) return false;
        int x = unpackX(position), y = unpackY(position), z = unpackZ(position);
        if (!world.blockExists(x, y, z)) {
            loadedTiles.remove(position);
            return true;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileController) || tile.isInvalid()) return false;
        TileEntity previous = loadedTiles.get(position);
        if (previous != null && previous != tile) return false;
        String expected = identities.get(position);
        if (expected == null) {
            this.world = world;
            rememberIdentity(position);
            markDirty();
            return true;
        }
        if (!(tile instanceof QuantumControllerIdentity)
            || !expected.equals(((QuantumControllerIdentity) tile).gtswn$getControllerIdentity())) return false;
        loadedTiles.put(position, tile);
        return true;
    }

    public void unloadTile(TileEntity tile) {
        long position = pack(tile.xCoord, tile.yCoord, tile.zCoord);
        if (loadedTiles.get(position) == tile) loadedTiles.remove(position);
    }

    /** Late invalidation of an old object must not cancel a newly registered replacement. */
    public boolean ownsController(TileEntity tile) {
        long position = pack(tile.xCoord, tile.yCoord, tile.zCoord);
        if (!controllers.contains(position)) return false;
        TileEntity remembered = loadedTiles.get(position);
        if (remembered != null) return remembered == tile;
        String identity = identities.get(position);
        if (identity != null) return tile instanceof QuantumControllerIdentity
            && identity.equals(((QuantumControllerIdentity) tile).gtswn$getControllerIdentity());
        return world != null && world.blockExists(tile.xCoord, tile.yCoord, tile.zCoord)
            && world.getTileEntity(tile.xCoord, tile.yCoord, tile.zCoord) == tile;
    }

    public void unload() {
        loadedTiles.clear();
    }

    private static Set<Long> coordinateComponent(Set<Long> coordinates, long start) {
        Set<Long> members = new HashSet<>();
        Queue<Long> pending = new ArrayDeque<>();
        members.add(start);
        pending.add(start);
        while (!pending.isEmpty()) {
            long current = pending.remove();
            for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                long next = pack(
                    unpackX(current) + side.offsetX,
                    unpackY(current) + side.offsetY,
                    unpackZ(current) + side.offsetZ);
                if (coordinates.contains(next) && members.add(next)) pending.add(next);
            }
        }
        return members;
    }

    private void adoptLegacyAnchors() {
        Set<Long> pending = new HashSet<>(controllers);
        // Retain the initial legacy seed order while scanning the hash table only once for component seeds.
        for (long first : new ArrayList<>(pending)) {
            if (!pending.contains(first)) continue;
            Set<Long> members = coordinateComponent(controllers, first);
            pending.removeAll(members);
            String generation = UUID.randomUUID()
                .toString();
            generationCounts.put(generation, members.size());
            for (long member : members) {
                memberGenerations.put(member, generation);
                legacyAnchorAliases.put(member, generation);
            }
        }
        markDirty();
    }

    // ==================== 洪泛与频道公式（静态工具） ====================

    /**
     * 从起点 BFS 洪泛 6 邻接的全部 ME 控制器方块（D10 整结构语义）。
     *
     * @param world 世界
     * @param x     起点 X（本身必须是 TileController，否则返回空集）
     * @param y     起点 Y
     * @param z     起点 Z
     * @return 整结构控制器坐标集（打包值，含起点）
     */
    public static Set<Long> floodControllers(World world, int x, int y, int z) {
        Set<Long> visited = new HashSet<>();
        if (!world.blockExists(x, y, z)) return visited;
        TileEntity start = world.getTileEntity(x, y, z);
        if (!(start instanceof TileController)) {
            return visited;
        }
        Queue<Long> queue = new ArrayDeque<>();
        long startPacked = pack(x, y, z);
        visited.add(startPacked);
        queue.add(startPacked);
        while (!queue.isEmpty() && visited.size() < FLOOD_LIMIT) {
            long current = queue.poll();
            int cx = unpackX(current);
            int cy = unpackY(current);
            int cz = unpackZ(current);
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                int nx = cx + d.offsetX;
                int ny = cy + d.offsetY;
                int nz = cz + d.offsetZ;
                long np = pack(nx, ny, nz);
                if (visited.contains(np)) {
                    continue;
                }
                if (!world.blockExists(nx, ny, nz)) continue;
                TileEntity te = world.getTileEntity(nx, ny, nz);
                if (te instanceof TileController) {
                    visited.add(np);
                    queue.add(np);
                }
            }
        }
        return visited;
    }

    /**
     * Returns whether AE2 has disabled channel accounting globally.
     *
     * AE2's NetworkFeatures.Channels=false setting is represented by the absence
     * of AEFeature.Channels from its feature flags. If AE2 has not finished
     * initializing, retain the finite-channel behavior.
     */
    public static boolean isChannelsInfinite() {
        return AEConfig.instance != null && !AEConfig.instance.isFeatureEnabled(AEFeature.Channels);
    }

    /**
     * 计算结构总频道数（规划 §1 需求 2 / §6 公式）。
     * <p>
     * 总频道 =（控制器数量 × 6 − 与其他控制器相连的面数）× 32
     * = 控制器暴露于非控制器面的面数 × 32。
     * 实现：对每块统计 6 邻接同属集合的次数 sharedFaces——每个相邻关系恰被计 2 次
     * （每侧各 1 次），与公式中「相连面数」语义一致。
     *
     * @param structure 洪泛得到的结构坐标集（打包值）
     * @return 总频道数
     */
    public static int computeTotalChannels(Set<Long> structure) {
        int n = structure.size();
        int sharedFaces = 0;
        for (long packed : structure) {
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
                if (structure.contains(pack(x + d.offsetX, y + d.offsetY, z + d.offsetZ))) {
                    sharedFaces++;
                }
            }
        }
        return (n * 6 - sharedFaces) * 32;
    }

    // ==================== NBT 持久化（§5.3：int[N][4] = {dim, x, y, z}） ====================

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        Map<String, Set<Long>> missingCanonicals = new HashMap<>();
        Map<String, Long> missingDefaults = new HashMap<>();
        this.controllers.clear();
        this.identities.clear();
        this.loadedTiles.clear();
        memberGenerations.clear();
        canonicalAnchors.clear();
        generationCounts.clear();
        legacyAnchorAliases.clear();
        recoveryGroups.clear();
        recoveryDefaults.clear();
        recoveryAliases.clear();
        recoveryDirtyGenerations.clear();
        recoveredAnchors.clear();
        suspendedGenerations.clear();
        suspendedNativeOwners.clear();
        this.revision++;
        if (!tag.hasKey(NBT_CONTROLLERS)) {
            return;
        }
        // 扁平 int 数组：每 4 个连续 int 为一条 {dim, x, y, z}
        // （1.7.10 NBTTagList 无 get(i)/getIntArray 访问 API，故弃用 int[N][4] 的列表嵌套写法）
        int[] flat = tag.getIntArray(NBT_CONTROLLERS);
        for (int i = 0; i + 3 < flat.length; i += 4) {
            // perWorldStorage 已按维度隔离，dim（flat[i]）仅作冗余记录，读取时忽略
            this.controllers.add(pack(flat[i + 1], flat[i + 2], flat[i + 3]));
        }
        NBTTagList list = tag.getTagList("identities", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound value = list.getCompoundTagAt(i);
            long position = value.getLong("pos");
            String identity = value.getString("identity");
            if (controllers.contains(position) && !identity.isEmpty()) identities.put(position, identity);
        }
        if (tag.getInteger("anchorSchema") < 1) {
            adoptLegacyAnchors();
        } else {
            NBTTagList members = tag.getTagList("memberGenerations", 10);
            for (int i = 0; i < members.tagCount(); i++) {
                NBTTagCompound entry = members.getCompoundTagAt(i);
                long position = entry.getLong("pos");
                String generation = entry.getString("generation");
                if (controllers.contains(position) && !generation.isEmpty()) {
                    memberGenerations.put(position, generation);
                    generationCounts.put(generation, generationCounts.getOrDefault(generation, 0) + 1);
                }
            }
            NBTTagList anchors = tag.getTagList("canonicalAnchors", 10);
            for (int i = 0; i < anchors.tagCount(); i++) {
                NBTTagCompound entry = anchors.getCompoundTagAt(i);
                String generation = entry.getString("generation");
                long position = entry.getLong("pos");
                if (generation.equals(memberGenerations.get(position)))
                    canonicalAnchors.put(generation, new CanonicalAnchor(position, generation));
                else if (generationCounts.containsKey(generation)) {
                    missingCanonicals.computeIfAbsent(generation, ignored -> new HashSet<>())
                        .add(position);
                    missingDefaults.put(generation, position);
                }
            }
            NBTTagList aliases = tag.getTagList("legacyAnchorAliases", 10);
            for (int i = 0; i < aliases.tagCount(); i++) {
                NBTTagCompound entry = aliases.getCompoundTagAt(i);
                if (!entry.getString("generation")
                    .isEmpty()) legacyAnchorAliases.put(entry.getLong("pos"), entry.getString("generation"));
            }
            NBTTagList suspended = tag.getTagList("suspendedGenerations", 10);
            for (int i = 0; i < suspended.tagCount(); i++) {
                String generation = suspended.getCompoundTagAt(i)
                    .getString("generation");
                if (generationCounts.containsKey(generation)) suspendedGenerations.add(generation);
            }
            NBTTagList owners = tag.getTagList("suspendedNativeOwners", 10);
            for (int i = 0; i < owners.tagCount(); i++) {
                NBTTagCompound entry = owners.getCompoundTagAt(i);
                long position = entry.getLong("pos");
                if (suspendedGenerations.contains(memberGenerations.get(position)))
                    suspendedNativeOwners.put(position, entry.getInteger("owner"));
            }
        }
        if (tag.getInteger("anchorSchema") < 2) {
            // One saved-coordinate migration only: keep existing components/first points, repair stale aliases.
            legacyAnchorAliases.clear();
            for (long member : controllers) {
                String generation = memberGenerations.get(member);
                if (generation != null) legacyAnchorAliases.put(member, generation);
            }
            markDirty();
        }
        NBTTagList recovery = tag.getTagList("bindingRecoveryGroups", 10);
        for (int i = 0; i < recovery.tagCount(); i++) {
            NBTTagCompound entry = recovery.getCompoundTagAt(i);
            String generation = entry.getString("generation");
            Set<Long> positions = new HashSet<>();
            int[] coordinates = entry.getIntArray("members");
            for (int j = 0; j + 2 < coordinates.length; j += 3)
                positions.add(pack(coordinates[j], coordinates[j + 1], coordinates[j + 2]));
            if (!generation.isEmpty() && !positions.isEmpty())
                recoveryGroups.put(generation, Collections.unmodifiableSet(positions));
            if (recoveryGroups.containsKey(generation) && entry.hasKey("default"))
                recoveryDefaults.put(generation, entry.getLong("default"));
        }
        NBTTagList recoveryPointers = tag.getTagList("bindingRecoveryAliases", 10);
        for (int i = 0; i < recoveryPointers.tagCount(); i++) {
            NBTTagCompound entry = recoveryPointers.getCompoundTagAt(i);
            if (recoveryGroups.containsKey(entry.getString("generation")))
                recoveryAliases.put(entry.getLong("pos"), entry.getString("generation"));
        }
        // Old schema 2 could save a dead canonical while still retaining its companions. The saved
        // coordinate is evidence, even though it is no longer a valid live canonical controller.
        if (!missingCanonicals.isEmpty()) {
            for (Map.Entry<Long, String> member : memberGenerations.entrySet()) {
                Set<Long> positions = missingCanonicals.get(member.getValue());
                if (positions != null) positions.add(member.getKey());
            }
            for (Map.Entry<String, Set<Long>> group : missingCanonicals.entrySet()) {
                Set<Long> positions = new HashSet<>(group.getValue());
                Set<Long> existing = recoveryGroups.get(group.getKey());
                if (existing != null) positions.addAll(existing);
                recoveryGroups.put(group.getKey(), Collections.unmodifiableSet(positions));
                recoveryDefaults.put(group.getKey(), missingDefaults.get(group.getKey()));
                for (long position : positions) recoveryAliases.put(position, group.getKey());
            }
            markDirty();
        }
        repairSavedMembership();
        recoveryDirtyGenerations.addAll(generationCounts.keySet());
    }

    /** Repair malformed schema 1/2 records once using their saved coordinate graph, without world reads. */
    private void repairSavedMembership() {
        Set<Long> pending = new HashSet<>(controllers);
        Set<String> assigned = new HashSet<>();
        boolean changed = false;
        for (long first : new ArrayList<>(pending)) {
            if (!pending.contains(first)) continue;
            Set<Long> members = coordinateComponent(controllers, first);
            pending.removeAll(members);
            Map<String, Integer> counts = new HashMap<>();
            String established = null;
            for (long position : members) {
                String generation = memberGenerations.get(position);
                if (generation == null) continue;
                counts.put(generation, counts.getOrDefault(generation, 0) + 1);
                CanonicalAnchor anchor = canonicalAnchors.get(generation);
                if (anchor != null && members.contains(anchor.position)
                    && (established == null || generation.compareTo(established) < 0)) established = generation;
            }
            String selected = established;
            if (selected == null) {
                int most = 0;
                for (Map.Entry<String, Integer> candidate : counts.entrySet()) {
                    if (candidate.getValue() > most
                        || (candidate.getValue() == most && (selected == null || candidate.getKey()
                            .compareTo(selected) < 0))) {
                        selected = candidate.getKey();
                        most = candidate.getValue();
                    }
                }
            }
            if (selected == null) {
                selected = UUID.randomUUID()
                    .toString();
            }
            Map<String, String> replacements = new HashMap<>();
            for (String generation : counts.keySet()) {
                CanonicalAnchor anchor = canonicalAnchors.get(generation);
                if (assigned.contains(generation) || (anchor != null && !members.contains(anchor.position))) {
                    String replacement = UUID.randomUUID()
                        .toString();
                    replacements.put(generation, replacement);
                    if (suspendedGenerations.contains(generation)) suspendedGenerations.add(replacement);
                } else assigned.add(generation);
            }
            selected = replacements.getOrDefault(selected, selected);
            // Multiple valid generations can touch in old saves; loading must not authorize their merger.
            for (long position : members) {
                String previous = memberGenerations.get(position);
                String replacement = previous == null ? selected : replacements.get(previous);
                if (replacement != null) {
                    memberGenerations.put(position, replacement);
                    if (previous != null && previous.equals(legacyAnchorAliases.get(position)))
                        legacyAnchorAliases.put(position, replacement);
                    changed = true;
                }
            }
        }
        generationCounts.clear();
        for (String generation : memberGenerations.values())
            generationCounts.put(generation, generationCounts.getOrDefault(generation, 0) + 1);
        if (changed) markDirty();
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        // dim 字段按 §5.3 结构保留（perWorldStorage 已按维度隔离存储，本字段为冗余信息）
        int[] flat = new int[this.controllers.size() * 4];
        int i = 0;
        for (long packed : this.controllers) {
            flat[i++] = this.dimensionId;
            flat[i++] = unpackX(packed);
            flat[i++] = unpackY(packed);
            flat[i++] = unpackZ(packed);
        }
        tag.setIntArray(NBT_CONTROLLERS, flat);
        NBTTagList list = new NBTTagList();
        for (Map.Entry<Long, String> entry : identities.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setLong("pos", entry.getKey());
            value.setString("identity", entry.getValue());
            list.appendTag(value);
        }
        tag.setTag("identities", list);
        tag.setInteger("anchorSchema", ANCHOR_SCHEMA);
        tag.setTag("memberGenerations", generationTags(memberGenerations));
        tag.setTag("legacyAnchorAliases", generationTags(legacyAnchorAliases));
        tag.setTag("bindingRecoveryAliases", generationTags(recoveryAliases));
        NBTTagList recovery = new NBTTagList();
        for (Map.Entry<String, Set<Long>> group : recoveryGroups.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("generation", group.getKey());
            Long previousDefault = recoveryDefaults.get(group.getKey());
            if (previousDefault != null) entry.setLong("default", previousDefault);
            int[] coordinates = new int[group.getValue()
                .size() * 3];
            int index = 0;
            for (long position : group.getValue()) {
                coordinates[index++] = unpackX(position);
                coordinates[index++] = unpackY(position);
                coordinates[index++] = unpackZ(position);
            }
            entry.setIntArray("members", coordinates);
            recovery.appendTag(entry);
        }
        tag.setTag("bindingRecoveryGroups", recovery);
        NBTTagList anchors = new NBTTagList();
        for (Map.Entry<String, CanonicalAnchor> entry : canonicalAnchors.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setString("generation", entry.getKey());
            value.setLong("pos", entry.getValue().position);
            anchors.appendTag(value);
        }
        tag.setTag("canonicalAnchors", anchors);
        NBTTagList suspended = new NBTTagList();
        for (String generation : suspendedGenerations) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setString("generation", generation);
            suspended.appendTag(entry);
        }
        tag.setTag("suspendedGenerations", suspended);
        NBTTagList owners = new NBTTagList();
        for (Map.Entry<Long, Integer> owner : suspendedNativeOwners.entrySet()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("pos", owner.getKey());
            entry.setInteger("owner", owner.getValue());
            owners.appendTag(entry);
        }
        tag.setTag("suspendedNativeOwners", owners);
    }

    private static NBTTagList generationTags(Map<Long, String> values) {
        NBTTagList result = new NBTTagList();
        for (Map.Entry<Long, String> entry : values.entrySet()) {
            NBTTagCompound value = new NBTTagCompound();
            value.setLong("pos", entry.getKey());
            value.setString("generation", entry.getValue());
            result.appendTag(value);
        }
        return result;
    }

    /**
     * Destructive fixture reset only: forget removed coordinates in the explicitly cleared volume.
     * Ordinary controller removal must retain this history so unloaded bindings can recover.
     * Registered members, including suspended members, are always preserved.
     */
    public boolean forgetRemovedControllerHistory(LongPredicate clearedVolume) {
        LongPredicate removed = position -> clearedVolume.test(position) && !controllers.contains(position);
        // Compress only the alias edges being deleted, before changing any original graph evidence.
        // Outside bindings must retain their recovery routes even while the outside target is absent.
        Map<String, Set<Long>> projectedGroups = new HashMap<>();
        for (String source : recoveryGroups.keySet()) {
            Set<Long> retained = new HashSet<>();
            Set<String> visited = new HashSet<>();
            Queue<String> pending = new ArrayDeque<>();
            pending.add(source);
            while (!pending.isEmpty()) {
                String group = pending.remove();
                if (!visited.add(group)) continue;
                Set<Long> positions = recoveryGroups.get(group);
                if (positions == null) continue;
                for (long position : positions) {
                    if (!removed.test(position)) retained.add(position);
                    else {
                        String next = recoveryAliases.get(position);
                        if (next != null && !visited.contains(next)) pending.add(next);
                    }
                }
            }
            projectedGroups.put(source, retained);
        }
        boolean changed = recoveryAliases.keySet()
            .removeIf(removed::test);
        changed |= legacyAnchorAliases.keySet()
            .removeIf(removed::test);
        changed |= recoveryDefaults.values()
            .removeIf(removed::test);
        changed |= canonicalAnchors.values()
            .removeIf(anchor -> removed.test(anchor.position));
        Iterator<Map.Entry<String, Set<Long>>> groups = recoveryGroups.entrySet()
            .iterator();
        while (groups.hasNext()) {
            Map.Entry<String, Set<Long>> group = groups.next();
            Set<Long> retained = projectedGroups.get(group.getKey());
            if (retained.equals(group.getValue())) continue;
            changed = true;
            if (retained.isEmpty()) {
                groups.remove();
                recoveryDefaults.remove(group.getKey());
                recoveryDirtyGenerations.remove(group.getKey());
            } else group.setValue(Collections.unmodifiableSet(retained));
        }
        if (changed) anchorChanged();
        return changed;
    }
}
