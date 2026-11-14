// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.behavior.interaction.BlockBreaking;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.interaction.spi.ChainKeyHold;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.game.interaction.DigToolUse;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockGone;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连锁挖一格：按住连锁键挖触发格，模组把整批一起挖掉；不连锁时就是普通地挖这一格。
 * 第一下之前问连锁键准备好没有 → 挖触发格（连锁时一直按着）→ 那一下确认后再等副格的方块更新到齐（最多一秒）
 * → 松键、等模组按松开处理 → 做完。
 *
 * <p>连锁键打开后 40 刻（两秒）内还没挥出第一下，或者挖到一半键丢了（形状被换、工具换了）：
 * 停手松开连锁键、取消挑面、在日志里写明当时停在哪一步，改成普通地单挖这一格，不按着连锁键原地干等。
 * 被打断时同样先松键，回来重新开一次连锁。哪些副格真挖掉了由结算时逐格复查并记入统计账本。
 */
final class ChainedDig implements TellsToolUse {

    private static final Logger LOG = LoggerFactory.getLogger(ChainedDig.class);

    /** 挥不下去每隔这么多刻记一行。 */
    private static final int STALL_LOG_TICKS = 100;
    /** 连锁键打开后最多等几刻必须挥出第一下：40 刻（两秒）内没挥下去就松键放弃这一批退回单挖。 */
    static final int CHAIN_SWING_TIMEOUT_TICKS = 40;
    /** 副格的方块更新最多等几刻：模组在同一刻挖完整批，更新随后几个包陆续到。 */
    static final int SETTLE_TICKS = 20;
    /** 要求的那一面这么多刻都打不中就不再挑面：站位偏了照样挖下去，形状不对由那一批的检查兜住。 */
    static final int FACE_GIVE_UP_TICKS = 40;

    /** 读现场：这一格还在不在。 */
    interface Cells {
        boolean gone(TickContext context, BlockPos cell);
    }

    private enum Stage { DIG, SETTLE, RELEASE, DONE }

    private final BlockPos trigger;
    private final BreakAccelerator.ChainShape shape;
    private final Predicate<BlockPos> mayBreak;
    private final List<BreakAccelerator> accelerators;
    private final BlockBreaking digging;
    private final Cells cells;
    private final OthersThingsNotice othersNotice;
    private final ChainMiningLedger ledger;
    /** 开挖前趁整批副格还在世界里抄下的归属快照：等副格消失结算时把确认挖掉的格入账。 */
    private final Map<BlockPos, OthersThingsNotice.BlockOwner> batchOwners = new LinkedHashMap<>();
    private Stage stage = Stage.DIG;
    /** 这一轮开出来的连锁键；不连锁（没装、答了只挖这一格）时为空。 */
    private ChainKeyHold hold;
    private boolean holdOpened;
    /** 准备好时那一批会一起挖的格；只挖这一格时为空。 */
    private List<BlockPos> batch = List.of();
    /** 挖的中途键丢了或等满 40 刻还没挥出：先松开，松好再接着单挖这一格。 */
    private boolean droppingChain;
    private PlayerContext current;
    private long settleUntil;
    private int ticksStalled;
    private int ticksWithoutSwing;
    private int chainWaitTicks;
    private boolean faceRequired;
    private ActionStatus.Failed failure;
    /** 上一次记下的"在等什么"：换了才再记一行。 */
    private String lastWait = "";
    /** 这次为什么没连锁：给描述与拒绝记忆、统计账本用。 */
    private String singleReason = "";
    /** 是否已向统计账本记过这一轮挖掘结果。 */
    private boolean ledgerRecorded;

    ChainedDig(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak,
            List<BreakAccelerator> accelerators, BlockBreaking digging, Cells cells) {
        this(trigger, face, shape, mayBreak, accelerators, digging, cells, OthersThingsNotice.NONE, null);
    }

    ChainedDig(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak,
            List<BreakAccelerator> accelerators, BlockBreaking digging, Cells cells,
            OthersThingsNotice othersNotice) {
        this(trigger, face, shape, mayBreak, accelerators, digging, cells, othersNotice, null);
    }

    ChainedDig(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape, Predicate<BlockPos> mayBreak,
            List<BreakAccelerator> accelerators, BlockBreaking digging, Cells cells,
            OthersThingsNotice othersNotice, ChainMiningLedger ledger) {
        this.trigger = trigger.immutable();
        this.shape = Objects.requireNonNull(shape, "shape");
        this.mayBreak = Objects.requireNonNull(mayBreak, "mayBreak");
        this.accelerators = List.copyOf(accelerators);
        this.digging = Objects.requireNonNull(digging, "digging");
        this.cells = Objects.requireNonNull(cells, "cells");
        this.othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        this.ledger = ledger;
        this.faceRequired = face != null;
        digging.aimAt(this.trigger);
        digging.fromFace(face);
        digging.beforeFirstSwing(this::gate);
    }

