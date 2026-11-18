// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.deposit;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.sources.MenuContainerTakes;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.inventory.containers.ClientSpotsContainers;
import org.maiwithu.maicraft.behavior.inventory.containers.StoresInContainer;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.TargetText;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;

/**
 * 存东西能力：把背包里的东西存进箱子、木桶、潜影盒这类容器。
 *
 * <p>从容器拿东西归 obtain。target 点名容器时只用它，
 * 不点名就在附近挑：已经放着同种东西的优先，一只满了换下一只。别人的箱子不乱塞，
 * 除非点名了它。存进 ME 网络时身上的无线终端和装着的终端都认，和腾地方、取货认终端是同一份。
 */
public final class DepositModule implements AbilityModule {

    private final DepositServices services;
    private final BackpackView backpack;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":deposit",
            "把背包里的东西存进容器",
            AbilityDoc.forAbility("deposit"),
            ParamSpecs.of(
                    ParamSpec.of("items", ParamType.ITEM_LIST)
                            .doc("存哪些（物品 ID 或 # 标签的列表）；不给就把随身要留的东西以外的全部存掉").build(),
                    ParamSpec.of("count", ParamType.INTEGER).range(1, 576)
                            .doc("一共存几件；不给就全存").build(),
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, 128).defaultValue(32)
                            .doc("自己挑容器时的范围（格）").build(),
                    ParamSpec.of("into", ParamType.CHOICE).choices("container", "network").defaultValue("container")
                            .doc("存进哪里：container 箱子、木桶这类容器；network ME 网络"
                                    + "（不给 target 先用身上的无线终端，没带走去附近的 ME 终端；给了 target 去那里的终端）").build(),
                    ParamSpec.of("source", ParamType.TEXT)
                            .doc("从哪里搬出再存（写法与 target 相同）：点名一只容器（b#、[x,y,z]）或储物生物（e#），"
                                    + "或给地标名、\"here\"、f# 把该处 radius 内箱子里的东西整理进 into=network 或点名的 target 容器；"
                                    + "给了它只搬从里面取出来的东西，角色原本随身带的保留不动").build()),
            // 前面某一步确认过的位置（previous）还没有地方记，解析不了：不列进接受的目标，免得说接受却用不了。
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    DepositModule(DepositServices services, BackpackView backpack) {
        this.services = services;
        this.backpack = backpack;
    }

    /**
     * 生产用：把角色上下文与各读端交给本包，拼好找容器（归属与盖子按游戏事实读）、
     * 找位置、挖盖子与标签判断的生产实现再建模块，启动清单只认这个入口。
     * 存放（走过去点开、搬、关上）用和腾地方同一份现场部件。
     */
    public static DepositModule live(Supplier<PlayerContext> contexts, StoresInContainer.Parts storing,
            CollectsBlocks digs, ReadsItemTags tags, WorldMemory memory,
            BackpackView backpack, BlockScanService scans, Protection protection, Supplier<Scene> scene,
            FacilityKinds facilities, Supplier<Optional<NetworkStorage>> network, ItemsToKeep kept,
            MenuLayouts layouts, BringsPlayerClose close, Interactions interactions, MovesToMainhand toMainhand,
            HoldsSneak sneaks) {
        return new DepositModule(new DepositServices(
                new ClientSpotsContainers(scans, contexts, memory, protection, facilities),
                storing,
                new LiveDepositPlaces(scene, memory, contexts),
                new NativeLidDigging(digs),
                tags, network, new LiveOffhandClearing(contexts), kept,
                new LiveCreatureStorage(scene, contexts, layouts, close, interactions, toMainhand, sneaks),
                // 来源容器取货与取物走同一份开箱搬货实现：点开、逐笔搬、刷世界记忆、通报、关上只有一份。
                new MenuContainerTakes(close, interactions, tags, memory, contexts, layouts,
                        storing.othersNotice())), backpack);
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看这一步怎么走：给了 source 时先核对写法，再交给任务；没有要存或要搬的东西时任务开工即完成。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        Target source = null;
        if (params.has("source")) {
            String rawSource = params.text("source");
            Optional<Target> parsed = TargetText.parse(rawSource);
            if (parsed.isEmpty()) {
                return new StepDecision.Finish(TaskResult.failed("source 写法不对",
                        Problem.of(Problem.Kind.INVALID_PARAMETER,
                                "source 要是容器观察编号（b3）、储物生物编号（e2）、坐标 [x,y,z]、地标名，或 \"here\"：" + rawSource,
                                "写法与 target 相同，例如 \"b5\"、\"[12,64,-8]\"、\"家\" 或 \"here\"")));
            }
            source = parsed.get();
        }
        List<String> items = params.has("items")
                ? params.list("items").stream().map(id -> id.toLowerCase(Locale.ROOT)).toList()
                : List.of();
        Integer count = params.has("count") ? (int) params.integer("count") : null;
        long radius = params.has("radius") ? params.integer("radius") : 32;
        boolean network = params.has("into") && params.text("into").equals("network");
        return new StepDecision.Run(new DepositInput(step.goal().target(), source, items, count, radius,
                step.goal().permissions(), network));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(DepositInput.class, input -> new DepositTask(input, services, backpack));
    }
}
