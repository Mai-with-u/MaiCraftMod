// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.interaction;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

import org.maiwithu.maicraft.game.interaction.DigToolUse;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.BlockDigger;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.interaction.DigTool;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * {@link BlockBreaking} 的原生实现：每次刨出任务开一个挖掘器，把瞄准、选工具、持续挖和等确认
 * 都交给游戏接口层现成的挖掘基础代码，这里只换算本刻结果。
 *
 * <p>只挖指定的那一格，不顺手拆别的格子：刨出时头就在那一格里，用不着拆遮挡。
 * 挖掘器第一次真正下手时才创建：创建它需要当刻的角色，而任务对象可能提前一刻就被造出来。
 */
public final class NativeBlockBreaking implements BlockBreaking {

    private final InteractionSender sender;
    private final MenuActions menuActions;
    private BlockDigger digger;
    private BlockPos cell;
    private Direction face;
    /** 挖概率掉落：挖掘器挑工具时不拿剪刀和精准采集。 */
    private boolean forChanceDrops;
    /** 挖之前拿哪件工具。 */
    private final DigToolRule tools;
    /** 这一趟预计还要挖几格：挑工具按它算换不换手。 */
    private int expectedBlocks = 1;
    /** 这一趟挖下来的东西要不要：只要路通时给 false，徒手、拿不对的工具挖也行。 */
    private boolean wantsDrop = true;
    /** 目标里点名的工具：只用那一件，身上没有就失败，不写时给 null。 */
    private DigTool digTool;
    private Predicate<BlockHitResult> gate = hit -> true;
    /** 最近一刻的角色上下文；收尾时还属于本刻就用它停手，过期了就把停手留到下一刻。 */
    private PlayerContext lastContext;

    /** @param tools 挖之前拿哪件工具：玩家行为层的挑工具 */
    public NativeBlockBreaking(InteractionSender sender, MenuActions menuActions, DigToolRule tools) {
        this.sender = sender;
        this.menuActions = menuActions;
        this.tools = Objects.requireNonNull(tools, "tools");
    }

    @Override
    public void expectBlocks(int blocks) {
        expectedBlocks = Math.max(1, blocks);
        if (digger != null) digger.expectedBlocks(expectedBlocks);
    }

    @Override
    public void aimAt(BlockPos target) {
        cell = target.immutable();
    }

    @Override
    public void fromFace(Direction required) {
        face = required;
        if (digger != null) digger.requiredFace(required);
    }

    @Override
    public void beforeFirstSwing(Predicate<BlockHitResult> check) {
        gate = check == null ? hit -> true : check;
        if (digger != null) digger.beforeBreak(gate);
    }

    @Override
    public void wantsDrop(boolean wants) {
        wantsDrop = wants;
        if (digger != null) digger.wantsDrop(wants);
    }

    @Override
    public void forChanceDrops() {
        forChanceDrops = true;
        if (digger != null) digger.forChanceDrops(true);
    }

    @Override
    public void digTool(DigTool tool) {
        digTool = tool;
        if (digger != null) digger.digTool(tool);
    }

    @Override
    public boolean swingPending() {
        return digger != null && digger.hasPendingBreak();
    }

    @Override
    public ActionStatus tick(TickContext context) {
        PlayerContext player = context.player();
        lastContext = player;
        if (cell == null) {
            return ActionStatus.running();
        }
        if (digger == null) {
            digger = new BlockDigger(player.localPlayer(), sender, menuActions, player.input(), tools);
            digger.expectedBlocks(expectedBlocks);
            digger.requiredFace(face);
            digger.beforeBreak(gate);
            digger.forChanceDrops(forChanceDrops);
            digger.wantsDrop(wantsDrop);
            digger.digTool(digTool);
        }
        // 挥下去的那一下还没确认、方块在客户端已经没了（原版先在本地把方块挖掉）：射线已经打不中它，
        // 只等游戏确认这一下，不再对着空气重新挖。连锁键要按到这里结清才松。
        if (digger.hasPendingBreak() && cell.equals(digger.current())
                && player.level() != null && player.level().getBlockState(cell).isAir()) {
            return switch (digger.settleGone(player, true)) {
                case BROKE_TARGET, BROKE_OCCLUDER -> ActionStatus.progressed();
                case PROGRESSING, NO_SHOT, NO_ROOM -> ActionStatus.running();
                case MISSING_TOOL -> missingNamedTool();
            };
        }
        // 挖得动就推进；一格真挖开了才算进展。暂时够不着（NO_SHOT）不算失败也不算进展，
        // 由刨出任务按"多久没进展算卡住"统一收口。
        return switch (digger.digTargetStep(player, cell)) {
            case PROGRESSING, NO_SHOT -> ActionStatus.running();
            case BROKE_TARGET, BROKE_OCCLUDER -> ActionStatus.progressed();
            // 空手最划算却腾不出放手里东西的地方：不丢东西，如实交回，别一直干等。
            case NO_ROOM -> ActionStatus.failed(Problem.of(Problem.Kind.INVENTORY_FULL,
                    "背包里没有空位，手里那件东西收不起来，腾不出手挖 " + cell.toShortString()));
            // 点名要用的工具身上没有：不换成别的，直接把原因交回。
            case MISSING_TOOL -> missingNamedTool();
        };
    }

    // 点名工具找不到时的失败：说清点的是哪一件、差在哪，不另找工具代替。
    private ActionStatus missingNamedTool() {
        String name = digTool instanceof DigTool.Item item ? item.itemId() : "点名的工具";
        String why = digger != null && digger.missingToolWhy() != null ? digger.missingToolWhy() : "身上没有";
        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "你点名要用 " + name + " 挖，" + why));
    }

    // 挖下来的格子用了什么工具：由挖掘器记着，收批时交出去拼成结果里的一条。
    @Override
    public List<DigToolUse> toolUses() {
        return digger == null ? List.of() : digger.toolUses();
    }

    @Override
    public void pause() {
        stopDigging();
    }

    @Override
    public void close() {
        stopDigging();
    }

    // 停手：结清这次挖掘占着的交互与按键；上下文已过期时由挖掘器把停挖留到下一刻。
    private void stopDigging() {
        if (digger != null) {
            digger.cancel(lastContext != null && lastContext.isCurrent() ? lastContext : null);
        }
    }

    @Override
    public String describe() {
        return digger == null || digger.current() == null ? "还没开始挖" : "正在挖 " + digger.current().toShortString();
    }
}
