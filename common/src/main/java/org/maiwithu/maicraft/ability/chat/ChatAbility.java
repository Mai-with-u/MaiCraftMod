// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.chat;

import org.maiwithu.maicraft.game.interaction.ChatDraftScreen;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 说话能力：向游戏聊天发一句话，对全体玩家可见。
 * 完成以聊天栏出现自己那条（本地回显）为准；回显读不到就按已提交但没能确认收场。
 *
 * <p>以 / 开头的是游戏命令。角色像生存玩家一样玩：/msg、/home、/help 这类普通玩家命令照发；
 * /tp、/give、/gamemode 这类管理员命令默认不执行（开局直接拒绝，这是本角色自己的规矩，不是游戏的拦截），
 * 实例配置（所有者在配置文件里改，不经任何工具参数）放开后管理员命令也按命令发送，
 * 以角色自己的权限交给服务器裁决——能不能成，服务器说了算。/maicraft 与 /mai 永远留给人。
 * 哪条算管理员命令见 CommandKinds。读别人的聊天与听消息归感知侧，本能力只管发。
 *
 * <p>给了 target（一只生物的 e#）就是对那只生物说：不进公屏、不当命令，经联动登记的说话办法走到它跟前、
 * 开它的对话框说（车万女仆的女仆）；它不会听人说话就如实说做不到。
 *
 * <p>公屏说一句话是插一下：原地打字、回车，几秒做完，不替换手上的目标，说完手上的事从原地接着做。
 * 发游戏命令（可能把角色传送走、改游戏状态）和对一只生物说话（要走过去、站着等回话）仍是主目标。
 */
public final class ChatAbility implements AbilityModule {

    private final SendsChatMessage sender;
    private final ReadsChatEcho echo;
    private final ChatDraftScreen drafts;
    /** 本实例是否放开管理员命令；来自所有者的实例配置，任何工具参数都改不了它。普通玩家命令不受它管。 */
    private final boolean allowGameCommands;
    /** 聊天逐字打字开关；来自所有者的实例配置。关掉时整句瞬间提交，调试时不用等逐字打完。 */
    private final boolean chatTypewriter;
    /** 服务器的命令树：单人时认得出模组自带的管理员命令；拿不到时只按名单认。 */
    private final KnowsAdminCommands adminCommands;
    /** 点名一只生物说话时找它的听法。 */
    private final FindsListener listeners;

