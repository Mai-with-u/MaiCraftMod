// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/**
 * 记住本次导航在哪些面前格反复卡住。同一格第一次卡住：途经可徒手开关的门就登记切到相反状态，没有门就原样重试；
 * 第二次仍卡住：把这格（若面前挡着跨越多格的船或潜影贝等硬碰撞实体，则连同其实体碰撞箱覆盖的地面格足迹）
 * 一并列为本次导航的障碍并撤销切门登记，交给重算绕行。障碍事件数有上限，超过后由导航如实失败。
 */
final class NavigationStallMemory {
    static final int ATTEMPTS = 2;
    static final int MAX_OBSTACLES = 8;
    /** 单个挡路硬碰撞实体在面前格四周最多扩展拉黑的水平半径，防止异常超大包围盒一次封死大片地图。 */
    private static final int MAX_FOOTPRINT_RADIUS = 4;

    enum Decision { RETRY, TOGGLE_PASSAGE, OBSTACLE, EXHAUSTED }

    /**
     * 执行段在面前格卡住的一次现场。
     *
     * @param passage   这一步途经、还没登记过的那扇徒手能开关的门（下半格）；没有为 null
     * @param open      那扇门要切到的开关状态
     * @param footprint 面前挡路硬碰撞实体与面前格共同覆盖的禁入格足迹（已排除角色脚下柱列）
     * @param facts     卡住原因、动作、起止、面前格脚头两格的方块与卡了几刻，列为障碍时写进导航诊断
     */
    record Stalled(BlockPos passage, boolean open, List<BlockPos> footprint, Map<String, Object> facts) {
        Stalled {
            footprint = List.copyOf(footprint);
        }
    }

    static Stalled read(MovementStall stall, ClientLevel level, Function<BlockPos, Boolean> registered) {
        return read(stall, level, null, null, registered);
    }

    /**
     * 读一次卡住的现场：途经的门里第一扇徒手能开关、还没登记过的，切到相反状态再试；
     * 同时扫描面前格附近的硬碰撞实体，按其实体碰撞箱算出完整地面格足迹，避免宽体载具只封一格后换半格继续撞。
     */
    static Stalled read(MovementStall stall, ClientLevel level, BlockPos feet, Player viewer,
            Function<BlockPos, Boolean> registered) {
        BlockPos passage = null;
        boolean open = false;
        for (BlockPos cell : stall.passageCells()) {
            if (!level.isLoaded(cell)) continue;
            BlockState state = level.getBlockState(cell);
            if (!handOpenable(state)) continue;
            BlockPos key = MovementStall.passageKey(cell, state);
            if (registered.apply(key) != null) continue;
            passage = key;
            open = !state.getValue(BlockStateProperties.OPEN);
            break;
        }
        BlockPos front = stall.front();
        List<AABB> solidBoxes = new ArrayList<>();
        if (front != null) {
            AABB probe = new AABB(front).expandTowards(0, 1, 0).inflate(0.35);
            for (Entity entity : level.getEntities(viewer, probe, candidate -> SolidEntityObstacles.isSolidObstacle(candidate, viewer))) {
                solidBoxes.add(entity.getBoundingBox());
            }
        }
        List<BlockPos> footprint = footprintOf(front, feet, solidBoxes);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("cause", stall.cause().name().toLowerCase(Locale.ROOT));
        facts.put("movement", stall.movement());
        facts.put("from", stall.src().toShortString());
        facts.put("to", stall.dest().toShortString());
        facts.put("feet_block", level.isLoaded(front) ? level.getBlockState(front).toString() : "unloaded");
        facts.put("head_block", level.isLoaded(front.above()) ? level.getBlockState(front.above()).toString() : "unloaded");
        facts.put("stalled_ticks", stall.ticks());
        return new Stalled(passage, open, footprint, facts);
    }

