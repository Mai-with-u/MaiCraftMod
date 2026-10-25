// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.travel.planning;

import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.retry.QuestionEscalation;
import org.maiwithu.maicraft.kernel.goal.Question;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.maiwithu.maicraft.behavior.travel.modes.DimensionRoute;

/**
 * 目的地解析：把目标对象读成一段导航要求，只读现场、给结论，不动角色。
 *
 * <p>解析规矩：坐标给不全 y 也能去——先走到那一柱列；整列是水时默认态挑附近能站的岸格算到达（贴岸几格内挑最近，
 * 开阔水中央把搜索扩到上限，结果写明偏差），严格到达（strict_arrival）则如实失败并给最近岸格当建议落点；
 * 叫得出名字的地点查记过的地方，没记过再认
 * 附近看得见、写着这个名字的那块告示牌，都落不了就向玩家提问，不拿脚边顶替，也不把名字猜成别的东西；观察编号直接查它指向的位置，
 * 编号失效如实以"东西不在了"收场。"就是去那个维度"与落在别的维度的目的地都按维度路由
 * 认门：主世界与下界走下界门，主世界与末地走末地门，其余维度对如实说走不了。
 */
public final class DestinationResolver {

    /** 解析的一种结局。 */
    public sealed interface Resolution {

        /** 解析出了目的地，可以上路。 */
        record Ready(TravelDestination destination) implements Resolution {}

        /**
         * 要跨维度的行程：去 {@code targetDimension}；{@code destination} 不为 null 时
         * 是"那个维度的这个坐标"，跨完接着步行，为 null 表示"就是去那个维度"。
         */
        record CrossDimension(String targetDimension, TravelDestination destination) implements Resolution {}

        /** 目标就是脚下（或就是当前维度）：已经站在目的地。 */
        record AlreadyThere(WorldPosition spot) implements Resolution {}

        /** 目标说不清，要向玩家提问；由内核的提问钩子取走。 */
        record Unclear(Question question) implements Resolution {}

        /** 解析不下去，带着现场事实结束。 */
        record DeadEnd(Problem problem) implements Resolution {}
    }

    /** 按地表落点走时至少放宽这么多（格）：地表那一格可能是一根草、一块台阶，站在旁边就算到。 */
    private static final double SURFACE_SLACK = 1.5;

    /** 目标柱整列是水时往外找干地落点几格（切比雪夫距离）：贴岸的水边几格内就有岸，再远算开阔水中央。 */
    static final int SHORE_SEARCH_RADIUS = 8;

    private final TravelWorldView world;
    private final ReadsRememberedPlaces rememberedPlaces;
    private final ReadsSeenTargets seenTargets;

    public DestinationResolver(TravelWorldView world, ReadsRememberedPlaces rememberedPlaces,
            ReadsSeenTargets seenTargets) {
        this.world = world;
        this.rememberedPlaces = rememberedPlaces;
        this.seenTargets = seenTargets;
    }

    /** 解析一个目标对象；radius 是到达容差（单位格，默认 2，0 表示要站进那一格）。 */
    public Resolution resolve(Target target, double radius) {
        return resolve(target, radius, false);
    }

    /**
     * 解析一个目标对象，带严格到达开关；radius 是到达容差（单位格，默认 2，0 表示要站进那一格）。
     * strictArrival 为 true 时目标柱整列是水不自动挪去岸格，如实失败并给最近能站的岸格当建议落点。
     */
    public Resolution resolve(Target target, double radius, boolean strictArrival) {
        if (target instanceof Target.Here) {
            return new Resolution.AlreadyThere(world.currentSpot());
        }
        if (target instanceof Target.Dimension dimension) {
            return resolveDimension(dimension.name());
        }
        if (target instanceof Target.Position position) {
            return resolvePosition(position, radius, strictArrival);
        }
        if (target instanceof Target.Landmark landmark) {
            return resolveLandmark(landmark, radius);
        }
        if (target instanceof Target.Seen seen) {
            return resolveSeen(seen, radius);
        }
        if (target instanceof Target.Direction direction) {
            return resolveDirection(direction, radius, strictArrival);
        }
        // 玩家与前一步的位置不是出行目标：跟着人走归陪伴，前一步的位置只有目标推进记得。
        return new Resolution.DeadEnd(Problem.of(Problem.Kind.UNSUPPORTED,
                "出行还不支持这种目的地：" + target.kind()));
    }

