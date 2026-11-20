// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.machine.spi.ItemSettings;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2Compat;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * AE2 陨石罗盘的显式只读物品查看（machine_inspect(item="ae2:meteorite_compass")）：
 * 仅在角色真实携带该罗盘时查询并输出只读项 compass_status 与（仅 pointing 时提供的）meteorite_direction，
 * 绝不透出精确距离格数与高度 Y 坐标。
 */
public final class MeteoriteCompassSettings implements ItemSettings {

    public static final String KEY_STATUS = "compass_status";
    public static final String KEY_DIRECTION = "meteorite_direction";

    private final Ae2Compat compat;
    private final Ae2ToolReads reads;
    private final PlayerServices services;

    public MeteoriteCompassSettings(Ae2Compat compat, Ae2ToolReads reads, PlayerServices services) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.services = Objects.requireNonNull(services, "services");
    }

    @Override public boolean covers(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && MeteoriteCompassSight.COMPASS_ITEM.equals(PortableCellCatalog.idOf(stack));
    }

    @Override public List<MachineSetting> settings(ItemStack stack) {
        if (!covers(stack)) return List.of();
        PlayerContext current = services.context().get();
        LocalPlayer self = current == null ? null : current.localPlayer();
        // 严格反透视：仅在角色在线且确实携带陨石罗盘时才向 CompassManager 查询。
        if (self == null || !MeteoriteCompassSight.carriesCompass(self)) {
            return List.of(new MachineSetting(KEY_STATUS, CompassReading.STATUS_SEARCHING_SPINNING, List.of(),
                    "陨石罗盘指针状态（pointing=指向 / nearby_spinning=6格内慢转 / searching_spinning=搜寻快转）"));
        }
        Optional<BlockPos> target = compat.call("查询陨石罗盘指向",
                () -> reads.closestMeteorite(self.blockPosition()));
        CompassReading reading = CompassReading.from(self.position(), target.orElse(null));
        List<MachineSetting> out = new ArrayList<>();
        out.add(new MachineSetting(KEY_STATUS, reading.status(), List.of(),
                "陨石罗盘指针状态（pointing=指向 / nearby_spinning=6格内慢转 / searching_spinning=搜寻快转）"));
        if (reading.hasDirection()) {
            out.add(new MachineSetting(KEY_DIRECTION, reading.direction(), List.of(),
                    "指针指向的八向水平方位（仅距离超过 6 格时提供，不含精确距离与高度）"));
        }
        return List.copyOf(out);
    }

    @Override public Optional<Action> change(int inventorySlot, Map<String, String> wanted, Permissions permissions) {
        return Optional.empty();
    }
}
