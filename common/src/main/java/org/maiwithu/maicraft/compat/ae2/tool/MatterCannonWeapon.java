// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.survival.defense.spi.RangedWeapon;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;

/**
 * AE2 物质炮（ae2:matter_cannon）的远程武器适配（实现 RangedWeapon）：
 * 弹药装在武器内部存储单元中（不消耗背包弹药格，usesInternalAmmo=true），
 * 仅在电量 ≥ 1600 AE 且内部存在至少 1 发有效伤害弹药（严格排除零伤 PaintBallItem 彩弹与权重 ≤ 0 物品）时提供瞬发直线弹道；
 * 发射确认经 shotFired 比较开火前后主手物质炮的 STORED_ENERGY 或内部弹药总数减少。
 */
public final class MatterCannonWeapon implements RangedWeapon {

    public static final String MATTER_CANNON = "ae2:matter_cannon";
    /** 物质炮单发最低耗电量（AE），对应 MatterCannonItem.ENERGY_PER_SHOT。F 游戏事实。 */
    public static final double ENERGY_PER_SHOT = 1600.0;
    /** 物质炮瞬发直线射线射程（格），对应 MatterCannonItem.use 的 255 格射线。F 游戏事实。 */
    public static final double RAY_RANGE = 255.0;
    /** 瞬发直线弹道：零重力、无阻力（drag=1.0）、从眼位直出、无方向偏移。 */
    public static final Ballistics INSTANT_RAY = new Ballistics(RAY_RANGE, 0.0, 1.0, false, Vec3.ZERO, Vec3.ZERO);

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final Predicate<ItemStack> weaponMatcher;

    public MatterCannonWeapon(Ae2Compat compat, Ae2ToolReads reads) {
        this(compat, reads, stack -> stack != null && !stack.isEmpty()
                && MATTER_CANNON.equals(PortableCellCatalog.idOf(stack)));
    }

    MatterCannonWeapon(Ae2Compat compat, Ae2ToolReads reads, Predicate<ItemStack> weaponMatcher) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.weaponMatcher = Objects.requireNonNull(weaponMatcher, "weaponMatcher");
    }

    @Override public boolean covers(ItemStack weapon) {
        return weapon != null && !weapon.isEmpty() && weaponMatcher.test(weapon);
    }

    @Override public boolean usesInternalAmmo() {
        return true;
    }

    @Override public Optional<Shot> nextShot(ItemStack weapon, Player player) {
        if (!covers(weapon) || player == null || player.level() == null) return Optional.empty();
        Optional<Ae2ToolReads.MatterCannonSnapshot> snapshot = compat.call("读取物质炮状态与弹药",
                () -> reads.readMatterCannon(weapon, player.level()));
        return snapshot.flatMap(MatterCannonWeapon::firstLethalShot);
    }

    @Override public List<Shot> possibleShots(ItemStack weapon, Player player) {
        if (!covers(weapon) || player == null || player.level() == null) return List.of();
        Optional<Ae2ToolReads.MatterCannonSnapshot> snapshot = compat.call("读取物质炮状态与弹药",
                () -> reads.readMatterCannon(weapon, player.level()));
        return snapshot.map(MatterCannonWeapon::allLethalShots).orElse(List.of());
    }

    // 物质炮弹药在武器内部且无原版冷却：比较开火前后主手物质炮电量减少或内部弹药总数减少即确认发射成功。
    @Override public boolean shotFired(ItemStack before, ItemStack after, Player player) {
        if (!covers(before) || !covers(after)) return false;
        Optional<Ae2ToolReads.EnergyReading> energyBefore = compat.call("读取开火前物质炮电量",
                () -> reads.readEnergy(before));
        Optional<Ae2ToolReads.EnergyReading> energyAfter = compat.call("读取开火后物质炮电量",
                () -> reads.readEnergy(after));
        if (energyBefore.isPresent() && energyAfter.isPresent()
                && energyAfter.get().current() + 1.0e-6 < energyBefore.get().current()) {
            return true;
        }
        long ammoBefore = compat.call("读取开火前物质炮弹药数", () -> reads.matterCannonTotalAmmo(before));
        long ammoAfter = compat.call("读取开火后物质炮弹药数", () -> reads.matterCannonTotalAmmo(after));
        return ammoAfter < ammoBefore;
    }

    /**
     * 按 AE2 MatterCannonItem.fireCannon 真实取弹顺序选出下一发弹药（纯函数）：
     * 游戏固定发射存储表里的第一条非空物品，绝不会跳过排在首位的彩弹或无权重杂物去打后面的铁粒；
     * 因此仅当电量 ≥ 1600 AE 且首条非空条目为有效致死弹药时才返回直线瞬发 Shot，否则返回空避免战斗空耗电打彩弹。
     */
    static Optional<Shot> firstLethalShot(Ae2ToolReads.MatterCannonSnapshot snapshot) {
        if (snapshot == null || snapshot.currentEnergy() < ENERGY_PER_SHOT) return Optional.empty();
        return firstNonEmptyEntry(snapshot)
                .filter(MatterCannonWeapon::isLethalAmmo)
                .map(MatterCannonWeapon::toShot);
    }

    /** 列出下一发实际可打出的有效伤害弹药（受 AE2 固定发射首条条目限制，首位非致死弹药时返回空）。 */
    static List<Shot> allLethalShots(Ae2ToolReads.MatterCannonSnapshot snapshot) {
        return firstLethalShot(snapshot).map(List::of).orElse(List.of());
    }

    /**
     * 按 AE2 MatterCannonItem 真实取弹顺序与伤害公式估算下一发单发伤害（纯函数）：
     * 仅看首条非空条目，首位为彩弹、无权重物品或电量不足时恒返回 0，否则返回 ceil(weight * shotPower / 20)。
     */
    public static int estimateDamage(Ae2ToolReads.MatterCannonSnapshot snapshot) {
        if (snapshot == null || snapshot.currentEnergy() < ENERGY_PER_SHOT) return 0;
        Optional<Ae2ToolReads.CannonAmmoEntry> firstOpt = firstNonEmptyEntry(snapshot);
        if (firstOpt.isEmpty() || !isLethalAmmo(firstOpt.get())) return 0;
        Ae2ToolReads.CannonAmmoEntry entry = firstOpt.get();
        int maxPower = 1 + Math.max(0, snapshot.speedCards());
        int byAmmo = (int) Math.min(Integer.MAX_VALUE, entry.count());
        int byEnergy = (int) (snapshot.currentEnergy() / ENERGY_PER_SHOT);
        int shotPower = Math.min(maxPower, Math.min(byAmmo, byEnergy));
        if (shotPower <= 0) return 0;
        return (int) Math.ceil((entry.weight() * shotPower) / 20.0);
    }

    static Optional<Ae2ToolReads.CannonAmmoEntry> firstNonEmptyEntry(Ae2ToolReads.MatterCannonSnapshot snapshot) {
        if (snapshot == null) return Optional.empty();
        for (Ae2ToolReads.CannonAmmoEntry entry : snapshot.ammoEntries()) {
            if (entry != null && entry.count() > 0L) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    static boolean isLethalAmmo(Ae2ToolReads.CannonAmmoEntry entry) {
        return entry != null && !entry.paintBall() && entry.count() > 0L && entry.weight() > 0.0f;
    }

    private static Shot toShot(Ae2ToolReads.CannonAmmoEntry entry) {
        return new Shot(entry.itemId(), -1, 0, 0, 1, INSTANT_RAY);
    }
}
