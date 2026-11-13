// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsBlockTypes;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.PlacesBlockBeside;
import org.maiwithu.maicraft.behavior.acquire.spi.StepwiseActions;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.interaction.HoldsSneak;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.inventory.drops.PicksUpDrops;
import org.maiwithu.maicraft.behavior.inventory.hands.MovesToMainhand;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 原地一块接一块"摆基底方块 → 换手持物右键改造 → 挖回拾取"的动作：
 * 斧头给原木去皮、机械动力的手工涂抹做安山机壳/黄铜机壳/铜机壳/列车机壳都走同一条路。
 *
 * <p>每次只在身边安全空位放下一格基底方块，改造成新方块后当场挖回进包，不在基地通道摆一排木桩；
 * 若放下基底后换手或右键改造没做成，自动把刚放下的那块基底挖回背包，挖不回也如实记进变化与尝试记录。
 */
public final class InWorldBlockTransform implements Action {

    /** 把指定的手持物换到主手并对着一格方块右键改造，等那一格变成目标方块（或碎成空气掉出物品）。 */
    @FunctionalInterface
    public interface AppliesToBlock {
        Optional<Action> apply(BlockPos cell, WantedItem held, String resultBlockId, boolean breaksIntoAir);
    }

    /** 原地摆块改造所需的现场接缝：放一格、看那一格现在是什么、持物右键改造、挖回一格、捡掉落物。 */
    public record Seams(PlacesBlockBeside placer, ReadsBlockTypes blockTypes, AppliesToBlock applier,
                        CollectsBlocks collects, PicksUpDrops drops) {
        public Seams {
            Objects.requireNonNull(placer, "placer");
            Objects.requireNonNull(blockTypes, "blockTypes");
            Objects.requireNonNull(applier, "applier");
            Objects.requireNonNull(collects, "collects");
        }
    }

    private enum Stage { PLACE_BASE, APPLY_HELD, COLLECT_RESULT, CLEANUP_LEFTOVER }

    private final String baseBlockId;
    private final WantedItem heldItem;
    private final String resultId;
    private final boolean breaksIntoAir;
    private final int rounds;
    private final Permissions permissions;
    private final TaskRecords records;
    private final Seams seams;

    private Stage stage = Stage.PLACE_BASE;
    private int completed;
    private BlockPos placedCell;
    private Set<Integer> dropsBefore = Set.of();
    private Action active;
    private Problem failure;

    public InWorldBlockTransform(String baseBlockId, WantedItem heldItem, String resultId, boolean breaksIntoAir,
            int rounds, Permissions permissions, TaskRecords records, Seams seams) {
        if (rounds < 1) throw new IllegalArgumentException("至少要做一轮：" + rounds);
        this.baseBlockId = Objects.requireNonNull(baseBlockId, "baseBlockId");
        this.heldItem = Objects.requireNonNull(heldItem, "heldItem");
        this.resultId = Objects.requireNonNull(resultId, "resultId");
        this.breaksIntoAir = breaksIntoAir;
        this.rounds = rounds;
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.records = records;
        this.seams = Objects.requireNonNull(seams, "seams");
    }

    /**
     * 生产用的持物右键改造入口：先把匹配的手持物（具体物品或标签如 #minecraft:axes）换到主手，
     * 再对着放好的那一格右键；主手持斧且副手持盾时自动按住潜行再点，绕开原版举盾优先。
     */
    public static AppliesToBlock liveApplier(Supplier<PlayerContext> contexts, MovesToMainhand toMainhand,
            Interactions interactions, HoldsSneak sneak, ReadsItemTags itemTags) {
        Objects.requireNonNull(contexts, "contexts");
        Objects.requireNonNull(toMainhand, "toMainhand");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(itemTags, "itemTags");
        return (cell, held, resultBlockId, intoAir) -> {
            Action holdStep = new DeferredStep("把 " + held.specifier() + " 换到主手",
                    () -> prepareMainhand(contexts.get(), toMainhand, held, itemTags));
            Action clickStep = new DeferredStep("对着 " + cell.toShortString() + " 右键改造",
                    () -> Optional.of(clickBlock(contexts.get(), interactions, sneak, cell, resultBlockId, intoAir)));
            return Optional.of(new StepwiseActions("持 " + held.specifier() + " 改造方块", holdStep, clickStep));
        };
    }

