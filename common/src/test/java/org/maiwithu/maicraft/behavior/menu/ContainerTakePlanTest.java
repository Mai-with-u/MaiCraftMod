// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.menu.MenuChannel;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuSlots;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;

/** 取货计划：整堆够用整堆拿，尾数按二分堆拆出来放进背包，点完箱里剩的正好是不要的量。 */
class ContainerTakePlanTest {

    /** 容器侧 27 格（槽位号 0–26），背包侧 36 格（27–62）；测试用的箱子格与背包格见各测试。 */
    private static final int BOX_SLOT = 3;
    private static final int PLAYER_SLOT = 27;

    @BeforeAll
    static void 引导物品注册表() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
        // 角色侧 27..62 对应背包格号 9..35（主背包在前、快捷栏在后的原版菜单顺序）。
        @Override public int playerInventoryIndex(int slot) { return slot - 27 + 9; }
    };

    /** 一份界面读数：容器侧 27 格、背包侧 36 格，null 写空格。 */
    private static MenuContent.Reading reading(ItemStack[] box, ItemStack[] player) {
        List<Integer> boxIds = new ArrayList<>();
        List<SlotSnapshot> boxSnapshots = new ArrayList<>();
        for (ItemStack item : box) {
            boxIds.add(boxIds.size());
            boxSnapshots.add(item == null ? SlotSnapshot.empty() : SlotSnapshot.of(item));
        }
        List<Integer> playerIds = new ArrayList<>();
        List<Integer> playerIndices = new ArrayList<>();
        List<SlotSnapshot> playerSnapshots = new ArrayList<>();
        for (ItemStack item : player) {
            // 背包格号按原版顺序：主背包 9-35 在前、快捷栏 0-8 在后，与槽位号一一对应。
            playerIds.add(27 + playerIds.size());
            playerIndices.add(playerIds.size() <= 27 ? 8 + playerIds.size() : playerIds.size() - 28);
            playerSnapshots.add(item == null ? SlotSnapshot.empty() : SlotSnapshot.of(item));
        }
        return new MenuContent.Reading(CHANNEL, SLOTS, boxIds, playerIds, playerIndices, boxSnapshots,
                playerSnapshots);
    }

    private static ItemStack iron(int count) {
        return new ItemStack(Items.IRON_INGOT, count);
    }

    private static ItemStack gold(int count) {
        return new ItemStack(Items.GOLD_BLOCK, count);
    }

    /** n 格长的格子数组，第 index 格放 item，其余空着。 */
    private static ItemStack[] slots(int length, int index, ItemStack item) {
        ItemStack[] slots = new ItemStack[length];
        slots[index] = item;
        return slots;
    }

    /** 只要铁锭：想要的东西按物品认。 */
    private static boolean wanted(SlotSnapshot snapshot) {
        return !snapshot.isEmpty() && snapshot.stack().is(Items.IRON_INGOT);
    }

    private static Optional<ContainerTakePlan.Move> plan(ItemStack[] box, ItemStack[] player, int needed) {
        return ContainerTakePlan.next(reading(box, player), ContainerTakePlanTest::wanted, needed);
    }

    @Test
    void 要的不少于整堆时整堆快速移动一笔() {
        ContainerTakePlan.Move move = plan(slots(27, BOX_SLOT, iron(30)), new ItemStack[36], 64).orElseThrow();
        assertEquals(30, move.amount(), "整堆都用得上，30 个整堆拿");
        assertEquals(1, move.clicks().size());
        assertTrue(move.clicks().getFirst().quickMove(), "整堆走快速移动，不拆堆");
    }

    @Test
    void 六十四个里拿五个_点完箱里剩五十九背包得五光标空() {
        ContainerTakePlan.Move move = plan(slots(27, BOX_SLOT, iron(64)), new ItemStack[36], 5).orElseThrow();
        assertEquals(5, move.amount());
        int[] after = clickThrough(move, BOX_SLOT, PLAYER_SLOT, 64);
        assertEquals(59, after[0], "箱子里剩的正是不要的量");
        assertEquals(5, after[1], "背包里进来的正好是要的量");
        assertEquals(0, after[2], "点完光标是空的");
    }

    @Test
    void 尾数并进背包里同种还没满的堆() {
        // 背包里已有 59 个铁锭（还放得下 5 个）：尾数叠到这堆上，不占新格子。
        ItemStack[] player = slots(36, 0, iron(59));
        ContainerTakePlan.Move move = plan(slots(27, BOX_SLOT, iron(64)), player, 5).orElseThrow();
        int giveTo = move.clicks().stream()
                .filter(click -> !click.quickMove() && !click.takesToCursor())
                .map(MenuClick::slotId)
                .findFirst().orElseThrow();
        assertEquals(PLAYER_SLOT, giveTo, "放进背包第一格的槽位号");
        int[] after = clickThrough(move, BOX_SLOT, giveTo, 64);
        assertEquals(5, after[1], "拿到的尾数 5 个并进那 59 个的堆，正好叠满");
        assertEquals(0, after[2]);
    }

    @Test
    void 背包放不下尾数时不给计划() {
        // 背包 36 格全被金块占着：拆 5 个铁锭没地方放，宁可不拿也不硬点整堆。
        ItemStack[] player = new ItemStack[36];
        for (int i = 0; i < player.length; i++) player[i] = gold(64);
        assertTrue(plan(slots(27, BOX_SLOT, iron(64)), player, 5).isEmpty());
    }

    @Test
    void 多格凑量_前面格子整堆拿_最后一格拿尾数() {
        ItemStack[] full = new ItemStack[27];
        full[1] = iron(3);
        full[9] = iron(4);
        // 第一笔：3 个那格整堆快速移动。
        ContainerTakePlan.Move first = plan(full, new ItemStack[36], 5).orElseThrow();
        assertEquals(1, first.containerSlotId());
        assertEquals(3, first.amount());
        assertTrue(first.clicks().getFirst().quickMove());
        // 第一笔搬走后的界面：还缺 2 个，从 4 个那格拆 2 个。
        ItemStack[] afterFirst = slots(27, 9, iron(4));
        ContainerTakePlan.Move second = plan(afterFirst, new ItemStack[36], 2).orElseThrow();
        assertEquals(9, second.containerSlotId());
        assertEquals(2, second.amount());
        int[] left = clickThrough(second, 9, PLAYER_SLOT, 4);
        assertEquals(2, left[0], "点完那格剩的正是不要的量");
        assertEquals(2, left[1], "要的 2 个进背包");
        assertEquals(0, left[2], "点完光标是空的");
    }

    @Test
    void 拿够了就没有下一笔() {
        assertTrue(plan(slots(27, BOX_SLOT, iron(64)), new ItemStack[36], 0).isEmpty(),
                "不缺了不再点下一格");
    }

    @Test
    void 不是想要东西的格子跳过() {
        ItemStack[] box = slots(27, 0, gold(64));
        box[4] = iron(10);
        ContainerTakePlan.Move move = plan(box, new ItemStack[36], 3).orElseThrow();
        assertEquals(4, move.containerSlotId(), "金块那格不碰，从铁锭那格拿");
    }

    @Test
    void 超叠一百二十八圆石拿八十个_分两轮按背包单格六十四上限搬入() {
        // 精妙背包一格装着 128 个圆石（光标与背包单格上限均为 64），要拿 80 个：
        // 第一轮先搬满背包第一格 64 个，第二轮从剩下的 64 个里拆 16 个放进背包第二格。
        ItemStack superCobble = new ItemStack(Items.COBBLESTONE);
        superCobble.setCount(128);
        ItemStack[] player = new ItemStack[36];
        ContainerTakePlan.Move first = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, superCobble), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.COBBLESTONE),
                80).orElseThrow();
        assertEquals(64, first.amount(), "第一轮按光标与背包单格上限搬 64 个");
        int[] afterFirst = clickThrough(first, BOX_SLOT, PLAYER_SLOT, 128, 64);
        assertEquals(64, afterFirst[0], "第一轮后超叠槽还剩 64 个圆石");
        assertEquals(64, afterFirst[1], "背包第一格收满 64 个圆石");
        assertEquals(0, afterFirst[2], "第一轮点完光标为空");

        player[0] = new ItemStack(Items.COBBLESTONE, 64);
        ItemStack remainingCobble = new ItemStack(Items.COBBLESTONE, afterFirst[0]);
        ContainerTakePlan.Move second = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, remainingCobble), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.COBBLESTONE),
                16).orElseThrow();
        assertEquals(16, second.amount(), "第二轮搬剩下的 16 个");
        int[] afterSecond = clickThrough(second, BOX_SLOT, PLAYER_SLOT + 1, 64, 64);
        assertEquals(48, afterSecond[0], "两轮后源格剩 48 个圆石");
        assertEquals(16, afterSecond[1], "背包第二格收到 16 个圆石");
        assertEquals(0, afterSecond[2], "第二轮点完光标为空");
    }

    @Test
    void 超叠一百二十八圆石整格取完一百二十八与取十六均不误发超额快移() {
        // 精妙背包对超叠槽点一次快速移动最多只搬 64 个：要取满 128 个时第一轮按 64 精确搬运，第二轮剩 64 个再整格快移；
        // 只取 16 个时右键按截断后半堆 32 抓取并退回 16 个，源格剩 112 个且光标为空。
        ItemStack superCobble = new ItemStack(Items.COBBLESTONE);
        superCobble.setCount(128);
        ItemStack[] player = new ItemStack[36];
        ContainerTakePlan.Move takeSixteen = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, superCobble), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.COBBLESTONE),
                16).orElseThrow();
        assertEquals(16, takeSixteen.amount());
        int[] afterSixteen = clickThrough(takeSixteen, BOX_SLOT, PLAYER_SLOT, 128, 64);
        assertEquals(112, afterSixteen[0], "128 圆石取 16 后源格剩 112 个");
        assertEquals(16, afterSixteen[1], "背包收到 16 个圆石");
        assertEquals(0, afterSixteen[2], "光标无残留");

        ContainerTakePlan.Move roundOne = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, superCobble), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.COBBLESTONE),
                128).orElseThrow();
        assertEquals(64, roundOne.amount(), "超叠 128 取 128 第一轮只报 64 个，不发期望 128 的单次快移");
        int[] afterRoundOne = clickThrough(roundOne, BOX_SLOT, PLAYER_SLOT, 128, 64);
        assertEquals(64, afterRoundOne[0]);
        assertEquals(64, afterRoundOne[1]);

        player[0] = new ItemStack(Items.COBBLESTONE, 64);
        ContainerTakePlan.Move roundTwo = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, new ItemStack(Items.COBBLESTONE, 64)), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.COBBLESTONE),
                64).orElseThrow();
        assertEquals(64, roundTwo.amount(), "第二轮源格已降至 64，整格快速移动收尾");
        assertEquals(List.of(new MenuClick(BOX_SLOT, true, 0, false)), roundTwo.clicks());
    }

    @Test
    void 超叠多把石镐拿两把与全取三把均按单把上限逐轮搬运() {
        // 精妙背包一格叠着 3 把石镐（maxStackSize=1），全取 3 把：前两轮各按光标上限搬 1 把，最后剩 1 把走快速移动收尾。
        ItemStack stackedPickaxes = new ItemStack(Items.STONE_PICKAXE);
        stackedPickaxes.setCount(3);
        ItemStack[] player = new ItemStack[36];
        ContainerTakePlan.Move first = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, stackedPickaxes), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.STONE_PICKAXE),
                3).orElseThrow();
        assertEquals(1, first.amount(), "3 把石镐取 3 把时第一轮只搬 1 把，不发期望搬 3 把的快速移动");
        int[] afterFirst = clickThrough(first, BOX_SLOT, PLAYER_SLOT, 3, 1);
        assertEquals(2, afterFirst[0]);
        assertEquals(1, afterFirst[1]);
        assertEquals(0, afterFirst[2]);

        player[0] = new ItemStack(Items.STONE_PICKAXE, 1);
        ItemStack twoLeft = new ItemStack(Items.STONE_PICKAXE);
        twoLeft.setCount(afterFirst[0]);
        ContainerTakePlan.Move second = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, twoLeft), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.STONE_PICKAXE),
                2).orElseThrow();
        assertEquals(1, second.amount(), "还剩 2 把超叠石镐时第二轮仍按 1 把搬运");
        int[] afterSecond = clickThrough(second, BOX_SLOT, PLAYER_SLOT + 1, 2, 1);
        assertEquals(1, afterSecond[0]);
        assertEquals(1, afterSecond[1]);
        assertEquals(0, afterSecond[2]);

        player[1] = new ItemStack(Items.STONE_PICKAXE, 1);
        ContainerTakePlan.Move third = ContainerTakePlan.next(
                reading(slots(27, BOX_SLOT, new ItemStack(Items.STONE_PICKAXE, 1)), player),
                snapshot -> !snapshot.isEmpty() && snapshot.stack().is(Items.STONE_PICKAXE),
                1).orElseThrow();
        assertEquals(1, third.amount(), "最后只剩 1 把非超叠石镐时一笔快速移动收尾");
        assertEquals(List.of(new MenuClick(BOX_SLOT, true, 0, false)), third.clicks());
    }

    // 按原版与超叠槽点击规矩把一笔点击在数字上走一遍，返回 {箱里剩的, 背包得到的, 光标}。
    private static int[] clickThrough(ContainerTakePlan.Move move, int sourceSlotId, int playerSlotId,
            int sourceCount) {
        return clickThrough(move, sourceSlotId, playerSlotId, sourceCount, 64);
    }

    private static int[] clickThrough(ContainerTakePlan.Move move, int sourceSlotId, int playerSlotId,
            int sourceCount, int cursorMax) {
        int source = sourceCount;
        int player = 0;
        int cursor = 0;
        for (MenuClick click : move.clicks()) {
            if (click.quickMove()) throw new IllegalStateException("尾数计划里不该有快速移动");
            boolean onSource = click.slotId() == sourceSlotId;
            boolean onPlayer = click.slotId() == playerSlotId;
            if (cursor == 0) {
                // 空光标从箱格拿：左键按光标上限抓整轮，右键先截断到光标上限再折半向上取整。
                int taken = click.button() == 0
                        ? Math.min(cursorMax, source)
                        : (Math.min(cursorMax, source) + 1) / 2;
                cursor = taken;
                source -= taken;
            } else if (click.button() == 0 && onPlayer) {
                // 左键整份放进背包格。
                player += cursor;
                cursor = 0;
            } else if (click.button() == 0 && onSource) {
                // 左键把光标余量整份退回源格（PUT_BACK_ALL）。
                source += cursor;
                cursor = 0;
            } else if (onPlayer) {
                cursor -= 1;
                player += 1;
            } else if (onSource) {
                // 右键退回箱格一个。
                cursor -= 1;
                source += 1;
            } else {
                throw new IllegalStateException("点击落在计划之外的格子上：" + click.slotId());
            }
        }
        return new int[] {source, player, cursor};
    }
}
