// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 振动室（ae2:vibration_chamber）的设备操作委托：
 * 客户端同步读取 active 燃烧状态，打开界面后缓存燃料、燃烧百分比、发电速率与 3 个升级槽；
 * 运行经 FuelUse 接受任意可燃燃料并在空桶阻塞燃料格时明确点名占位物；配置支持 upgrade.0..2。
 */
final class VibrationChamberOps implements Ae2DeviceOps {

    static final int UPGRADE_SLOTS = 3;
    static final String FUEL_NOTE = "接受任意可燃烧物品（如煤炭、木炭、木板）；岩浆桶烧完会留空桶阻塞燃料格";
    private static final List<String> UPGRADE_CHOICES = List.of("ae2:energy_card", "ae2:speed_card", "empty");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    VibrationChamberOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到振动室"));
        Optional<Ae2MachineReads.VibrationView> live = reads.vibrationAt(level, pos);
        if (live.isEmpty()) return Optional.empty();
        return Optional.of(buildState(live.get(), cache.vibration(pos)));
    }

    /** 纯函数：组装振动室客户端状态与开窗缓存补读字段。 */
    static MachineState buildState(Ae2MachineReads.VibrationView live,
            Optional<Ae2MachineReads.VibrationMenuView> cached) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("active", live.active());
        List<MachineState.Shown> shown = new ArrayList<>();
        Double progress = null;
        String note;
        if (cached.isPresent()) {
            Ae2MachineReads.VibrationMenuView menu = cached.get();
            readings.put("remaining_burn_percent", (long) menu.remainingBurnPercent());
            readings.put("power_per_tick", menu.powerPerTick());
            readings.put("fuel_efficiency", menu.fuelEfficiency());
            if (menu.fuelItemId() != null && !menu.fuelItemId().isEmpty() && menu.fuelCount() > 0) {
                readings.put("fuel_slot", menu.fuelItemId() + " ×" + menu.fuelCount());
                shown.add(new MachineState.Shown(menu.fuelItemId(), menu.fuelCount()));
            } else {
                readings.put("fuel_slot", "empty");
            }
            for (int i = 0; i < UPGRADE_SLOTS; i++) {
                String card = i < menu.upgrades().size() ? menu.upgrades().get(i) : "empty";
                readings.put("upgrade." + i, card);
            }
            progress = Math.max(0.0, Math.min(1.0, menu.remainingBurnPercent() / 100.0));
            note = "燃料、功率与升级槽读数来自最近一次打开界面";
        } else {
            readings.put("fuel_slot", "unknown");
            note = "客户端仅同步燃烧状态；燃料、发电功率与升级卡须打开界面或经服务端 ae2.device 补读";
        }
        MachineState.Activity activity = live.active() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, progress, List.copyOf(shown), readings, note);
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public FuelUse fuelUse(BlockPos at) {
                return FuelUse.fuel(FUEL_NOTE);
            }

            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                BlockPos target = at.immutable();
                return Optional.of(new MenuFeed(services, "振动室", target, "ae2:vibration_chamber",
                        item.item(), item.count(), permissions, null,
                        new MenuFeed.Hooks(
                                () -> reads.openVibrationMenu().ifPresent(v -> cache.putVibration(target, v)),
                                () -> reads.openVibrationMenu().ifPresent(v -> cache.putVibration(target, v)),
                                problem -> enrichFuelFailure(target, item.item(), problem))));
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                return Optional.empty();
            }
        });
    }

    // 投燃料失败时检查燃料格是否被残留空桶或其他不可燃异物占据，明确点名占位物以便 LLM 清理。
    Problem enrichFuelFailure(BlockPos pos, String itemId, Problem base) {
        Optional<Ae2MachineReads.VibrationMenuView> latest = reads.openVibrationMenu()
                .or(() -> cache.vibration(pos));
        if (latest.isPresent()) {
            cache.putVibration(pos, latest.get());
            String occupier = latest.get().fuelItemId();
            if (occupier != null && !occupier.isEmpty() && latest.get().fuelCount() > 0 && !occupier.equals(itemId)) {
                String bucketHint = "minecraft:bucket".equals(occupier)
                        ? "（岩浆桶烧完留下的空桶正在阻塞燃料格）" : "（槽内异物正在阻塞燃料格）";
                return Problem.of(base.kind(),
                        "振动室燃料格被 " + occupier + " ×" + latest.get().fuelCount() + " 占据" + bucketHint
                                + "，无法放入 " + itemId + "：" + base.headline(),
                        "先取出燃料格中的 " + occupier + " 再投燃料");
            }
        }
        return base;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                return upgradeSettings(cache.vibration(at));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                int slot = parseUpgradeSlot(key, UPGRADE_SLOTS);
                if (slot < 0) return Optional.empty();
                boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
                if (!remove && !UPGRADE_CHOICES.contains(value)) return Optional.empty();
                BlockPos target = at.immutable();
                return Optional.of(new Ae2MenuActionSupport.SemanticSlotSwapAction(
                        Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                        compat, "UPGRADE", slot,
                        remove ? null : (snap, ctx) -> value.equals(snap.key()),
                        remove ? "空位" : value,
                        () -> reads.openVibrationMenu().ifPresent(v -> cache.putVibration(target, v)),
                        "振动室升级槽 " + slot));
            }
        });
    }

    static List<MachineSetting> upgradeSettings(Optional<Ae2MachineReads.VibrationMenuView> cached) {
        List<MachineSetting> list = new ArrayList<>(UPGRADE_SLOTS);
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            int idx = i;
            String current = cached.map(v -> idx < v.upgrades().size() ? v.upgrades().get(idx) : "empty")
                    .orElse("unknown");
            list.add(new MachineSetting("upgrade." + i, current, UPGRADE_CHOICES,
                    "振动室第 " + i + " 号升级槽：ae2:energy_card 提高单燃料发电量，ae2:speed_card 提高最大功率，empty 取出"));
        }
        return List.copyOf(list);
    }

    static int parseUpgradeSlot(String key, int maxSlots) {
        if (key == null || !key.startsWith("upgrade.")) return -1;
        try {
            int index = Integer.parseInt(key.substring("upgrade.".length()));
            return index >= 0 && index < maxSlots ? index : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
