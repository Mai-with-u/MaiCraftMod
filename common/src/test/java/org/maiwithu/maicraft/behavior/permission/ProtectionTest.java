// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.behavior.worldmemory.RemembersRegions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 保护判断：记住的区域与额外保护的地标各挡各的，归属（谁放的）不拦任何动作，
 * 只经 placedByOther 给出"是谁放的"这个事实；生物有名字、被驯服、拴绳、围栏里都不碰。
 */
class ProtectionTest {

    private static final WorldPosition WALL = new WorldPosition(10, 64, 10, "minecraft:overworld");
    private static final String SELF = UUID.randomUUID().toString();
    private static final String OTHER = UUID.randomUUID().toString();

    @Test
    void 谁放的都不拦_别人放的与没记录的都不受保护() {
        var protection = protection(Map.of(WALL, OTHER), List.of(), Map.of());
        assertFalse(protection.blockProtected(WALL, null, Set.of()));
        assertFalse(protection.blockProtected(new WorldPosition(11, 64, 10, "minecraft:overworld"), null, Set.of()));
    }

    @Test
    void 记录写着谁放的_事实照它答_自己放的和没记录的给空() {
        var protection = protection(Map.of(WALL, OTHER, new WorldPosition(1, 64, 1, "minecraft:overworld"), SELF),
                List.of(), Map.of());
        assertEquals(Optional.of(OTHER), protection.placedByOther(WALL));
        assertEquals(Optional.empty(), protection.placedByOther(new WorldPosition(1, 64, 1, "minecraft:overworld")));
        assertEquals(Optional.empty(), protection.placedByOther(new WorldPosition(2, 64, 2, "minecraft:overworld")));
    }

    @Test
    void 记住区域里的格子_受保护() {
        var farm = new RememberedRegion("河东的农场", WALL, 16);
        var farAway = new WorldPosition(100_000, 64, 0, "minecraft:overworld");
        var protection = protection(Map.of(), List.of(farm), Map.of());
        assertTrue(protection.blockProtected(WALL, null, Set.of()));
        assertFalse(protection.blockProtected(farAway, null, Set.of()));
    }

    @Test
    void 额外保护地标的旁边_受保护_远处不受() {
        WorldPosition home = new WorldPosition(0, 64, 0, "minecraft:overworld");
        var protection = protection(Map.of(), List.of(), Map.of("家", home));
        assertTrue(protection.blockProtected(new WorldPosition(3, 64, 3, "minecraft:overworld"),
                null, Set.of("家")));
        assertFalse(protection.blockProtected(new WorldPosition(50, 64, 50, "minecraft:overworld"),
                null, Set.of("家")));
        // 名字不在这次任务的额外保护清单里，就不挡。
        assertFalse(protection.blockProtected(new WorldPosition(3, 64, 3, "minecraft:overworld"),
                null, Set.of()));
        assertEquals(Optional.of("家"),
                protection.protectedLandmarkHit(new WorldPosition(2, 64, 2, "minecraft:overworld"),
                        Set.of("家")));
    }

