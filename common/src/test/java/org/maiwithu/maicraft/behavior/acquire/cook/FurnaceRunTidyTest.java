// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.InteractionSender;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.storage.DocumentStore;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 熔炉收尾收拾阶段的离线替身测试：确认炉内同时残留自添燃料与自装原料，或只残留其中一项时，
 * 收拾阶段都会先等上一笔搬运结清再发下一笔，零状态异常且真正将本趟放入的物品收回背包后关界面。
 */
class FurnaceRunTidyTest {

    private static final WorldPosition FURNACE_AT = new WorldPosition(10, 64, 10, "minecraft:overworld");
    private static final String RECIPE_ID = "test:cooked_beef";

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 炉内同时残留自添燃料与自装原料_收拾阶段平滑收回两项零异常(@TempDir Path temp) {
        // 回到之前留着烧的那台熔炉：产出格已有 1 块熟牛肉（收完即达目标），燃料槽还剩 2 块自添煤炭，
        // 投入口还剩 3 块自装生牛肉；收完熟牛肉进入收拾阶段后，两项应依次平滑收回背包，不抛异常。
        FakeFurnaceMenu furnace = new FakeFurnaceMenu();
        furnace.container.set(0, new ItemStack(Items.BEEF, 3));
        furnace.container.set(1, new ItemStack(Items.COAL, 2));
        furnace.container.set(2, new ItemStack(Items.COOKED_BEEF, 1));

        FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
        left.leave(new LeftCooking(FURNACE_AT, WorkstationRecipe.Kind.SMELTING, RECIPE_ID,
                "minecraft:cooked_beef", "熟牛肉", "minecraft:beef", "生牛肉", "minecraft:coal",
                3, 3, 0, 0, 600, null));

        FurnaceRun run = createRun(temp, furnace, left);
        ActionStatus result = driveToEnd(run, 40);

        assertInstanceOf(ActionStatus.Done.class, result, "收完成品并收回燃料与原料后应顺利结束");
        assertTrue(furnace.closed, "收尾后应关上熔炉界面");
        assertTrue(furnace.container.get(0).isEmpty(), "本趟自装的生牛肉应收回背包");
        assertTrue(furnace.container.get(1).isEmpty(), "本趟自添的煤炭应收回背包");
        assertTrue(furnace.container.get(2).isEmpty(), "成品熟牛肉应已收进背包");
        assertEquals(1, furnace.carriedCount(Items.COOKED_BEEF));
        assertEquals(2, furnace.carriedCount(Items.COAL));
        assertEquals(3, furnace.carriedCount(Items.BEEF));
        assertEquals(List.of(2, 1, 0), furnace.quickMovedSlots, "应按收成品 -> 收燃料 -> 收原料顺序各结清一笔");
    }

    @Test
    void 炉内只残留一项自添燃料_收拾阶段真正收回而不是提前关界面(@TempDir Path temp) {
        // 投入口已烧空，只剩燃料槽里本趟自添的 2 块煤炭：收拾阶段不能在发起搬运的下一刻直接关界面，
        // 必须推进该笔搬运把煤炭真正收进背包后再关界面。
        FakeFurnaceMenu furnace = new FakeFurnaceMenu();
        furnace.container.set(1, new ItemStack(Items.COAL, 2));
        furnace.container.set(2, new ItemStack(Items.COOKED_BEEF, 1));

        FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
        left.leave(new LeftCooking(FURNACE_AT, WorkstationRecipe.Kind.SMELTING, RECIPE_ID,
                "minecraft:cooked_beef", "熟牛肉", "minecraft:beef", "生牛肉", "minecraft:coal",
                1, 1, 0, 0, 200, null));

        FurnaceRun run = createRun(temp, furnace, left);
        ActionStatus result = driveToEnd(run, 40);

        assertInstanceOf(ActionStatus.Done.class, result);
        assertTrue(furnace.closed);
        assertTrue(furnace.container.get(1).isEmpty(), "只剩燃料时也应真正收回煤炭，而不是提前关界面");
        assertEquals(2, furnace.carriedCount(Items.COAL));
        assertEquals(1, furnace.carriedCount(Items.COOKED_BEEF));
        assertEquals(List.of(2, 1), furnace.quickMovedSlots);
    }

