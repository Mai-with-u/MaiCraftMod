// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.inventory.spi.NetworkStorage;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.inventory.hands.StackMerger;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/** 腾地方：一步一个真实动作，做完重新看背包；做不成换下一步、不原地重试；不替玩家丢东西，腾不出如实失败。 */
class InventorySpaceTest {

    private static final KnownContainer 家门口的箱子 =
            new KnownContainer("家门口的箱子", WorldPosition.here(12, -60, 208), "minecraft:chest");

    // 测试替身：背包就是一张可以改的格子表，腾挪动作直接改它，好核对下一步看到的现场。
    private static final class FakeBackpack implements BackpackView {
        private final List<BackpackStack> stacks = new ArrayList<>();
        private final int capacity;

        FakeBackpack(int capacity, BackpackStack... initial) {
            this.capacity = capacity;
            stacks.addAll(List.of(initial));
        }

        @Override public List<BackpackStack> stacks() {
            return List.copyOf(stacks);
        }

        @Override public int usedSlots() {
            return stacks.size();
        }

        @Override public int totalSlots() {
            return capacity;
        }

        @Override public OptionalInt hotbarSlotOf(String itemId) {
            return OptionalInt.empty();
        }

        @Override public int selectedHotbarSlot() {
            return 0;
        }

        void removeOne(String itemId) {
            for (BackpackStack stack : stacks) {
                if (stack.itemId().equals(itemId)) {
                    stacks.remove(stack);
                    return;
                }
            }
        }
    }

    /** 合并替身：并一对就把那种散着的物品拿掉一格，变化照真读端的样子记一笔。 */
    private static final class FakeMerger implements StackMerger {
        final List<String> merged = new ArrayList<>();
        private final FakeBackpack backpack;

        FakeMerger(FakeBackpack backpack) {
            this.backpack = backpack;
        }

