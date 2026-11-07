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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 记住本次导航在哪些面前格反复卡住。同一格第一次卡住：途经可徒手开关的门就登记切到相反状态，没有门就原样重试；
 * 第二次仍卡住：把这格列为本次导航的障碍并撤销它的切门登记，交给重算绕行。障碍数有上限，超过后由导航如实失败。
 */
final class NavigationStallMemory {
    static final int ATTEMPTS = 2;
    static final int MAX_OBSTACLES = 8;

    enum Decision { RETRY, TOGGLE_PASSAGE, OBSTACLE, EXHAUSTED }

    /**
     * 执行段在面前格卡住的一次现场。
     *
     * @param passage 这一步途经、还没登记过的那扇徒手能开关的门（下半格）；没有为 null
     * @param open    那扇门要切到的开关状态
     * @param facts   卡住原因、动作、起止、面前格脚头两格的方块与卡了几刻，列为障碍时写进导航诊断
     */
    record Stalled(BlockPos passage, boolean open, Map<String, Object> facts) {}

    /**
     * 读一次卡住的现场：途经的门里第一扇徒手能开关、还没登记过的，切到相反状态再试；再记下卡住的样子。
     *
     * @param registered 这扇门已经登记过的目标开关状态；没登记为 null
     */
    static Stalled read(MovementStall stall, ClientLevel level, Function<BlockPos, Boolean> registered) {
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
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("cause", stall.cause().name().toLowerCase(Locale.ROOT));
        facts.put("movement", stall.movement());
        facts.put("from", stall.src().toShortString());
        facts.put("to", stall.dest().toShortString());
        facts.put("feet_block", level.isLoaded(front) ? level.getBlockState(front).toString() : "unloaded");
        facts.put("head_block", level.isLoaded(front.above()) ? level.getBlockState(front.above()).toString() : "unloaded");
        facts.put("stalled_ticks", stall.ticks());
        return new Stalled(passage, open, facts);
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

    /**
     * 记一次卡住并给出下一步：passage 是这一步途经、尚未登记过的门（下半格），open 是它要切到的开关状态；没有门时传 null。
     * facts 为卡住的现场，列为障碍时原样写进导航诊断。
     */
    Decision observe(BlockPos front, BlockPos passage, boolean open, Map<String, Object> facts) {
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
        if (obstacles.size() >= MAX_OBSTACLES) return Decision.EXHAUSTED;
        obstacles.add(cell);
        // 切门后仍卡在同一格：撤销这扇门的登记，让绕行路线回到按门板朝向判断。
        if (toggledFor.containsKey(cell)) {
            long passageCell = toggledFor.remove(cell);
            wantOpen.remove(passageCell);
            toggleFacts.get(passageCell).put("status", "withdrawn_after_obstacle");
        }
        var fact = new LinkedHashMap<String, Object>();
        fact.put("cell", front.toShortString());
        fact.put("stalls", count);
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