    @Test
    void 炉内是原有燃料且只残留自装原料_不拿原有燃料并完整收回自装原料(@TempDir Path temp) {
        // 本趟未添燃料（fuelItemId 为 null），燃料槽里是炉子原有的煤炭，投入口剩 2 块自装生牛肉：
        // 收拾阶段不拿炉子原有的煤炭，只把自装生牛肉完整收回背包再关界面。
        FakeFurnaceMenu furnace = new FakeFurnaceMenu();
        furnace.container.set(0, new ItemStack(Items.BEEF, 2));
        furnace.container.set(1, new ItemStack(Items.COAL, 4));
        furnace.container.set(2, new ItemStack(Items.COOKED_BEEF, 1));

        FurnacesLeftCooking left = new FurnacesLeftCooking(FurnacesLeftCooking.SetsTimers.NONE);
        left.leave(new LeftCooking(FURNACE_AT, WorkstationRecipe.Kind.SMELTING, RECIPE_ID,
                "minecraft:cooked_beef", "熟牛肉", "minecraft:beef", "生牛肉", null,
                2, 2, 0, 0, 400, null));

        FurnaceRun run = createRun(temp, furnace, left);
        ActionStatus result = driveToEnd(run, 40);

        assertInstanceOf(ActionStatus.Done.class, result);
        assertTrue(furnace.closed);
        assertTrue(furnace.container.get(0).isEmpty(), "自装生牛肉应收回背包");
        assertEquals(4, furnace.container.get(1).getCount(), "炉子原有的煤炭不能拿走");
        assertEquals(2, furnace.carriedCount(Items.BEEF));
        assertEquals(0, furnace.carriedCount(Items.COAL));
        assertEquals(List.of(2, 0), furnace.quickMovedSlots);
    }

    private static FurnaceRun createRun(Path temp, FakeFurnaceMenu furnace, FurnacesLeftCooking left) {
        RecipeHolder<?> holder = new RecipeHolder<>(ResourceLocation.parse(RECIPE_ID),
                new SmeltingRecipe("", CookingBookCategory.FOOD,
                        Ingredient.of(Items.BEEF), new ItemStack(Items.COOKED_BEEF), 0.35f, 200));
        WorkstationRecipe recipe = new WorkstationRecipe(RECIPE_ID, WorkstationRecipe.Kind.SMELTING,
                WantedItem.ofItem("minecraft:cooked_beef"), 1,
                List.of(new WorkstationRecipe.IngredientStack(WantedItem.ofItem("minecraft:beef"), 1, List.of())));
        WorldMemory memory = new WorldMemory(new DocumentStore(temp.resolve("furnace-tidy.sqlite")), "world-1");
        ReadsFuels fuels = id -> "minecraft:coal".equals(id) ? 1600 : 0;
        StationOpening opening = new StationOpening() {
            @Override public ActionStatus tick(TickContext tick) { return ActionStatus.done(); }
            @Override public MenuContent menus() { return furnace::reading; }
            @Override public OpenedMenu claimMenu() { return furnace; }
            @Override public void pause() {}
            @Override public void close() {}
        };
        return new FurnaceRun(holder, recipe, List.of(FURNACE_AT), 1, pos -> opening,
                fuels, memory, null, left, false);
    }

    private static ActionStatus driveToEnd(FurnaceRun run, int maxTicks) {
        StubTick tick = new StubTick();
        ActionStatus status = ActionStatus.running();
        for (int i = 0; i < maxTicks; i++) {
            status = run.tick(tick);
            tick.advance();
            if (!(status instanceof ActionStatus.Running)) return status;
        }
        return status;
    }

    /** 熔炉界面的离线槽位归属：0 投入口、1 燃料槽、2 产出格，3–38 角色背包。 */
    private static final MenuSlots FURNACE_SLOTS = new MenuSlots() {
        @Override public String menuTypeId() { return "minecraft:furnace"; }
        @Override public int slotCount() { return 39; }
        @Override public boolean playerBacked(int slot) { return slot >= 3; }
        @Override public int playerInventoryIndex(int slot) { return slot >= 30 ? slot - 30 : slot + 6; }
    };

