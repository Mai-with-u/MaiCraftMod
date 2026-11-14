// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.interaction.BlockBreaking;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.game.interaction.DigToolUse;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockGone;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 挖方块的生产实现：每要挖一格，就请一套原生挖掘基础代码上场，
 * 瞄准、选工具、持续挖和等确认都在里面，这里只把一次挖掘包成"挖到这一格没了才算做完"的动作。
 *
 * <p>原生挖掘每挖开一格只报一次进展、从不自己说做完（它也给刨出这种逐刻换格子的用）；
 * 这里每刻看一眼目标格：变成空气，或者只剩原来泡着的水（含水方块挖掉后留下的流体），就是挖完了。
 * 挖的许可（动谁的方块）由调用方的许可检查先把关，到这里的一格都是获准动土的。
 *
 * <p>装了连锁挖的模组时，连锁挖一格按住连锁键挖，整批交给模组一起挖掉；没装或因固定拒绝原因跳过时普通地挖并向统计账本如实记原因。
 */
public final class LiveBlockDigging implements DigsBlocks {

    private static final String NOT_INSTALLED_REASON = "当前实例未接入连锁破坏";
    private static final String SINGLE_SPOT_REASON = "单点采集只挖指定一格";
    private static final String CHANCE_DROP_REASON = "概率掉落逐格采集";

    private final Supplier<BlockBreaking> diggings;
    private final List<BreakAccelerator> accelerators;
    private final OthersThingsNotice othersNotice;
    private final ChainMiningLedger ledger;

    public LiveBlockDigging(Supplier<BlockBreaking> diggings) {
        this(diggings, List.of(), OthersThingsNotice.NONE, null);
    }

    /** @param accelerators 联动的挖掘加速（连锁挖）；没装时为空表，连锁挖一格就是普通地挖 */
    public LiveBlockDigging(Supplier<BlockBreaking> diggings, List<BreakAccelerator> accelerators) {
        this(diggings, accelerators, OthersThingsNotice.NONE, null);
    }

    /** 生产接入动别人东西的通报器：连锁挖掉的别人副格在收口时一并记进连拆账。 */
    public LiveBlockDigging(Supplier<BlockBreaking> diggings, List<BreakAccelerator> accelerators,
            OthersThingsNotice othersNotice) {
        this(diggings, accelerators, othersNotice, null);
    }

    /** 生产接入动别人东西的通报器与世界级连锁挖掘统计账本。 */
    public LiveBlockDigging(Supplier<BlockBreaking> diggings, List<BreakAccelerator> accelerators,
            OthersThingsNotice othersNotice, ChainMiningLedger ledger) {
        this.diggings = diggings;
        this.accelerators = List.copyOf(accelerators);
        this.othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        this.ledger = ledger;
    }

    @Override
    public Optional<Action> dig(BlockPos target) {
        // 挖掘本身就是一个动作：瞄准这一格交给它，推进、被打断停手、收尾结清都由它自己管。
        BlockBreaking digging = diggings.get();
        digging.aimAt(target.immutable());
        return Optional.of(new UntilGone(target.immutable(), digging, defaultSingleReason(), ledger));
    }

    @Override
    public Optional<Action> dig(BlockPos target, ToolPrep prep) {
        return digUntilGone(target, prep, defaultSingleReason());
    }

    @Override
    public Optional<Action> digSkippingChain(BlockPos target, ToolPrep prep, String skippedReason) {
        String reason = skippedReason == null || skippedReason.isBlank() ? defaultSingleReason() : skippedReason;
        return digUntilGone(target, prep, reason);
    }

    private Optional<Action> digUntilGone(BlockPos target, ToolPrep prep, String singleReason) {
        // 带着这一趟的规模挖：挑工具按预计的格数算徒手也掉的方块换不换锹。
        BlockBreaking digging = diggings.get();
        digging.aimAt(target.immutable());
        digging.expectBlocks(prep.expectedBlocks());
        digging.wantsDrop(prep.wantsDrop());
        // 目标里点名了工具就交给挖掘器：只认那一件，身上没有就失败。
        digging.digTool(prep.digTool());
        return Optional.of(new UntilGone(target.immutable(), digging, singleReason, ledger));
    }

