// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.perception.ClientCampPitSight;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 填平地面坑能力（maicraft:fill_pit）：只用身上现成多余的泥土、圆石等废料把营地或点名位置的地面竖坑与爆炸弹坑填平。
 */
public final class FillPitModule implements AbilityModule {

    private final FillPitSeams.Site site;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":fill_pit",
            "用身上现成多余的泥土、圆石把营地或指定位置的地面坑填平",
            AbilityDoc.forAbility("fill_pit"),
            ParamSpecs.of(
                    ParamSpec.of("radius", ParamType.INTEGER).range(4, 32).defaultValue(16)
                            .doc("不给 target 时在多远内把营地地面坑依次填平（格）").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    FillPitModule(FillPitSeams.Site site) {
        this.site = Objects.requireNonNull(site, "site");
    }

    /** 生产入口：复用场景的营地地面坑扫描读端与玩家行为层的垫块放置积木。 */
    public static FillPitModule live(Supplier<Scene> scene, WorldMemory memory, Protection protection,
                                     ClientCampPitSight campPits, WalkTo walks, Interactions interactions,
                                     ClientMovesToMainhand toMainhand, ItemsToKeep kept) {
        return new FillPitModule(new LiveFillPitSite(scene, memory, protection, campPits,
                walks, interactions, toMainhand, kept));
    }

    @Override
    public AbilitySpec spec() {
        return spec;
    }

    @Override
    public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        int radius = params.has("radius") ? (int) params.integer("radius") : 16;
        return new StepDecision.Run(new FillPitInput(step.goal().target(), radius, step.goal().permissions()));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(FillPitInput.class, input -> new FillPitTask(input, site));
    }
}
