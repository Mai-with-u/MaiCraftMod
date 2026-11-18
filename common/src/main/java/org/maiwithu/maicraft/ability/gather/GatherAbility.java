// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.maiwithu.maicraft.behavior.acquire.mining.ChainMiningLedger;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.field.ReplantsCrops;
import org.maiwithu.maicraft.behavior.interaction.HitsItemFrames;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.game.interaction.DigTool;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.TargetKind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 采集的能力：对世界里的一个目标做一次采集动作——收熟庄稼、挖方块、捡掉落物、打一下展示框取出里面的东西。
 * 决定只做一件事：把目标对象落实成一个位置（观察编号查最后看到的方位；坐标缺高度找柱列顶面），
 * 之后走到、看清、动手、等掉落物入包都交给采集任务；单点动作，不接来源排序，也不递归备料。
 */
public final class GatherAbility implements AbilityModule {

    private final SeesTargets seen;
    private final ReadsSpot world;
    private final ApproachesTargets approaches;
    private final DigsBlocks digs;
    private final ReadsToolRequirements tools;
    private final ReplantsCrops replants;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final HitsItemFrames frames;
    private final ChainMiningLedger chainLedger;

    public GatherAbility(SeesTargets seen, ReadsSpot world, ApproachesTargets approaches, DigsBlocks digs,
            ReadsToolRequirements tools, ReplantsCrops replants, PicksUpDrops drops, PermissionCheck permission,
            BackpackView backpack, OffhandContents offhand, HitsItemFrames frames) {
        this(seen, world, approaches, digs, tools, replants, drops, permission, backpack, offhand, frames,
                new ChainMiningLedger());
    }

    public GatherAbility(SeesTargets seen, ReadsSpot world, ApproachesTargets approaches, DigsBlocks digs,
            ReadsToolRequirements tools, ReplantsCrops replants, PicksUpDrops drops, PermissionCheck permission,
            BackpackView backpack, OffhandContents offhand, HitsItemFrames frames, ChainMiningLedger chainLedger) {
        this.seen = seen;
        this.world = world;
        this.approaches = approaches;
        this.digs = digs;
        this.tools = tools;
        this.replants = replants;
        this.drops = drops;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.frames = frames;
        this.chainLedger = Objects.requireNonNull(chainLedger, "chainLedger");
    }

    /** 观察编号对应的现场：最后看到的方位，以及它是方块、真正的地上掉落物，还是要打一下的展示框。 */
    public interface SeesTargets {

        Optional<SeenTarget> lookup(String id);

        /**
         * @param at             最后看到它的位置
         * @param block          是方块或地形特征时为 true
         * @param drop           是真正的地上掉落物（{@code ItemEntity}）时为 true
         * @param itemFrame      是展示框时它的游戏实体编号，不是为 -1
         * @param registeredDrop 登记时它是一只掉落物：这会儿活体与场景都核对不到时，采集靠它把
         *                       "多半已被捡走"与"根本不是掉落物"分开说
         * @param droppedItem    登记或现场这只掉落物装的物品注册 ID；非掉落物或说不出为 null。
         *                       目标物品已被原版自动拾取吸进背包、或进任务后核验没有误捡杂物时，都靠它确认
         * @param dropEntityId   是现场地上掉落物时它的游戏实体编号，采集任务靠它精确追捡这一件；不是或未知为 -1
         */
        record SeenTarget(WorldPosition at, boolean block, boolean drop, int itemFrame,
                boolean registeredDrop, String droppedItem, int dropEntityId) {

            SeenTarget(WorldPosition at, boolean block) {
                this(at, block, !block, -1);
            }

            SeenTarget(WorldPosition at, boolean block, int itemFrame) {
                this(at, block, !block && itemFrame < 0, itemFrame);
            }

            SeenTarget(WorldPosition at, boolean block, boolean drop, int itemFrame) {
                this(at, block, drop, itemFrame, false, null, -1);
            }

            SeenTarget(WorldPosition at, boolean block, boolean drop, int itemFrame,
                    boolean registeredDrop, String droppedItem) {
                this(at, block, drop, itemFrame, registeredDrop, droppedItem, -1);
            }
        }
    }

    @Override public AbilitySpec spec() {
        return new AbilitySpec("maicraft:gather",
                "采掉、收获、捡起世界里指定的东西；点名展示框就打一下取出里面的东西",
                AbilityDoc.forAbility("gather"),
                ParamSpecs.of(
                        ParamSpec.of("block", ParamType.BLOCK_OR_TAG)
                                .doc("配 position 目标用：要采的方块 ID").build(),
                        ParamSpec.of("item", ParamType.ITEM_OR_TAG)
                                .doc("认为会掉出的东西，用于确认；不给按实际掉落记录").build()),
                Set.of(TargetKind.SEEN, TargetKind.POSITION),
                ExecutionMode.CONTROLS_PLAYER, Set.of(), List.of(), Listing.LISTED);
    }