    @Override
    public Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak, ToolPrep prep) {
        if (accelerators.isEmpty()) return digUntilGone(trigger, prep, NOT_INSTALLED_REASON);
        BlockBreaking digging = diggings.get();
        digging.expectBlocks(prep.expectedBlocks());
        digging.wantsDrop(prep.wantsDrop());
        digging.digTool(prep.digTool());
        return Optional.of(new ChainedDig(trigger, face, shape, mayBreak, accelerators, digging,
                (context, cell) -> gone(context.player(), cell), othersNotice, ledger));
    }

    @Override
    public Optional<Action> digForChanceDrops(BlockPos target) {
        // 要的是概率掉落：挖掘器挑工具时绕开剪刀和精准采集，有时运的先拿；其余和普通地挖一样。
        BlockBreaking digging = diggings.get();
        digging.aimAt(target.immutable());
        digging.forChanceDrops();
        String reason = chainsAvailable() ? CHANCE_DROP_REASON : NOT_INSTALLED_REASON;
        return Optional.of(new UntilGone(target.immutable(), digging, reason, ledger));
    }

    @Override
    public Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak) {
        // 没有连锁挖的模组：普通地挖这一格，不挑面（只挖一格时哪面看得见挖哪面）。
        if (accelerators.isEmpty()) {
            BlockBreaking digging = diggings.get();
            digging.aimAt(trigger.immutable());
            return Optional.of(new UntilGone(trigger.immutable(), digging, NOT_INSTALLED_REASON, ledger));
        }
        return Optional.of(new ChainedDig(trigger, face, shape, mayBreak, accelerators, diggings.get(),
                (context, cell) -> gone(context.player(), cell), othersNotice, ledger));
    }

    @Override
    public boolean gone(TickContext context, BlockPos cell) {
        return gone(context.player(), cell);
    }

    @Override
    public boolean chains() {
        return !accelerators.isEmpty();
    }

    private boolean chainsAvailable() {
        return accelerators.stream().anyMatch(BreakAccelerator::available);
    }

    private String defaultSingleReason() {
        return chainsAvailable() ? SINGLE_SPOT_REASON : NOT_INSTALLED_REASON;
    }

    // 目标格没了：空气，或只剩流体（含水的方块挖掉后留下原来的水）。没加载时不冒充挖完了。
    private static boolean gone(PlayerContext player, BlockPos cell) {
        if (player == null) return false;
        return BlockGone.gone(player.level(), cell);
    }

    /**
     * 方块先在客户端消失时能不能就报做完。
     *
     * <p>原版先在本地把方块挖掉，这一下挖掘记录还没结清；这时直接报做完，挖掘器就没机会收这一下，
     * 挖下来的那一格就进不了结果里的工具现场，这一趟磨掉多少耐久也没处说（耐久本身照常由服务器扣）。
     * 所以方块没了还要先把这一下交给挖掘器收掉
     * （它收的时候会看到方块已经没了，走"先没了、只等确认"那一条），收完才算做完。
     */
    static boolean finishable(boolean gone, boolean swingPending) {
        return gone && !swingPending;
    }

    /** 挖到目标格没了才做完：每刻先看现场，还在就再挖一刻；确认挖完后向统计账本记一格单挖。 */
    private static final class UntilGone implements TellsToolUse {
        private final BlockPos cell;
        private final BlockBreaking digging;
        private final String notChainedReason;
        private final ChainMiningLedger ledger;
        private boolean recorded;

        UntilGone(BlockPos cell, BlockBreaking digging, String notChainedReason, ChainMiningLedger ledger) {
            this.cell = cell;
            this.digging = digging;
            this.notChainedReason = notChainedReason;
            this.ledger = ledger;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (finishable(gone(context.player(), cell), digging.swingPending())) {
                recordDone();
                return ActionStatus.done();
            }
            ActionStatus status = digging.tick(context);
            if (status instanceof ActionStatus.Done) recordDone();
            return status;
        }

        private void recordDone() {
            if (!recorded && ledger != null) {
                recorded = true;
                ledger.recordSingle(notChainedReason);
            }
        }

        @Override public void pause() {
            digging.pause();
        }

        @Override public void close() {
            digging.close();
        }

        @Override public Interruptibility interruptibility() {
            return digging.interruptibility();
        }

        @Override public String describe() {
            return digging.describe();
        }

        @Override public List<DigToolUse> toolUses() {
            return digging.toolUses();
        }

        @Override public String notChainedReason() {
            return notChainedReason;
        }
    }
}
