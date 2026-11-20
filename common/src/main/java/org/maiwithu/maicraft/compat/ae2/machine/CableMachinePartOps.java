// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.compat.ae2.Ae2Grid;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 线缆格（ae2:cable_bus）上三大自动合成部件的操作委托：
 * 专门负责样板编码终端（ae2:pattern_encoding_terminal）、样板管理终端（ae2:pattern_access_terminal）
 * 与线缆版样板供应器（ae2:cable_pattern_provider）的按面读数、partSettings（不带面缀）
 * 与 settings/change（带 &lt;side&gt;. 前缀），供汇总层与其他线缆部件合并接入。
 */
final class CableMachinePartOps implements Ae2DeviceOps {

    static final String ENCODING_TERMINAL = "ae2:pattern_encoding_terminal";
    static final String ACCESS_TERMINAL = "ae2:pattern_access_terminal";
    static final String CABLE_PATTERN_PROVIDER = "ae2:cable_pattern_provider";

    static final List<String> SHOW_PROVIDERS_VALUES = List.of("visible", "not_full", "all");
    private static final List<String> BOOL_VALUES = List.of("true", "false");

    private final Ae2Compat compat;
    private final Ae2MachineReads reads;
    private final Ae2MachineCache cache;
    private final PatternProviderOps providerOps;

