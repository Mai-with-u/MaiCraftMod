// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.behavior.perception.DirectionWords;
import org.maiwithu.maicraft.behavior.perception.FacilityKinds;
import org.maiwithu.maicraft.behavior.perception.FacilitySighting;
import org.maiwithu.maicraft.behavior.perception.HeardSound;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.SceneEntity;
import org.maiwithu.maicraft.behavior.perception.SceneSighting;
import org.maiwithu.maicraft.behavior.perception.SceneSign;
import org.maiwithu.maicraft.behavior.perception.SeenRegistry;
import org.maiwithu.maicraft.behavior.perception.TerrainFeature;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.interaction.FirstPersonInteractionTargeting;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.InteractionRange;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 闲时看读现场的生产实现：角色状态读本地玩家，可看的东西全从感知场景来（看得见的实体、听见的声音、
 * 场景里的地形特征与设施），观察编号按场景的登记表换回位置，地标按世界记忆。
 */
final class ClientLookingWorld implements LookingWorld {

    /** 这么快（格每刻）算在动、算朝角色走过来。 */
    static final double MOVING_PER_TICK = 0.03;
    /** 开箱时身边多远内看得见敌对生物就不开（格）。 */
    static final int HOSTILE_BLOCKS = 16;
    /** 读周围时挑的东西在多远以内（格）：太远的看过去也只是一片背景。 */
    static final int SPOT_BLOCKS = 32;
    private static final String PLAYER = "minecraft:player";

    private final Scene scene;
    private final WorldMemory memory;
    private final FacilityKinds kinds;
    private final Protection protection;

