// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.interaction.StepsInOrder;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 内存卡（ae2:memory_card）的物品设置适配：
 * 客户端直读全部 12 项网络同步的 AEComponents，暴露只读项 source、custom_name、priority、
 * p2p_type、p2p_frequency、color、settings_summary、upgrades_count；
 * 配置 clear=true 时将内存卡换到主手并潜行对空右键清空全部已导出设置与颜色组件。
 */
public final class MemoryCardSettings implements ItemSettings {

    public static final String MEMORY_CARD = "ae2:memory_card";
    public static final String KEY_CLEAR = "clear";

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;
    private final Function<ItemStack, String> itemIdReader;

    public MemoryCardSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this(compat, reads, services, PortableCellCatalog::idOf);
    }

    MemoryCardSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services,
            Function<ItemStack, String> itemIdReader) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.itemIdReader = Objects.requireNonNull(itemIdReader, "itemIdReader");
    }

    @Override public boolean covers(ItemStack stack) {
        return stack != null && !stack.isEmpty() && MEMORY_CARD.equals(itemIdReader.apply(stack));
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        if (!covers(stack)) return List.of();
        Optional<Ae2ToolReads.MemoryCardSnapshot> snapshotOpt = compat.call("读取内存卡组件",
                () -> reads.readMemoryCard(stack));
        if (snapshotOpt.isEmpty()) return List.of();
        Ae2ToolReads.MemoryCardSnapshot card = snapshotOpt.get();
        List<MachineSetting> out = new ArrayList<>();
        out.add(readOnly("source", card.source().isEmpty() ? "empty" : card.source(),
                "导出配置的源设备类型"));
        out.add(readOnly("custom_name", card.customName(), "导出的自定义设备名称"));
        out.add(readOnly("priority", card.priority() == null ? "" : Integer.toString(card.priority()),
                "导出的优先级"));
        out.add(readOnly("p2p_type", card.p2pType(), "导出的 P2P 通道类型物品 ID"));
        out.add(readOnly("p2p_frequency", card.p2pFrequency(), "导出的 P2P 频率四位十六进制编码"));
        out.add(readOnly("color", card.color().isEmpty() ? "default" : card.color(),
                "内存卡颜色编码"));
        out.add(readOnly("settings_summary", buildSettingsSummary(card),
                "导出的配置项、过滤槽、发信器阈值、样板与推送方向汇总"));
        out.add(readOnly("upgrades_count", Integer.toString(card.upgradesCount()),
                "导出的升级卡总张数"));
        out.add(new MachineSetting(KEY_CLEAR, card.hasAnyExported() ? "false" : "true", List.of("true"),
                "设为 true 时主手潜行对空右键清空内存卡全部已导出组件"));
        return List.copyOf(out);
    }

    @Override public boolean holds(ItemStack stack, String key, String wanted) {
        if (KEY_CLEAR.equals(key) && "true".equalsIgnoreCase(wanted)) {
            Optional<Ae2ToolReads.MemoryCardSnapshot> card = compat.call("核对内存卡是否已清空",
                    () -> reads.readMemoryCard(stack));
            return card.map(snapshot -> !snapshot.hasAnyExported()).orElse(false);
        }
        return ItemSettings.super.holds(stack, key, wanted);
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        if (wanted == null || wanted.size() != 1 || !"true".equalsIgnoreCase(wanted.get(KEY_CLEAR))) {
            return Optional.empty();
        }
        // 主手持有内存卡 -> 潜行对空右键触发 MemoryCardItem.use 清空全部导出组件，核对内存卡变空确认生效。
        return Optional.of(new StepsInOrder("清空内存卡已保存配置", List.of(
                new StepsInOrder.Step("把内存卡拿到主手", () -> moveToMainhand(inventorySlot)),
                new StepsInOrder.Step("潜行对空右键清空内存卡", () -> services.sneak().sneaking(
                        services.interactions().pressUseKeyIntoAir(cardClearedConfirmation()))))));
    }

    /** 将 12 项导出组件里除独立键以外的细节拼成精炼中文汇总（纯函数，离线单测可直接覆盖）。 */
    static String buildSettingsSummary(Ae2ToolReads.MemoryCardSnapshot card) {
        List<String> parts = new ArrayList<>();
        if (!card.exportedSettings().isEmpty()) {
            List<String> entries = new ArrayList<>(card.exportedSettings().size());
            card.exportedSettings().forEach((k, v) -> entries.add(k + "=" + v));
            parts.add("配置(" + String.join(", ", entries) + ")");
        }
        if (card.configFilterCount() > 0) {
            parts.add("过滤槽 " + card.configFilterCount() + " 项");
        }
        if (card.levelEmitterValue() != null) {
            parts.add("发信阈值 " + card.levelEmitterValue());
        }
        if (card.exportedPatterns() > 0) {
            parts.add("样板 " + card.exportedPatterns() + " 张");
        }
        if (!card.pushDirection().isEmpty()) {
            parts.add("推送方向 " + card.pushDirection());
        }
        return parts.isEmpty() ? "empty" : String.join("；", parts);
    }

    private Action moveToMainhand(int inventorySlot) {
        PlayerContext current = services.context().get();
        LocalPlayer self = current == null ? null : current.localPlayer();
        if (self == null) return null;
        return PortableCellCatalog.moveSlotToMainhand(services, self, inventorySlot, MEMORY_CARD).orElse(null);
    }

    private InteractionConfirmation cardClearedConfirmation() {
        return ctx -> {
            if (ctx.localPlayer() == null) return InteractionConfirmation.Verdict.PENDING;
            ItemStack main = ctx.localPlayer().getMainHandItem();
            if (!covers(main)) return InteractionConfirmation.Verdict.DIVERGED;
            boolean cleared = compat.call("核对内存卡清空结果", () -> reads.readMemoryCard(main))
                    .map(snapshot -> !snapshot.hasAnyExported())
                    .orElse(false);
            return cleared ? InteractionConfirmation.Verdict.APPLIED : InteractionConfirmation.Verdict.PENDING;
        };
    }

    private static MachineSetting readOnly(String key, String value, String description) {
        return new MachineSetting(key, value, List.of(), description);
    }
}
