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
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.inventory.containers.MenuFeed;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOutputLook;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 分子组装室（ae2:molecular_assembler）的设备操作委托：
 * 客户端同步读 powered，开窗缓存补齐 craft_progress（0..100）、5 个加速卡槽与单机样板占用警告
 * （放了单机样板后相邻样板供应器不再向其推送合成作业）；
 * 配置支持 pattern=&lt;output_item_id&gt;|empty 与 upgrade.0..4=ae2:speed_card|empty；
 * 单机模式下支持通过 MenuFeed 投料与 MenuOutputLook 收产物。
 */
final class MolecularAssemblerOps implements Ae2DeviceOps {

    static final int UPGRADE_SLOTS = 5;
    static final String STANDALONE_PATTERN_WARNING =
            "已放入单机样板，相邻样板供应器不再向其推送合成作业";
    private static final List<String> SPEED_CARD_CHOICES = List.of("ae2:speed_card", "empty");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    MolecularAssemblerOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到分子组装室"));
        Optional<Ae2MachineReads.AssemblerView> live = reads.molecularAssemblerAt(level, pos);
        if (live.isEmpty()) return Optional.empty();
        return Optional.of(buildState(live.get(), cache.assembler(pos)));
    }

    /** 纯函数：组装分子组装室状态，单机样板占用时显式输出警告与加速卡统计。 */
    static MachineState buildState(Ae2MachineReads.AssemblerView live,
            Optional<Ae2MachineReads.AssemblerMenuView> cached) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("powered", live.powered());
        Double progress = null;
        StringBuilder note = new StringBuilder();
        if (cached.isPresent()) {
            Ae2MachineReads.AssemblerMenuView menu = cached.get();
            readings.put("craft_progress", (long) menu.craftProgress());
            long speedCards = menu.upgrades().stream().filter("ae2:speed_card"::equals).count();
            readings.put("speed_cards", speedCards);
            for (int i = 0; i < UPGRADE_SLOTS; i++) {
                String card = i < menu.upgrades().size() ? menu.upgrades().get(i) : "empty";
                readings.put("upgrade." + i, card);
            }
            if (menu.hasPattern()) {
                String output = menu.patternOutput() == null || menu.patternOutput().isEmpty()
                        ? "encoded_pattern" : menu.patternOutput();
                readings.put("standalone_pattern", output);
                readings.put("accepts_provider_plans", false);
                note.append(STANDALONE_PATTERN_WARNING);
            } else {
                readings.put("standalone_pattern", "empty");
                readings.put("accepts_provider_plans", true);
            }
            progress = Math.max(0.0, Math.min(1.0, menu.craftProgress() / 100.0));
        } else {
            readings.put("craft_progress", "unknown");
            readings.put("standalone_pattern", "unknown");
            note.append("进度、5 个加速卡槽与单机样板须打开界面或经服务端 ae2.device 补读");
        }
        MachineState.Activity activity = !live.powered() ? MachineState.Activity.OFFLINE
                : progress != null && progress > 0 ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, progress, List.of(), readings, note.toString());
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                Optional<Ae2MachineReads.AssemblerMenuView> cached = cache.assembler(at);
                List<MachineSetting> list = new ArrayList<>(UPGRADE_SLOTS + 1);
                String patValue = cached.map(v -> v.hasPattern()
                        ? (v.patternOutput() == null || v.patternOutput().isEmpty() ? "encoded_pattern" : v.patternOutput())
                        : "empty").orElse("unknown");
                list.add(new MachineSetting("pattern", patValue, List.of("<output_item_id>", "empty"),
                        "单机样板槽：放入产出对应物品的编码样板进入单机模式（会屏蔽相邻样板供应器），empty 取出"));
                for (int i = 0; i < UPGRADE_SLOTS; i++) {
                    int idx = i;
                    String card = cached.map(v -> idx < v.upgrades().size() ? v.upgrades().get(idx) : "empty")
                            .orElse("unknown");
                    list.add(new MachineSetting("upgrade." + i, card, SPEED_CARD_CHOICES,
                            "分子组装室第 " + i + " 号加速卡槽（最多 5 张 ae2:speed_card）"));
                }
                return List.copyOf(list);
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                BlockPos target = at.immutable();
                if ("pattern".equals(key)) {
                    boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
                    return Optional.of(new Ae2MenuActionSupport.SemanticSlotSwapAction(
                            Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                            compat, "ENCODED_PATTERN", 0,
                            remove ? null : (snap, ctx) -> ctx != null && ctx.level() != null
                                    && compat.patternOutput(snap.stack(), ctx.level()).map(value::equals).orElse(false),
                            remove ? "空位" : "产出 " + value + " 的编码样板",
                            () -> reads.openMolecularAssemblerMenu().ifPresent(v -> cache.putAssembler(target, v)),
                            "分子组装室单机样板槽"));
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
                        () -> reads.openMolecularAssemblerMenu().ifPresent(v -> cache.putAssembler(target, v)),
                        "分子组装室加速卡槽 " + slot));
            }
        });
    }

    // 单机模式运行：往合成格投料并从输出槽收回成品。
    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                BlockPos target = at.immutable();
                return Optional.of(new MenuFeed(services, "分子组装室", target, "ae2:molecular_assembler",
                        item.item(), item.count(), permissions, null,
                        new MenuFeed.Hooks(
                                () -> reads.openMolecularAssemblerMenu().ifPresent(v -> cache.putAssembler(target, v)),
                                () -> reads.openMolecularAssemblerMenu().ifPresent(v -> cache.putAssembler(target, v)),
                                problem -> problem)));
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                ClientMenuOpening opening = new ClientMenuOpening(at, permissions, services.menuLayouts(),
                        services.approach(), services.interactions(), services.context());
                return Optional.of(new MenuOutputLook(itemId, true, opening, services.context()));
            }
        });
    }
}