    /** 没有命令树可问的一份：只按名单认管理员命令（离线测试、连远程服务器）。逐字打字按开着处理。 */
    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo, ChatDraftScreen drafts, boolean allowGameCommands) {
        this(sender, echo, drafts, allowGameCommands, true, KnowsAdminCommands.NONE);
    }

    // 不提供省略逐字打字开关的构造：实例配置接到能力是组装层的活，漏接曾经让配置恒被当成开着的，
    // 关不掉逐字打字。开关必须显式给，忘了接在编译期就报错，而不是运行时静默逐字。
    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo, ChatDraftScreen drafts, boolean allowGameCommands,
            boolean chatTypewriter, KnowsAdminCommands adminCommands) {
        this(sender, echo, drafts, allowGameCommands, chatTypewriter, adminCommands, FindsListener.NONE);
    }

    /** @param listeners 点名一只生物说话时找它的听法；没接联动时谁都不会听 */
    public ChatAbility(SendsChatMessage sender, ReadsChatEcho echo, ChatDraftScreen drafts, boolean allowGameCommands,
            boolean chatTypewriter, KnowsAdminCommands adminCommands, FindsListener listeners) {
        this.sender = sender;
        this.echo = echo;
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.allowGameCommands = allowGameCommands;
        this.chatTypewriter = chatTypewriter;
        this.adminCommands = Objects.requireNonNull(adminCommands, "adminCommands");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }

    @Override
    public AbilitySpec spec() {
        // 参数说明、能力说明与一句话用途都随实例而变：放开了就如实告知 LLM 命令会按命令发送，
        // 全量列表也看得到这一点；不放开时保持现行文案。
        String messageDoc = allowGameCommands
                ? "要发到游戏聊天或游戏命令栏的话，全体可见；以 / 开头的按游戏命令发，本实例连管理员命令也放行，以角色自己的权限执行"
                : "要发到游戏聊天的话，全体玩家可见；以 / 开头的按游戏命令发：/msg、/home、/help 这类普通玩家命令照发，"
                        + "/tp、/give、/gamemode 这类管理员命令角色不执行";
        String summary = (allowGameCommands
                ? "向游戏聊天发一句话，或发一条游戏命令（对全体玩家可见；本实例连管理员命令也允许执行）"
                : "向游戏聊天发一句话，或发一条普通玩家命令（对全体玩家可见）")
                + "；公屏说一句话插一下，不替换手上的目标";
        AbilityDoc doc = AbilityDoc.forAbility("chat");
        if (allowGameCommands) {
            doc = doc.withExtraNote("本实例连管理员命令也允许执行：`message` 以 `/` 开头时不论哪种命令都不拒绝"
                    + "（/maicraft 与 /mai 除外），以角色自己的权限交给服务器执行；上面「管理员命令角色不执行」"
                    + "的边界在本实例不适用。");
        }
        return new AbilitySpec(
                "maicraft:chat",
                summary,
                doc,
                ParamSpecs.of(
                        ParamSpec.of("message", ParamType.TEXT).required()
                                .doc(messageDoc + "；给了 target 就是对那只生物说，不进公屏，/ 开头的也照原话说给它听").build(),
                        ParamSpec.of("max_seconds", ParamType.INTEGER).range(0, 90)
                                .doc("对生物说话时，说完以后最多站着等回话几秒：默认 30，最多 90，0 表示确认它听到了就走；"
                                        + "对全体玩家说时不用给").build()),
                Set.of(TargetKind.SEEN),
                ExecutionMode.CONTROLS_PLAYER,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    // 公屏说一句话原地打字、几秒做完：插一下，手上的事从原地接着做。游戏命令会把角色传送走、改游戏状态，
    // 对一只生物说话要走过去、站着等回话，这两种仍替换手上的目标。和决定阶段一样按"/ 开头"认命令。
    @Override
    public ExecutionMode modeFor(Goal goal) {
        if (goal.target() != null) return ExecutionMode.CONTROLS_PLAYER;
        String message = goal.params().has("message") ? goal.params().text("message") : "";
        return message.startsWith("/") ? ExecutionMode.CONTROLS_PLAYER : ExecutionMode.CUTS_IN;
    }

    // 角色正忙（自卫、换气、撤离、动作停不下）半秒内腾不出身子时，公屏话不占身体，直接整句发出去，不再被反射挡掉。
    @Override
    public TaskResult finishWithoutControl(Goal goal, TickContext context) {
        if (modeFor(goal) != ExecutionMode.CUTS_IN) return null;
        if (goal.params().has("max_seconds")) {
            return TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, "max_seconds 只在对生物说话（给 target）时有用",
                            "对全体玩家说就不给 max_seconds"));
        }
        long tick = context == null ? -1 : context.gameTick();
        return ChatTask.sendDirectly(goal.params().text("message"), sender, tick);
    }

    @Override
    public StepDecision decide(StepContext step) {
        String message = step.goal().params().text("message");
        // 点名一只生物：对它说，不进公屏，也不按命令处理。
        if (step.goal().target() != null) return talkTo(step, step.goal().target(), message);
        if (step.goal().params().has("max_seconds")) {
            return new StepDecision.Finish(TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, "max_seconds 只在对生物说话（给 target）时有用",
                            "对全体玩家说就不给 max_seconds")));
        }
        // 命令的规矩放在决定阶段，判断只读、无副作用：/maicraft 与 /mai 留给人；管理员命令默认不碰，实例放开才发；
        // 普通玩家命令像生存玩家一样照发，能不能成由服务器裁决。
        if (message.startsWith("/") && CommandKinds.reserved(message)) {
            return new StepDecision.Finish(TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "/maicraft 与 /mai 是留给人的命令（/mai 管谁能当角色），角色不能改自己或别人的权限、Mod 设置",
                            "要改 Mod 设置、角色账号名单请人在游戏里敲")));
        }
        if (message.startsWith("/") && !allowGameCommands && CommandKinds.admin(message, adminCommands)) {
            return new StepDecision.Finish(TaskResult.failed("没有把话发出去",
                    Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            "管理员命令角色不执行：/" + CommandKinds.commandName(message)
                                    + " 要管理员权限，本实例没放开",
                            "像生存玩家一样靠能力去做：去哪用 travel、要东西用 obtain、改世界用 build；"
                                    + "普通玩家命令（/msg、/home、/help 这类）照常能发")));
        }
        return new StepDecision.Run(new ChatInput(message));
    }

    // 对生物说话：target 只认实体的观察编号；等回话默认 30 秒。它会不会听、话长不长在任务里对着此刻的它判断。
    private StepDecision talkTo(StepContext step, Target target, String message) {
        if (!(target instanceof Target.Seen seen) || !seen.id().startsWith("e")) {
            return new StepDecision.Finish(TaskResult.failed("没有把话说出去",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, "对生物说话的 target 要给一只生物的观察编号 e#（例如 e3）",
                            "要对全体玩家说就不给 target")));
        }
        int maxSeconds = step.goal().params().has("max_seconds")
                ? (int) step.goal().params().integer("max_seconds") : 30;
        return new StepDecision.Run(new TalkInput(seen.id(), message, maxSeconds, step.goal().permissions()));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 对生物说话的任务：听法、逐字打字开关经闭包交给任务。
        factories.register(TalkInput.class, input -> new TalkTask(input, listeners, chatTypewriter));
        factories.register(ChatInput.class, input -> {
            // 每次运行新建发话任务；聊天框、发送入口与回显通过闭包交给任务，不经全局单例。
            // 逐字打字关掉时任务直接从提交阶段开始，不再逐字打草稿。
            return new ChatTask(input.message(), sender, echo, drafts, chatTypewriter);
        });
    }
}