    @Test
    void 生物占上一条有主的理由_就不碰() {
        var protection = protection(Map.of(), List.of(), Map.of());
        assertFalse(protection.creatureProtected(
                new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false)));
        for (boolean[] bond : new boolean[][] {{true, false, false, false},
                {false, true, false, false}, {false, false, true, false}, {false, false, false, true}}) {
            assertTrue(protection.creatureProtected(new ReadsCreatureSituation.CreatureSituation(
                    false, false, bond[0], bond[1], bond[2], bond[3])));
        }
    }

    @Test
    void 驯服者_按角色与自家人名单分清是谁的() {
        UUID self = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        TrustedPlayers trusted = new TrustedPlayers(List.of(friend.toString()), name -> Optional.empty());
        assertEquals(ReadsCreatureSituation.TamedBy.SELF, ReadsCreatureSituation.TamedBy.of(self, self, trusted));
        assertEquals(ReadsCreatureSituation.TamedBy.TRUSTED, ReadsCreatureSituation.TamedBy.of(friend, self, trusted));
        assertEquals(ReadsCreatureSituation.TamedBy.SOMEONE_ELSE,
                ReadsCreatureSituation.TamedBy.of(UUID.randomUUID(), self, trusted));
        assertEquals(ReadsCreatureSituation.TamedBy.NOBODY, ReadsCreatureSituation.TamedBy.of(null, self, trusted));
    }

    @Test
    void 自己驯服的_用它不算别人的_伤它仍算有主的() {
        var protection = protection(Map.of(), List.of(), Map.of());
        // 自己驯服的狼起了名字、拴着绳也一样：喂它、开它的界面不用点名，打它照样要放开。
        var mine = new ReadsCreatureSituation.CreatureSituation(false, false, true, true, true, false, false,
                ReadsCreatureSituation.TamedBy.SELF);
        assertFalse(protection.creatureSomeoneElses(mine));
        assertTrue(protection.creatureProtected(mine));
        // 自家人驯服的照旧算别人的。
        var friends = new ReadsCreatureSituation.CreatureSituation(false, false, false, true, false, false, false,
                ReadsCreatureSituation.TamedBy.TRUSTED);
        assertTrue(protection.creatureSomeoneElses(friends));
        // 只说驯服了、没说是谁的：按别人的算。
        var unknown = new ReadsCreatureSituation.CreatureSituation(false, false, false, true, false, false);
        assertEquals(ReadsCreatureSituation.TamedBy.SOMEONE_ELSE, unknown.tamedBy());
        assertTrue(protection.creatureSomeoneElses(unknown));
        // 野生的、没占任何理由的，两问都不算。
        var wild = new ReadsCreatureSituation.CreatureSituation(false, false, false, false, false, false);
        assertFalse(protection.creatureSomeoneElses(wild));
        assertFalse(protection.creatureProtected(wild));
    }

    @Test
    void 存储_谁放的都能用_只有地标旁与区域里的不能用() {
        WorldPosition bobs = new WorldPosition(30, 64, 30, "minecraft:overworld");
        WorldPosition mine = new WorldPosition(31, 64, 30, "minecraft:overworld");
        var protection = protection(Map.of(WALL, OTHER, bobs, OTHER, mine, SELF), List.of(), Map.of());
        assertTrue(protection.mayUseStorage(bobs, "minecraft:chest", Set.of()), "别人放的箱子能取能存");
        assertTrue(protection.mayUseStorage(mine, "minecraft:chest", Set.of()));
        assertFalse(protection.blockProtected(bobs, "minecraft:chest", Set.of()), "别人的箱子也不拦拆");
    }

    @Test
    void 存储_记住的区域与额外保护的地标旁不能用() {
        WorldPosition wild = new WorldPosition(900, 64, 900, "minecraft:overworld");
        var regions = List.of(new RememberedRegion("河东的农场", new WorldPosition(500, 64, 500, "minecraft:overworld"), 16));
        var protection = protection(Map.of(), regions, Map.of("家", new WorldPosition(900, 64, 905, "minecraft:overworld")));
        assertTrue(protection.mayUseStorage(wild, "minecraft:chest", Set.of()), "野外没记过的箱子能用");
        assertFalse(protection.mayUseStorage(new WorldPosition(505, 64, 500, "minecraft:overworld"),
                "minecraft:chest", Set.of()), "记住的区域里的不能用");
        assertFalse(protection.mayUseStorage(wild, "minecraft:chest", Set.of("家")), "这次额外保护的地标旁不能用");
    }

    @Test
    void 存储不能用时说清是哪一路挡的() {
        WorldPosition wild = new WorldPosition(900, 64, 900, "minecraft:overworld");
        WorldPosition farm = new WorldPosition(505, 64, 500, "minecraft:overworld");
        var regions = List.of(new RememberedRegion("河东的农场", new WorldPosition(500, 64, 500, "minecraft:overworld"), 16));
        var protection = protection(Map.of(), regions, Map.of("家", new WorldPosition(900, 64, 905, "minecraft:overworld")));
        assertEquals(new StorageUse(StorageUse.Kind.IN_REMEMBERED_REGION, "河东的农场"),
                protection.storageUse(farm, "minecraft:chest", Set.of()));
        assertEquals(new StorageUse(StorageUse.Kind.NEAR_PROTECTED_LANDMARK, "家"),
                protection.storageUse(wild, "minecraft:chest", Set.of("家")));
        assertEquals(StorageUse.USABLE, protection.storageUse(wild, "minecraft:chest", Set.of()));
        assertEquals("在记住的区域「河东的农场」里",
                protection.storageUse(farm, "minecraft:chest", Set.of()).describe());
    }

    @Test
    void 被困时能挖开的_普通方块谁放的都能挖() {
        WorldPosition dirt = new WorldPosition(10, 64, 10, "minecraft:overworld");
        WorldPosition mine = new WorldPosition(11, 64, 10, "minecraft:overworld");
        WorldPosition unrecorded = new WorldPosition(12, 64, 10, "minecraft:overworld");
        var protection = protection(Map.of(dirt, OTHER, mine, SELF), List.of(), Map.of());
        // 别人放的、自己放的、没有记录的普通方块：都能挖开出去。
        assertTrue(protection.breakableWhenTrapped(dirt, false));
        assertTrue(protection.breakableWhenTrapped(mine, false));
        assertTrue(protection.breakableWhenTrapped(unrecorded, false));
        // 功能方块（箱子、门、按钮、铁轨……）：被困也不挖。
        assertFalse(protection.breakableWhenTrapped(dirt, true));
        // 归属还没问到也不拦：普通方块照挖。
        ReadsBlockOwnership unknown = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.empty();
            }

            @Override public boolean known(String dimension, int x, int y, int z) {
                return false;
            }
        };
        assertTrue(new Protection(unknown, List::of, remembered(Map.of()), SELF).breakableWhenTrapped(WALL, false));
    }

    @Test
    void 被困时也不挖_记住的区域里与记住的地点周围八格内的() {
        WorldPosition home = new WorldPosition(0, 64, 0, "minecraft:overworld");
        WorldPosition doorstep = new WorldPosition(5, 64, 5, "minecraft:overworld");
        WorldPosition field = new WorldPosition(40, 64, 40, "minecraft:overworld");
        WorldPosition farmCell = new WorldPosition(100, 64, 100, "minecraft:overworld");
        WorldPosition netherDoorstep = new WorldPosition(5, 64, 5, "minecraft:the_nether");
        var farm = new RememberedRegion("河东的农场", farmCell, 16);
        var protection = protection(Map.of(doorstep, OTHER, field, OTHER, farmCell, OTHER, netherDoorstep, OTHER),
                List.of(farm), Map.of("家", home));
        // 家门口被围也不挖（所有者 2026-10-27 定）；记住的区域里的也不挖。
        assertFalse(protection.breakableWhenTrapped(doorstep, false));
        assertFalse(protection.breakableWhenTrapped(farmCell, false));
        // 远离地点与区域的野外：能挖；别的维度里同样坐标不算在家附近。
        assertTrue(protection.breakableWhenTrapped(field, false));
        assertTrue(protection.breakableWhenTrapped(netherDoorstep, false));
    }

    @Test
    void 一片范围只看记住的地盘_别的维度不算() {
        WorldPosition crystal = new WorldPosition(42, 90, 0, "minecraft:the_end");
        var bare = protection(Map.of(), List.of(), Map.of());
        assertEquals(Optional.empty(), bare.protectedAreaNear(crystal, 12, Set.of()));
        // 主世界同一个坐标附近记过的农场、地标"家"：不在末地，不算。
        var overworldFarm = new RememberedRegion("河东的农场", new WorldPosition(40, 70, 0, "minecraft:overworld"), 16);
        var overworldHome = Map.of("家", new WorldPosition(45, 70, 0, "minecraft:overworld"));
        var elsewhere = new Protection(recorded(Map.of()), fixed(List.of(overworldFarm)),
                remembered(overworldHome), SELF);
        assertEquals(Optional.empty(), elsewhere.protectedAreaNear(crystal, 12, Set.of("家")));
        // 末地里记过的区域：地盘边缘离水晶 12 格以内就碰得到；离得更远不算。
        var nearBase = new RememberedRegion("末地小屋", new WorldPosition(60, 90, 0, "minecraft:the_end"), 8);
        var farBase = new RememberedRegion("末地远处的塔", new WorldPosition(80, 90, 0, "minecraft:the_end"), 8);
        assertEquals(Optional.of("记住的区域「末地小屋」"), new Protection(recorded(Map.of()), fixed(List.of(nearBase)),
                remembered(Map.of()), SELF).protectedAreaNear(crystal, 12, Set.of()));
        assertEquals(Optional.empty(), new Protection(recorded(Map.of()), fixed(List.of(farBase)),
                remembered(Map.of()), SELF).protectedAreaNear(crystal, 12, Set.of()));
        // 这次任务额外保护的地标：地标八格地盘与爆炸范围交叠就算；名字不在清单里不算。
        var endHome = Map.of("观景台", new WorldPosition(30, 90, 10, "minecraft:the_end"));
        var landmark = new Protection(recorded(Map.of()), List::of, remembered(endHome), SELF);
        assertEquals(Optional.of("额外保护的地标「观景台」"), landmark.protectedAreaNear(crystal, 12, Set.of("观景台")));
        assertEquals(Optional.empty(), landmark.protectedAreaNear(crystal, 12, Set.of()));
    }

    private static Protection protection(Map<WorldPosition, String> placements,
            List<RememberedRegion> regions, Map<String, WorldPosition> places) {
        return new Protection(recorded(placements), fixed(regions), remembered(places), SELF);
    }

    private static ReadsBlockOwnership recorded(Map<WorldPosition, String> placements) {
        return (dimension, x, y, z) -> {
            var hit = placements.get(new WorldPosition(x, y, z, dimension));
            return hit == null ? Optional.empty() : Optional.of(new ReadsBlockOwnership.PlacedBy(hit));
        };
    }

    private static RemembersRegions fixed(List<RememberedRegion> regions) {
        return () -> regions;
    }

    private static ReadsRememberedPlaces remembered(Map<String, WorldPosition> places) {
        return new ReadsRememberedPlaces() {
            @Override public Optional<WorldPosition> place(String name) {
                return Optional.ofNullable(places.get(name));
            }

            @Override public Map<String, WorldPosition> places() {
                return places;
            }
        };
    }
}
