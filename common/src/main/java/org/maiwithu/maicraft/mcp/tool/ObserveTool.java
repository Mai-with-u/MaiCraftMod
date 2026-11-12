// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.GsonBuilder;
import com.google.gson.Gson;
import com.google.gson.FieldNamingPolicy;
import org.maiwithu.maicraft.ability.design.api.BuildingArchive;
import org.maiwithu.maicraft.ability.design.api.BuildingArchives;
import org.maiwithu.maicraft.behavior.perception.looking.LooksForPicture;
import org.maiwithu.maicraft.behavior.perception.looking.PictureLook;
import org.maiwithu.maicraft.behavior.reminder.Reminder;
import org.maiwithu.maicraft.game.picture.CameraPicture;
import org.maiwithu.maicraft.game.picture.CameraPictures;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.mcp.view.PictureEncoding;
import org.maiwithu.maicraft.mcp.view.PictureSightings;
import org.maiwithu.maicraft.mcp.view.PictureView;
import org.maiwithu.maicraft.behavior.perception.Scene;
import org.maiwithu.maicraft.behavior.perception.looking.LooksAfterReading;
import org.maiwithu.maicraft.behavior.perception.looking.WhatWasRead;
import org.maiwithu.maicraft.behavior.worldmemory.MemoryKind;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.ClientFoodValues;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.GoalRunTable;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.mcp.view.DetailView;
import org.maiwithu.maicraft.mcp.view.MenuView;
import org.maiwithu.maicraft.mcp.view.SceneView;
import org.maiwithu.maicraft.mcp.view.SelfView;
import org.maiwithu.maicraft.mcp.view.WorldMemoryView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * observe：看世界和自己，只看不做。what 选看什么：self（自己）、scene（周围，默认）、
 * detail（一个看到的东西或记住的地点的细节）、world_memory（角色记得的地点与容器）、
 * menu（此刻开着的界面）、picture（角色眼里此刻的画面，一张图加一段文字）。
 *
 * <p>只给"玩家此刻能看见、记得的"；需要分析、计算的（例如施工场地勘测）是能力，用 execute。
 * 场景与记忆按世界创建，角色不在世界里时回答不在世界里。
 */
public final class ObserveTool implements McpTool {
    private static final List<String> VIEWS = List.of(
            "self", "scene", "detail", "places", "world_memory", "menu", "picture");
    private static final List<String> MEMORY_KINDS = List.of(
            "container", "workstation", "site", "machine", "building", "temporary_block");
    /** 拍画面时等转头最多多久（毫秒）：闲时看那一回最多转两秒，多给半秒余量。 */
    static final long TURN_WAIT_MILLIS = 2500;
    /** 等下一帧画到取图点最多多久（毫秒）：游戏这一秒都没画出一帧，多半是窗口最小化了。 */
    static final long FRAME_WAIT_MILLIS = 1000;

    /**
     * 拍画面要用的东西：取画面的观察口、当前世界的闲时看（为拍画面转头）、当前世界的建筑档案库。
     * 没接上取画面时 cameras 为 null，observe(picture) 照实说拿不到。
     */
    public record Pictures(CameraPictures cameras, Supplier<LooksForPicture> looks, Supplier<BuildingArchives> archives) {
        /** 没接上取画面：测试和启动时不接取画面时用。 */
        public static final Pictures NONE = new Pictures(null, () -> LooksForPicture.NONE, () -> null);

        public Pictures {
            Objects.requireNonNull(looks, "looks");
            Objects.requireNonNull(archives, "archives");
        }
    }

    /** 提醒转成 JSON：字段名照记录的写法换成下划线小写。 */
    private static final Gson REMINDER_JSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();

