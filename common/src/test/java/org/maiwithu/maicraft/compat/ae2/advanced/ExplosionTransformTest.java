// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.compat.ae2.advanced.ExplosionTransformPlan.DroppedCheck;
import org.maiwithu.maicraft.compat.ae2.advanced.ExplosionTransformPlan.Loadout;
import org.maiwithu.maicraft.compat.ae2.advanced.ExplosionTransformPlan.LoadoutChoice;
import org.maiwithu.maicraft.compat.ae2.advanced.ExplosionTransformPlan.PairMatch;
import org.maiwithu.maicraft.compat.ae2.advanced.ExplosionTransformPlan.SiteChoice;
import org.maiwithu.maicraft.compat.ae2.advanced.ExplosionTransformPlan.SiteProbe;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/** 奇点爆炸转化离线测试：验证迷你 TNT 优先、原版 TNT 权限门槛、保护区与机器避让选点及成对同频确认。 */
class ExplosionTransformTest {

    @Test
    void 优先选择不破坏地形的迷你TNT_仅在允许改任意方块时放行原版TNT() {
        // 同时拥有迷你 TNT 与原版 TNT：一律优先迷你 TNT。
        Map<String, Integer> both = Map.of(
                ExplosionTransformPlan.TINY_TNT_ITEM, 1,
                ExplosionTransformPlan.VANILLA_TNT_ITEM, 2,
                ExplosionTransformPlan.SINGULARITY_ITEM, 1,
                ExplosionTransformPlan.ENDER_DUST_ITEM, 1,
                "minecraft:flint_and_steel", 1);
        LoadoutChoice chosenTiny = ExplosionTransformPlan.chooseLoadout(Permissions.DEFAULT, both, item -> Set.of());
        Loadout tiny = assertInstanceOf(LoadoutChoice.Chosen.class, chosenTiny).loadout();
        assertEquals(ExplosionTransformPlan.TINY_TNT_ITEM, tiny.explosiveItem());
        assertFalse(tiny.breaksBlocks());
        assertEquals(0, tiny.missingCount());

        // 只有原版 TNT 但 change_blocks=natural（默认）：必须按 NEED_APPROVAL 拒绝。
        Map<String, Integer> vanillaOnly = Map.of(
                ExplosionTransformPlan.VANILLA_TNT_ITEM, 1,
                ExplosionTransformPlan.SINGULARITY_ITEM, 1,
                ExplosionTransformPlan.ENDER_PEARL_ITEM, 1);
        LoadoutChoice rejected = ExplosionTransformPlan.chooseLoadout(Permissions.DEFAULT, vanillaOnly, item -> Set.of());
        Problem problem = assertInstanceOf(LoadoutChoice.Rejected.class, rejected).problem();
        assertEquals(Problem.Kind.NEED_APPROVAL, problem.kind());

        // 放开 change_blocks=any 后允许使用原版 TNT。
        Permissions anyBlocks = Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.ANY, null, null, null, null, null);
        LoadoutChoice chosenVanilla = ExplosionTransformPlan.chooseLoadout(anyBlocks, vanillaOnly, item -> Set.of());
        Loadout vanilla = assertInstanceOf(LoadoutChoice.Chosen.class, chosenVanilla).loadout();
        assertEquals(ExplosionTransformPlan.VANILLA_TNT_ITEM, vanilla.explosiveItem());
        assertTrue(vanilla.breaksBlocks());

