// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.maiwithu.maicraft.behavior.explore.ClientExplorationWorld;
import org.maiwithu.maicraft.behavior.explore.ExploreObjective;
import org.maiwithu.maicraft.behavior.explore.ExploreTask;
import org.maiwithu.maicraft.behavior.explore.cave.CaveWalkPlan;
import org.maiwithu.maicraft.behavior.explore.cave.CaveWalkTask;
import org.maiwithu.maicraft.behavior.explore.cave.ClientCaveWorld;
import org.maiwithu.maicraft.behavior.explore.cave.WalkedArea;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.behavior.navigation.WalkTo;
import org.maiwithu.maicraft.behavior.perception.CaveOpenings;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.IdKind;
import org.maiwithu.maicraft.game.world.SimilarIds;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Goal;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 探索能力：朝某个方向把外面的地形跑一遍。能力只做薄皮——目标与留意清单在这里解析、
 * 校验（写岔的 ID 一次报全，认不出的群系以不支持结束并提示先查可用 ID），然后把整备好的
 * 目标交给探索走行；观察推进、航点、换向、发现落账与收尾都是探索走行的事。
 *
 * <p>探索没有预设终点：不接受目标对象，只认 target 参数里的五种写法；钻洞（cave）交给钻洞任务，
 * 其余交给跑图。乘船、飞行这类
 * 载具归载具域，本能力以"暂不支持"如实说明，不做变通。需要的现场读端、走到、世界记忆
 * 与事件出口都从构造函数传入，本能力不认任何具体的服务实现。
 */
public final class ExploreAbility implements AbilityModule {

    private final ReadsExploreTypes worldTypes;
    private final WalkTo walks;
    private final Supplier<PlayerContext> context;
    private final BlockScanService scans;
    private final WorldMemory memory;
    private final TaskEventSink events;
    /** 场景此刻报的洞口与峡谷：钻洞挑洞口就挑这几处，与 observe 看到的一致。 */
    private final Supplier<List<CaveOpenings.Opening>> caveOpenings;
    /** 钻洞每走一段站定转头看一圈的动作；闲时看关着时给 null，站着看不转头。 */
    private final Supplier<Action> lookAround;
    /** 钻洞走过的地方：几次钻洞之间留着，再钻一次不重走；换维度自己清空。 */
    private final WalkedArea walked = new WalkedArea();

    /** 生产用：读端在这里接上真实客户端与扫描服务，能力包内可见的实现类不出包。 */
    public static ExploreAbility live(Supplier<PlayerContext> context, BlockScanService scans,
            WalkTo walks, WorldMemory memory, TaskEventSink events,
            Supplier<List<CaveOpenings.Opening>> caveOpenings, Supplier<Action> lookAround) {
        return new ExploreAbility(new LiveExploreTypes(context), walks, context, scans, memory, events, caveOpenings,
                lookAround);
    }

    ExploreAbility(ReadsExploreTypes worldTypes, WalkTo walks, Supplier<PlayerContext> context,
            BlockScanService scans, WorldMemory memory, TaskEventSink events,
            Supplier<List<CaveOpenings.Opening>> caveOpenings, Supplier<Action> lookAround) {
        this.worldTypes = Objects.requireNonNull(worldTypes, "worldTypes");
        this.walks = Objects.requireNonNull(walks, "walks");
        this.context = Objects.requireNonNull(context, "context");
        this.scans = Objects.requireNonNull(scans, "scans");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.events = Objects.requireNonNull(events, "events");
        this.caveOpenings = Objects.requireNonNull(caveOpenings, "caveOpenings");
        this.lookAround = Objects.requireNonNull(lookAround, "lookAround");
    }

    /** 钻洞默认走多远（离洞口的直线距离，格）。 */
    static final int CAVE_MAX_DISTANCE = 128;
    /** 其余目标默认的探索半径（格）。 */
    static final int SURFACE_MAX_DISTANCE = 768;

