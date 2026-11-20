// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 打开方块容器遇拒重试测试：主手拿着物品右键方块容器遭遇游戏拒绝时，
 * 按玩家常识切空手或非工具重试一次；最多重试一次，仍拒收或切手失败时如实返回游戏拒绝。
 */
class ClientMenuOpeningTest {

    private static final BlockPos CHEST = new BlockPos(4, 64, 4);
    private static final BringsPlayerClose INSTANT_APPROACH = (target, permissions) -> doneAction("走近");

    private static TickContext tick(long n) {
        return new TickContext() {
            @Override public long gameTick() { return n; }
            @Override public PlayerContext player() { return null; }
        };
    }

    private static Action doneAction(String describe) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.done(); }
            @Override public String describe() { return describe; }
        };
    }

    @Test
    void 主手遇拒时切空手或非工具重试一次_最多重试一次不再循环() {
        List<String> log = new ArrayList<>();
        int[] clicks = {0};
        MovesToMainhand clearHand = new MovesToMainhand() {
            @Override public Optional<Action> moveToMainhand(String itemId) { return Optional.empty(); }

            @Override public Optional<Action> clearHeldForOpen() {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        log.add("切手");
                        return ActionStatus.done();
                    }

                    @Override public String describe() { return "切空手"; }
                });
            }
        };
        ClientMenuOpening opening = new ClientMenuOpening(CHEST, Permissions.DEFAULT, MenuLayouts.VANILLA,
                INSTANT_APPROACH, confirmation -> new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        int n = ++clicks[0];
                        log.add("点击" + n);
                        return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "第" + n + "次未打开"));
                    }

                    @Override public String describe() { return "右键点开"; }
                }, clearHand, () -> null);

        ActionStatus last = ActionStatus.running();
        for (int i = 1; i <= 20; i++) {
            last = opening.tick(tick(i));
            if (!(last instanceof ActionStatus.Running)) break;
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, last);
        assertEquals(Problem.Kind.REFUSED_BY_GAME, failed.problem().kind());
        assertEquals(List.of("点击1", "切手", "点击2"), log, "首点遇拒后应切手重试一次，仍拒收则如实返回不再重试第二次");
        assertTrue(failed.problem().message().contains("第2次未打开"), failed.problem().message());
    }

    @Test
    void 切手重试时第二次点击成功即正常进入认领阶段() {
        List<String> log = new ArrayList<>();
        int[] clicks = {0};
        MovesToMainhand clearHand = new MovesToMainhand() {
            @Override public Optional<Action> moveToMainhand(String itemId) { return Optional.empty(); }

            @Override public Optional<Action> clearHeldForOpen() {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        log.add("切手");
                        return ActionStatus.done();
                    }

                    @Override public String describe() { return "切空手"; }
                });
            }
        };
        ClientMenuOpening opening = new ClientMenuOpening(CHEST, Permissions.DEFAULT, MenuLayouts.VANILLA,
                INSTANT_APPROACH, confirmation -> new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        int n = ++clicks[0];
                        log.add("点击" + n);
                        return n == 1
                                ? ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "手持工具未打开"))
                                : ActionStatus.done();
                    }

                    @Override public String describe() { return "右键点开"; }
                }, clearHand, () -> null);

        ActionStatus last = ActionStatus.running();
        for (int i = 1; i <= 20; i++) {
            last = opening.tick(tick(i));
            if (!(last instanceof ActionStatus.Running)) break;
        }
        // 离线替身没有真实客户端菜单可认领，第二次点击完成后走到认领返回 TARGET_GONE，证明第二次点击确实成功执行。
        ActionStatus.Failed afterDone = assertInstanceOf(ActionStatus.Failed.class, last);
        assertEquals(Problem.Kind.TARGET_GONE, afterDone.problem().kind());
        assertEquals(List.of("点击1", "切手", "点击2"), log);
    }

    @Test
    void 切手动作失败时仍如实返回首次游戏拒绝原因() {
        MovesToMainhand brokenClear = new MovesToMainhand() {
            @Override public Optional<Action> moveToMainhand(String itemId) { return Optional.empty(); }

            @Override public Optional<Action> clearHeldForOpen() {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "换手失败"));
                    }

                    @Override public String describe() { return "切手"; }
                });
            }
        };
        ClientMenuOpening opening = new ClientMenuOpening(CHEST, Permissions.DEFAULT, MenuLayouts.VANILLA,
                INSTANT_APPROACH, confirmation -> new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "首次点击被拒绝"));
                    }

                    @Override public String describe() { return "右键点开"; }
                }, brokenClear, () -> null);

        ActionStatus last = ActionStatus.running();
        for (int i = 1; i <= 20; i++) {
            last = opening.tick(tick(i));
            if (!(last instanceof ActionStatus.Running)) break;
        }
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, last);
        assertEquals(Problem.Kind.REFUSED_BY_GAME, failed.problem().kind());
        assertTrue(failed.problem().message().contains("首次点击被拒绝"), failed.problem().message());
    }

    @Test
    void 确认条件按过期即没生效包装_STUCK也能触发一次切手重试_到不了和目标消失不误触发() {
        List<String> log = new ArrayList<>();
        boolean[] expiryAsNotApplied = {false};
        int[] clicks = {0};
        MovesToMainhand clearHand = new MovesToMainhand() {
            @Override public Optional<Action> moveToMainhand(String itemId) { return Optional.empty(); }

            @Override public Optional<Action> clearHeldForOpen() {
                return Optional.of(new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        log.add("切手");
                        return ActionStatus.done();
                    }

                    @Override public String describe() { return "切空手"; }
                });
            }
        };
        ClientMenuOpening stuckOpening = new ClientMenuOpening(CHEST, Permissions.DEFAULT, MenuLayouts.VANILLA,
                INSTANT_APPROACH, confirmation -> {
                    expiryAsNotApplied[0] = confirmation.expiryMeansNotApplied();
                    return new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            int n = ++clicks[0];
                            log.add("点击" + n);
                            return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "第" + n + "次超时未开"));
                        }

                        @Override public String describe() { return "右键点开"; }
                    };
                }, clearHand, () -> null);

        ActionStatus last = ActionStatus.running();
        for (int i = 1; i <= 20; i++) {
            last = stuckOpening.tick(tick(i));
            if (!(last instanceof ActionStatus.Running)) break;
        }
        assertTrue(expiryAsNotApplied[0], "打开容器的确认条件必须声明过期即没生效");
        assertInstanceOf(ActionStatus.Failed.class, last);
        assertEquals(List.of("点击1", "切手", "点击2"), log, "STUCK 同样触发切手重试且最多一次");

        // 反向用例：UNREACHABLE 与 TARGET_GONE 绝不误触发切手重试。
        for (Problem.Kind nonRetryable : List.of(Problem.Kind.UNREACHABLE, Problem.Kind.TARGET_GONE)) {
            List<String> noSwapLog = new ArrayList<>();
            ClientMenuOpening nonRetry = new ClientMenuOpening(CHEST, Permissions.DEFAULT, MenuLayouts.VANILLA,
                    INSTANT_APPROACH, confirmation -> new Action() {
                        @Override public ActionStatus tick(TickContext context) {
                            noSwapLog.add("点击");
                            return ActionStatus.failed(Problem.of(nonRetryable, "非拒收失败"));
                        }

                        @Override public String describe() { return "右键点开"; }
                    }, clearHand, () -> null);
            ActionStatus status = ActionStatus.running();
            for (int i = 1; i <= 20; i++) {
                status = nonRetry.tick(tick(i));
                if (!(status instanceof ActionStatus.Running)) break;
            }
            assertInstanceOf(ActionStatus.Failed.class, status);
            assertEquals(List.of("点击"), noSwapLog, nonRetryable + " 不得误触发切手重试");
            assertFalse(noSwapLog.contains("切手"));
        }
    }
}
