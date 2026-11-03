// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.maid;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 改一只女仆的设置：找到她 → 核对键她都认 → 交给联动的办法打开她的界面逐项改。
 * 不是女仆按做不到，她所属的模组装了联动没登记按不支持；键不认识让 LLM 改写法，列出她认的键。
 */
final class MaidConfigureTask extends PhasedTask<MaidConfigureTask.Phase> {

    /** 改设置的阶段：找她 → 改。 */
    enum Phase { FIND, CONFIGURE }

    /** 多久没有真实进展算卡住：走过去、开界面、每项最多等两秒确认，一分钟足够。 */
    private static final long STUCK_AFTER_TICKS = 20L * 60;

    private final MaidConfigureInput input;
    private final FindsMaid maids;
    private MaidSettings.Maid maid;
    private MaidSettings.Configure configure;

    MaidConfigureTask(MaidConfigureInput input, FindsMaid maids) {
        super("改女仆的设置", Phase.FIND, new ProgressTracker(STUCK_AFTER_TICKS, Long.MAX_VALUE));
        this.input = Objects.requireNonNull(input, "input");
        this.maids = Objects.requireNonNull(maids, "maids");
    }

    /**
     * 改女仆设置的结果细节。
     *
     * @param maid     她的观察编号与名字
     * @param applied  改成了的项：键、原来的值、现在的值
     * @param skipped  本来就是目标值、没动的项
     * @param workHint 切了工作时界面写的启用条件；没切不给
     * @param shiftNow 她此刻在干什么（work / rest / sleep）
     */
    record MaidConfigureDetails(String maid, List<MaidSettings.Applied> applied, List<String> skipped,
                                List<String> workHint, String shiftNow) implements ResultDetails {}

    @Override protected Action enter(Phase phase) {
        if (phase != Phase.CONFIGURE) return null;
        configure = maid.configure(input.settings(), input.permissions());
        return configure;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case FIND -> find();
            case CONFIGURE -> runActionThen(context, this::finish);
        };
    }

    // 找她：不在了按目标没了；不是女仆按做不到；模组装了联动没登记按不支持；键她不认识让改写法。
    private Next<Phase> find() {
        return switch (maids.find(input.seenId())) {
            case FindsMaid.Found.Gone gone -> Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + input.seenId() + " 对应的生物已经不在了", "先 observe 看看她去哪了"));
            case FindsMaid.Found.NotMaid other when other.unregistered() != null -> Next.fail(Problem.of(
                    Problem.Kind.UNSUPPORTED, other.what() + " 是模组 " + other.modId() + " 的生物，这个模组装了，但它的联动没登记（"
                            + other.unregistered() + "），改不了设置", "看启动日志里这个模组的联动结论"));
            case FindsMaid.Found.NotMaid other -> Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    other.what() + " 不是女仆，没有女仆的设置可改", null));
            case FindsMaid.Found.Maid found -> keysKnown(found.maid());
        };
    }

    private Next<Phase> keysKnown(MaidSettings.Maid found) {
        maid = found;
        List<String> unknown = input.settings().stream().map(MaidSettings.Setting::key)
                .filter(key -> !found.keys().contains(key)).toList();
        if (!unknown.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.INVALID_PARAMETER,
                    found.name() + "不认这些设置项：" + String.join("、", unknown),
                    "她认的设置项：" + String.join("、", found.keys())));
        }
        return Next.go(Phase.CONFIGURE, "找到" + found.name());
    }

    // 改完：变化照列，等满没变的记进没能确认；结论说改成了几项、哪几项本来就是。
    private Next<Phase> finish() {
        MaidSettings.Report report = configure.report();
        report.changes().forEach(this::recordChange);
        report.unconfirmed().forEach(this::recordUnconfirmed);
        String summary = maid.name() + "：改成了 " + report.applied().size() + " 项";
        if (!report.skipped().isEmpty()) summary += "，" + String.join("、", report.skipped()) + " 本来就是";
        if (!report.unconfirmed().isEmpty()) summary += "；有一项改了没看到变化，没能确认";
        return Next.done(TaskResult.done(summary));
    }

    @Override protected ResultDetails details() {
        MaidSettings.Report report = configure == null ? MaidSettings.Report.nothing() : configure.report();
        String who = input.seenId() + (maid == null ? "" : " " + maid.name());
        return new MaidConfigureDetails(who, report.applied(), report.skipped(),
                report.workHint().isEmpty() ? null : report.workHint(), report.shiftNow());
    }

    @Override protected String describePhase(Phase value) {
        return switch (value) {
            case FIND -> "认她是不是女仆";
            case CONFIGURE -> "打开她的界面改设置";
        };
    }
}
