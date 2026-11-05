// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Supplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.explore.ClientExplorationWorld;
import org.maiwithu.maicraft.behavior.explore.ColumnSample;
import org.maiwithu.maicraft.behavior.explore.OpenSky;
import org.maiwithu.maicraft.behavior.navigation.util.BlockPassability;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 出行现场的读端：从当刻的角色上下文读角色所在位置与水平朝向。
 *
 * <p>位置带维度；朝向只给东南西北之一，相对的前后左右由目的地解析自己换算。
 * 没有角色上下文时位置给 null，解析方以"不知道自己在哪"处理，不猜。
 */
public final class ClientTravelWorldView implements TravelWorldView {

    private final Supplier<PlayerContext> context;

    public ClientTravelWorldView(Supplier<PlayerContext> context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public WorldPosition currentSpot() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return null;
        }
        // 脚位和寻路引擎同一个口径：站在床、下半台阶、灵魂沙上算上面那一格。规划时判站到没有、每段停在哪
        // （legs 的 to）和走路段报的到达位置都按它，不会一个写床那一格、一个写床上面那一格。
        LocalPlayer player = current.localPlayer();
        BlockPos feet = BlockPassability.playerFeet(player.level(), player.getX(), player.getY(), player.getZ());
        return new WorldPosition(feet.getX(), feet.getY(), feet.getZ(), player.level().dimension().location().toString());
    }

    @Override
    public boolean standable(int x, int y, int z) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return true;
        BlockPos feet = new BlockPos(x, y, z);
        if (!current.level().isLoaded(feet)) return true;
        return BlockPassability.isStandable(current.level(), feet);
    }

    // 目的地周围一圈逐格问"寻路为什么不踩它"，问得出原因的就是危险格；没加载的格读出来是空气，跳过。
    @Override
    public List<HazardCell> hazardsNear(BlockPos center, int radius) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return List.of();
        List<HazardCell> found = new ArrayList<>();
        for (BlockPos cell : BlockPos.betweenClosed(center.offset(-radius, -1, -radius), center.offset(radius, 1, radius))) {
            if (!current.level().isLoaded(cell)) continue;
            BlockState state = current.level().getBlockState(cell);
            BlockPassability.whyNotStoodOn(current.level(), cell, state).ifPresent(why -> found.add(new HazardCell(
                    cell.immutable(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), why)));
        }
        return found;
    }

    @Override
    public boolean inWater() {
        PlayerContext current = context.get();
        return current != null && current.localPlayer() != null
                && current.localPlayer().isInWater();
    }

    @Override
    public OptionalInt surfaceFeet(int x, int z) {
        PlayerContext current = context.get();
        if (current == null || current.level() == null) return OptionalInt.empty();
        // 客户端世界的 hasChunkAt 永远答"加载着"，没加载的格读出来是空气；按逐列样本自己带 loaded 判。
        ColumnSample sample = ClientExplorationWorld.sample(current.level(), x, z);
        if (!sample.loaded()) return OptionalInt.empty();
        // 落点要在能站的干地上：水面列的顶格悬在水上、封顶建筑的顶格在屋顶，都不能只凭"顶上有方块"就当落点。
        // 挑法见 ColumnLanding：站不站得住按 BlockPassability 的干地事实判，头顶有没有遮挡按露天判断判。
        WorldPosition spot = currentSpot();
        int playerY = spot == null ? sample.topY() + 1 : spot.y();
        boolean underCover = spot != null && OpenSky.judge(
                ClientExplorationWorld.sample(current.level(), spot.x(), spot.z()), spot.y())
                instanceof OpenSky.Verdict.Buried;
        return ColumnLanding.feet(sample,
                y -> BlockPassability.isDryStandable(current.level(), new BlockPos(x, y, z)),
                playerY, underCover);
    }

    // 顶盖样本和探索逐列抽样读的是同一份：回没回到露天、哪儿是露天落脚处，两边按同一条事实判。
    @Override
    public ColumnSample column(int x, int z) {
        PlayerContext current = context.get();
        return ClientExplorationWorld.sample(current == null ? null : current.level(), x, z);
    }

    @Override
    public Target.Toward currentFacing() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null) {
            return null;
        }
        // "往前"按角色朝哪边解析：闲着转头看东西时仍是站定的那个方向，LLM 读观察时的前方不变。
        return towardOf(current.facingYaw());
    }

    /**
     * 视角角换成世界方位：0 朝南，顺时针增大，90 朝西。朝向只给东南西北之一，
     * 斜向按东西、南北哪个分量大靠到哪边；与观察视图共用同一套方位换算
     * （{@code DirectionWords}），出行说的方向和感知报的方向是同一套。纯函数，离线测试直接喂角度。
     */
    static Target.Toward towardOf(float facingYawDegrees) {
        // 前方向向量与方位词的换算共用感知的规则：dx = -sin(yaw)，dz = cos(yaw)。
        double dx = -Math.sin(Math.toRadians(facingYawDegrees));
        double dz = Math.cos(Math.toRadians(facingYawDegrees));
        // 东西分量大朝东西，南北分量大朝南北：45 度的斜向不会丢给默认值。
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Target.Toward.EAST : Target.Toward.WEST;
        }
        return dz > 0 ? Target.Toward.SOUTH : Target.Toward.NORTH;
    }
}