    /** "就是去那个维度"：已在就是已满足；按维度路由认门，其余维度对如实说走不了。 */
    private Resolution resolveDimension(String dimension) {
        String here = world.currentSpot().dimension();
        if (dimension.equals(here)) {
            return new Resolution.AlreadyThere(world.currentSpot());
        }
        return routeAcross(dimension, null);
    }

    /** 目的地落在别的维度："那个维度的这个坐标"，跨完接着步行；认不出门就带着原因结束。 */
    private Resolution routeAcross(String dimension, TravelDestination destination) {
        DimensionRoute.Decision route = DimensionRoute.decide(world.currentSpot().dimension(), dimension);
        return switch (route.way()) {
            case NO_DIRECT_PORTAL, UNKNOWN_DIMENSION -> new Resolution.DeadEnd(route.problem());
            default -> new Resolution.CrossDimension(dimension, destination);
        };
    }

    private Resolution resolvePosition(Target.Position position, double radius, boolean strictArrival) {
        String here = world.currentSpot().dimension();
        TravelDestination destination = position.y() == null
                ? TravelDestination.anyHeight(position.x(), position.z(), radius, position.dimension())
                : TravelDestination.confirmed(
                        new WorldPosition(position.x(), position.y(), position.z(), position.dimension()), radius);
        // 目的地落在别的维度：坐标按目标维度自己的坐标系写，跨维度段先送过去。
        if (position.dimension() != null && !position.dimension().equals(here)) {
            return routeAcross(position.dimension(), destination);
        }
        if (position.y() == null) {
            return resolveColumn(position.x(), position.z(), radius, strictArrival, position.dimension());
        }
        return new Resolution.Ready(destination);
    }

    /**
     * 只给 x/z（或方向走多远算出来的落点）：落到这一柱里真能站的干地上（真人说"去那儿"指的是
     * 站得住的地面，不是水面上方悬空的顶格、也不是封顶建筑的屋顶）。整柱是水时落点挑选全在这一处分支：
     * 默认态先按贴岸规矩在 8 格内挑最近的干地岸格（到达判据站干地才放行，落点留在水里人会在水里泡到超时），
     * 8 格内无岸的开阔水把岸格搜索扩到上限，找到就落岸格算到达、结果写明偏差；
     * 严格到达（strictArrival）关闭一切自动挪——含贴岸换格，目标柱没干地就如实失败并给最近岸格当建议落点；
     * 默认态搜索范围内也找不到岸时同样失败，建议落点同一个格式。
     */
    private Resolution resolveColumn(int x, int z, double radius, boolean strictArrival, String dimension) {
        OptionalInt surface = world.surfaceFeet(x, z);
        if (surface.isPresent()) {
            return new Resolution.Ready(TravelDestination.confirmed(
                    new WorldPosition(x, surface.getAsInt(), z, dimension), Math.max(radius, SURFACE_SLACK)));
        }
        if (!strictArrival) {
            // 贴岸的水边几格内就有岸：沿用现行规矩挑最近的岸格，行为与开阔水语义变更之前逐字一致。
            Optional<WorldPosition> shore = NearestDryLanding.near(x, z, SHORE_SEARCH_RADIUS, world::surfaceFeet, dimension);
            if (shore.isPresent()) {
                return new Resolution.Ready(TravelDestination.confirmed(shore.get(), Math.max(radius, SURFACE_SLACK)));
            }
            // 开阔水中央：把岸格搜索扩到上限再找一轮，找到就落岸格算到达，结果写明偏差。
            Optional<WorldPosition> farShore = NearestDryLanding.near(x, z, openWaterSearchLimit(radius),
                    world::surfaceFeet, dimension);
            if (farShore.isPresent()) {
                return new Resolution.Ready(TravelDestination.confirmed(farShore.get(), Math.max(radius, SURFACE_SLACK),
                        WaterArrival.detourNote(x, z, farShore.get())));
            }
        }
        // 目标那一列还没加载时读不出"整列是水"，谈不上挪不挪：照旧先走到那一柱列，走近加载了再挑落点。
        if (!world.column(x, z).loaded()) {
            return new Resolution.Ready(TravelDestination.anyHeight(x, z, radius, dimension));
        }
        // 已加载又没有能站的干地（整列是水，或严格到达不肯挪）：如实失败，
        // 最近能站的岸格当建议落点带上，不开始一段永远到不了的行程。
        Optional<WorldPosition> suggestion = NearestDryLanding.near(x, z, MAX_SHORE_SEARCH, world::surfaceFeet, dimension);
        return new Resolution.DeadEnd(WaterArrival.waterColumnFailure(x, z, strictArrival, suggestion));
    }

