// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.Optional;
import java.util.Set;

import org.maiwithu.maicraft.behavior.inventory.drops.ThrowsItems;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 做一种肢体动作：认出对着谁 → 拿出东西（拍一下先空出手）→ 扔东西、拍一下要先走到他跟前 → 逐下做 → 选回原来那格快捷栏。
 *
 * <p>做完视线停在他身上，手上原来的活接着做时由那件任务自己转走；拿出东西（hold）就是要拿着，不选回去。
 * 冲着一个人做完了，告诉被拍了回头看"回过他了"。做成几下按游戏里真发生的算，没做的几下写清原因。
 */
final class NonverbalTask extends PhasedTask<NonverbalTask.Phase> {

    /** 认人 → 拿东西 → 走过去 → 做 → 收手。 */
    enum Phase { FIND, TAKE_OUT, CLOSE_IN, DO, PUT_BACK }

    /** 扔东西：他要在几格内，走到离他几格。 */
    static final double TOSS_FROM_BLOCKS = 6;
    static final double TOSS_NEAR_BLOCKS = 2;
    /** 拍一下：他要在几格内，走到离他几格（伸手够得着）。 */
    static final double POKE_FROM_BLOCKS = 3;
    static final double POKE_NEAR_BLOCKS = 2.5;
    /** 选回原来那格快捷栏最多试几刻。 */
    static final int PUT_BACK_TICKS = 20;

    private final NonverbalInput input;
    private final NonverbalMoves moves;
    private final PokesGiven pokes;
    private NonverbalMoves.Partner partner;
    private Set<Integer> items = Set.of();
    private String heldItem;
    private int slot = -1;
    private Action hand;
    private Gesturing gesturing;
    private ThrowsItems throwing;
    private Gesturing facing;
    private int picked;
    private int putBackTicks;

    /** 结果细节：实际做了什么、对着谁、没做的几下和原因。 */
    record NonverbalDetails(String done, String to, String skipped) implements ResultDetails {}

    NonverbalTask(NonverbalInput input, NonverbalMoves moves, PokesGiven pokes) {
        super("做肢体动作", Phase.FIND, new ProgressTracker(100, NonverbalRhythm.MAX_TICKS + 200));
        this.input = input;
        this.moves = moves;
        this.pokes = pokes;
    }

    @Override protected Action enter(Phase phase) {
        return switch (phase) {
            case TAKE_OUT -> hand;
            case CLOSE_IN -> moves.walkNear(partner.entityId(), input.operation() == NonverbalOperation.TOSS
                    ? TOSS_NEAR_BLOCKS : POKE_NEAR_BLOCKS);
            case DO -> startDoing();
            default -> null;
        };
    }

