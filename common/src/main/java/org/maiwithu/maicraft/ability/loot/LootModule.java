// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.loot;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.containers.ClientSpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
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
 * 搜刮能力（maicraft:loot）：打开一只战利品容器，把值钱的和用得上的东西搬进背包。
 *
 * <p>不指定目标时在半径内自动挑最近一只还没开过的战利品容器（优先 natural_loot，其次 no_record）；
 * 点名自家或别的玩家放的容器时只打开看清并记下内容、一件不拿，以 NOT_POSSIBLE_HERE 收场提示改用 obtain。
 */
public final class LootModule implements AbilityModule {

    private final LootSeams.World world;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":loot",
            "打开战利品容器，按价值把稀罕物、装备、有用材料和食物拿进背包",
            AbilityDoc.forAbility("loot"),
            ParamSpecs.of(
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, 64).defaultValue(24)
                            .doc("不给 target 时在多远内挑没开过的战利品容器（格）").build()),
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    LootModule(LootSeams.World world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    /** 生产入口：组装现场扫描、归属判断、容器界面与稀罕战利品通报器。 */
    public static LootModule live(Supplier<PlayerContext> contexts, Supplier<Scene> scene, WorldMemory memory,
            BlockScanService scans, Protection protection, FacilityKinds facilities, CollectsBlocks collects,
            MenuLayouts layouts, BringsPlayerClose close, Interactions interactions, MovesToMainhand toMainhand,
            HoldsSneak sneaks, ItemsToKeep kept, RareLootNotice rareLootNotice) {
        return new LootModule(new LiveLootWorld(
                contexts, scene, memory,
                new ClientSpotsContainers(scans, contexts, memory, protection, facilities),
                protection, facilities, collects, layouts, close, interactions, toMainhand, sneaks, kept,
                rareLootNotice));
    }

    @Override
    public AbilitySpec spec() {
        return spec;
    }

    @Override
    public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        int radius = params.has("radius") ? (int) params.integer("radius") : 24;
        return new StepDecision.Run(new LootInput(step.goal().target(), radius, step.goal().permissions()));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        factories.register(LootInput.class, input -> new LootTask(input, world));
    }
}