    /**
     * 开阔水的岸格搜索上限（格）：radius 没给、或给得不比贴岸圈大时用常量上限——湖心到岸通常十几格，
     * 32 格盖住常见的湖面，再远属于大海中央。radius 给得更大时按它来，但不超过 {@link #MAX_SHORE_SEARCH}。
     */
    static final int OPEN_WATER_SEARCH_LIMIT = 32;

    /** 岸格搜索的最大上限（格）：radius 给得再大也不超过，网格扫描随半径平方涨；失败时的建议落点也只找这么远。 */
    static final int MAX_SHORE_SEARCH = 64;

    private static int openWaterSearchLimit(double radius) {
        return radius <= SHORE_SEARCH_RADIUS
                ? OPEN_WATER_SEARCH_LIMIT
                : (int) Math.min(Math.floor(radius), MAX_SHORE_SEARCH);
    }

    // 地标名：记住的地点优先，没记过再认附近看得见、写着这个名字的那块告示牌，落出来的位置和记住的地点一样走；
    // 都落不了（没有，或几块隔开的牌子都写着它）就提问，不拿脚边顶替，也不替玩家挑一块牌子。
    private Resolution resolveLandmark(Target.Landmark landmark, double radius) {
        var located = LandmarkResolver.resolve(landmark.name(), rememberedPlaces, seenTargets::signs);
        if (located instanceof LandmarkResolver.Resolution.Found found) {
            return resolveRemembered(found.position(), radius);
        }
        LandmarkResolver.Resolution.Missing missing = (LandmarkResolver.Resolution.Missing) located;
        return new Resolution.Unclear(QuestionEscalation.unclearTarget(missing.sentence(),
                List.of(new Question.Option("tell_where", "改用坐标或看得见的东西说明目的地"),
                        new Question.Option("give_up", "这次不去，取消出行"))));
    }

    /** 记住的地点可能在别的维度（记的时候人在下界）：落在哪个维度按哪个维度走。 */
    private Resolution resolveRemembered(WorldPosition place, double radius) {
        TravelDestination destination = TravelDestination.confirmed(place, radius);
        String here = world.currentSpot().dimension();
        if (place.dimension() != null && !place.dimension().equals(here)) {
            return routeAcross(place.dimension(), destination);
        }
        return new Resolution.Ready(destination);
    }

    private Resolution resolveSeen(Target.Seen seen, double radius) {
        Optional<WorldPosition> place = seenTargets.positionOf(seen.id());
        if (place.isEmpty()) {
            return new Resolution.DeadEnd(Problem.of(Problem.Kind.TARGET_GONE,
                    "观察编号 " + seen.id() + " 对应的东西已经不在了，没法照着它走"));
        }
        return resolveRemembered(place.get(), radius);
    }

    private Resolution resolveDirection(Target.Direction direction, double radius, boolean strictArrival) {
        WorldPosition spot = world.currentSpot();
        // 相对方向先按此刻朝向换算成东南西北，再算落点；落点的高度没法核实，走柱列。
        int[] vector = vectorOf(direction.toward());
        int x = spot.x() + vector[0] * direction.distance();
        int z = spot.z() + vector[1] * direction.distance();
        return resolveColumn(x, z, radius, strictArrival, spot.dimension());
    }

    /** 方向变成单位向量（x, z）；相对的前后左右按此刻朝向折算。 */
    private int[] vectorOf(Target.Toward toward) {
        Target.Toward facing = world.currentFacing();
        return switch (toward) {
            case NORTH -> new int[]{0, -1};
            case SOUTH -> new int[]{0, 1};
            case WEST -> new int[]{-1, 0};
            case EAST -> new int[]{1, 0};
            case FORWARD -> vectorOf(facing);
            case BACKWARD -> reverse(vectorOf(facing));
            case LEFT -> rotateLeft(vectorOf(facing));
            case RIGHT -> rotateLeft(rotateLeft(rotateLeft(vectorOf(facing))));
        };
    }

    private int[] reverse(int[] vector) {
        return new int[]{-vector[0], -vector[1]};
    }

    // 面朝北时左手边是西：(0, -1) 转成 (-1, 0)。
    private int[] rotateLeft(int[] vector) {
        return new int[]{vector[1], -vector[0]};
    }
}
