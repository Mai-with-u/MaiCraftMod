// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.inventory.drops.TossesTogether;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 奇点爆炸转化的现场执行动作：
 * 放置引爆块（优先 ae2:tiny_tnt）→ 紧贴其旁（±1 格 2×2×2 扫描盒内）丢出 1 枚奇点与 1 枚末影珍珠/粉
 * → 点燃引爆块并立刻退至爆炸安全距离外 → 等待爆炸并核对掉落物变为成对同频量子缠绕奇点
 * → 走近捡齐两枚并确认背包内新增 2 枚同频缠绕奇点。
 */
final class ExplosionTransformRun implements Action {

    private enum Stage {
        HAND_EXPLOSIVE, APPROACH_PLACE, PLACE_EXPLOSIVE, TOSS_REAGENTS,
        HAND_IGNITER, APPROACH_IGNITE, IGNITE, RETREAT, WAIT_EXPLOSION, COLLECT, FINISHED
    }

    /** 爆炸引信与掉落物生成的最长等待刻数（原版 TNT 80 刻 + 缓冲）。F 游戏事实。 */
    private static final int EXPLOSION_WAIT_TICKS = 140;
    /** 爆炸后扫描成对缠绕奇点掉落物的半径（格）。 */
    private static final double DROP_SCAN_RADIUS = 5.0;
    /** 走近掉落物后等待原版吸进背包的最长刻数。 */
    private static final int PICKUP_WAIT_TICKS = 40;

    private final ExplosionTransformPlan.Loadout loadout;
    private final ExplosionTransformPlan.SiteChoice.Found site;
    private final Permissions permissions;
    private final Ae2AdvancedReads reads;
    private final PlayerServices services;
    private final TaskRecords records;

    private Stage stage = Stage.HAND_EXPLOSIVE;
    private Action current;
    private Set<Integer> dropsBefore = Set.of();
    private Map<Long, Integer> carriedBefore = Map.of();
    private int waitTicks;
    private ExplosionTransformPlan.PairMatch matchedPair;
    private BlockPos collectTarget;

    ExplosionTransformRun(ExplosionTransformPlan.Loadout loadout, ExplosionTransformPlan.SiteChoice.Found site,
                          Permissions permissions, Ae2AdvancedReads reads, PlayerServices services,
                          TaskRecords records) {
        this.loadout = Objects.requireNonNull(loadout, "loadout");
        this.site = Objects.requireNonNull(site, "site");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.records = records;
    }

