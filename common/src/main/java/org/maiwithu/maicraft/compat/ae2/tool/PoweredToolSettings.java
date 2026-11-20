// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 熵变仪、物质炮与充能法杖的只读物品设置（machine_inspect(item=...)）：
 * <ul>
 *   <li>熵变仪（ae2:entropy_manipulator）：只读 energy、energy_max，注明单次 1600 AE、点 TNT 不耗电与充能器补电提示；</li>
 *   <li>物质炮（ae2:matter_cannon）：只读 energy、energy_max、ammo、ammo_count、estimated_damage，注明经 ME 箱子或 ME IO 传输站装弹；</li>
 *   <li>充能法杖（ae2:charged_staff）：只读 energy、energy_max，注明单次 300 AE、满电附加 6 魔法伤害与低电补电提示。</li>
 * </ul>
 */
public final class PoweredToolSettings implements ItemSettings {

    public static final String ENTROPY_MANIPULATOR = "ae2:entropy_manipulator";
    public static final String CHARGED_STAFF = "ae2:charged_staff";
    /** 熵变仪单次加热/冷却耗电（AE）；点 TNT/迷你 TNT 不耗电。F 游戏事实。 */
    public static final double ENTROPY_ENERGY_PER_USE = 1600.0;
    /** 充能法杖单次近战附加魔法伤害耗电（AE）。F 游戏事实。 */
    public static final double STAFF_ENERGY_PER_HIT = 300.0;

    private static final Set<String> COVERED = Set.of(
            ENTROPY_MANIPULATOR,
            MatterCannonWeapon.MATTER_CANNON,
            CHARGED_STAFF);

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;
    private final Function<ItemStack, String> itemIdReader;

