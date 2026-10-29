// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.interaction.spi.BreakAccelerator;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.hands.DigToolChoice;
import org.maiwithu.maicraft.behavior.inventory.hands.LiveDigToolRule;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 现做工具：挖一格之前身上没有一把对路的工具，时间账划算、料过得了要留的东西，就先做最便宜的那一把再挖。
 * 包在共用的挖格外面：只有带着这一趟规模挖的路径（挖楼梯、清障、采掘）才会现做，别的照原样挖。
 *
 * <p>做一把走拿东西的引擎（合成、临时工作台放下用完收回都是既有的路），按这次任务的许可；料只用身上的，
 * 账算之前先看过不留的料够不够，不为做一把工具出去砍树挖石。做的时间有上限，超了就收手徒手挖。
 * 做了什么、为什么没做在这次任务的结果里说一句；同一种工具一次任务里只说一次，做不成的这次任务不再试。
 */
public final class ToolFirstDigging implements DigsBlocks {

    /** 做一把最多花多久：三十秒。做不完说明料或工作台出了岔子，收手徒手挖，不在这里耗着。类别：玩家常识。 */
    static final long MAKE_BUDGET_TICKS = 20L * 30;
    /** 现做的用途标签：写进拿东西的结果，看得出这是挖路时顺手做的。 */
    static final String PURPOSE = "现做工具";

    private final DigsBlocks digs;
    private final LiveDigToolRule tools;
    private final ItemNeeds needs;
    private final ItemsToKeep kept;
    private final ReadsItemTags tags;
    /** 一次任务里已经说过、试过的：按任务的记账口记，任务结束记账口没人用了就跟着丢。 */
    private final Map<TaskRecords, Set<String>> settled = new WeakHashMap<>();

    /**
     * @param digs  共用的挖格
     * @param tools 挑工具：看身上有没有一把对路的
     * @param needs 拿东西的引擎的内需入口：现做走它
     * @param kept  要留的东西：做工具的料只用多出来的
     * @param tags  物品挂着哪些标签：认木板、原木、石质工具材料
     */
    public ToolFirstDigging(DigsBlocks digs, LiveDigToolRule tools, ItemNeeds needs, ItemsToKeep kept,
            ReadsItemTags tags) {
        this.digs = Objects.requireNonNull(digs, "digs");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.needs = Objects.requireNonNull(needs, "needs");
        this.kept = Objects.requireNonNull(kept, "kept");
        this.tags = Objects.requireNonNull(tags, "tags");
    }

    @Override public Optional<Action> dig(BlockPos target) {
        return digs.dig(target);
    }

    @Override public Optional<Action> dig(BlockPos target, ToolPrep prep) {
        return Optional.of(new ToolFirst(target.immutable(), prep, () -> digs.dig(target, prep)));
    }