    private final Supplier<Scene> scene;
    private final Supplier<WorldMemory> memory;
    private final GoalRunTable table;
    private final ClientThread clientThread;
    /** 角色身上还成立的提醒：observe(self) 每次都完整交出去，事件只负责叫醒。 */
    private final Supplier<List<Reminder>> reminders;
    /** 看界面要读的东西：开着的界面与交易报价；不在世界里时给 null。 */
    private final Supplier<MenuView.Reads> menus;
    /** 调用方写下的预留与身上各有几件：observe(self) 每次都完整交出去，LLM 不用自己记。 */
    private final Supplier<List<SelfView.Reserved>> reserved;
    /** 读完告诉闲时看读了什么：角色闲着时顺着看过去，读到的内容不变。 */
    private final LooksAfterReading looks;
    /** 角色是谁、在哪个世界：observe(self) 每次都带上；不在世界里时给 null。 */
    private final Supplier<SelfView.Identity> identity;
    /** 死一次会怎样（死亡不掉落开没开、会在哪重生）：observe(self) 每次都带上；在客户端线程上读，不在世界里时给 null。 */
    private final Supplier<SelfView.AfterDeath> afterDeath;
    /** 拍画面要用的东西。 */
    private final Pictures pictures;
    /** 归属读端：容器的"最后由谁放下"从这来；不在世界里时给 null。 */
    private final Supplier<Protection> protection;

