// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2SemanticsLayout;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 空间锚（{@code ae2:spatial_anchor}）的设备操作委托：
 * <ul>
 * <li>能耗公式：{@code 80 + n * (n + 1) / 2} AE/t（{@code n} 为维持加载的区块数），断电宽限 200 刻后卸载；</li>
 * <li>读数：结合服务端 {@code ae2.device} 或开 {@code ae2:spatialanchor} 菜单的会话缓存输出
 * {@code power_consumption}、{@code loaded_chunks}、{@code overlay_mode} 与全网多维统计；</li>
 * <li>配置：支持 {@code overlay_mode=yes|no}，直连发 {@code ConfigButtonPacket(Settings.OVERLAY_MODE, false)}；
 * 由于 {@code ae2:spatialanchor} 不含 36 格玩家背包，不注册进语义容器布局，而由本动作直开直关。</li>
 * </ul>
 */
public final class SpatialAnchor implements Ae2DeviceOps {

    /** 空间锚方块注册 ID。 */
    public static final String BLOCK_ID = "ae2:spatial_anchor";
    /** 空间锚菜单类型注册 ID（无玩家背包槽，不注册进 Ae2SemanticsLayout）。 */
    public static final String MENU_ID = "ae2:spatialanchor";
    /** 配置键名与 AE2 Settings.OVERLAY_MODE 注册名一致。 */
    public static final String SETTING_OVERLAY_MODE = "overlay_mode";
    /** 基础空载耗电（AE/t）。F 游戏事实。 */
    public static final long BASE_POWER_CONSUMPTION = 80L;

    private final Ae2Compat compat;
    private final Ae2AdvancedReads reads;
    private final Ae2DeviceReadings serverReadings;
    /** 本会话打开空间锚界面并同步后缓存的最新状态。 */
    private final Map<BlockPos, Ae2AdvancedReads.SpatialAnchorMenuStatus> seenMenu = new HashMap<>();

    public SpatialAnchor(Ae2Compat compat, Ae2AdvancedReads reads, Ae2DeviceReadings serverReadings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.serverReadings = Objects.requireNonNull(serverReadings, "serverReadings");
    }

