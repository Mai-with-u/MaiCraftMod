// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.part;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineSettings;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.compat.ae2.Ae2DeviceOps;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 无线访问点（{@code ae2:wireless_access_point}）补全委托。
 *
 * <p>显式区分「已同步增幅卡数（来自服务端 {@code ae2.device} 或开窗缓存）」与「未同步」：
 * 未同步时绝不把客户端默认恒返的 16.0 当成真实覆盖范围；读到精确张数后，按 AE2 19.2.17
 * 精确公式计算 {@code range = 16 + boosters^1.5} 与 {@code power_drain = 8 + boosters^(1 + boosters / 64)}。
 * 同时提供 {@code boosters=<0..64>} 配置项，按差额向增幅卡槽放取 {@code ae2:wireless_booster}，
 * 完全保留已有无线终端绑定流程。
 */
public final class WirelessAccessPointOps implements Ae2DeviceOps {

    /** AE2 无线访问点基础覆盖范围（格，0 张增幅卡）。F 游戏事实。 */
    static final double BASE_RANGE = 16.0;
    /** AE2 无线访问点基础待机能耗（AE/t，0 张增幅卡）。F 游戏事实。 */
    static final double BASE_POWER_DRAIN = 8.0;
    static final String UNSYNCED_NOTE =
            "客户端未同步增幅卡数量，请勿把客户端默认的 16.0 格当作真实覆盖范围；打开访问点界面或启用服务端 ae2.device 后可读精确值";

    private final Ae2PartControl control;
    private final Ae2PartCache cache;

    public WirelessAccessPointOps(Ae2PartControl control, Ae2PartCache cache) {
        this.control = Objects.requireNonNull(control, "control");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    /** 按 AE2 精确公式计算装入 boosters 张无线增幅卡时的最大覆盖范围（格）：16 + boosters^1.5。 */
    public static double maxRange(int boosters) {
        int clamped = clampBoosters(boosters);
        return BASE_RANGE + Math.pow(clamped, 1.5);
    }

    /** 按 AE2 精确公式计算装入 boosters 张无线增幅卡时的能耗（AE/t）：8 + boosters^(1 + boosters / 64)。 */
    public static double powerDrain(int boosters) {
        int clamped = clampBoosters(boosters);
        return BASE_POWER_DRAIN + Math.pow(clamped, 1.0 + clamped / 64.0);
    }

    /** 构造无线访问点的额外读数表（显式区分已同步与未同步）。 */
    public Map<String, Object> readingsFor(BlockPos pos, ServerReads serverReads) {
        Map<String, Object> readings = new LinkedHashMap<>();
        OptionalInt boosters = cache.syncedBoosters(pos, serverReads);
        if (boosters.isPresent()) {
            int count = clampBoosters(boosters.getAsInt());
            readings.put("boosters_synced", true);
            readings.put("boosters", count);
            readings.put("range", maxRange(count));
            readings.put("power_drain", powerDrain(count));
        } else {
            readings.put("boosters_synced", false);
            readings.put("boosters", Ae2PartKeys.UNKNOWN);
            readings.put("range", Ae2PartKeys.UNKNOWN);
            readings.put("power_drain", Ae2PartKeys.UNKNOWN);
            readings.put("sync_note", UNSYNCED_NOTE);
        }
        return readings;
    }

    @Override public Map<String, Object> extraReadings(BlockPos pos, ClientLevel level) {
        return readingsFor(pos, ServerReads.NONE);
    }

    @Override public Optional<MachineSettings> settings(BlockPos pos, PlayerServices services) {
        return Optional.of(new AccessPointSettings(services));
    }

    private static int clampBoosters(int boosters) {
        return Math.max(0, Math.min(boosters, Ae2PartKeys.MAX_WIRELESS_BOOSTERS));
    }

    private static Optional<Action> invalid(String message) {
        return Optional.of(new Action() {
            @Override public ActionStatus tick(TickContext context) {
                return ActionStatus.failed(Problem.of(Problem.Kind.INVALID_PARAMETER, message));
            }
            @Override public String describe() {
                return message;
            }
        });
    }

    private final class AccessPointSettings implements MachineSettings {

        private final PlayerServices services;

        AccessPointSettings(PlayerServices services) {
            this.services = services;
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            OptionalInt boosters = cache.syncedBoosters(at, services.serverReads());
            String value = boosters.isPresent() ? Integer.toString(boosters.getAsInt()) : Ae2PartKeys.UNKNOWN;
            return List.of(new MachineSetting("boosters", value, List.of(),
                    "无线增幅卡张数（0..64；按差额放取 ae2:wireless_booster，不影响无线终端绑定）"));
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            if (!"boosters".equals(key)) return Optional.empty();
            int wanted;
            try {
                wanted = Integer.parseInt(value == null ? "" : value.trim());
            } catch (NumberFormatException ignored) {
                return invalid("boosters 必须为 0..64 的整数：" + value);
            }
            if (wanted < 0 || wanted > Ae2PartKeys.MAX_WIRELESS_BOOSTERS) {
                return invalid("boosters 超出范围（允许 0..64）：" + wanted);
            }
            return Optional.of(new WirelessBoosterChange(control, cache, services, at, wanted, permissions));
        }
    }
}
