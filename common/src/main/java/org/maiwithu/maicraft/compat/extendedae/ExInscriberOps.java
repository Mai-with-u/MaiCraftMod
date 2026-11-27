// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.extendedae;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 扩展压印器（{@code extendedae:ex_inscriber}）设备委托：
 * 除相对正面外的五面可连 ME 网、受电或安装木质曲柄；客户端直接同步 4 页全部槽位与堆叠上限；
 * {@code machine_run} 经 {@link ExInscriberPlanner} 自动在 4 页间择优调度并只收回本会话角色自放的模板；
 * 配置支持 {@code separate_sides}、{@code auto_export}、{@code input_capacity}（1/4/64）与 {@code upgrade.0..3}。
 */
final class ExInscriberOps implements ExtendedAeDeviceOps {

    static final int UPGRADE_SLOTS = 4;
    static final List<String> YES_NO = List.of("yes", "no");
    static final List<String> CAPACITIES = List.of("1", "4", "64");
    private static final List<String> UPGRADE_CHOICES = List.of("ae2:speed_card", "empty");

    private record PageKey(BlockPos pos, int page) {}

    private final ExtendedAeCompat compat;
    private final ExtendedAeDeviceReadings readings;
    private final BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings;
    /** 记录每台扩展压印器最近一次计划选定的目标页（0..3）。 */
    private final Map<BlockPos, Integer> selectedPages = new HashMap<>();
    /** 记录每台扩展压印器各页由本会话角色亲自放入的不消耗模板。 */
    private final Map<PageKey, Set<String>> placedPlates = new HashMap<>();

    ExInscriberOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings) {
        this(compat, readings, null);
    }

    ExInscriberOps(ExtendedAeCompat compat, ExtendedAeDeviceReadings readings,
            BiFunction<BlockPos, Permissions, MenuOpening> menuOpenings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.readings = Objects.requireNonNull(readings, "readings");
        this.menuOpenings = menuOpenings;
    }

    // 扩展压印器除相对正面外的五面可连 ME 网、受电或安装木质曲柄。
    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        Direction front = level == null ? Direction.NORTH : compat.exInscriberAt(level, at)
                .map(ExtendedAeReads.ExInscriberView::front).orElse(Direction.NORTH);
        List<ExchangePoint> points = new ArrayList<>(5);
        for (Direction side : Direction.values()) {
            if (side == front) continue;
            points.add(new ExchangePoint(at, side, null, ME_NETWORK, ExchangePoint.Flow.BOTH,
                    "扩展压印器非正面：可连 ME 网、受电或安装木质曲柄"));
        }
        return Optional.of(List.copyOf(points));
    }

    @Override public Optional<MachineState> state(BlockPos at, ClientLevel level) {
        if (level == null) return Optional.of(MachineState.unknown("角色不在世界里，读不到扩展压印器"));
        Optional<ExtendedAeReads.ExInscriberView> found = compat.exInscriberAt(level, at);
        if (found.isEmpty()) return Optional.empty();
        return Optional.of(buildState(found.get(), readings.exInscriber(at), readings.latest(at)));
    }

    /** 纯函数：整合 4 页客户端同步槽位、堆叠上限与开窗/服务端配置读数，完整呈现 4 页不折叠省略。 */
    static MachineState buildState(ExtendedAeReads.ExInscriberView view,
            Optional<ExtendedAeReads.ExInscriberMenuView> cached, Optional<JsonObject> serverReply) {
        Map<String, Object> readingsMap = new LinkedHashMap<>();
        readingsMap.put("powered", view.powered());
        readingsMap.put("active", view.active());
        readingsMap.put("smashing", view.smashing());
        readingsMap.put("input_capacity", Integer.toString(view.stackSize()));
        List<Map<String, Object>> pageList = new ArrayList<>(4);
        List<MachineState.Shown> shown = new ArrayList<>();
        for (ExtendedAeReads.InscriberPageView page : view.pages()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("page", page.page());
            entry.put("top", formatSlot(page.top()));
            entry.put("bottom", formatSlot(page.bottom()));
            entry.put("middle", formatSlot(page.middle()));
            entry.put("output", formatSlot(page.output()));
            pageList.add(entry);
            for (int i = 0; i < 4; i++) {
                MachineState.Shown s = page.slot(i);
                if (s != null) shown.add(s);
            }
        }
        readingsMap.put("pages", pageList);
        cached.ifPresent(menu -> applyMenuCache(readingsMap, menu));
        serverReply.ifPresent(reply -> ExtendedAeDeviceReadings.mergeServerReply(readingsMap, reply));
        MachineState.Activity activity = !view.powered() ? MachineState.Activity.OFFLINE
                : view.smashing() ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, null, List.copyOf(shown), readingsMap,
                "除正面外五面可连 ME 网受电；共 4 个独立工作页并行压印");
    }

    private static void applyMenuCache(Map<String, Object> target, ExtendedAeReads.ExInscriberMenuView menu) {
        target.put("separate_sides", menu.separateSides());
        target.put("auto_export", menu.autoExport());
        target.put("input_capacity", menu.inputCapacity());
        target.put("processing_time", (long) menu.processingTime());
        target.put("max_processing_time", (long) menu.maxProcessingTime());
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            target.put("upgrade." + i, i < menu.upgrades().size() ? menu.upgrades().get(i) : "empty");
        }
    }

    // 4 页输出格均由客户端同步，直接汇总全部输出槽物品。
    @Override public List<MachineState.Shown> output(BlockPos at, ClientLevel level) {
        if (level == null) return List.of();
        Optional<ExtendedAeReads.ExInscriberView> found = compat.exInscriberAt(level, at);
        if (found.isEmpty()) return List.of();
        List<MachineState.Shown> outputs = new ArrayList<>();
        for (ExtendedAeReads.InscriberPageView page : found.get().pages()) {
            if (page.output() != null) outputs.add(page.output());
        }
        return List.copyOf(outputs);
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public FeedPlan plan(BlockPos at, ShownRecipe recipe, int batches, Permissions permissions) {
                BlockPos target = at.immutable();
                ClientLevel level = services.context().get() == null ? null : services.context().get().level();
                Optional<ExtendedAeReads.ExInscriberView> view = level == null
                        ? Optional.empty() : compat.exInscriberAt(level, target);
                ExInscriberPlanner.PagePlan computed = ExInscriberPlanner.plan(recipe, batches, view);
                selectedPages.put(target, computed.targetPage());
                Set<String> own = placedPlates.get(new PageKey(target, computed.targetPage()));
                Optional<Action> clearing = computed.conflictingSlots().isEmpty() ? Optional.empty()
                        : Optional.of(new ExInscriberActions.ClearAction(computed.targetPage(),
                                computed.conflictingSlots(), own, openingFor(target, permissions, services), compat));
                return new FeedPlan(computed.entries(), computed.notes(), computed.conflict(), clearing);
            }

            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                BlockPos target = at.immutable();
                int page = selectedPages.getOrDefault(target, 0);
                Set<String> own = placedPlates.computeIfAbsent(new PageKey(target, page), k -> new LinkedHashSet<>());
                return Optional.of(new ExInscriberActions.FeedAction(page, item.item(), item.count(),
                        item.collectBack(), own, openingFor(target, permissions, services), compat));
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                BlockPos target = at.immutable();
                int page = selectedPages.getOrDefault(target, 0);
                Set<String> own = placedPlates.get(new PageKey(target, page));
                return Optional.of(new ExInscriberActions.TakeAction(page, itemId, own,
                        openingFor(target, permissions, services), compat, services.context()));
            }
        });
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                return inscriberSettings(readings.exInscriber(at), readings.latest(at));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return changeSetting(at.immutable(), key, value, permissions, services);
            }
        });
    }

    /** 纯函数：构造扩展压印器设置项清单。 */
    static List<MachineSetting> inscriberSettings(Optional<ExtendedAeReads.ExInscriberMenuView> cached,
            Optional<JsonObject> serverReply) {
        List<MachineSetting> list = new ArrayList<>(3 + UPGRADE_SLOTS);
        String separate = cached.map(ExtendedAeReads.ExInscriberMenuView::separateSides)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "inscriber_separate_sides")))
                .orElse("unknown");
        String autoExport = cached.map(ExtendedAeReads.ExInscriberMenuView::autoExport)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readSetting(r, "auto_export")))
                .orElse("unknown");
        String cap = cached.map(ExtendedAeReads.ExInscriberMenuView::inputCapacity)
                .or(() -> serverReply.flatMap(r -> ExtendedAeDeviceReadings.readString(r, "input_capacity")))
                .orElse("unknown");
        list.add(new MachineSetting("separate_sides", separate, YES_NO,
                "分离面输入：yes 时顶底与四周分槽接入，no 时任意面互通"));
        list.add(new MachineSetting("auto_export", autoExport, YES_NO,
                "自动输出：yes 时压印完成自动推向相邻容器"));
        list.add(new MachineSetting("input_capacity", cap, CAPACITIES,
                "单槽输入堆叠上限：1 / 4 / 64"));
        List<String> upgrades = cached.map(ExtendedAeReads.ExInscriberMenuView::upgrades)
                .orElseGet(() -> serverReply.map(ExtendedAeDeviceReadings::readUpgrades).orElse(List.of()));
        for (int i = 0; i < UPGRADE_SLOTS; i++) {
            String card = i < upgrades.size() ? upgrades.get(i) : (cached.isPresent() ? "empty" : "unknown");
            list.add(new MachineSetting("upgrade." + i, card, UPGRADE_CHOICES,
                    "扩展压印器第 " + i + " 号升级槽（ae2:speed_card / empty）"));
        }
        return List.copyOf(list);
    }

    private Optional<Action> changeSetting(BlockPos target, String key, String value,
            Permissions permissions, PlayerServices services) {
        if ("separate_sides".equals(key)) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                    openingFor(target, permissions, services), compat, "inscriber_separate_sides", normalized, 3,
                    () -> compat.openExInscriberMenu().map(ExtendedAeReads.ExInscriberMenuView::separateSides),
                    () -> compat.openExInscriberMenu().ifPresent(v -> readings.putExInscriber(target, v)),
                    "扩展压印器分离面"));
        }
        if ("auto_export".equals(key)) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(new ExtendedAeMenuActionSupport.ConfigCycleAction(
                    openingFor(target, permissions, services), compat, "auto_export", normalized, 3,
                    () -> compat.openExInscriberMenu().map(ExtendedAeReads.ExInscriberMenuView::autoExport),
                    () -> compat.openExInscriberMenu().ifPresent(v -> readings.putExInscriber(target, v)),
                    "扩展压印器自动输出"));
        }
        if ("input_capacity".equals(key)) {
            String normalized = value.trim();
            if (!CAPACITIES.contains(normalized)) return Optional.empty();
            int size = Integer.parseInt(normalized);
            int expectedMode = stackModeOf(size);
            return Optional.of(new ExtendedAeMenuActionSupport.GenericPacketAction(
                    openingFor(target, permissions, services),
                    () -> compat.openExInscriberMenu().isPresent(),
                    () -> compat.openExInscriberMenu().map(v -> v.stackMode() == expectedMode).orElse(false),
                    () -> compat.sendGenericAction("stack", size),
                    () -> compat.openExInscriberMenu().map(v -> v.stackMode() == expectedMode).orElse(false),
                    () -> compat.openExInscriberMenu().ifPresent(v -> readings.putExInscriber(target, v)),
                    "设置扩展压印器输入上限为 " + size));
        }
        int slot = ExtendedAeMenuActionSupport.parseUpgradeSlot(key, UPGRADE_SLOTS);
        if (slot < 0) return Optional.empty();
        boolean remove = "empty".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value);
        if (!remove && !"ae2:speed_card".equals(value)) return Optional.empty();
        return Optional.of(new ExtendedAeMenuActionSupport.SemanticSlotSwapAction(
                openingFor(target, permissions, services), compat, "UPGRADE", slot,
                remove ? null : snap -> "ae2:speed_card".equals(snap.key()),
                remove ? "空位" : "ae2:speed_card",
                () -> compat.openExInscriberMenu().ifPresent(v -> readings.putExInscriber(target, v)),
                "扩展压印器升级槽 " + slot));
    }

    /** 将输入堆叠上限（64/1/4）换算为 ContainerExInscriber.getStackMode() 编号（0/1/2）。 */
    static int stackModeOf(int size) {
        return switch (size) {
            case 1 -> 1;
            case 4 -> 2;
            default -> 0;
        };
    }

    private static String formatSlot(MachineState.Shown slot) {
        return slot == null ? "empty" : slot.itemId() + " ×" + slot.count();
    }

    private MenuOpening openingFor(BlockPos target, Permissions permissions, PlayerServices services) {
        return menuOpenings != null ? menuOpenings.apply(target, permissions)
                : ExtendedAeMenuActionSupport.openBlock(target, services, permissions);
    }
}
