// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.gather;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.acquire.engine.PickedUpItems;
import org.maiwithu.maicraft.behavior.acquire.mining.ChainMiningLedger;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.TellsToolUse;
import org.maiwithu.maicraft.behavior.acquire.mining.ToolScene;
import org.maiwithu.maicraft.behavior.acquire.mining.VanillaBlockDrops;
import org.maiwithu.maicraft.behavior.acquire.engine.OffhandContents;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.field.ReplantsCrops;
import org.maiwithu.maicraft.behavior.interaction.HitsItemFrames;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.ItemMatch;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.progress.ProgressTracker;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.ResultDetails;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Next;
import org.maiwithu.maicraft.kernel.task.PhasedTask;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 采集的任务：走到跟前，看清现场，只收熟了的作物、挖够得着的方块，然后走过去捡起这一下掉出来的东西。
 * 收完庄稼、种子进了包再顺手种回去（种子从这次收获里出，没有就不补，补不上也不白收）；
 * 工具不够格不自己去做一把，如实说缺什么等级的工具——备工具是拿东西的事，两件事分开才都看得清。
 * 拿没拿到按背包里数量的变化确认：冷却中的掉落物照常等，不误报"附近没有"。
 * 点名的是展示框：走到够得着的地方左键打一下，框里的东西弹出来再捡；框空着不打（再打会把框本身打下来）。
 * 挖的是概率掉落的方块（草出种子、树叶出树苗、砾石出燧石）：挖开捡完就算这一下做完，没掉要的东西是常态，
 * 结果如实写掉了什么、没掉什么，不当成缺东西；点名要它掉的东西时挖的时候不拿剪刀和精准采集（拿了只掉方块自己）。
 */
final class GatherTask extends PhasedTask<GatherTask.Phase> {

    /** 任务的阶段：走近 → 看现场 → 动手（挖/收）→ 捡掉落物 → 补种。掉落物目标走近后直接捡；展示框走近后打一下再捡。 */
    enum Phase { WALK, JUDGE, DIG, HIT, PICK_UP, REPLANT }

    /** 半分钟没有真实进展算卡住；整件事最多做五分钟。 */
    private static final long STUCK_AFTER_TICKS = 20L * 30;
    private static final long MAX_TICKS = 20L * 60 * 5;
    /** 无精准采集破坏时不掉自身的原版功能设施已知掉落对照，避免误拦明确拆设施拿掉落的调用。 */
    private static final Map<String, String> FACILITY_KNOWN_DROPS = Map.of(
            "minecraft:campfire", "minecraft:charcoal",
            "minecraft:soul_campfire", "minecraft:soul_soil",
            "minecraft:ender_chest", "minecraft:obsidian");

    private final GatherSpot spot;
    private final ApproachesTargets approaches;
    private final DigsBlocks digs;
    private final ReadsToolRequirements tools;
    private final ReadsSpot world;
    private final ReplantsCrops replants;
    private final PicksUpDrops drops;
    private final PermissionCheck permission;
    private final BackpackView backpack;
    private final OffhandContents offhand;
    private final HitsItemFrames frames;
    private final Permissions permissions;
    private final ChainMiningLedger chainLedger;
    private int chainMark;
    private boolean chainSettled;
    private ChainMiningLedger.Summary settledChainMining;

    /** 动手前背包里每样东西有几件；捡没捡到以它和结算时的差为准。 */
    private Map<String, Integer> baseline;
    /** 看现场时确认的方块种类；挖与结算用。 */
    private String liveType;
    /** 看现场时确认是熟了的作物：收完要不要补种按它，挖开后格子空了就再也看不出来。 */
    private boolean matureCrop;
    /** 看现场时这格方块有机会掉的东西（草出种子、树叶出树苗）；不是概率掉落的方块为空。 */
    private List<VanillaBlockDrops.ChanceDrop> chanceDrops = List.of();
    /** 动手前脚边已经躺着的掉落物：捡东西只捡这一下新冒出来的。 */
    private Set<Integer> dropsBefore = Set.of();
    /** 下一阶段的动作：上一刻按现场备好，进入阶段时交给基类持有。 */
    private Action prepared;
    /** 这次挖方块那一步：挖完要问它用了什么工具，把工具现场记进结果。 */
    private Action digAction;
    /** 打展示框之前框里放着的东西（物品 ID）：结算时说清打出来的是什么。 */
    private String frameItem;
    /** 拾取阶段子动作是否确认完成（点名实体拾取时靠它排除顺脚吸起无关杂物的假完成）。 */
    private boolean dropPickupConfirmed;
    /** 拾取阶段子动作报告的失败原因；没有为 null。 */
    private Problem dropPickupProblem;

