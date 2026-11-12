// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 靠近动作：原地可用就一步不走；核对不过换下一个候选；都不行按许可决定要不要补救，
 * 补救也不行就按到不了结束，问题里带着试过哪些站位、败在哪；走向站位途中能不能打断、先停稳都按走路说。
 */
class ApproachTest {

    private static final ReachRules REACH = new ReachRules(4.5, 3.0, 3, 1.62);
    private static final BlockPos 目标 = new BlockPos(3, 64, 0);

    private final StubWorld world = new StubWorld();
    private final StubWalkCost walking = new StubWalkCost();
    private final StubGuarded guarded = new StubGuarded();

    private Approach approach(StubMoves moves, StandOpener opener, Permissions permissions) {
        return approach(ApproachTarget.ofBlock(目标), moves, opener, permissions, null);
    }

    private Approach approach(ApproachTarget target, StubMoves moves, StandOpener opener,
            Permissions permissions, TaskRecords records) {
        return new Approach(target, REACH, world, walking, guarded,
                moves, TerrainPermit.WALK_ONLY, opener, permissions, records, UnreachableFromHere.none());
    }

    @Test
    void 已经站在合适的位置就一步都不走() {
        world.feet = new BlockPos(2, 64, 0);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(1)));
        assertEquals(0, moves.begun.size());
    }

    @Test
    void 走到候选站位核对通过就完成() {
        // 出发时还在跳跃中：原地核对不过，走过去落地之后才算到。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.done());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(1)));
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(2)));
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(3)));
        assertEquals(1, moves.begun.size());
        // 干的站位按「站进指定格」上路：到了以站进那一格为准，邻格不算到。
        assertEquals("Exact", moves.goals.getLast().goal().getClass().getSimpleName());
        assertTrue(moves.goals.getLast().goal().isAt(moves.begun.getLast()));
    }

    @Test
    void 湿脚位走到够得着就算到_不必站进水里那格() {
        // 水里的船：周围整片浅水，能站的候选都是湿脚位——水里那一格寻路站不进去，
        // 按宽松目标上路，走到够得着它的位置就算到，不必把身体放进水里。
        for (int x = -2; x <= 11; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 50; y <= 63; y++) {
                    world.fluids.add(new BlockPos(x, y, z));
                }
            }
        }
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.done());
        Approach approach = approach(ApproachTarget.ofVehicle(new AABB(4, 60, 0, 5, 62, 1)),
                moves, null, Permissions.DEFAULT, null);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(status instanceof ActionStatus.Done); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), status);
        assertFalse(moves.goals.getLast().goal().getClass().getSimpleName().equals("Exact"),
                "湿脚位不用「站进指定格」的目标");
        assertTrue(moves.goals.getLast().goal().isAt(moves.begun.getLast().north()),
                "邻格就算到：不必站进水里那一格");
    }

    @Test
    void 走向站位途中能不能打断与先停稳都按走路说() {
        // 悬空走向站位：能不能打断按走路说（停不得），先到安全处再停转给走路，落地前不报停稳；还没上路时现在就能停。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.running(), ActionStatus.running());
        moves.airborne = true;
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        assertEquals(Interruptibility.WORKING, approach.interruptibility(), "还在看原地，正常干活");
        assertFalse(approach.stopSafely(), "还没上路，现在就能停");
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.tick(new StubTick(3));
        assertEquals(1, moves.begun.size(), "走在去站位的路上");

        assertEquals(Interruptibility.UNSAFE_TO_STOP, approach.interruptibility(), "悬空走着停不得");
        assertTrue(approach.stopSafely(), "转给走路先落地");
        assertEquals(1, moves.safeStops, "走路收到先停稳");
        assertFalse(approach.stoppedSafely(), "还没落地");
        // 收尾途中落地站稳：原地等着，落地报的"已停下"不是到了，也不重新上路。
        assertInstanceOf(ActionStatus.Running.class, approach.tick(new StubTick(4)), "收尾途中还在落地");
        assertInstanceOf(ActionStatus.Running.class, approach.tick(new StubTick(5)), "落地报的已停下不是到了");
        assertTrue(approach.stoppedSafely(), "落地站稳了");
        assertEquals(1, moves.begun.size(), "收尾途中原地等着，不重新上路");
        // 落地等着时被打断，恢复推进后从原地续上同一趟，不重新挑站位。
        approach.pause();
        assertInstanceOf(ActionStatus.Running.class, approach.tick(new StubTick(6)));
        assertEquals(1, moves.begun.size(), "回来接着走，不重新挑站位");
        assertEquals(1, moves.resumes, "从原地续上这一趟");
    }

    @Test
    void 普通目标走到站位后被细碎遮挡直接完成_桶等要求视线的目标照旧换站位() {
        // 普通方块走到挑好的站位后就算前景多了草叶遮挡，也不再原地换站位打转，直接交给直发包出手。
        world.grounded = false;
        AtomicInteger walkedPlain = new AtomicInteger();
        StubMoves plainMoves = new StubMoves(world, ActionStatus.done());
        plainMoves.onArrive = walkedPlain::incrementAndGet;
        world.hidden = (eye, target) -> walkedPlain.get() > 0 && eye.x() < 1.5;
        Approach plain = approach(plainMoves, null, Permissions.DEFAULT);
        ActionStatus plainStatus = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(plainStatus instanceof ActionStatus.Done); tick++) {
            plainStatus = plain.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), plainStatus);
        assertEquals(1, plainMoves.begun.size(), "普通方块到站位后不再因前景遮挡换站位");

        // 桶舀倒水这类要求真实视线的目标：走过去之后挡板立起来看不见了，照旧收掉上一趟换到看得见的东侧站位。
        world.feet = new BlockPos(0, 64, 0);
        world.grounded = false;
        AtomicInteger walkedBucket = new AtomicInteger();
        StubMoves bucketMoves = new StubMoves(world, ActionStatus.done(), ActionStatus.done(), ActionStatus.done());
        bucketMoves.onArrive = walkedBucket::incrementAndGet;
        world.hidden = (eye, target) -> walkedBucket.get() > 0 && eye.x() < 1.5;
        Approach bucket = approach(ApproachTarget.ofBlock(目标).requiringSightOnArrival(),
                bucketMoves, null, Permissions.DEFAULT, null);
        ActionStatus bucketStatus = ActionStatus.running();
        for (int tick = 1; tick <= 30 && !(bucketStatus instanceof ActionStatus.Done); tick++) {
            bucketStatus = bucket.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), bucketStatus);
        assertTrue(bucketMoves.begun.size() > 1);
        assertEquals(bucketMoves.begun.size() - 1, bucketMoves.closes);
        assertTrue(bucket.tried().stream().allMatch(attempt -> attempt.result().contains("看不见")));
    }

    @Test
    void 这条路走不通也换下一个候选() {
        world.grounded = false;
        StubMoves moves = new StubMoves(world,
                ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "前面是断崖")),
                ActionStatus.done());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        assertInstanceOf(ActionStatus.Running.class, approach.tick(new StubTick(3)));
        approach.tick(new StubTick(4));
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(5)));
        assertEquals(2, moves.begun.size());
        assertEquals(1, approach.tried().size());
    }

    @Test
    void 高处的站位没走通就不再挨个爬同一带的塔顶() {
        // 夜宿回站位锚在一座塔顶时，待试队列里全是塔顶的合格候选：脚下实心、头顶开阔，
        // 挨个爬一遍把整段接近耗光。寻路是全域找路，第一个高处站位没走通，同一带的到不了
        // 就一次说清：剩下的高处候选不再爬，如实按到不了收场，不再挨个上路。
        world.grounded = false;
        world.feet = new BlockPos(0, 64, 0);
        // 低处的候选全被挡板挡成看不见，待试队列里只剩高出不止一跳的塔身候选。
        world.hidden = (eye, target) -> eye.y() < 70;
        StubMoves moves = new StubMoves(world,
                ActionStatus.failed(Problem.of(Problem.Kind.UNREACHABLE, "塔身爬不上去")));
        Approach approach = approach(ApproachTarget.ofEntity(new AABB(3, 64, 0, 4, 80, 1)),
                moves, null, Permissions.DEFAULT, null);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.tick(new StubTick(3));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(4)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertEquals(1, moves.begun.size(), "只走过第一个高处站位");
        assertTrue(approach.tried().stream().anyMatch(attempt -> attempt.tried().contains("一跳")
                && attempt.result().contains("不再挨个爬")), approach.tried().toString());
    }

    @Test
    void 寻路程序出错就不再换站位_照原样交上去() {
        // 实机里算路程序出错时，采集挨个试了 58 个站位，每个都错在同一处；程序出错换站位没用。
        world.grounded = false;
        Problem broken = Problem.of(Problem.Kind.INTERNAL_ERROR, "寻路程序出错，这次没算出路线，不是没有路：x");
        StubMoves moves = new StubMoves(world, ActionStatus.failed(broken), ActionStatus.done());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        ActionStatus status = approach.tick(new StubTick(3));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertEquals(Problem.Kind.INTERNAL_ERROR, failed.problem().kind());
        assertEquals(1, moves.begun.size(), "只走了第一个站位");
    }

    @Test
    void 候选用尽且许可不许改方块就按到不了结束() {
        world.hidden = (eye, target) -> true;
        Approach approach = approach(new StubMoves(world), null,
                new Permissions(Permissions.BlockChanges.NONE, Permissions.Fight.HOSTILE_MOBS,
                        false, Permissions.AnimalKilling.WILD, Permissions.SurvivalNeeds.ON, null));
        approach.tick(new StubTick(1));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(2)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        // 被挡住但不许动方块：建议里把"允许挖开遮挡"说出来，让 LLM 能点头放行。
        assertTrue(failed.problem().suggestion().contains("挖开遮挡"));
    }

    @Test
    void 许可允许时用补救作最后手段() {
        world.hidden = (eye, target) -> true;
        StubOpener opener = new StubOpener(true);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(moves, opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(2)));
        // 补救做完了，但挡板还在（世界替身里始终看不见）：如实按到不了结束，不再回头换站位。
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(3)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        assertEquals(1, opener.asked.size());
        assertEquals(0, moves.begun.size());
    }

    @Test
    void 补救做不成也如实按到不了结束() {
        world.hidden = (eye, target) -> true;
        StubOpener opener = new StubOpener(false);
        Approach approach = approach(new StubMoves(world), opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(2)));
        assertEquals(Problem.Kind.UNREACHABLE, failed.problem().kind());
        // 一个位置救不了就换下一个只是被挡住的位置再问，但有上限，不把几百个被拒位置挨个算一遍。
        assertTrue(opener.asked.size() > 1 && opener.asked.size() <= 24, "问了 " + opener.asked.size() + " 个");
        assertTrue(opener.asked.stream().allMatch(spot -> spot.failedItem().equals("看不见")));
        // 没走过任何候选：问题里交代附近被拒的候选位置，各败在哪一项、最接近能用的是哪一个。
        assertTrue(failed.problem().message().contains("候选位置"));
        assertTrue(failed.problem().message().contains("最接近能用的是"), failed.problem().message());
    }

    @Test
    void 收尾时停下脚步并收掉补救动作() {
        world.hidden = (eye, target) -> true;
        StubOpener opener = new StubOpener(true);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(moves, opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.close();
        assertEquals(1, opener.closes, "补救动作一并收掉");
    }

    @Test
    void 被打断时只暂停脚步_回来接着走同一个站位() {
        // 出发时还在跳跃中：原地核对不过，挑了站位在路上时被打断。
        world.grounded = false;
        StubMoves moves = new StubMoves(world, ActionStatus.running(), ActionStatus.running());
        Approach approach = approach(moves, null, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        approach.tick(new StubTick(2));
        approach.pause();
        assertEquals(1, moves.pauses);
        assertEquals(0, moves.closes, "被打断不该把这一趟丢掉");
        approach.tick(new StubTick(3));
        assertEquals(1, moves.begun.size(), "回来接着走，不重新挑站位");
    }

    @Test
    void 要挖的方块在脚下下面是岩浆就换旁边一格() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        world.feet = 目标格.above();
        world.lavaUnderneath.add(目标格.below());
        StubMoves moves = new StubMoves(world, ActionStatus.done());
        Approach approach = approach(ApproachTarget.ofBlockToDig(目标格), moves, null, Permissions.DEFAULT, null);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(status instanceof ActionStatus.Done); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), status);
        // 挪去了旁边一格，没有留在要挖的那格正上方。
        assertEquals(1, moves.begun.size());
        assertNotEquals(目标格.above(), moves.begun.get(0));
    }

    @Test
    void 要挖的方块在脚下下面是实地就原地挖() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        world.feet = 目标格.above();
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(ApproachTarget.ofBlockToDig(目标格), moves, null, Permissions.DEFAULT, null);
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(1)));
        assertEquals(0, moves.begun.size());
    }

    @Test
    void 要挖的方块在脚下旁边站不了就就地挖并交代下面是什么() {
        BlockPos 目标格 = new BlockPos(3, 64, 0);
        world.feet = 目标格.above();
        world.lavaUnderneath.add(目标格.below());
        // 附近所有格子的地面都抽掉（候选脚位最低到 59，它的地面在 58）：旁边一格都站不了。
        for (int x = -2; x <= 8; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 58; y <= 67; y++) {
                    world.noFloor.add(new BlockPos(x, y, z));
                }
            }
        }
        List<String> 记录 = new ArrayList<>();
        TaskRecords 记账 = new TaskRecords() {
            @Override public void change(Change change) { }
            @Override public void unconfirmed(Change change) { }
            @Override public void attempt(String tried, String whatHappened) {
                记录.add(tried + "：" + whatHappened);
            }
        };
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(ApproachTarget.ofBlockToDig(目标格), moves, null, Permissions.DEFAULT, 记账);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(status instanceof ActionStatus.Done); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        // 不算到不了：留在原地动手，下面是什么跟着任务结果走。
        assertEquals(ActionStatus.done(), status);
        assertEquals(0, moves.begun.size());
        assertEquals(1, 记录.size());
        assertTrue(记录.get(0).contains("岩浆"));
    }

    @Test
    void 登船目标全被拒时按只剩泡水的位置交代() {
        // 船周围整片深水（脚格和头一格都泡着）：声明允许湿脚的目标浅水已放行，还因液体被拒的
        // 都是头会没进水的位置，交代时如实说"只剩泡水的位置"，不说成"这格不能站"。
        for (int x = -1; x <= 9; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 59; y <= 69; y++) {
                    world.fluids.add(new BlockPos(x, y, z));
                }
            }
        }
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(ApproachTarget.ofVehicle(new AABB(4, 64, 0, 5, 66, 1)),
                moves, null, Permissions.DEFAULT, null);
        approach.tick(new StubTick(1));
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, approach.tick(new StubTick(2)));
        assertTrue(failed.problem().message().contains("只剩泡水的位置"), failed.problem().message());
        assertFalse(failed.problem().message().contains("泡在液体里"), failed.problem().message());
    }

    @Test
    void 目标格比地面低时挖进坑边同层埋着的站位去站() {
        // 立墙的锚点落在地面下两格，四格已清成坑（y 62、63），要往坑底 (3,62,0) 放：得点坑底方块 (3,61,0) 的顶面。
        // 地面（脚位 64）上的位置看不进坑底，坑边与坑底同层的格子都埋在土里、被拒为"头顶没空间"；
        // 只差脚格与头格两块时挖进去站，站进去就看得见、够得着。
        ApproachTarget target = PlacementSpots.faceTarget(new BlockPos(3, 61, 0), Direction.UP);
        List<BlockPos> cleared = List.of(new BlockPos(3, 62, 0), new BlockPos(3, 63, 0),
                new BlockPos(4, 62, 0), new BlockPos(4, 63, 0));
        // 只看坑边这一小片地：别处当没加载，候选少到补救问得到坑边那一格。
        for (int x = -2; x <= 8; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = 55; y <= 65; y++) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (x < 2 || x > 4 || z < 0 || z > 2) world.unloaded.add(at);
                    else if (y <= 63 && !cleared.contains(at)) world.lowCeiling.add(at);
                }
            }
        }
        cleared.forEach(guarded::add);
        world.hidden = (eye, t) -> eye.y > 64.5;
        world.feet = new BlockPos(3, 64, 1);
        BlockPos pitSide = new BlockPos(3, 62, 1);
        DigsIntoOneSpot opener = new DigsIntoOneSpot(pitSide);
        StubMoves moves = new StubMoves(world);
        Approach approach = approach(target, moves, opener, Permissions.DEFAULT, null);
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 10 && !(status instanceof ActionStatus.Done) && !(status instanceof ActionStatus.Failed); tick++) {
            status = approach.tick(new StubTick(tick));
        }
        assertEquals(ActionStatus.done(), status, "挖进坑边站位后核对通过：" + approach.tried());
        assertEquals(pitSide, world.feet);
        assertTrue(opener.asked.stream().anyMatch(spot -> spot.failedItem().equals("头顶没空间")),
                "埋着的站位也拿去问补救：" + opener.asked);
        assertEquals(pitSide, opener.asked.getLast().feet(), "问到坑边那一格就救");
        // 地面上看不见的位置先问（补救替身不肯救），再问埋着的。
        assertEquals("看不见", opener.asked.getFirst().failedItem());
    }

    @Test
    void 补救完人还在往下掉就等落地再核对() {
        // 补救常是挖开自己脚下那一块（坑边看不见坑底就挖掉坑沿）：挖完人掉下去的那几刻还没落地，
        // 等站稳了再核对，不把这几刻当成补救没用。
        world.hidden = (eye, t) -> true;
        StubOpener opener = new StubOpener(true);
        Approach approach = approach(new StubMoves(world), opener, Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        world.grounded = false;
        assertEquals(ActionStatus.progressed(), approach.tick(new StubTick(2)), "补救开始");
        assertEquals(ActionStatus.running(), approach.tick(new StubTick(3)), "补救做完、人还在掉：等着");
        assertEquals(ActionStatus.running(), approach.tick(new StubTick(4)));
        // 落地了，挡板也没了：核对通过。
        world.grounded = true;
        world.hidden = (eye, t) -> false;
        assertEquals(ActionStatus.done(), approach.tick(new StubTick(5)));
        assertEquals(1, opener.asked.size(), "等落地期间不再问补救");
    }

    @Test
    void 补救完一直落不了地就如实按到不了结束() {
        world.hidden = (eye, t) -> true;
        Approach approach = approach(new StubMoves(world), new StubOpener(true), Permissions.DEFAULT);
        approach.tick(new StubTick(1));
        world.grounded = false;
        approach.tick(new StubTick(2));
        ActionStatus status = ActionStatus.running();
        int ticks = 0;
        while (status instanceof ActionStatus.Running && ticks < 100) {
            status = approach.tick(new StubTick(3 + ticks++));
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, status);
        assertTrue(ticks > 30 && ticks <= 45, "等了 " + ticks + " 刻");
        assertTrue(failed.problem().message().contains("还没落地"), failed.problem().message());
    }

    /** 站位补救替身：只肯挖进指定的那一格（模拟生产实现对够不着、挖开了也看不见的位置说不），挖完把人放进去。 */
    private final class DigsIntoOneSpot implements StandOpener {
        final List<RejectedSpot> asked = new ArrayList<>();
        private final BlockPos spot;

        DigsIntoOneSpot(BlockPos spot) {
            this.spot = spot;
        }

        @Override public Optional<Action> rescue(RejectedSpot blocked, ApproachTarget target) {
            asked.add(blocked);
            if (!blocked.feet().equals(spot)) return Optional.empty();
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    world.lowCeiling.remove(spot);
                    world.feet = spot;
                    return ActionStatus.done();
                }

                @Override public String describe() {
                    return "挖进坑边站位";
                }
            });
        }
    }
}
