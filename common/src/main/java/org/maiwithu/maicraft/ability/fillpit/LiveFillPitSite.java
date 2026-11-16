// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.fillpit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.ItemsToKeep;
import org.maiwithu.maicraft.behavior.inventory.hands.ClientMovesToMainhand;
import org.maiwithu.maicraft.behavior.navigation.FillerPlacement;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.navigation.goal.GoalCompiler;
import org.maiwithu.maicraft.behavior.perception.CampPits;
import org.maiwithu.maicraft.behavior.perception.ClientCampPitSight;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 填坑任务的客户端现场实现：扫描复用感知同一份营地坑读端，回填方块只取身上扣除预留后的现成废料。
 */
final class LiveFillPitSite implements FillPitSeams.Site {

    private static final double REACH_SQ = 4.2 * 4.2;

    private final Supplier<Scene> scene;
    private final WorldMemory memory;
    private final Protection protection;
    private final ClientCampPitSight campPits;
    private final WalkTo walks;
    private final Interactions interactions;
    private final ClientMovesToMainhand toMainhand;
    private final ItemsToKeep kept;

    LiveFillPitSite(Supplier<Scene> scene, WorldMemory memory, Protection protection,
                    ClientCampPitSight campPits, WalkTo walks, Interactions interactions,
                    ClientMovesToMainhand toMainhand, ItemsToKeep kept) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.protection = Objects.requireNonNull(protection, "protection");
        this.campPits = Objects.requireNonNull(campPits, "campPits");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.interactions = Objects.requireNonNull(interactions, "interactions");
        this.toMainhand = Objects.requireNonNull(toMainhand, "toMainhand");
        this.kept = Objects.requireNonNull(kept, "kept");
    }

    @Override
    public BlockPos feet(TickContext context) {
        LocalPlayer self = self(context);
        return self == null ? BlockPos.ZERO : self.blockPosition();
    }

    @Override
    public String dimension(TickContext context) {
        ClientLevel level = level(context);
        return level == null ? null : level.dimension().location().toString();
    }

    @Override
    public Optional<WorldPosition> seen(String seenId) {
        Scene current = scene.get();
        return current == null ? Optional.empty() : current.lookup(seenId).map(SeenRegistry.Entry::position);
    }

    @Override
    public Optional<WorldPosition> landmark(String name) {
        return memory.place(name);
    }

    @Override
    public List<SceneSign> signs() {
        Scene current = scene.get();
        return current == null ? List.of() : current.signs();
    }

    @Override
    public boolean cellProtected(WorldPosition position, Set<String> protectedLandmarks) {
        return protection.blockProtectedAsSeen(position, protectedLandmarks);
    }

    @Override
    public List<CampPits.Pit> scanPits(TickContext context, BlockPos origin, int radius, boolean requireCamp) {
        return campPits.scanNow(context.player(), origin, radius, requireCamp);
    }

    @Override
    public List<String> availableFillers(TickContext context) {
        LocalPlayer self = self(context);
        return self == null ? List.of() : FillerPlacement.carriedFillers(self, kept);
    }

    @Override
    public boolean needsFill(TickContext context, BlockPos cell) {
        ClientLevel level = level(context);
        if (level == null || !level.isLoaded(cell)) return false;
        BlockState state = level.getBlockState(cell);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        return level.getEntities((Entity) null, new AABB(cell), entity -> !entity.isSpectator() && entity.blocksBuilding)
                .isEmpty();
    }

    // 站在待填坑列里会卡住自己，离待填格超过手长够不着：两种情况都在坑外挑一格手够得着的平地先站好。
    @Override
    public Optional<BlockPos> rimStandFor(TickContext context, BlockPos cell, List<BlockPos> pitCells) {
        ClientLevel level = level(context);
        LocalPlayer self = self(context);
        if (level == null || self == null) return Optional.empty();
        Vec3 center = Vec3.atCenterOf(cell);
        boolean insidePit = inSameColumn(self.blockPosition(), pitCells);
        if (!insidePit && self.getEyePosition().distanceToSqr(center) <= REACH_SQ) {
            return Optional.empty();
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos feet = new BlockPos(cell.getX() + dx, cell.getY() + dy, cell.getZ() + dz);
                    if (inSameColumn(feet, pitCells) || !standable(level, feet)) continue;
                    Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
                    double distSq = eye.distanceToSqr(center);
                    if (distSq >= 1.2 && distSq <= REACH_SQ) candidates.add(feet);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(feet -> feet.distSqr(self.blockPosition())));
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    @Override
    public Action walkTo(BlockPos feet) {
        return walks.start(GoalCompiler.standOn(feet), TerrainPermit.WALK_ONLY);
    }

    // 优先贴坑底正下方结实顶面往上垒，其次贴坑壁四个水平侧面往里封口。
    @Override
    public Optional<Action> placeFiller(TickContext context, BlockPos cell, String fillerId) {
        ClientLevel level = level(context);
        LocalPlayer self = self(context);
        if (level == null || self == null) return Optional.empty();
        Direction ahead = Direction.getNearest(cell.getX() + 0.5 - self.getX(), 0, cell.getZ() + 0.5 - self.getZ());
        List<BlockPos> supports = new ArrayList<>();
        for (Direction side : List.of(Direction.DOWN, ahead, ahead.getClockWise(), ahead.getCounterClockWise(), ahead.getOpposite())) {
            BlockPos support = cell.relative(side);
            if (level.isLoaded(support) && level.getBlockState(support).isFaceSturdy(level, support, side.getOpposite())) {
                supports.add(support);
            }
        }
        return supports.isEmpty() ? Optional.empty()
                : Optional.of(new FillerPlacement(interactions, toMainhand, cell, fillerId, supports));
    }

    private static boolean inSameColumn(BlockPos pos, List<BlockPos> cells) {
        for (BlockPos cell : cells) {
            if (cell.getX() == pos.getX() && cell.getZ() == pos.getZ()) return true;
        }
        return false;
    }

    private static boolean standable(ClientLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        return level.isLoaded(feet)
                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && level.getBlockState(feet).getFluidState().isEmpty();
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }

    private static ClientLevel level(TickContext context) {
        return context.player() == null ? null : context.player().level();
    }
}
