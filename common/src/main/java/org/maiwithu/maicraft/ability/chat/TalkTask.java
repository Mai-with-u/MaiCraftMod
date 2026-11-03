// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 对一只生物说话：找到它的听法 → 它还在想上一句就先等它想完 → 交给听法走近、开它的对话框、打字、交出去、等回话。
 *
 * <p>共用的规矩在这里：认观察编号；它不会听人说话按做不到说；它所属的模组装了联动却没登记上，如实说联动没登记；
 * 一句话超过它的对话框收的字数让 LLM 改短，不拆成两次说；它还在想上一句时不叠着说。回话不自动接，
 * 说不说下一句由 LLM 决定；回话本身不当成它做了什么的证据，它改了自己的状态按说话前后对比列成变化。
 */
final class TalkTask extends PhasedTask<TalkTask.Phase> {

    /** 说话的阶段：找它的听法 → 等它想完上一句 → 说。 */
    enum Phase { FIND, WAIT_QUIET, TALK }

    private final TalkInput input;
    private final FindsListener listeners;
    private final boolean typewriter;
    private TalkTo.Listener listener;
    private TalkTo.Talk talk;
    /** 等它想完上一句已经等了几刻。 */
    private long quietWaited;

    TalkTask(TalkInput input, FindsListener listeners, boolean typewriter) {
        // 卡住判定：等它想完与等回话都可能一直没有新进展，最长两段各等满 max_seconds，再留走近、开框、打字的余量。
        super("说话", Phase.FIND, new ProgressTracker(input.maxSeconds() * 20L * 2 + 20L * 60, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.typewriter = typewriter;
    }

    /**
     * 对生物说话的结果细节。
     *
     * @param message          实际说了哪句
     * @param to               对谁说的：观察编号与名字
     * @param delivered        它确实听到了（对话框关上且它有了反应）
     * @param reply            它的回话；没等到时不给
     * @param replyPending     没等到回话：之后的回话照常进 events(topic=chat)，不用重说
     * @param notices          这段时间聊天栏收到的其他系统消息原话；没有不给
     * @param personaGenerated 它这次只生成了人设、没回答；不是这种情况不给
     */
    record TalkDetails(String message, To to, boolean delivered, String reply, boolean replyPending,
                       List<String> notices, Boolean personaGenerated) implements ResultDetails {

        /** 对谁说的。 */
        record To(String id, String name) {}
    }

    @Override
    protected Action enter(Phase phase) {
        // 说这一句交给听法：走近、开框、打字、交出去、等回话都在它的动作里。
        if (phase != Phase.TALK) return null;
        talk = listener.talk(input.message(), input.maxSeconds(), typewriter, input.permissions());
        return talk;
    }

    @Override
    protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case WAIT_QUIET -> waitQuiet();
            case TALK -> runActionThen(context, this::finishTalk);
        };
    }

    // 找它的听法：不在了按目标没了；不会听人说话按做不到；联动没登记上按不支持；话太长让改短。
    private Next<Phase> find() {
        return switch (listeners.find(input.seenId())) {
            case FindsListener.Found.Gone gone -> Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + input.seenId() + " 对应的生物已经不在了，这句话没说", "先 observe 看看它去哪了"));
            case FindsListener.Found.Deaf deaf when deaf.unregistered() != null -> Next.fail(Problem.of(
                    Problem.Kind.UNSUPPORTED,
                    deaf.what() + " 是模组 " + deaf.modId() + " 的生物，这个模组装了，但它的联动没登记（"
                            + deaf.unregistered() + "），对它说不了话", "看启动日志里这个模组的联动结论"));
            case FindsListener.Found.Deaf deaf -> Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    deaf.what() + " 不会听人说话，这句话没说", "要对全体玩家说就不给 target"));
            case FindsListener.Found.Listening listening -> heard(listening.listener());
        };
    }

    private Next<Phase> heard(TalkTo.Listener found) {
        listener = found;
        Optional<Problem> refusal = found.refusal();
        if (refusal.isPresent()) return Next.fail(refusal.get());
        // 一句话的长度上限由它的对话框定：超了让 LLM 改短，不替它拆成两次说。
        int length = input.message().length();
        if (length > found.maxLength()) {
            return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    "这句话 " + length + " 个字，" + found.name() + "的对话框最多收 " + found.maxLength() + " 个字",
                    "改短了再说，不要拆成两次"));
        }
        return Next.go(Phase.WAIT_QUIET, "找到" + found.name() + "的听法");
    }

    // 它还在想上一句：先等它想完再说，叠着说它会乱；等满 max_seconds 还在想，这句不说，如实交回。
    private Next<Phase> waitQuiet() {
        if (!listener.thinking()) return Next.go(Phase.TALK, listener.name() + "没在想别的，开口");
        if (++quietWaited > input.maxSeconds() * 20L) {
            return Next.fail(Problem.of(Problem.Kind.WRONG_TIME,
                    listener.name() + "还在想上一句，等了 " + input.maxSeconds() + " 秒还没想完，这句话没说",
                    "它想完的回话会进 events(topic=chat)；过一会儿再说"));
        }
        return Next.stay();
    }

    // 说完了：变化照列，没看到它有反应记进 unconfirmed，不重说；结论按听到、回话、人设、没等到分开说。
    private Next<Phase> finishTalk() {
        TalkTo.Heard heard = talk.heard();
        heard.changes().forEach(this::recordChange);
        if (heard.unconfirmed() != null) {
            recordUnconfirmed(new Change(Change.Kind.OTHER, input.message(), 1, heard.unconfirmed()));
        }
        String who = listener.name();
        String summary;
        if (heard.reply() != null) {
            summary = who + "回话了：" + heard.reply();
        } else if (heard.personaGenerated()) {
            summary = who + "这次只生成了一份人设，没回答这句；再说一遍才会回答";
        } else if (!heard.delivered()) {
            summary = "没能确认" + who + "听到了这句" + (heard.unconfirmed() == null ? "" : "：" + heard.unconfirmed());
        } else if (input.maxSeconds() == 0) {
            summary = who + "听到了，没等回话就走了；它的回话会进 events(topic=chat)";
        } else {
            summary = who + "听到了，等了 " + input.maxSeconds() + " 秒还没回话；之后的回话会进 events(topic=chat)，不用重说";
        }
        if (heard.note() != null) summary += "；" + heard.note();
        return Next.done(TaskResult.done(summary));
    }

    @Override
    protected ResultDetails details() {
        TalkTo.Heard heard = talk == null ? TalkTo.Heard.nothing() : talk.heard();
        String name = listener == null ? null : listener.name();
        return new TalkDetails(input.message(), new TalkDetails.To(input.seenId(), name), heard.delivered(),
                heard.reply(), heard.replyPending(), heard.notices().isEmpty() ? null : heard.notices(),
                heard.personaGenerated() ? Boolean.TRUE : null);
    }

    @Override
    protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "认它会不会听";
            case WAIT_QUIET -> "等它想完上一句";
            case TALK -> "对它说话";
        };
    }
}