    @Override public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        if (stage != Stage.FINISHED && (player == null || !player.isCurrent() || player.localPlayer() == null)) {
            return ActionStatus.running();
        }
        return switch (stage) {
            case HAND_EXPLOSIVE -> moveItemToHand(context, loadout.explosiveItem(), Stage.APPROACH_PLACE);
            case APPROACH_PLACE -> runSub(context,
                    () -> services.approach().toward(ApproachTarget.ofBlockFace(site.explosiveAt().below(), Direction.UP),
                            permissions),
                    Stage.PLACE_EXPLOSIVE);
            case PLACE_EXPLOSIVE -> placeExplosive(context);
            case TOSS_REAGENTS -> tossReagents(context);
            case HAND_IGNITER -> moveItemToHand(context, loadout.igniterItem(), Stage.APPROACH_IGNITE);
            case APPROACH_IGNITE -> runSub(context,
                    () -> services.approach().toward(ApproachTarget.ofBlock(site.explosiveAt()), permissions),
                    Stage.IGNITE);
            case IGNITE -> igniteExplosive(context);
            case RETREAT -> runSub(context,
                    () -> services.approach().toward(ApproachTarget.ofBlock(site.retreatAt()), permissions),
                    Stage.WAIT_EXPLOSION);
            case WAIT_EXPLOSION -> waitForPair(player);
            case COLLECT -> collectPair(context);
            case FINISHED -> ActionStatus.done();
        };
    }

    private ActionStatus moveItemToHand(TickContext context, String itemId, Stage next) {
        if (current == null) {
            ItemStack held = context.player().localPlayer().getItemInHand(InteractionHand.MAIN_HAND);
            if (!held.isEmpty() && idOf(held).equals(itemId)) {
                return enter(next);
            }
            Optional<Action> move = services.toMainhand().moveToMainhand(itemId);
            if (move.isEmpty()) {
                return stop(Problem.of(Problem.Kind.NEED_ITEM, "身上没有 " + itemId + "，无法继续奇点爆炸转化"));
            }
            current = move.get();
        }
        return advanceSub(context, next);
    }

    // 在安全格脚下顶面放置迷你 TNT（或已许可的原版 TNT），等该格方块变化确认放稳。
    private ActionStatus placeExplosive(TickContext context) {
        if (current == null) {
            ClientLevel level = context.player().level();
            BlockPos target = site.explosiveAt();
            BlockState before = level.getBlockState(target);
            carriedBefore = snapshotEntangledInInventory(context.player().localPlayer());
            dropsBefore = nearbyDropIds(level, target);
            InteractionConfirmation confirmation = InteractionConfirmation.expiresAsNotApplied(
                    InteractionConfirmation.blockChanged(target, before));
            current = services.interactions().useBlockFace(target.below(), Direction.UP, confirmation);
        }
        return advanceSub(context, Stage.TOSS_REAGENTS);
    }

    // 紧贴引爆块相邻 1 格（稳落在 AE2 爆炸转化 ±1 格 2×2×2 盒内）精确丢出 1 枚奇点与 1 枚末影珍珠/粉。
    private ActionStatus tossReagents(TickContext context) {
        if (current == null) {
            List<TossesTogether.Toss> tosses = List.of(
                    new TossesTogether.Toss(ExplosionTransformPlan.SINGULARITY_ITEM, 1, true),
                    new TossesTogether.Toss(loadout.enderItem(), 1, true));
            current = new TossesTogether(tosses, site.tossAt(), 0.1, permissions, services.approach(),
                    services.toMainhand(), FirstPersonScene::of, Optional.empty());
        }
        return advanceSub(context, Stage.HAND_IGNITER);
    }

    // 点燃引爆块：打火石/火焰弹/熵变仪直接右键引爆块；红石火把则放到引爆块邻格触发红石引爆。
    private ActionStatus igniteExplosive(TickContext context) {
        if (current == null) {
            ClientLevel level = context.player().level();
            BlockPos target = site.explosiveAt();
            BlockState before = level.getBlockState(target);
            InteractionConfirmation confirmation = InteractionConfirmation.expiresAsNotApplied(
                    InteractionConfirmation.blockChanged(target, before));
            if ("minecraft:redstone_torch".equals(loadout.igniterItem())) {
                BlockPos torchAt = site.tossAt();
                current = services.interactions().useBlockFace(torchAt.below(), Direction.UP, confirmation);
            } else {
                current = services.interactions().useBlock(target, confirmation);
            }
        }
        return advanceSub(context, Stage.RETREAT);
    }

    // 撤至安全距离后等爆炸结束，扫描爆点附近新掉落物确认生成至少 2 枚同频缠绕奇点。
    private ActionStatus waitForPair(PlayerContext player) {
        ClientLevel level = player.level();
        List<ExplosionTransformPlan.DroppedCheck> freshDrops = freshDropsNear(level, site.explosiveAt());
        Optional<ExplosionTransformPlan.PairMatch> found = ExplosionTransformPlan.findEntangledPair(freshDrops);
        if (found.isPresent()) {
            matchedPair = found.get();
            collectTarget = locateDropPosition(level, matchedPair.entityIds()).orElse(site.explosiveAt());
            waitTicks = 0;
            return enter(Stage.COLLECT);
        }
        // 万一角色离得稍近已经被吸进背包，也直接复核背包增量。
        Optional<Long> alreadyInBag = ExplosionTransformPlan.confirmPickedPair(
                carriedBefore, snapshotEntangledInInventory(player.localPlayer()));
        if (alreadyInBag.isPresent()) {
            return completeSuccess(alreadyInBag.get());
        }
        if (++waitTicks > EXPLOSION_WAIT_TICKS) {
            return stop(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "引爆后等待 " + (EXPLOSION_WAIT_TICKS / 20) + " 秒未检测到成对同频的量子缠绕奇点掉落物",
                    "请检查奇点与末影珍珠/粉是否落在引爆块 ±1 格范围内且未被障碍隔开"));
        }
        return ActionStatus.running();
    }

    // 走近爆点把两枚同频缠绕奇点捡进背包，并以背包内新增 2 枚同频为最终完成证据。
    private ActionStatus collectPair(TickContext context) {
        LocalPlayer body = context.player().localPlayer();
        Optional<Long> picked = ExplosionTransformPlan.confirmPickedPair(
                carriedBefore, snapshotEntangledInInventory(body));
        if (picked.isPresent()) {
            return completeSuccess(picked.get());
        }
        if (current == null) {
            BlockPos dest = locateDropPosition(context.player().level(), matchedPair.entityIds())
                    .orElse(collectTarget == null ? site.explosiveAt() : collectTarget);
            current = services.approach().toward(ApproachTarget.ofBlock(dest), permissions);
        }
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (++waitTicks > PICKUP_WAIT_TICKS) {
            return stop(Problem.of(Problem.Kind.STUCK,
                    "已检测到同频缠绕奇点（频率 " + matchedPair.frequency() + "），但未能将两枚都捡进背包",
                    "请确认背包有至少 2 个空位后靠近爆点拾取"));
        }
        return ActionStatus.running();
    }

    private ActionStatus completeSuccess(long frequency) {
        if (current != null) current.close();
        current = null;
        stage = Stage.FINISHED;
        if (records != null) {
            records.attempt("在 " + site.explosiveAt().toShortString() + " 用 " + loadout.explosiveItem() + " 引爆转化奇点",
                    "获得成对同频量子缠绕奇点 ×2（频率 " + frequency + "）");
        }
        return ActionStatus.done();
    }

    private ActionStatus runSub(TickContext context, Supplier<Action> factory, Stage next) {
        if (current == null) current = factory.get();
        return advanceSub(context, next);
    }

    private ActionStatus advanceSub(TickContext context, Stage next) {
        ActionStatus status = current.tick(context);
        if (status instanceof ActionStatus.Running) return status;
        current.close();
        current = null;
        if (status instanceof ActionStatus.Failed failed) return stop(failed.problem());
        return enter(next);
    }

    private ActionStatus enter(Stage next) {
        if (current != null) current.close();
        current = null;
        stage = next;
        waitTicks = 0;
        return ActionStatus.progressed();
    }

    private ActionStatus stop(Problem problem) {
        if (current != null) current.close();
        current = null;
        stage = Stage.FINISHED;
        return ActionStatus.failed(problem);
    }

    private Set<Integer> nearbyDropIds(ClientLevel level, BlockPos center) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(DROP_SCAN_RADIUS))
                .stream().map(ItemEntity::getId).collect(Collectors.toSet());
    }

    private List<ExplosionTransformPlan.DroppedCheck> freshDropsNear(ClientLevel level, BlockPos center) {
        List<ExplosionTransformPlan.DroppedCheck> list = new ArrayList<>();
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(DROP_SCAN_RADIUS))) {
            if (dropsBefore.contains(entity.getId())) continue;
            ItemStack stack = entity.getItem();
            if (stack.isEmpty()) continue;
            long freq = reads.entangledSingularityId(stack).orElse(0L);
            list.add(new ExplosionTransformPlan.DroppedCheck(entity.getId(), idOf(stack), stack.getCount(), freq));
        }
        return list;
    }

    private static Optional<BlockPos> locateDropPosition(ClientLevel level, List<Integer> entityIds) {
        for (int id : entityIds) {
            if (level.getEntity(id) instanceof ItemEntity entity && entity.isAlive()) {
                return Optional.of(entity.blockPosition());
            }
        }
        return Optional.empty();
    }

    private Map<Long, Integer> snapshotEntangledInInventory(LocalPlayer player) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !ExplosionTransformPlan.RESULT_ITEM.equals(idOf(stack))) continue;
            long freq = reads.entangledSingularityId(stack).orElse(0L);
            if (freq != 0L) {
                counts.merge(freq, stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override public void pause() {
        if (current != null) current.pause();
    }

    @Override public void close() {
        if (current != null) current.close();
    }

    // 点燃与撤离阶段绝对不能被打断，防止角色站在引燃的 TNT 旁停住挨炸。
    @Override public Interruptibility interruptibility() {
        if (stage == Stage.IGNITE || stage == Stage.RETREAT) return Interruptibility.UNSAFE_TO_STOP;
        return current != null ? current.interruptibility() : Interruptibility.WORKING;
    }

    @Override public String describe() {
        return switch (stage) {
            case HAND_EXPLOSIVE, APPROACH_PLACE, PLACE_EXPLOSIVE -> "在安全空地放置 " + loadout.explosiveItem();
            case TOSS_REAGENTS -> "紧贴引爆块丢出奇点与末影料";
            case HAND_IGNITER, APPROACH_IGNITE, IGNITE -> "点燃 " + loadout.explosiveItem();
            case RETREAT -> "撤离到爆炸安全距离外";
            case WAIT_EXPLOSION -> "等待爆炸生成成对同频缠绕奇点";
            case COLLECT -> "走近捡齐两枚同频缠绕奇点";
            case FINISHED -> "奇点爆炸转化完成";
        };
    }
}