    public PoweredToolSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this(compat, reads, services, PortableCellCatalog::idOf);
    }

    PoweredToolSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services,
            Function<ItemStack, String> itemIdReader) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
        this.itemIdReader = Objects.requireNonNull(itemIdReader, "itemIdReader");
    }

    @Override public boolean covers(ItemStack stack) {
        return stack != null && !stack.isEmpty() && COVERED.contains(itemIdReader.apply(stack));
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        if (!covers(stack)) return List.of();
        String id = itemIdReader.apply(stack);
        return switch (id) {
            case ENTROPY_MANIPULATOR -> entropySettings(stack);
            case MatterCannonWeapon.MATTER_CANNON -> matterCannonSettings(stack);
            case CHARGED_STAFF -> chargedStaffSettings(stack);
            default -> List.of();
        };
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        return Optional.empty();
    }

    private List<MachineSetting> entropySettings(ItemStack stack) {
        Ae2ToolReads.EnergyReading energy = readEnergyOrZero(stack);
        String note = energy.current() < ENTROPY_ENERGY_PER_USE
                ? "电量不足（" + formatPair(energy) + "），单次加热/冷却耗电 1600 AE（点 TNT/迷你 TNT 不耗电）；请用充能器（ae2:charger）执行 machine_run 补电"
                : "当前电量（AE）；单次加热/冷却耗电 1600 AE，点 TNT/迷你 TNT 不耗电，低电时可用充能器（ae2:charger）执行 machine_run 补电";
        return List.of(
                readOnly("energy", PortableCellCatalog.formatAe(energy.current()), note),
                readOnly("energy_max", PortableCellCatalog.formatAe(energy.max()), "熵变仪电量上限（AE）"));
    }

    private List<MachineSetting> chargedStaffSettings(ItemStack stack) {
        Ae2ToolReads.EnergyReading energy = readEnergyOrZero(stack);
        String note = energy.current() < STAFF_ENERGY_PER_HIT
                ? "电量不足（" + formatPair(energy) + "），单次近战耗电 300 AE、满电附加 6 点魔法伤害；请用充能器（ae2:charger）执行 machine_run 补电"
                : "当前电量（AE）；单次近战耗电 300 AE，满电附加 6 点魔法伤害，低电时可用充能器（ae2:charger）执行 machine_run 补电";
        return List.of(
                readOnly("energy", PortableCellCatalog.formatAe(energy.current()), note),
                readOnly("energy_max", PortableCellCatalog.formatAe(energy.max()), "充能法杖电量上限（AE）"));
    }

    private List<MachineSetting> matterCannonSettings(ItemStack stack) {
        PlayerContext current = services.context().get();
        Level level = current == null ? null : current.level();
        Optional<Ae2ToolReads.MatterCannonSnapshot> snapshotOpt = compat.call("读取物质炮状态",
                () -> reads.readMatterCannon(stack, level));
        if (snapshotOpt.isEmpty()) {
            Ae2ToolReads.EnergyReading energy = readEnergyOrZero(stack);
            return List.of(
                    readOnly("energy", PortableCellCatalog.formatAe(energy.current()),
                            "当前电量（AE）；单发耗电 1600 AE，低电请用充能器（ae2:charger）补电"),
                    readOnly("energy_max", PortableCellCatalog.formatAe(energy.max()), "物质炮电量上限（AE）"),
                    readOnly("ammo", "empty", "通过 ME 箱子或 ME IO 传输站（FILL 模式）装填弹药"),
                    readOnly("ammo_count", "0", "内部弹药余量"),
                    readOnly("estimated_damage", "0", "估算单发伤害"));
        }
        Ae2ToolReads.MatterCannonSnapshot cannon = snapshotOpt.get();
        Ae2ToolReads.CannonAmmoEntry chosen = pickDisplayedAmmo(cannon);
        String ammoId = chosen == null ? "empty" : chosen.itemId();
        long ammoCount = chosen == null ? 0L : chosen.count();
        int estimatedDamage = MatterCannonWeapon.estimateDamage(cannon);
        String energyNote = cannon.currentEnergy() < MatterCannonWeapon.ENERGY_PER_SHOT
                ? "电量不足（" + PortableCellCatalog.formatAe(cannon.currentEnergy()) + "/"
                        + PortableCellCatalog.formatAe(cannon.maxEnergy())
                        + " AE），单发需 1600 AE；请用充能器（ae2:charger）执行 machine_run 补电"
                : "当前电量（AE）；单发耗电 1600 AE，低电时可用充能器（ae2:charger）执行 machine_run 补电";
        String ammoNote = chosen != null && chosen.paintBall()
                ? "当前装填的是彩弹（零伤害，远程战斗不会选用）；请通过 ME 箱子或 ME IO 传输站（FILL 模式）装填物质球或金属粒"
                : "当前弹药；请通过 ME 箱子或 ME IO 传输站（FILL 模式）装填（不能直接在背包塞入）";
        List<MachineSetting> out = new ArrayList<>();
        out.add(readOnly("energy", PortableCellCatalog.formatAe(cannon.currentEnergy()), energyNote));
        out.add(readOnly("energy_max", PortableCellCatalog.formatAe(cannon.maxEnergy()), "物质炮电量上限（AE）"));
        out.add(readOnly("ammo", ammoId, ammoNote));
        out.add(readOnly("ammo_count", Long.toString(ammoCount), "当前弹药余量"));
        out.add(readOnly("estimated_damage", Integer.toString(estimatedDamage),
                "按客户端配方权重与加速卡数估算的单发伤害（彩弹与低电为 0）"));
        return List.copyOf(out);
    }

    // 优先展示首个有效伤害弹药，否则展示首条已装物品（如彩弹）以便提醒用户。
    private static Ae2ToolReads.CannonAmmoEntry pickDisplayedAmmo(Ae2ToolReads.MatterCannonSnapshot cannon) {
        for (Ae2ToolReads.CannonAmmoEntry entry : cannon.ammoEntries()) {
            if (MatterCannonWeapon.isLethalAmmo(entry)) return entry;
        }
        for (Ae2ToolReads.CannonAmmoEntry entry : cannon.ammoEntries()) {
            if (entry.count() > 0L) return entry;
        }
        return null;
    }

    private Ae2ToolReads.EnergyReading readEnergyOrZero(ItemStack stack) {
        return compat.call("读取带电工具电量", () -> reads.readEnergy(stack))
                .orElse(new Ae2ToolReads.EnergyReading(0.0, 0.0));
    }

    private static String formatPair(Ae2ToolReads.EnergyReading energy) {
        return PortableCellCatalog.formatAe(energy.current()) + "/" + PortableCellCatalog.formatAe(energy.max()) + " AE";
    }

    private static MachineSetting readOnly(String key, String value, String description) {
        return new MachineSetting(key, value, List.of(), description);
    }
}
