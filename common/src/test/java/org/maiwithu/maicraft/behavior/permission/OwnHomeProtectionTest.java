// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.worldmemory.RememberedRegion;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 角色自己的家里放开一层：她睡的床旁、她亲手放下过的格子外扩、记住的家旁，别人放的方块和箱子她亲手用、改都行，
 * 回报写明是谁放的；寻路顺手挖、自动挑箱子挑点仍按别人的算；记住的区域与额外保护的地标照旧挡（有归属记录也挡）；
 * 家宁可认小：维度说不清、床的归属没问到、借住的床都不算家；拒绝时说清是哪一路。
 */
class OwnHomeProtectionTest {

    private static final String DIM = "minecraft:overworld";
    private static final String SELF = "self-id";
    private static final String OTHER = "other-id";
    private static final WorldPosition BED = new WorldPosition(-351, 65, 55, DIM);
    /** 棚子北墙那格木板：离床 5 格，归属记录写着是玩家甲放的。 */
    private static final WorldPosition PLANK = new WorldPosition(-352, 70, 54, DIM);
    /** 营地那只箱子：也是玩家甲放的。 */
    private static final WorldPosition CHEST = new WorldPosition(-350, 69, 57, DIM);
    /** 离床 30 格外的一格，同样是玩家甲放的。 */
    private static final WorldPosition FAR = new WorldPosition(-320, 65, 55, DIM);

    private final Map<WorldPosition, String> placements = new HashMap<>(Map.of(PLANK, OTHER, CHEST, OTHER, FAR, OTHER));
    private Optional<WorldPosition> sleptBed = Optional.of(BED);
    private List<ReadsOwnHome.Building> buildings = List.of();
    private List<RememberedRegion> regions = List.of();
    private Map<String, WorldPosition> places = Map.of();
    /** 归属还没问到服务端的格子。 */
    private final Set<WorldPosition> unknown = new HashSet<>();

    private Protection protection() {
        ReadsOwnHome home = new ReadsOwnHome() {
            @Override public Optional<WorldPosition> sleptBed() {
                return sleptBed;
            }

            @Override public List<Building> ownBuildings() {
                return buildings;
            }
        };
        ReadsRememberedPlaces remembered = new ReadsRememberedPlaces() {
            @Override public Optional<WorldPosition> place(String name) {
                return Optional.ofNullable(places.get(name));
            }

            @Override public Map<String, WorldPosition> places() {
                return places;
            }
        };
        ReadsBlockOwnership ownership = new ReadsBlockOwnership() {
            @Override public Optional<PlacedBy> whoPlaced(String dimension, int x, int y, int z) {
                return Optional.ofNullable(placements.get(new WorldPosition(x, y, z, dimension))).map(PlacedBy::new);
            }

            @Override public boolean known(String dimension, int x, int y, int z) {
                return !unknown.contains(new WorldPosition(x, y, z, dimension));
            }
        };
        return new Protection(ownership, () -> regions, remembered, GuessesPlayerMade.NOTHING, SELF,
                TrustedPlayers.NOBODY, SeesVillagers.NOBODY, home, id -> OTHER.equals(id) ? "玩家甲" : id);
    }

