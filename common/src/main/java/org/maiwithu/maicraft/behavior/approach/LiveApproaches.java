// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.approach;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 靠近的组装：把站位判断、走向站位与走到拼成一个"带我靠近"的动作。
 *
 * <p>距离数值每刻从角色的属性读（交互距离被装备撑大后站位判断跟着变）；
 * 走过去的代价在寻路接缝上回答不了时按直线距离排——靠近只靠它挑先试哪个站位，
 * 走不走得到由真的走路决定。候选站位全不过时，站位补救为只是被一两格挡住视线的位置挖开遮挡（许可允许时）。
 */
public final class LiveApproaches implements BringsPlayerClose {

    /** 服务端对床允许的距离档：站位与床底面中心各轴的偏移不超过它。 */
    private static final int BED_DISTANCE = 3;

    private final Supplier<PlayerContext> context;
    private final ApproachWorldView world;
    private final WalkTo walks;
    private final PermissionCheck permission;
    private final DigToolRule tools;
    private final UnreachableFromHere unreachable;
    private final OthersThingsNotice othersNotice;
    private final DoubleSupplier serverPadding;

    /**
     * @param tools       站位补救挖遮挡时拿哪件工具
     * @param unreachable 记得的"从这里到不了"：每次靠近共用这一份，同一个目标运行里不再重算
     */
    public LiveApproaches(Supplier<PlayerContext> context, ApproachWorldView world, WalkTo walks,
                          PermissionCheck permission, DigToolRule tools, UnreachableFromHere unreachable) {
        this(context, world, walks, permission, tools, unreachable,
                OthersThingsNotice.NONE, () -> InteractionRange.VANILLA_SERVER_PADDING);
    }

    /** 生产接入动别人东西的通报器：站位补救顺手拆掉别人放的遮挡格时一并记进连拆账。 */
    public LiveApproaches(Supplier<PlayerContext> context, ApproachWorldView world, WalkTo walks,
                          PermissionCheck permission, DigToolRule tools, UnreachableFromHere unreachable,
                          OthersThingsNotice othersNotice) {
        this(context, world, walks, permission, tools, unreachable,
                othersNotice, () -> InteractionRange.VANILLA_SERVER_PADDING);
    }

    /**
     * @param othersNotice  动别人东西的通报器
     * @param serverPadding 本次连接服务端实际放行的交互距离余量（握手确认的新服务端为 2.0 格，否则为原版 1.0 格）
     */
    public LiveApproaches(Supplier<PlayerContext> context, ApproachWorldView world, WalkTo walks,
                          PermissionCheck permission, DigToolRule tools, UnreachableFromHere unreachable,
                          OthersThingsNotice othersNotice, DoubleSupplier serverPadding) {
        this.tools = Objects.requireNonNull(tools, "tools");
        this.unreachable = Objects.requireNonNull(unreachable, "unreachable");
        this.context = Objects.requireNonNull(context, "context");
        this.world = Objects.requireNonNull(world, "world");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.permission = Objects.requireNonNull(permission, "permission");
        this.othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        this.serverPadding = Objects.requireNonNull(serverPadding, "serverPadding");
    }

    @Override
    public Action toward(ApproachTarget target, Permissions permissions) {
        return toward(target, permissions, at -> false);
    }

    @Override
    public Action toward(ApproachTarget target, Permissions permissions, ProtectedCells avoid) {
        return toward(target, permissions, avoid, null);
    }

    @Override
    public Action toward(ApproachTarget target, Permissions permissions, TaskRecords records) {
        return toward(target, permissions, at -> false, records);
    }

    private Action toward(ApproachTarget target, Permissions permissions, ProtectedCells avoid,
            TaskRecords records) {
        // 每个靠近动作各走各的一趟：路上能动多少地形按这次任务的许可折算成地形许可，
        // 靠近动作与站位补救都拿同一份走到入口与许可自己去上路，暂停与收尾互不牵连。
        // 调用方不许踩的格（放方块时的目标格）并进受保护格判断，能不能挖某一格由走到实现方把关。
        ReachRules reach = reachNow();
        TerrainPermit permit = TerrainPermit.of(permissions);
        StandOpener opener = new LiveStandOpener(context, permission, permissions, reach, walks, permit, avoid, tools,
                othersNotice);
        return new Approach(target, reach, world, LiveApproaches::straightCost,
                avoid, walks, permit, opener, permissions, records, unreachable);
    }

    /** 当刻的距离数值：交互距离读角色属性，眼高按当时姿态取，到达余量读握手状态。 */
    private ReachRules reachNow() {
        double padding = serverPadding.getAsDouble();
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            // 没有角色上下文时给不出真实属性；按原版徒手触达给保底值，站位判断后面还会再核对。
            return new ReachRules(4.5, 3.0, BED_DISTANCE, 1.62, padding);
        }
        var player = current.localPlayer();
        double blockRange = player.blockInteractionRange();
        double entityRange = player.entityInteractionRange();
        return new ReachRules(blockRange, entityRange, BED_DISTANCE, player.getEyeHeight(), padding);
    }

    /** 直线距离的代价：只用来给候选站位排序，走不走得到由真的走路决定。 */
    private static OptionalDouble straightCost(BlockPos from, BlockPos spot) {
        double dx = spot.getX() - from.getX();
        double dy = spot.getY() - from.getY();
        double dz = spot.getZ() - from.getZ();
        return OptionalDouble.of(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }
}