    GatherTask(GatherSpot spot, ApproachesTargets approaches, DigsBlocks digs, ReadsToolRequirements tools,
            ReadsSpot world, ReplantsCrops replants, PicksUpDrops drops, PermissionCheck permission,
            BackpackView backpack, OffhandContents offhand, HitsItemFrames frames, Permissions permissions) {
        this(spot, approaches, digs, tools, world, replants, drops, permission, backpack, offhand, frames,
                permissions, new ChainMiningLedger());
    }

    GatherTask(GatherSpot spot, ApproachesTargets approaches, DigsBlocks digs, ReadsToolRequirements tools,
            ReadsSpot world, ReplantsCrops replants, PicksUpDrops drops, PermissionCheck permission,
            BackpackView backpack, OffhandContents offhand, HitsItemFrames frames, Permissions permissions,
            ChainMiningLedger chainLedger) {
        super("采集", Phase.WALK, new ProgressTracker(STUCK_AFTER_TICKS, MAX_TICKS));
        this.spot = spot;
        this.approaches = approaches;
        this.digs = digs;
        this.tools = tools;
        this.world = world;
        this.replants = replants;
        this.drops = drops;
        this.permission = permission;
        this.backpack = backpack;
        this.offhand = offhand;
        this.frames = frames;
        this.permissions = permissions;
        this.chainLedger = chainLedger;
    }

    @Override protected Action enter(Phase phase) {
        if (phase == Phase.WALK) {
            // 出发前记下背包里每样东西有几件与挖掘统计游标：走过去时顺路吸进包的掉落物也算这次捡到的。
            snapshotBaseline();
            chainMark = chainLedger.mark();
            // 走近交给站位与靠近的模型；展示框按它挂着的包围盒找够得着的站位；
            // 要挖的方块在脚下、旁边站不了就地挖时，说明经记账口进结果。
            return spot.frame() ? frames.approach(spot.itemFrame(), spot.permissions())
                    : approaches.toward(asBlock(spot.at()), spot.permissions(), records());
        }
        Action next = prepared;
        prepared = null;
        return next;
    }

    @Override protected Next<Phase> tick(Phase phase, TickContext context) {
        return switch (phase) {
            case WALK -> runActionThen(context, () -> {
                // 展示框：到了先看框里有没有东西再打；掉落物目标：走近途中已把预期物品吸进包就直接结算，
                // 否则有目标实体编号就只追捡那一件实体，不扫脚边无关杂物。
                if (spot.frame()) return judgeFrame();
                if (spot.drop()) {
                    if (spot.expectedItem() != null
                            && gainedMatchesExpected(gainedSinceBaseline(), spot.expectedItem())) {
                        return settle();
                    }
                    Action pick = spot.dropEntityId() >= 0
                            ? drops.pickUpOne(spot.dropEntityId())
                            : drops.pickUpNewSince(Set.of());
                    return goWith(Phase.PICK_UP, pick, "是掉落物，走过去捡目标那一件");
                }
                return Next.go(Phase.JUDGE, "到跟前了，先看清是什么");
            });
            case JUDGE -> judge();
            case DIG -> digDone(context);
            case HIT -> hitDone(context);
            case PICK_UP -> pickedUp(context);
            case REPLANT -> replantDone(context);
        };
    }

    private Next<Phase> goWith(Phase phase, Action action, String why) {
        prepared = action;
        return Next.go(phase, why);
    }

