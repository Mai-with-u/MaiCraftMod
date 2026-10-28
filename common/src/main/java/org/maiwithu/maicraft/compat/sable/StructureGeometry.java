// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import org.maiwithu.maicraft.ability.vehicle.spi.BearingRotors;

/**
 * 物理结构的几何，纯函数：地块里的方块按位姿换到世界里——逐格碰撞、甲板上能站的格、在怎么动。
 * 读一格方块交进来（地块没加载的为 null）；读格有上限，超了就说没读全，调用方按整体范围处理。
 */
final class StructureGeometry {

    /** 一个结构最多逐格读这么多格；再多就按整体范围。 */
    static final int MAX_CELLS = 32_768;
    /** 每刻挪不到这么远（格）、转不到这么多（弧度）当它停着。 */
    static final double STILL_BLOCKS = 1e-3;
    static final double STILL_RADIANS = 1e-3;
    /** Simulated 的物理组装器。 */
    static final String ASSEMBLER = "simulated:physics_assembler";
    /** Simulated 的无线红石打字机：接管后按键就往绑的频率发信号。 */
    static final String TYPEWRITER = "simulated:linked_typewriter";

    private StructureGeometry() {}

    /**
     * 一次逐格读的结果（地块坐标，和位姿无关，结构不改方块就一直对）：读全没有、碰撞盒、方块数、座位、认得的部件。
     *
     * @param complete 读全了：格数没超、没有没加载的格
     * @param boxes    逐格碰撞盒（地块坐标）
     * @param blocks   不是空气的方块数
     * @param seats    座位所在的格
     * @param parts    认得的部件（控制件、收端、执行件、浮力源）：所在的格与方块 ID
     * @param assemblers 物理组装器所在的格（拆回时拉它）
     * @param makeup   方块清单：方块 ID → 格数；拆回再组装时按它认是不是同一艘、哪里改过
     * @param wings    机翼所在的格：固定在结构上、会产生升力的方块，装在轴承正面跟着转的不算
     */
    record Scan(boolean complete, List<AABB> boxes, int blocks, List<BlockPos> seats, List<Part> parts,
                List<BlockPos> assemblers, Map<String, Integer> makeup, List<BlockPos> wings) {

        Scan {
            makeup = Map.copyOf(makeup);
            wings = List.copyOf(wings);
        }

        /** 控制件（无线红石打字机、舵轮、油门拉杆）。 */
        List<Part> controls() {
            return parts.stream().filter(part -> VehicleParts.CONTROLS.contains(part.id())).toList();
        }

        /** 某一种部件所在的格。 */
        List<BlockPos> partsOf(String id) {
            return parts.stream().filter(part -> part.id().equals(id)).map(Part::local).toList();
        }
    }

    /** 结构上的一个部件：地块里的格与方块 ID。 */
    record Part(BlockPos local, String id) {}

    /**
     * 认机翼用的三样：这块方块装在物理结构上产生的升力沿结构自己的哪个方向（问 Sable，不产生为空），是不是轴承、朝哪，
     * 是不是风帆类方块（顺着它们认轴承转子，跟着转的风帆不算机翼）。
     */
    record WingRules(Function<BlockState, Optional<Direction>> lift, Function<BlockState, Optional<Direction>> bearing,
                     Predicate<BlockState> sail) {

        /** 不认机翼：读不到升力时用。 */
        static final WingRules NONE = new WingRules(state -> Optional.empty(), state -> Optional.empty(), state -> false);

        /**
         * 这块能当机翼：升力朝上或朝下（帆面平放）。竖着装的风帆升力是水平的，托不起机身，是垂尾或船帆，
         * 带帆的船不因此被认成固定翼。
         */
        boolean holdsUp(BlockState state) {
            return lift.apply(state).filter(direction -> direction.getAxis() == Direction.Axis.Y).isPresent();
        }

        /** 机械动力的风车风帆标签：风帆、风帆框架、对称风帆都在里面。 */
        private static final TagKey<Block> SAILS = TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath("create", "windmill_sails"));

        /** 升力问 Sable；轴承按方块 ID 认、朝向读方块状态，风帆类按风车风帆标签或会产生升力认（不论朝向）。 */
        static WingRules of(Function<BlockState, Optional<Direction>> lift) {
            return new WingRules(lift, WingRules::bearingFacing,
                    state -> state.is(SAILS) || lift.apply(state).isPresent());
        }

