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
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 样板管理终端（ae2:pattern_access_terminal）快速送入样板动作：
 * 解析 "&lt;target_group_name&gt;:&lt;output_item_id&gt;"，打开 ae2:patternaccessterminal，
 * 经公开 PatternSlot 读取可见容器分组（绝不反射 PatternAccessTermScreen），
 * 选中同组容器 serverId 列表后发 QuickMovePatternPacket 送入并核对背包样板减少 1 张再关窗。
 */
final class PatternAccessAction implements Action {

    private enum Stage { OPEN, SEND, WAIT_MOVED, CLOSE, DONE }

    /** 解析后的送入目标：目标分组名与样板主产物 ID。 */
    record PutTarget(String groupName, String outputItemId) {
        static Optional<PutTarget> parse(String raw) {
            if (raw == null || raw.isBlank()) return Optional.empty();
            // 物品 ID 自身含冒号（如 minecraft:iron_ingot），先按第一个冒号切出分组名，前提是右边还含冒号或非空。
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep >= raw.length() - 1) return Optional.empty();
            String group = raw.substring(0, sep).trim();
            String output = raw.substring(sep + 1).trim();
            if (group.isEmpty() || output.isEmpty()) return Optional.empty();
            return Optional.of(new PutTarget(group, output));
        }
    }

    private final BlockPos host;
    private final Direction side;
    private final PutTarget target;
    private final String rawValue;
    private final MenuOpening opening;
    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    private Stage stage = Stage.OPEN;
    private OpenedMenu menu;
    private Action closing;
    private int sourcePlayerSlot = -1;
    private int waited;
    private Problem problem;

    PatternAccessAction(BlockPos host, Direction side, PutTarget target, String rawValue,
            Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache,
            PlayerServices services, Permissions permissions) {
        this(host, side, target, rawValue,
                Ae2MenuActionSupport.openBlockOrPart(host, side, reads, services, permissions),
                compat, reads, cache);
    }

    PatternAccessAction(BlockPos host, Direction side, PutTarget target, String rawValue, MenuOpening opening,
            Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.host = host.immutable();
        this.side = side;
        this.target = Objects.requireNonNull(target, "target");
        this.rawValue = Objects.requireNonNull(rawValue, "rawValue");
        this.opening = Objects.requireNonNull(opening, "opening");
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public ActionStatus tick(TickContext tick) {
        ClientLevel level = tick.player() == null ? null : tick.player().level();
        return switch (stage) {
            case OPEN -> open(tick);
            case SEND -> sendQuickMove(level);
            case WAIT_MOVED -> waitMoved();
            case CLOSE -> closeMenu(tick);
            case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
        };
    }

    private ActionStatus open(TickContext tick) {
        ActionStatus status = opening.tick(tick);
        if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
        if (!(status instanceof ActionStatus.Done)) return status;
        menu = opening.opened().orElse(null);
        if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "样板管理终端界面没打开"));
        waited = 0;
        stage = Stage.SEND;
        return ActionStatus.progressed();
    }

    private ActionStatus sendQuickMove(ClientLevel level) {
        Optional<Ae2MachineReads.PatternAccessMenuView> view = reads.openPatternAccessMenu();
        Optional<MenuContent.Reading> reading = menu.reading();
        if (view.isEmpty() || reading.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "样板管理终端界面同步超时"));
        }
        if (!view.get().linked()) {
            return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "样板管理终端未连上 ME 网络"));
        }
        sourcePlayerSlot = findPatternSlot(reading.get(), level);
        if (sourcePlayerSlot < 0) {
            return beginClose(Problem.of(Problem.Kind.NEED_ITEM,
                    "背包里没有产出 " + target.outputItemId() + " 的单张编码样板"));
        }
        // QuickMovePatternPacket 服务端硬校验所有 allowedIds 必须同属一个 PatternContainerGroup。
        List<Long> allowedIds = selectGroupServerIds(view.get().visibleContainers(), target.groupName());
        if (allowedIds.isEmpty()) {
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.NOT_FOUND,
                    "样板管理终端当前可见容器中找不到分组 " + target.groupName()
                            + "（可直接对目标样板供应器调用 machine_configure）"));
        }
        if (!reads.sendQuickMovePattern(view.get().containerId(), sourcePlayerSlot, allowedIds)) {
            return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME, "发送快速送入样板包失败"));
        }
        waited = 0;
        stage = Stage.WAIT_MOVED;
        return ActionStatus.progressed();
    }

    private ActionStatus waitMoved() {
        Optional<MenuContent.Reading> reading = menu.reading();
        if (reading.isPresent()) {
            SlotSnapshot after = playerSlotSnapshot(reading.get(), sourcePlayerSlot);
            if (after.isEmpty()) {
                String shown = reads.openPatternAccessMenu()
                        .map(Ae2MachineReads.PatternAccessMenuView::showProviders).orElse("visible");
                cache.putAccess(host, side, new Ae2MachineCache.AccessCache(shown, rawValue));
                return beginClose(null);
            }
        }
        if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
        return beginClose(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                "样板未能送入分组 " + target.groupName() + "（目标供应器可能已满，或分子组装室/机器样板类型不匹配）"));
    }

    /**
     * 从可见容器列表挑选匹配目标分组名的同一组 serverId 列表：
     * 优先精确匹配（忽略大小写），其次按子串命中第一个匹配组，确保所有 ID 严格属于同一组。
     */
    static List<Long> selectGroupServerIds(List<Ae2MachineReads.VisiblePatternContainer> containers,
            String wantedGroup) {
        if (containers == null || containers.isEmpty() || wantedGroup == null || wantedGroup.isBlank()) {
            return List.of();
        }
        String needle = wantedGroup.trim().toLowerCase(Locale.ROOT);
        Map<String, List<Long>> byExactGroup = new LinkedHashMap<>();
        for (Ae2MachineReads.VisiblePatternContainer c : containers) {
            if (c.groupName() == null) continue;
            byExactGroup.computeIfAbsent(c.groupName(), ignored -> new ArrayList<>()).add(c.serverId());
        }
        for (Map.Entry<String, List<Long>> entry : byExactGroup.entrySet()) {
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(needle)) {
                return List.copyOf(entry.getValue());
            }
        }
        for (Map.Entry<String, List<Long>> entry : byExactGroup.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(needle)) {
                return List.copyOf(entry.getValue());
            }
        }
        return List.of();
    }

    private int findPatternSlot(MenuContent.Reading reading, ClientLevel level) {
        for (int i = 0; i < reading.playerSnapshots().size(); i++) {
            SlotSnapshot snap = reading.playerSnapshots().get(i);
            if (snap.isEmpty() || snap.count() != 1) continue;
            if (compat.patternOutput(snap.stack(), level).map(target.outputItemId()::equals).orElse(false)) {
                return reading.playerSlotIds().get(i);
            }
        }
        return -1;
    }

    private static SlotSnapshot playerSlotSnapshot(MenuContent.Reading reading, int slotId) {
        for (int i = 0; i < reading.playerSlotIds().size(); i++) {
            if (reading.playerSlotIds().get(i) == slotId) return reading.playerSnapshots().get(i);
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
        return "经样板管理终端向 " + target.groupName() + " 放入产出 " + target.outputItemId() + " 的样板";
    }
}