    /**
     * 按 AE2 规则计算空间锚实时耗电（AE/t）：{@code 80 + n * (n + 1) / 2}。
     */
    public static long powerConsumption(int loadedChunks) {
        int n = Math.max(0, loadedChunks);
        return BASE_POWER_CONSUMPTION + (long) n * (n + 1L) / 2L;
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.empty();
        Optional<Ae2AdvancedReads.SpatialAnchorCell> cell = reads.spatialAnchor(level, pos);
        if (cell.isEmpty()) return Optional.empty();
        return Optional.of(composeState(cell.get(),
                Optional.ofNullable(seenMenu.get(pos.immutable())),
                serverReadings.latest(pos, null)));
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new AnchorSettings(services));
    }

    /** 合并客户端在线态、开窗缓存与服务端多维统计为统一状态（纯函数，便于离线单测）。 */
    public static MachineState composeState(Ae2AdvancedReads.SpatialAnchorCell cell,
                                            Optional<Ae2AdvancedReads.SpatialAnchorMenuStatus> cachedMenu,
                                            Optional<JsonObject> serverReply) {
        Map<String, Object> readings = new LinkedHashMap<>();
        readings.put("active", cell.active());

        if (serverReply.isPresent()) {
            JsonObject reply = serverReply.get();
            long chunks = Ae2DeviceReadings.readLong(reply, "loaded_chunks").orElse(0L);
            long power = Ae2DeviceReadings.readLong(reply, "power_consumption")
                    .orElse(powerConsumption((int) chunks));
            readings.put("loaded_chunks", chunks);
            readings.put("power_consumption", power);
            readings.put("overlay_mode", Ae2DeviceReadings.readString(reply, "overlay_mode").orElse("unknown"));
            Ae2DeviceReadings.readLong(reply, "all_loaded_worlds")
                    .ifPresent(v -> readings.put("all_loaded_worlds", v));
            Ae2DeviceReadings.readLong(reply, "all_loaded_chunks")
                    .ifPresent(v -> readings.put("all_loaded_chunks", v));
            Ae2DeviceReadings.readLong(reply, "all_worlds").ifPresent(v -> readings.put("all_worlds", v));
            Ae2DeviceReadings.readLong(reply, "all_chunks").ifPresent(v -> readings.put("all_chunks", v));
        } else if (cachedMenu.isPresent()) {
            Ae2AdvancedReads.SpatialAnchorMenuStatus status = cachedMenu.get();
            readings.put("loaded_chunks", (long) status.loadedChunks());
            readings.put("power_consumption", status.powerConsumption());
            readings.put("overlay_mode", status.overlayMode());
            readings.put("all_loaded_worlds", (long) status.allLoadedWorlds());
            readings.put("all_loaded_chunks", (long) status.allLoadedChunks());
            readings.put("all_worlds", (long) status.allWorlds());
            readings.put("all_chunks", (long) status.allChunks());
        } else if (cell.overlayChunkCount() > 0) {
            readings.put("loaded_chunks", (long) cell.overlayChunkCount());
            readings.put("power_consumption", powerConsumption(cell.overlayChunkCount()));
            readings.put("overlay_mode", "yes");
        } else {
            readings.put("loaded_chunks", "unknown");
            readings.put("power_consumption", "unknown");
            readings.put("overlay_mode", "unknown");
        }

        String note = !cell.active()
                ? "空间锚当前离线（缺电或未分到频道，断电宽限 200 刻后卸载区块）"
                : "unknown".equals(readings.get("loaded_chunks"))
                ? "关闭覆盖层时客户端不同步加载区块数；启用服务端 ae2.device 或打开空间锚界面可读出精确数值"
                : "正维持 " + readings.get("loaded_chunks") + " 个区块常载，耗电 "
                + readings.get("power_consumption") + " AE/t";
        MachineState.Activity activity = cell.active() ? MachineState.Activity.RUNNING : MachineState.Activity.OFFLINE;
        return new MachineState(activity, null, null, List.of(), readings, note);
    }

    /** 空间锚配置项：overlay_mode=yes|no。 */
    private final class AnchorSettings implements MachineSettings {

        private final PlayerServices services;

        private AnchorSettings(PlayerServices services) {
            this.services = services;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            String value = currentOverlayValue(at);
            return List.of(new MachineSetting(SETTING_OVERLAY_MODE, value, List.of("yes", "no"),
                    "是否渲染空间锚加载区块范围覆盖层（yes / no）"));
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            if (!SETTING_OVERLAY_MODE.equals(key)) return Optional.empty();
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!"yes".equals(normalized) && !"no".equals(normalized)) return Optional.empty();
            return Optional.of(new OverlayToggleAction(at.immutable(), normalized, permissions, services));
        }

        private String currentOverlayValue(BlockPos at) {
            Ae2AdvancedReads.SpatialAnchorMenuStatus cached = seenMenu.get(at.immutable());
            if (cached != null) return cached.overlayMode();
            Optional<JsonObject> serverReply = serverReadings.latest(at, null);
            if (serverReply.isPresent()) {
                Optional<String> fromServer = Ae2DeviceReadings.readString(serverReply.get(), "overlay_mode");
                if (fromServer.isPresent()) return fromServer.get();
            }
            PlayerContext current = services.context().get();
            if (current != null && current.level() != null) {
                Optional<Ae2AdvancedReads.SpatialAnchorCell> cell = reads.spatialAnchor(current.level(), at);
                if (cell.isPresent() && cell.get().overlayChunkCount() > 0) return "yes";
            }
            return "unknown";
        }
    }

    /**
     * 切换空间锚覆盖层模式的直连动作：由于 ae2:spatialanchor 不含 36 格玩家背包，不经 MenuLayout，
     * 直接走近空手右键打开 → 等菜单同步 → 发送 ConfigButtonPacket(Settings.OVERLAY_MODE, false) → 核对后经 MenuActions 关窗。
     */
    private final class OverlayToggleAction implements Action {

        private enum Stage { CLEAR_HAND, APPROACH, OPEN, WAIT_SYNC, SEND_TOGGLE, WAIT_CONFIRM, CLOSE, FINISHED }

        /** 菜单同步与切换确认上限（刻）。F 游戏事实。 */
        private static final int SYNC_WAIT_TICKS = 40;
        /** 关闭界面等待上限（刻）。 */
        private static final int CLOSE_WAIT_TICKS = 20;

        private final BlockPos at;
        private final String targetMode;
        private final Permissions permissions;
        private final PlayerServices services;

        private Stage stage = Stage.CLEAR_HAND;
        private Action current;
        private PendingMenuAction pendingClose;
        private int waited;
        private int togglesSent;
        private Problem problem;

        private OverlayToggleAction(BlockPos at, String targetMode, Permissions permissions, PlayerServices services) {
            this.at = at;
            this.targetMode = targetMode;
            this.permissions = permissions;
            this.services = services;
        }

        @Override public ActionStatus tick(TickContext context) {
            PlayerContext player = context.player();
            if (stage != Stage.FINISHED && (player == null || !player.isCurrent() || player.localPlayer() == null)) {
                return ActionStatus.running();
            }
            return switch (stage) {
                case CLEAR_HAND -> clearHand(context);
                case APPROACH -> approach(context);
                case OPEN -> openAnchor(context);
                case WAIT_SYNC -> waitSync(player);
                case SEND_TOGGLE -> sendToggle(player);
                case WAIT_CONFIRM -> waitConfirm(player);
                case CLOSE -> closeMenu(player);
                case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus clearHand(TickContext context) {
            if (current == null) {
                Optional<Action> empty = services.toMainhand().emptyMainhand();
                if (empty.isEmpty()) return enter(Stage.APPROACH);
                current = empty.get();
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            return enter(Stage.APPROACH);
        }

        private ActionStatus approach(TickContext context) {
            if (current == null) {
                current = services.approach().toward(ApproachTarget.ofBlock(at), permissions);
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            return enter(Stage.OPEN);
        }

        private ActionStatus openAnchor(TickContext context) {
            if (current == null) {
                int beforeId = context.player().localPlayer().containerMenu.containerId;
                InteractionConfirmation confirmation = InteractionConfirmation.expiresAsNotApplied(
                        InteractionConfirmation.menuChanged(beforeId));
                current = services.interactions().useBlock(at, confirmation);
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            return enter(Stage.WAIT_SYNC);
        }

        // 等 SpatialAnchorMenu 打开并同步出当前 overlayMode，再决定要不要发切换包。
        private ActionStatus waitSync(PlayerContext player) {
            Optional<Ae2AdvancedReads.SpatialAnchorMenuStatus> status = reads.openSpatialAnchorMenu(
                    player.localPlayer().containerMenu);
            if (status.isPresent()) {
                seenMenu.put(at, status.get());
                if (targetMode.equals(status.get().overlayMode())) {
                    return closeWith(null);
                }
                return enter(Stage.SEND_TOGGLE);
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK, "打开空间锚后界面一直未识别为空间锚菜单"));
        }

        private ActionStatus sendToggle(PlayerContext player) {
            if (!player.tryClaimInteraction()) return ActionStatus.running();
            if (!compat.sendConfigButton(SETTING_OVERLAY_MODE, false)) {
                return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "无法发送空间锚覆盖层切换包"));
            }
            togglesSent++;
            return enter(Stage.WAIT_CONFIRM);
        }

        private ActionStatus waitConfirm(PlayerContext player) {
            Optional<Ae2AdvancedReads.SpatialAnchorMenuStatus> status = reads.openSpatialAnchorMenu(
                    player.localPlayer().containerMenu);
            if (status.isPresent()) {
                seenMenu.put(at, status.get());
                if (targetMode.equals(status.get().overlayMode())) {
                    return closeWith(null);
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            if (togglesSent < 2) return enter(Stage.SEND_TOGGLE);
            return closeWith(Problem.of(Problem.Kind.STUCK, "发送覆盖层切换包后，空间锚未变成 " + targetMode));
        }

        private ActionStatus closeMenu(PlayerContext player) {
            MenuActions menuActions = player.menuActions();
            if (menuActions == null) return finish(problem);
            if (pendingClose == null) {
                pendingClose = menuActions.close(player, CLOSE_WAIT_TICKS);
                if (pendingClose == null) return ActionStatus.running();
            }
            pendingClose = menuActions.poll(player, pendingClose);
            if (pendingClose.status() == PendingMenuAction.Status.PENDING) return ActionStatus.running();
            pendingClose = null;
            return finish(problem);
        }

        private ActionStatus closeWith(Problem reason) {
            problem = reason;
            return enter(Stage.CLOSE);
        }

        private ActionStatus enter(Stage next) {
            if (current != null) current.close();
            current = null;
            stage = next;
            waited = 0;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem reason) {
            if (current != null) current.close();
            current = null;
            problem = reason;
            stage = Stage.FINISHED;
            return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
        }

        @Override public void pause() {
            if (current != null) current.pause();
        }

        @Override public void close() {
            if (current != null) current.close();
        }

        @Override public Interruptibility interruptibility() {
            return current != null ? current.interruptibility() : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "将空间锚的 overlay_mode 切为 " + targetMode;
        }
    }
}