    /**
     * 计算面前格及与其相交的硬碰撞实体包围盒所占的格子足迹；角色当前脚下所在柱列始终留空作脱身出口。
     */
    static List<BlockPos> footprintOf(BlockPos front, BlockPos feet, List<AABB> solidBoxes) {
        if (front == null) {
            return List.of();
        }
        var cells = new LinkedHashSet<BlockPos>();
        if (!sameColumn(front.getX(), front.getZ(), feet)) {
            cells.add(front.immutable());
        }
        AABB probe = new AABB(front).expandTowards(0, 1, 0).inflate(0.35);
        for (AABB box : solidBoxes) {
            if (box == null || !box.intersects(probe)) continue;
            int minX = Math.max(front.getX() - MAX_FOOTPRINT_RADIUS, Mth.floor(box.minX + 1e-4));
            int maxX = Math.min(front.getX() + MAX_FOOTPRINT_RADIUS, Mth.floor(box.maxX - 1e-4));
            int minZ = Math.max(front.getZ() - MAX_FOOTPRINT_RADIUS, Mth.floor(box.minZ + 1e-4));
            int maxZ = Math.min(front.getZ() + MAX_FOOTPRINT_RADIUS, Mth.floor(box.maxZ - 1e-4));
            int minY = Math.max(front.getY() - 1, Mth.floor(box.minY + 1e-4));
            int maxY = Math.min(front.getY() + 2, Mth.floor(box.maxY - 1e-4));
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (sameColumn(x, z, feet)) continue;
                    // 地面寻路节点落在面前格同层高度，连同实体实际跨越的上下层一并记入禁入格。
                    cells.add(new BlockPos(x, front.getY(), z));
                    for (int y = minY; y <= maxY; y++) {
                        cells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return List.copyOf(cells);
    }

    private static boolean sameColumn(int x, int z, BlockPos feet) {
        return feet != null && feet.getX() == x && feet.getZ() == z;
    }

    // 徒手能开关的：木门这类（铁门要红石）和栅栏门。
    private static boolean handOpenable(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
                || state.getBlock() instanceof FenceGateBlock;
    }

    private final Long2IntOpenHashMap attempts = new Long2IntOpenHashMap();
    private final LongOpenHashSet obstacles = new LongOpenHashSet();
    private final Long2BooleanOpenHashMap wantOpen = new Long2BooleanOpenHashMap();
    private final Long2LongOpenHashMap toggledFor = new Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<Map<String, Object>> toggleFacts = new Long2ObjectOpenHashMap<>();
    private final List<Map<String, Object>> toggleOrder = new ArrayList<>();
    private final List<Map<String, Object>> obstacleFacts = new ArrayList<>();

    Decision observe(BlockPos front, BlockPos passage, boolean open, Map<String, Object> facts) {
        return observe(front, List.of(front), passage, open, facts);
    }

    /**
     * 记一次卡住并给出下一步：达重试上限后把面前格及挡路实体的整个足迹一并加入禁入格，同时按一次障碍事件计入上限。
     */
    Decision observe(BlockPos front, List<BlockPos> footprint, BlockPos passage, boolean open,
            Map<String, Object> facts) {
        long cell = front.asLong();
        // 已列为障碍但新策略还没来得及装上（角色正在空中等）时，等重算绕开，不重复计数。
        if (obstacles.contains(cell)) return Decision.RETRY;
        int count = attempts.addTo(cell, 1) + 1;
        if (count < ATTEMPTS) {
            if (passage == null || wantOpen.containsKey(passage.asLong())) return Decision.RETRY;
            // 第一次卡在门前：之后这扇门只按登记状态通行，到门前先右键切换一次，不再按门板朝向推断。
            wantOpen.put(passage.asLong(), open);
            toggledFor.put(cell, passage.asLong());
            var fact = new LinkedHashMap<String, Object>();
            fact.put("passage", passage.toShortString());
            fact.put("want_open", open);
            fact.put("front", front.toShortString());
            fact.put("status", "active");
            toggleFacts.put(passage.asLong(), fact);
            toggleOrder.add(fact);
            return Decision.TOGGLE_PASSAGE;
        }
        if (obstacleFacts.size() >= MAX_OBSTACLES) return Decision.EXHAUSTED;
        obstacles.add(cell);
        for (BlockPos covered : footprint) {
            obstacles.add(covered.asLong());
        }
        // 切门后仍卡在同一格：撤销这扇门的登记，让绕行路线回到按门板朝向判断。
        if (toggledFor.containsKey(cell)) {
            long passageCell = toggledFor.remove(cell);
            wantOpen.remove(passageCell);
            toggleFacts.get(passageCell).put("status", "withdrawn_after_obstacle");
        }
        var fact = new LinkedHashMap<String, Object>();
        fact.put("cell", front.toShortString());
        fact.put("stalls", count);
        if (footprint.size() > 1) {
            fact.put("footprint_cells", footprint.size());
        }
        fact.putAll(facts);
        obstacleFacts.add(fact);
        return Decision.OBSTACLE;
    }

    /** 登记过的目标开关状态；没有登记时返回 null，仍由寻路按门板朝向判断。 */
    Boolean wantOpen(BlockPos passage) {
        long key = passage.asLong();
        return wantOpen.containsKey(key) ? wantOpen.get(key) : null;
    }

    LongSet obstacles() { return LongSets.unmodifiable(obstacles); }

    boolean isEmpty() { return toggleOrder.isEmpty() && obstacleFacts.isEmpty(); }

    List<String> obstacleCells() {
        return obstacleFacts.stream().map(fact -> String.valueOf(fact.get("cell"))).toList();
    }

    /** 把障碍格和切门登记的完整明细写进导航诊断，结果据此说明在哪、被什么挡住、做过什么。 */
    void describeInto(Map<String, Object> facts) {
        if (!obstacleFacts.isEmpty()) facts.put("stuck_obstacles", obstacleFacts.stream().map(Map::copyOf).toList());
        if (!toggleOrder.isEmpty()) facts.put("passage_toggles", toggleOrder.stream().map(Map::copyOf).toList());
    }
}
