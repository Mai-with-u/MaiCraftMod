// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 出口在界面里的机器投完料以后回头看产物：关着界面等一个加工周期，到点走过去点开界面看一眼，
 * 出了就按 collect 收进背包，没出再等一回；要的件数出够、连着几回都没出、或到了等待上限就收手。
 *
 * <p>等的那段是两件事之间的空当，手上没有动作：饿了吃一口、天黑去睡都插得进来，回来接着等同一批，
 * 不重投料；看到一半被叫走，这一眼作废，回来先补看一眼。隔多久看、几回没出收手由 {@link LookBackSchedule} 定。
 */
final class MenuOutputLookBack implements Action {

    /** 为什么收手。 */
    enum Ending {
        /** 要的件数出够了。 */
        ENOUGH,
        /** 连着几回看到都没出。 */
        NOTHING_NEW,
        /** 到了等待上限。 */
        TIME_UP
    }

    private final MachineInteractions interactions;
    private final BlockPos cell;
    private final String itemId;
    private final int wanted;
    private final boolean collect;
    private final Permissions permissions;
    private final long limitTicks;
    private final LookBackSchedule schedule;
    private final List<String> notes = new ArrayList<>();
    private long startTick = -1;
    private long lastTick;
    private long dueTick;
    private OutputLook looking;
    private Ending ending;

    /**
     * @param limitTicks 投完料最多等多久（刻），到了看完手上这一眼就收手
     * @param schedule   隔多久看一眼、几回没出收手、出了几件的账（开工前出口里已有的记在里面）
     */
    MenuOutputLookBack(MachineInteractions interactions, BlockPos cell, String itemId, int wanted, boolean collect,
            Permissions permissions, long limitTicks, LookBackSchedule schedule) {
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.cell = cell.immutable();
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.wanted = wanted;
        this.collect = collect;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.limitTicks = Math.max(1, limitTicks);
        this.schedule = Objects.requireNonNull(schedule, "schedule");
    }

    // 等到点 → 走过去点开看一眼（要收就收）→ 记账，够了、几回没出、到上限就收手，不然定下一回什么时候看。
    @Override public ActionStatus tick(TickContext context) {
        long now = context.gameTick();
        lastTick = now;
        if (startTick < 0) {
            startTick = now;
            dueTick = now + schedule.firstWait();
        }
        if (looking != null) return look(context, now);
        // 到上限前最后补看一眼：上限不在看的间隔上也不白等。
        if (now < Math.min(dueTick, startTick + limitTicks)) return ActionStatus.running();
        Optional<OutputLook> look = interactions.lookIntoMenu(cell, itemId, collect, permissions);
        if (look.isEmpty()) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED,
                    "这里点不开机器的界面，回去看不了出口", null));
        }
        looking = look.get();
        return ActionStatus.progressed();
    }

    // 看一眼：点不开、看不成如实收场，不对着打不开的机器反复跑；看完记账再定下一步。
    private ActionStatus look(TickContext context, long now) {
        ActionStatus status = looking.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        notes.addAll(looking.notes());
        if (status instanceof ActionStatus.Failed failed) {
            settleLook(false);
            return ActionStatus.failed(Problem.of(failed.problem().kind(),
                    "回去看出口没看成：" + failed.problem().message(), failed.problem().suggestion()));
        }
        settleLook(true);
        long waited = now - startTick;
        if (schedule.produced() >= wanted) return finish(Ending.ENOUGH);
        if (schedule.gaveUp()) return finish(Ending.NOTHING_NEW);
        if (waited >= limitTicks) return finish(Ending.TIME_UP);
        dueTick = now + schedule.nextWait(waited, wanted);
        return ActionStatus.progressed();
    }

    // 一眼收场：看完了按看到的记一回；没看完（看不成、被叫走）只把已经收进背包的记上，不当成一回没出。
    private void settleLook(boolean looked) {
        OutputLook done = looking;
        looking = null;
        done.close();
        if (looked) {
            schedule.record(done.seen(), done.taken());
        } else if (done.taken() > 0) {
            schedule.record(done.seen(), done.taken());
        }
    }

    private ActionStatus finish(Ending why) {
        ending = why;
        return ActionStatus.done();
    }

    /** 这次新出了几件（开工前就在出口里的不算）。 */
    int produced() {
        return schedule.produced();
    }

    /** 收进背包几件。 */
    int collected() {
        return schedule.collected();
    }

    /** 回去看了几回。 */
    int looks() {
        return schedule.looks();
    }

    /** 投完料到收手一共等了几刻（含看的那几趟）。 */
    long waitedTicks() {
        return startTick < 0 ? 0 : lastTick - startTick;
    }

    /** 为什么收手；还没收手、或看不成收场时为 null。 */
    Ending ending() {
        return ending;
    }

    /** 最后一眼收完还留在出口里几件。 */
    int leftInExit() {
        return schedule.leftInExit();
    }

    /** 看的那几趟里值得知道的事：点不开、搬不动、没能确认的那几下。 */
    List<String> notes() {
        return List.copyOf(notes);
    }

    // 等着的时候手上没有动作，是两件事之间的空当；看的时候照那一眼手上的动作说。
    @Override public Interruptibility interruptibility() {
        return looking == null ? Interruptibility.BETWEEN_ACTIONS : looking.interruptibility();
    }

    // 被生存需求叫走：看到一半的这一眼作废（界面请游戏关上），回来马上补看一眼；等着的就接着等，账不动。
    @Override public void pause() {
        if (looking == null) return;
        notes.addAll(looking.notes());
        settleLook(false);
        dueTick = lastTick;
    }

    // 被叫先到安全处再停：走过去看的那一趟交给它先停稳。
    @Override public boolean stopSafely() {
        return looking != null && looking.stopSafely();
    }

    @Override public boolean stoppedSafely() {
        return looking == null || looking.stoppedSafely();
    }

    @Override public void close() {
        if (looking != null) settleLook(false);
    }

    @Override public String describe() {
        return looking != null ? looking.describe()
                : "等机器做 " + itemId + "，过一会儿回去点开界面看（已看 " + schedule.looks() + " 回）";
    }
}