    @Override public Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak) {
        return digs.digChained(trigger, face, shape, mayBreak);
    }

    @Override public Optional<Action> digChained(BlockPos trigger, Direction face, BreakAccelerator.ChainShape shape,
            Predicate<BlockPos> mayBreak, ToolPrep prep) {
        return Optional.of(new ToolFirst(trigger.immutable(), prep,
                () -> digs.digChained(trigger, face, shape, mayBreak, prep)));
    }

    @Override public Optional<Action> digForChanceDrops(BlockPos target) {
        return digs.digForChanceDrops(target);
    }

    @Override public boolean chains() {
        return digs.chains();
    }

    @Override public boolean gone(TickContext context, BlockPos cell) {
        return digs.gone(context, cell);
    }

    /** 挖一格之前先看要不要现做：要做就先做（有上限），做完或不做都接着挖。 */
    private final class ToolFirst implements Action {
        private final BlockPos target;
        private final ToolPrep prep;
        private final Supplier<Optional<Action>> digging;
        private Action making;
        private String makingItem;
        private long deadline;
        private Action dig;
        private boolean decided;

        ToolFirst(BlockPos target, ToolPrep prep, Supplier<Optional<Action>> digging) {
            this.target = target;
            this.prep = prep;
            this.digging = digging;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (dig != null) return dig.tick(context);
            if (making != null) return making(context);
            if (!decided) {
                decided = true;
                Optional<ActionStatus> started = decide(context);
                if (started.isPresent()) return started.get();
            }
            return startDigging(context);
        }

        // 做着：做完了、做不成或超了时限都收手接着挖；做不成的这次任务不再试。
        private ActionStatus making(TickContext context) {
            ActionStatus status = making.tick(context);
            boolean overdue = context.gameTick() > deadline;
            if (status instanceof ActionStatus.Running && !overdue) return status;
            making.close();
            making = null;
            if (status instanceof ActionStatus.Done) {
                note("现做 " + makingItem, "做好了，换上它接着挖");
            } else {
                String why = status instanceof ActionStatus.Failed failed ? failed.problem().message()
                        : "做了 " + MAKE_BUDGET_TICKS / 20 + " 秒还没做好";
                note("现做 " + makingItem, "没做成：" + why + "；徒手接着挖");
                settledFor().add("gave-up:" + makingItem);
            }
            return startDigging(context);
        }

        private ActionStatus startDigging(TickContext context) {
            Optional<Action> started = digging.get();
            if (started.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, "挖方块的现场动作没接上"));
            }
            dig = started.get();
            return dig.tick(context);
        }

        // 身上没有对路的工具才算账：划算且料够就开始做，料不够说一句徒手挖；划不来、看不清现场就直接挖。
        private Optional<ActionStatus> decide(TickContext context) {
            LocalPlayer player = context.player() == null ? null : context.player().localPlayer();
            if (player == null || player.level() == null || !player.level().isLoaded(target)) return Optional.empty();
            BlockState state = player.level().getBlockState(target);
            if (state.isAir()) return Optional.empty();
            if (!(tools.verdict(player, state, 0, prep.expectedBlocks()) instanceof DigToolChoice.Verdict.None none)) {
                return Optional.empty();
            }
            DigToolChoice.Block block = tools.block(player, state);
            List<ToolMaking.Option> options = options(state);
            if (block == null || options.isEmpty()) return Optional.empty();
            ToolMaking.Decision decision = ToolMaking.decide(block, none.handTicks(), prep.expectedBlocks(), options,
                    stock(player));
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (decision instanceof ToolMaking.Decision.NoMaterial lacking) {
                if (settledFor().add("short:" + lacking.option().itemId())) {
                    note("徒手挖 " + blockId, "没现做 " + lacking.option().itemId() + "：" + lacking.why());
                }
                return Optional.empty();
            }
            if (!(decision instanceof ToolMaking.Decision.Make make)) return Optional.empty();
            if (settledFor().contains("gave-up:" + make.option().itemId())) return Optional.empty();
            makingItem = make.option().itemId();
            ItemRequest request = new ItemRequest(WantedItem.ofItem(makingItem), 1, PURPOSE);
            making = prep.records() == null ? needs.actionFor(request, prep.permissions())
                    : needs.actionFor(request, prep.permissions(), prep.records());
            deadline = context.gameTick() + MAKE_BUDGET_TICKS;
            note("现做 " + makingItem, "挖 " + blockId + " 徒手一格 " + none.handTicks() + " 刻，有它每格省 "
                    + make.savedPerBlock() + " 刻，这一趟约 " + prep.expectedBlocks() + " 格，划得来");
            return Optional.of(ActionStatus.progressed());
        }

        private void note(String tried, String whatHappened) {
            if (prep.records() != null) prep.records().attempt(tried, whatHappened);
        }

        private Set<String> settledFor() {
            if (prep.records() == null) return new HashSet<>();
            return settled.computeIfAbsent(prep.records(), records -> new HashSet<>());
        }

        @Override public void pause() {
            if (making != null) making.pause();
            if (dig != null) dig.pause();
        }

        @Override public void close() {
            if (making != null) making.close();
            if (dig != null) dig.close();
        }

        @Override public Interruptibility interruptibility() {
            if (making != null) return making.interruptibility();
            return dig == null ? Interruptibility.WORKING : dig.interruptibility();
        }

        @Override public String describe() {
            if (making != null) return "现做 " + makingItem + "，做好再挖 " + target.toShortString();
            return dig == null ? "挖 " + target.toShortString() : dig.describe();
        }
    }

    // 能现做的几种：看这一格归哪种工具挖（镐、斧、锹、锄，按方块标签认），木的在前、石的在后；
    // 速度倍数与能不能掉落问原版的物品。
    private static List<ToolMaking.Option> options(BlockState state) {
        String kind = state.is(BlockTags.MINEABLE_WITH_PICKAXE) ? "pickaxe"
                : state.is(BlockTags.MINEABLE_WITH_AXE) ? "axe"
                : state.is(BlockTags.MINEABLE_WITH_SHOVEL) ? "shovel"
                : state.is(BlockTags.MINEABLE_WITH_HOE) ? "hoe" : null;
        if (kind == null) return List.of();
        List<ToolMaking.Option> options = new ArrayList<>();
        for (ToolMaking.Head head : ToolMaking.Head.values()) {
            String itemId = "minecraft:" + (head == ToolMaking.Head.PLANKS ? "wooden_" : "stone_") + kind;
            ItemStack tool = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)));
            if (tool.isEmpty()) continue;
            options.add(new ToolMaking.Option(itemId, head, tool.getDestroySpeed(state),
                    !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state)));
        }
        return List.copyOf(options);
    }

    // 身上能拿来做工具的料：每种按身上件数减去要留的，木板、原木、木棍、石质工具材料、工作台分开数；
    // 被留着的另记，挡住时说得清。
    private ToolMaking.Stock stock(LocalPlayer player) {
        Map<String, Integer> carried = new LinkedHashMap<>();
        var inventory = player.getInventory();
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) carried.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    stack.getCount(), Integer::sum);
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) carried.merge(BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString(),
                offhand.getCount(), Integer::sum);
        int planks = 0, logs = 0, sticks = 0, stone = 0, keptPlanks = 0, keptStone = 0;
        boolean table = false;
        for (var entry : carried.entrySet()) {
            String itemId = entry.getKey();
            int count = entry.getValue();
            int free = Math.max(0, count - Math.min(count, kept.keep(itemId)));
            Set<String> itemTags = tags.tagsOf(itemId);
            if (itemTags.contains("minecraft:planks")) {
                planks += free;
                keptPlanks += count - free;
            } else if (itemTags.contains("minecraft:logs")) {
                logs += free;
                keptPlanks += (count - free) * 4;
            } else if (itemTags.contains("minecraft:stone_tool_materials")) {
                stone += free;
                keptStone += count - free;
            }
            if (itemId.equals("minecraft:stick")) sticks += free;
            if (itemId.equals("minecraft:crafting_table") && free > 0) table = true;
        }
        return new ToolMaking.Stock(planks, logs, sticks, stone, table, keptPlanks, keptStone);
    }
}
