// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 奇点爆炸转化的纯规则判断：
 * <ul>
 * <li>仅针对 {@code ae2:quantum_entangled_singularity} 生效，单次爆炸恒产 2 枚同频缠绕奇点；</li>
 * <li>引爆件优先选用不破坏方块的 {@code ae2:tiny_tnt}；无迷你 TNT 且许可放开 {@code change_blocks=any}
 * 时才允许使用原版 {@code minecraft:tnt}；</li>
 * <li>安全选点远离记住的区域、额外保护的地标、自家建筑与机器，并逐格过 {@link PermissionCheck}；</li>
 * <li>核对爆炸后的掉落物是否产出至少 2 枚携带相同非零频率的缠绕奇点。</li>
 * </ul>
 */
public final class ExplosionTransformPlan {

    /** 奇点爆炸转化在 obtain 里的途径标识。 */
    public static final AcquireVia VIA = new AcquireVia("ae2_explosion_transform",
            "在安全空地放置迷你 TNT 并紧贴丢出奇点与末影珍珠/粉引爆，生成成对同频量子缠绕奇点");
    /** 产出物品注册 ID（每次转化恒产 2 枚同频）。 */
    public static final String RESULT_ITEM = QuantumRing.ENTANGLED_SINGULARITY;
    /** 原料：奇点。 */
    public static final String SINGULARITY_ITEM = "ae2:singularity";
    /** 原料：AE2 末影粉。 */
    public static final String ENDER_DUST_ITEM = "ae2:ender_dust";
    /** 原料：原版末影珍珠。 */
    public static final String ENDER_PEARL_ITEM = "minecraft:ender_pearl";
    /** 末影粉标签（不带 # 前缀）。 */
    public static final String ENDER_DUST_TAG = "c:dusts/ender_pearl";
    /** 末影珍珠标签（不带 # 前缀）。 */
    public static final String ENDER_PEARL_TAG = "c:ender_pearls";
    /** 优先引爆块：AE2 迷你 TNT（仅产生微型爆炸，不破坏周围建筑方块）。 */
    public static final String TINY_TNT_ITEM = "ae2:tiny_tnt";
    /** 备选引爆块：原版 TNT（仅在无迷你 TNT 且 change_blocks=any 时允许）。 */
    public static final String VANILLA_TNT_ITEM = "minecraft:tnt";
    /** 点火或红石引爆件优先顺序。 */
    public static final List<String> IGNITERS = List.of(
            "minecraft:flint_and_steel",
            "minecraft:fire_charge",
            "ae2:entropy_manipulator",
            "minecraft:redstone_torch");
    /** 单次爆炸产出的同频缠绕奇点枚数。F 游戏事实。 */
    public static final int PAIR_YIELD = 2;
    /** 迷你 TNT 保护隔离半径（格）。C 玩家常识。 */
    public static final double TINY_TNT_SAFE_RADIUS = 5.0;
    /** 原版 TNT 保护隔离半径（格）。C 玩家常识。 */
    public static final double VANILLA_TNT_SAFE_RADIUS = 10.0;
    /** 迷你 TNT 撤离安全距离（格）。C 玩家常识。 */
    public static final int TINY_TNT_RETREAT_BLOCKS = 4;
    /** 原版 TNT 撤离安全距离（格）。C 玩家常识。 */
    public static final int VANILLA_TNT_RETREAT_BLOCKS = 8;

    private ExplosionTransformPlan() {
    }

    /** 引爆方案选择结果：选用哪种 TNT、是否会炸毁方块、选用哪种点火件与哪种末影料。 */
    public record Loadout(String explosiveItem, boolean breaksBlocks, String igniterItem, String enderItem,
                          int missingCount) {
        public Loadout {
            Objects.requireNonNull(explosiveItem, "explosiveItem");
            Objects.requireNonNull(igniterItem, "igniterItem");
            Objects.requireNonNull(enderItem, "enderItem");
        }

        /** 对应引爆块的保护隔离半径（格）。 */
        public double safeRadius() {
            return breaksBlocks ? VANILLA_TNT_SAFE_RADIUS : TINY_TNT_SAFE_RADIUS;
        }

        /** 对应引爆块点燃后的撤退安全距离（格）。 */
        public int retreatBlocks() {
            return breaksBlocks ? VANILLA_TNT_RETREAT_BLOCKS : TINY_TNT_RETREAT_BLOCKS;
        }
    }

    /** 选方案的结果：成功给出方案，或因许可不够返回 NEED_APPROVAL。 */
    public sealed interface LoadoutChoice {
        /** 已定下的备料与引爆方案。 */
        record Chosen(Loadout loadout) implements LoadoutChoice {
        }

        /** 许可不允许当前引爆方式（例如只有原版 TNT 但 change_blocks 不是 any）。 */
        record Rejected(Problem problem) implements LoadoutChoice {
        }
    }

