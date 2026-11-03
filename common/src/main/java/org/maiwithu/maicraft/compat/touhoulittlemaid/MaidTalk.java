// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;

import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.TypeChatDraft;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 对自己的女仆说话，照真人的身体动作做：走到她跟前 → 看着她发开对话框的包（真人按聊天键时车万女仆发的那一个）
 * → 对话框弹出后等它过了吞字的那几刻 → 逐字打字 → 回车 → 看她有没有开始想 → 站着等她回话。
 *
 * <p>回话从聊天栏认：她在头上换上文字气泡的同时把回话以灰字 {@code <她的名字> 回话} 只发给主人。
 * 打字途中被生存需求打断就关掉对话框，回来重新对准、重新开框从头打（对话框重开后输入框是空的）。
 * 她用自带的工具改了自己的状态（坐下、跟随或驻守、班次、工作），按说话前后各读一次对比列成变化。
 */
final class MaidTalk implements TalkTo {

    /** 她的对话框输入框只收 128 字（车万女仆的游戏事实）。 */
    static final int MAX_LENGTH = 128;
    /** 开框的包发出后最多等几刻对话框弹出。 */
    static final int DIALOG_WAIT_TICKS = 40;
    /** 对话框开了先等几刻：车万女仆在开框后 5 刻内吞字，免得把按键录进去。 */
    static final int SETTLE_TICKS = 6;
    /** 回车后最多等几刻看她有反应（冒出在想的气泡或新的话）。 */
    static final int HEARD_WAIT_TICKS = 40;
    /** 在想的气泡消失后再等几刻回话：气泡和灰字分两路同步，前后差一两刻。 */
    static final int AFTER_THINKING_TICKS = 20;
    /** 打字途中对话框没了，最多重开几次。 */
    private static final int REOPEN_LIMIT = 3;
    /** 看身边别的女仆在不在想的范围（格）：同名、别的在想的女仆影响认领回话。 */
    private static final double NEARBY = 32;

    private final TouhouLittleMaidCompat compat;
    private final PlayerServices services;
    private final AimsAndSubmits aim;

    MaidTalk(TouhouLittleMaidCompat compat, PlayerServices services) {
        this(compat, services, services.interactions()::aimAtEntityThenSubmit);
    }

