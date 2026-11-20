// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 样板供应器（方块版 ae2:pattern_provider 与线缆部件版 ae2:cable_pattern_provider）补全委托：
 * 支持 blocking_mode、lock_crafting_mode、show_in_access_terminal、priority，方块版另支持
 * 石英扳手普通右键切换 push_direction；读数补齐 crafting_locked_reason 与 unlock_stack。
 * 方块版自动与包根既有的 pattern.&lt;item_id&gt; 合并共存。
 */
final class PatternProviderOps implements Ae2DeviceOps {

    static final List<String> YES_NO = List.of("yes", "no");
    static final List<String> LOCK_MODES = List.of("none", "until_pulse", "while_high", "while_low", "until_result");
    static final List<String> PUSH_DIRECTIONS = List.of("all", "north", "south", "east", "west", "up", "down");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;

    PatternProviderOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        Map<String, Object> readings = new LinkedHashMap<>();
        if (level != null) {
            reads.patternProviderPushDirection(level, pos).ifPresent(dir -> readings.put("push_direction", dir));
        }
        cache.provider(pos, null).ifPresent(cached -> appendProviderReadings(readings, cached));
        return readings;
    }

    static void appendProviderReadings(Map<String, Object> readings, Ae2MachineCache.ProviderCache cached) {
        if (cached.blockingMode() != null) readings.put("blocking_mode", cached.blockingMode());
        if (cached.lockCraftingMode() != null) readings.put("lock_crafting_mode", cached.lockCraftingMode());
        if (cached.showInAccessTerminal() != null) {
            readings.put("show_in_access_terminal", cached.showInAccessTerminal());
        }
        if (cached.priority() != null) readings.put("priority", (long) cached.priority());
        if (cached.craftingLockedReason() != null && !cached.craftingLockedReason().isEmpty()) {
            readings.put("crafting_locked_reason", cached.craftingLockedReason());
        }
        if (cached.unlockStack() != null && !cached.unlockStack().isEmpty()) {
            readings.put("unlock_stack", cached.unlockStack());
        }
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                PlayerContext ctx = services.context().get();
                String pushDir = ctx == null || ctx.level() == null ? "unknown"
                        : reads.patternProviderPushDirection(ctx.level(), at).orElse("unknown");
                return providerSettings(cache.provider(at, null), true, pushDir);
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return changeProvider(at, null, key, value, permissions, services);
            }
        });
    }

    /** 构造样板供应器设置清单：方块版含 push_direction，线缆部件版不含 push_direction。 */
    static List<MachineSetting> providerSettings(Optional<Ae2MachineCache.ProviderCache> cached,
            boolean isBlockVariant, String pushDirection) {
        List<MachineSetting> list = new ArrayList<>();
        list.add(new MachineSetting("blocking_mode",
                cached.map(Ae2MachineCache.ProviderCache::blockingMode).orElse("unknown"), YES_NO,
                "阻挡模式：yes 目标容器已有材料时暂不推送下一批"));
        list.add(new MachineSetting("lock_crafting_mode",
                cached.map(Ae2MachineCache.ProviderCache::lockCraftingMode).orElse("unknown"), LOCK_MODES,
                "锁合成模式：none / until_pulse / while_high / while_low / until_result"));
        list.add(new MachineSetting("show_in_access_terminal",
                cached.map(Ae2MachineCache.ProviderCache::showInAccessTerminal).orElse("unknown"), YES_NO,
                "是否在样板管理终端中显示"));
        list.add(new MachineSetting("priority",
                cached.map(Ae2MachineCache.ProviderCache::priority).map(String::valueOf).orElse("unknown"),
                List.of(), "ME 优先级（整数）"));
        if (isBlockVariant) {
            list.add(new MachineSetting("push_direction", pushDirection, PUSH_DIRECTIONS,
                    "推送方向：all 六面推送，或指定单面（石英扳手普通右键切换，推送面不再连网）"));
        }
        return List.copyOf(list);
    }

    /** 统一处理方块版与部件版样板供应器的设置修改；键不匹配返回 empty。 */
    Optional<Action> changeProvider(BlockPos host, Direction side, String key, String value,
            Permissions permissions, PlayerServices services) {
        BlockPos target = host.immutable();
        String label = side == null ? "样板供应器" : "样板供应器部件（" + side.getSerializedName() + "）";
        if ("blocking_mode".equals(key)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(configCycle(target, side, "blocking_mode", normalized, 3,
                    () -> reads.openPatternProviderMenu().map(Ae2MachineReads.PatternProviderMenuView::blockingMode),
                    label + "阻挡模式", permissions, services));
        }
        if ("lock_crafting_mode".equals(key)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!LOCK_MODES.contains(normalized)) return Optional.empty();
            return Optional.of(configCycle(target, side, "lock_crafting_mode", normalized, 6,
                    () -> reads.openPatternProviderMenu().map(Ae2MachineReads.PatternProviderMenuView::lockCraftingMode),
                    label + "锁合成模式", permissions, services));
        }
        if ("show_in_access_terminal".equals(key)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!YES_NO.contains(normalized)) return Optional.empty();
            return Optional.of(configCycle(target, side, "pattern_access_terminal", normalized, 3,
                    () -> reads.openPatternProviderMenu().map(Ae2MachineReads.PatternProviderMenuView::showInAccessTerminal),
                    label + "管理终端显示", permissions, services));
        }
        if ("priority".equals(key)) {
            Integer parsed = parseInteger(value);
            if (parsed == null) return Optional.empty();
            MenuOpening opening = Ae2MenuActionSupport.openBlockOrPart(target, side, reads, services, permissions);
            return Optional.of(new Ae2MenuActionSupport.PrioritySubMenuAction(
                    opening, compat, reads, parsed,
                    saved -> updatePriorityCache(target, side, saved), label));
        }
        if (side == null && "push_direction".equals(key)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!PUSH_DIRECTIONS.contains(normalized)) return Optional.empty();
            return Optional.of(new PushDirectionAction(target, normalized, reads, services, permissions));
        }
        return Optional.empty();
    }

    private Action configCycle(BlockPos target, Direction side, String settingName, String wanted, int maxCycles,
            Supplier<Optional<String>> reader, String label,
            Permissions permissions, PlayerServices services) {
        MenuOpening opening = Ae2MenuActionSupport.openBlockOrPart(target, side, reads, services, permissions);
        return new Ae2MenuActionSupport.ConfigCycleAction(opening, compat, settingName, wanted, maxCycles,
                reader, () -> recordProviderMenu(target, side), label);
    }

    private void recordProviderMenu(BlockPos target, Direction side) {
        reads.openPatternProviderMenu().ifPresent(view -> {
            Ae2MachineCache.ProviderCache old = cache.provider(target, side).orElse(null);
            cache.putProvider(target, side, new Ae2MachineCache.ProviderCache(
                    view.blockingMode(), view.lockCraftingMode(), view.showInAccessTerminal(),
                    old == null ? null : old.priority(),
                    view.craftingLockedReason(), view.unlockStack(),
                    old == null ? List.of() : old.patterns()));
        });
    }

    private void updatePriorityCache(BlockPos target, Direction side, int priority) {
        Ae2MachineCache.ProviderCache old = cache.provider(target, side).orElse(null);
        cache.putProvider(target, side, new Ae2MachineCache.ProviderCache(
                old == null ? null : old.blockingMode(),
                old == null ? null : old.lockCraftingMode(),
                old == null ? null : old.showInAccessTerminal(),
                priority,
                old == null ? null : old.craftingLockedReason(),
                old == null ? null : old.unlockStack(),
                old == null ? List.of() : old.patterns()));
    }

    /**
     * 计算方块版样板供应器从当前推送方向切到目标方向时下一步应该用石英扳手点击哪一面：
     * 依据 PatternProviderBlock.setSide 规则（ALL 点 F 变 F 反面、F 反面点 F 变 F、F 点 F 变 ALL），
     * 最多两步精准到达目标，无需盲目旋转。
     */
    static Optional<Direction> nextWrenchFace(String currentPush, String wantedPush) {
        if (currentPush == null || wantedPush == null || currentPush.equals(wantedPush)) return Optional.empty();
        Direction current = "all".equals(currentPush) ? null : Direction.byName(currentPush);
        Direction wanted = "all".equals(wantedPush) ? null : Direction.byName(wantedPush);
        if (wanted == null) {
            // 目标为 ALL：点击当前推送面一次即切回 ALL。
            return Optional.ofNullable(current);
        }
        if (current == null) {
            // 当前为 ALL：点击目标反面一次即切到目标面。
            return Optional.of(wanted.getOpposite());
        }
        if (current == wanted.getOpposite()) {
            // 当前为目标反面：点击目标面一次即切到目标面。
            return Optional.of(wanted);
        }
        // 其他单面状态：先点当前推送面回到 ALL，下一次再点目标反面直达目标面。
        return Optional.of(current);
    }

    private static Integer parseInteger(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 手持石英扳手普通右键方块版样板供应器切换 PUSH_DIRECTION 的动作。 */
    static final class PushDirectionAction implements Action {

        private static final int MAX_CLICKS = 4;

        private final BlockPos at;
        private final String wanted;
        private final Ae2MachineReads reads;
        private final PlayerServices services;
        private final Permissions permissions;

        private Action current;
        private Direction approachedFace;
        private int clicks;

        PushDirectionAction(BlockPos at, String wanted, Ae2MachineReads reads,
                PlayerServices services, Permissions permissions) {
            this.at = at.immutable();
            this.wanted = wanted;
            this.reads = reads;
            this.services = services;
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.level() == null || player.localPlayer() == null) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "角色不在世界里"));
            }
            Optional<String> now = reads.patternProviderPushDirection(player.level(), at);
            if (now.isEmpty()) {
                return ActionStatus.failed(Problem.of(Problem.Kind.TARGET_GONE, "这里已经不是方块版样板供应器"));
            }
            if (wanted.equals(now.get())) return ActionStatus.done();
            if (current != null) {
                ActionStatus status = current.tick(tick);
                if (status instanceof ActionStatus.Running) return status;
                current.close();
                current = null;
                if (status instanceof ActionStatus.Failed) return status;
                return ActionStatus.progressed();
            }
            if (!reads.isWrenchRotateTool(player.localPlayer().getMainHandItem())) {
                Optional<String> wrenchId = findWrenchInInventory(player, reads);
                if (wrenchId.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                            "切换样板供应器推送方向需要主手持石英扳手（ae2:certus_quartz_wrench 或 ae2:nether_quartz_wrench）"));
                }
                Optional<Action> hold = services.toMainhand().moveToMainhand(wrenchId.get());
                if (hold.isEmpty()) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "无法将石英扳手换到主手"));
                }
                current = hold.get();
                return ActionStatus.progressed();
            }
            Direction face = nextWrenchFace(now.get(), wanted).orElse(Direction.UP);
            // 每次点击的目标面改变时，重新靠近到看得见该面的站位，避免两次跨面点击时够不着第二面。
            if (approachedFace != face) {
                current = services.approach().toward(
                        ApproachTarget.ofBlockPart(at, FaceMenuOpening.faceSlice(at, face)), permissions);
                approachedFace = face;
                return ActionStatus.progressed();
            }
            if (clicks >= MAX_CLICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "石英扳手点击多次后样板供应器推送方向仍为 " + now.get() + "，未变为 " + wanted));
            }
            String before = now.get();
            InteractionConfirmation changed = ctx -> reads.patternProviderPushDirection(ctx.level(), at)
                    .map(cur -> cur.equals(before)
                            ? InteractionConfirmation.Verdict.PENDING
                            : InteractionConfirmation.Verdict.APPLIED)
                    .orElse(InteractionConfirmation.Verdict.APPLIED);
            current = services.interactions().useBlockFace(at, face, changed);
            clicks++;
            return ActionStatus.progressed();
        }

        private static Optional<String> findWrenchInInventory(PlayerContext player, Ae2MachineReads reads) {
            Inventory inv = player.localPlayer().getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && reads.isWrenchRotateTool(stack)) {
                    return Optional.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                }
            }
            return Optional.empty();
        }

        @Override public void pause() { if (current != null) current.pause(); }
        @Override public void close() { if (current != null) { current.close(); current = null; } }
        @Override public Interruptibility interruptibility() { return Interruptibility.WORKING; }
        @Override public String describe() { return "用石英扳手将样板供应器推送方向设为 " + wanted; }
    }
}
