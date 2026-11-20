// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * AE2 上色器（ae2:color_applicator）的物品设置适配：
 * 客户端直读当前电量、上限电量、当前选中颜色（AEComponents.SELECTED_COLOR）与内部各色颜料余量；
 * 配置 color=&lt;ae_color|transparent&gt; 时将上色器换到主手并发送 ColorApplicatorSelectColorPacket，
 * 随后由读回核对 getActiveColor 是否已切换到目标颜色（仅包内已有该色颜料时游戏才接受切换）。
 */
public final class ColorApplicatorSettings implements ItemSettings {

    public static final String COLOR_APPLICATOR = "ae2:color_applicator";
    public static final String KEY_COLOR = "color";
    /** 单次染色耗电（AE）。F 游戏事实。 */
    public static final double ENERGY_PER_USE = 100.0;

    /** 上色器支持的全部 17 种颜色名（16 种染料色加 transparent 透明/清洗色）。 */
    public static final List<String> VALID_COLORS = List.of(
            "white", "light_gray", "gray", "black",
            "lime", "yellow", "orange", "brown",
            "red", "pink", "magenta", "purple",
            "blue", "light_blue", "cyan", "green",
            "transparent");
    private static final Set<String> VALID_COLOR_SET = Set.copyOf(VALID_COLORS);

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;
    private final Function<ItemStack, String> itemIdReader;