    // 看现场：目标还在不在、是熟作物还是普通方块、工具与许可过不过。哪一关不过都以问题如实结束。
    private Next<Phase> judge() {
        Optional<String> type = world.blockTypeAt(spot.at());
        if (type.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "要采的那格已经是空的：方块不在了，或者那时候看到的位置现在没有东西"));
        }
        liveType = type.get();
        if (spot.declaredType() != null && !spot.declaredType().equals(liveType)) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE,
                    "要采的是 " + spot.declaredType() + "，但那里现在是 " + liveType));
        }
        // 按坐标指定目标且声明了预期物品，但该格是功能设施（营火、容器、工作站、厨锅等）且本身不会掉该物品时，
        // 拒绝挖掘：调用方多半是想捡落在设施旁或弹出在上的掉落物，直接挖会误拆设施。
        if (!spot.named() && !spot.drop() && !spot.frame()
                && spot.expectedItem() != null && !spot.expectedItem().isBlank()
                && world.isFacilityBlock(spot.at(), liveType)
                && !expectedMatchesFacility(liveType, spot.expectedItem())) {
            return Next.fail(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    spot.at().x() + ", " + spot.at().y() + ", " + spot.at().z() + " 处是功能设施 " + liveType
                            + "，并非期望物品 " + spot.expectedItem() + " 的掉落物，直接挖掘会误拆该设施",
                    "捡地面掉落物请用 travel 走近自动拾取，或先 observe 再按实体编号 e# 传给 gather 拾取；"
                            + "若确实要拆除该设施，请去掉不匹配的 item 参数或改用方块观察编号 b# 点名"));
        }
        if (world.isCrop(liveType)) {
            if (!world.matureCrop(spot.at(), liveType)) {
                // 没熟的被踩掉就白长了：只收熟的是采集的常识，没熟时如实说等成熟再来。
                return Next.fail(Problem.of(Problem.Kind.WRONG_TIME,
                        liveType + "还没熟，现在收不上东西", "等它成熟了再来收"));
            }
            matureCrop = true;
            return permissionOr("是熟了的作物，收");
        }
        // 草、树叶、砾石这类：挖一下不一定掉要的东西，结算时按"挖开了就算做完"如实写掉了什么。
        chanceDrops = VanillaBlockDrops.chanceDropsOf(liveType);
        Optional<String> required = tools.toolRequired(liveType);
        // 点名了工具就只认那一件：不够格也照挖，挖掉了没掉落由结算如实报，不再因为缺工具取消资格。
        if (spot.digTool() == null && required.isPresent()
                && !CarriedItems.hasToolThatSuffices(backpack, offhand, liveType, tools)) {
            // 工具不足不自动备：备一把镐可能是采矿石加合成的一大串，采集悄悄做完会让结果难读。
            // 先拿东西备一把工具再来采集，两步都清清楚楚。
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    "挖" + liveType + "需要" + required.get() + "（或更高级的工具），身上没有够格的；"
                            + "采集不会自己去备工具",
                    "先用拿东西备一件够格的工具，再来采集"));
        }
        return permissionOr("是一格普通方块，挖");
    }

    // 动手前过一次许可：按坐标给的，别人搭的、玩家放的一格都不动；LLM 用观察编号点名的，点名即许可
    // （和照明、施工一样），不然拒绝里叫它"用观察编号点名"、照做了还是同一句拒绝，LLM 会在这里打转。
    // 许可过了才记下脚边原有的掉落物、开挖。
    private Next<Phase> permissionOr(String why) {
        if (!spot.named()) {
            Optional<Problem> refusal = permission.blockAllowed(permissions,
                    PermissionCheck.WorldAction.DIG_BLOCK, spot.at(), liveType);
            if (refusal.isPresent()) return Next.fail(refusal.get());
        }
        // 点名要的是这格的概率掉落（打草要种子、打树叶要树苗）且未显式指定工具时：挖的时候不拿剪刀和精准采集，拿了只掉方块自己；
        // 其余普通方块采集统一走带工具准备的入口，缺对路工具且徒手耗时超过现做门槛时先备一把再挖。
        Optional<Action> dig = spot.digTool() == null && expectedChanceDrop().isPresent()
                ? digs.digForChanceDrops(asBlock(spot.at()))
                : digs.dig(asBlock(spot.at()), new DigsBlocks.ToolPrep(1, true, permissions, records(), spot.digTool()));
        if (dig.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作还没接上，采不了"));
        }
        dropsBefore = drops.nearby();
        digAction = dig.get();
        return goWith(Phase.DIG, digAction, why);
    }

    // 到展示框跟前了：框不在了、框里空着都如实说（空框再打就把框本身打下来了，不打）；
    // 有东西就记下脚边原有的掉落物，左键打一下。点名即许可，和挖点名的方块同一条。
    private Next<Phase> judgeFrame() {
        Optional<String> inside = frames.contents(spot.itemFrame());
        if (inside.isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.TARGET_GONE, "展示框不在了：被拆了，或者已经走出看得见的范围"));
        }
        if (inside.get().isEmpty()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, "展示框里是空的，打一下也弹不出东西（再打会把框本身打下来）",
                    "用 observe 看看别的展示框里放着什么"));
        }
        frameItem = inside.get();
        dropsBefore = drops.nearby();
        return goWith(Phase.HIT, frames.hit(spot.itemFrame()), "框里是 " + frameItem + "，打一下让它弹出来");
    }

    // 打完了：弹出来就走过去捡；打了没反应、够不着都如实说（确认没等到但框已经空了也照样去捡）。
    private Next<Phase> hitDone(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        boolean emptied = frames.contents(spot.itemFrame()).map(String::isEmpty).orElse(true);
        if (status instanceof ActionStatus.Failed failed && !emptied) {
            return Next.fail(Problem.of(failed.problem().kind(),
                    "打了一下展示框，框里的 " + frameItem + " 没弹出来：" + failed.problem().message(),
                    "换个站位再试；框被固定住或这里不让动的话打不出来"));
        }
        recordChange(Change.of(Change.Kind.ENTITY_AFFECTED, "从展示框里打出 " + frameItem, 1));
        return goWith(Phase.PICK_UP, drops.pickUpNewSince(dropsBefore), "框里的东西弹出来了，走过去捡");
    }

    // 挖开了：记下拆掉的方块，走过去捡这一下掉出来的东西。
    private Next<Phase> digDone(TickContext context) {        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordChange(Change.of(Change.Kind.BLOCK_BROKEN, liveType, 1));
                tellToolUse();
                yield goWith(Phase.PICK_UP, drops.pickUpNewSince(dropsBefore), "挖开了，走过去捡掉出来的东西");
            }
            case ActionStatus.Failed failed -> Next.fail(failed.problem());
        };
    }

    // 挖完这一格：问挖的那一步用了什么工具，记成结果里的一条（空手、换了工具都照实说）。
    private void tellToolUse() {
        if (digAction instanceof TellsToolUse tells) ToolScene.record(records(), tells.toolUses());
    }

    // 捡完了（捡不到的如实记一笔）：收的是熟作物就趁种子进了包顺手种回去，否则按背包变化结算。
    private Next<Phase> pickedUp(TickContext context) {
        ActionStatus status = runAction(context);
        if (status instanceof ActionStatus.Running) return Next.stay();
        dropPickupConfirmed = status instanceof ActionStatus.Done;
        if (status instanceof ActionStatus.Failed failed) {
            dropPickupProblem = failed.problem();
            recordAttempt("捡起掉出的东西", failed.problem().message());
        }
        if (matureCrop && replants != null) {
            Optional<Action> replant = replants.replant(asBlock(spot.at()), liveType);
            if (replant.isPresent()) {
                return goWith(Phase.REPLANT, replant.get(), "收完了，顺手把种子种回去");
            }
        }
        return settle();
    }

    // 补种成了、没种子、或游戏拒绝，都按已收的结算；补种失败不冒充没收成，记下事实。
    private Next<Phase> replantDone(TickContext context) {
        return switch (runAction(context)) {
            case ActionStatus.Running running -> Next.stay();
            case ActionStatus.Done done -> {
                recordAttempt("补种", "补种这步做完了（种回去了，或者没有种子没补）");
                yield settle();
            }
            case ActionStatus.Failed failed -> {
                recordAttempt("补种", "没补上：" + failed.problem().message() + "；已收的照算");
                yield settle();
            }
        };
    }

    private Next<Phase> settle() {
        Map<String, Integer> gained = gainedSinceBaseline();
        for (var entry : gained.entrySet()) {
            recordChange(Change.of(Change.Kind.ITEM_GAINED, entry.getKey(), entry.getValue()));
        }
        if (spot.drop() && !spot.frame()) {
            return settleDropTarget(gained);
        }
        // 方块采集显式指定了非概率掉落的预期物品时，顺脚吸起无关杂物不算拿到预期产物。
        if (!spot.frame() && spot.expectedItem() != null && expectedChanceDrop().isEmpty()
                && !gainedMatchesExpected(gained, spot.expectedItem())) {
            String unrelated = gained.isEmpty() ? "" : "（顺路吸起了 " + gainedText(gained) + "，并非目标掉落）";
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    spot.expectedItem() + " 没进背包：采开了但没拿到目标掉落物" + unrelated));
        }
        if (!gained.isEmpty()) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "采集完成：" + gainedText(gained) + " 进了背包" + missedChance(gained)).details(details()).build());
        }
        if (spot.frame()) {
            return Next.fail(Problem.of(Problem.Kind.NOT_FOUND, frameItem
                    + " 从展示框里打出来了，但没进背包（掉在够不着的地方，或被别人捡走）", "看看附近地上，用 gather 点名那个掉落物再捡"));
        }
        // 概率掉落的方块挖开了：这一下没掉是常态，算做完，写明没掉什么、原版每块的机会。
        if (!chanceDrops.isEmpty() && (spot.expectedItem() == null || expectedChanceDrop().isPresent())) {
            return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                    "采集完成：挖开了 " + liveType + "，这一下什么都没掉" + chanceText()).details(details()).build());
        }
        if (spot.expectedItem() != null) {
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM,
                    spot.expectedItem() + " 没进背包：采开了但没等到掉落物（可能没掉、被别人捡走，"
                            + "或掉在够不着的地方）"));
        }
        // 非概率掉落方块破坏后零掉落且未收获任何物品：如实上报未掉落失败事实，不再静默报"采集完成"。
        return Next.fail(Problem.of(Problem.Kind.NOT_FOUND,
                "挖开了 " + liveType + "，但没有东西掉落进背包（该方块可能本身无掉落、工具不对路、被别人捡走，或掉在够不着的地方）",
                "确认方块是否需要精准采集或特定工具，或检查附近地面掉落物"));
    }

    // 点名捡地上掉落物的核验：必须确认目标掉落物实体已拾起或预期物品确实进包，顺脚吸起无关杂物绝不判完成。
    private Next<Phase> settleDropTarget(Map<String, Integer> gained) {
        if (spot.expectedItem() != null) {
            if (gainedMatchesExpected(gained, spot.expectedItem())) {
                return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                        "采集完成：" + gainedText(gained) + " 进了背包").details(details()).build());
            }
            String unrelated = gained.isEmpty()
                    ? "：目标掉落物没捡到（可能掉在够不着的地方或已被收走）"
                    : "（顺路吸起了 " + gainedText(gained) + "，但目标掉落物没捡到）";
            return Next.fail(Problem.of(Problem.Kind.NEED_ITEM, spot.expectedItem() + " 没进背包" + unrelated));
        }
        if (spot.dropEntityId() >= 0 && !dropPickupConfirmed) {
            Problem cause = dropPickupProblem != null ? dropPickupProblem
                    : Problem.of(Problem.Kind.TARGET_GONE, "点名的掉落物没捡到（可能已被收走或够不着）");
            return Next.fail(cause);
        }
        if (gained.isEmpty()) {
            return Next.fail(dropPickupProblem != null ? dropPickupProblem
                    : Problem.of(Problem.Kind.TARGET_GONE, "点名的掉落物没进背包（可能已被收走或够不着）"));
        }
        return Next.done(TaskResult.builder(TaskResult.Status.DONE,
                "采集完成：" + gainedText(gained) + " 进了背包").details(details()).build());
    }

    // 判定调用方声明的预期物品是否与该功能设施本身或其已知挖掉掉落物一致（一致说明明确要拆设施，照常放行）。
    static boolean expectedMatchesFacility(String blockType, String expectedItem) {
        if (blockType == null || expectedItem == null) return false;
        if (expectedItem.equals(blockType)) return true;
        String knownFacilityDrop = FACILITY_KNOWN_DROPS.get(blockType);
        if (expectedItem.equals(knownFacilityDrop)) return true;
        String vanillaDrop = VanillaBlockDrops.droppedBy(blockType);
        if (expectedItem.equals(vanillaDrop)) return true;
        if (VanillaBlockDrops.chanceDropsOf(blockType).stream()
                .anyMatch(drop -> drop.itemId().equals(expectedItem))) {
            return true;
        }
        if (expectedItem.startsWith("#")) {
            return itemMatchesTag(blockType, expectedItem)
                    || (knownFacilityDrop != null && itemMatchesTag(knownFacilityDrop, expectedItem));
        }
        return false;
    }

    private static boolean itemMatchesTag(String itemId, String tagSpec) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        Optional<Item> item = key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
        return item.isPresent() && item.get() != Items.AIR
                && ItemMatch.matches(new ItemStack(item.get()), tagSpec);
    }

    private static boolean gainedMatchesExpected(Map<String, Integer> gained, String expected) {
        if (expected == null || gained.isEmpty()) return false;
        if (!expected.startsWith("#")) {
            return gained.getOrDefault(expected, 0) > 0;
        }
        for (Map.Entry<String, Integer> entry : gained.entrySet()) {
            if (entry.getValue() <= 0) continue;
            ResourceLocation key = ResourceLocation.tryParse(entry.getKey());
            Optional<Item> item = key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key);
            if (item.isPresent() && item.get() != Items.AIR
                    && ItemMatch.matches(new ItemStack(item.get()), expected)) {
                return true;
            }
        }
        return false;
    }

    @Override protected ResultDetails details() {
        if (!chainSettled) {
            chainSettled = true;
            settledChainMining = chainLedger.completeSince(chainMark).orElse(null);
        }
        return settledChainMining == null ? ResultDetails.NONE : new GatherDetails(settledChainMining);
    }

    // 点名要的东西正好是这格的概率掉落（草出种子、树叶出树苗）；不是为空。
    private Optional<VanillaBlockDrops.ChanceDrop> expectedChanceDrop() {
        if (spot.expectedItem() == null) return Optional.empty();
        return chanceDrops.stream().filter(drop -> drop.itemId().equals(spot.expectedItem())).findFirst();
    }

    // 进了别的、点名要的概率掉落却没掉（打树叶掉了木棍、没掉树苗）：补一句没掉什么、原版每块的机会。
    private String missedChance(Map<String, Integer> gained) {
        return expectedChanceDrop().filter(drop -> !gained.containsKey(drop.itemId()))
                .map(drop -> "；这一下没掉 " + drop.itemId() + "（原版" + drop.odds(0).describe() + "，没掉是常态）")
                .orElse("");
    }

    // 什么都没掉时说清它本来有机会掉什么：点名的那样，没点名就列这格方块的全部概率掉落。
    private String chanceText() {
        List<VanillaBlockDrops.ChanceDrop> named = expectedChanceDrop().map(List::of).orElse(chanceDrops);
        List<String> parts = named.stream().map(drop -> drop.itemId() + " 原版" + drop.odds(0).describe()).toList();
        return "（有机会掉 " + String.join("、", parts) + "，没掉是常态）";
    }

    /** 动手以来捡到的：原版拾取只进背包主格，主背包比起开工时的差就是捡到的。 */
    private Map<String, Integer> gainedSinceBaseline() {
        return PickedUpItems.gainedSince(baseline, backpack);
    }

    private void snapshotBaseline() {
        baseline = PickedUpItems.byItem(backpack);
    }

    private String gainedText(Map<String, Integer> gained) {
        List<String> parts = gained.entrySet().stream()
                .map(entry -> entry.getValue() + " 个 " + entry.getKey())
                .toList();
        return String.join("、", parts);
    }

    private static BlockPos asBlock(WorldPosition at) {
        return new BlockPos(at.x(), at.y(), at.z());
    }
}