    CableMachinePartOps(Ae2Compat compat, Ae2MachineReads reads, Ae2MachineCache cache,
            PatternProviderOps providerOps) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.cache = Objects.requireNonNull(cache, "cache");
        this.providerOps = Objects.requireNonNull(providerOps, "providerOps");
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        if (level == null) return Map.of();
        Optional<Ae2Grid.GridCell> cell = compat.gridCell(level, pos);
        if (cell.isEmpty() || !cell.get().cableBus()) return Map.of();
        Map<String, Object> readings = new LinkedHashMap<>();
        for (Map.Entry<Direction, Ae2Grid.PartView> entry : cell.get().parts().entrySet()) {
            String prefix = entry.getKey().getSerializedName() + ".";
            String partId = entry.getValue().itemId();
            if (CABLE_PATTERN_PROVIDER.equals(partId)) {
                cache.provider(pos, entry.getKey()).ifPresent(cached -> {
                    Map<String, Object> sub = new LinkedHashMap<>();
                    PatternProviderOps.appendProviderReadings(sub, cached);
                    sub.forEach((k, v) -> readings.put(prefix + k, v));
                });
            } else if (ENCODING_TERMINAL.equals(partId)) {
                cache.encoding(pos, entry.getKey()).ifPresent(cached -> {
                    readings.put(prefix + "encode_mode", cached.mode());
                    readings.put(prefix + "substitute", cached.substitute());
                    readings.put(prefix + "substitute_fluids", cached.substituteFluids());
                    if (cached.lastOutput() != null && !cached.lastOutput().isEmpty()) {
                        readings.put(prefix + "last_encoded_output", cached.lastOutput());
                    }
                });
            } else if (ACCESS_TERMINAL.equals(partId)) {
                cache.access(pos, entry.getKey()).ifPresent(cached ->
                        readings.put(prefix + "show_providers", cached.showProviders()));
            }
        }
        return readings;
    }

    // 查看部件时：返回该面部件不带 <side>. 前缀的局部设置列表，直接填入 DeviceView.settings。
    @Override public List<MachineSetting> partSettings(BlockPos host, Direction side, PlayerServices services) {
        String partId = partIdAt(host, side, services);
        if (partId == null) return List.of();
        return localSettingsFor(host, side, partId, services);
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new MachineSettings() {
            @Override public List<MachineSetting> settings(BlockPos at) {
                PlayerContext ctx = services.context().get();
                if (ctx == null || ctx.level() == null) return List.of();
                Optional<Ae2Grid.GridCell> cell = compat.gridCell(ctx.level(), at);
                if (cell.isEmpty() || !cell.get().cableBus()) return List.of();
                List<MachineSetting> prefixed = new ArrayList<>();
                for (Direction side : Direction.values()) {
                    Ae2Grid.PartView part = cell.get().parts().get(side);
                    if (part == null) continue;
                    String prefix = side.getSerializedName() + ".";
                    for (MachineSetting local : localSettingsFor(at, side, part.itemId(), services)) {
                        prefixed.add(new MachineSetting(prefix + local.key(), local.value(),
                                local.choices(), local.description()));
                    }
                }
                return List.copyOf(prefixed);
            }

            @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
                return routePrefixedChange(at.immutable(), key, value, permissions, services);
            }

            @Override public List<MachineSetting> partSettings(BlockPos host, Direction side) {
                return CableMachinePartOps.this.partSettings(host, side, services);
            }
        });
    }

    /** 按部件类型构造不带面缀的局部设置列表。 */
    List<MachineSetting> localSettingsFor(BlockPos host, Direction side, String partId, PlayerServices services) {
        return switch (partId) {
            case ENCODING_TERMINAL -> encodingSettings(cache.encoding(host, side));
            case ACCESS_TERMINAL -> accessSettings(cache.access(host, side));
            case CABLE_PATTERN_PROVIDER -> cableProviderSettings(host, side, services);
            default -> List.of();
        };
    }

    static List<MachineSetting> encodingSettings(Optional<Ae2MachineCache.EncodingCache> cached) {
        return List.of(
                new MachineSetting("encode_mode",
                        cached.map(Ae2MachineCache.EncodingCache::mode).orElse("unknown"),
                        PatternEncodingRequest.MODES,
                        "编码模式：crafting / processing / smithing / stonecutting"),
                new MachineSetting("substitute",
                        cached.map(v -> String.valueOf(v.substitute())).orElse("unknown"),
                        BOOL_VALUES, "是否允许替换等价物品"),
                new MachineSetting("substitute_fluids",
                        cached.map(v -> String.valueOf(v.substituteFluids())).orElse("unknown"),
                        BOOL_VALUES, "是否允许替换流体容器"),
                new MachineSetting("stonecutting_recipe",
                        cached.map(v -> v.stonecuttingRecipeId() == null ? "none" : v.stonecuttingRecipeId())
                                .orElse("unknown"),
                        List.of(), "切石模式配方 ID（如 minecraft:stone_bricks_from_stone_stonecutting）"),
                new MachineSetting("encode_pattern",
                        cached.map(v -> v.lastRequest() == null || v.lastRequest().isEmpty()
                                ? "ready" : v.lastRequest()).orElse("ready"),
                        List.of(),
                        "完整编码请求 JSON：{mode, inputs, outputs, substitute, substitute_fluids, stonecutting_recipe}"));
    }

    static List<MachineSetting> accessSettings(Optional<Ae2MachineCache.AccessCache> cached) {
        return List.of(
                new MachineSetting("show_providers",
                        cached.map(Ae2MachineCache.AccessCache::showProviders).orElse("unknown"),
                        SHOW_PROVIDERS_VALUES,
                        "样板管理终端过滤模式：visible / not_full / all"),
                new MachineSetting("put_pattern",
                        cached.map(v -> v.lastPutPattern() == null ? "ready" : v.lastPutPattern()).orElse("ready"),
                        List.of(),
                        "快速放入样板：<target_group_name>:<output_item_id>"));
    }

    private List<MachineSetting> cableProviderSettings(BlockPos host, Direction side, PlayerServices services) {
        List<MachineSetting> list = new ArrayList<>(
                PatternProviderOps.providerSettings(cache.provider(host, side), false, "none"));
        Set<String> outputs = new LinkedHashSet<>();
        cache.provider(host, side).ifPresent(c -> outputs.addAll(c.patterns()));
        outputs.addAll(carriedPatternOutputs(services));
        Optional<List<String>> inside = cache.provider(host, side).map(Ae2MachineCache.ProviderCache::patterns);
        for (String out : outputs) {
            String cur = inside.map(l -> l.contains(out) ? "in" : "out").orElse("unknown");
            list.add(new MachineSetting("pattern." + out, cur, List.of("in", "out"),
                    "产出 " + out + " 的样板：in 放入该面样板供应器部件，out 取回背包"));
        }
        return List.copyOf(list);
    }

    private Optional<Action> routePrefixedChange(BlockPos host, String fullKey, String value,
            Permissions permissions, PlayerServices services) {
        int dot = fullKey == null ? -1 : fullKey.indexOf('.');
        if (dot <= 0 || dot >= fullKey.length() - 1) return Optional.empty();
        Direction side = Direction.byName(fullKey.substring(0, dot).toLowerCase(Locale.ROOT));
        if (side == null) return Optional.empty();
        String localKey = fullKey.substring(dot + 1);
        String partId = partIdAt(host, side, services);
        if (partId == null) return Optional.empty();
        return switch (partId) {
            case ENCODING_TERMINAL -> changeEncoding(host, side, localKey, value, permissions, services);
            case ACCESS_TERMINAL -> changeAccess(host, side, localKey, value, permissions, services);
            case CABLE_PATTERN_PROVIDER -> changeCableProvider(host, side, localKey, value, permissions, services);
            default -> Optional.empty();
        };
    }

    private Optional<Action> changeEncoding(BlockPos host, Direction side, String localKey, String value,
            Permissions permissions, PlayerServices services) {
        if ("encode_pattern".equals(localKey)) {
            PatternEncodingRequest.ParseResult parsed = PatternEncodingRequest.parse(value);
            if (!parsed.ok()) return Optional.empty();
            return Optional.of(PatternEncodingAction.encode(host, side, parsed.request(), value,
                    compat, reads, cache, services, permissions));
        }
        if ("encode_mode".equals(localKey)) {
            String normalized = PatternEncodingRequest.normalizeMode(value);
            if (!PatternEncodingRequest.MODES.contains(normalized)) return Optional.empty();
            return Optional.of(PatternEncodingAction.singleSetting(host, side, localKey, normalized,
                    compat, reads, cache, services, permissions));
        }
        if ("substitute".equals(localKey) || "substitute_fluids".equals(localKey)) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (!BOOL_VALUES.contains(lower)) return Optional.empty();
            return Optional.of(PatternEncodingAction.singleSetting(host, side, localKey, lower,
                    compat, reads, cache, services, permissions));
        }
        if ("stonecutting_recipe".equals(localKey) && !value.isBlank()) {
            return Optional.of(PatternEncodingAction.singleSetting(host, side, localKey, value.trim(),
                    compat, reads, cache, services, permissions));
        }
        return Optional.empty();
    }

    private Optional<Action> changeAccess(BlockPos host, Direction side, String localKey, String value,
            Permissions permissions, PlayerServices services) {
        if ("show_providers".equals(localKey)) {
            String normalized = value.toLowerCase(Locale.ROOT);
            if (!SHOW_PROVIDERS_VALUES.contains(normalized)) return Optional.empty();
            return Optional.of(new Ae2MenuActionSupport.ConfigCycleAction(
                    Ae2MenuActionSupport.openBlockOrPart(host, side, reads, services, permissions),
                    compat, "show_pattern_providers", normalized, 4,
                    () -> reads.openPatternAccessMenu().map(Ae2MachineReads.PatternAccessMenuView::showProviders),
                    () -> reads.openPatternAccessMenu().ifPresent(v -> cache.putAccess(host, side,
                            new Ae2MachineCache.AccessCache(v.showProviders(), ""))),
                    "样板管理终端显示过滤"));
        }
        if ("put_pattern".equals(localKey)) {
            Optional<PatternAccessAction.PutTarget> parsed = PatternAccessAction.PutTarget.parse(value);
            if (parsed.isEmpty()) return Optional.empty();
            return Optional.of(new PatternAccessAction(host, side, parsed.get(), value,
                    compat, reads, cache, services, permissions));
        }
        return Optional.empty();
    }

    private Optional<Action> changeCableProvider(BlockPos host, Direction side, String localKey, String value,
            Permissions permissions, PlayerServices services) {
        if (localKey.startsWith("pattern.") && localKey.length() > "pattern.".length()) {
            String outputId = localKey.substring("pattern.".length());
            boolean wantIn = "in".equalsIgnoreCase(value);
            boolean wantOut = "out".equalsIgnoreCase(value);
            if (!wantIn && !wantOut) return Optional.empty();
            return Optional.of(new CablePatternSwapAction(host, side, outputId, wantIn,
                    Ae2MenuActionSupport.openBlockOrPart(host, side, reads, services, permissions),
                    compat, cache));
        }
        return providerOps.changeProvider(host, side, localKey, value, permissions, services);
    }

    /** 线缆版样板供应器放取样板动作：遍历全部 9 个样板槽而非写死槽 0，绝不顶掉已有其他样板。 */
    private static final class CablePatternSwapAction implements Action {

        private enum Stage { OPEN, READ, WAIT, CLOSE, DONE }

        private final BlockPos host;
        private final Direction side;
        private final String output;
        private final boolean wantInside;
        private final MenuOpening opening;
        private final Ae2Compat compat;
        private final Ae2MachineCache cache;

        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int waited;
        private Problem problem;

        CablePatternSwapAction(BlockPos host, Direction side, String output, boolean wantInside,
                MenuOpening opening, Ae2Compat compat, Ae2MachineCache cache) {
            this.host = host.immutable();
            this.side = side;
            this.output = output;
            this.wantInside = wantInside;
            this.opening = opening;
            this.compat = compat;
            this.cache = cache;
        }

        @Override public ActionStatus tick(TickContext tick) {
            ClientLevel level = tick.player() == null ? null : tick.player().level();
            return switch (stage) {
                case OPEN -> open(tick);
                case READ -> read(level);
                case WAIT -> await(level);
                case CLOSE -> closeMenu(tick);
                case DONE -> problem == null ? ActionStatus.done() : ActionStatus.failed(problem);
            };
        }

        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) return finish(failed.problem());
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElse(null);
            if (menu == null) return finish(Problem.of(Problem.Kind.TARGET_GONE, "线缆版样板供应器界面没打开"));
            waited = 0;
            stage = Stage.READ;
            return ActionStatus.progressed();
        }

        private ActionStatus read(ClientLevel level) {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "线缆版样板供应器界面同步超时"));
            }
            List<String> inside = readOutputs(reading.get().containerSnapshots(), level);
            savePatterns(inside);
            if (inside.contains(output) == wantInside) return beginClose(null);
            int slot = wantInside ? findPlayerPattern(reading.get(), level) : findContainerPattern(reading.get(), level);
            if (slot < 0) {
                return beginClose(Problem.of(Problem.Kind.NEED_ITEM, "背包里没有产出 " + output + " 的编码样板"));
            }
            if (wantInside && reading.get().containerSnapshots().stream().noneMatch(SlotSnapshot::isEmpty)) {
                return beginClose(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, "线缆版样板供应器 9 个样板格已满"));
            }
            if (!menu.quickMove(slot)) {
                if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
                return beginClose(Problem.of(Problem.Kind.STUCK, "无法移动线缆版样板供应器的样板槽"));
            }
            waited = 0;
            stage = Stage.WAIT;
            return ActionStatus.progressed();
        }

        private ActionStatus await(ClientLevel level) {
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isPresent()) {
                List<String> inside = readOutputs(reading.get().containerSnapshots(), level);
                savePatterns(inside);
                if (inside.contains(output) == wantInside) return beginClose(null);
            }
            if (++waited <= Ae2MenuActionSupport.SYNC_WAIT_TICKS) return ActionStatus.running();
            return beginClose(Problem.of(Problem.Kind.STUCK, "等待线缆版样板供应器样板移动确认超时"));
        }

        private List<String> readOutputs(List<SlotSnapshot> snaps, ClientLevel level) {
            List<String> list = new ArrayList<>();
            for (SlotSnapshot snap : snaps) {
                if (!snap.isEmpty()) compat.patternOutput(snap.stack(), level).ifPresent(list::add);
            }
            return List.copyOf(list);
        }

        private int findPlayerPattern(MenuContent.Reading reading, ClientLevel level) {
            for (int i = 0; i < reading.playerSnapshots().size(); i++) {
                SlotSnapshot s = reading.playerSnapshots().get(i);
                if (!s.isEmpty() && compat.patternOutput(s.stack(), level).map(output::equals).orElse(false)) {
                    return reading.playerSlotIds().get(i);
                }
            }
            return -1;
        }

        private int findContainerPattern(MenuContent.Reading reading, ClientLevel level) {
            for (int i = 0; i < reading.containerSnapshots().size(); i++) {
                SlotSnapshot s = reading.containerSnapshots().get(i);
                if (!s.isEmpty() && compat.patternOutput(s.stack(), level).map(output::equals).orElse(false)) {
                    return reading.containerSlotIds().get(i);
                }
            }
            return -1;
        }

        private void savePatterns(List<String> inside) {
            Ae2MachineCache.ProviderCache old = cache.provider(host, side).orElse(null);
            cache.putProvider(host, side, new Ae2MachineCache.ProviderCache(
                    old == null ? null : old.blockingMode(),
                    old == null ? null : old.lockCraftingMode(),
                    old == null ? null : old.showInAccessTerminal(),
                    old == null ? null : old.priority(),
                    old == null ? null : old.craftingLockedReason(),
                    old == null ? null : old.unlockStack(),
                    inside));
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
            return (wantInside ? "放进" : "拿出") + "线缆版样板供应器里产出 " + output + " 的样板";
        }
    }

    private String partIdAt(BlockPos host, Direction side, PlayerServices services) {
        PlayerContext ctx = services.context().get();
        if (ctx == null || ctx.level() == null) return null;
        return compat.gridCell(ctx.level(), host)
                .filter(Ae2Grid.GridCell::cableBus)
                .map(cell -> cell.parts().get(side))
                .map(Ae2Grid.PartView::itemId)
                .orElse(null);
    }

    private Set<String> carriedPatternOutputs(PlayerServices services) {
        PlayerContext ctx = services.context().get();
        if (ctx == null || ctx.localPlayer() == null || ctx.level() == null) return Set.of();
        Set<String> outputs = new LinkedHashSet<>();
        Inventory inv = ctx.localPlayer().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) compat.patternOutput(stack, ctx.level()).ifPresent(outputs::add);
        }
        return outputs;
    }
}