    /** 可点格与关闭的熔炉界面替身：快速移动把容器槽整堆搬进背包空格，关界面立刻关闭。 */
    private static final class FakeFurnaceMenu implements OpenedMenu {
        final List<ItemStack> container = new ArrayList<>();
        final List<ItemStack> player = new ArrayList<>();
        final List<Integer> quickMovedSlots = new ArrayList<>();
        boolean closed;

        FakeFurnaceMenu() {
            for (int i = 0; i < 3; i++) container.add(ItemStack.EMPTY);
            for (int i = 0; i < 36; i++) player.add(ItemStack.EMPTY);
        }

        @Override
        public Optional<MenuContent.Reading> reading() {
            if (closed) return Optional.empty();
            List<Integer> containerIds = List.of(0, 1, 2);
            List<SlotSnapshot> containerSnapshots = container.stream().map(FakeFurnaceMenu::snapshot).toList();
            List<Integer> playerIds = new ArrayList<>();
            List<Integer> playerIndices = new ArrayList<>();
            List<SlotSnapshot> playerSnapshots = new ArrayList<>();
            for (int i = 0; i < player.size(); i++) {
                playerIds.add(3 + i);
                playerIndices.add(i < 27 ? 9 + i : i - 27);
                playerSnapshots.add(snapshot(player.get(i)));
            }
            return Optional.of(new MenuContent.Reading(channel(), FURNACE_SLOTS, containerIds, playerIds,
                    playerIndices, containerSnapshots, playerSnapshots));
        }

        private MenuChannel channel() {
            return new MenuChannel() {
                @Override public boolean stillOpen() { return !closed; }
                @Override public boolean cursorCarrying() { return false; }
                @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }
                @Override public boolean click(int slot, int button) { return true; }
                @Override public boolean quickMove(int slot) { return FakeFurnaceMenu.this.quickMove(slot); }
                @Override public boolean selectTradeOffer(int offerIndex) { return true; }
                @Override public void requestClose() { closed = true; }
            };
        }

        @Override public boolean busy() { return false; }
        @Override public boolean cursorEmpty() { return true; }
        @Override public SlotSnapshot cursor() { return SlotSnapshot.empty(); }

        @Override
        public boolean quickMove(int slotId) {
            if (closed || slotId < 0 || slotId >= container.size()) return false;
            quickMovedSlots.add(slotId);
            ItemStack moving = container.get(slotId);
            if (moving.isEmpty()) return true;
            for (int i = 0; i < player.size(); i++) {
                if (player.get(i).isEmpty()) {
                    player.set(i, moving.copy());
                    container.set(slotId, ItemStack.EMPTY);
                    break;
                }
            }
            return true;
        }

        @Override public boolean click(int slotId, int button) { return false; }
        @Override public void noteCursorTakenFrom(int slotId) {}

        @Override
        public Action closing() {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    closed = true;
                    return ActionStatus.done();
                }

                @Override public String describe() { return "关闭替身熔炉界面"; }
            };
        }

        @Override public void abandon() { closed = true; }

        int carriedCount(Item item) {
            return player.stream().filter(stack -> !stack.isEmpty() && stack.is(item))
                    .mapToInt(ItemStack::getCount).sum();
        }

        private static SlotSnapshot snapshot(ItemStack stack) {
            return stack.isEmpty() ? SlotSnapshot.empty() : SlotSnapshot.of(stack);
        }
    }

    /** 仅推进游戏刻与客户端刻的离线上下文替身。 */
    private static final class StubTick implements TickContext {
        private long tick = 1000;
        private final PlayerContext player = new PlayerContext() {
            @Override public LocalPlayer localPlayer() { return null; }
            @Override public ClientLevel level() { return null; }
            @Override public ClientPacketListener connection() { return null; }
            @Override public PlayerInput input() { return null; }
            @Override public InteractionSender interactionSender() { return null; }
            @Override public MenuActions menuActions() { return null; }
            @Override public long clientTick() { return tick; }
            @Override public boolean isCurrent() { return true; }
            @Override public boolean canInteractThisTick() { return true; }
            @Override public boolean tryClaimInteraction() { return true; }
        };

        void advance() { tick++; }
        @Override public long gameTick() { return tick; }
        @Override public PlayerContext player() { return player; }
    }
}
