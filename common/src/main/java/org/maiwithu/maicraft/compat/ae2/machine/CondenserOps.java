// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.FeedPlan;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.MachineFeeding;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 物质聚合器（ae2:condenser）的设备操作委托：
 * 客户端方块流无同步字段，未开窗且无缓存时如实标 unknown；
 * 配置支持 mode（trash / matter_balls / singularity）与 storage_component（in:&lt;item_id&gt; / out），
 * 仅提示存储组件容量门槛（1k=8,192 产物质球，64k=524,288 产奇点），绝不预判拦截；
 * 运行支持产出物质球或奇点，仅投入背包中非保留的低值杂物。
 */
final class CondenserOps implements Ae2DeviceOps {

    static final long MATTER_BALL_ENERGY = 256L;
    static final long SINGULARITY_ENERGY = 256_000L;
    static final long COMPONENT_1K_CAPACITY = 8_192L;
    static final long COMPONENT_64K_CAPACITY = 524_288L;

    static final List<String> MODE_VALUES = List.of("trash", "matter_balls", "singularity");
    /** 允许自动送入聚合器销毁蓄能的低值普通杂物白名单，绝不误吃工具、矿石或贵重物。C 玩家常识。 */
    static final Set<String> LOW_VALUE_TRASH = Set.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt",
            "minecraft:gravel", "minecraft:netherrack", "minecraft:sand",
            "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    CondenserOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        return Optional.of(buildState(cache.condenser(pos)));
    }

    /** 纯函数：根据开窗缓存构造物质聚合器状态（未读过标 unknown）。 */
    static MachineState buildState(Optional<Ae2MachineReads.CondenserMenuView> cached) {
        if (cached.isEmpty()) {
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("mode", "unknown");
            readings.put("stored_power", "unknown");
            readings.put("required_energy", "unknown");
            readings.put("storage_component", "unknown");
            return new MachineState(MachineState.Activity.UNKNOWN, null, null, List.of(), readings,
                    "物质聚合器客户端不同步字段，须打开界面或依赖服务端 ae2.device 读数");
        }
        Ae2MachineReads.CondenserMenuView view = cached.get();
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("mode", view.mode());
        readings.put("stored_power", view.storedPower());
        readings.put("required_energy", view.requiredEnergy());
        String comp = view.storageComponentId() == null || view.storageComponentId().isEmpty()
                ? "empty" : view.storageComponentId();
        readings.put("storage_component", comp);
        List<MachineState.Shown> shown = new ArrayList<>();
        if (view.outputItemId() != null && !view.outputItemId().isEmpty() && view.outputCount() > 0) {
            readings.put("output", view.outputItemId() + " ×" + view.outputCount());
            shown.add(new MachineState.Shown(view.outputItemId(), view.outputCount()));
        } else {
            readings.put("output", "empty");
        }
        Double progress = view.requiredEnergy() > 0
                ? Math.min(1.0, (double) view.storedPower() / view.requiredEnergy()) : null;
        String hint = capacityHint(view.mode(), comp);
        MachineState.Activity activity = view.storedPower() > 0
                ? MachineState.Activity.RUNNING : MachineState.Activity.IDLE;
        return new MachineState(activity, null, progress, List.copyOf(shown), readings, hint);
    }

    /** 根据当前模式与所插存储组件生成容量事实提示，仅作提示绝不阻止原生操作。 */
    static String capacityHint(String mode, String storageComponentId) {
        long capacity = componentCapacity(storageComponentId);
        if ("matter_balls".equals(mode) && capacity < MATTER_BALL_ENERGY) {
            return "产物质球（需求 256）至少需插入 1k 存储组件（容量 8,192），当前组件容量不足无法蓄能";
        }
        if ("singularity".equals(mode) && capacity < SINGULARITY_ENERGY) {
            return "产奇点（需求 256,000）至少需插入 64k 存储组件（容量 524,288），当前组件容量不足无法蓄能";
        }
        return "组件容量足够当前模式（物质球需 256、奇点需 256,000）";
    }

    /** 估算 AE2 存储组件容量（bytes × 8），未知或空返回 0。 */
    static long componentCapacity(String itemId) {
        if (itemId == null || itemId.isEmpty() || "empty".equals(itemId)) return 0L;
        String lower = itemId.toLowerCase(Locale.ROOT);
        if (lower.contains("256k")) return 256L * 1024L * 8L;
        if (lower.contains("64k")) return COMPONENT_64K_CAPACITY;
        if (lower.contains("16k")) return 16L * 1024L * 8L;
        if (lower.contains("4k")) return 4L * 1024L * 8L;
        if (lower.contains("1k")) return COMPONENT_1K_CAPACITY;
        return 0L;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                Optional<Ae2MachineReads.CondenserMenuView> cached = cache.condenser(at);
                String mode = cached.map(Ae2MachineReads.CondenserMenuView::mode).orElse("unknown");
                String comp = cached.map(v -> v.storageComponentId() == null || v.storageComponentId().isEmpty()
                        ? "out" : "in:" + v.storageComponentId()).orElse("unknown");
                return List.of(
                        new MachineSetting("mode", mode, MODE_VALUES,
                                "输出模式：trash（销毁）/ matter_balls（物质球 256，至少 1k 组件）/ singularity（奇点 256,000，至少 64k 组件）"),
                        new MachineSetting("storage_component", comp, List.of("in:<item_id>", "out"),
                                "存储组件槽：in:<item_id> 放入组件，out 取出组件"));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                BlockPos target = at.immutable();
                if ("mode".equals(key)) {
                    String normalized = value.toLowerCase(Locale.ROOT);
                    if (!MODE_VALUES.contains(normalized)) return Optional.empty();
                    return Optional.of(new Ae2MenuActionSupport.ConfigCycleAction(
                            Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                            compat, "condenser_output", normalized, 4,
                            () -> reads.openCondenserMenu().map(Ae2MachineReads.CondenserMenuView::mode),
                            () -> reads.openCondenserMenu().ifPresent(v -> cache.putCondenser(target, v)),
                            "物质聚合器输出模式"));
                }
                if ("storage_component".equals(key)) {
                    boolean out = "out".equalsIgnoreCase(value) || "empty".equalsIgnoreCase(value);
                    String wanted = out ? null : (value.startsWith("in:") ? value.substring(3) : value);
                    if (!out && (wanted == null || wanted.isBlank())) return Optional.empty();
                    return Optional.of(new Ae2MenuActionSupport.SemanticSlotSwapAction(
                            Ae2MenuActionSupport.openBlockOrPart(target, null, reads, services, permissions),
                            compat, "STORAGE_CELL", 0,
                            out ? null : (snap, ctx) -> wanted.equals(snap.key()),
                            out ? "空位" : wanted,
                            () -> reads.openCondenserMenu().ifPresent(v -> cache.putCondenser(target, v)),
                            "物质聚合器存储组件槽"));
                }
                return Optional.empty();
            }
        });
    }

    @Override public Optional<MachineFeeding> feeding(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineFeeding() {
            @Override public Optional<Action> feed(BlockPos at, FeedPlan.Entry item, Permissions permissions) {
                return Optional.empty();
            }

            @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
                return Optional.empty();
            }

            @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
                if (request == null || request.item() == null) return Optional.empty();
                String wantedMode = targetModeForItem(request.item());
                if (wantedMode == null) return Optional.empty();
                return Optional.of(new CondenserFullRun(at, request.item(), Math.max(1, request.count()),
                        wantedMode, compat, reads, cache, services, permissions));
            }
        });
    }

    static String targetModeForItem(String itemId) {
        return switch (itemId) {
            case "ae2:matter_ball" -> "matter_balls";
            case "ae2:singularity" -> "singularity";
            default -> null;
        };
    }

    /**
     * 物质聚合器产出物质球/奇点的整段运行动作：
     * 打开界面 → 若模式不符则切换至目标模式 → 只从角色背包挑选低值普通杂物投入黑洞槽 →
     * 一旦输出槽出现目标产物立即取回背包，直至达到目标数量或背包低值杂物耗尽。
     */
    static final class CondenserFullRun implements FullRun {

        private enum Stage { OPEN, ENSURE_MODE, FEED_OR_TAKE, CLOSE, DONE }

        private final BlockPos at;
        private final String targetItem;
        private final int targetCount;
        private final String wantedMode;
        private final Ae2Compat compat;
        private final Ae2MachineReads reads;
        private final Ae2MachineCache cache;
        private final MenuOpening opening;

        private final List<String> notes = new ArrayList<>();
        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int produced;
        private int modeCycles;
        private int waited;
        private Problem problem;

        CondenserFullRun(BlockPos at, String targetItem, int targetCount, String wantedMode,
                Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache,
                PlayerServices services, Permissions permissions) {
            this(at, targetItem, targetCount, wantedMode, compat, reads, cache,
                    Ae2MenuActionSupport.openBlockOrPart(at, null, reads, services, permissions));
        }

        CondenserFullRun(BlockPos at, String targetItem, int targetCount, String wantedMode,
                Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache, MenuOpening opening) {
            this.at = at.immutable();
            this.targetItem = targetItem;
            this.targetCount = targetCount;
            this.wantedMode = wantedMode;
            this.compat = compat;
            this.reads = reads;
            this.cache = cache;
            this.opening = opening;
        }

        @Override public int produced() { return produced; }
        @Override public boolean complete() { return produced >= targetCount; }
        @Override public List<String> notes() { return List.copyOf(notes); }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case ENSURE_MODE -> ensureMode();
                case FEED_OR_TAKE -> feedOrTake();
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "物质聚合器界面没打开"));
            stage = Stage.ENSURE_MODE;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus ensureMode() {
            Optional<Ae2MachineReads.CondenserMenuView> view = reads.openCondenserMenu();
            if (view.isEmpty()) {
                if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "物质聚合器界面同步超时"));
            }
            cache.putCondenser(at, view.get());
            if (wantedMode.equals(view.get().mode())) {
                String hint = capacityHint(view.get().mode(), view.get().storageComponentId());
                if (!hint.startsWith("组件容量足够")) notes.add(hint);
                stage = Stage.FEED_OR_TAKE;
                return ActionStatus.progressed();
            }
            if (modeCycles >= 4) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "物质聚合器未能切到模式 " + wantedMode));
            }
            compat.sendConfigButton("condenser_output", false);
            modeCycles++;
            return ActionStatus.running();
        }

        // 先收产出，再投背包里低值白名单杂物；若没有可投低值杂物如实停下并报 NEED_ITEM。
        private ActionStatus feedOrTake() {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) return ActionStatus.running();
            reads.openCondenserMenu().ifPresent(v -> cache.putCondenser(at, v));
            if (produced >= targetCount) return beginClose(null);
            List<Integer> outputSlots = compat.menuSlotsOf("MACHINE_OUTPUT");
            if (!outputSlots.isEmpty()) {
                int outSlot = outputSlots.getFirst();
                SlotSnapshot outSnap = snapshotBySlot(reading.get(), outSlot);
                if (!outSnap.isEmpty() && targetItem.equals(outSnap.key())) {
                    if (menu.quickMove(outSlot)) {
                        produced += outSnap.count();
                        return produced >= targetCount ? beginClose(null) : ActionStatus.progressed();
                    }
                    return ActionStatus.running();
                }
            }
            int trashSlot = findLowValueTrashSlot(reading.get());
            if (trashSlot < 0) {
                String compNote = cache.condenser(at)
                        .map(v -> "；当前蓄能 " + v.storedPower() + "/" + v.requiredEnergy()
                                + "，" + capacityHint(v.mode(), v.storageComponentId()))
                        .orElse("");
                return beginClose(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里已没有可投入物质聚合器的低值杂物（圆石/泥土/沙砾/地狱岩等）" + compNote));
            }
            return menu.quickMove(trashSlot) ? ActionStatus.progressed() : ActionStatus.running();
        }

        /** 仅挑选低值普通杂物，保护工具、武器、装备和贵重物品不被黑洞槽销毁。 */
        static int findLowValueTrashSlot(MenuContent.Reading reading) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot snap = reading.playerSnapshots().get(i);
                if (!snap.isEmpty() && LOW_VALUE_TRASH.contains(snap.key())) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        private static SlotSnapshot snapshotBySlot(MenuContent.Reading reading, int slotId) {
            for (int i = 0; i < reading.containerSlotIds().size(); i++) {
                if (reading.containerSlotIds().get(i) == slotId) {
                    return reading.containerSnapshots().get(i);
                }
            }
            return SlotSnapshot.empty();
        }

        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            closing = null;
            return finish(problem);
        }

        private ActionStatus beginClose(Problem cause) {
            problem = cause;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem cause) {
            problem = cause;
            stage = Stage.DONE;
            return cause == null ? ActionStatus.done() : ActionStatus.failed(cause);
        }

        @Override public void pause() {
            if (closing != null) closing.pause();
            else if (menu == null) opening.pause();
        }

        @Override public void close() {
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) menu.abandon();
            else if (menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }

        @Override public String describe() {
            return "在物质聚合器中生产 " + targetItem + "（" + produced + "/" + targetCount + "）";
        }
    }
}