    // 扔东西：看着他脚下一件一件扔；捡东西：走过去等它进包；别的：看着他逐下做。
    private Action startDoing() {
        return switch (input.operation()) {
            case TOSS -> {
                facing = new Gesturing(moves, NonverbalOperation.LOOK, 1, Integer.MAX_VALUE, partner,
                        NonverbalMoves.AimPoint.FEET);
                hold(facing);
                throwing = moves.toss(heldItem, input.count());
                yield throwing;
            }
            case PICK_UP -> moves.pickUp(items);
            default -> gesturing = new Gesturing(moves, input.operation(), input.count(), input.lookTicks(), partner,
                    input.operation() == NonverbalOperation.POKE ? NonverbalMoves.AimPoint.CENTER
                            : NonverbalMoves.AimPoint.EYES);
        };
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find(context);
            case TAKE_OUT -> hand == null ? afterTakeOut(context) : runActionThen(context, () -> afterTakeOut(context));
            case CLOSE_IN -> runActionThen(context, () -> Next.go(Phase.DO, "走到他跟前了"));
            case DO -> act(context);
            case PUT_BACK -> putBack(context);
        };
    }

    // 认人：骑着东西只能拿出东西；捡东西找地上那几件；别的找对着的人，扔东西、拍一下要有人且离得够近。
    private Next<Phase> find(TickContext context) {
        NonverbalOperation op = input.operation();
        if (op != NonverbalOperation.HOLD && moves.riding(context)) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "骑着东西：蹲起会下来、转头会带着坐骑转、跳会让坐骑跳", "先下来再做"));
        }
        slot = moves.selectedSlot(context);
        if (op == NonverbalOperation.PICK_UP) {
            items = moves.pickable(context, input.target());
            if (items.isEmpty()) {
                return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, input.target() == null
                        ? "身边 6 格内没有别人刚扔过来的东西" : "那件东西不在了，或者在 6 格外", null));
            }
            return Next.go(Phase.DO, "地上有 " + items.size() + " 件要捡");
        }
        partner = moves.partner(context, input.target());
        if (partner == null && input.target() != null) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, "找不到要对着的人：走了、下线了，或者不在附近", null));
        }
        if (partner == null && op.needsSomeone()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, "没说对着谁，最近 30 秒也没人冲你做过肢体动作",
                    "用 target 点名"));
        }
        Optional<Problem> unreachable = tooFar(context);
        if (unreachable.isPresent()) return Next.fail(unreachable.get());
        if (op == NonverbalOperation.POKE && !pokes.allowed(partner.uuid(), context.gameTick())) {
            return Next.fail(Problem.of(Problem.Kind.WRONG_TIME,
                    "10 秒内已经拍过 " + partner.name() + " 2 下了，再拍就成连打了", "过几秒再拍，或者换个动作"));
        }
        Optional<Problem> noHand = chooseHand(context);
        return noHand.<Next<Phase>>map(Next::fail).orElseGet(() -> Next.go(Phase.TAKE_OUT,
                partner == null ? "没有对着的人，原地做" : "对着 " + partner.name()));
    }

    // 扔东西他要在 6 格内、拍一下要在 3 格内：再远就不是"插一下"能做的事，先走过去。
    private Optional<Problem> tooFar(TickContext context) {
        NonverbalOperation op = input.operation();
        if (!op.needsSomeone()) return Optional.empty();
        double limit = op == NonverbalOperation.TOSS ? TOSS_FROM_BLOCKS : POKE_FROM_BLOCKS;
        double far = moves.distanceTo(context, partner.entityId());
        if (far <= limit) return Optional.empty();
        return Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, Double.isInfinite(far)
                ? partner.name() + " 不在附近" : partner.name() + " 在 " + Math.round(far) + " 格外，"
                + op.words + "要在 " + Math.round(limit) + " 格内", "先走近他（travel 或 follow）"));
    }

    // 手上拿什么：拍一下、不带东西的 hold 空出手；带了东西就拿到主手；别的照原样。
    private Optional<Problem> chooseHand(TickContext context) {
        NonverbalOperation op = input.operation();
        if (op == NonverbalOperation.POKE || op == NonverbalOperation.HOLD && input.itemId() == null) {
            hand = moves.emptyHand().orElse(null);
            return hand != null ? Optional.empty() : Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "快捷栏和背包都满了，空不出手", "先腾一格"));
        }
        if (input.itemId() == null) return Optional.empty();
        heldItem = moves.carried(context, input.itemId()).orElse(null);
        if (heldItem == null) {
            return Optional.of(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + input.itemId(), null));
        }
        hand = moves.takeOut(context, heldItem).orElse(null);
        return hand != null ? Optional.empty()
                : Optional.of(Problem.of(Problem.Kind.NEED_ITEM, heldItem + " 换不到手上", null));
    }

    // 拿好了：hold 到此做完；扔东西、拍一下离得不够近先走过去；别的直接做。
    private Next<Phase> afterTakeOut(TickContext context) {
        NonverbalOperation op = input.operation();
        if (op == NonverbalOperation.HOLD) return finish();
        if (op.needsSomeone()) {
            double near = op == NonverbalOperation.TOSS ? TOSS_NEAR_BLOCKS : POKE_NEAR_BLOCKS;
            if (moves.distanceTo(context, partner.entityId()) > near) return Next.go(Phase.CLOSE_IN, "走到他跟前");
        }
        return Next.go(Phase.DO, "开始" + op.words);
    }

    // 做：扔东西时一边看着他脚下；做完记下扔出、捡到的东西，再选回原来那格快捷栏。
    private Next<Phase> act(TickContext context) {
        if (facing != null) facing.tick(context);
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        if (throwing != null) recordTossed();
        if (input.operation() == NonverbalOperation.PICK_UP) {
            picked = (int) items.stream().filter(id -> Double.isInfinite(moves.distanceTo(context, id))).count();
        }
        if (input.operation() == NonverbalOperation.POKE && gesturing != null && gesturing.done() > 0) {
            pokes.gave(partner.uuid(), context.gameTick());
        }
        if (status instanceof ActionStatus.Failed failed) return Next.fail(failed.problem());
        return Next.go(Phase.PUT_BACK, "做完了");
    }

    private void recordTossed() {
        String note = "扔给 " + partner.name();
        if (throwing.thrown() > 0) recordChange(new Change(Change.Kind.ITEM_DROPPED, heldItem, throwing.thrown(), note));
        if (throwing.unconfirmed() > 0) {
            recordUnconfirmed(new Change(Change.Kind.ITEM_DROPPED, heldItem, throwing.unconfirmed(), note + "，没等到确认"));
        }
    }

    // 选回原来那格快捷栏（本刻出不了手就下一刻再选，一秒还选不回就算了）。
    private Next<Phase> putBack(TickContext context) {
        boolean back = slot < 0 || moves.selectedSlot(context) == slot || moves.selectSlot(context, slot);
        if (!back && ++putBackTicks < PUT_BACK_TICKS) return Next.stay();
        return finish();
    }

    // 收场：冲着一个人做了就告诉回头看回过他了；一下都没做成按没做成交代原因。
    private Next<Phase> finish() {
        if (partner != null) moves.answered(partner.uuid());
        String done = doneWords();
        String skipped = gesturing == null ? null : gesturing.skipped();
        String to = partner == null ? null : partner.name();
        if (gesturing != null && gesturing.done() == 0 && skipped != null) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, skipped, null));
        }
        String summary = (to == null ? "" : "对着 " + to + "：") + done + (skipped == null ? "" : "；" + skipped);
        boolean partial = skipped != null || throwing != null && throwing.thrown() < input.count()
                || input.operation() == NonverbalOperation.PICK_UP && picked < items.size();
        return Next.done(TaskResult.builder(partial ? TaskResult.Status.PARTIAL : TaskResult.Status.DONE, summary).build());
    }

    // 实际做了什么：还没做到那一步（中途收场）的照实说没做。
    private String doneWords() {
        NonverbalOperation op = input.operation();
        if (op == NonverbalOperation.HOLD) return heldItem == null ? "空出了手" : "拿出了 " + heldItem;
        if (op == NonverbalOperation.TOSS) return "扔出 " + (throwing == null ? 0 : throwing.thrown()) + " 件 " + heldItem;
        if (op == NonverbalOperation.PICK_UP) return "捡到 " + picked + " 件（地上一共 " + items.size() + " 件）";
        int count = gesturing == null ? 0 : gesturing.done();
        return switch (op) {
            case LOOK -> count > 0 ? "看了 " + input.lookTicks() / 20 + " 秒" : "没看完";
            case POKE -> count > 0 ? "拍了一下" : "没拍到";
            default -> op.words + " " + count + " 下";
        };
    }

    @Override protected ResultDetails details() {
        return new NonverbalDetails(doneWords(), partner == null ? null : partner.name(),
                gesturing == null ? null : gesturing.skipped());
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "认人";
            case TAKE_OUT -> "拿东西";
            case CLOSE_IN -> "走到他跟前";
            case DO -> input.describe();
            case PUT_BACK -> "收手";
        };
    }
}