    private final AbilitySpec spec = new AbilitySpec(
            "maicraft:explore",
            "朝某个方向把外面的地形跑一遍：自由看看这一片，或去找一种群系、一块能站的平台、回露天的地面；"
                    + "也能钻进附近的矿洞顺着走，一路看洞壁上露出来的矿",
            AbilityDoc.forAbility("explore"),
            ParamSpecs.of(
                    ParamSpec.of("target", ParamType.TEXT).required()
                            .doc("探索目标：survey 自由跑图 / 群系 ID 或 # 标签 / platform 找平台 / surface 回露天 / "
                                    + "cave 钻洞（钻进附近的矿洞顺着走、一路看矿）；"
                                    + "探索不接受已定位终点，找海岸给 minecraft:beach").build(),
                    ParamSpec.of("direction", ParamType.CHOICE)
                            .choices("forward", "backward", "left", "right", "north", "south", "east", "west")
                            .doc("扇形朝向；platform 必配，其余可选，出发时按当时朝向固定；"
                                    + "钻洞时只用来挑哪个洞口，进了洞是偏向那边，洞往哪拐由洞定").build(),
                    ParamSpec.of("angle_degrees", ParamType.INTEGER).range(1, 360)
                            .doc("扇形开口角（度）；有方向默认 90，无方向必须省略或 360").build(),
                    ParamSpec.of("min_distance", ParamType.INTEGER).range(0, 2048)
                            .doc("忽略更近的目标匹配（格）；有方向默认 16，无方向默认 0；钻洞时用在挑洞口上，默认 0").build(),
                    ParamSpec.of("max_distance", ParamType.INTEGER).range(64, 2048)
                            .doc("探索半径（格），默认 768；钻洞是离洞口的直线距离，默认 128；到范围边上就收工").build(),
                    ParamSpec.of("max_seconds", ParamType.INTEGER)
                            .doc("最多做多久（秒）；观察与走行有真实进展就续期").build(),
                    ParamSpec.of("interests", ParamType.TEXT_LIST)
                            .doc("途中留意清单：地形要素（lava_pool）、结构 ID、方块 ID 或 # 标签、实体类型；"
                                    + "看见了记下来并发一条发现事件，不改路线、不算抵达；"
                                    + "钻洞时只认方块与 lava_pool，看见就走到跟前收场（例如 #c:ores/iron）").build()),
            Set.of(),
            ExecutionMode.CONTROLS_PLAYER,
            Set.of(),
            List.of(),
            Listing.LISTED);

    @Override public AbilitySpec spec() { return spec; }

    @Override
    public StepDecision decide(StepContext step) {
        Goal goal = step.goal();
        if (goal.target() != null) {
            // 探索没有预设终点：目标对象是"去某个地方"，探索只认 target 参数里的五种写法。
            return new StepDecision.Finish(failInvalid("探索不接受目标对象（target）："
                    + "要去某个具体地方用出行；探索的目标写在参数 target 里"));
        }
        List<String> problems = new ArrayList<>();
        Target.Toward direction = directionOf(goal.params(), problems);
        String targetSpec = goal.params().text("target").trim().toLowerCase(Locale.ROOT);
        boolean cave = "cave".equals(targetSpec);
        // 钻洞说的是离洞口走多远，默认短得多；其余目标是跑图的半径。
        int maxDistance = goal.params().has("max_distance") ? (int) goal.params().integer("max_distance")
                : cave ? CAVE_MAX_DISTANCE : SURFACE_MAX_DISTANCE;
        if (goal.params().has("min_distance") && goal.params().integer("min_distance") > maxDistance) {
            problems.add("起找距离（min_distance " + goal.params().integer("min_distance")
                    + "）不能超过探索半径（max_distance " + maxDistance + "）");
        }
        // 注册表里没有的群系不跑图：以不支持结束，说出本实例里相近的群系，并指到群系目录页，不混进参数错误里。
        if (!targetSpec.isEmpty() && !List.of("survey", "platform", "surface", "cave").contains(targetSpec)
                && !worldTypes.biomeExists(targetSpec)) {
            // 一句话结论只带"不存在"那半句，相近的名字与去处只在问题里说一次。
            Problem unknown = Problem.of(Problem.Kind.UNSUPPORTED, worldTypes.missing(IdKind.BIOME, targetSpec),
                    BIOME_CATALOG_HINT);
            return new StepDecision.Finish(TaskResult.failed("探索：" + unknown.headline(), unknown));
        }
        ExploreObjective objective = objectiveOf(goal.params(), direction, problems);
        if (objective == null) {
            return new StepDecision.Finish(failInvalid(String.join("；", problems)));
        }
        ExploreObjective.Interests interests = interestsOf(goal.params(), problems);
        if (cave && (!interests.entities().isEmpty() || !interests.structures().isEmpty())) {
            // 钻洞看见留意的东西就走到跟前收场：只认洞壁上看得见的方块和岩浆，生物、结构不在洞壁上。
            problems.add("钻洞的留意清单只认方块 ID 或 # 标签与 lava_pool，不认实体和结构："
                    + String.join("、", concat(interests.entities(), interests.structures())));
        }
        if (!problems.isEmpty()) {
            return new StepDecision.Finish(failInvalid(String.join("；", problems)));
        }
        // 钻洞的起找距离只用在挑洞口上，有方向也从脚边挑起。
        int minDistance = goal.params().has("min_distance")
                ? (int) goal.params().integer("min_distance")
                : direction != null && !cave ? 16 : 0;
        return new StepDecision.Run(new ExploreInput(objective, interests, direction,
                angleOf(goal.params(), direction), minDistance, maxDistance,
                maxSeconds(goal), TerrainPermit.of(goal.permissions())));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    /** 五种目标写法，一次只认一种；认不出的群系以不支持结束，提示先查可用 ID。 */
    private ExploreObjective objectiveOf(ParamValues params, Target.Toward direction,
            List<String> problems) {
        String spec = params.text("target").trim().toLowerCase(Locale.ROOT);
        switch (spec) {
            case "survey":
                // 有方向的自由跑图说得通：扇形约束往哪边看。
                return ExploreObjective.survey();
            case "platform":
                if (direction == null) {
                    problems.add("platform 找平台必配 direction");
                    return null;
                }
                return new ExploreObjective(ExploreObjective.Kind.FLAT_GROUND, List.of());
            case "surface":
                return new ExploreObjective(ExploreObjective.Kind.SURFACE, List.of());
            case "cave":
                return new ExploreObjective(ExploreObjective.Kind.CAVE, List.of());
            default:
                if (spec.isEmpty()) {
                    problems.add("target 不能为空：survey / 群系 ID 或 # 标签 / platform / surface / cave");
                    return null;
                }
                // 群系存不存在已在开头查过：到这里就是认得出的群系定向探索。
                return new ExploreObjective(ExploreObjective.Kind.BIOME, List.of(spec));
        }
    }

    /** 八向朝向；无方向时开口角必须省略或 360，一并在这里查。 */
    private Target.Toward directionOf(ParamValues params, List<String> problems) {
        if (!params.has("direction")) {
            if (params.has("angle_degrees") && params.integer("angle_degrees") != 360) {
                problems.add("没给 direction 就不给开口角：angle_degrees 只能省略或 360");
            }
            return null;
        }
        return Target.Toward.valueOf(params.text("direction").trim().toUpperCase(Locale.ROOT));
    }

    /** 开口角：有方向默认 90，全向 360。 */
    private int angleOf(ParamValues params, Target.Toward direction) {
        if (params.has("angle_degrees")) {
            return (int) params.integer("angle_degrees");
        }
        return direction != null ? 90 : 360;
    }

    /** 途中留意清单：按注册表分类（地形要素、方块、实体、结构），认不出的记下来一次报全，各带一个相近的名字。 */
    private ExploreObjective.Interests interestsOf(ParamValues params, List<String> problems) {
        List<String> raw = params.has("interests") ? params.list("interests") : List.of();
        // 空数组当没给。
        if (raw.isEmpty()) {
            return ExploreObjective.Interests.NONE;
        }
        List<String> blocks = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> structures = new ArrayList<>();
        List<String> terrain = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String selector : raw) {
            String spec = selector.trim();
            if (spec.isEmpty()) {
                continue;
            }
            if (worldTypes.terrainExists(spec)) {
                terrain.add(spec);
            } else if (worldTypes.blockExists(spec)) {
                blocks.add(spec);
            } else if (worldTypes.entityTypeExists(spec)) {
                entities.add(spec);
            } else if (worldTypes.structureExists(spec)) {
                structures.add(spec);
            } else {
                unknown.add(spec);
            }
        }
        if (!unknown.isEmpty()) {
            List<String> sentences = unknown.stream()
                    .map(spec -> SimilarIds.missing("留意清单里的", spec, worldTypes.similarInterest(spec))).toList();
            problems.add(String.join("；", sentences)
                    + "（地形要素只有 lava_pool，其余要写方块、实体或结构的注册 ID）");
        }
        return new ExploreObjective.Interests(blocks, entities, structures, terrain);
    }

