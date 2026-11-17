// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.equip;

import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.inventory.InventorySpace;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.ReadsEquipment;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 穿卸装备的离线场景：卸下先腾地方、逐格核对；部分没卸下来按仍穿着如实列出，
 * 没能确认的记进 unconfirmed；腾不出地方按背包满结束。
 */
class EquipTaskTest {

    private static final String HELMET = "minecraft:iron_helmet";
    private static final String CHESTPLATE = "minecraft:iron_chestplate";
    private static final String SWORD = "minecraft:iron_sword";

    /** 装备栏替身：一张可以改的格子表。 */
    static final class FakeEquipment implements ReadsEquipment {
        final Map<GearSlotName, BackpackStack> slots = new HashMap<>();

        void put(GearSlotName name, String itemId) {
            slots.put(name, new BackpackStack(itemId, 1, 64, true, false, false, false));
        }

        void clear(GearSlotName name) {
            slots.remove(name);
        }

        @Override public Optional<BackpackStack> slot(GearSlotName name) {
            return Optional.ofNullable(slots.get(name));
        }
    }

    /** 背包替身：只报占了几格，腾挪模型用它判断。 */
    record FullBackpack(int used, int total) implements BackpackView {
        @Override public List<BackpackStack> stacks() { return List.of(); }
        @Override public int usedSlots() { return used; }
        @Override public int totalSlots() { return total; }
    }

    /** 穿卸接缝替身：动作跑到哪一刻、执行时改不改装备栏，都由测试摆布。 */
    static final class StubGearChanges implements GearChanges {
        final List<String> calls = new ArrayList<>();
        private final FakeEquipment equipment;
        /** 卸下动作做完时，栏位是否真的空了。 */
        boolean takeOffSucceeds = true;
        /** 穿上动作做完时，栏位里是否真的换成了目标物品。 */
        boolean wearSucceeds = true;
        /** 动作第一次 tick 就做完。 */
        boolean instant = true;
        /** 不为 null 时动作当场失败（界面没开上、那件没找到），带这个原因。 */
        Problem fails;

        StubGearChanges(FakeEquipment equipment) { this.equipment = equipment; }

        @Override public Optional<Action> wear(String itemId, GearSlotName slot) {
            calls.add("wear:" + slot.paramName());
            return Optional.of(new GearAction(() -> {
                if (wearSucceeds) equipment.put(slot, itemId);
            }));
        }

        @Override public Optional<Action> takeOff(GearSlotName slot) {
            calls.add("takeOff:" + slot.paramName());
            return Optional.of(new GearAction(() -> {
                if (takeOffSucceeds) equipment.clear(slot);
            }));
        }

        private final class GearAction implements Action {
            private final Runnable effect;
            private boolean done;
            GearAction(Runnable effect) { this.effect = effect; }
            @Override public ActionStatus tick(TickContext context) {
                if (fails != null) return ActionStatus.failed(fails);
                if (!done) {
                    effect.run();
                    done = true;
                }
                return instant || done ? ActionStatus.done() : ActionStatus.running();
            }
            @Override public String describe() { return "穿卸动作"; }
        }
    }

    /** 每刻上下文替身：刻号递增，角色本体用不到。 */
    @SuppressWarnings("unused")
    private static final class StubTick implements TickContext {
        long tick;
        @Override public long gameTick() { return tick; }
        @Override public PlayerContext player() { return null; }
    }

    private static TickResult run(Task task, int ticks) {
        TickResult result = TickResult.RUNNING;
        for (int i = 0; i < ticks; i++) {
            StubTick context = new StubTick();
            context.tick = i;
            result = task.tick(context);
            if (result instanceof TickResult.Finished finished) return finished;
        }
        return result;
    }

