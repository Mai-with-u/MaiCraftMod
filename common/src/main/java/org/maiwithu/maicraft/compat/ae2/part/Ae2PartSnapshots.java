// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 将打开着的 AE2 菜单快照提炼成统一的配置缓存与补充读数，纯函数。
 * 供配置动作在关闭菜单前一次性刷新本地缓存，确保配置任务紧接其后的同步读回立即命中。
 */
final class Ae2PartSnapshots {

    private Ae2PartSnapshots() {
    }

    /** 把菜单快照里可见的设置、槽位与附加读数刷入缓存，必要时附带本次刚改好的键值。 */
    static void flushToCache(Ae2PartCache cache, BlockPos pos, Direction side, Ae2PartControl.OpenMenuSnapshot snapshot,
                             String appliedKey, String appliedValue) {
        if (cache == null || pos == null || snapshot == null) return;
        Map<String, String> settings = extractSettings(snapshot);
        Map<String, Object> readings = extractReadings(snapshot);
        if (appliedKey != null && appliedValue != null) {
            if (appliedKey.equals("action")) {
                cache.recordActionApplied(pos, side, appliedValue);
            } else {
                settings.put(appliedKey, appliedValue);
            }
        }
        cache.recordLocal(pos, side, settings, readings);
    }

    /** 从打开菜单的快照里抽取所有当前可见的配置键值。 */
    static Map<String, String> extractSettings(Ae2PartControl.OpenMenuSnapshot snapshot) {
        Map<String, String> out = new LinkedHashMap<>(snapshot.settings());
        snapshot.keyTypes().forEach((type, enabled) -> out.put("key_type." + type, Boolean.toString(enabled)));
        snapshot.reportingValue().ifPresent(val -> out.put("reporting_value", Long.toString(val)));
        snapshot.priority().ifPresent(val -> out.put("priority", Integer.toString(val)));
        snapshot.wirelessBoosters().ifPresent(val -> out.put("boosters", Integer.toString(val)));
        boolean isInterface = snapshot.menuTypeId().equals(Ae2PartKeys.MENU_INTERFACE);
        for (Ae2PartControl.MenuSlotEntry slot : snapshot.configSlots()) {
            String prefix = isInterface ? "stock." : "filter.";
            out.put(prefix + slot.localIndex(), formatConfigSlot(slot, isInterface));
        }
        for (Ae2PartControl.MenuSlotEntry slot : snapshot.upgradeSlots()) {
            out.put("upgrade." + slot.localIndex(), slot.isEmpty() ? Ae2PartKeys.EMPTY : slot.itemId());
        }
        if (!snapshot.cellSlots().isEmpty()) {
            Ae2PartControl.MenuSlotEntry cell = snapshot.cellSlots().getFirst();
            out.put("cell", cell.isEmpty() ? "out" : "in:" + cell.itemId());
        }
        return out;
    }

    /** 从打开菜单的快照里抽取需要透出给查看机器的补充读数。 */
    static Map<String, Object> extractReadings(Ae2PartControl.OpenMenuSnapshot snapshot) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (snapshot.connectedTo() != null && !snapshot.connectedTo().isBlank()) {
            out.put("connected_to", snapshot.connectedTo());
        }
        snapshot.reportingValue().ifPresent(val -> out.put("reporting_value", val));
        snapshot.priority().ifPresent(val -> out.put("priority", val));
        snapshot.wirelessBoosters().ifPresent(val -> out.put("boosters", val));
        if (snapshot.menuTypeId().equals(Ae2PartKeys.MENU_CELL_TRANSFER)) {
            out.put("has_redstone_card", snapshot.hasRedstoneCard());
        }
        if (!snapshot.upgradeSlots().isEmpty()) {
            long active = snapshot.upgradeSlots().stream().filter(Ae2PartControl.MenuSlotEntry::enabled).count();
            out.put("active_upgrade_slots", active);
        }
        if (!snapshot.cellSlots().isEmpty()) {
            Ae2PartControl.MenuSlotEntry cell = snapshot.cellSlots().getFirst();
            out.put("cell", cell.isEmpty() ? Ae2PartKeys.EMPTY : cell.itemId());
        }
        return out;
    }

    /** 按局部序号查找指定语义槽位记录。 */
    static Optional<Ae2PartControl.MenuSlotEntry> slotAt(List<Ae2PartControl.MenuSlotEntry> slots, int localIndex) {
        for (Ae2PartControl.MenuSlotEntry entry : slots) {
            if (entry.localIndex() == localIndex) return Optional.of(entry);
        }
        return Optional.empty();
    }

    /** 检查某过滤槽或备料槽目标是否已匹配目标 ID（支持 {@code chemical:<id>} 显式核对化学品键类别）。 */
    static boolean matchesFilterTarget(Ae2PartControl.MenuSlotEntry slot, String wantedTarget) {
        if (slot == null || slot.isEmpty() || wantedTarget == null || wantedTarget.isBlank()) return false;
        String trimmed = wantedTarget.trim();
        if (trimmed.regionMatches(true, 0, Ae2PartKeys.CHEMICAL_PREFIX, 0, Ae2PartKeys.CHEMICAL_PREFIX.length())) {
            String bareId = trimmed.substring(Ae2PartKeys.CHEMICAL_PREFIX.length()).trim();
            boolean typeOk = slot.keyType().isEmpty() || "chemical".equalsIgnoreCase(slot.keyType());
            return typeOk && bareId.equals(slot.itemId());
        }
        return trimmed.equals(slot.itemId());
    }

    /** 检查某备料槽内容是否已经满足目标备料规格。 */
    static boolean matchesStock(Ae2PartControl.MenuSlotEntry slot, Ae2PartKeys.StockValue wanted) {
        if (wanted.empty()) return slot.isEmpty();
        if (slot.isEmpty() || !matchesFilterTarget(slot, wanted.targetId())) return false;
        return wanted.amount() == null || slot.count() == wanted.amount();
    }

    private static String formatConfigSlot(Ae2PartControl.MenuSlotEntry slot, boolean includeAmount) {
        if (slot.isEmpty()) return Ae2PartKeys.EMPTY;
        // 化学品槽在读回缓存时标明 chemical: 前缀，避免与同名流体产生单位与介质歧义。
        String target = "chemical".equalsIgnoreCase(slot.keyType())
                ? Ae2PartKeys.CHEMICAL_PREFIX + slot.itemId() : slot.itemId();
        if (!includeAmount) return target;
        return target + " " + Math.max(1L, slot.count());
    }
}