    @Test
    void 拒绝时说清是哪一路_归属记录写着谁放的() {
        ProtectedBy reason = protection().protectedBy(FAR, null, Set.of());
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, reason.route());
        assertEquals("服务端归属记录写着是 玩家甲 放的", protection().describe(reason));
    }

    @Test
    void 睡的床旁别人放的木板_亲手用改都行_寻路顺手挖仍不动() {
        Protection protection = protection();
        assertEquals(ProtectedBy.NONE, protection.protectedByUnlessHome(PLANK, null, Set.of()));
        // 寻路、自动挑取材点走的是这一问：家里别人放的照旧当别人的，免得顺手拆了墙。
        assertTrue(protection.blockProtected(PLANK, null, Set.of()));
        assertEquals("是 玩家甲 放的，在你家（你睡的床旁）", protection.othersAtHomeNote(PLANK));
        // 家外的照旧挡，说清是谁放的。
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, protection.protectedByUnlessHome(FAR, null, Set.of()).route());
        assertEquals("", protection.othersAtHomeNote(FAR));
    }

    @Test
    void 从箱子里拿_家里别人放的箱子能拿_带上是谁放的() {
        StorageUse use = protection().storageUseAtHome(CHEST, "minecraft:chest", Set.of());
        assertTrue(use.usable());
        assertEquals(StorageUse.Kind.AT_HOME, use.kind());
        assertEquals("是 玩家甲 放的，在你家（你睡的床旁）", use.homeNote(id -> OTHER.equals(id) ? "玩家甲" : id));
        // 家外的箱子照旧不翻，记在谁名下。
        assertEquals(StorageUse.Kind.PLACED_BY_OTHER, protection().storageUseAtHome(FAR, "minecraft:chest", Set.of()).kind());
    }

    @Test
    void 腾背包存东西闲时开箱这些自己挑箱子的_家里也不放开() {
        assertEquals(StorageUse.Kind.PLACED_BY_OTHER, protection().storageUse(CHEST, "minecraft:chest", Set.of()).kind());
        assertFalse(protection().mayUseStorage(CHEST, "minecraft:chest", Set.of()));
    }

    @Test
    void 借住别人放的床_不算自己的家() {
        placements.put(BED, OTHER);
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, protection().protectedByUnlessHome(PLANK, null, Set.of()).route());
        assertFalse(protection().storageUseAtHome(CHEST, "minecraft:chest", Set.of()).usable());
    }

    @Test
    void 床那一格归属还没问到_拿不准不当家() {
        unknown.add(BED);
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, protection().protectedByUnlessHome(PLANK, null, Set.of()).route());
    }

    @Test
    void 维度说不清或对不上_不当家() {
        // 下界里和主世界的床坐标重叠的一格：不是家。
        WorldPosition nether = new WorldPosition(PLANK.x(), PLANK.y(), PLANK.z(), "minecraft:the_nether");
        placements.put(nether, OTHER);
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, protection().protectedByUnlessHome(nether, null, Set.of()).route());
        assertTrue(protection().homeOf(new WorldPosition(PLANK.x(), PLANK.y(), PLANK.z(), null)).isEmpty());
        // 记住的家没写维度：也不认。
        sleptBed = Optional.empty();
        places = Map.of("家", new WorldPosition(-352, 66, 55, null));
        assertTrue(protection().homeOf(PLANK).isEmpty());
    }

    @Test
    void 没睡过床_按她真放下过的格外扩两格认家_图上画了没放成的不算() {
        sleptBed = Optional.empty();
        // 她放下过的只有屋顶那一排：(-355..-351, 68, 54)。
        Set<BlockPos> placed = new HashSet<>();
        for (int x = -355; x <= -351; x++) placed.add(new BlockPos(x, 68, 54));
        buildings = List.of(new ReadsOwnHome.Building("火山平原小屋", DIM, placed));
        // 木板在屋顶上方 2 格：挨着她放下的格，算这栋的。
        assertEquals(ProtectedBy.NONE, protection().protectedByUnlessHome(PLANK, null, Set.of()));
        assertEquals("是 玩家甲 放的，在你盖的「火山平原小屋」里", protection().othersAtHomeNote(PLANK));
        // 同在图纸范围里、离她放下的格 3 格的一格（地板那层）：她没放成的地方不算她家，别人放的照旧挡。
        WorldPosition floor = new WorldPosition(-353, 65, 54, DIM);
        placements.put(floor, OTHER);
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, protection().protectedByUnlessHome(floor, null, Set.of()).route());
        // 别的维度坐标重叠的一格：不算。
        WorldPosition nether = new WorldPosition(PLANK.x(), PLANK.y(), PLANK.z(), "minecraft:the_nether");
        placements.put(nether, OTHER);
        assertEquals(ProtectedBy.Route.PLACED_BY_OTHER, protection().protectedByUnlessHome(nether, null, Set.of()).route());
    }

    @Test
    void 被困在家里拆墙出去_家里别人放的墙能拆_功能方块记住的区域家外的不拆() {
        // 床旁那格木板是玩家甲放的：被困时可以水平拆开出去（出去后补回）。
        assertTrue(protection().breakableAtHome(PLANK, false));
        assertFalse(protection().breakableAtHome(PLANK, true), "箱子、熔炉这类功能方块不拆");
        assertFalse(protection().breakableAtHome(FAR, false), "家外不按这条拆");
        regions = List.of(new RememberedRegion("邻居的花园", PLANK, 3));
        assertFalse(protection().breakableAtHome(PLANK, false), "记住的区域里的不拆");
    }

    @Test
    void 记住的家旁也算() {
        sleptBed = Optional.empty();
        places = Map.of("家", new WorldPosition(-352, 66, 55, DIM));
        assertEquals(ProtectedBy.NONE, protection().protectedByUnlessHome(PLANK, null, Set.of()));
    }

    @Test
    void 家里的玩家地盘与额外保护的地标照旧挡_有归属记录也挡() {
        // 木板的归属记录写着是玩家甲放的，同时又在记住的区域里：记录排在区域前面，放开前也得单独查区域。
        regions = List.of(new RememberedRegion("邻居的花园", PLANK, 3));
        ProtectedBy region = protection().protectedByUnlessHome(PLANK, null, Set.of());
        assertEquals(ProtectedBy.Route.IN_REMEMBERED_REGION, region.route());
        assertEquals("在记住的玩家地盘「邻居的花园」里", protection().describe(region));
        regions = List.of(new RememberedRegion("邻居的花园", CHEST, 3));
        assertEquals(StorageUse.Kind.IN_REMEMBERED_REGION, protection().storageUseAtHome(CHEST, "minecraft:chest", Set.of()).kind());

        // 同样有归属记录、又在这次额外保护的地标旁：照旧挡，箱子也不翻。
        regions = List.of();
        places = Map.of("菜地", new WorldPosition(-352, 69, 54, DIM));
        assertEquals(ProtectedBy.Route.NEAR_PROTECTED_LANDMARK,
                protection().protectedByUnlessHome(PLANK, null, Set.of("菜地")).route());
        assertEquals(StorageUse.Kind.NEAR_PROTECTED_LANDMARK,
                protection().storageUseAtHome(CHEST, "minecraft:chest", Set.of("菜地")).kind());
    }
}
