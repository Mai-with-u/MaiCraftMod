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
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 10 种 AE2 便携存储元件的目录与纯计算逻辑：覆盖物品 1k/4k/16k/64k/256k 与流体 1k/4k/16k/64k/256k。
 * 统一封装随身元件扫描、字节与种类折算剩余空槽、低电报错文案、以及将指定栏位元件安全换到主手（不潜行）的辅助方法。
 */
public final class PortableCellCatalog {

    /** 副手栏位编号约定（与物品设置、背包视图一致）。 */
    static final int OFFHAND_SLOT = 40;
    /** AE2 物品元件每字节可容纳 8 件物品，一组 64 件占用 8 字节。F 游戏事实。 */
    private static final long BYTES_PER_FULL_STACK = 8L;

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

    /** 把指定栏位的便携元件换到主手；已在主手时返回空（无需动作）。 */
    static Optional<Action> moveSlotToMainhand(PlayerServices services, LocalPlayer self, int slot, String itemId) {
        if (slot == self.getInventory().selected) return Optional.empty();
        if (slot >= 0 && slot <= 35) {
            return new ClientMovesToMainhand(services.context()).actionToMainhandFrom(slot, "把 " + itemId + " 拿到主手");
        }
        return services.toMainhand().moveToMainhand(itemId);
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
}