        @Override public Action mergeOne(TaskRecords records) {
            return oneTick(() -> {
                for (BackpackStack stack : backpack.stacks()) {
                    long piles = backpack.stacks().stream().filter(other -> other.itemId().equals(stack.itemId())).count();
                    if (piles > 1) {
                        merged.add(stack.itemId());
                        backpack.removeOne(stack.itemId());
                        records.change(new Change(Change.Kind.OTHER, stack.itemId(), stack.count(), "并成整堆"));
                        return ActionStatus.done();
                    }
                }
                return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "没有能并成一堆的散堆"));
            });
        }
    }

    /** 随身背包替身：放得进就把那一堆从背包里拿走；坏了就每次都放不进。 */
    private static final class FakeCarried implements CarriedBackpack {
        final List<String> stored = new ArrayList<>();
        boolean broken;
        int tried;
        private final FakeBackpack backpack;

        FakeCarried(FakeBackpack backpack) {
            this.backpack = backpack;
        }

        @Override public int freeSlots() {
            return 5;
        }

        @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
            return Optional.of(oneTick(() -> {
                tried++;
                if (broken) return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "随身背包打不开"));
                stored.add(stack.itemId());
                backpack.removeOne(stack.itemId());
                records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), stack.count(), "放进了随身背包"));
                return ActionStatus.done();
            }));
        }
    }

    // 测试替身：有终端可用（身上的或附近装着的）时存一堆就从背包表里拿掉；坏了就说没连上；
    // 没有终端时挑不到。记下每次是照什么挑法找终端的。
    private static final class FakeNetwork implements NetworkStorage {
        final List<String> stored = new ArrayList<>();
        final List<TerminalSearch> searches = new ArrayList<>();
        boolean broken;
        boolean reachable = true;
        private final FakeBackpack backpack;

        FakeNetwork(FakeBackpack backpack) {
            this.backpack = backpack;
        }

        @Override public boolean reachable(TerminalSearch search) {
            searches.add(search);
            return reachable;
        }

        @Override public TerminalPick store(List<BackpackStack> stacks, String purpose, TerminalSearch search,
                TaskRecords records) {
            searches.add(search);
            if (!reachable) return new TerminalPick.NotFound("附近 16 格内没有 ME 终端");
            return new TerminalPick.Chosen(oneTick(() -> {
                if (broken) return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "终端没连上 ME 网络"));
                for (BackpackStack stack : stacks) {
                    stored.add(stack.itemId());
                    backpack.removeOne(stack.itemId());
                    records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), stack.count(),
                            purpose + "：存进了网络"));
                }
                return ActionStatus.done();
            }));
        }
    }

    private interface Step {
        ActionStatus run();
    }

    private static Action oneTick(Step step) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return step.run();
            }

            @Override public String describe() {
                return "替身的一步";
            }
        };
    }

    private static BackpackStack junk(String itemId, int count) {
        return new BackpackStack(itemId, count, 64, false, false, false, true);
    }

    private static BackpackStack pearl() {
        return new BackpackStack("minecraft:ender_pearl", 2, 16, false, false, true, false);
    }

    private static final TickContext NOW = new TickContext() {
        @Override public long gameTick() {
            return 100;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    // 推进到收场；腾地方每做完一步回来看一次背包，几十刻足够。
    private static ActionStatus run(Action action) {
        for (int i = 0; i < 50; i++) {
            ActionStatus status = action.tick(NOW);
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        throw new AssertionError("腾地方 50 刻还没收场：" + action.describe());
    }

    private static Action makeRoom(InventorySpace space, int slots, Set<String> keep, TaskRecords records) {
        return space.makeRoom(slots, "捡起掉落的铁锭", keep, Permissions.DEFAULT, records);
    }

    @Test
    void 还有空格就什么都不做() {
        CollectedRecords records = new CollectedRecords();
        InventorySpace space = new InventorySpace(new FakeBackpack(36), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertTrue(records.changes.isEmpty());
    }

    @Test
    void 能合并的散堆先并_不往外挪() {
        FakeBackpack backpack = new FakeBackpack(3,
                junk("minecraft:cobblestone", 32), junk("minecraft:cobblestone", 32), junk("minecraft:dirt", 64));
        FakeMerger merger = new FakeMerger(backpack);
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.of(merger), Optional.of(carried),
                Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(List.of("minecraft:cobblestone"), merger.merged);
        assertTrue(carried.stored.isEmpty(), "并出一格就够了，泥土不用挪");
        assertEquals(1, records.changes.size());
    }

    @Test
    void 挪最不值钱的那堆_变化记进发起任务的结果() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(List.of("minecraft:cobblestone"), carried.stored);
        assertEquals(Change.Kind.ITEM_STORED, records.changes.getFirst().kind());
        assertEquals(64, records.changes.getFirst().count());
    }

    @Test
    void 身上的终端连着网络就存进网络_有用的也不丢() {
        // 链条是做出来的有用东西：有网络就存进去，不丢也不问。
        FakeBackpack backpack = new FakeBackpack(1, new BackpackStack("minecraft:chain", 32, 64, false, false, false, false));
        FakeNetwork network = new FakeNetwork(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), () -> Optional.of(network));
        CollectedRecords records = new CollectedRecords();

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(List.of("minecraft:chain"), network.stored);
        assertTrue(records.changes.getFirst().note().contains("存进了网络"), records.changes::toString);
    }

    @Test
    void 腾地方找终端就在身边_身上的优先_没带找16格内装着的() {
        // 存进网络的挑法交给网络存储：腾地方只说"就在身边、16 格内"，身上带不带无线终端由它去认。
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:dirt", 64));
        FakeNetwork network = new FakeNetwork(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), () -> Optional.of(network));

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), new CollectedRecords())));
        assertEquals(List.of("minecraft:dirt"), network.stored);
        assertTrue(network.searches.stream().allMatch(search -> search.nearMeFirst()
                && search.radius() == InventorySpace.NEARBY_CONTAINER_RANGE), network.searches::toString);
    }

    @Test
    void 身上没带附近也没有终端_不排存网络这一步() {
        // 没有终端可用：计划里不出现存网络，零碎照常放进随身背包。
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:dirt", 64));
        FakeNetwork network = new FakeNetwork(backpack);
        network.reachable = false;
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty(), () -> Optional.of(network));

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), new CollectedRecords())));
        assertTrue(network.stored.isEmpty());
        assertEquals(List.of("minecraft:dirt"), carried.stored);
    }

    @Test
    void 网络存不进就改走别的路() {
        // 终端没连上：不再试网络，零碎照常放进随身背包。
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:dirt", 64));
        FakeNetwork network = new FakeNetwork(backpack);
        network.broken = true;
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty(), () -> Optional.of(network));

        assertInstanceOf(ActionStatus.Done.class, run(makeRoom(space, 1, Set.of(), new CollectedRecords())));
        assertEquals(List.of("minecraft:dirt"), carried.stored, "网络存不进，改放随身背包");
    }

    @Test
    void 要留着的东西不往外挪() {
        FakeBackpack backpack = new FakeBackpack(2, junk("minecraft:cobblestone", 64), junk("minecraft:dirt", 64));
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty());

        // 正要拿圆石：腾地方不把圆石挪走，挪的是泥土。
        run(makeRoom(space, 1, Set.of("minecraft:cobblestone"), new CollectedRecords()));
        assertEquals(List.of("minecraft:dirt"), carried.stored);
    }

    @Test
    void 一步做不成换下一步_不原地重试() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64));
        FakeCarried carried = new FakeCarried(backpack);
        carried.broken = true;
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        // 随身背包打不开，别处也没地方存：试一次就如实说腾不出，不在随身背包上反复点。
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(1, carried.tried);
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(records.attempts.stream().anyMatch(line -> line.contains("随身背包打不开")), records.attempts::toString);
    }

    @Test
    void 只剩贵重品_如实失败_建议不列贵重品() {
        FakeBackpack backpack = new FakeBackpack(1, pearl());
        FakeCarried carried = new FakeCarried(backpack);
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.of(carried),
                Optional.empty(), Optional.empty());
        CollectedRecords records = new CollectedRecords();

        // 只剩贵重品能动也不再问"同不同意丢"：同意没有通路可送回来，如实说腾不出，处置交调用方。
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 1, Set.of(), records)));
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("腾不出 1 格"), failed.problem().message());
        assertTrue(failed.problem().suggestion().contains("drop_items")
                && failed.problem().suggestion().contains("manage_container"), failed.problem().suggestion());
        // 没动手：背包没变，也没有账。
        assertTrue(carried.stored.isEmpty());
        assertTrue(records.changes.isEmpty());
    }

    @Test
    void 腾不出时建议照现场列_能存的容器带坐标() {
        FakeBackpack backpack = new FakeBackpack(1, junk("minecraft:cobblestone", 64), pearl());
        // 记得家门口有只箱子，但存箱子的部件没接上：搬不动就如实失败，建议里给出箱子的位置。
        InventorySpace space = new InventorySpace(backpack, Optional.empty(), Optional.empty(),
                Optional.of(known -> List.of(家门口的箱子)), Optional.empty());

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 1, Set.of(), new CollectedRecords())));
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(failed.problem().suggestion().contains("家门口的箱子"), failed.problem().suggestion());
        assertTrue(failed.problem().suggestion().contains("12,-60,208"), failed.problem().suggestion());
        // 贵重品不进可丢候选：建议里不出现末影珍珠。
        assertTrue(!failed.problem().suggestion().contains("ender_pearl"), failed.problem().suggestion());
    }

    @Test
    void 连贵重品都算上也不够_如实说腾不出() {
        InventorySpace space = new InventorySpace(new FakeBackpack(1, pearl()), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class,
                run(makeRoom(space, 2, Set.of(), new CollectedRecords())));
        assertEquals(Problem.Kind.INVENTORY_FULL, failed.problem().kind());
        assertTrue(failed.problem().message().contains("腾不出 2 格"), failed.problem().message());
    }
}
