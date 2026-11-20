// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.advanced;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 量子环与量子链接仓的设备操作委托：同时覆盖 {@code ae2:quantum_ring} 与中心 {@code ae2:quantum_link}，
 * 负责边中点接线口过滤、客户端/服务端合并读数，以及中心链接仓打开 {@code ae2:qnb} 放取缠绕奇点的配置动作。
 */
public final class QuantumLink implements Ae2DeviceOps {

    /** 中心链接仓配置键：放进或取出量子缠绕奇点。 */
    public static final String SETTING_SINGULARITY = "singularity";
    /** 放入缠绕奇点的设置值。 */
    public static final String VALUE_IN = "in";
    /** 取出缠绕奇点的设置值。 */
    public static final String VALUE_OUT = "out";

    private final Ae2Compat compat;
    private final Ae2AdvancedReads reads;
    private final Ae2DeviceReadings serverReadings;
    /** 本次会话中在打开 ae2:qnb 界面后读到的中心链接仓奇点频率（0 表示刚确认清空）。 */
    private final Map<BlockPos, Long> seenFrequency = new HashMap<>();

    public QuantumLink(Ae2Compat compat, Ae2AdvancedReads reads, Ae2DeviceReadings serverReadings) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.serverReadings = Objects.requireNonNull(serverReadings, "serverReadings");
    }

    @Override public Optional<List<ExchangePoint>> exchangePoints(BlockState state, BlockPos at, ClientLevel level) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Optional<Ae2AdvancedReads.QuantumCell> cell = level == null ? Optional.empty() : reads.quantum(level, at);
        return Optional.of(QuantumRing.exchangePoints(blockId, at, cell));
    }

    @Override public Optional<MachineState> state(BlockPos pos, ClientLevel level) {
        if (level == null) return Optional.empty();
        Optional<Ae2AdvancedReads.QuantumCell> cell = reads.quantum(level, pos);
        if (cell.isEmpty()) return Optional.empty();
        String blockId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        Optional<JsonObject> serverReply = serverReadings.latest(pos, null);
        Optional<QuantumRing.StructureReview> review = cell.get().center()
                ? Optional.of(QuantumRing.reviewStructure(pos,
                        p -> isBlock(level, p, QuantumRing.RING_BLOCK),
                        p -> isBlock(level, p, QuantumRing.LINK_BLOCK)))
                : Optional.empty();
        Long cached = seenFrequency.get(pos.immutable());
        return Optional.of(QuantumRing.state(blockId, cell.get(), serverReply,
                cached != null && cached != 0L ? Optional.of(cached) : Optional.empty(), review));
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new LinkSettings(services));
    }

    private static boolean isBlock(ClientLevel level, BlockPos pos, String expectedId) {
        if (!level.isLoaded(pos)) return false;
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(expectedId);
    }

    /** 中心量子链接仓的设置项实现：支持 singularity=in|out。 */
    private final class LinkSettings implements MachineSettings {

        private final PlayerServices services;

        private LinkSettings(PlayerServices services) {
            this.services = services;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            PlayerContext current = services.context().get();
            ClientLevel level = current == null ? null : current.level();
            if (level == null || !isBlock(level, at, QuantumRing.LINK_BLOCK)) {
                return List.of();
            }
            Optional<Ae2AdvancedReads.QuantumCell> cell = reads.quantum(level, at);
            String value = currentSettingValue(at, cell);
            return List.of(new MachineSetting(SETTING_SINGULARITY, value, List.of(VALUE_IN, VALUE_OUT),
                    "在中心量子链接仓（ae2:qnb）放进或取出 1 枚带频率的量子缠绕奇点"));
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            if (!SETTING_SINGULARITY.equals(key)) return Optional.empty();
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!VALUE_IN.equals(normalized) && !VALUE_OUT.equals(normalized)) return Optional.empty();
            PlayerContext current = services.context().get();
            ClientLevel level = current == null ? null : current.level();
            if (level == null || !isBlock(level, at, QuantumRing.LINK_BLOCK)) return Optional.empty();
            return Optional.of(new SingularityAction(at.immutable(), VALUE_IN.equals(normalized), permissions, services));
        }

        private String currentSettingValue(BlockPos at, Optional<Ae2AdvancedReads.QuantumCell> cell) {
            Long cached = seenFrequency.get(at.immutable());
            if (cached != null) {
                return cached != 0L ? VALUE_IN : VALUE_OUT;
            }
            if (cell.isEmpty()) return "unknown";
            return cell.get().hasSingularity() ? VALUE_IN : VALUE_OUT;
        }
    }

    /** 打开中心量子链接仓（ae2:qnb）放入或取出 1 枚带频率缠绕奇点的动作。 */
    private final class SingularityAction implements Action {

        private enum Stage { HAND, OPEN, ACT, WAIT, CLOSE, FINISHED }

        /** 等待界面槽位同步与放取生效的刻数上限。F 游戏事实。 */
        private static final int SYNC_WAIT_TICKS = 40;

        private final BlockPos at;
        private final boolean wantInside;
        private final Permissions permissions;
        private final PlayerServices services;

        private Stage stage = Stage.HAND;
        private Action current;
        private MenuOpening opening;
        private OpenedMenu menu;
        private int waited;
        private Problem problem;

        private SingularityAction(BlockPos at, boolean wantInside, Permissions permissions, PlayerServices services) {
            this.at = at;
            this.wantInside = wantInside;
            this.permissions = permissions;
            this.services = services;
        }

        @Override public ActionStatus tick(TickContext context) {
            return switch (stage) {
                case HAND -> clearHand(context);
                case OPEN -> openMenu(context);
                case ACT -> performMove();
                case WAIT -> waitSync();
                case CLOSE -> closeMenu(context);
                case FINISHED -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        // 腾空主手后再点中心量子链接仓，避免手持扳手误拆。
        private ActionStatus clearHand(TickContext context) {
            if (current == null) {
                Optional<Action> empty = services.toMainhand().emptyMainhand();
                if (empty.isEmpty()) {
                    stage = Stage.OPEN;
                    return ActionStatus.progressed();
                }
                current = empty.get();
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current.close();
            current = null;
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        private ActionStatus openMenu(TickContext context) {
            if (opening == null) {
                opening = new ClientMenuOpening(at, permissions, services.menuLayouts(), services.approach(),
                        services.interactions(), services.context());
            }
            ActionStatus status = opening.tick(context);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "量子链接仓界面没打开"));
            stage = Stage.ACT;
            return ActionStatus.progressed();
        }

        // 定位 STORAGE 奇点槽：核对当前状态，已经符合要求则直接记缓存并关窗；否则快速移入或移出 1 枚带频率缠绕奇点。
        private ActionStatus performMove() {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isEmpty()) {
                if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
                return closeWith(Problem.of(Problem.Kind.STUCK, "量子链接仓界面打开后槽位一直没同步"));
            }
            MenuContent.Reading reading = readingOpt.get();
            int containerSlotIndex = resolveSingularitySlot(reading);
            if (containerSlotIndex < 0) {
                return closeWith(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "在量子链接仓界面里认不出奇点槽位"));
            }
            SlotSnapshot slotSnap = snapshotForSlotId(reading, containerSlotIndex);
            boolean hasInside = slotSnap != null && !slotSnap.isEmpty();
            recordSeenSlot(slotSnap);
            if (hasInside == wantInside) return closeWith(null);

            int moveSlot = wantInside ? findCarriedSingularitySlot(reading) : containerSlotIndex;
            if (moveSlot < 0) {
                return closeWith(Problem.of(Problem.Kind.NEED_ITEM,
                        "背包里没有带有效频率的量子缠绕奇点（ae2:quantum_entangled_singularity）",
                        "先用 obtain（via=ae2_explosion_transform）炸出一对同频量子缠绕奇点"));
            }
            if (!menu.quickMove(moveSlot)) return ActionStatus.running();
            waited = 0;
            stage = Stage.WAIT;
            return ActionStatus.progressed();
        }

        private ActionStatus waitSync() {
            Optional<MenuContent.Reading> readingOpt = menu.reading();
            if (readingOpt.isPresent()) {
                MenuContent.Reading reading = readingOpt.get();
                int slotId = resolveSingularitySlot(reading);
                SlotSnapshot snap = snapshotForSlotId(reading, slotId);
                boolean hasInside = snap != null && !snap.isEmpty();
                if (hasInside == wantInside) {
                    recordSeenSlot(snap);
                    return closeWith(null);
                }
            }
            if (++waited <= SYNC_WAIT_TICKS) return ActionStatus.running();
            return closeWith(Problem.of(Problem.Kind.STUCK,
                    (wantInside ? "放进" : "取出") + "量子缠绕奇点后，链接仓槽位一直没变化"));
        }

        private ActionStatus closeMenu(TickContext context) {
            if (current == null) current = menu.closing();
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current = null;
            return finish(problem);
        }

        // 优先走 AE2 槽位语义 STORAGE，没有读写端时回退到容器侧第一个槽。
        private int resolveSingularitySlot(MenuContent.Reading reading) {
            List<Integer> semantic = compat.menuSlotsOf("STORAGE");
            for (int id : semantic) {
                if (reading.containerSlotIds().contains(id)) return id;
            }
            return reading.containerSlotIds().isEmpty() ? -1 : reading.containerSlotIds().getFirst();
        }

        private SlotSnapshot snapshotForSlotId(MenuContent.Reading reading, int slotId) {
            for (int i = 0; i < reading.containerSlotIds().size(); i++) {
                if (reading.containerSlotIds().get(i) == slotId) {
                    return reading.containerSnapshots().get(i);
                }
            }
            return null;
        }

        private int findCarriedSingularitySlot(MenuContent.Reading reading) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot snap = reading.playerSnapshots().get(i);
                if (snap.isEmpty()) continue;
                String id = BuiltInRegistries.ITEM.getKey(snap.stack().getItem()).toString();
                if (!QuantumRing.ENTANGLED_SINGULARITY.equals(id)) continue;
                Optional<Long> freq = reads.entangledSingularityId(snap.stack());
                if (freq.isPresent() && freq.get() != 0L) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        private void recordSeenSlot(SlotSnapshot snap) {
            if (snap == null || snap.isEmpty()) {
                seenFrequency.put(at, 0L);
                return;
            }
            long freq = reads.entangledSingularityId(snap.stack()).orElse(1L);
            seenFrequency.put(at, freq == 0L ? 1L : freq);
        }

        private ActionStatus closeWith(Problem reason) {
            problem = reason;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        private ActionStatus finish(Problem reason) {
            problem = reason;
            stage = Stage.FINISHED;
            return reason == null ? ActionStatus.done() : ActionStatus.failed(reason);
        }

        @Override public void pause() {
            if (current != null) current.pause();
            if (opening != null && menu == null) opening.pause();
        }

        @Override public void close() {
            if (current != null) current.close();
            if (menu != null && stage != Stage.FINISHED) menu.abandon();
            else if (opening != null && menu == null) opening.close();
        }

        @Override public Interruptibility interruptibility() {
            return current != null ? current.interruptibility() : Interruptibility.WORKING;
        }

        @Override public String describe() {
            return (wantInside ? "往量子链接仓放入" : "从量子链接仓取出") + "缠绕奇点";
        }
    }
}
