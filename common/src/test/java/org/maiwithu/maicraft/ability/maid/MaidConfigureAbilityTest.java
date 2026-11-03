// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParseResult;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;

/**
 * 改女仆设置的能力：写法不对当场退回；目标不在了、不是女仆、模组装了联动没登记、键她不认识各自如实说；
 * 改成的、本来就是的、没能确认的照列。
 */
class MaidConfigureAbilityTest {

    /** 刻号由测试推进的本刻上下文。 */
    private static final class TestTick implements TickContext {
        private long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }

    /** 一只女仆替身：改设置时按给的报告收场。 */
    private static final class FakeMaid implements MaidSettings.Maid {
        final List<List<MaidSettings.Setting>> asked = new ArrayList<>();
        MaidSettings.Report report = new MaidSettings.Report(
                List.of(new MaidSettings.Applied("stay", "with_you", "in_area")), List.of("pickup"), List.of(), "work",
                List.of(new Change(Change.Kind.ENTITY_AFFECTED, "e3 小雪", 1, "改成在家附近活动")), List.of());

        @Override public String name() {
            return "小雪";
        }

        @Override public List<String> keys() {
            return List.of("skin", "stay", "pickup");
        }

        @Override public MaidSettings.Configure configure(List<MaidSettings.Setting> settings, Permissions permissions) {
            asked.add(settings);
            return new MaidSettings.Configure() {
                private int ticks;

                @Override public ActionStatus tick(TickContext context) {
                    return ++ticks < 3 ? ActionStatus.running() : ActionStatus.done();
                }

                @Override public String describe() {
                    return "改小雪的设置";
                }

                @Override public MaidSettings.Report report() {
                    return ticks < 3 ? MaidSettings.Report.nothing() : report;
                }
            };
        }
    }

    private static StepContext step(Goal goal) {
        return new StepContext() {
            @Override public Goal goal() {
                return goal;
            }

            @Override public int stepIndex() {
                return 0;
            }

            @Override public TickContext tick() {
                throw new IllegalStateException("决定阶段不应碰到每刻上下文");
            }
        };
    }

    private static StepDecision decide(FindsMaid maids, String params, Target target) {
        MaidConfigureModule module = new MaidConfigureModule(maids);
        ParseResult parsed = module.spec().paramSpecs().parse(JsonParser.parseString(params).getAsJsonObject());
        assertTrue(parsed.ok(), "参数应能解析：" + parsed.errors());
        Goal goal = new Goal("maicraft:maid_configure", null, target, parsed.params(), Permissions.DEFAULT, List.of(),
                null);
        return module.decide(step(goal));
    }

    /** 照真实路径开一条任务：决定阶段出输入，任务工厂造任务，推进到出结果。 */
    private static TaskResult run(FindsMaid maids, String params) {
        StepDecision.Run run = assertInstanceOf(StepDecision.Run.class, decide(maids, params, new Target.Seen("e3")));
        MaidConfigureTask task = new MaidConfigureTask(assertInstanceOf(MaidConfigureInput.class, run.input()), maids);
        TestTick tick = new TestTick();
        task.start(tick);
        for (int i = 0; i < 500; i++) {
            if (task.tick(tick) instanceof TickResult.Finished finished) return finished.result();
            tick.gameTick++;
        }
        throw new AssertionError("改设置任务没有按时收场");
    }

    private static Problem refused(StepDecision decision) {
        return assertInstanceOf(StepDecision.Finish.class, decision).result().problem();
    }

    @Test
    void 目标不是生物编号_当场退回() {
        Problem problem = refused(decide(id -> new FindsMaid.Found.Gone(), "{\"settings\":{\"stay\":\"in_area\"}}",
                new Target.Seen("b3")));
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
    }

    @Test
    void 值不是字符串_当场退回() {
        Problem problem = refused(decide(id -> new FindsMaid.Found.Gone(), "{\"settings\":{\"stay\":[\"in_area\"]}}",
                new Target.Seen("e3")));
        assertTrue(problem.message().contains("stay"));
    }

    @Test
    void 一项都没给_当场退回() {
        Problem problem = refused(decide(id -> new FindsMaid.Found.Gone(), "{\"settings\":{}}", new Target.Seen("e3")));
        assertEquals(Problem.Kind.INVALID_PARAMETER, problem.kind());
    }

    @Test
    void 不是女仆_按做不到() {
        TaskResult result = run(id -> new FindsMaid.Found.NotMaid("牛（minecraft:cow）", "minecraft", null),
                "{\"settings\":{\"stay\":\"in_area\"}}");
        assertEquals(Problem.Kind.NOT_POSSIBLE_HERE, result.problem().kind());
    }

    @Test
    void 模组装了联动没登记_按不支持() {
        TaskResult result = run(id -> new FindsMaid.Found.NotMaid("小雪（touhou_little_maid:maid）", "touhou_little_maid",
                "装的是 1.6.0，验证过的范围是 1.5.3 到 1.5.4，不登记"), "{\"settings\":{\"stay\":\"in_area\"}}");
        assertEquals(Problem.Kind.UNSUPPORTED, result.problem().kind());
        assertTrue(result.problem().message().contains("1.6.0"));
    }

    @Test
    void 她不在了_按目标没了() {
        TaskResult result = run(id -> new FindsMaid.Found.Gone(), "{\"settings\":{\"stay\":\"in_area\"}}");
        assertEquals(Problem.Kind.TARGET_GONE, result.problem().kind());
    }

    @Test
    void 键她不认识_列出她认的键且一项不改() {
        FakeMaid maid = new FakeMaid();
        TaskResult result = run(id -> new FindsMaid.Found.Maid(maid), "{\"settings\":{\"stay\":\"in_area\",\"hair\":\"long\"}}");
        assertEquals(Problem.Kind.INVALID_PARAMETER, result.problem().kind());
        assertTrue(result.problem().message().contains("hair"));
        assertTrue(result.problem().suggestion().contains("skin、stay、pickup"));
        assertTrue(maid.asked.isEmpty());
    }

    @Test
    void 改成了_按给的顺序交给她并照列变化() {
        FakeMaid maid = new FakeMaid();
        TaskResult result = run(id -> new FindsMaid.Found.Maid(maid),
                "{\"settings\":{\"stay\":\"in_area\",\"pickup\":\"true\"}}");
        assertEquals(TaskResult.Status.DONE, result.status());
        assertEquals(List.of(new MaidSettings.Setting("stay", "in_area"), new MaidSettings.Setting("pickup", "true")),
                maid.asked.getFirst());
        assertTrue(result.summary().contains("改成了 1 项"));
        assertTrue(result.summary().contains("pickup 本来就是"));
        assertEquals(1, result.changes().size());
        MaidConfigureTask.MaidConfigureDetails details =
                assertInstanceOf(MaidConfigureTask.MaidConfigureDetails.class, result.details());
        assertEquals("e3 小雪", details.maid());
        assertEquals("work", details.shiftNow());
    }

    @Test
    void 有一项没能确认_结论说出来() {
        FakeMaid maid = new FakeMaid();
        maid.report = new MaidSettings.Report(List.of(), List.of(), List.of(), "rest", List.of(),
                List.of(new Change(Change.Kind.OTHER, "e3 小雪 的 pickup", 0, "改成 false 后等了两秒没变")));
        TaskResult result = run(id -> new FindsMaid.Found.Maid(maid), "{\"settings\":{\"pickup\":\"false\"}}");
        assertTrue(result.summary().contains("没能确认"));
        assertEquals(1, result.unconfirmed().size());
    }
}