    @Test
    void 栏位本来就是空的_卸下直接完成() {
        TickResult result = run(new EquipTask(new EquipInput(true, GearSlotName.HEAD, null, null),
                new FakeEquipment(), Optional.empty(), Optional.empty()), 5);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE);
        assertTrue(((TickResult.Finished) result).result().summary().contains("卸下完成"));
    }

    @Test
    void 卸下成功_变化记进背包() {
        FakeEquipment equipment = new FakeEquipment();
        equipment.put(GearSlotName.HEAD, HELMET);
        StubGearChanges gear = new StubGearChanges(equipment);
        TickResult result = run(new EquipTask(new EquipInput(true, GearSlotName.HEAD, null, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE);
        assertTrue(((TickResult.Finished) result).result().changes().stream()
                .anyMatch(change -> change.what().equals(HELMET) && change.count() == 1));
    }

    @Test
    void 整套卸下_没卸完的栏位按仍穿着列出() {
        FakeEquipment equipment = new FakeEquipment();
        equipment.put(GearSlotName.HEAD, HELMET);
        equipment.put(GearSlotName.CHEST, CHESTPLATE);
        StubGearChanges gear = new StubGearChanges(equipment);
        gear.takeOffSucceeds = false;
        // 手工让头的卸下成功：动作做完后头空了，胸甲栏保留。
        TickResult result = run(new EquipTask(new EquipInput(true, null, null, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.PARTIAL);
        TaskResult partial = ((TickResult.Finished) result).result();
        // 还穿着的栏位报中文说法（头上、身上），不再是英文参数名。
        assertEquals("卸下做了一部分：还穿着 头上、身上", partial.summary());
        assertTrue(partial.remaining().contains("仍穿着 头上"));
        assertTrue(partial.details() instanceof EquipTask.EquipDetails details
                && !details.stillWorn().isEmpty());
    }

    @Test
    void 穿上动作做完但栏位没变_按没能确认记录() {
        FakeEquipment equipment = new FakeEquipment();
        StubGearChanges gear = new StubGearChanges(equipment);
        gear.wearSucceeds = false;
        TickResult result = run(new EquipTask(new EquipInput(false, GearSlotName.HEAD, HELMET, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.PARTIAL);
        assertTrue(!((TickResult.Finished) result).result().unconfirmed().isEmpty());
    }

    @Test
    void 主手穿上成功_成功句就是拿到主手_不带栏位计数() {
        // 往主手拿一把剑不是"穿上"：成功句直接说拿到主手，读的人不会再以为是穿盔甲；
        // 变化记录也不再写"穿在了 mainhand"。
        FakeEquipment equipment = new FakeEquipment();
        StubGearChanges gear = new StubGearChanges(equipment);
        TickResult result = run(new EquipTask(new EquipInput(false, GearSlotName.MAINHAND, SWORD, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE);
        assertEquals("拿到主手", ((TickResult.Finished) result).result().summary());
        assertTrue(((TickResult.Finished) result).result().changes().stream()
                .anyMatch(change -> change.note().equals("拿到了主手")));
    }

    @Test
    void 副手穿上成功_成功句就是拿到副手() {
        FakeEquipment equipment = new FakeEquipment();
        StubGearChanges gear = new StubGearChanges(equipment);
        TickResult result = run(new EquipTask(new EquipInput(false, GearSlotName.OFFHAND, SWORD, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE);
        assertEquals("拿到副手", ((TickResult.Finished) result).result().summary());
    }

    @Test
    void 主手卸下成功_成功句就是腾出主手() {
        FakeEquipment equipment = new FakeEquipment();
        equipment.put(GearSlotName.MAINHAND, SWORD);
        StubGearChanges gear = new StubGearChanges(equipment);
        TickResult result = run(new EquipTask(new EquipInput(true, GearSlotName.MAINHAND, null, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.DONE);
        assertEquals("腾出主手", ((TickResult.Finished) result).result().summary());
    }

    @Test
    void 主手穿上没换上_按拿到主手交代没能确认() {
        // 动作做完了主手里却不是这件：总结说"拿到主手的操作没能全部确认"，还差的事是确认主手拿到没有。
        FakeEquipment equipment = new FakeEquipment();
        StubGearChanges gear = new StubGearChanges(equipment);
        gear.wearSucceeds = false;
        TickResult result = run(new EquipTask(new EquipInput(false, GearSlotName.MAINHAND, SWORD, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.PARTIAL);
        TaskResult partial = ((TickResult.Finished) result).result();
        assertEquals("拿到主手的操作没能全部确认，做过什么见 unconfirmed", partial.summary());
        assertEquals(List.of("确认主手拿到没有"), partial.remaining());
        assertTrue(partial.unconfirmed().stream()
                .allMatch(change -> change.note().equals("拿到主手的操作没能确认")));
    }

    @Test
    void 说法按栏位分家_手部是拿到腾出_护甲是穿上卸下() {
        assertEquals("拿到主手 minecraft:iron_sword",
                new EquipInput(false, GearSlotName.MAINHAND, SWORD, null).describe());
        assertEquals("腾出副手", new EquipInput(true, GearSlotName.OFFHAND, null, null).describe());
        assertEquals("卸下 头上", new EquipInput(true, GearSlotName.HEAD, null, null).describe());
        assertEquals("穿上 身上 minecraft:iron_chestplate",
                new EquipInput(false, GearSlotName.CHEST, CHESTPLATE, null).describe());
        assertEquals("卸下 整套护甲", new EquipInput(true, null, null, null).describe());
    }

    @Test
    void 穿上动作当场失败_按失败收场不说完成() {
        // 界面没开上：一个栏位都没穿成，不能回"穿上完成（0 个栏位）"，按失败带上原因。
        FakeEquipment equipment = new FakeEquipment();
        StubGearChanges gear = new StubGearChanges(equipment);
        gear.fails = Problem.of(Problem.Kind.NEED_ITEM, "背包里没找到 " + HELMET);
        TickResult result = run(new EquipTask(new EquipInput(false, GearSlotName.HEAD, HELMET, null),
                equipment, Optional.empty(), Optional.of(gear)), 10);
        TaskResult failed = ((TickResult.Finished) result).result();
        assertEquals(TaskResult.Status.FAILED, failed.status());
        assertEquals(Problem.Kind.NEED_ITEM, failed.problem().kind());
        assertTrue(equipment.slot(GearSlotName.HEAD).isEmpty());
    }

    @Test
    void 背包腾不出地方_按背包满结束() {
        FakeEquipment equipment = new FakeEquipment();
        equipment.put(GearSlotName.HEAD, HELMET);
        // 背包只剩一格且装着贵重品：腾背包不再动它，任务把"腾不出"按背包满带回。
        InventorySpace space = new InventorySpace(ItemsToKeep.NOTHING, new FullBackpack(1, 1),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        TickResult result = run(new EquipTask(new EquipInput(true, GearSlotName.HEAD, null, null),
                equipment, Optional.of(space), Optional.of(new StubGearChanges(equipment))), 10);
        assertTrue(result instanceof TickResult.Finished finished
                && finished.result().status() == TaskResult.Status.FAILED);
        assertEquals(Problem.Kind.INVENTORY_FULL, ((TickResult.Finished) result).result().problem().kind());
        // 贵重品没被丢掉：装备栏原样。
        assertTrue(equipment.slot(GearSlotName.HEAD).isPresent());
    }
}
