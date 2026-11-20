// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

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
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 合成 CPU（ae2:crafting_unit 系列）的设备操作委托：
 * 读数补充开窗缓存（scheduling_mode、cant_store_items 与 CPU 列表摘要，与服务端 ae2.network 扩展兼容）；
 * 配置支持 scheduling_mode（any / player_only / machine_only）与 job_action（cancel / toggle_pause），
 * 因 ae2:craftingcpu 不含 36 格玩家背包，直接经 {@link RawMenuOpening} 直连交互，绝不走容器布局证明。
 */
final class CraftingCpuOps implements Ae2DeviceOps {

    static final List<String> SCHEDULING_MODES = List.of("any", "player_only", "machine_only");
    static final List<String> JOB_ACTIONS = List.of("cancel", "toggle_pause");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    CraftingCpuOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        Optional<Ae2MachineCache.CpuCache> cached = cache.cpu(pos);
        if (cached.isEmpty()) return Map.of();
        Ae2MachineCache.CpuCache view = cached.get();
        Map<String, Object> readings = new LinkedHashMap<>();
        if (view.schedulingMode() != null) readings.put("scheduling_mode", view.schedulingMode());
        readings.put("cant_store_items", view.cantStoreItems());
        if (view.cantStoreItems()) {
            readings.put("storage_warning", "网络存储空间不足，合成产物无法回存");
        }
        if (view.cpus() != null && !view.cpus().isEmpty()) {
            readings.put("cached_cpus", (long) view.cpus().size());
        }
        return readings;
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                Optional<Ae2MachineCache.CpuCache> cached = cache.cpu(at);
                String mode = cached.map(Ae2MachineCache.CpuCache::schedulingMode).orElse("unknown");
                String lastAction = cached.map(Ae2MachineCache.CpuCache::lastJobAction).orElse("none");
                return List.of(
                        new MachineSetting("scheduling_mode", mode, SCHEDULING_MODES,
                                "合成 CPU 调度归属：any（全部）/ player_only（仅玩家下单）/ machine_only（仅自动设备）"),
                        new MachineSetting("job_action", lastAction, JOB_ACTIONS,
                                "作业控制：cancel 取消当前卡住的合成作业，toggle_pause 暂停或恢复调度"));
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                BlockPos target = at.immutable();
                String normalized = value.toLowerCase(Locale.ROOT);
                if ("scheduling_mode".equals(key)) {
                    if (!SCHEDULING_MODES.contains(normalized)) return Optional.empty();
                    return Optional.of(new CpuControlAction(target, true, normalized, compat, reads, cache,
                            services, permissions));
                }
                if ("job_action".equals(key)) {
                    if (!JOB_ACTIONS.contains(normalized)) return Optional.empty();
                    return Optional.of(new CpuControlAction(target, false, normalized, compat, reads, cache,
                            services, permissions));
                }
                return Optional.empty();
            }
        });
    }

    /** 直连打开合成 CPU 界面执行调度模式切换或取消/暂停作业，更新缓存后关窗。 */
    static final class CpuControlAction implements Action {

        private enum Stage { OPEN, ACT, WAIT_CONFIRM, CLOSE, DONE }

        private final BlockPos at;
        private final boolean schedulingModeChange;
        private final String wanted;
        private final Ae2Compat compat;
        private final Ae2MachineReads reads;
        private final Ae2MachineCache cache;
        private final RawMenuOpening opening;

        private Stage stage = Stage.OPEN;
        private String modeBefore;
        private int cycles;
        private int waited;
        private Problem problem;

        CpuControlAction(BlockPos at, boolean schedulingModeChange, String wanted, Ae2Compat compat,
                Ae2MachineReads reads, Ae2MachineCache cache, PlayerServices services, Permissions permissions) {
            this(at, schedulingModeChange, wanted, compat, reads, cache,
                    new RawMenuOpening(at, permissions, services, () -> reads.openCraftingCpuMenu().isPresent(), "合成 CPU"));
        }

        CpuControlAction(BlockPos at, boolean schedulingModeChange, String wanted, Ae2Compat compat,
                Ae2MachineReads reads, Ae2MachineCache cache, RawMenuOpening opening) {
            this.at = at.immutable();
            this.schedulingModeChange = schedulingModeChange;
            this.wanted = wanted;
            this.compat = compat;
            this.reads = reads;
            this.cache = cache;
            this.opening = opening;
        }

        @Override public ActionStatus tick(TickContext tick) {
            return switch (stage) {
                case OPEN -> open(tick);
                case ACT -> act();
                case WAIT_CONFIRM -> waitConfirm();
                case CLOSE -> closeMenu();
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            stage = Stage.ACT;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus act() {
            Optional<Ae2MachineReads.CraftingCpuMenuView> view = reads.openCraftingCpuMenu();
            if (view.isEmpty()) {
                if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "合成 CPU 界面同步超时"));
            }
            if (!schedulingModeChange) {
                boolean sent = "cancel".equals(wanted) ? reads.sendCpuCancel() : reads.sendCpuTogglePause();
                if (!sent) {
                    return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送合成 CPU 作业控制指令：" + wanted));
                }
                cache.putCpu(at, new Ae2MachineCache.CpuCache(view.get().schedulingMode(),
                        view.get().cantStoreItems(), wanted, view.get().cpus()));
                return beginClose(null);
            }
            String currentMode = view.get().schedulingMode();
            cache.putCpu(at, new Ae2MachineCache.CpuCache(currentMode, view.get().cantStoreItems(),
                    cache.cpu(at).map(Ae2MachineCache.CpuCache::lastJobAction).orElse("none"), view.get().cpus()));
            if (wanted.equalsIgnoreCase(currentMode)) return beginClose(null);
            if (cycles >= 4) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "合成 CPU 调度模式循环切换后仍为 " + currentMode + "，未变为 " + wanted));
            }
            if (!compat.sendConfigButton("crafting_scheduling_mode", false)) {
                return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "无法发送合成 CPU 调度模式切换包"));
            }
            modeBefore = currentMode;
            cycles++;
            waited = 0;
            stage = Stage.WAIT_CONFIRM;
            return ActionStatus.progressed();
        }

        private ActionStatus waitConfirm() {
            Optional<Ae2MachineReads.CraftingCpuMenuView> view = reads.openCraftingCpuMenu();
            if (view.isPresent() && !view.get().schedulingMode().equalsIgnoreCase(modeBefore)) {
                String updated = view.get().schedulingMode();
                cache.putCpu(at, new Ae2MachineCache.CpuCache(updated, view.get().cantStoreItems(),
                        cache.cpu(at).map(Ae2MachineCache.CpuCache::lastJobAction).orElse("none"), view.get().cpus()));
                if (wanted.equalsIgnoreCase(updated)) return beginClose(null);
                stage = Stage.ACT;
                waited = 0;
                return ActionStatus.progressed();
            }
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "等待合成 CPU 调度模式同步超时"));
        }

        private ActionStatus closeMenu() {
            opening.requestClose();
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

        @Override public void pause() { opening.pause(); }
        @Override public void close() { opening.close(); }
        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }
        @Override public String describe() {
            return schedulingModeChange ? "切换合成 CPU 调度模式为 " + wanted : "执行合成 CPU 作业指令 " + wanted;
        }
    }
}
