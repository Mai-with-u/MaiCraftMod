// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.game.menu.CursorStowing;

/**
 * 从终端里怎么取：只拿需要的量，又不在一件一件上耗太久。纯函数，不读世界也不动手。
 *
 * <p>还缺一整组以上，就 Shift 点网络里那一条，一下一组直接进背包；不足一组，就一件一件取到光标上，
 * 凑够了再放进背包的一格。Shift 点一下就是一整组，不足一组时用它会多拿；一件一件取虽然慢，
 * 但不会把用不着的东西塞进背包。光标上的东西只在能整个放进某一格时才继续往上叠，免得关界面时放不下掉在地上。
 */
public final class NetworkTakePlan {

    private NetworkTakePlan() {}

    /** 下一步做什么。 */
    public sealed interface Move {
    }

    /** Shift 点这一条：最多一组直接进背包。 */
    public record TakeStack(long serial) implements Move {
    }

    /** 取一件到光标上。 */
    public record TakeOne(long serial) implements Move {
    }

    /** 把光标上的东西放进角色背包那一侧的这一格（界面里的槽位号）。 */
    public record PutDown(int slotId) implements Move {
    }

    /** 背包放不下了：网络里还有、也还缺，但没有地方装。 */
    public record NoRoom() implements Move {
    }

    /** 这一条取完了：够数了，或网络里这种东西没了。 */
    public record Done() implements Move {
    }

    /**
     * 网络存货里算想要的那几条，按先取哪条排：和物品默认样子一样的（没耐久损耗、没附魔、没改名）先取，
     * 再按存量从多到少；存量为零的不列。
     *
     * @param wanted 一种东西（看样子）算不算想要的
     */
    public static List<Ae2TerminalMenu.StockEntry> matching(List<Ae2TerminalMenu.StockEntry> stock,
            Predicate<ItemStack> wanted) {
        // 网络里现成的桶装水先拿，拿空桶去装流体排在后面。
        return stock.stream()
                .filter(entry -> entry.stored() > 0 && wanted.test(entry.sample()))
                .sorted(Comparator.comparing(Ae2TerminalMenu.StockEntry::fillsBucket)
                        .thenComparing((Ae2TerminalMenu.StockEntry entry) -> !plain(entry.sample()))
                        .thenComparing(Comparator.comparingLong(Ae2TerminalMenu.StockEntry::stored).reversed()))
                .toList();
    }

    /**
     * 对网络里的这一条，下一步做什么。
     *
     * @param remaining       还要多少件进背包（光标上的不算已经进了背包）
     * @param cursor          此刻光标上的东西；只会是这一条的东西或空
     * @param entry           网络里这一条此刻的样子
     * @param playerSlotIds   角色背包那一侧的槽位号
     * @param playerInventoryIndices 与 playerSlotIds 对齐的角色背包格号（快捷栏 0-8、主背包 9-35）
     * @param playerSnapshots 与槽位号对齐的此刻内容
     */
    public static Move next(int remaining, SlotSnapshot cursor, Ae2TerminalMenu.StockEntry entry,
            List<Integer> playerSlotIds, List<Integer> playerInventoryIndices, List<SlotSnapshot> playerSnapshots) {
        int stackSize = entry.sample().getMaxStackSize();
        int onCursor = cursor.isEmpty() ? 0 : cursor.count();
        if (onCursor > 0) {
            // 光标上已经有：够数、满一组、网络里没了、或再叠一件就没有一格放得下，都先放下。
            boolean enough = onCursor >= remaining || onCursor >= stackSize || entry.stored() <= 0;
            boolean roomForOneMore = stowSlot(entry.sample().copyWithCount(onCursor + 1),
                    playerSlotIds, playerInventoryIndices, playerSnapshots) >= 0;
            if (enough || !roomForOneMore) {
                int slot = stowSlot(entry.sample().copyWithCount(onCursor),
                        playerSlotIds, playerInventoryIndices, playerSnapshots);
                return slot >= 0 ? new PutDown(slot) : new NoRoom();
            }
            return new TakeOne(entry.serial());
        }
        if (remaining <= 0 || entry.stored() <= 0) {
            return new Done();
        }
        // 整组：Shift 点进哪一格由游戏挑，能叠进没满的同种堆也行，所以只要主背包里还有一处能放下一件就点。
        if (remaining >= stackSize) {
            return stowSlot(entry.sample().copyWithCount(1), playerSlotIds, playerInventoryIndices, playerSnapshots) >= 0
                    ? new TakeStack(entry.serial()) : new NoRoom();
        }
        return stowSlot(entry.sample().copyWithCount(1), playerSlotIds, playerInventoryIndices, playerSnapshots) >= 0
                ? new TakeOne(entry.serial()) : new NoRoom();
    }

    /**
     * 只有一份角色侧读数、拿不到原界面，挑选走统一的收进背包：先叠同种，再主背包空格，
     * 不占快捷栏空格；装不下给 -1。
     */
    private static int stowSlot(ItemStack stack, List<Integer> playerSlotIds,
            List<Integer> playerInventoryIndices, List<SlotSnapshot> playerSnapshots) {
        return CursorStowing.fromSnapshots(playerSlotIds, playerInventoryIndices,
                playerSnapshots.stream().map(SlotSnapshot::stack).toList(), stack);
    }

    /**
     * 角色背包那一侧这次还能装下几件这种东西：空格按一组算，放着同一种、没满的格子补到满。
     * 在网络里下单合成前用它定件数，做出来拿得走多少就下多少。
     */
    static int room(ItemStack sample, List<SlotSnapshot> playerSnapshots) {
        SlotSnapshot kind = SlotSnapshot.of(sample.copyWithCount(1));
        int stackSize = sample.getMaxStackSize();
        int room = 0;
        for (SlotSnapshot slot : playerSnapshots) {
            if (slot.isEmpty()) {
                room += stackSize;
            } else if (slot.sameIdentity(kind)) {
                room += Math.max(0, slot.stack().getMaxStackSize() - slot.count());
            }
        }
        return room;
    }

    // 和这种物品刚做出来时一个样：组件与默认的一致。
    private static boolean plain(ItemStack sample) {
        return ItemStack.isSameItemSameComponents(sample, new ItemStack(sample.getItem()));
    }
}