    @Override public ActionStatus tick(TickContext context) {
        current = context.player();
        return switch (stage) {
            case DONE -> failure != null ? failure : ActionStatus.done();
            case RELEASE -> released();
            case SETTLE -> settle(context);
            case DIG -> dig(context);
        };
    }

    // 挖触发格：先开连锁键；挥下去以后每刻续按，键丢了或 40 刻内没挥出第一下就松键改成只挖这一格。
    private ActionStatus dig(TickContext context) {
        if (!holdOpened) open();
        if (!batch.isEmpty() && digging.swingPending() && !hold.keep(current)) {
            digging.pause();
            batch = List.of();
            batchOwners.clear();
            droppingChain = true;
            singleReason = "挖到一半连锁键松掉了";
        }
        if (droppingChain) {
            if (!hold.release(current)) return ActionStatus.running();
            hold = null;
            droppingChain = false;
        }
        // 触发格没了、也没有等确认的那一下：要么刚挖掉，要么别人挖了，都不再对着空气挖。
        if (cells.gone(context, trigger) && !digging.swingPending()) return dug(context);
        ActionStatus status = digging.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            failure = failed;
            stage = Stage.RELEASE;
            return ActionStatus.running();
        }
        // 挖掘器本刻确认挖开了（只挖目标、不拆遮挡，进展就是触发格那一下）。
        if (status instanceof ActionStatus.Done
                || status instanceof ActionStatus.Running running && running.progressed()) {
            return dug(context);
        }
        if (hold != null && !digging.swingPending() && ++chainWaitTicks >= CHAIN_SWING_TIMEOUT_TICKS) {
            giveUpChainBeforeSwing();
        }
        logStallIfNeeded(context);
        // 要求的面一直打不中（站位斜了、上一行还没挖开）：不再挑面，哪面看得见挖哪面。
        if (faceRequired && !digging.swingPending() && ++ticksWithoutSwing > FACE_GIVE_UP_TICKS) {
            faceRequired = false;
            digging.fromFace(null);
        }
        return ActionStatus.running();
    }

    // 连锁键打开后满 40 刻还没挥出第一下：写清当时停在哪一步，松开连锁键、取消挑面，当场退回单挖。
    private void giveUpChainBeforeSwing() {
        String stepWhere = lastWait.isEmpty() ? "挖掘器在" + digging.describe() : lastWait;
        LOG.info("[连锁挖] {}：等满 {} 刻还没挥下去（停在：{}），松开连锁键退回单挖",
                trigger.toShortString(), chainWaitTicks, stepWhere);
        singleReason = "按下连锁键后超时未挥出（" + stepWhere + "）";
        batch = List.of();
        batchOwners.clear();
        droppingChain = true;
        if (faceRequired) {
            faceRequired = false;
            digging.fromFace(null);
        }
    }

    private void logStallIfNeeded(TickContext context) {
        if (!digging.swingPending() && ++ticksStalled % STALL_LOG_TICKS == 0) {
            var self = context.player() == null ? null : context.player().localPlayer();
            LOG.info("[连锁挖] {}：{} 刻还没挥下去，挖掘器在{}；人在 {}，眼睛离这格中心 {} 格", trigger.toShortString(),
                    ticksStalled, digging.describe(), self == null ? "?" : self.blockPosition().toShortString(),
                    self == null ? "?" : String.format("%.1f", Math.sqrt(self.getEyePosition().distanceToSqr(
                            trigger.getX() + 0.5, trigger.getY() + 0.5, trigger.getZ() + 0.5))));
        }
    }

    // 触发格挖掉了：连锁的要再等副格更新到齐才松键；只挖这一格的直接收尾。
    private ActionStatus dug(TickContext context) {
        if (!batch.isEmpty()) {
            stage = Stage.SETTLE;
            settleUntil = context.gameTick() + SETTLE_TICKS;
        } else {
            stage = Stage.RELEASE;
        }
        return ActionStatus.progressed();
    }

    // 等副格：整批都没了或者等满一秒就松键；按现场真消失的格数记进统计账本。
    private ActionStatus settle(TickContext context) {
        long brokenCount = batch.stream().filter(cell -> cells.gone(context, cell)).count();
        boolean allGone = brokenCount == batch.size();
        if (!allGone && context.gameTick() < settleUntil) return ActionStatus.running();
        recordChainedLedger((int) Math.max(1L, brokenCount));
        reportBrokenBatch(context);
        stage = Stage.RELEASE;
        return released();
    }

    // 松键：模组按松开处理了才算做完，不然下一次挖还带着连锁。
    private ActionStatus released() {
        if (hold != null && !hold.release(current)) return ActionStatus.running();
        hold = null;
        stage = Stage.DONE;
        if (failure == null && batch.isEmpty()) recordSingleLedger();
        return failure != null ? failure : ActionStatus.done();
    }

    private void recordChainedLedger(int brokenBlocks) {
        if (ledger == null || ledgerRecorded) return;
        ledgerRecorded = true;
        ledger.recordChained(brokenBlocks, "周围没有相连的同种方块");
    }

    private void recordSingleLedger() {
        if (ledger == null || ledgerRecorded) return;
        ledgerRecorded = true;
        ledger.recordSingle(singleReason);
    }

    // 开这一轮的连锁键：按顺序问联动的挖掘加速，第一个开得出的就用它；都开不出就普通地挖。
    private void open() {
        holdOpened = true;
        chainWaitTicks = 0;
        for (BreakAccelerator accelerator : accelerators) {
            Optional<ChainKeyHold> opened = accelerator.hold(shape, mayBreak);
            if (opened.isPresent()) {
                hold = opened.get();
                return;
            }
        }
        singleReason = "没有连锁挖";
    }

    // 第一下挥下去之前：连锁键说再等就先不挥；说可以连锁就记下这一批；说只挖这一格就照常挥。
    private boolean gate(BlockHitResult hit) {
        if (hold == null || droppingChain) return true;
        ChainKeyHold.Readiness readiness = hold.ready(current, hit);
        if (readiness instanceof ChainKeyHold.Readiness.Wait wait) {
            if (!wait.why().equals(lastWait)) {
                lastWait = wait.why();
                LOG.info("[连锁挖] {}：等着，{}", trigger.toShortString(), wait.why());
            }
            return false;
        }
        if (readiness instanceof ChainKeyHold.Readiness.Chain chain) {
            batch = chain.batch();
            snapshotBatchOwners();
            LOG.info("[连锁挖] {}：按住连锁键，一批 {} 格", trigger.toShortString(), batch.size());
            return true;
        }
        if (readiness instanceof ChainKeyHold.Readiness.Single single) {
            singleReason = single.why();
            LOG.info("[连锁挖] {}：不连锁，{}", trigger.toShortString(), singleReason);
        }
        hold = null;
        return true;
    }

    @Override public void pause() {
        digging.pause();
        letGo();
    }

    @Override public void close() {
        digging.close();
        letGo();
    }

    // 停手时松开连锁键：插进来的事（吃东西、躲怪）挖别的不能带着连锁；回来时重新开一次。
    private void letGo() {
        if (hold != null) hold.release(current);
        hold = null;
        holdOpened = false;
        chainWaitTicks = 0;
        batch = List.of();
        batchOwners.clear();
        droppingChain = false;
        if (stage == Stage.SETTLE || stage == Stage.RELEASE) stage = Stage.DIG;
    }

    // 趁第一下挥下去之前整批方块还在世界里，把其中别人放的副格先抄下归属快照。
    private void snapshotBatchOwners() {
        batchOwners.clear();
        if (current == null || current.level() == null) return;
        String dimension = current.level().dimension().location().toString();
        for (BlockPos cell : batch) {
            if (BlockGone.gone(current.level(), cell)) continue;
            String blockId = BuiltInRegistries.BLOCK.getKey(current.level().getBlockState(cell).getBlock()).toString();
            WorldPosition at = new WorldPosition(cell.getX(), cell.getY(), cell.getZ(), dimension);
            othersNotice.snapshotBlock(at, blockId).ifPresent(owner -> batchOwners.put(cell.immutable(), owner));
        }
    }

    // 副格等同步收口时，只把真已经从世界里消失的那几格报进连拆账（触发格与副格由通报器按坐标去重）。
    private void reportBrokenBatch(TickContext context) {
        if (batchOwners.isEmpty()) return;
        batchOwners.forEach((cell, owner) -> {
            if (cells.gone(context, cell)) {
                othersNotice.blockBroken(owner, context.gameTick());
            }
        });
        batchOwners.clear();
    }

    @Override public Interruptibility interruptibility() {
        return digging.interruptibility();
    }

    @Override public List<DigToolUse> toolUses() {
        return digging.toolUses();
    }

    @Override public String notChainedReason() {
        return batch.isEmpty() && !singleReason.isEmpty() ? singleReason : null;
    }

    @Override public String describe() {
        String at = trigger.toShortString();
        if (!batch.isEmpty()) return "连锁挖 " + at + "（一批 " + batch.size() + " 格）";
        return singleReason.isEmpty() ? "挖 " + at : "挖 " + at + "（不连锁：" + singleReason + "）";
    }
}