    /**
     * 挑引爆块、点火件与末影料：
     * <ul>
     * <li>放置任何 TNT 至少需要 {@code change_blocks != none}；</li>
     * <li>优先选 {@code ae2:tiny_tnt}；若身上只有 {@code minecraft:tnt}，必须 {@code change_blocks == any} 才放行，
     * 否则明确给出 {@code NEED_APPROVAL} 提示改用迷你 TNT 或放开 {@code change_blocks=any}；</li>
     * <li>两者都没有时默认规划去备不炸地形的 {@code ae2:tiny_tnt}。</li>
     * </ul>
     */
    public static LoadoutChoice chooseLoadout(Permissions permissions, Map<String, Integer> carried,
                                              Function<String, Set<String>> itemTags) {
        if (permissions.changeBlocks() == Permissions.BlockChanges.NONE) {
            return new LoadoutChoice.Rejected(Problem.of(Problem.Kind.NEED_APPROVAL,
                    "奇点爆炸转化需要放置引爆块，但当前 change_blocks=none，一格方块都不能放",
                    "把 change_blocks 提到 temporary 或更高（推荐搭配不炸地形的 ae2:tiny_tnt）"));
        }
        boolean hasTiny = carried.getOrDefault(TINY_TNT_ITEM, 0) > 0;
        boolean hasVanilla = carried.getOrDefault(VANILLA_TNT_ITEM, 0) > 0;
        String explosive;
        boolean breaksBlocks;
        if (hasTiny) {
            explosive = TINY_TNT_ITEM;
            breaksBlocks = false;
        } else if (hasVanilla) {
            if (permissions.changeBlocks() != Permissions.BlockChanges.ANY) {
                return new LoadoutChoice.Rejected(Problem.of(Problem.Kind.NEED_APPROVAL,
                        "身上没有迷你 TNT（ae2:tiny_tnt），原版 TNT 会炸毁地形，需要 change_blocks=any 才允许引爆",
                        "准备 ae2:tiny_tnt，或把本次任务的 change_blocks 设为 any"));
            }
            explosive = VANILLA_TNT_ITEM;
            breaksBlocks = true;
        } else {
            // 身上两种都没有：默认走不炸地形的迷你 TNT 备料。
            explosive = TINY_TNT_ITEM;
            breaksBlocks = false;
        }
        String igniter = chooseIgniter(carried);
        String ender = chooseEnderReagent(carried, itemTags);
        int missing = 0;
        if (carried.getOrDefault(SINGULARITY_ITEM, 0) < 1) missing++;
        if (carried.getOrDefault(ender, 0) < 1) missing++;
        if (carried.getOrDefault(explosive, 0) < 1) missing++;
        if (carried.getOrDefault(igniter, 0) < 1) missing++;
        return new LoadoutChoice.Chosen(new Loadout(explosive, breaksBlocks, igniter, ender, missing));
    }

    /** 挑一个身上已有的点火/红石脉冲件；都没有时默认备打火石。 */
    static String chooseIgniter(Map<String, Integer> carried) {
        for (String candidate : IGNITERS) {
            if (carried.getOrDefault(candidate, 0) > 0) return candidate;
        }
        return IGNITERS.getFirst();
    }

    /** 挑身上已有的末影粉或末影珍珠（按标签或注册 ID 匹配）；都没有时默认备末影珍珠。 */
    static String chooseEnderReagent(Map<String, Integer> carried, Function<String, Set<String>> itemTags) {
        if (carried.getOrDefault(ENDER_DUST_ITEM, 0) > 0) return ENDER_DUST_ITEM;
        if (carried.getOrDefault(ENDER_PEARL_ITEM, 0) > 0) return ENDER_PEARL_ITEM;
        for (Map.Entry<String, Integer> entry : carried.entrySet()) {
            if (entry.getValue() <= 0) continue;
            Set<String> tags = itemTags.apply(entry.getKey());
            if (tags.contains(ENDER_DUST_TAG) || tags.contains(ENDER_PEARL_TAG)) {
                return entry.getKey();
            }
        }
        return ENDER_PEARL_ITEM;
    }

    /** 安全选点时查询单格地形与机器占用情况的只读接口（离线测试直接传替身）。 */
    public interface SiteProbe {
        /** 这一格是不是空气/可替换且上面一格也畅通、脚下是稳固方块。 */
        boolean openGround(BlockPos cell);

        /** 以 center 为中心、半径 radius 内有没有机器/容器等方块实体。 */
        boolean hasMachineNear(BlockPos center, int radius);

        /** 这一格所在维度名。 */
        String dimension();
    }

