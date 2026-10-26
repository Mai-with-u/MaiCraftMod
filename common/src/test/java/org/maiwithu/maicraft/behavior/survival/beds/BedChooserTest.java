// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.beds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 选床的离线场景：占用、受保护、旁边有怪的床被排除；床区限定生效；按离角色近的排序。 */
class BedChooserTest {

    private static BedCandidate bed(int x, int z, boolean occupied, boolean protectedLand, boolean hostileNear,
            double distance) {
        return new BedCandidate(new BlockPos(x, 64, z), occupied, protectedLand, hostileNear, distance);
    }

    @Test
    void occupiedProtectedAndThreatenedBedsAreExcluded() {
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(2, 0, true, false, false, 2),
                        bed(3, 0, false, true, false, 3),
                        bed(4, 0, false, false, true, 4),
                        bed(5, 0, false, false, false, 5)),
                Set.of(), null);
        // 只有最后一张三关全过：被占用、受保护、旁边有怪的都不算能用的床。
        assertEquals(new BlockPos(5, 64, 0), chosen.orElseThrow().cell());
    }

    @Test
    void triedBedsAreNotChosenAgain() {
        BlockPos tried = new BlockPos(1, 64, 0);
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(1, 0, false, false, false, 1), bed(9, 0, false, false, false, 9)),
                Set.of(tried), null);
        // 试过不行的床不再试，哪怕它更近。
        assertEquals(new BlockPos(9, 64, 0), chosen.orElseThrow().cell());
    }

    @Test
    void bedsOutsideTheAreaAreIgnoredWhenAreaIsGiven() {
        WorldPosition area = WorldPosition.here(0, 64, 0);
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(3, 3, false, false, false, 1),
                        bed(200, 200, false, false, false, 0.5)),
                Set.of(), area);
        // 指定了床区就只在半径内选：再近的远床也不算"这一片"的。
        assertEquals(new BlockPos(3, 64, 3), chosen.orElseThrow().cell());
    }

    @Test
    void nearestUsableBedComesFirst() {
        List<BedCandidate> usable = BedChooser.usable(
                List.of(bed(10, 0, false, false, false, 10),
                        bed(2, 0, false, false, false, 2),
                        bed(5, 0, false, false, false, 5)),
                Set.of(), null);
        assertEquals(3, usable.size());
        assertEquals(new BlockPos(2, 64, 0), usable.getFirst().cell());
        assertTrue(usable.stream().sorted(Comparator.comparingDouble(BedCandidate::distance))
                .toList().equals(usable), "按近到远排好");
    }

    @Test
    void atNightOnlyBedsWithinThirtyTwoBlocksCount() {
        // 实机 10-15：夜里从家走 130 格去睡上一晚野外那张床。夜里只认 32 格以内的床。
        List<BedCandidate> farOnly = List.of(bed(130, 0, false, false, false, 130));
        assertTrue(BedChooser.choose(farOnly, Set.of(), null, BedChooser.NIGHT_WALK_LIMIT_BLOCKS).isEmpty());
        // 不限距离时（公开睡觉）远处那张照样能选。
        assertTrue(BedChooser.choose(farOnly, Set.of(), null).isPresent());
        // 近处有一张就选近处的。
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(bed(130, 0, false, false, false, 130), bed(20, 0, false, false, false, 20)),
                Set.of(), null, BedChooser.NIGHT_WALK_LIMIT_BLOCKS);
        assertEquals(new BlockPos(20, 64, 0), chosen.orElseThrow().cell());
    }

    @Test
    void unverifiedRememberedBedsNeverOutrankScannedOnes() {
        // 实机三夜样本：身边明明有床，选床却落到几十格外一张区块没加载、
        // 占用怪物都没核对过的记忆床上。没核对过的记忆床整批排在核对过的床后面：
        // 哪怕它离得近，也轮不到它抢在扫到的床前面。
        BlockPos remembered = new BlockPos(93, 64, -68);
        List<BedCandidate> usable = BedChooser.usable(
                List.of(new BedCandidate(remembered, false, false, false, 5, true),
                        bed(10, 0, false, false, false, 40)),
                Set.of(), null);
        assertEquals(new BlockPos(10, 64, 0), usable.getFirst().cell(), "核对过的床在前");
        // 核对过的全被筛掉（此处被占用）时，记忆床照样召回：人离家很远、附近没床，回家睡靠它。
        List<BedCandidate> memoryOnly = BedChooser.usable(
                List.of(new BedCandidate(remembered, false, false, false, 5, true),
                        bed(10, 0, true, false, false, 40)),
                Set.of(), null);
        assertEquals(remembered, memoryOnly.getFirst().cell());
        // 只有记忆床时按近到远挑。
        Optional<BedCandidate> chosen = BedChooser.choose(
                List.of(new BedCandidate(remembered, false, false, false, 5, true),
                        new BedCandidate(new BlockPos(268, 75, -332), false, false, false, 300, true)),
                Set.of(), null);
        assertEquals(remembered, chosen.orElseThrow().cell());
    }

    @Test
    void triedBedsCountByTheReasonTheyWereRefusedAtLieDown() {
        // 扫描没看出来、躺下那一刻才被原版拒的床（怪走近了、床被占了）：带着原因回灌进分类，
        // 各归各的；没记下原因的试过床才算"试过睡不上"。
        BlockPos guarded = new BlockPos(1, 64, 0);
        BlockPos occupied = new BlockPos(2, 64, 0);
        BlockPos unknown = new BlockPos(3, 64, 0);
        BedChooser.Screening screening = BedChooser.screen(
                List.of(bed(1, 0, false, false, false, 1),
                        bed(2, 0, false, false, false, 2),
                        bed(3, 0, false, false, false, 3)),
                Set.of(guarded, occupied, unknown),
                Map.of(guarded, BedRefusal.MONSTERS_NEARBY, occupied, BedRefusal.OCCUPIED),
                null, Double.MAX_VALUE);
        assertEquals(3, screening.scanned());
        assertEquals(1, screening.tried());
        assertEquals(1, screening.occupied());
        assertEquals(1, screening.hostileNear());
        assertEquals(0, screening.usable());
        assertTrue(screening.describe().contains("1 张床边有怪"));
        assertTrue(screening.describe().contains("1 张被占用"));
        assertTrue(screening.describe().contains("1 张试过睡不上"));
    }

    @Test
    void blockedAndGoneBedsCountByTheirOwnReason() {
        // 点不上（被挡、太远、路走不通）和走到跟前发现床没了，是两样不同的游戏事实，各归各的原因，
        // 不混进"试过睡不上"；话术里说得出各有几张。
        BlockPos blocked = new BlockPos(1, 64, 0);
        BlockPos gone = new BlockPos(2, 64, 0);
        BedChooser.Screening screening = BedChooser.screen(
                List.of(bed(1, 0, false, false, false, 1), bed(2, 0, false, false, false, 2)),
                Set.of(blocked, gone),
                Map.of(blocked, BedRefusal.BLOCKED, gone, BedRefusal.GONE),
                null, Double.MAX_VALUE);
        assertEquals(1, screening.blocked());
        assertEquals(1, screening.gone());
        assertEquals(0, screening.tried());
        assertTrue(screening.describe().contains("1 张被挡住或太远点不上"));
        assertTrue(screening.describe().contains("1 张已经不在了"));
    }

    @Test
    void reasonsAreListedEvenWhenAUsableBedRemains() {
        // 选上了床也不许把被筛掉的账吞掉：还剩能睡的床时，分类说明照样列出筛掉的原因。
        BedChooser.Screening screening = BedChooser.screen(
                List.of(bed(1, 0, true, false, false, 1), bed(9, 0, false, false, false, 9)),
                Set.of(), null, Double.MAX_VALUE);
        assertEquals(1, screening.usable());
        assertTrue(screening.describe().contains("其中 1 张能睡"));
        assertTrue(screening.describe().contains("1 张被占用"));
    }
}
