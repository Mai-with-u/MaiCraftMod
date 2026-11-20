// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 10 种 AE2 便携存储元件的目录与纯计算逻辑：覆盖物品 1k/4k/16k/64k/256k 与流体 1k/4k/16k/64k/256k。
 * 统一封装随身元件扫描、字节与种类折算剩余空槽、低电报错文案、以及将指定栏位元件安全换到主手（不潜行）的辅助方法。
 */
public final class PortableCellCatalog {

    /** 副手栏位编号约定（与物品设置、背包视图一致）。 */
    static final int OFFHAND_SLOT = 40;
    /** 打开便携存储元件前必须连续保持非潜行的最短刻数，确保服务端同步解除潜行防止空元件触发 disassembleDrive 拆解。 */
    static final int UNSNEAK_STABLE_TICKS = 2;
    /** AE2 物品元件每字节可容纳 8 件物品，一组 64 件占用 8 字节。F 游戏事实。 */
    private static final long BYTES_PER_FULL_STACK = 8L;
    private static final int OFFHAND_SWAP_TIMEOUT_TICKS = 60;

    /** 5 档物品便携存储元件的物品注册 ID。 */
    public static final Set<String> ITEM_CELL_IDS = Set.of(
            "ae2:portable_item_cell_1k",
            "ae2:portable_item_cell_4k",
            "ae2:portable_item_cell_16k",
            "ae2:portable_item_cell_64k",
            "ae2:portable_item_cell_256k");

    /** 5 档流体便携存储元件的物品注册 ID。 */
    public static final Set<String> FLUID_CELL_IDS = Set.of(
            "ae2:portable_fluid_cell_1k",
            "ae2:portable_fluid_cell_4k",
            "ae2:portable_fluid_cell_16k",
            "ae2:portable_fluid_cell_64k",
            "ae2:portable_fluid_cell_256k");

    /** 全部 10 种便携存储元件的物品注册 ID。 */
    public static final Set<String> ALL_CELL_IDS = Set.of(
            "ae2:portable_item_cell_1k",
            "ae2:portable_item_cell_4k",
            "ae2:portable_item_cell_16k",
            "ae2:portable_item_cell_64k",
            "ae2:portable_item_cell_256k",
            "ae2:portable_fluid_cell_1k",
            "ae2:portable_fluid_cell_4k",
            "ae2:portable_fluid_cell_16k",
            "ae2:portable_fluid_cell_64k",
            "ae2:portable_fluid_cell_256k");

    private PortableCellCatalog() {
    }

    /** 角色身上某一只便携存储元件的栏位与客户端直读快照。 */
    public record CarriedCell(int slot, String where, ItemStack stack, Ae2ToolReads.PortableCellSnapshot snapshot) {
        public CarriedCell {
            Objects.requireNonNull(where, "where");
            Objects.requireNonNull(stack, "stack");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** 判断某物品 ID 是否属于 10 种便携存储元件。 */
    public static boolean isPortableCellId(String itemId) {
        return itemId != null && ALL_CELL_IDS.contains(itemId);
    }

    /** 扫描角色快捷栏、主背包与副手中携带的全部便携存储元件，经客户端同步组件免开界面直读快照。 */
    public static List<CarriedCell> scanCarried(Ae2Compat compat, Ae2ToolReads reads, Player player) {
        if (player == null) return List.of();
        List<CarriedCell> out = new ArrayList<>();
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty()) continue;
            int index = slot;
            String where = index < 9 ? "hotbar" : "inventory";
            readCell(compat, reads, stack).ifPresent(snapshot -> out.add(
                    new CarriedCell(index, where, stack, snapshot)));
        }
        ItemStack offhand = player.getOffhandItem();
        if (!offhand.isEmpty()) {
            readCell(compat, reads, offhand).ifPresent(snapshot -> out.add(
                    new CarriedCell(OFFHAND_SLOT, "offhand", offhand, snapshot)));
        }
        return List.copyOf(out);
    }

    /**
     * 按剩余种类与剩余字节折算可用于腾背包的空槽数（纯函数）。
     * 流体便携元件不参与腾背包（报 0），电量耗尽报 0，种类已满或剩余字节不足一个新种类报 0。
     */
    public static int freeSlotsOf(Ae2ToolReads.PortableCellSnapshot snapshot) {
        if (snapshot == null || snapshot.fluidCell() || snapshot.currentEnergy() <= 0.0) {
            return 0;
        }
        int remainingTypes = Math.max(0, snapshot.totalTypes() - snapshot.storedTypes());
        long remainingBytes = Math.max(0L, snapshot.totalBytes() - snapshot.usedBytes());
        if (remainingTypes <= 0 || remainingBytes <= snapshot.bytesPerType()) {
            return 0;
        }
        long bytesPerNewStack = Math.max(1L, snapshot.bytesPerType() + BYTES_PER_FULL_STACK);
        long estimated = Math.max(1L, remainingBytes / bytesPerNewStack);
        return (int) Math.min(remainingTypes, Math.min(Integer.MAX_VALUE, estimated));
    }

    /** 判断这只物品便携元件此刻是否有电且有空间收下指定物品（已有同种物品只需剩余字节，新种类还需剩余种类与类型字节）。 */
    public static boolean canAcceptItem(Ae2ToolReads.PortableCellSnapshot snapshot, String itemId) {
        if (snapshot == null || snapshot.fluidCell() || snapshot.currentEnergy() <= 0.0) return false;
        long remainingBytes = Math.max(0L, snapshot.totalBytes() - snapshot.usedBytes());
        if (remainingBytes <= 0L) return false;
        boolean alreadyPresent = snapshot.entries().stream()
                .anyMatch(entry -> !entry.fillsBucket() && entry.itemId().equals(itemId));
        if (alreadyPresent) return true;
        return snapshot.storedTypes() < snapshot.totalTypes() && remainingBytes > snapshot.bytesPerType();
    }

