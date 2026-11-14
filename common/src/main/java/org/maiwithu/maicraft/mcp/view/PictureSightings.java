// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.behavior.perception.FacilitySighting;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneCampPit;
import org.maiwithu.maicraft.behavior.perception.SceneEntity;
import org.maiwithu.maicraft.behavior.perception.SceneSighting;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SceneThing;
import org.maiwithu.maicraft.behavior.perception.TerrainFeature;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.game.picture.CameraPicture;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 拍下画面以后在客户端线程上读：画面里看得见哪些编号、标注点落在哪、要看的那样东西的外框看不看得见。
 *
 * <p>看不看得见按镜头所在点到它的视线判断，用感知判断"看见"的同一份视线，只把起点换成镜头：
 * 第一人称时镜头就是眼睛，和场景里的 visible 一样；第三人称时以镜头为准。看不见的不标，
 * 标了就等于把墙后的东西画到墙上。
 */
public final class PictureSightings {

    private PictureSightings() {}

    /** 场景里此刻从镜头看得见的条目：实体、设施、告示牌、地形特征、模组的东西，各带标注点。 */
    public static List<PictureView.Sighting> read(TickContext context, Scene scene, CameraPicture picture) {
        List<PictureView.Sighting> out = new ArrayList<>();
        LocalPlayer self = self(context);
        if (self == null) return out;
        ClientLevel level = self.clientLevel;
        Vec3 origin = new Vec3(picture.cameraX(), picture.cameraY(), picture.cameraZ());
        for (SceneEntity entity : scene.entities()) {
            // 不看场景里按眼睛算的 visible：第三人称时镜头在身后，看不看得见以镜头为准，下面按镜头重新算视线。
            Optional<Entity> live = scene.seen().entityIn(level, entity.id());
            if (live.isEmpty()) continue;
            // 先试身子中间，挡住了再试头顶：有一点看得见就标在那一点。
            Vec3 center = live.get().getBoundingBox().getCenter();
            Vec3 head = live.get().getEyePosition();
            Vec3 anchor = ObservationVisibility.pointFrom(self, origin, center) ? center
                    : ObservationVisibility.pointFrom(self, origin, head) ? head : null;
            if (anchor == null) continue;
            boolean player = "minecraft:player".equals(entity.type());
            PictureLabels.Rank rank = player ? PictureLabels.Rank.PLAYER
                    : entity.hostile() ? PictureLabels.Rank.HOSTILE : PictureLabels.Rank.ENTITY;
            out.add(new PictureView.Sighting(entity.id(), "type", entity.type(), player ? entity.name() : null, rank,
                    anchor.x, anchor.y, anchor.z, origin.distanceTo(anchor)));
        }
        for (FacilitySighting facility : scene.facilities()) {
            block(self, level, origin, facility.id(), "block", facility.blockType(), facility.position(),
                    PictureLabels.Rank.FACILITY).ifPresent(out::add);
        }
        for (SceneSign sign : scene.signs()) {
            block(self, level, origin, sign.id(), "block", sign.blockType(), sign.position(),
                    PictureLabels.Rank.FACILITY).ifPresent(out::add);
        }
        for (SceneThing thing : scene.things()) {
            block(self, level, origin, thing.id(), "category", thing.category(), thing.position(),
                    PictureLabels.Rank.FACILITY).ifPresent(out::add);
        }
        for (TerrainFeature feature : scene.features()) {
            WorldPosition at = feature.center();
            if (!sameDimension(level, at)) continue;
            Vec3 point = new Vec3(at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
            if (!ObservationVisibility.pointFrom(self, origin, point)) continue;
            out.add(new PictureView.Sighting(feature.id(), "kind", feature.kind(), null, PictureLabels.Rank.FEATURE,
                    point.x, point.y, point.z, origin.distanceTo(point)));
        }
        for (SceneCampPit pit : scene.campPits()) {
            WorldPosition at = pit.position();
            if (!sameDimension(level, at)) continue;
            Vec3 point = new Vec3(at.x() + 0.5, at.y() + 0.5, at.z() + 0.5);
            if (!ObservationVisibility.pointFrom(self, origin, point)) continue;
            out.add(new PictureView.Sighting(pit.id(), "kind", "营地地面坑", null, PictureLabels.Rank.FEATURE,
                    point.x, point.y, point.z, origin.distanceTo(point)));
        }
        return out;
    }

    /**
     * 给了 id 时要看的那样东西：外框与看不看得见。观察编号查场景（实体按碰撞箱、方块按那一格），
     * 记住的地点按那一格；都不是为空（建筑档案由调用方另给外框）。
     */
    public static Optional<PictureView.Toward> toward(TickContext context, Scene scene, CameraPicture picture,
            String idOrLandmark, Optional<WorldPosition> landmark, boolean turned, String notTurnedBecause) {
        LocalPlayer self = self(context);
        if (self == null) return Optional.empty();
        ClientLevel level = self.clientLevel;
        Vec3 origin = new Vec3(picture.cameraX(), picture.cameraY(), picture.cameraZ());
        String id = idOrLandmark.trim().toLowerCase(Locale.ROOT);
        for (SceneSighting sighting : scene.sighting(id)) {
            if (sighting instanceof SceneEntity entity) {
                Optional<Entity> live = scene.seen().entityIn(level, entity.id());
                if (live.isEmpty()) return Optional.of(new PictureView.Toward(id, turned, notTurnedBecause, null, false, true));
                AABB box = live.get().getBoundingBox();
                boolean visible = ObservationVisibility.pointFrom(self, origin, box.getCenter())
                        || ObservationVisibility.pointFrom(self, origin, live.get().getEyePosition());
                return Optional.of(new PictureView.Toward(id, turned, notTurnedBecause, box(box), visible, true));
            }
            WorldPosition at = positionOf(sighting);
            if (at != null) return Optional.of(blockToward(self, origin, id, at, turned, notTurnedBecause));
        }
        return landmark.map(at -> blockToward(self, origin, idOrLandmark.trim(), at, turned, notTurnedBecause));
    }

    /** 建筑档案那一栋的外框：八个角与六个面正中那几格里有一格看得见就算看得见。 */
    public static PictureView.Toward building(TickContext context, CameraPicture picture, String name, BlockPos min,
            BlockPos max, boolean turned, String notTurnedBecause) {
        LocalPlayer self = self(context);
        PictureView.Box box = new PictureView.Box(min.getX(), min.getY(), min.getZ(), max.getX() + 1, max.getY() + 1,
                max.getZ() + 1);
        boolean visible = false;
        if (self != null) {
            Vec3 origin = new Vec3(picture.cameraX(), picture.cameraY(), picture.cameraZ());
            // 按格看：外框八个角那一格和六个面正中那一格，哪一格从镜头看得见（射线打到的就是那一格本身也算），
            // 这一栋就算看得见。不按点看：墙是实心的，角上、中心那一点都在方块里头，射线总先打到墙面。
            for (BlockPos cell : outlineCells(min, max)) {
                if (ObservationVisibility.blockFrom(self, origin, cell)) {
                    visible = true;
                    break;
                }
            }
        }
        return new PictureView.Toward(name, turned, notTurnedBecause, box, visible, false);
    }

    // 外框上的代表格：八个角，加上六个面各自正中的那一格。
    private static List<BlockPos> outlineCells(BlockPos min, BlockPos max) {
        int midX = (min.getX() + max.getX()) / 2;
        int midY = (min.getY() + max.getY()) / 2;
        int midZ = (min.getZ() + max.getZ()) / 2;
        List<BlockPos> cells = new ArrayList<>();
        for (int x : new int[] {min.getX(), max.getX()})
            for (int y : new int[] {min.getY(), max.getY()})
                for (int z : new int[] {min.getZ(), max.getZ()}) cells.add(new BlockPos(x, y, z));
        cells.add(new BlockPos(midX, midY, min.getZ()));
        cells.add(new BlockPos(midX, midY, max.getZ()));
        cells.add(new BlockPos(min.getX(), midY, midZ));
        cells.add(new BlockPos(max.getX(), midY, midZ));
        cells.add(new BlockPos(midX, max.getY(), midZ));
        cells.add(new BlockPos(midX, min.getY(), midZ));
        return cells;
    }

    /**
     * 拍下这一帧时镜头朝哪：镜头正前方换成相对站定朝向的方位、东南西北和俯仰的说法。
     *
     * @param standingYaw 角色站定时朝哪（只为看东西转头时不跟着头走）
     */
    public static PictureView.Camera camera(CameraPicture picture, float standingYaw) {
        Vector3f forward = new Vector3f(0, 0, -1).rotate(picture.rotation());
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, forward.y))));
        return new PictureView.Camera(DirectionWords.relative(forward.x, forward.z, standingYaw),
                DirectionWords.compassOf(forward.x, forward.z), PictureView.pitchWord(pitch));
    }

    private static Optional<PictureView.Sighting> block(LocalPlayer self, ClientLevel level, Vec3 origin, String id,
            String kindField, String kindValue, WorldPosition at, PictureLabels.Rank rank) {
        if (at == null || !sameDimension(level, at)) return Optional.empty();
        BlockPos cell = new BlockPos(at.x(), at.y(), at.z());
        if (!ObservationVisibility.blockFrom(self, origin, cell)) return Optional.empty();
        Vec3 center = Vec3.atCenterOf(cell);
        return Optional.of(new PictureView.Sighting(id, kindField, kindValue, null, rank, center.x, center.y, center.z,
                origin.distanceTo(center)));
    }

    private static PictureView.Toward blockToward(LocalPlayer self, Vec3 origin, String id, WorldPosition at,
            boolean turned, String notTurnedBecause) {
        BlockPos cell = new BlockPos(at.x(), at.y(), at.z());
        boolean visible = sameDimension(self.clientLevel, at) && ObservationVisibility.blockFrom(self, origin, cell);
        PictureView.Box box = new PictureView.Box(at.x(), at.y(), at.z(), at.x() + 1, at.y() + 1, at.z() + 1);
        return new PictureView.Toward(id, turned, notTurnedBecause, box, visible, false);
    }

    private static WorldPosition positionOf(SceneSighting sighting) {
        return switch (sighting) {
            case FacilitySighting facility -> facility.position();
            case SceneSign sign -> sign.position();
            case SceneThing thing -> thing.position();
            case TerrainFeature feature -> feature.center();
            case SceneCampPit pit -> pit.position();
            case SceneEntity entity -> null;
        };
    }

    private static PictureView.Box box(AABB box) {
        return new PictureView.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    private static boolean sameDimension(ClientLevel level, WorldPosition position) {
        return level != null && (position.dimension() == null
                || position.dimension().equals(level.dimension().location().toString()));
    }

    private static LocalPlayer self(TickContext context) {
        return context.player() == null ? null : context.player().localPlayer();
    }
}