    /** 安全选点结论：找到安全放爆点与丢料格，或因权限/保护/地形拒绝。 */
    public sealed interface SiteChoice {
        /**
         * @param explosiveAt 放置引爆块的格子
         * @param tossAt      紧贴引爆块旁、落在 ±1 格 2×2×2 盒内的丢料格
         * @param retreatAt   点燃后撤退到的爆炸安全距离外立足格
         */
        record Found(BlockPos explosiveAt, BlockPos tossAt, BlockPos retreatAt) implements SiteChoice {
        }

        /** 候选点被保护区域、地标、自家建筑或权限拦下，或附近没有安全空地。 */
        record Blocked(Problem problem) implements SiteChoice {
        }
    }

    /**
     * 在候选空地集合里挑选满足全部安全与权限条件的爆炸点：
     * <ul>
     * <li>远离记住的区域、额外保护的地标（{@link Protection#protectedAreaNear}）、自家（{@link Protection#homeOf}）
     * 与结构保护区（{@link Protection#structureBlockAt}）；</li>
     * <li>半径内没有机器/方块实体；</li>
     * <li>放爆点与邻格（原版 TNT 再校验爆破邻域）逐格通过 {@link PermissionCheck#blockAllowed}；</li>
     * <li>同平面旁边有开阔丢料格（距爆点 1 格，稳落在 ±1 格 2×2×2 盒内）以及足够远的撤退立足格。</li>
     * </ul>
     */
    public static SiteChoice chooseSafeSite(List<BlockPos> candidates, Loadout loadout, Permissions permissions,
                                            Protection protection, SiteProbe probe) {
        PermissionCheck check = new PermissionCheck(protection, id -> Optional.empty());
        Problem lastApproval = null;
        int machineRadius = (int) Math.ceil(loadout.safeRadius());
        String dimension = probe.dimension();

        for (BlockPos spot : candidates) {
            if (!probe.openGround(spot)) continue;
            WorldPosition worldPos = new WorldPosition(spot.getX(), spot.getY(), spot.getZ(), dimension);
            // 保护范围校验：记住的区域、额外保护的地标、自家与建筑/机器结构保护区全部避让。
            Optional<String> nearProtected = protection.protectedAreaNear(
                    worldPos, loadout.safeRadius(), permissions.protectedLandmarks());
            if (nearProtected.isPresent()) {
                lastApproval = Problem.of(Problem.Kind.NEED_APPROVAL,
                        spot.toShortString() + " 靠近" + nearProtected.get() + "，不允许在此引爆",
                        "走到远离保护区与地标的开阔空地再执行奇点爆炸转化");
                continue;
            }
            if (protection.homeOf(worldPos).isPresent() || protection.structureBlockAt(worldPos).protects()) {
                lastApproval = Problem.of(Problem.Kind.NEED_APPROVAL,
                        spot.toShortString() + " 位于自家、已建建筑或在役机器保护范围内，不能在此引爆",
                        "走到远离基地、建筑与机器的开阔空地再执行奇点爆炸转化");
                continue;
            }
            if (probe.hasMachineNear(spot, machineRadius)) {
                continue;
            }
            // 逐格过 PermissionCheck：放爆点必须允许放方块；原版 TNT 还校验周围 3×3 格允许破坏。
            Optional<Problem> cellRejection = checkCells(check, permissions, spot, dimension, loadout.breaksBlocks());
            if (cellRejection.isPresent()) {
                lastApproval = cellRejection.get();
                continue;
            }
            Optional<BlockPos> tossSpot = findAdjacentTossSpot(spot, probe);
            if (tossSpot.isEmpty()) continue;
            BlockPos retreatSpot = findRetreatSpot(spot, loadout.retreatBlocks(), probe);
            return new SiteChoice.Found(spot.immutable(), tossSpot.get().immutable(), retreatSpot.immutable());
        }
        if (lastApproval != null) {
            return new SiteChoice.Blocked(lastApproval);
        }
        return new SiteChoice.Blocked(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                "附近没有远离建筑、机器与保护区的安全开阔空地可供奇点爆炸转化",
                "先移动到远离基地和机器的开阔平地再试"));
    }

    /** 单个地面掉落物的检验视图（实体编号、物品 ID、数量与奇点频率）。 */
    public record DroppedCheck(int entityId, String itemId, int count, long frequency) {
    }

    /** 成对同频确认结论。 */
    public record PairMatch(long frequency, List<Integer> entityIds, int totalCount) {
        public PairMatch {
            entityIds = List.copyOf(entityIds);
        }
    }

    /**
     * 在爆炸后的新掉落物里核对是否已产出成对同频的 {@code ae2:quantum_entangled_singularity}：
     * 必须至少有 2 枚且携带相同的非零 {@code ENTANGLED_SINGULARITY_ID}。
     */
    public static Optional<PairMatch> findEntangledPair(List<DroppedCheck> drops) {
        Map<Long, List<DroppedCheck>> byFrequency = new LinkedHashMap<>();
        for (DroppedCheck drop : drops) {
            if (!RESULT_ITEM.equals(drop.itemId()) || drop.frequency() == 0L || drop.count() <= 0) continue;
            byFrequency.computeIfAbsent(drop.frequency(), ignored -> new ArrayList<>()).add(drop);
        }
        for (Map.Entry<Long, List<DroppedCheck>> entry : byFrequency.entrySet()) {
            int total = 0;
            List<Integer> ids = new ArrayList<>();
            for (DroppedCheck drop : entry.getValue()) {
                total += drop.count();
                ids.add(drop.entityId());
            }
            if (total >= PAIR_YIELD) {
                return Optional.of(new PairMatch(entry.getKey(), ids, total));
            }
        }
        return Optional.empty();
    }

    /** 核对两组掉落物或背包快照之间是否至少新增了 2 枚同频缠绕奇点。 */
    public static Optional<Long> confirmPickedPair(Map<Long, Integer> beforeByFreq, Map<Long, Integer> afterByFreq) {
        for (Map.Entry<Long, Integer> entry : afterByFreq.entrySet()) {
            long freq = entry.getKey();
            if (freq == 0L) continue;
            int delta = entry.getValue() - beforeByFreq.getOrDefault(freq, 0);
            if (delta >= PAIR_YIELD) {
                return Optional.of(freq);
            }
        }
        return Optional.empty();
    }

    /** 核对引爆前奇点与末影珍珠/粉两样原料都已确认丢出，防止缺一料误引爆烧毁奇点。 */
    public static boolean allReagentsThrown(Map<String, Integer> thrown, String enderItem) {
        if (thrown == null || enderItem == null) return false;
        return thrown.getOrDefault(SINGULARITY_ITEM, 0) >= 1
                && thrown.getOrDefault(enderItem, 0) >= 1;
    }

    private static Optional<Problem> checkCells(PermissionCheck check, Permissions permissions, BlockPos center,
                                                String dimension, boolean breaksBlocks) {
        WorldPosition centerPos = new WorldPosition(center.getX(), center.getY(), center.getZ(), dimension);
        Optional<Problem> placeCheck = check.blockAllowed(permissions, PermissionCheck.WorldAction.PLACE_BLOCK,
                centerPos, null);
        if (placeCheck.isPresent()) return placeCheck;
        if (!breaksBlocks) return Optional.empty();
        // 原版 TNT 会破坏周围方块：逐格检查紧邻 3×3 范围是否允许破坏。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                WorldPosition neighbor = new WorldPosition(center.getX() + dx, center.getY(), center.getZ() + dz,
                        dimension);
                Optional<Problem> digCheck = check.blockAllowed(permissions, PermissionCheck.WorldAction.DIG_BLOCK,
                        neighbor, null);
                if (digCheck.isPresent()) return digCheck;
            }
        }
        return Optional.empty();
    }

    // 紧贴引爆块四周 1 格找一块畅通平地，确保丢下的奇点与末影料稳落在 ±1 格 2×2×2 爆炸扫描盒内。
    private static Optional<BlockPos> findAdjacentTossSpot(BlockPos center, SiteProbe probe) {
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : offsets) {
            BlockPos adj = center.offset(offset[0], 0, offset[1]);
            if (probe.openGround(adj)) return Optional.of(adj);
        }
        return Optional.empty();
    }

    // 选一格距离爆点至少 retreatBlocks 格的开阔点作为点火后的撤退点；找不到理想平地时退回水平偏移点。
    private static BlockPos findRetreatSpot(BlockPos center, int retreatBlocks, SiteProbe probe) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] dir : dirs) {
            BlockPos spot = center.offset(dir[0] * retreatBlocks, 0, dir[1] * retreatBlocks);
            if (probe.openGround(spot)) return spot;
        }
        return center.offset(retreatBlocks, 0, 0);
    }

    /** 围绕中心按由近到远生成水平搜索候选格（给客户端选点扫描复用）。 */
    public static List<BlockPos> spiralCandidates(BlockPos origin, int minRadius, int maxRadius) {
        List<BlockPos> out = new ArrayList<>();
        for (int radius = Math.max(0, minRadius); radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    out.add(origin.offset(dx, 0, dz));
                }
            }
        }
        return out;
    }

    /** 判定物品是否满足末影珍珠/粉原料条件（供测试与选料共用）。 */
    public static boolean isEnderReagent(String itemId, Predicate<String> hasTag) {
        return ENDER_DUST_ITEM.equals(itemId) || ENDER_PEARL_ITEM.equals(itemId)
                || hasTag.test(ENDER_DUST_TAG) || hasTag.test(ENDER_PEARL_TAG);
    }
}