    /** 将元件内同步库存汇总为观察用的「物品 ID → 件数」映射（流体按毫桶原 ID 标出，避免与物品混数）。 */
    public static Map<String, Integer> summarizeContents(Ae2ToolReads.PortableCellSnapshot snapshot) {
        Map<String, Integer> summary = new LinkedHashMap<>();
        for (Ae2ToolReads.CellStockEntry entry : snapshot.entries()) {
            long value = entry.fillsBucket() ? entry.rawAmount() : entry.count();
            if (value <= 0L) continue;
            String key = entry.fillsBucket() ? entry.rawId() : entry.itemId();
            int clamped = (int) Math.min(Integer.MAX_VALUE, value);
            summary.merge(key, clamped, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
        }
        return Map.copyOf(summary);
    }

    /** 构造统一的便携元件电量不足提示（写明当前电量/上限电量并建议通过充能器 machine_run 补电）。 */
    public static String lowEnergyMessage(Ae2ToolReads.PortableCellSnapshot snapshot) {
        return "便携存储元件 " + snapshot.itemId() + " 电量不足（" + formatAe(snapshot.currentEnergy())
                + "/" + formatAe(snapshot.maxEnergy()) + " AE），请用充能器（ae2:charger）执行 machine_run 补电";
    }

    /**
     * 把指定栏位的物品精准换到主手；已在主手时返回空（无需动作）。
     * 0~35 走精确槽位切换；40（副手）直接走原生副手交换（MenuActions.swapInventoryToOffhand），
     * 避免按物品 ID 回退时错拿背包里另一只同名元件或空网络工具。
     */
    static Optional<Action> moveSlotToMainhand(PlayerServices services, LocalPlayer self, int slot, String itemId) {
        if (slot == self.getInventory().selected) return Optional.empty();
        if (slot >= 0 && slot <= 35) {
            return new ClientMovesToMainhand(services.context()).actionToMainhandFrom(slot, "把 " + itemId + " 拿到主手");
        }
        if (slot == OFFHAND_SLOT) {
            return Optional.of(new OffhandSwapToMainhand(itemId));
        }
        return services.toMainhand().moveToMainhand(itemId);
    }

    /** 推进防误拆的非潜行稳定计数：仍在潜行时归零，非潜行时累加至 UNSNEAK_STABLE_TICKS（纯函数）。 */
    static int advanceUnsneakTicks(boolean shiftKeyDown, int stableTicks) {
        return shiftKeyDown ? 0 : Math.min(UNSNEAK_STABLE_TICKS, Math.max(0, stableTicks) + 1);
    }

    /** 判断是否已连续保持足够刻数非潜行，可安全普通右键打开便携存储元件（纯函数）。 */
    static boolean isUnsneakStable(boolean shiftKeyDown, int stableTicks) {
        return !shiftKeyDown && stableTicks >= UNSNEAK_STABLE_TICKS;
    }

    /** 误开非预期容器界面时立即关闭当前容器，避免错误界面滞留屏幕卡住后续动作。 */
    static void closeUnexpectedMenu(LocalPlayer self) {
        if (self != null && self.containerMenu != null && self.containerMenu != self.inventoryMenu) {
            self.closeContainer();
        }
    }

    /** 格式化 AE 电量数值（整数去小数尾零，保留一位精度）。 */
    public static String formatAe(double value) {
        double safe = Math.max(0.0, value);
        if (Math.abs(safe - Math.rint(safe)) < 0.05) {
            return Long.toString(Math.round(safe));
        }
        return String.format(Locale.ROOT, "%.1f", safe);
    }

    static String idOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Optional<Ae2ToolReads.PortableCellSnapshot> readCell(Ae2Compat compat, Ae2ToolReads reads,
            ItemStack stack) {
        return compat.call("读取便携存储元件状态", () -> reads.readPortableCell(stack));
    }

    /** 直接将副手（槽 40）与当前选中的主手槽原生对调，保证拿到副手里那一只目标物品而不错拿背包同名物品。 */
    private static final class OffhandSwapToMainhand implements Action {

        private final String itemId;
        private PendingMenuAction pending;
        private int stalledTicks;

        OffhandSwapToMainhand(String itemId) {
            this.itemId = itemId;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            MenuActions actions = player.menuActions();
            if (pending == null) {
                if (player.localPlayer().containerMenu != player.localPlayer().inventoryMenu) {
                    if (!actions.ensureWorldVisible(player)) {
                        return failIfStalled("旧界面未能关闭，无法交换副手物品到主手");
                    }
                }
                try {
                    pending = actions.swapInventoryToOffhand(player, OFFHAND_SLOT, OFFHAND_SWAP_TIMEOUT_TICKS);
                    stalledTicks = 0;
                } catch (IllegalStateException busy) {
                    return failIfStalled("画面被占用，副手交换一直无法提交");
                }
                return ActionStatus.running();
            }
            if (!pending.terminal()) {
                pending = actions.poll(player, pending);
                return ActionStatus.running();
            }
            if (pending.status() != PendingMenuAction.Status.CONFIRMED_APPLIED) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "把副手 " + itemId + " 换到主手未获确认：" + pending.detail()));
            }
            return ActionStatus.done();
        }

        private ActionStatus failIfStalled(String message) {
            if (++stalledTicks > OFFHAND_SWAP_TIMEOUT_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, message));
            }
            return ActionStatus.running();
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "把副手 " + itemId + " 交换到主手";
        }
    }
}