        // change_blocks=none 一律按 NEED_APPROVAL 拒绝。
        Permissions noBlocks = Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.NONE, null, null, null, null, null);
        LoadoutChoice noneChoice = ExplosionTransformPlan.chooseLoadout(noBlocks, both, item -> Set.of());
        assertEquals(Problem.Kind.NEED_APPROVAL,
                assertInstanceOf(LoadoutChoice.Rejected.class, noneChoice).problem().kind());
    }

    @Test
    void 安全选点必须避开记住的区域地标自家与机器并在空地选出相邻丢料格与撤退格() {
        RememberedRegion farm = new RememberedRegion("东区农场",
                new WorldPosition(0, 64, 0, "minecraft:overworld"), 10);
        Protection protection = new Protection(
                (dim, x, y, z) -> Optional.empty(),
                () -> List.of(farm),
                new ReadsRememberedPlaces() {
                    @Override public Optional<WorldPosition> place(String name) {
                        return "主基地".equals(name)
                                ? Optional.of(new WorldPosition(30, 64, 0, "minecraft:overworld"))
                                : Optional.empty();
                    }

                    @Override public Map<String, WorldPosition> places() {
                        return Map.of("主基地", new WorldPosition(30, 64, 0, "minecraft:overworld"));
                    }
                },
                "self-player",
                SeesVillagers.NOBODY,
                ReadsOwnHome.NOWHERE,
                id -> id);

        Permissions perms = Permissions.DEFAULT.mergedWith(null, null, null, null, null, Set.of("主基地"));
        Loadout loadout = new Loadout(ExplosionTransformPlan.TINY_TNT_ITEM, false,
                "minecraft:flint_and_steel", ExplosionTransformPlan.ENDER_DUST_ITEM, 0);

        SiteProbe openProbe = new SiteProbe() {
            @Override public boolean openGround(BlockPos cell) { return true; }
            @Override public boolean hasMachineNear(BlockPos center, int radius) {
                return center.getX() == 60;
            }
            @Override public String dimension() { return "minecraft:overworld"; }
        };

        // 候选点都在保护区与额外保护地标内时，按 NEED_APPROVAL 收场。
        SiteChoice blocked = ExplosionTransformPlan.chooseSafeSite(
                List.of(new BlockPos(5, 64, 0), new BlockPos(32, 64, 0)), loadout, perms, protection, openProbe);
        assertEquals(Problem.Kind.NEED_APPROVAL,
                assertInstanceOf(SiteChoice.Blocked.class, blocked).problem().kind());

        // 候选点 60 附近有机器应跳过，选中 100 处的安全空地；丢料格紧邻 1 格且撤退格位于安全距离外。
        SiteChoice foundChoice = ExplosionTransformPlan.chooseSafeSite(
                List.of(new BlockPos(5, 64, 0), new BlockPos(60, 64, 0), new BlockPos(100, 64, 0)),
                loadout, perms, protection, openProbe);
        SiteChoice.Found found = assertInstanceOf(SiteChoice.Found.class, foundChoice);
        assertEquals(new BlockPos(100, 64, 0), found.explosiveAt());
        assertEquals(1, found.explosiveAt().distManhattan(found.tossAt()));
        assertTrue(Math.sqrt(found.explosiveAt().distSqr(found.retreatAt())) >= loadout.retreatBlocks());
    }

    @Test
    void 严格核对成对同频量子缠绕奇点并拒绝单枚或异频() {
        // 两枚同频非零才成立。
        List<DroppedCheck> validPair = List.of(
                new DroppedCheck(1, ExplosionTransformPlan.RESULT_ITEM, 1, 7777L),
                new DroppedCheck(2, ExplosionTransformPlan.RESULT_ITEM, 1, 7777L));
        Optional<PairMatch> matched = ExplosionTransformPlan.findEntangledPair(validPair);
        assertTrue(matched.isPresent());
        assertEquals(7777L, matched.get().frequency());
        assertEquals(2, matched.get().totalCount());

        // 异频、单枚或频率 0 均不成立。
        List<DroppedCheck> mismatched = List.of(
                new DroppedCheck(1, ExplosionTransformPlan.RESULT_ITEM, 1, 1001L),
                new DroppedCheck(2, ExplosionTransformPlan.RESULT_ITEM, 1, 1002L),
                new DroppedCheck(3, ExplosionTransformPlan.RESULT_ITEM, 2, 0L));
        assertTrue(ExplosionTransformPlan.findEntangledPair(mismatched).isEmpty());

        // 拾取后前后比对背包按频率增量 >= 2 确认。
        assertEquals(Optional.of(8888L), ExplosionTransformPlan.confirmPickedPair(
                Map.of(8888L, 0), Map.of(8888L, 2)));
        assertTrue(ExplosionTransformPlan.confirmPickedPair(
                Map.of(8888L, 2), Map.of(8888L, 3)).isEmpty());
    }
}