        private static Optional<Direction> bearingFacing(BlockState state) {
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            return VehicleParts.BEARINGS.contains(id) && state.hasProperty(BlockStateProperties.FACING)
                    ? Optional.of(state.getValue(BlockStateProperties.FACING)) : Optional.empty();
        }
    }

    /** 地块里逐格读一遍，不认机翼。 */
    static Scan scan(SableStructures.Structure structure, Function<BlockPos, BlockState> read) {
        return scan(structure, read, WingRules.NONE);
    }

    /** 地块里逐格读一遍：每格自己的碰撞形状、座位、认得的部件、机翼；格数超了或有没加载的格就是没读全。 */
    static Scan scan(SableStructures.Structure structure, Function<BlockPos, BlockState> read, WingRules rules) {
        List<AABB> boxes = new ArrayList<>();
        List<BlockPos> seats = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        List<BlockPos> assemblers = new ArrayList<>();
        Map<String, Integer> makeup = new TreeMap<>();
        if (cells(structure) > MAX_CELLS) return new Scan(false, boxes, 0, seats, parts, assemblers, makeup, List.of());
        WingCells wings = new WingCells(rules);
        boolean complete = true;
        int blocks = 0;
        for (BlockPos local : BlockPos.betweenClosed(structure.localMin(), structure.localMax())) {
            BlockState state = read.apply(local);
            if (state == null) {
                complete = false;
                continue;
            }
            if (state.isAir()) continue;
            blocks++;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            // 每种方块数一下：拆回改完再组装时，拿这份清单认是不是同一艘、多了少了什么。
            makeup.merge(id, 1, Integer::sum);
            if (id.endsWith("_seat")) seats.add(local.immutable());
            if (VehicleParts.known(id)) parts.add(new Part(local.immutable(), id));
            if (ASSEMBLER.equals(id)) assemblers.add(local.immutable());
            wings.note(local.immutable(), state);
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, local);
            for (AABB part : shape.toAabbs()) boxes.add(part.move(local));
        }
        return new Scan(complete, boxes, blocks, seats, parts, assemblers, makeup, wings.wings());
    }

    // 逐格读时顺手记下的：会产生升力的格、轴承朝哪、风帆类的格、所有方块；读完去掉轴承转子上的就是机翼。
    private static final class WingCells {
        private final WingRules rules;
        private final List<BlockPos> lifting = new ArrayList<>();
        private final Map<BlockPos, Direction> bearings = new HashMap<>();
        private final Set<BlockPos> sails = new HashSet<>();
        private final Set<BlockPos> filled = new HashSet<>();

        WingCells(WingRules rules) {
            this.rules = rules;
        }

        void note(BlockPos local, BlockState state) {
            filled.add(local);
            if (rules.holdsUp(state)) lifting.add(local);
            rules.bearing().apply(state).ifPresent(facing -> bearings.put(local, facing));
            if (rules.sail().test(state)) sails.add(local);
        }

        // 螺旋桨桨叶、舵面这类装在轴承正面跟着转的风帆不是机翼。
        List<BlockPos> wings() {
            if (lifting.isEmpty()) return List.of();
            Set<BlockPos> turning = new HashSet<>();
            for (BearingRotors.Rotor rotor : BearingRotors.of(filled, cell -> Optional.ofNullable(bearings.get(cell)),
                    sails::contains)) {
                turning.addAll(rotor.cells());
            }
            return lifting.stream().filter(cell -> !turning.contains(cell)).toList();
        }
    }

    /**
     * 甲板上能站人的格：脚下是有上表面的方块，脚和头两格都没有碰撞；给的是此刻人站上去时脚底中心在世界里的位置，
     * 和对应的地块格。
     */
    static List<Deck> deck(SableStructures.Structure structure, Function<BlockPos, BlockState> read) {
        List<Deck> cells = new ArrayList<>();
        if (cells(structure) > MAX_CELLS) return cells;
        BlockPos min = structure.localMin();
        BlockPos max = structure.localMax().above(2);
        for (BlockPos local : BlockPos.betweenClosed(min, max)) {
            BlockState floor = read.apply(local.below());
            BlockState feet = read.apply(local);
            BlockState head = read.apply(local.above());
            if (floor == null || feet == null || head == null) continue;
            if (!solidTop(floor, local.below()) || blocks(feet, local) || blocks(head, local.above())) continue;
            Vec3 standing = structure.pose().toWorld(new Vec3(local.getX() + 0.5, local.getY(), local.getZ() + 0.5));
            cells.add(new Deck(local.immutable(), standing));
        }
        return cells;
    }

    /** 甲板上的一格：地块里的格，和此刻脚底中心在世界里的位置。 */
    record Deck(BlockPos local, Vec3 world) {}

    /** 这一刻比上一刻挪了多少（格每刻）。 */
    static Vec3 velocity(SableStructures.Structure structure) {
        return structure.pose().position().subtract(structure.lastPose().position());
    }

    /** 这一刻比上一刻转了多少（弧度）。 */
    static double turned(SableStructures.Structure structure) {
        double[] a = structure.pose().orientation();
        double[] b = structure.lastPose().orientation();
        double dot = Math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]);
        return 2 * Math.acos(Math.min(1, dot));
    }

    /**
     * 两个位姿之间的变化：世界里挪了多少；换到开始那一刻结构自己的轴上挪了多少；绕结构自己的 x、y、z 轴各转了多少度。
     * 结构自己的轴就是组装那一刻的世界轴（组装不转方块），所以"沿 +z"就是造的时候朝南的那头。
     */
    record Change(Vec3 moved, Vec3 alongOwnAxes, Vec3 turnDegrees) {}

    /** 从 from 到 to 的变化。 */
    static Change change(SableStructures.Pose from, SableStructures.Pose to) {
        Vec3 moved = to.position().subtract(from.position());
        Quaterniond start = quaternion(from);
        Vector3d own = start.transformInverse(new Vector3d(moved.x, moved.y, moved.z));
        // 相对转动 = 开始的逆 × 现在：转轴落在开始那一刻结构自己的轴上；取短的那一圈。
        Quaterniond relative = new Quaterniond(start).conjugate().mul(quaternion(to));
        double sign = relative.w < 0 ? -1 : 1;
        double x = relative.x * sign, y = relative.y * sign, z = relative.z * sign, w = relative.w * sign;
        double sin = Math.sqrt(x * x + y * y + z * z);
        Vec3 turn = Vec3.ZERO;
        if (sin > 1e-9) {
            double degrees = Math.toDegrees(2 * Math.atan2(sin, w));
            turn = new Vec3(x / sin * degrees, y / sin * degrees, z / sin * degrees);
        }
        return new Change(moved, new Vec3(own.x, own.y, own.z), turn);
    }

    private static Quaterniond quaternion(SableStructures.Pose pose) {
        double[] q = pose.orientation();
        return new Quaterniond(q[0], q[1], q[2], q[3]);
    }

    /** 在动（挪或转）。 */
    static boolean moving(SableStructures.Structure structure) {
        return velocity(structure).length() > STILL_BLOCKS || turned(structure) > STILL_RADIANS;
    }

    /** 局部的一个盒子换到世界：八个角都换过去取包围盒。 */
    static AABB toWorld(SableStructures.Pose pose, AABB local) {
        Vec3 first = pose.toWorld(new Vec3(local.minX, local.minY, local.minZ));
        double minX = first.x, minY = first.y, minZ = first.z, maxX = first.x, maxY = first.y, maxZ = first.z;
        for (int bits = 1; bits < 8; bits++) {
            Vec3 corner = pose.toWorld(new Vec3((bits & 1) == 0 ? local.minX : local.maxX,
                    (bits & 2) == 0 ? local.minY : local.maxY, (bits & 4) == 0 ? local.minZ : local.maxZ));
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static long cells(SableStructures.Structure structure) {
        BlockPos min = structure.localMin();
        BlockPos max = structure.localMax();
        return (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
    }

    private static boolean solidTop(BlockState state, BlockPos at) {
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, at);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) >= 1.0;
    }

    private static boolean blocks(BlockState state, BlockPos at) {
        return !state.getCollisionShape(EmptyBlockGetter.INSTANCE, at).isEmpty();
    }
}