    ClientLookingWorld(Scene scene, WorldMemory memory, FacilityKinds kinds, Protection protection) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.memory = memory;
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.protection = protection;
    }

    // 只转头看东西要脚踏实地：骑马时马跟着视角转身，鞘翅和一部分载具靠视角操纵，睡着时转不了头。
    @Override public boolean playerCanLook(TickContext context) {
        PlayerContext player = context.player();
        LocalPlayer self = player == null ? null : player.localPlayer();
        if (self == null || !player.backpackReceived()) return false;
        boolean footing = self.onGround() || self.isInWater() && !self.isUnderWater();
        return footing && !self.isPassenger() && !self.isSleeping() && !self.isFallFlying()
                && !self.getAbilities().flying;
    }

    @Override public Optional<Aim> aim(TickContext context, LookTarget target) {
        LocalPlayer self = self(context);
        if (self == null) return Optional.empty();
        Vec3 eye = self.getEyePosition();
        return switch (target) {
            case LookTarget.Thing thing -> entity(context, thing.sceneId())
                    .map(entity -> aimAt(eye, entity.getEyePosition()));
            case LookTarget.Spot spot -> Optional.of(Aim.between(eye.x, eye.y, eye.z, spot.x(), spot.y(), spot.z()));
            case LookTarget.Direction direction -> Optional.of(new Aim(direction.yaw(), direction.pitch()));
            case LookTarget.Standing standing -> Optional.empty();
        };
    }

    // 身边看得见的玩家、生物：玩家带编号认说话的人，生物报动没动；声音按场景给的相对方位折成大致的偏航。
    @Override public List<Candidate> around(TickContext context) {
        LocalPlayer self = self(context);
        List<Candidate> around = new ArrayList<>();
        if (self == null) return around;
        Vec3 eye = self.getEyePosition();
        for (SceneEntity seen : scene.entities()) {
            if (!seen.visible()) continue;
            Optional<Entity> entity = entity(context, seen.id());
            if (entity.isEmpty() || !(entity.get() instanceof LivingEntity living)) continue;
            Vec3 motion = living.getDeltaMovement();
            boolean moving = Math.hypot(motion.x, motion.z) > MOVING_PER_TICK;
            Vec3 toMe = eye.subtract(living.position());
            double closing = Math.hypot(toMe.x, toMe.z) < 1e-6 ? 0
                    : (motion.x * toMe.x + motion.z * toMe.z) / Math.hypot(toMe.x, toMe.z);
            boolean isPlayer = PLAYER.equals(seen.type()) && living instanceof Player;
            around.add(new Candidate(isPlayer ? CandidateKind.PLAYER : CandidateKind.MOB, seen.id(),
                    new LookTarget.Thing(seen.id()), aimAt(eye, living.getEyePosition()).yaw(), seen.distance(),
                    moving, closing > MOVING_PER_TICK, isPlayer ? living.getUUID() : null));
        }
        float facing = context.player().facingYaw();
        List<String> relative = DirectionWords.relativeOrder();
        for (HeardSound sound : scene.heard()) {
            int sector = relative.indexOf(sound.direction());
            if (sector < 0) continue;
            // 声音只给八个方位：偏航按站定朝向往右转几个四十五度，平视看过去。
            float yaw = Aim.wrap(facing + sector * 45f);
            around.add(new Candidate(CandidateKind.SOUND, "sound:" + sound.kind() + ":" + sound.direction(),
                    new LookTarget.Direction(yaw, 0f), yaw, 8, false, false, null));
        }
        return around;
    }

    // 周围可看的几样：看得见的实体、地形特征、设施，三十二格以内；读周围是看身边看得到的，墙后、地下的不挑。
    @Override public List<Sighted> sceneSpots(TickContext context) {
        LocalPlayer self = self(context);
        List<Sighted> spots = new ArrayList<>();
        if (self == null) return spots;
        Vec3 eye = self.getEyePosition();
        for (SceneEntity seen : scene.entities()) {
            if (!seen.visible() || seen.distance() > SPOT_BLOCKS) continue;
            entity(context, seen.id()).ifPresent(entity -> spots.add(new Sighted(seen.id(), new LookTarget.Thing(seen.id()),
                    Kind.ENTITY, aimAt(eye, entity.getEyePosition()).yaw(), seen.distance(), null)));
        }
        for (TerrainFeature feature : scene.features()) {
            if (feature.distance() > SPOT_BLOCKS) continue;
            addIfSeen(spots, at(self, eye, feature.id(), feature.center(), Kind.OTHER, null));
        }
        for (FacilitySighting facility : scene.facilities()) {
            if (facility.distance() > SPOT_BLOCKS) continue;
            addIfSeen(spots, at(self, eye, facility.id(), facility.position(), Kind.OTHER, null));
        }
        return spots;
    }

    // 只朝大致方位瞥的（此刻看不见）不算周围可看的。
    private static void addIfSeen(List<Sighted> spots, Sighted spot) {
        if (!(spot.target() instanceof LookTarget.Spot point && point.roughly())) spots.add(spot);
    }

    // 观察编号换回场景里的那样东西（已经不在场景里的用最后看到的位置），不是编号的按地标名找世界记忆。
    @Override public Optional<Sighted> resolve(TickContext context, String idOrLandmark) {
        LocalPlayer self = self(context);
        if (self == null) return Optional.empty();
        Vec3 eye = self.getEyePosition();
        String normalized = idOrLandmark.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[efbs][0-9]+")) {
            if (memory == null) return Optional.empty();
            return memory.place(idOrLandmark.trim()).filter(position -> sameDimension(context, position))
                    .map(position -> at(self, eye, "landmark:" + idOrLandmark.trim(), position, Kind.OTHER, null));
        }
        Optional<SeenRegistry.Entry> entry = scene.lookup(normalized);
        if (entry.isEmpty()) return Optional.empty();
        if (entry.get().kind() == SeenRegistry.Kind.ENTITY) {
            Optional<Entity> entity = entity(context, normalized);
            if (entity.isPresent() && ObservationVisibility.entity(self, entity.get())) {
                // 看得见的实体看着它的头，它走动视线跟着。
                return Optional.of(new Sighted(normalized, new LookTarget.Thing(normalized), Kind.ENTITY,
                        aimAt(eye, entity.get().getEyePosition()).yaw(), eye.distanceTo(entity.get().position()), null));
            }
            if (entity.isPresent()) {
                // 还在附近却被墙挡住：只朝它那边瞥一眼，不隔着墙盯着它转。
                Vec3 head = entity.get().getEyePosition();
                return Optional.of(new Sighted(normalized, new LookTarget.Spot(head.x, head.y, head.z, true), Kind.ENTITY,
                        aimAt(eye, head).yaw(), eye.distanceTo(entity.get().position()), null));
            }
        }
        WorldPosition position = entry.get().position();
        for (SceneSighting sighting : scene.sighting(normalized)) {
            if (sighting instanceof SceneSign) return Optional.of(at(self, eye, normalized, position, Kind.SIGN, null));
            if (sighting instanceof FacilitySighting facility && kinds.isContainer(facility.blockType())) {
                return Optional.of(at(self, eye, normalized, position, Kind.CONTAINER,
                        containerFacts(context, self, eye, position, facility.blockType())));
            }
        }
        return Optional.of(at(self, eye, normalized, position, Kind.OTHER, null));
    }

    @Override public List<Sighted> places(TickContext context, List<WorldPosition> places) {
        LocalPlayer self = self(context);
        List<Sighted> sighted = new ArrayList<>();
        if (self == null) return sighted;
        Vec3 eye = self.getEyePosition();
        for (WorldPosition place : places) {
            if (sameDimension(context, place)) sighted.add(at(self, eye, "place:" + place, place, Kind.OTHER, null));
        }
        return sighted;
    }

    // 开箱五条里由现场回答的四条：站在原地够得着看得见、别人的箱子不开、陷阱箱不开、身边看得见敌对生物不开。
    private ContainerFacts containerFacts(TickContext context, LocalPlayer self, Vec3 eye, WorldPosition position,
            String blockType) {
        ClientLevel level = context.player().level();
        BlockPos cell = new BlockPos(position.x(), position.y(), position.z());
        boolean reachable = FirstPersonInteractionTargeting.visibleBlockHit(level, self, eye, cell,
                InteractionRange.blockReach(self)) != null;
        boolean mayUse = protection != null && protection.mayUseStorage(position, blockType, Set.of());
        boolean signal = level.getBlockState(cell).isSignalSource();
        boolean hostile = scene.entities().stream()
                .anyMatch(entity -> entity.hostile() && entity.visible() && entity.distance() <= HOSTILE_BLOCKS);
        return new ContainerFacts(position, blockType, reachable, mayUse, signal, hostile);
    }

    // 方块位置看它的中心。此刻看不见的（隔着墙、在地下，与感知判断"看见"同一份视线）和太远的标上，
    // 看的时候只朝大致方位瞥：LLM 读的可能是世界记忆里的矿、墙那边的箱子，盯着墙看过会儿挖出矿来像透视。
    private static Sighted at(LocalPlayer self, Vec3 eye, String key, WorldPosition position, Kind kind,
            ContainerFacts container) {
        double x = position.x() + 0.5;
        double y = position.y() + 0.5;
        double z = position.z() + 0.5;
        double distance = Math.sqrt(eye.distanceToSqr(x, y, z));
        boolean seenNow = ObservationVisibility.block(self, new BlockPos(position.x(), position.y(), position.z()));
        return new Sighted(key, new LookTarget.Spot(x, y, z, LookPlans.far(distance) || !seenNow), kind,
                Aim.between(eye.x, eye.y, eye.z, x, y, z).yaw(), distance, container);
    }

    private static Aim aimAt(Vec3 eye, Vec3 point) {
        return Aim.between(eye.x, eye.y, eye.z, point.x, point.y, point.z);
    }

    private Optional<Entity> entity(TickContext context, String sceneId) {
        ClientLevel level = context.player() == null ? null : context.player().level();
        if (level == null) return Optional.empty();
        return scene.seen().gameEntityOf(sceneId).map(level::getEntity).filter(entity -> !entity.isRemoved());
    }

    private static boolean sameDimension(TickContext context, WorldPosition position) {
        ClientLevel level = context.player() == null ? null : context.player().level();
        return level != null && (position.dimension() == null
                || position.dimension().equals(level.dimension().location().toString()));
    }

    private static LocalPlayer self(TickContext context) {
        PlayerContext player = context.player();
        return player == null ? null : player.localPlayer();
    }
}