    public ColorApplicatorSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this(compat, reads, services, PortableCellCatalog::idOf);
    }

    ColorApplicatorSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services,
            Function<ItemStack, String> itemIdReader) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.itemIdReader = Objects.requireNonNull(itemIdReader, "itemIdReader");
    }

    @Override public boolean covers(ItemStack stack) {
        return stack != null && !stack.isEmpty() && COLOR_APPLICATOR.equals(itemIdReader.apply(stack));
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        if (!covers(stack)) return List.of();
        Optional<Ae2ToolReads.ColorApplicatorSnapshot> snapshotOpt = compat.call("读取上色器状态",
                () -> reads.readColorApplicator(stack));
        if (snapshotOpt.isEmpty()) return List.of();
        Ae2ToolReads.ColorApplicatorSnapshot applicator = snapshotOpt.get();
        String active = applicator.activeColor().isEmpty() ? "empty" : applicator.activeColor();
        String energyNote = applicator.currentEnergy() < ENERGY_PER_USE
                ? "电量不足（单次染色耗电 100 AE），请用充能器（ae2:charger）执行 machine_run 补电"
                : "当前电量（AE）；单次染色耗电 100 AE，低电时可用充能器（ae2:charger）补电";
        List<MachineSetting> out = new ArrayList<>();
        out.add(new MachineSetting("energy", PortableCellCatalog.formatAe(applicator.currentEnergy()),
                List.of(), energyNote));
        out.add(new MachineSetting("energy_max", PortableCellCatalog.formatAe(applicator.maxEnergy()),
                List.of(), "电量上限（AE）"));
        out.add(new MachineSetting("active_color", active, List.of(),
                "当前选中的染色颜色（只读）"));
        out.add(new MachineSetting(KEY_COLOR, active, VALID_COLORS,
                "切换当前颜色（仅包内已装该色颜料时生效；通过 ME 箱子或 ME IO 传输站 FILL 模式装填染料/彩弹/雪球）"));
        out.add(new MachineSetting("pigments", formatPigments(applicator.pigments()), List.of(),
                "上色器内部各色颜料余量（雪球与水对应 transparent 清洗色）"));
        return List.copyOf(out);
    }

    @Override public boolean holds(ItemStack stack, String key, String wanted) {
        if (KEY_COLOR.equals(key)) {
            Optional<String> normalized = normalizeColor(wanted);
            if (normalized.isEmpty()) return false;
            Optional<Ae2ToolReads.ColorApplicatorSnapshot> snapshot = compat.call("核对上色器当前颜色",
                    () -> reads.readColorApplicator(stack));
            return snapshot.map(s -> normalized.get().equals(s.activeColor())).orElse(false);
        }
        return ItemSettings.super.holds(stack, key, wanted);
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        if (wanted == null || wanted.size() != 1 || !wanted.containsKey(KEY_COLOR)) {
            return Optional.empty();
        }
        Optional<String> normalized = normalizeColor(wanted.get(KEY_COLOR));
        if (normalized.isEmpty()) {
            return Optional.of(invalidColorFailure(wanted.get(KEY_COLOR)));
        }
        return Optional.of(new SelectColorAction(inventorySlot, normalized.get()));
    }

    /** 将输入颜色名规范化为标准小写 AEColor 名称（支持 fluix 别名映射到 transparent）；无效时给空。 */
    public static Optional<String> normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if ("fluix".equals(lower)) return Optional.of("transparent");
        return VALID_COLOR_SET.contains(lower) ? Optional.of(lower) : Optional.empty();
    }

    static String formatPigments(Map<String, Long> pigments) {
        if (pigments == null || pigments.isEmpty()) return "empty";
        List<String> parts = new ArrayList<>(pigments.size());
        pigments.forEach((color, count) -> {
            if (count != null && count > 0L) parts.add(color + "=" + count);
        });
        return parts.isEmpty() ? "empty" : String.join(", ", parts);
    }

    private static Action invalidColorFailure(String raw) {
        return new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INVALID_PARAMETER,
                        "未知的上色器颜色：" + raw + "；可选值：" + String.join("、", VALID_COLORS)));
            }

            @Override public String describe() {
                return "校验上色器颜色参数";
            }
        };
    }

    /** 把上色器换到主手后发送 ColorApplicatorSelectColorPacket，并等待服务端同步 SELECTED_COLOR 确认切色生效。 */
    private final class SelectColorAction implements Action {

        private static final int SYNC_WAIT_TICKS = 20;

        private enum Stage { HAND, SEND, WAIT_SYNC, DONE }

        private final int slot;
        private final String colorName;
        private Stage stage = Stage.HAND;
        private Action handStep;
        private int waited;

        SelectColorAction(int slot, String colorName) {
            this.slot = slot;
            this.colorName = colorName;
        }

        @Override public ActionStatus tick(TickContext tick) {
            PlayerContext player = tick.player();
            if (player == null || player.localPlayer() == null) return ActionStatus.running();
            return switch (stage) {
                case HAND -> moveToHand(tick, player.localPlayer());
                case SEND -> sendPacket(player.localPlayer());
                case WAIT_SYNC -> waitColorSync(player.localPlayer());
                case DONE -> ActionStatus.done();
            };
        }

        private ActionStatus moveToHand(TickContext tick, LocalPlayer self) {
            if (handStep == null) {
                handStep = PortableCellCatalog.moveSlotToMainhand(services, self, slot, COLOR_APPLICATOR).orElse(null);
                if (handStep == null) {
                    stage = Stage.SEND;
                    return ActionStatus.progressed();
                }
            }
            ActionStatus status = handStep.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            handStep.close();
            handStep = null;
            if (status instanceof ActionStatus.Failed failed) {
                return ActionStatus.failed(Problem.of(failed.problem().kind(),
                        "没能把上色器拿到主手：" + failed.problem().message()));
            }
            stage = Stage.SEND;
            return ActionStatus.progressed();
        }

        private ActionStatus sendPacket(LocalPlayer self) {
            boolean sent = compat.call("发送上色器切色包", () -> reads.selectApplicatorColor(colorName));
            if (!sent) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "发送上色器切换颜色 " + colorName + " 请求失败"));
            }
            if (holds(self.getMainHandItem(), KEY_COLOR, colorName)) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            waited = 0;
            stage = Stage.WAIT_SYNC;
            return ActionStatus.progressed();
        }

        // 切色网络包异步生效：等待服务端回传更新后的 SELECTED_COLOR，若超时未变说明包内没有该色颜料被游戏忽略。
        private ActionStatus waitColorSync(LocalPlayer self) {
            if (holds(self.getMainHandItem(), KEY_COLOR, colorName)) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            if (++waited > SYNC_WAIT_TICKS) {
                return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "上色器未能切换到颜色 " + colorName + "（包内可能没有装填该色颜料）"));
            }
            return ActionStatus.running();
        }

        @Override public void pause() {
            if (handStep != null) handStep.pause();
        }

        @Override public void close() {
            if (handStep != null) {
                handStep.close();
                handStep = null;
            }
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.WORKING;
        }

        @Override public String describe() {
            return "将上色器颜色切换为 " + colorName;
        }
    }
}
