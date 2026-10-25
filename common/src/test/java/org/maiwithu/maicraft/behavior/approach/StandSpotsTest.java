// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 站位判断：按能到、够得着、看得见、站得稳筛候选并排序；被拒的位置记着败在哪一项。 */
class StandSpotsTest {

    private static final ReachRules REACH = new ReachRules(4.5, 3.0, 3, 1.62);

    private final StubWorld world = new StubWorld();
    private final StubWalkCost walking = new StubWalkCost();
    private final StubGuarded guarded = new StubGuarded();

    private StandSpots.Ranking find(ApproachTarget target) {
        return StandSpots.find(target, REACH, world, walking, guarded);
    }

    @Test
    void 平地上围着目标找出一圈能用的站位并按代价排好序() {
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertFalse(ranking.spots().isEmpty());
        // 排序：同一可达档里前面的代价不比后面的大，同代价的按格子位置排，保证顺序稳定；
        // 高出不止一跳的候选整档排在后面，不与同层的比代价（见塔顶候选的排序用例）。
        for (int i = 1; i < ranking.spots().size(); i++) {
            StandSpot previous = ranking.spots().get(i - 1);
            StandSpot following = ranking.spots().get(i);
            if (StandSpots.climbTier(previous.feet(), world.feet)
                    == StandSpots.climbTier(following.feet(), world.feet)) {
                assertTrue(previous.compareTo(following) <= 0);
            }
        }
        // 目标紧邻的位置应该出现：贴着方块才能对它动手。
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(2, 64, 0))));
    }

    @Test
    void 高出不止一跳的塔顶候选排在同层候选之后() {
        // 高塔立在目标方块边上，角色在塔脚：塔身半腰的合格候选（脚下实心、头顶开阔、够得着高塔）
        // 按直线代价比几格外的同层候选便宜，但比角色高出一跳以上，要走过去常常没有梯级——
        // 可达档把它排到同层候选之后，不按代价抢在前面。
        world.feet = new BlockPos(3, 68, 0);
        ApproachTarget 高塔 = ApproachTarget.ofEntity(new AABB(3, 64, 0, 4, 80, 1));
        StandSpots.Ranking ranking = StandSpots.find(高塔, REACH, world, walking, guarded);
        BlockPos 塔身半腰 = new BlockPos(3, 70, 0);
        BlockPos 同层远格 = new BlockPos(6, 69, 0);
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(塔身半腰)),
                "塔身上脚位实心、头顶开阔，是合格候选");
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(同层远格)));
        assertEquals(1, StandSpots.climbTier(塔身半腰, world.feet), "塔身半腰比脚底高出两格，在要爬的档");
        assertEquals(0, StandSpots.climbTier(同层远格, world.feet));
        assertTrue(ranking.spots().indexOf(ranking.spots().stream()
                        .filter(spot -> spot.feet().equals(塔身半腰)).findFirst().orElseThrow())
                > ranking.spots().indexOf(ranking.spots().stream()
                        .filter(spot -> spot.feet().equals(同层远格)).findFirst().orElseThrow()),
                "要爬的候选排在同层候选之后，尽管它离脚底更近");
    }

    @Test
    void 脚下悬空和头顶没空间的格子被拒() {
        world.noFloor.add(new BlockPos(2, 64, 0));
        world.lowCeiling.add(new BlockPos(2, 64, 1));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("脚下悬空", failureAt(ranking, new BlockPos(2, 64, 0)));
        assertEquals("头顶没空间", failureAt(ranking, new BlockPos(2, 64, 1)));
    }

    @Test
    void 深落差液体岩浆和受保护的格子都不站() {
        world.deepDrops.add(new BlockPos(2, 64, 0));
        world.fluids.add(new BlockPos(2, 64, 1));
        world.lavas.add(new BlockPos(2, 64, -1));
        guarded.add(new BlockPos(2, 63, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("落差太深", failureAt(ranking, new BlockPos(2, 64, 0)));
        assertEquals("泡在液体里", failureAt(ranking, new BlockPos(2, 64, 1)));
        assertEquals("旁边有岩浆", failureAt(ranking, new BlockPos(2, 64, -1)));
        assertEquals("受保护", failureAt(ranking, new BlockPos(2, 63, 0)));
    }

    @Test
    void 超出方块交互距离的格子记为够不着() {
        // 交互距离几乎为零，目标块又不能站进去：所有候选不是够不着就是进不去。
        world.lowCeiling.add(new BlockPos(0, 64, 5));
        world.lowCeiling.add(new BlockPos(0, 63, 5));
        ReachRules 近视 = new ReachRules(0.3, 3.0, 3, 1.62);
        StandSpots.Ranking ranking = StandSpots.find(
                ApproachTarget.ofBlock(new BlockPos(0, 64, 5)), 近视, world, walking, guarded);
        assertTrue(ranking.spots().isEmpty());
        assertFalse(ranking.rejected().isEmpty());
        assertEquals("够不着", failureAt(ranking, new BlockPos(0, 64, 4)));
    }

    @Test
    void 床按服务端距离分轴卡() {
        ApproachTarget bed = ApproachTarget.ofBed(new BlockPos(0, 60, 0));
        StandSpots.Ranking ranking = find(bed);
        // 横向差四格超出床距离档；横向差两格、高度差两格走到格子哪一边都在服务端允许的范围里。
        assertEquals("够不着", failureAt(ranking, new BlockPos(4, 60, 0)));
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(2, 62, 0))));
        // 高度差三格超出床距离档；候选只在目标上下两格内找，直接核对站在那里行不行。
        world.feet = new BlockPos(0, 63, 0);
        assertEquals(Optional.of("够不着"), StandSpots.checkArrival(bed, REACH, world, guarded));
    }

    @Test
    void 离床三格的那一圈不当候选_走进去停在外沿就点不着床() {
        // 实机 10-15：从远处走来，站进离床三格的那一格就停在进格子那一侧的边上，离床底面中心三格多，
        // 点床被原版以"床太远了"拒绝。挑候选时这一圈不要，横向两格以内的才要。
        ApproachTarget bed = ApproachTarget.ofBed(new BlockPos(0, 60, 0));
        StandSpots.Ranking ranking = find(bed);
        assertEquals("够不着", failureAt(ranking, new BlockPos(3, 60, 0)));
        assertEquals("够不着", failureAt(ranking, new BlockPos(0, 60, -3)));
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(2, 60, 2))));
    }

    @Test
    void 已经站着时按人的精确位置核对床距() {
        ApproachTarget bed = ApproachTarget.ofBed(new BlockPos(0, 60, 0));
        world.feet = new BlockPos(3, 60, 0);
        // 站在离床三格那一格靠床的一边：离床底面中心 2.8 格，原版让睡。
        world.position = new Vec3(3.3, 60, 0.5);
        assertEquals(Optional.empty(), StandSpots.checkArrival(bed, REACH, world, guarded));
        // 同一格靠外的一边：离床底面中心 3.4 格，原版说床太远了，这里也不能当成到了。
        world.position = new Vec3(3.9, 60, 0.5);
        assertEquals(Optional.of("够不着"), StandSpots.checkArrival(bed, REACH, world, guarded));
    }

    @Test
    void 实体按实体交互距离卡() {
        ApproachTarget entity = ApproachTarget.ofEntity(new AABB(4, 64, 0, 5, 66, 1));
        StandSpots.Ranking ranking = StandSpots.find(entity, REACH, world, walking, guarded);
        // 平地上围着实体的一圈都在三格实体距离内，找得到能用的站位，先走代价最小的。
        assertFalse(ranking.spots().isEmpty());
        assertEquals(1.0, ranking.spots().getFirst().walkCost());
        // 离实体十格的角色：实体距离够不够与角色站多远无关，但代价排序让它先走身边的候选。
        world.feet = new BlockPos(14, 64, 0);
        StandSpots.Ranking far = StandSpots.find(entity, REACH, world, walking, guarded);
        assertFalse(far.spots().isEmpty());
        assertEquals(7.0, far.spots().getFirst().walkCost());
        // 实体距离收得极紧时一个候选都留不下；把身体能探进实体的三格头顶封住，免得替身里站进实体。
        world.lowCeiling.add(new BlockPos(4, 62, 0));
        world.lowCeiling.add(new BlockPos(4, 63, 0));
        world.lowCeiling.add(new BlockPos(4, 64, 0));
        ReachRules 贴脸 = new ReachRules(4.5, 0.4, 3, 1.62);
        assertTrue(StandSpots.find(entity, 贴脸, world, walking, guarded).spots().isEmpty());
    }

    @Test
    void 射线被挡住的格子记为看不见() {
        // 半边天被墙挡住：眼睛在挡板西侧的候选都看不见。
        world.hidden = (eye, target) -> eye.x() < 1.5;
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("看不见", failureAt(ranking, new BlockPos(0, 64, 0)));
        assertTrue(ranking.spots().stream().allMatch(spot -> spot.feet().getX() >= 1));
    }

    @Test
    void 写告示牌时正面那一侧的候选留下背面的记为背面() {
        // 面朝南的告示牌（原版水平朝向角 0，南边是正面）：正面的候选留下，正对背面的按"背面"拒掉。
        ApproachTarget sign = ApproachTarget.onFrontSideOf(new BlockPos(3, 64, 0), 0.0);
        StandSpots.Ranking ranking = find(sign);
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(3, 64, 1))));
        assertEquals("背面", failureAt(ranking, new BlockPos(3, 64, -1)));
        // 斜着放的告示牌按原版同一个方位角式子判：朝向角 45 时西南是正面，东北那一侧是背面。
        StandSpots.Ranking tilted = find(ApproachTarget.onFrontSideOf(new BlockPos(3, 64, 0), 45.0));
        assertTrue(tilted.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(4, 64, 4))));
        assertEquals("背面", failureAt(tilted, new BlockPos(1, 64, -3)));
        // 人已经站在背面时到点核对也不过：报"背面"，由靠近动作换到正面那一侧的站位。
        world.feet = new BlockPos(3, 64, -1);
        assertEquals(Optional.of("背面"), StandSpots.checkArrival(sign, REACH, world, guarded));
    }

    @Test
    void 寻路回答走不过去的格子记为到不了() {
        walking.unreachable.add(new BlockPos(2, 64, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("到不了", failureAt(ranking, new BlockPos(2, 64, 0)));
    }

    @Test
    void 到了之后四下核对() {
        ApproachTarget target = ApproachTarget.ofBlock(new BlockPos(3, 64, 0));
        assertEquals(Optional.empty(), StandSpots.checkArrival(target, REACH, world, guarded));
        // 离目标太远：够不着。
        world.feet = new BlockPos(20, 64, 0);
        assertEquals(Optional.of("够不着"), StandSpots.checkArrival(target, REACH, world, guarded));
        // 站对了位置但被挡住：看不见。
        world.feet = new BlockPos(2, 64, 0);
        world.hidden = (eye, t) -> true;
        assertEquals(Optional.of("看不见"), StandSpots.checkArrival(target, REACH, world, guarded));
        // 还在跳跃中：没落地。
        world.hidden = (eye, t) -> false;
        world.grounded = false;
        assertEquals(Optional.of("还没落地"), StandSpots.checkArrival(target, REACH, world, guarded));
    }

    @Test
    void 往一格里扔只站正挨着它_出手点比盆沿高的地方() {
        // 工作盆在 (3,64,0)，盆沿在 13/16 格高：平地上站在它四邻能扔，斜对角太远、正上方会踩着它。
        StandSpots.Ranking ranking = find(ApproachTarget.forToss(new BlockPos(3, 64, 0), 13 / 16.0));
        List<BlockPos> feet = ranking.spots().stream().map(StandSpot::feet).toList();
        assertTrue(feet.containsAll(List.of(new BlockPos(2, 64, 0), new BlockPos(4, 64, 0),
                new BlockPos(3, 64, 1), new BlockPos(3, 64, -1))));
        assertTrue(feet.stream().allMatch(spot -> Math.abs(spot.getX() - 3) + Math.abs(spot.getZ()) == 1),
                "能用的站位都在正挨着它的四格（高低不论）");
        assertEquals("离得太远，扔不准", failureAt(ranking, new BlockPos(2, 64, 1)));
        assertEquals("站在它正上方", failureAt(ranking, new BlockPos(3, 65, 0)));
        assertEquals("比落点低，扔不进去", failureAt(ranking, new BlockPos(2, 63, 0)));
        assertEquals("比落点高太多，扔下去会偏", failureAt(ranking, new BlockPos(2, 67, 0)));
    }

    @Test
    void 骑载具时浅水站位可用且排在干站位之后() {
        // 船浮在水边：靠船一侧的一格泡着浅水（脚格进水、头一格在水面上、脚下有实地）。
        world.fluids.add(new BlockPos(4, 64, 3));
        world.fluids.add(new BlockPos(4, 64, 4));
        ApproachTarget boat = ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1));
        StandSpots.Ranking ranking = find(boat);
        // 浅水位入选，和干位一起按代价排序；所有干位都排在所有湿位之前——先挑干的，干的不够才湿脚。
        assertFalse(ranking.spots().stream().noneMatch(spot -> world.fluids.contains(spot.feet())));
        int firstWet = -1;
        int lastDry = -1;
        for (int i = 0; i < ranking.spots().size(); i++) {
            if (world.fluids.contains(ranking.spots().get(i).feet())) {
                if (firstWet < 0) firstWet = i;
            } else {
                lastDry = i;
            }
        }
        assertTrue(firstWet < 0 || lastDry < 0 || lastDry < firstWet, "干位要排在湿位前面");
    }

    @Test
    void 岸边没有干位时踩进浅水也登得上去() {
        // 船周围整片浅水（水面在脚格、头一格在水面上、水底实地）：没有干位也挑得出登船站位，不用等船漂过来。
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int y = 60; y <= 68; y++) {
                    BlockPos cell = new BlockPos(4 + dx, y, dz);
                    // 水面那一格是浅水；其余高度的格子按头顶封住处理，替身里不另摆水的形状。
                    if (y == 64) world.fluids.add(cell);
                    else world.lowCeiling.add(cell);
                }
            }
        }
        ApproachTarget boat = ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1));
        StandSpots.Ranking ranking = find(boat);
        assertFalse(ranking.spots().isEmpty(), "全是浅水也该有能站的位置");
        assertTrue(ranking.spots().stream().allMatch(spot -> world.fluids.contains(spot.feet())));
    }

    @Test
    void 载具点击位的漂浮位不因落脚粗筛被毙() {
        // 贴水面的漂浮位脚下没有实地、头顶也未必有两格空：这些是干位落脚的粗筛项，
        // 载具点击位在脚格泡水时只认流体读数，不再被它们整片提前毙掉（实机贴船 2 格 use 候选全拒的教训）。
        for (int dx = -1; dx <= 1; dx++) {
            world.fluids.add(new BlockPos(3 + dx, 64, 0));
            world.noFloor.add(new BlockPos(3 + dx, 64, 0));
            world.deepDrops.add(new BlockPos(3 + dx, 64, 0));
            world.lowCeiling.add(new BlockPos(3 + dx, 64, 0));
        }
        world.lowCeiling.add(new BlockPos(1, 64, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1)));
        assertFalse(ranking.spots().stream().noneMatch(spot -> world.fluids.contains(spot.feet())),
                "头顶封住、脚下悬空的漂浮位照样能当载具点击位");
        // 干位粗筛不外溢：干地上头顶封住的格子照旧拒。
        assertEquals("头顶没空间", failureAt(ranking, new BlockPos(1, 64, 0)));
    }

    @Test
    void 头也会没进水的位置骑载具照样拒() {
        // 深水：脚格和头一格都泡在水里，湿脚的放行救不了它。
        world.fluids.add(new BlockPos(2, 64, 0));
        world.fluids.add(new BlockPos(2, 65, 0));
        ApproachTarget boat = ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1));
        StandSpots.Ranking ranking = find(boat);
        assertEquals("泡在液体里", failureAt(ranking, new BlockPos(2, 64, 0)));
    }

    @Test
    void 船漂进深水时贴水面的漂浮位也登得上去() {
        // 湖心没有一寸干地：水面格脚格进水、头一格在水面上，脚下没有实地、下面深不见底。
        // 骑上去本来就离开水面，踩着水凑到船边点一下就走，这样的位置该放行。
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int y = 59; y <= 67; y++) {
                    BlockPos cell = new BlockPos(4 + dx, y, dz);
                    if (y == 64) {
                        // 水面那一格：脚下悬空、落差深不见底，头一格露在水面上。
                        world.fluids.add(cell);
                        world.noFloor.add(cell);
                        world.deepDrops.add(cell);
                    } else if (y < 64) {
                        // 水面以下：脚格和头一格都泡在水里。
                        world.fluids.add(cell);
                    } else {
                        // 水面上方的空气：没有实地可站。
                        world.noFloor.add(cell);
                    }
                }
            }
        }
        ApproachTarget boat = ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1));
        StandSpots.Ranking ranking = find(boat);
        assertFalse(ranking.spots().isEmpty(), "湖心全水也该挑得出登船的位置");
        // 能用的全是贴水面的漂浮位；头会没进水的深水位照旧被拒。
        assertTrue(ranking.spots().stream().allMatch(spot -> world.fluids.contains(spot.feet())));
        assertEquals("泡在液体里", failureAt(ranking, new BlockPos(2, 63, 0)));
        assertEquals("脚下悬空", failureAt(ranking, new BlockPos(2, 65, 0)));
    }

    @Test
    void 骑载具踩着水漂在船边算到了() {
        // 到了之后的核对：人在贴水面的湿位漂着、还没落地，头露在水面上，够得着船就算到。
        world.grounded = false;
        world.feet = new BlockPos(2, 64, 0);
        world.fluids.add(world.feet);
        ApproachTarget boat = ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1));
        assertEquals(Optional.empty(), StandSpots.checkArrival(boat, REACH, world, guarded));
        // 没有湿脚许可的目标（放箱这类）漂在水里照旧不算到：口子不外溢。
        assertEquals(Optional.of("还没落地"),
                StandSpots.checkArrival(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)), REACH, world, guarded));
    }

    @Test
    void 没声明湿脚的目标浅水位照旧不站() {
        // 放箱、靠墙、施工这些目标没有湿脚的许可：脚格进水一票拒，口子不外溢。
        world.fluids.add(new BlockPos(2, 64, 1));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("泡在液体里", failureAt(ranking, new BlockPos(2, 64, 1)));
    }

    @Test
    void 恰好压着方块交互距离的格子要_再远一格就拒() {
        // 眼到方块边线 4.5 格整：距离判是"超过才拒"，压线算够得着；这一边界错成"大于等于才过"就全盘收紧。
        ApproachTarget 目标块 = ApproachTarget.ofBlock(new BlockPos(5, 65, 0));
        StandSpots.Ranking ranking = StandSpots.find(目标块, REACH, world, walking, guarded);
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(0, 64, 0))),
                "恰好 4.5 格的候选要入选");
        world.feet = new BlockPos(0, 64, 0);
        assertEquals(Optional.empty(), StandSpots.checkArrival(目标块, REACH, world, guarded));
        // 再远一格：5.5 格，够不着。
        world.feet = new BlockPos(-1, 64, 0);
        assertEquals(Optional.of("够不着"), StandSpots.checkArrival(目标块, REACH, world, guarded));
    }

    @Test
    void 实体恰好压着实体交互距离的格子要_再远一丝就拒() {
        // 包围盒左缘离眼睛 3.0 格整：压线算够得着；往远处挪一丝（3.0001）就该拒。
        ApproachTarget 压线 = ApproachTarget.ofEntity(new AABB(3.5, 64, 0, 4.5, 66, 1));
        StandSpots.Ranking ranking = StandSpots.find(压线, REACH, world, walking, guarded);
        assertTrue(ranking.spots().stream().anyMatch(spot -> spot.feet().equals(new BlockPos(0, 64, 0))),
                "恰好 3.0 格的实体候选要入选");
        ApproachTarget 过线 = ApproachTarget.ofEntity(new AABB(3.5001, 64, 0, 4.5001, 66, 1));
        assertEquals("够不着", failureAt(StandSpots.find(过线, REACH, world, walking, guarded),
                new BlockPos(0, 64, 0)));
    }

    @Test
    void 没加载的格子不进候选也不进被拒表() {
        // 既不能当站位、也不算"附近没有站位"的理由：静默跳过，不造一条假败因。
        world.unloaded.add(new BlockPos(2, 64, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertTrue(ranking.spots().stream().noneMatch(spot -> spot.feet().equals(new BlockPos(2, 64, 0))));
        assertTrue(ranking.rejected().stream().noneMatch(spot -> spot.feet().equals(new BlockPos(2, 64, 0))));
    }

    @Test
    void 一格同时坏几项只记最先败的那一项() {
        // 脚下悬空、头顶没空间、落差深、泡水、旁边岩浆全占：顺序里最先的"脚下悬空"一条定案，不堆一串败因。
        BlockPos 坏格 = new BlockPos(2, 64, 0);
        world.noFloor.add(坏格);
        world.lowCeiling.add(坏格);
        world.deepDrops.add(坏格);
        world.fluids.add(坏格);
        world.lavas.add(坏格);
        StandSpots.Ranking ranking = find(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)));
        assertEquals("脚下悬空", failureAt(ranking, 坏格));
    }

    @Test
    void 到达核对先报站的这格要挪开() {
        // 站着的这格本身要放东西或受保护：排在够不着、看不见之前，先挪开再谈别的。
        world.feet = new BlockPos(2, 64, 0);
        guarded.add(world.feet);
        assertEquals(Optional.of("站的这格要放东西或受保护，得挪开"),
                StandSpots.checkArrival(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)), REACH, world, guarded));
        // 站的格受保护又够不着：还是先报挪开。
        world.feet = new BlockPos(20, 64, 0);
        guarded.add(world.feet);
        assertEquals(Optional.of("站的这格要放东西或受保护，得挪开"),
                StandSpots.checkArrival(ApproachTarget.ofBlock(new BlockPos(3, 64, 0)), REACH, world, guarded));
    }

    @Test
    void 湿位旁边有岩浆照样拒() {
        // 湿位岔路只放了落脚粗筛，岩浆这条活命线还在：贴水的载具点击位旁边有岩浆不站。
        world.fluids.add(new BlockPos(2, 64, 0));
        world.lavas.add(new BlockPos(2, 64, 0));
        StandSpots.Ranking ranking = find(ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1)));
        assertEquals("旁边有岩浆", failureAt(ranking, new BlockPos(2, 64, 0)));
    }

    @Test
    void 要挖的那格下面落差恰好三格能就地挖_再深就看不见底() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        StubWorld 三格坑 = new StubWorld() {
            @Override public double dropBelow(BlockPos at) { return 3.0; }
        };
        assertNull(StandSpots.underfootFailure(目标格, 三格坑), "落差 3 格摔不坏，可以原地挖");
        StubWorld 深坑 = new StubWorld() {
            @Override public double dropBelow(BlockPos at) { return 3.5; }
        };
        assertEquals("要挖的那格下面看不见底", StandSpots.underfootFailure(目标格, 深坑));
        // 正下方是岩浆：无论落差多少都不在它上面挖。
        StubWorld 岩浆底 = new StubWorld();
        岩浆底.lavaUnderneath.add(目标格.below());
        assertEquals("要挖的那格下面是岩浆", StandSpots.underfootFailure(目标格, 岩浆底));
    }

    private String failureAt(StandSpots.Ranking ranking, BlockPos feet) {
        List<RejectedSpot> matches = ranking.rejected().stream()
                .filter(spot -> spot.feet().equals(feet)).toList();
        assertEquals(1, matches.size(), "位置 " + feet + " 应该恰好在被拒表里出现一次");
        return matches.getFirst().failedItem();
    }
}