    private static Optional<Action> prepareMainhand(PlayerContext current, MovesToMainhand toMainhand,
            WantedItem held, ReadsItemTags itemTags) {
        if (current == null || current.localPlayer() == null) {
            return Optional.of(failing(Problem.of(Problem.Kind.UNREACHABLE, "不在世界里，换不了主手物品", null)));
        }
        ItemStack main = current.localPlayer().getMainHandItem();
        if (!main.isEmpty()) {
            String mainId = BuiltInRegistries.ITEM.getKey(main.getItem()).toString();
            if (held.matches(mainId, itemTags.tagsOf(mainId))) return Optional.empty();
        }
        Inventory inventory = current.localPlayer().getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (held.matches(id, itemTags.tagsOf(id))) {
                return toMainhand.moveToMainhand(id)
                        .or(() -> Optional.of(failing(Problem.of(Problem.Kind.NEED_ITEM,
                                "身上的 " + id + " 换不到主手", null))));
            }
        }
        return Optional.of(failing(Problem.of(Problem.Kind.NEED_ITEM,
                "身上没有 " + held.specifier() + "，改不了刚放下的方块", null)));
    }

    private static Action clickBlock(PlayerContext current, Interactions interactions, HoldsSneak sneak,
            BlockPos cell, String resultBlockId, boolean intoAir) {
        if (current == null || current.localPlayer() == null) {
            return failing(Problem.of(Problem.Kind.UNREACHABLE, "不在世界里，点不了方块", null));
        }
        InteractionConfirmation confirmation = transformedConfirmation(cell, resultBlockId, intoAir);
        String mainId = current.localPlayer().getMainHandItem().isEmpty() ? null
                : BuiltInRegistries.ITEM.getKey(current.localPlayer().getMainHandItem().getItem()).toString();
        boolean shieldInOffhand = current.localPlayer().getOffhandItem().is(Items.SHIELD);
        if (ItemUseAim.sneaksPastOffhandShield(ItemUseAim.gestureOf(mainId), shieldInOffhand)) {
            return interactions.useBlockSneaking(cell, sneak, confirmation);
        }
        return interactions.useBlock(cell, confirmation);
    }

    private static InteractionConfirmation transformedConfirmation(BlockPos cell, String resultBlockId,
            boolean intoAir) {
        BlockPos frozen = cell.immutable();
        return context -> {
            if (context.level() == null || !context.level().isLoaded(frozen)) {
                return InteractionConfirmation.Verdict.PENDING;
            }
            BlockState now = context.level().getBlockState(frozen);
            if (intoAir) {
                return now.isAir()
                        ? InteractionConfirmation.Verdict.APPLIED
                        : InteractionConfirmation.Verdict.PENDING;
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(now.getBlock()).toString();
            return blockId.equals(resultBlockId)
                    ? InteractionConfirmation.Verdict.APPLIED
                    : InteractionConfirmation.Verdict.PENDING;
        };
    }

    private static Action failing(Problem problem) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) { return ActionStatus.failed(problem); }
            @Override public String describe() { return problem.headline(); }
        };
    }

    @Override
    public ActionStatus tick(TickContext context) {
        return switch (stage) {
            case PLACE_BASE -> tickPlace(context);
            case APPLY_HELD -> tickApply(context);
            case COLLECT_RESULT -> tickCollect(context);
            case CLEANUP_LEFTOVER -> tickCleanup(context);
        };
    }

    // 在身边安全空位放下一格基底方块，记下落点后再做下一步改造。
    private ActionStatus tickPlace(TickContext context) {
        if (active == null) {
            placedCell = null;
            Optional<Action> place = seams.placer().placeBeside(baseBlockId, pos -> placedCell = pos.immutable());
            if (place.isEmpty()) {
                return settleOrFail(Problem.of(Problem.Kind.UNREACHABLE, "放不下临时方块 " + baseBlockId, null));
            }
            active = place.get();
        }
        ActionStatus status = active.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        active.close();
        active = null;
        if (status instanceof ActionStatus.Failed failed) return settleOrFail(failed.problem());
        if (placedCell == null) {
            return settleOrFail(Problem.of(Problem.Kind.UNREACHABLE, "没拿到临时方块的落点", null));
        }
        if (breaksIntoAir && seams.drops() != null) dropsBefore = seams.drops().nearby();
        stage = Stage.APPLY_HELD;
        return ActionStatus.progressed();
    }

    // 换上手持物对着刚放下的那一格右键；若没改成，转去把刚放下的基底挖回包里，不留木桩。
    private ActionStatus tickApply(TickContext context) {
        if (active == null) {
            Optional<Action> apply = seams.applier().apply(placedCell, heldItem, resultId, breaksIntoAir);
            if (apply.isEmpty()) {
                failure = Problem.of(Problem.Kind.UNREACHABLE, "没法对着 " + placedCell.toShortString() + " 右键改造", null);
                stage = Stage.CLEANUP_LEFTOVER;
                return ActionStatus.progressed();
            }
            active = apply.get();
        }
        ActionStatus status = active.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        active.close();
        active = null;
        if (status instanceof ActionStatus.Failed failed) {
            failure = failed.problem();
            stage = Stage.CLEANUP_LEFTOVER;
            return ActionStatus.progressed();
        }
        stage = Stage.COLLECT_RESULT;
        return ActionStatus.progressed();
    }

    // 改造生效后当场把新方块挖回包里（或捡起直接掉出的物品），一块一清再进入下一块。
    private ActionStatus tickCollect(TickContext context) {
        if (active == null) {
            if (breaksIntoAir) {
                active = seams.drops() == null ? null : seams.drops().pickUpNewSince(dropsBefore);
                if (active == null) return finishOneRound();
            } else {
                Optional<Action> collect = seams.collects().collect(placedCell, permissions);
                if (collect.isEmpty()) {
                    recordLeftBlock(resultId, "改造好了但没能收回，留在 " + placedCell.toShortString());
                    placedCell = null;
                    return settleOrFail(Problem.of(Problem.Kind.UNREACHABLE, "收回改造好的方块接缝未接上", null));
                }
                active = collect.get();
            }
        }
        ActionStatus status = active.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        active.close();
        active = null;
        if (status instanceof ActionStatus.Failed failed) {
            placedCell = null;
            return settleOrFail(failed.problem());
        }
        return finishOneRound();
    }

    private ActionStatus finishOneRound() {
        placedCell = null;
        completed++;
        if (completed >= rounds) return ActionStatus.done();
        stage = Stage.PLACE_BASE;
        return ActionStatus.progressed();
    }

    // 改造失败后的现场清理：核对那一格仍是刚放下的基底方块就顺手挖回包里，不挖别人的方块。
    private ActionStatus tickCleanup(TickContext context) {
        if (active == null) {
            Optional<String> currentBlock = seams.blockTypes().blockTypeAt(
                    new WorldPosition(placedCell.getX(), placedCell.getY(), placedCell.getZ(), null));
            if (currentBlock.isEmpty() || !currentBlock.get().equals(baseBlockId)) {
                placedCell = null;
                return settleOrFail(failure);
            }
            Optional<Action> reclaim = seams.collects().collect(placedCell, permissions);
            if (reclaim.isEmpty()) {
                recordLeftBlock(baseBlockId, "改造没成且收回入口未接上，留在 " + placedCell.toShortString());
                placedCell = null;
                return settleOrFail(failure);
            }
            active = reclaim.get();
        }
        ActionStatus status = active.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        active.close();
        active = null;
        if (status instanceof ActionStatus.Done) {
            if (records != null) {
                records.attempt("收回没改成的临时方块",
                        "把刚放下的 " + baseBlockId + " 从 " + placedCell.toShortString() + " 挖回背包");
            }
        } else {
            recordLeftBlock(baseBlockId, "改造没成且没能挖回，留在 " + placedCell.toShortString());
        }
        placedCell = null;
        return settleOrFail(failure);
    }

    // 前面已经做成过至少一块时交回完成，由取物引擎清点实际进包的件数按部分完成或下一批处理；一块没做成则报失败。
    private ActionStatus settleOrFail(Problem problem) {
        return completed > 0 ? ActionStatus.done() : ActionStatus.failed(problem);
    }

    private void recordLeftBlock(String blockId, String note) {
        if (records == null) return;
        records.change(new Change(Change.Kind.BLOCK_PLACED, blockId, 1, note));
        records.attempt("收回临时方块", note);
    }

    @Override
    public void pause() {
        if (active != null) active.pause();
    }

    @Override
    public void close() {
        if (active != null) {
            active.close();
            active = null;
        }
        if (placedCell != null) {
            Optional<String> left = seams.blockTypes().blockTypeAt(
                    new WorldPosition(placedCell.getX(), placedCell.getY(), placedCell.getZ(), null));
            left.ifPresent(blockId -> recordLeftBlock(blockId,
                    "任务收尾时临时放下的方块还在 " + placedCell.toShortString()));
            placedCell = null;
        }
    }

    @Override
    public Interruptibility interruptibility() {
        return active != null ? active.interruptibility() : Interruptibility.WORKING;
    }

    @Override
    public String describe() {
        return "原地摆下 " + baseBlockId + " 改造成 " + resultId + "（" + (completed + 1) + "/" + rounds + "）";
    }
}
