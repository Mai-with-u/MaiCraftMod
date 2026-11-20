// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsBlockOwnership;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 箱式加工站原生容器取货离线测试：验证出料箱下方接漏斗持续吐入新成品时，
 * 仍按角色背包侧净增量精确核算已取件数并及时停手（不超额狂拿也不误判 0 产出），
 * 同时确认从别人放的出料箱取货后会发出动了别人东西的通报。
 */
class LiveContainerStationOpsTest {

    private static final WorldPosition OUTPUT = new WorldPosition(10, 64, 0, "minecraft:overworld");

    @TempDir
    Path temp;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void takeOutputCountsBackpackDeltaUnderContinuousHopperRefillAndNotifiesOthers() {
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("live-ops.sqlite")), "b".repeat(64));
        ReadsBlockOwnership ownership = (dim, x, y, z) -> Optional.of(new ReadsBlockOwnership.PlacedBy("other-uuid"));
        Protection protection = new Protection(ownership, memory, memory, "self-uuid");
        List<TaskEvent.Kind> publishedKinds = new ArrayList<>();
        List<String> publishedMessages = new ArrayList<>();
        TaskEventSink sink = (kind, message) -> {
            publishedKinds.add(kind);
            publishedMessages.add(message);
        };
        OthersThingsNotice notice = new OthersThingsNotice(protection, id -> false, sink);

        // 模拟出料箱接漏斗持续吐货：容器格每次被取走 4 个铁锭后，立刻在同格补回 4 个新铁锭，容器侧总量恒为 4。
        HopperRefillBox box = new HopperRefillBox(new ItemStack(Items.IRON_INGOT, 4));
        LiveContainerStationOps ops = new LiveContainerStationOps(
                itemId -> Set.of(), memory, () -> null,
                (pos, perm) -> instantOpen(box), notice, pos -> Optional.of("minecraft:chest"));

        ContainerStationOps.TakeAction take = ops.takeOutput(OUTPUT, "鼓风站",
                WantedItem.ofItem("minecraft:iron_ingot"), 4, null, Permissions.DEFAULT);
        ActionStatus status = drive(take, 60);

        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(4, take.taken(), "容器侧被漏斗补满时仍应按背包净增量准确记 4 件");
        assertEquals(4, box.carriedCount(), "取够 4 件即停手关箱，不能超额狂拿");
        assertTrue(box.closed, "取完应正常关箱");
        assertEquals(List.of(TaskEvent.Kind.OTHERS_THINGS_TOUCHED), publishedKinds, "动了别人放的出料箱应补通报");
        assertTrue(publishedMessages.getFirst().contains("箱子"), publishedMessages.getFirst());
    }

    private static ActionStatus drive(Action action, int maxSteps) {
        long tick = 1000L;
        ActionStatus last = ActionStatus.running();
        for (int i = 0; i < maxSteps; i++) {
            long current = tick++;
            last = action.tick(new TickContext() {
                @Override public long gameTick() { return current; }
                @Override public PlayerContext player() { return null; }
            });
            if (!(last instanceof ActionStatus.Running)) return last;
        }
        return last;
    }

    private static MenuOpening instantOpen(OpenedMenu menu) {
        return new MenuOpening() {
            private boolean opened;
            @Override public Optional<OpenedMenu> opened() { return opened ? Optional.of(menu) : Optional.empty(); }
            @Override public ActionStatus tick(TickContext tick) { opened = true; return ActionStatus.done(); }
            @Override public String describe() { return "打开替身出料箱"; }
        };
    }

    private static final class HopperRefillBox implements OpenedMenu {
        private final List<ItemStack> container = new ArrayList<>();
        private final List<ItemStack> player = new ArrayList<>();
        private final ItemStack refillTemplate;
        boolean closed;

        HopperRefillBox(ItemStack initial) {
            for (int i = 0; i < 27; i++) container.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
            container.set(0, initial.copy());
            this.refillTemplate = initial.copy();
        }

        int carriedCount() {
            return player.stream().mapToInt(ItemStack::getCount).sum();
        }

        @Override public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> cIds = new ArrayList<>();
            List<SlotSnapshot> cSnaps = new ArrayList<>();
            for (int i = 0; i < container.size(); i++) {
                cIds.add(i);
                cSnaps.add(snapshot(container.get(i)));
            }
            List<Integer> pIds = new ArrayList<>();
            List<Integer> pIndices = new ArrayList<>();
            List<SlotSnapshot> pSnaps = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                pIds.add(27 + i);
                pIndices.add(i);
                pSnaps.add(snapshot(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(CHANNEL, SLOTS, cIds, pIds, pIndices, cSnaps, pSnaps));
        }

        private static SlotSnapshot snapshot(ItemStack item) {
            return item.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(item);
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        // 快速移走这一格进背包，同时模拟漏斗立刻往出料箱补回同等件数（容器总量纹丝不动）。
        @Override public boolean quickMove(int slotId) {
            ItemStack moving = container.get(slotId);
            if (moving.isEmpty()) return true;
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    container.set(slotId, refillTemplate.copy());
                    break;
                }
            }
            return true;
        }

        @Override public boolean click(int slotId, int button) { return true; }
        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext tick) { closed = true; return ActionStatus.done(); }
                @Override public String describe() { return "关闭替身出料箱"; }
            };
        }

        @Override public void abandon() { closed = true; }
    }

    private static final MenuChannel CHANNEL = new MenuChannel() {
        @Override public boolean stillOpen() { return true; }
        @Override public boolean cursorCarrying() { return false; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
        @Override public boolean click(int slot, int button) { return true; }
        @Override public boolean quickMove(int slot) { return true; }
        @Override public boolean selectTradeOffer(int offerIndex) { return true; }
        @Override public void requestClose() {}
    };

    private static final MenuSlots SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "minecraft:generic_9x3"; }
        @Override public int slotCount() { return 63; }
        @Override public boolean playerBacked(int slot) { return slot >= 27; }
        @Override public int playerInventoryIndex(int slot) { return slot - 27; }
    };
}