    /** 秒换内部用的整数时限；没给正数与给得过大同义：都不设时限。 */
    private int maxSeconds(Goal goal) {
        if (!goal.params().has("max_seconds")) {
            return 0;
        }
        long seconds = goal.params().integer("max_seconds");
        if (seconds <= 0 || seconds > Integer.MAX_VALUE / 20) {
            return 0;
        }
        return (int) seconds;
    }

    /** 认不出群系时的去处：本实例的群系目录页列着全部群系 ID 与标签。 */
    static final String BIOME_CATALOG_HINT =
            "lookup(topic=knowledge, id=biomes) 看本实例的群系目录（例如找海岸给 minecraft:beach）";

    private TaskResult failInvalid(String message) {
        return TaskResult.failed("探索：参数说得不对",
                Problem.of(Problem.Kind.INVALID_PARAMETER, message,
                        "target 写 survey、群系 ID 或 # 标签、platform、surface 或 cave；拿不准的群系 ID 先 "
                                + BIOME_CATALOG_HINT));
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 钻洞交给钻洞任务：转头看一圈、量路、看矿都在里面；其余目标照旧跑图。
        factories.register(ExploreInput.class, input -> input.objective().target() == ExploreObjective.Kind.CAVE
                ? new CaveWalkTask(caveWalkPlan(input), new ClientCaveWorld(context, scans), walks, caveOpenings,
                        lookAround, walked, memory, events)
                : new ExploreTask(input.objective(),
                        input.interests(), input.direction(), input.angleDegrees(), input.minDistance(),
                        input.maxDistance(), input.maxSeconds(), input.permit(),
                        new ClientExplorationWorld(context, scans), walks, memory, events));
    }

    /** 钻洞说好的事：留意的方块、看不看岩浆，加上方向、距离与许可。 */
    static CaveWalkPlan caveWalkPlan(ExploreInput input) {
        return new CaveWalkPlan(input.interests().blocks(),
                input.interests().terrainKinds().contains("lava_pool"), input.direction(), input.angleDegrees(),
                input.minDistance(), input.maxDistance(), input.maxSeconds(), input.permit());
    }
}