    /**
     * @param scene  当前世界的感知场景；不在世界里时给 null
     * @param memory 当前世界的世界记忆；不在世界里时给 null
     */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders) {
        this(scene, memory, table, clientThread, reminders, () -> null);
    }

    /** @param menus 看界面要读的东西；不在世界里时给 null */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders,
                       Supplier<MenuView.Reads> menus) {
        this(scene, memory, table, clientThread, reminders, menus, List::of);
    }

    /** @param reserved 调用方写下的预留与身上各有几件；没有预留时给空 */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders,
                       Supplier<MenuView.Reads> menus, Supplier<List<SelfView.Reserved>> reserved) {
        this(scene, memory, table, clientThread, reminders, menus, reserved, LooksAfterReading.NONE);
    }

    /** @param looks 读完告诉闲时看读了什么；不演的给 {@link LooksAfterReading#NONE} */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders,
                       Supplier<MenuView.Reads> menus, Supplier<List<SelfView.Reserved>> reserved,
                       LooksAfterReading looks) {
        this(scene, memory, table, clientThread, reminders, menus, reserved, looks, () -> null);
    }

    /** @param identity 角色是谁、在哪个世界；不在世界里时给 null */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders,
                       Supplier<MenuView.Reads> menus, Supplier<List<SelfView.Reserved>> reserved,
                       LooksAfterReading looks, Supplier<SelfView.Identity> identity) {
        this(scene, memory, table, clientThread, reminders, menus, reserved, looks, identity, () -> null);
    }

    /** @param afterDeath 死一次会怎样；在客户端线程上读，不在世界里时给 null */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders,
                       Supplier<MenuView.Reads> menus, Supplier<List<SelfView.Reserved>> reserved,
                       LooksAfterReading looks, Supplier<SelfView.Identity> identity,
                       Supplier<SelfView.AfterDeath> afterDeath) {
        this(scene, memory, table, clientThread, reminders, menus, reserved, looks, identity, afterDeath, Pictures.NONE,
                () -> null);
    }

    /**
     * @param pictures   拍画面要用的东西；不拍画面的给 {@link Pictures#NONE}
     * @param protection 归属读端：容器记录的"最后由谁放下"从这来；不在世界里时给 null
     */
    public ObserveTool(Supplier<Scene> scene, Supplier<WorldMemory> memory, GoalRunTable table,
                       ClientThread clientThread, Supplier<List<Reminder>> reminders,
                       Supplier<MenuView.Reads> menus, Supplier<List<SelfView.Reserved>> reserved,
                       LooksAfterReading looks, Supplier<SelfView.Identity> identity,
                       Supplier<SelfView.AfterDeath> afterDeath, Pictures pictures,
                       Supplier<Protection> protection) {
        this.pictures = Objects.requireNonNull(pictures, "pictures");
        this.protection = protection;
        this.afterDeath = Objects.requireNonNull(afterDeath, "afterDeath");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.looks = Objects.requireNonNull(looks, "looks");
        this.reserved = Objects.requireNonNull(reserved, "reserved");
        this.scene = Objects.requireNonNull(scene, "scene");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.table = Objects.requireNonNull(table, "table");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
        this.reminders = Objects.requireNonNull(reminders, "reminders");
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    @Override public String name() {
        return ToolCatalog.OBSERVE;
    }

    @Override public JsonObject call(JsonObject arguments) {
        return answer(arguments).reply();
    }

    // picture 另带一张图；别的视图只有 JSON。
    @Override public ToolReply.WithImages answer(JsonObject arguments) {
        RequestCheck check = new RequestCheck();
        check.rejectUnknownFields(arguments, "",
                List.of("what", "id", "grid", "with_menu", "unlabeled", "radius", "kind", "sections"));
        String chosen = check.choice(arguments, "what", "what", VIEWS);
        String what = chosen == null ? "scene" : chosen;
        String id = check.text(arguments, "id", "id", false);
        Boolean grid = check.bool(arguments, "grid", "grid");
        Boolean withMenu = check.bool(arguments, "with_menu", "with_menu");
        Boolean unlabeled = check.bool(arguments, "unlabeled", "unlabeled");
        Integer radius = check.integer(arguments, "radius", "radius", false);
        String kind = check.choice(arguments, "kind", "kind", MEMORY_KINDS);
        Set<String> sections = sections(arguments, what, check);
        if (what.equals("detail") && id == null && !arguments.has("id")) {
            check.error("id", "what=detail 时要给 id", "观察编号（e12、f3、b5、s2）或地标名");
        }
        boolean takesId = what.equals("detail") || what.equals("picture");
        if (!takesId && id != null) check.error("id", "id 只在 what=detail 或 picture 时用", null);
        if (!what.equals("scene") && grid != null) check.error("grid", "grid 只在 what=scene 时用", null);
        if (!what.equals("picture") && withMenu != null) check.error("with_menu", "with_menu 只在 what=picture 时用", null);
        if (!what.equals("picture") && unlabeled != null) check.error("unlabeled", "unlabeled 只在 what=picture 时用", null);
        if (!what.equals("world_memory") && radius != null) check.error("radius", "radius 只在 what=world_memory 时用", null);
        if (what.equals("world_memory") && radius != null && radius < 1) {
            check.error("radius", "radius 至少为 1", "正整数（格）");
        }
        if (!what.equals("world_memory") && kind != null) check.error("kind", "kind 只在 what=world_memory 时用", null);
        if (!check.ok()) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.INVALID_PARAMETER,
                    "参数有 " + check.errors().size() + " 处问题", check.errors(), null));
        }
        if (what.equals("picture")) {
            return picture(id, Boolean.TRUE.equals(withMenu), Boolean.TRUE.equals(unlabeled), check.notes());
        }
        MemoryKind kindFilter = kind == null ? null : MemoryKind.valueOf(kind.toUpperCase(Locale.ROOT));
        return ToolReply.WithImages.of(view(what, id, grid, radius, kindFilter, sections, check));
    }

    /**
     * world_memory 的分节取用：要哪几节（places / regions / records），不给或给了空数组都按三节全要看。
     * 只配 world_memory 用；点的节名必须是这三节之一。
     */
    private static Set<String> sections(JsonObject arguments, String what, RequestCheck check) {
        if (!arguments.has("sections")) return null;
        if (!what.equals("world_memory")) {
            check.error("sections", "sections 只在 what=world_memory 时用", null);
            return null;
        }
        JsonArray asked = arguments.getAsJsonArray("sections");
        if (asked == null || asked.isEmpty()) {
            check.error("sections", "sections 要是节名的数组：places、regions、records；不给就是三节都要", null);
            return null;
        }
        Set<String> parsed = new LinkedHashSet<>();
        for (int index = 0; index < asked.size(); index++) {
            String section = asked.get(index).isJsonPrimitive() ? asked.get(index).getAsString() : null;
            if (section == null || !SECTIONS.contains(section)) {
                check.error("sections", "sections 里第 " + index + " 项不是节名：places、regions、records 之一", null);
                return null;
            }
            parsed.add(section);
        }
        return parsed;
    }

    private JsonObject view(String what, String id, Boolean grid, Integer radius, MemoryKind kindFilter,
            Set<String> sections, RequestCheck check) {
        List<String> viewNotes = new ArrayList<>();
        JsonObject data = clientThread.call(
                context -> view(what, id, Boolean.TRUE.equals(grid), radius, kindFilter, sections, viewNotes, context));
        if (data == null) {
            return ToolReply.error(ErrorCode.UNKNOWN_ID, "没有观察编号或地标叫 " + id
                    + "；观察编号只在短时间内有效，重新 observe(scene) 拿新的编号");
        }
        viewNotes.forEach(check::note);
        return ToolReply.ok(data, check.notes(), null);
    }

    /** world_memory 的三节名字：sections 参数只认这几个。 */
    private static final Set<String> SECTIONS = Set.of("places", "regions", "records");

    /** 在客户端线程上读场景与记忆；找不到 detail 的对象时返回 null。 */
    private JsonObject view(String what, String id, boolean grid, Integer radius, MemoryKind kindFilter,
            Set<String> sections, List<String> notes, TickContext context) {
        Scene currentScene = scene.get();
        WorldMemory currentMemory = memory.get();
        if (currentScene == null || currentMemory == null) {
            throw new ClientThread.NotInWorld();
        }
        PlayerContext player = context.player();
        String dimension = player == null || player.level() == null
                ? null : player.level().dimension().location().toString();
        Protection guard = protection == null ? null : protection.get();
        WorldMemoryView.ContainerOrigin recordOrigin =
                WorldMemoryView.originFrom(currentScene.facilityKinds(), guard, dimension);
        JsonObject view = switch (what) {
            case "self" -> self(currentScene, context);
            case "detail" -> DetailView.of(currentScene, currentMemory, id, dimension, guard).orElse(null);
            case "places" -> WorldMemoryView.placesView(currentMemory, currentScene.self(), dimension);
            case "world_memory" -> WorldMemoryView.of(currentMemory, currentScene.self(), dimension,
                    radius, kindFilter, recordOrigin, notes::add, sections);
            case "menu" -> menu();
            default -> SceneView.scene(currentScene, grid,
                    DetailView.facilityOrigin(currentScene.facilityKinds(), currentMemory, recordOrigin));
        };
        // 看了自己、周围或某样东西：角色闲着时顺着看过去（开背包、转头、开手边的箱子）。
        // 记忆、界面在世界里没有对应的东西可看，不演；读到的内容照旧当场返回，不等角色看完。
        if (view != null) {
            switch (what) {
                case "self" -> looks.justRead(WhatWasRead.self());
                case "scene" -> looks.justRead(WhatWasRead.surroundings());
                case "detail" -> looks.justRead(WhatWasRead.seen(id));
                default -> { }
            }
        }
        return view;
    }

    /**
     * 拍画面：先问能不能拍（正在拍、离上一张不到一秒就不转头）；客户端线程上认 id、问闲时看转不转；
     * 等转到位（最多两秒半）；要下一帧（最多等一秒）；再回客户端线程按镜头的视线读画面里看得见的编号；
     * 最后在请求线程上摆标签、画标注、编 JPEG。图另走一个图片内容块，JSON 里只放和图对得上的事实。
     */
    private ToolReply.WithImages picture(String id, boolean withMenu, boolean unlabeled, List<String> notes) {
        CameraPictures cameras = pictures.cameras();
        if (cameras == null) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.UNAVAILABLE, "这个客户端没有接上取画面"));
        }
        CameraPictures.Asked refused = cameras.refusal(System.nanoTime());
        if (refused != null) return ToolReply.WithImages.of(ToolReply.error(ErrorCode.BUSY, busyMessage(refused)));
        Prepared prepared = clientThread.call(context -> prepare(context, id));
        if (prepared.unknown() != null) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.UNKNOWN_ID, prepared.unknown()));
        }
        boolean arrived = waitForTurn(prepared.turn());
        CameraPictures.Asked asked = cameras.request(withMenu && prepared.menuOpen() != null, System.nanoTime());
        if (!(asked instanceof CameraPictures.Asked.Accepted accepted)) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.BUSY, busyMessage(asked)));
        }
        CameraPicture picture = waitForFrame(cameras, accepted.picture());
        if (picture == null) {
            return ToolReply.WithImages.of(ToolReply.error(ErrorCode.UNAVAILABLE,
                    "游戏这一秒没画出新的一帧画面，窗口可能最小化了；把游戏窗口还原后再拍"));
        }
        PictureView.Facts facts = clientThread.call(context -> facts(context, picture, prepared, arrived, withMenu,
                unlabeled));
        PictureView.Result result = PictureView.of(picture, facts);
        byte[] jpeg = PictureEncoding.jpeg(picture, result.placements());
        return new ToolReply.WithImages(ToolReply.ok(result.view(), notes, null),
                List.of(new ToolReply.Image("image/jpeg", jpeg)));
    }

    /**
     * 客户端线程上认好的要看的东西与转头的结论。
     *
     * @param id       给的 id（观察编号转成小写）；没给为 null
     * @param building id 是建筑档案名时那一栋；不是为 null
     * @param landmark id 是记住的地点名时那一格
     * @param unknown  id 认不出时说给调用方的一句话；认出来了为 null
     */
    private record Prepared(String id, BuildingArchive building, Optional<WorldPosition> landmark,
                            PictureLook.Turn turn, String notTurnedBecause, String menuOpen, String unknown) {}

    // 认 id：观察编号查场景；名字先认建筑档案（存档案时同名记成了地点，那就是同一栋），再认记住的地点。
    // 认出来了就问闲时看转不转；不给 id 时只要闲时看正看着别处，就先转回站定朝向。
    private Prepared prepare(TickContext context, String id) {
        Scene currentScene = scene.get();
        WorldMemory currentMemory = memory.get();
        if (currentScene == null || currentMemory == null) throw new ClientThread.NotInWorld();
        String menuOpen = menuKind();
        boolean temporary = table.occupiedByTemporaryTask().isPresent();
        boolean automation = context.player() != null && context.player().input() != null
                && context.player().input().automationOwnsControls();
        // 人按了 F8 收回，和进世界以后还没下过目标、控制权还没交给自动化，是两种处境，没转的原因分开说。
        boolean tookOver = context.player() != null && context.player().input() != null
                && context.player().input().humanTookOver();
        LooksForPicture looks = Objects.requireNonNullElse(pictures.looks().get(), LooksForPicture.NONE);
        if (id == null) {
            PictureLook.Turn turn = looks.lookForPicture(context, new PictureLook.Subject.Standing(), automation, temporary);
            return new Prepared(null, null, Optional.empty(), turn, null, menuOpen, null);
        }
        String trimmed = id.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (normalized.matches("[efbs][0-9]+")) {
            if (currentScene.lookup(normalized).isEmpty()) {
                return unknown("没有观察编号 " + normalized + "；观察编号只在短时间内有效，重新 observe(scene) 拿新的编号");
            }
            PictureLook.Turn turn = looks.lookForPicture(context, new PictureLook.Subject.Seen(normalized), automation, temporary);
            return new Prepared(normalized, null, Optional.empty(), turn, whyNot(turn, tookOver), menuOpen, null);
        }
        Optional<BuildingArchive> building = building(context, trimmed);
        if (building.isPresent()) {
            var bounds = building.get().bounds();
            double x = (bounds.min().getX() + bounds.max().getX() + 1) / 2.0;
            double y = (bounds.min().getY() + bounds.max().getY() + 1) / 2.0;
            double z = (bounds.min().getZ() + bounds.max().getZ() + 1) / 2.0;
            PictureLook.Turn turn = looks.lookForPicture(context, new PictureLook.Subject.Point(x, y, z, false), automation, temporary);
            return new Prepared(trimmed, building.get(), Optional.empty(), turn, whyNot(turn, tookOver), menuOpen, null);
        }
        Optional<WorldPosition> place = currentMemory.place(trimmed);
        if (place.isEmpty()) {
            return unknown("没有观察编号、记住的地点或建筑档案叫 " + trimmed
                    + "；观察编号只在短时间内有效，重新 observe(scene) 拿新的编号");
        }
        PictureLook.Turn turn = looks.lookForPicture(context, new PictureLook.Subject.Seen(trimmed), automation, temporary);
        return new Prepared(trimmed, null, place, turn, whyNot(turn, tookOver), menuOpen, null);
    }

    private static Prepared unknown(String message) {
        return new Prepared(null, null, Optional.empty(), new PictureLook.Turn.NoNeed(), null, null, message);
    }

    // 这个世界、这个维度里叫这个名的一栋。
    private Optional<BuildingArchive> building(TickContext context, String name) {
        BuildingArchives archives = pictures.archives().get();
        if (archives == null || context.player() == null || context.player().level() == null) return Optional.empty();
        String dimension = context.player().level().dimension().location().toString();
        return archives.find(name).filter(archive -> archive.dimension().equals(dimension));
    }

    // 没转的原因说成调用方照着就能做的话：手上的目标带上编号，告诉它怎么暂停、怎么恢复。
    private String whyNot(PictureLook.Turn turn, boolean tookOver) {
        if (!(turn instanceof PictureLook.Turn.NotTurning notTurning)) {
            return turn instanceof PictureLook.Turn.NoNeed ? "它此刻不在场景里，没处可转" : null;
        }
        return switch (notTurning.why()) {
            case HUMAN_CONTROLS -> tookOver ? "人按 F8 拿着角色，镜头在人手里；拍的是人正看着的"
                    : "进世界以后还没下过目标，控制权还没交给自动化，镜头照人看着的拍；下一个目标开始后再拍就会转头";
            // 临时任务自己的一句话只包一层：不借 observe(self) 那句"没有在做的目标"，有主目标时那句话不对。
            case TEMPORARY_TASK -> "生存需求的临时任务「" + table.temporaryTaskDoing().orElse("") + "」占着身体；"
                    + "等它做完再拍";
            case WORKING -> working();
            case NOT_ON_FOOT -> "没脚踏实地（骑着、坐着、在飞、在睡或悬在空中），转头会带着坐骑转或把飞行带歪";
            case OTHERS_SCREEN -> "屏幕上开着别人开的界面，不碰它";
        };
    }

    private String working() {
        GoalRun main = table.mainGoal().orElse(null);
        if (main == null) return "手上的任务在推进，转头会带歪它的瞄准和走路";
        String doing = table.doing(main.id()).orElse(main.goal().ability());
        return "手上在做「" + doing + "」（目标 " + main.id() + "），转头会带歪它的瞄准和走路；要转过去看，先 "
                + "goal(operation=pause, goal_id=" + main.id() + ")，看完 goal(operation=resume, goal_id=" + main.id() + ")";
    }

    // 等转到位：转到位交回真；不转、转够两秒没到位、被收手交回假。不给 id、用不着转的算到位。
    private static boolean waitForTurn(PictureLook.Turn turn) {
        if (turn instanceof PictureLook.Turn.NoNeed) return true;
        if (!(turn instanceof PictureLook.Turn.Turning turning)) return false;
        try {
            return Boolean.TRUE.equals(turning.arrived().get(TURN_WAIT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException late) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // 等下一帧画到取图点；一秒没等到就撤掉这一张，交回 null。
    private static CameraPicture waitForFrame(CameraPictures cameras, CompletableFuture<CameraPicture> picture) {
        try {
            return picture.get(FRAME_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException late) {
            cameras.giveUp(picture);
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cameras.giveUp(picture);
            return null;
        } catch (ExecutionException failure) {
            throw new IllegalStateException("取画面出错：" + failure.getCause(), failure.getCause());
        }
    }

    // 拍下以后回客户端线程：画面里看得见的编号、镜头朝哪、要看的那样东西的外框、此刻开着的界面。
    private PictureView.Facts facts(TickContext context, CameraPicture picture, Prepared prepared, boolean arrived,
                                    boolean withMenu, boolean unlabeled) {
        Scene currentScene = scene.get();
        if (currentScene == null || context.player() == null) throw new ClientThread.NotInWorld();
        PictureView.Camera camera = PictureSightings.camera(picture, context.player().facingYaw());
        PictureView.Toward toward = null;
        if (prepared.id() != null) {
            boolean turned = prepared.turn() instanceof PictureLook.Turn.Turning && arrived;
            String reason = turned ? null : prepared.notTurnedBecause() != null ? prepared.notTurnedBecause()
                    : "转了两秒没转到位，拍的是转到一半的画面";
            if (prepared.building() != null) {
                var bounds = prepared.building().bounds();
                toward = PictureSightings.building(context, picture, prepared.id(), bounds.min(), bounds.max(), turned,
                        reason);
            } else {
                toward = PictureSightings.toward(context, currentScene, picture, prepared.id(), prepared.landmark(),
                        turned, reason).orElse(new PictureView.Toward(prepared.id(), turned, reason, null, false, false));
            }
        }
        return new PictureView.Facts(camera, PictureSightings.read(context, currentScene, picture), menuKind(),
                withMenu, toward, unlabeled);
    }

    // 此刻开着的界面是哪种（取值同 observe(menu) 的 kind）；没开、读不到为 null。
    private String menuKind() {
        MenuView.Reads reads = menus.get();
        if (reads == null) return null;
        JsonObject menu = MenuView.read(reads);
        return menu.has("open") && menu.get("open").getAsBoolean() && menu.has("kind")
                ? menu.get("kind").getAsString() : null;
    }

    private static String busyMessage(CameraPictures.Asked asked) {
        return switch (asked) {
            case CameraPictures.Asked.TooSoon soon -> "上一张刚拍完，" + soon.waitMillis() + " 毫秒后再拍";
            case CameraPictures.Asked.AlreadyTaking taking -> "正在拍上一张，稍后再拍";
            case CameraPictures.Asked.Accepted accepted -> "正在拍上一张，稍后再拍";
        };
    }

    // 此刻开着的界面：在客户端线程上当场读，读不到界面读端说明不在世界里。
    private JsonObject menu() {
        MenuView.Reads reads = menus.get();
        if (reads == null) throw new ClientThread.NotInWorld();
        return MenuView.read(reads);
    }

    private JsonObject self(Scene currentScene, TickContext context) {
        if (currentScene.self() == null) {
            throw new ClientThread.Busy("刚进世界，还没看清自己，下一刻再看");
        }
        PlayerContext player = context.player();
        String dimension = player == null || player.level() == null
                ? null : player.level().dimension().location().toString();
        GoalRun main = table.mainGoal().orElse(null);
        // 没有主目标时身体也可能被生存需求的临时任务占着（闲着被打了在自卫）：doing 写明被哪件占着，
        // 不写"空闲"，对面才不用靠事件流猜角色为什么在跑动、战斗。
        String doing = main == null ? table.occupiedByTemporaryTask().orElse(null) : table.doing(main.id()).orElse(null);
        Boolean automation = player == null || player.input() == null ? null : player.input().automationOwnsControls();
        boolean tookOver = player != null && player.input() != null && player.input().humanTookOver();
        // 背包里的吃的按游戏的食物组件写上补多少、有什么效果。
        ReadsFoodValues foods = player == null || player.localPlayer() == null ? null : new ClientFoodValues(player.localPlayer());
        JsonObject view = SelfView.of(currentScene.self(), dimension, player == null ? null : player.backpack(), foods,
                main, doing, automation, tookOver);
        // 还成立的提醒原样交出：一句话、证据数值与阈值、可以考虑的做法；没有提醒时是空数组。
        view.add("reminders", REMINDER_JSON.toJsonTree(reminders.get()));
        // 预留着什么、身上各有几件：没有预留时不写这一项。
        SelfView.addReserved(view, reserved.get());
        // 角色是谁、在哪个世界：宿主认出聊天和播报里哪个名字是角色自己，按世界分开记事。
        SelfView.addIdentity(view, identity.get());
        // 死一次会怎样：死亡不掉落开没开、会在哪重生；LLM 看着它决定要不要寻死回家。
        SelfView.addAfterDeath(view, afterDeath.get());
        return view;
    }
}
