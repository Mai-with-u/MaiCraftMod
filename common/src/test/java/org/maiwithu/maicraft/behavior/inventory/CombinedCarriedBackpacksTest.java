// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CollectedRecords;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 多个随身背包组合：空槽求和、观察摘要合并，存放时按顺序尝试直到有一只接手并成功，
 * 不会因为第一只（精妙背包）排在前面而忽略第二只（便携存储元件）。
 */
class CombinedCarriedBackpacksTest {

    private static final BackpackStack COBBLE = new BackpackStack("minecraft:cobblestone", 64, 64, false, false, false, true);
    private static final TickContext NOW = new TickContext() {
        @Override public long gameTick() {
            return 100L;
        }

        @Override public PlayerContext player() {
            return null;
        }
    };

    private static final class StubBackpack implements CarriedBackpack {
        int slots;
        boolean accept = true;
        boolean failOnTick;
        String failMessage = "背包放不下";
        final List<String> stored = new ArrayList<>();
        int closes;
        List<CarriedPack> packs = List.of();

        StubBackpack(int slots) {
            this.slots = slots;
        }

        @Override public int freeSlots() {
            return slots;
        }

        @Override public Optional<Action> store(BackpackStack stack, TaskRecords records) {
            if (!accept) return Optional.empty();
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    if (failOnTick) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, failMessage));
                    }
                    stored.add(stack.itemId());
                    records.change(new Change(Change.Kind.ITEM_STORED, stack.itemId(), stack.count(), "存进随身背包"));
                    return ActionStatus.done();
                }

                @Override public void close() {
                    closes++;
                }

                @Override public String describe() {
                    return "放进替身背包";
                }
            });
        }

        @Override public List<CarriedPack> carried() {
            return packs;
        }
    }

    @Test
    void 空列表给空_单个随身背包原样返回() {
        assertTrue(CombinedCarriedBackpacks.of(List.of()).isEmpty());
        StubBackpack only = new StubBackpack(5);
        assertSame(only, CombinedCarriedBackpacks.of(List.of(only)).orElseThrow());
    }

    @Test
    void 双背包共存时空槽求和且第一只无空槽或拒收时不忽略第二只() {
        StubBackpack sophisticated = new StubBackpack(0);
        sophisticated.packs = List.of(new CarriedBackpack.CarriedPack("sophisticatedbackpacks:backpack",
                "curios:back", true, 0, Map.of("minecraft:dirt", 64)));
        StubBackpack portableCell = new StubBackpack(12);
        portableCell.packs = List.of(new CarriedBackpack.CarriedPack("ae2:portable_item_cell_1k",
                "inventory", true, 12, Map.of()));

        CarriedBackpack combined = CombinedCarriedBackpacks.of(List.of(sophisticated, portableCell)).orElseThrow();
        assertEquals(12, combined.freeSlots(), "精妙背包满槽时仍能看到第二只便携元件的 12 格空槽");
        assertEquals(2, combined.carried().size(), "两只随身容器的观察摘要合并呈现");

        CollectedRecords records = new CollectedRecords();
        Action action = combined.store(COBBLE, records).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, action.tick(NOW));
        assertTrue(sophisticated.stored.isEmpty());
        assertEquals(List.of("minecraft:cobblestone"), portableCell.stored, "第一只满时交第二只成功存放");
        assertEquals(1, records.changes.size());

        // 第一只虽报有空槽但同步拒收该物品，仍转交第二只。
        sophisticated.slots = 4;
        sophisticated.accept = false;
        assertEquals(16, combined.freeSlots(), "两只背包空槽求和");
        Action second = combined.store(COBBLE, new CollectedRecords()).orElseThrow();
        assertInstanceOf(ActionStatus.Done.class, second.tick(NOW));
        assertEquals(2, portableCell.stored.size());
    }

    @Test
    void 第一只背包存放中途失败时收尾并自动改试第二只() {
        StubBackpack first = new StubBackpack(3);
        first.failOnTick = true;
        first.failMessage = "第一只过滤拒收";
        StubBackpack second = new StubBackpack(6);

        CarriedBackpack combined = CombinedCarriedBackpacks.of(List.of(first, second)).orElseThrow();
        CollectedRecords records = new CollectedRecords();
        Action action = combined.store(COBBLE, records).orElseThrow();

        // 第一刻：第一只失败后收尾并切到第二只，上报有进展等待下一刻推进第二只。
        assertInstanceOf(ActionStatus.Running.class, action.tick(NOW));
        assertEquals(1, first.closes, "切到下一只前必须收尾前一只动作");
        // 第二刻：第二只成功存进。
        assertInstanceOf(ActionStatus.Done.class, action.tick(NOW));
        assertEquals(List.of("minecraft:cobblestone"), second.stored);
        assertEquals(1, second.closes);
    }

    @Test
    void 两只背包都存放失败时如实回报失败原因() {
        StubBackpack first = new StubBackpack(3);
        first.failOnTick = true;
        first.failMessage = "第一只满了";
        StubBackpack second = new StubBackpack(3);
        second.failOnTick = true;
        second.failMessage = "第二只没电了";

        CarriedBackpack combined = CombinedCarriedBackpacks.of(List.of(first, second)).orElseThrow();
        Action action = combined.store(COBBLE, new CollectedRecords()).orElseThrow();

        assertInstanceOf(ActionStatus.Running.class, action.tick(NOW));
        ActionStatus end = action.tick(NOW);
        ActionStatus.Failed failed = assertInstanceOf(ActionStatus.Failed.class, end);
        assertEquals("第二只没电了", failed.problem().message());
    }
}
