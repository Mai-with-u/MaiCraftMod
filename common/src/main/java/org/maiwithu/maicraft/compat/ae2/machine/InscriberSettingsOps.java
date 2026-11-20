// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 压印器（ae2:inscriber）设置补全委托：
 * 不覆盖包根既有的四工作格状态读数与投料收货流程，只补齐 separate_sides、auto_export、
 * input_capacity（1 / 4 / 64）三项配置按钮以及 upgrade.0..3 四个加速卡槽与开窗缓存读数。
 */
final class InscriberSettingsOps implements Ae2DeviceOps {

    static final int UPGRADE_SLOTS = 4;
    static final List<String> YES_NO = List.of("yes", "no");
    static final List<String> CAPACITIES = List.of("1", "4", "64");
    private static final List<String> UPGRADE_CHOICES = List.of("ae2:speed_card", "empty");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    InscriberSettingsOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        Optional<Ae2MachineReads.InscriberMenuView> cached = cache.inscriber(pos);
        if (cached.isEmpty()) return Map.of();
        Ae2MachineReads.InscriberMenuView view = cached.get();
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("separate_sides", view.separateSides());
        readings.put("auto_export", view.autoExport());
        readings.put("input_capacity", view.inputCapacity());
        readings.put("processing_time", (long) view.processingTime());
        readings.put("max_processing_time", (long) view.maxProcessingTime());
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            String card = i < view.upgrades().size() ? view.upgrades().get(i) : "empty";
            readings.put("upgrade." + i, card);
        }
        return readings;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                return inscriberSettings(cache.inscriber(at));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return changeSetting(at.immutable(), key, value, permissions, services);
            }
        });
    }

    static List<MachineSetting> inscriberSettings(Optional<Ae2MachineReads.InscriberMenuView> cached) {
        List<MachineSetting> list = new ArrayList<>(3 + UPGRADE_SLOTS);
        list.add(new MachineSetting("separate_sides",
                cached.map(Ae2MachineReads.InscriberMenuView::separateSides).orElse("unknown"), YES_NO,
                "分离面输入：yes 时顶/底/四周分槽接入，no 时任意面互通"));
        list.add(new MachineSetting("auto_export",
                cached.map(Ae2MachineReads.InscriberMenuView::autoExport).orElse("unknown"), YES_NO,
                "自动输出：yes 时压印完成自动推向相邻容器（分离面时不推顶/底）"));
        list.add(new MachineSetting("input_capacity",
                cached.map(Ae2MachineReads.InscriberMenuView::inputCapacity).orElse("unknown"), CAPACITIES,
                "单槽输入堆叠上限：1 / 4 / 64"));
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            int idx = i;
            String card = cached.map(v -> idx < v.upgrades().size() ? v.upgrades().get(idx) : "empty")
                    .orElse("unknown");
            list.add(new MachineSetting("upgrade." + i, card, UPGRADE_CHOICES,
                    "压印器第 " + i + " 号升级槽（最多 4 张 ae2:speed_card）"));
        }
        return List.copyOf(list);
    }

    private Optional<Action> changeSetting(BlockPos target, String key, String value,
            Permissions permissions, PlayerServices services) {
        if ("separate_sides".equals(key)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(new Ae2MenuActionSupport.ConfigCycleAction(
                    Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                    compat, "inscriber_separate_sides", normalized, 3,
                    () -> reads.openInscriberMenu().map(Ae2MachineReads.InscriberMenuView::separateSides),
                    () -> reads.openInscriberMenu().ifPresent(v -> cache.putInscriber(target, v)),
                    "压印器分离面"));
        }
        if ("auto_export".equals(key)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(new Ae2MenuActionSupport.ConfigCycleAction(
                    Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                    compat, "auto_export", normalized, 3,
                    () -> reads.openInscriberMenu().map(Ae2MachineReads.InscriberMenuView::autoExport),
                    () -> reads.openInscriberMenu().ifPresent(v -> cache.putInscriber(target, v)),
                    "压印器自动输出"));
        }
        if ("input_capacity".equals(key)) {
            String normalized = value.trim();
            if (!CAPACITIES.contains(normalized)) return Optional.empty();
            return Optional.of(new Ae2MenuActionSupport.ConfigCycleAction(
                    Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                    compat, "inscriber_input_capacity", normalized, 4,
                    () -> reads.openInscriberMenu().map(Ae2MachineReads.InscriberMenuView::inputCapacity),
                    () -> reads.openInscriberMenu().ifPresent(v -> cache.putInscriber(target, v)),
                    "压印器输入容量"));
        }
        int slot = VibrationChamberOps.parseUpgradeSlot(key, UPGRADE_SLOTS);
        if (slot < 0) return Optional.empty();
        boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
        if (!remove && !"ae2:speed_card".equals(value)) return Optional.empty();
        return Optional.of(new Ae2MenuActionSupport.SemanticSlotSwapAction(
                Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                compat, "UPGRADE", slot,
                remove ? null : (snap, ctx) -> "ae2:speed_card".equals(snap.key()),
                remove ? "空位" : "ae2:speed_card",
                () -> reads.openInscriberMenu().ifPresent(v -> cache.putInscriber(target, v)),
                "压印器升级槽 " + slot));
    }
}
