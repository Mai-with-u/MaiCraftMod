// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.drops.ClientDropPickup;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.ModIdentity;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ClientRegisteredIds;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.ReadsRegisteredIds;
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
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 用东西能力：对一个方块或实体用一下手上的东西，就像玩家按一下右键。
 *
 * <p>开门、按按钮、拉杆、打开箱子看看里面、骑上马或船、剪羊毛、挤奶、舀水倒水、点火、
 * 耕地、在告示牌上写字。取代旧版的 interact、use_item、use_container 三个能力。
 */
public final class UseModule implements AbilityModule {

    private final UseServices services;
    /** 药水种类注册了哪些：potion 写错的不进世界，报相近的名字。 */
    private final ReadsRegisteredIds registeredIds;
    private final AbilitySpec spec = new AbilitySpec(
            ModIdentity.MOD_ID + ":use",
            "对一个方块或实体用一下手上的东西（开门、按钮、拉杆、开箱看看、骑乘、剪毛、点火……）",
            AbilityDoc.forAbility("use"),
            ParamSpecs.of(
                    ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                            .doc("手上拿什么；不给就空手。标签表示其中任意一种，例如 #minecraft:hoes").build(),
                    ParamSpec.of("potion", ParamType.TEXT)
                            .doc("拿哪种药水（药水种类 ID，例如 minecraft:fire_resistance）：身上几瓶药水长得一样时挑对得上的那瓶喝、扔；"
                                    + "配 item 写三种药水瓶之一，不给 item 当普通药水瓶").build(),
                    ParamSpec.of("block", ParamType.BLOCK_OR_TAG)
                            .doc("没指定是哪一个时按它找最近的；指定了时核对目标是不是它").build(),
                    ParamSpec.of("entity", ParamType.ENTITY_TYPE)
                            .doc("对实体用时它的类型；同 block，对实体").build(),
                    ParamSpec.of("count", ParamType.INTEGER).range(1, 64).defaultValue(1)
                            .doc("做几次；某一次没有效果就停下").build(),
                    ParamSpec.of("radius", ParamType.INTEGER).range(1, 128).defaultValue(32)
                            .doc("按 block、entity 找目标的范围（格）").build(),
                    ParamSpec.of("text", ParamType.TEXT)
                            .doc("写到告示牌上的文字，用换行分行，最多 4 行；给了 text 就必须空手").build(),
                    ParamSpec.of("keep_trying", ParamType.INTEGER).range(0, 10).defaultValue(0)
                            .doc("点下去没反应时接着点，最多这么多秒：传送带上跑着的东西要连着点才抓得到。"
                                    + "不给就点一下没反应就停（不重复点，免得做两次）").build(),
                    ParamSpec.of("sneak", ParamType.BOOLEAN).defaultValue(false)
                            .doc("true 时按住潜行再右键：潜行右键马、驴打开它的物品栏，潜行右键女仆让她坐下；"
                                    + "不给就不潜行。不能和 text 一起给").build(),
                    ParamSpec.of("face", ParamType.CHOICE)
                            .choices("up", "down", "north", "south", "east", "west")
                            .doc("点方块朝哪一面（方块外形上朝这边的表面都算）；不给就点看得见的任一面。"
                                    + "用扳手转机器时点哪一面决定绕哪根轴转").build()),
            // 前面某一步确认过的位置（previous）还没有地方记，解析不了：不列进接受的目标，免得说接受却用不了。
            Set.of(TargetKind.HERE, TargetKind.SEEN, TargetKind.LANDMARK, TargetKind.POSITION),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    UseModule(UseServices services, ReadsRegisteredIds registeredIds) {
        this.services = services;
        this.registeredIds = registeredIds;
    }

    /**
     * 生产用：把角色上下文与各读端交给本包，拼好读世界、组装交互、备手、搜索、出行、捡东西与看界面的
     * 生产实现再建模块，启动清单只认这个入口。缺的东西去拿一件走拿到物品的引擎（needs）。
     */
    public static UseModule live(Supplier<PlayerContext> context, Interactions interactions, BringsPlayerClose close,
            ClientMovesToMainhand toMainhand, ItemNeeds needs, Supplier<Scene> scene, BlockScanService scans,
            ClientGameRefusals refusals, WalkTo walks, DropConfirmations confirmations, WorldMemory memory,
            Supplier<Protection> protection, ReadsCreatureSituation creatures, InventorySpace space,
            FacilityKinds facilities, HoldsSneak sneak, MenuLayouts layouts) {
        return new UseModule(new UseServices(
                new LiveUseWorld(context),
                new LiveUseInteractions(interactions, context),
                close,
                new LiveHandPreparation(toMainhand, context),
                needs,
                new LiveSeenResolver(scene),
                new LiveNearbySearcher(scans, context),
                new UseSeams.ReadsGameRefusal() {
                    @Override public Optional<String> latestMessage() {
                        return refusals.latestMessage();
                    }

                    @Override public long chatMark() {
                        return refusals.chatMark();
                    }

                    @Override public List<String> systemMessagesSince(long mark) {
                        return refusals.systemMessagesSince(mark);
                    }
                },
                new MenuSignEditors(),
                new ClientDropPickup(context, walks, confirmations),
                new LiveUseTravel(walks),
                new LiveMenuLooks(context, layouts),
                memory,
                new LiveOthersThings(context, protection, creatures),
                space,
                facilities,
                sneak), ClientRegisteredIds.builtIn());
    }

    @Override public AbilitySpec spec() {
        return spec;
    }

    // 看现场决定这一步：参数组合在计划阶段一次报全，核对过就交给任务去做。
    @Override public StepDecision decide(StepContext step) {
        var params = step.goal().params();
        String item = params.has("item") ? params.text("item") : null;
        // 点名了药水种类：瓶子不写当普通药水瓶；拿在手上的是写着这种药水的那一瓶，不随手拿第一瓶。
        String potion = params.has("potion") ? lower(params.text("potion").trim()) : null;
        if (potion != null && item == null) item = StackKey.PLAIN_POTION;
        String block = params.has("block") ? params.text("block") : null;
        String entity = params.has("entity") ? params.text("entity") : null;
        List<String> textLines = linesOf(params.has("text") ? params.text("text") : null);
        List<String> invalid = UseDecider.invalidCombinations(step.goal().target() != null,
                item, block, entity, textLines);
        boolean sneak = params.has("sneak") && params.bool("sneak");
        // 潜行右键告示牌会把手里的东西放上去、不打开编辑界面：写字只能直接右键。
        if (sneak && !textLines.isEmpty()) {
            invalid.add("sneak 不能和 text 一起给：写告示牌要直接右键，潜行右键打不开编辑界面");
        }
        if (textLines.size() > 4) {
            invalid.add("告示牌最多写 4 行，现在给了 " + textLines.size() + " 行");
        }
        if (potion != null && !StackKey.POTION_BOTTLES.contains(lower(item))) {
            invalid.add("potion 只配药水瓶用（item 写 minecraft:potion、minecraft:splash_potion 或 "
                    + "minecraft:lingering_potion，不写当普通药水瓶），这里配的是 " + item);
        }
        if (potion != null && !registeredIds.ids(IdKind.POTION).contains(potion)) {
            invalid.add(registeredIds.missing(IdKind.POTION, potion));
        }
        if (!invalid.isEmpty()) {
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "参数用得不对")
                    .problem(Problem.of(Problem.Kind.UNSUPPORTED,
                            String.join("；", invalid), "按参数说明改一改再试")).build());
        }
        var rejected = UseDecider.rejectedItem(item);
        if (rejected.isPresent()) {
            // 不支持的东西不进游戏就收场：一句话结论只带结论句，展开的原因只在问题里一份。
            Problem unsupported = Problem.of(Problem.Kind.UNSUPPORTED, rejected.get().message(),
                    rejected.get().suggestion());
            return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, unsupported.headline())
                    .problem(unsupported).build());
        }
        long count = params.has("count") ? params.integer("count") : 1;
        long radius = params.has("radius") ? params.integer("radius") : 32;
        // 点哪一面：只对方块有意义，对实体给了也不用。
        Direction face = params.has("face") ? Direction.byName(params.text("face")) : null;
        int keepTrying = params.has("keep_trying") ? (int) params.integer("keep_trying") : 0;
        String held = potion == null ? lower(item) : new StackKey(lower(item), potion).written();
        return new StepDecision.Run(new UseInput(step.goal().target(), held, lower(block), lower(entity),
                count, radius, textLines, step.goal().permissions(), face, keepTrying, sneak));
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(UseInput.class, input -> new UseTask(input, services));
    }

    private static String lower(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    // 写告示牌的文字按换行分行；整段没有换行就是一行。
    private static List<String> linesOf(String text) {
        if (text == null || text.isBlank()) return List.of();
        return List.of(text.split("\\n", -1));
    }
}