    /** 离线测试用：对准她发包那一下由替身做。 */
    MaidTalk(TouhouLittleMaidCompat compat, PlayerServices services, AimsAndSubmits aim) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.aim = Objects.requireNonNull(aim, "aim");
    }

    /** 看着一只生物替模组发一次它自己的包，按给的条件等确认；生产实现是交互入口的同名一下。 */
    @FunctionalInterface
    interface AimsAndSubmits {
        Action toward(Entity target, String doing, Runnable submission, InteractionConfirmation confirmation,
                int confirmTicks);
    }

    @Override public Optional<Listener> listener(PlayerContext context, Entity creature) {
        if (!compat.isMaid(creature)) return Optional.empty();
        return Optional.of(new MaidListener(creature));
    }

    /** 这只女仆是不是角色的：车万女仆只对主人开对话框。 */
    private boolean mine(Entity maid) {
        PlayerContext player = services.context().get();
        if (player == null || player.localPlayer() == null || !(maid instanceof TamableAnimal tamed)) return false;
        UUID owner = tamed.getOwnerUUID();
        return owner != null && owner.equals(player.localPlayer().getUUID());
    }

    /** 一只女仆的听法。 */
    private final class MaidListener implements Listener {

        private final Entity maid;

        MaidListener(Entity maid) {
            this.maid = maid;
        }

        @Override public String name() {
            return compat.state(maid).name();
        }

        @Override public int maxLength() {
            return MAX_LENGTH;
        }

        // 不是自己的女仆、本机关了 AI 对话：真人对着她按聊天键什么都不会发生，如实说做不到。
        @Override public Optional<Problem> refusal() {
            if (!mine(maid)) {
                return Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        name() + "不是你的女仆：车万女仆只对主人开对话框，对着她按聊天键什么都不会发生", null));
            }
            if (!compat.aiChatEnabled()) {
                return Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                        "本机车万女仆的 AI 对话关着（LLMEnabled），对着她按聊天键不会打开对话框", null));
            }
            return Optional.empty();
        }

        @Override public boolean thinking() {
            return compat.state(maid).thinking();
        }

        @Override public Talk talk(String message, int maxSeconds, boolean typewriter, Permissions permissions) {
            return new Talking(maid, message, maxSeconds, typewriter, permissions);
        }
    }

    /** 说一句话的阶段：走近 → 开框 → 等吞字的几刻过去 → 打字 → 回车 → 看她有没有反应 → 等回话 → 说完。 */
    private enum Stage { APPROACH, OPEN, SETTLE, TYPE, ENTER, HEARD, REPLY, DONE }

    /** 对她说一句话的动作。 */
    private final class Talking implements Talk {

        private final Entity maid;
        private final int entityId;
        private final String message;
        private final int maxSeconds;
        private final boolean typewriter;
        private final Permissions permissions;
        private final DialogDraft draft;
        private Stage stage = Stage.APPROACH;
        private Action current;
        private int stageTicks;
        private int approaches;
        private int reopens;
        /** 开口前她的状态，和说完后对比出她自己改了什么。 */
        private MaidState before;
        private boolean satBefore;
        /** 开框前聊天栏的记号：之后收到的服务端系统消息才算这次说话前后的回应。 */
        private long chatMark = -1;
        /** 回车前她头上已有的气泡：之后冒出新的文字气泡也算她听到了。 */
        private Set<Long> bubblesBefore = Set.of();
        /** 回车后见过在想的别的女仆：只有她一只在想时名字对不上的回话也认，有同名的在想时分不清。 */
        private final Set<Integer> othersThinking = new HashSet<>();
        private final Set<Integer> sameNameThinking = new HashSet<>();
        private int quietTicks;
        private boolean delivered;
        private boolean replyPending;
        private ReplyClaim.Claimed claimed;
        private String unconfirmed;
        private String note;
        private List<Change> changes = List.of();

        Talking(Entity maid, String message, int maxSeconds, boolean typewriter, Permissions permissions) {
            this.maid = maid;
            this.entityId = maid.getId();
            this.message = message;
            this.maxSeconds = maxSeconds;
            this.typewriter = typewriter;
            this.permissions = permissions;
            this.draft = new DialogDraft(entityId);
        }

        @Override public ActionStatus tick(TickContext context) {
            if (stage == Stage.DONE) return ActionStatus.done();
            // 还没开口她就不在了：这句没说；已经回车了，就按已经知道的收场。
            if (maid.isRemoved() || !maid.isAlive()) {
                if (stage.compareTo(Stage.HEARD) < 0) {
                    closeCurrent();
                    return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "她不在了，这句话没说", null));
                }
                return finish();
            }
            return switch (stage) {
                case APPROACH -> approach(context);
                case OPEN -> open(context);
                case SETTLE -> settle();
                case TYPE -> type(context);
                case ENTER -> enter();
                case HEARD -> checkHeard();
                case REPLY -> reply();
                case DONE -> ActionStatus.done();
            };
        }

        // 走到她跟前：够得着、看得见她；开口前先记下她此刻的状态与聊天栏读到哪了。
        private ActionStatus approach(TickContext context) {
            if (before == null) {
                before = compat.state(maid);
                satBefore = sitting();
            }
            if (chatMark < 0) chatMark = services.refusals().chatMark();
            if (current == null) {
                current = services.approach().toward(ApproachTarget.ofEntity(maid.getBoundingBox()), permissions);
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Done) {
                closeCurrent();
                stage = Stage.OPEN;
                return ActionStatus.progressed();
            }
            if (status instanceof ActionStatus.Failed) closeCurrent();
            return status;
        }

        // 看着她发开对话框的包：等对话框真的弹出来；对不准就换个站位再来一次；没弹出来如实说没打开。
        private ActionStatus open(TickContext context) {
            if (current == null) {
                current = aim.toward(maid, "打开" + compat.state(maid).name() + "的对话框",
                        () -> compat.openDialog(entityId), dialogOpened(), DIALOG_WAIT_TICKS);
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Done) {
                closeCurrent();
                stage = Stage.SETTLE;
                stageTicks = 0;
                return ActionStatus.progressed();
            }
            if (!(status instanceof ActionStatus.Failed failed)) return status;
            closeCurrent();
            Problem problem = failed.problem();
            if (problem.kind() == Problem.Kind.UNREACHABLE && approaches++ < 2) {
                stage = Stage.APPROACH;
                return ActionStatus.running();
            }
            if (problem.kind() == Problem.Kind.UNREACHABLE || problem.kind() == Problem.Kind.TARGET_GONE
                    || problem.kind() == Problem.Kind.WRONG_TIME) {
                return status;
            }
            // 包发出去了，对话框却没弹出来：服务器关了 AI 对话、她已经不是你的、网络延迟都可能，说不准是哪个。
            unconfirmed = "对话框 2 秒内没打开，这句没打出去（服务器关了 AI 对话、她已经不是你的、网络延迟都可能）";
            claimed = claim(compat.state(maid));
            return finish();
        }

        // 对话框弹出来先等几刻：车万女仆开框后 5 刻内吞字。
        private ActionStatus settle() {
            if (!compat.dialogOpenFor(entityId)) return reopen();
            if (++stageTicks < SETTLE_TICKS) return ActionStatus.running();
            stage = Stage.TYPE;
            return ActionStatus.progressed();
        }

        // 逐字打进她的对话框，节奏与公屏说话相同；逐字打字关着就整句放进去。
        private ActionStatus type(TickContext context) {
            if (!typewriter) {
                if (!draft.show(message)) return reopen();
                stage = Stage.ENTER;
                return ActionStatus.progressed();
            }
            if (current == null) current = new TypeChatDraft(draft, message);
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Done) {
                // 打完不关框：回车还要对着这个框按。
                current = null;
                stage = Stage.ENTER;
                return ActionStatus.progressed();
            }
            if (status instanceof ActionStatus.Failed) return reopen();
            return status;
        }

        // 回车：先记下她头上已有的气泡，按下后对话框自己把话发出去并关上。框里的字不对就重开重打。
        private ActionStatus enter() {
            if (!compat.dialogOpenFor(entityId) || !message.equals(compat.dialogText())) return reopen();
            bubblesBefore = keys(compat.state(maid));
            compat.pressEnterInDialog();
            stage = Stage.HEARD;
            stageTicks = 0;
            return ActionStatus.progressed();
        }

        // 认听到：对话框关了，她开始想或冒出新的话；2 秒都没有，有提示按游戏拒绝交回，没提示按没能确认，不重说。
        private ActionStatus checkHeard() {
            stageTicks++;
            MaidState now = compat.state(maid);
            watchOthers();
            claimed = claim(now);
            boolean closed = !compat.dialogOpenFor(entityId);
            if (closed && (now.thinking() || newText(now) || claimed.answered())) {
                delivered = true;
                if (claimed.answered()) return answered();
                if (maxSeconds == 0) {
                    replyPending = true;
                    return finish();
                }
                stage = Stage.REPLY;
                stageTicks = 0;
                return ActionStatus.progressed();
            }
            if (stageTicks < HEARD_WAIT_TICKS) return ActionStatus.running();
            if (!claimed.notices().isEmpty()) return refused();
            unconfirmed = "话交出去了，但 2 秒内她没冒出在想的气泡，也没冒出新的话；不要重说";
            return finish();
        }

        // 等回话：认到了就收场；她不想了再等一会儿还没有，有提示按游戏拒绝，没提示按没等到；等满也按没等到。
        private ActionStatus reply() {
            stageTicks++;
            MaidState now = compat.state(maid);
            watchOthers();
            claimed = claim(now);
            if (claimed.answered()) return answered();
            quietTicks = now.thinking() ? 0 : quietTicks + 1;
            if (quietTicks >= AFTER_THINKING_TICKS) {
                if (!claimed.notices().isEmpty()) return refused();
                replyPending = true;
                return finish();
            }
            if (stageTicks >= maxSeconds * 20) {
                replyPending = true;
                return finish();
            }
            return ActionStatus.running();
        }

        private ActionStatus answered() {
            if (claimed.ambiguous()) {
                note = "身边有同名的女仆也在想，分不清是谁回的，原话都在 notices";
            }
            return finish();
        }

        private ActionStatus refused() {
            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "她那边回了提示，没有回话：" + String.join(" / ", claimed.notices()), null));
        }

        // 收场：说话前后对比她自己改了什么。
        private ActionStatus finish() {
            closeCurrent();
            if (before != null && !maid.isRemoved()) {
                changes = changesBetween(before, compat.state(maid), satBefore, sitting());
            }
            stage = Stage.DONE;
            return ActionStatus.done();
        }

        // 对话框打字途中没了（被别的界面顶掉、被关了）：重新对准、重新开框，从头打。
        private ActionStatus reopen() {
            closeCurrent();
            if (++reopens > REOPEN_LIMIT) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "她的对话框打字途中一再被关掉，这句没说完", "等眼前别的界面关掉再说"));
            }
            draft.close();
            stage = Stage.OPEN;
            return ActionStatus.running();
        }

        private ReplyClaim.Claimed claim(MaidState now) {
            List<String> messages = chatMark < 0 ? List.of() : services.refusals().systemMessagesSince(chatMark);
            PlayerContext player = services.context().get();
            String self = player == null || player.localPlayer() == null ? "" : player.localPlayer().getName().getString();
            return ReplyClaim.claim(messages, names(now), self, othersThinking.isEmpty(), !sameNameThinking.isEmpty(),
                    compat.personaGeneratedText());
        }

        // 回车后身边自己的别的女仆在不在想：只有她一只在想时名字对不上的回话也认；同名的也在想就分不清。
        private void watchOthers() {
            PlayerContext player = services.context().get();
            if (player == null || player.level() == null) return;
            Set<String> hers = names(compat.state(maid));
            for (Entity other : player.level().getEntities(maid, maid.getBoundingBox().inflate(NEARBY),
                    found -> compat.isMaid(found) && mine(found))) {
                MaidState state = compat.state(other);
                if (!state.thinking()) continue;
                othersThinking.add(other.getId());
                if (names(state).stream().anyMatch(hers::contains)) sameNameThinking.add(other.getId());
            }
        }

        private boolean newText(MaidState now) {
            return now.bubbles().stream().anyMatch(bubble -> bubble.kind() == MaidState.Bubble.Kind.TEXT
                    && !bubblesBefore.contains(bubble.key()));
        }

        private boolean sitting() {
            return maid instanceof TamableAnimal tamed && tamed.isInSittingPose();
        }

        // 对话框弹出来了才算开框这一下做成。
        private InteractionConfirmation dialogOpened() {
            return new InteractionConfirmation() {
                @Override public Verdict observe(PlayerContext context) {
                    return compat.dialogOpenFor(entityId) ? Verdict.APPLIED : Verdict.PENDING;
                }

                @Override public boolean expiryMeansNotApplied() {
                    return true;
                }
            };
        }

        private void closeCurrent() {
            if (current != null) current.close();
            current = null;
        }

        // 被生存需求打断：还没回车就关掉对话框，回来从走近开始重开重打；回车之后只是站着等，回来接着等。
        @Override public void pause() {
            if (stage.compareTo(Stage.HEARD) >= 0) return;
            closeCurrent();
            draft.close();
            stage = Stage.APPROACH;
        }

        @Override public void close() {
            closeCurrent();
            draft.close();
        }

        @Override public Interruptibility interruptibility() {
            return current == null ? Interruptibility.WORKING : current.interruptibility();
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return current == null ? Interruptibility.WORKING : current.interruptibility(context);
        }

        @Override public String describe() {
            return switch (stage) {
                case APPROACH -> "走到她跟前";
                case OPEN -> "看着她打开对话框";
                case SETTLE -> "等对话框能打字";
                case TYPE -> "往她的对话框里打字";
                case ENTER -> "回车";
                case HEARD -> "看她听到没有";
                case REPLY -> "等她回话";
                case DONE -> "说完了";
            };
        }

        @Override public Heard heard() {
            ReplyClaim.Claimed known = claimed == null ? new ReplyClaim.Claimed(null, false, List.of(), false) : claimed;
            String reply = known.ambiguous() ? null : known.reply();
            return new Heard(delivered, reply, replyPending, known.personaGenerated(), known.notices(), changes,
                    unconfirmed, note);
        }
    }

    /** 她的名字候选：此刻在本机的显示名、本机模型表里她皮肤的显示名。 */
    static Set<String> names(MaidState state) {
        Set<String> names = new LinkedHashSet<>();
        names.add(state.name());
        if (state.skinName() != null) names.add(state.skinName());
        return names;
    }

    private static Set<Long> keys(MaidState state) {
        Set<Long> keys = new HashSet<>();
        state.bubbles().forEach(bubble -> keys.add(bubble.key()));
        return keys;
    }

    /**
     * 她用这句话自己改了哪些状态：坐没坐、跟随或驻守、班次、工作。她的提示词不让她口头播报工具结果，所以只能这样确认。
     */
    static List<Change> changesBetween(MaidState before, MaidState after, boolean satBefore, boolean satAfter) {
        List<Change> changes = new ArrayList<>();
        String who = after.name();
        if (satBefore != satAfter) changes.add(affected(who, satAfter ? "她自己坐下了" : "她自己站起来了"));
        if (before.stayInArea() != after.stayInArea()) {
            changes.add(affected(who, after.stayInArea() ? "她自己改成了驻守（in_area）" : "她自己改成了跟着你（with_you）"));
        }
        if (before.shift() != after.shift()) {
            changes.add(affected(who, "她自己把班次改成了 " + after.shift().word()));
        }
        if (!before.workId().equals(after.workId())) {
            changes.add(affected(who, "她自己把工作换成了 " + after.workId()));
        }
        return changes;
    }

    private static Change affected(String who, String note) {
        return new Change(Change.Kind.ENTITY_AFFECTED, who, 1, note);
    }

    /** 她的对话框当草稿框：只往这只女仆开着的对话框里打，新长出来的字照真人按键打进去。 */
    private final class DialogDraft implements ChatDraftScreen {

        private final int entityId;

        DialogDraft(int entityId) {
            this.entityId = entityId;
        }

        @Override public boolean show(String text) {
            if (!compat.dialogOpenFor(entityId)) return false;
            String typed = compat.dialogText();
            // 框里已有的是草稿的开头：只打新长出来的那几个字；对不上（被别的东西打进了字）不硬改，回车前再核对。
            if (typed != null && text.startsWith(typed) && text.length() > typed.length()) {
                compat.typeIntoDialog(text.substring(typed.length()));
            }
            return true;
        }

        @Override public boolean showing() {
            return compat.dialogOpenFor(entityId);
        }

        @Override public void close() {
            if (compat.dialogOpenFor(entityId)) compat.closeDialog();
        }
    }
}
