// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * AE2 陨石罗盘的场景感知适配（实现 ModSight）：
 * 严格遵守反透视底线——每轮先核查角色主手、副手与背包中是否真实携带 ae2:meteorite_compass，
 * 未携带时立即返回且绝不调用 CompassManager；仅在携带时查询并按玩家可见的 3 种指针状态
 * 向 observe(scene).mod_things 输出种类 meteorite_compass，绝不透出精确距离格数与 Y 坐标。
 */
public final class MeteoriteCompassSight implements ModSight {

    public static final String COMPASS_ITEM = "ae2:meteorite_compass";
    public static final String CATEGORY = "meteorite_compass";

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final Predicate<ItemStack> compassMatcher;

    public MeteoriteCompassSight(Ae2Compat compat, Ae2ToolReads reads) {
        this(compat, reads, MeteoriteCompassSight::isCompass);
    }

    MeteoriteCompassSight(Ae2Compat compat, Ae2ToolReads reads, Predicate<ItemStack> compassMatcher) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.compassMatcher = Objects.requireNonNull(compassMatcher, "compassMatcher");
    }

    @Override public void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        if (level == null || viewer == null || sink == null) return;
        Optional<JsonObject> summary = buildSummary(carriesCompass(viewer, compassMatcher),
                viewer.position(), viewer.blockPosition());
        if (summary.isEmpty()) return;
        BlockPos at = viewer.blockPosition();
        WorldPosition position = new WorldPosition(at.getX(), at.getY(), at.getZ(),
                level.dimension().location().toString());
        sink.accept(new ModThing(CATEGORY, ModThing.Identity.ENTITY, Integer.toString(viewer.getId()),
                position, summary.get(), null));
    }

    /**
     * 反透视门控与场景摘要构造：仅在 carryingCompass 为真时查询 CompassManager，
     * 返回只含 status 与（仅 pointing 时含）direction 的摘要对象，绝不透出精确距离与 Y 坐标。
     */
    Optional<JsonObject> buildSummary(boolean carryingCompass, Vec3 playerPos, BlockPos playerBlockPos) {
        // 严格反透视：未真实携带陨石罗盘时绝不调用 CompassManager，直接返回空。
        if (!carryingCompass) return Optional.empty();
        Optional<BlockPos> target = compat.call("查询陨石罗盘指向", () -> reads.closestMeteorite(playerBlockPos));
        CompassReading reading = CompassReading.from(playerPos, target.orElse(null));
        JsonObject summary = new JsonObject();
        summary.addProperty("status", reading.status());
        if (reading.hasDirection()) {
            summary.addProperty("direction", reading.direction());
        }
        return Optional.of(summary);
    }

    /** 检查角色主手、副手或背包中是否真实携带陨石罗盘。 */
    public static boolean carriesCompass(Player player) {
        return carriesCompass(player, MeteoriteCompassSight::isCompass);
    }

    static boolean carriesCompass(Player player, Predicate<ItemStack> matcher) {
        if (player == null) return false;
        if (matcher.test(player.getMainHandItem()) || matcher.test(player.getOffhandItem())) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (matcher.test(inventory.items.get(slot))) return true;
        }
        return false;
    }

    private static boolean isCompass(ItemStack stack) {
        return stack != null && !stack.isEmpty() && COMPASS_ITEM.equals(PortableCellCatalog.idOf(stack));
    }
}