    @Override public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        Target target = goal.target();
        if (target == null) {
            return invalid("缺少目标对象（target）：要采哪格方块、哪株庄稼或哪个掉落物，得指一个");
        }
        ParamValues params = goal.params();
        String expectedItem = params.has("item") ? params.text("item") : null;
        if (target instanceof Target.Seen seenId) {
            // 观察编号在册与否当场能查：不在册或已失效，都是"目标没了"，不猜、不当成参数写岔。
            Optional<SeesTargets.SeenTarget> lookedUp = seen.lookup(seenId.id());
            if (lookedUp.isEmpty()) {
                return gone("观察编号 " + seenId.id() + " 对应的东西不在了（失效、走远或被拆）");
            }
            SeesTargets.SeenTarget found = lookedUp.get();
            // 实体编号既不是展示框、也不是真正的地上掉落物（例如生物、矿车、船或动态装置）时，不能误当成掉落物走过去空等拾取，也不能去挖它脚下的地。
            if (!found.block() && !found.drop() && found.itemFrame() < 0) {
                // 登记时是掉落物、这会儿活体与场景表都核对不到：多半是走近时被原版自动拾取吸进了背包，
                // 实体随之移除。先查背包确认——目标物品已经在身上就按达成收场；确认不上才报"不在了"，
                // 不再把它错报成"不是掉落物"，让人以为投喂被拒又扔一次。
                if (found.registeredDrop()) {
                    String targetItem = expectedItem != null ? expectedItem : found.droppedItem();
                    if (targetItem != null && backpack != null && countCarried(targetItem) > 0) {
                        return new StepDecision.Finish(TaskResult.done(seenId.id() + " 的 " + targetItem
                                + " 已经在身上（走近时自动拾取的）"));
                    }
                    return gone("观察编号 " + seenId.id() + " 对应的东西不在了（可能已经被捡起）");
                }
                return invalid("观察编号 " + seenId.id() + " 不是可挖的方块，也不是地上的掉落物或展示框，不能用 gather 采集");
            }
            // 点名地上掉落物时保留目标游戏实体编号，并在未显式传 item 参数时带上掉落物自身的物品 ID，
            // 让采集任务精确追捡该实体并核对目标物品入包，防止顺脚吸起无关杂物就误报完成。
            String resolvedExpected = expectedItem != null ? expectedItem
                    : (found.drop() ? found.droppedItem() : null);
            int dropEntityId = found.drop() ? found.dropEntityId() : -1;
            return new StepDecision.Run(new GatherSpot(found.at(), found.drop(),
                    null, resolvedExpected, goal.permissions(), true, describe(seenId.id(), found),
                    found.itemFrame(), goal.digTool(), dropEntityId));
        }
        if (target instanceof Target.Position position) {
            Optional<GatherSpot> resolved = resolvePosition(position, params, expectedItem, goal.permissions(),
                    goal.digTool());
            if (resolved.isPresent()) {
                return new StepDecision.Run(resolved.get());
            }
        }
        return invalid("目标对象说不清：gather 的 target 只认 seen（观察编号）或 position 加 block，"
                + "坐标要能落到一格上（缺高度时附近也找不到顶面）");
    }

    // 给日志与面板的一句话：点名的是方块、掉落物，还是要打一下的展示框。
    private static String describe(String id, SeesTargets.SeenTarget found) {
        if (found.itemFrame() >= 0) return "打一下 " + id + " 展示框，取出里面的东西";
        return "采集 " + id + (found.block() ? " 处的方块" : " 的掉落物");
    }

    // 目标物品此刻在背包主格里的件数：原版自动拾取吸走的东西从这里确认。
    // 只数主背包，与采集任务的拾取确认同一把尺。
    private int countCarried(String itemId) {
        int count = 0;
        for (BackpackStack stack : backpack.stacks()) {
            if (stack.itemId().equals(itemId)) count += stack.count();
        }
        return count;
    }

    // 坐标目标：y 可以省略，找这一柱列的顶面；整列没加载就说不清，不瞎猜高度。
    private Optional<GatherSpot> resolvePosition(Target.Position position, ParamValues params, String expectedItem,
            Permissions permissions, DigTool digTool) {
        // 给了高度就用（深板岩层、钻石层在 0 以下，负高度是合法的一格）；没给才找这一柱列的顶面，找不到才说不清。
        int y;
        if (position.y() != null) {
            y = position.y();
        } else {
            OptionalInt top = world.surfaceY(position.x(), position.z());
            if (top.isEmpty()) {
                return Optional.empty();
            }
            y = top.getAsInt();
        }
        String declared = params.has("block") ? params.text("block") : null;
        WorldPosition at = new WorldPosition(position.x(), y, position.z(), position.dimension());
        return Optional.of(new GatherSpot(at, false, declared, expectedItem, permissions, false,
                "采集 (" + position.x() + ", " + y + ", " + position.z() + ") 的方块", -1, digTool));
    }

    // 目标没了、写法不对都不进游戏就收场：一句话结论只带结论句，展开的原因只在问题里一份。
    private StepDecision gone(String message) {
        return notStarted(Problem.of(Problem.Kind.TARGET_GONE, message));
    }

    private StepDecision invalid(String message) {
        return notStarted(Problem.of(Problem.Kind.INVALID_PARAMETER, message,
                "用 observe 看一眼现场，拿观察编号或坐标再来"));
    }

    private static StepDecision notStarted(Problem problem) {
        return new StepDecision.Finish(TaskResult.builder(TaskResult.Status.FAILED, "没有开始采集：" + problem.headline())
                .problem(problem).build());
    }

    @Override public void registerTasks(TaskFactories factories) {
        factories.register(GatherSpot.class, input -> new GatherTask(input, approaches, digs, tools,
                world, replants, drops, permission, backpack, offhand, frames, input.permissions(), chainLedger));
    }
}
