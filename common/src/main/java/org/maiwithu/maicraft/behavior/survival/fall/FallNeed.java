// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.survival.fall;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.survival.SurvivalSituation;
import org.maiwithu.maicraft.behavior.landing.CatchesFall;
import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 落地防护这项生存需求：意外往下掉、预计落地会掉血（或下面是虚空）时立刻处理，
 * 派落地防护临时任务就地找落点、用身上带着的东西接住自己；身上没带时先从随身无线终端取一件。
 * 走路自己在管这一掉时让开。
 */
public final class FallNeed implements SurvivalNeed {

    private final SurvivalSituation.SituationReader reader;
    /** 就地找落点与办法，交给一次空中救援；救不了为 null。 */
    private final Function<PlayerContext, CatchesFall> rescues;
    /** 身上没带落地料时，能从哪儿现取一件（联动模组登记；没有就当取不到）。 */
    private final Supplier<List<LandingMaterials>> landingMaterials;
    /** 走路自己正在跑落地救援。 */
    private final BooleanSupplier walkHandlesFall;

    public FallNeed(SurvivalSituation.SituationReader reader, Function<PlayerContext, CatchesFall> rescues,
                    BooleanSupplier walkHandlesFall) {
        this(reader, rescues, List::of, walkHandlesFall);
    }

    public FallNeed(SurvivalSituation.SituationReader reader, Function<PlayerContext, CatchesFall> rescues,
                    Supplier<List<LandingMaterials>> landingMaterials, BooleanSupplier walkHandlesFall) {
        this.reader = reader;
        this.rescues = rescues;
        this.landingMaterials = landingMaterials;
        this.walkHandlesFall = walkHandlesFall;
    }

    // 正往致命高度掉是眼前危险：主目标声明 survival_needs=off 也照样护住落地。
    @Override public boolean reactsToImmediateDanger() { return true; }

    @Override public String name() { return "落地防护"; }

    @Override
    public Urgency urgency(TickContext context) {
        // 走路里的落地救援正在管这一掉（出发点备好了料、空中等着放）：这里让开，免得两边抢着放水。
        if (walkHandlesFall.getAsBoolean()) return null;
        SurvivalSituation situation = reader.read(context);
        return situation == null ? null : FallDanger.assess(situation);
    }

    @Override
    public Task createTask(TickContext context) {
        return new FallGuardTask(reader, rescues, landingMaterials);
    }
}
