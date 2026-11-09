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
 * 角色自己的家：她睡的床旁、她亲手放下过的格子外扩、记住的家旁。家不再放开归属——
 * 谁放的都能动手（03 M7），家的判断只管两件事：被困在家里能不能水平拆墙出去，
 * 以及回报里"在你家…"与"是 玩家甲 放的"这两句事实。家宁可认小：维度说不清、床的归属没问到、借住的床都不算家。
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
        return new Protection(ownership, () -> regions, remembered, SELF,
                SeesVillagers.NOBODY, home, id -> OTHER.equals(id) ? "玩家甲" : id);
    }

    @Test
    void 谁放的都不拦_家里家外一样() {
        Protection protection = protection();
        assertFalse(protection.blockProtected(PLANK, null, Set.of()));
        assertFalse(protection.blockProtected(FAR, null, Set.of()));
        assertTrue(protection.mayUseStorage(CHEST, "minecraft:chest", Set.of()));
        assertTrue(protection.mayUseStorage(FAR, "minecraft:chest", Set.of()));
    }

    @Test
    void 家里的与家外的_回报里的事实体面() {
        Protection protection = protection();
        assertEquals("是 玩家甲 放的，在你家（你睡的床旁）", protection.othersAtHomeNote(PLANK));
        assertEquals("", protection.othersAtHomeNote(FAR), "家外的没那句");
    }

    @Test
    void 存储不能用时说清是哪一路_家照旧认() {
        assertEquals(StorageUse.USABLE, protection().storageUse(CHEST, "minecraft:chest", Set.of()));
        // 睡的床旁算家，回报里能带上这句。
        assertEquals(Optional.of("在你家（你睡的床旁）"), protection().homeOf(PLANK));
    }

    @Test
    void 借住别人放的床_不算自己的家() {
        placements.put(BED, OTHER);
        assertTrue(protection().homeOf(PLANK).isEmpty(), "床是别人放的：借住的不算家");
    }

    @Test
    void 床那一格归属还没问到_拿不准不当家() {
        unknown.add(BED);
        assertTrue(protection().homeOf(PLANK).isEmpty());
    }

    @Test
    void 维度说不清或对不上_不当家() {
        // 下界里和主世界的床坐标重叠的一格：不是家。
        WorldPosition nether = new WorldPosition(PLANK.x(), PLANK.y(), PLANK.z(), "minecraft:the_nether");
        placements.put(nether, OTHER);
        assertTrue(protection().homeOf(nether).isEmpty());
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
        assertEquals("是 玩家甲 放的，在你盖的「火山平原小屋」里", protection().othersAtHomeNote(PLANK));
        // 同在图纸范围里、离她放下的格 3 格的一格（地板那层）：她没放成的地方不算她家。
        WorldPosition floor = new WorldPosition(-353, 65, 54, DIM);
        placements.put(floor, OTHER);
        assertEquals("", protection().othersAtHomeNote(floor));
        // 别的维度坐标重叠的一格：不算。
        WorldPosition nether = new WorldPosition(PLANK.x(), PLANK.y(), PLANK.z(), "minecraft:the_nether");
        placements.put(nether, OTHER);
        assertEquals("", protection().othersAtHomeNote(nether));
    }

    @Test
    void 被困在家里拆墙出去_家里的普通方块能拆_功能方块记住的区域家外的不拆() {
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
        assertEquals(Optional.of("在你家（记住的家旁）"), protection().homeOf(PLANK));
    }

    @Test
    void 家里的玩家地盘与额外保护的地标照旧挡() {
        // 木板同时在记住的区域里：区域照旧拦。
        regions = List.of(new RememberedRegion("邻居的花园", PLANK, 3));
        ProtectedBy region = protection().protectedBy(PLANK, null, Set.of());
        assertEquals(ProtectedBy.Route.IN_REMEMBERED_REGION, region.route());
        assertEquals("在记住的玩家地盘「邻居的花园」里", protection().describe(region));
        regions = List.of(new RememberedRegion("邻居的花园", CHEST, 3));
        assertEquals(StorageUse.Kind.IN_REMEMBERED_REGION,
                protection().storageUse(CHEST, "minecraft:chest", Set.of()).kind());

        // 又在这次额外保护的地标旁：照旧挡，箱子也不翻。
        regions = List.of();
        places = Map.of("菜地", new WorldPosition(-352, 69, 54, DIM));
        assertEquals(ProtectedBy.Route.NEAR_PROTECTED_LANDMARK,
                protection().protectedBy(PLANK, null, Set.of("菜地")).route());
        assertEquals(StorageUse.Kind.NEAR_PROTECTED_LANDMARK,
                protection().storageUse(CHEST, "minecraft:chest", Set.of("菜地")).kind());
    }
}
