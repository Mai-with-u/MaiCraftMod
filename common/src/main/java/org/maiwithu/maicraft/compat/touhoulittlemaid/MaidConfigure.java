// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.menu.MenuConfirmation;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Change;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 改自己的女仆的设置，照真人在她界面里点按钮做：先核对每项的值（皮肤要在本机模型表里、工作要在她的工作列表里），
 * 已经是目标值的直接算完成；要改的就走到她跟前空手右键打开她的界面，一项项改——皮肤、工作、班次、跟随或驻守、拾取
 * 发车万女仆自己的包，装备从角色背包点进她的装备格——每项等同步下来的值变了再改下一项，改完关上界面。
 *
 * <p>游戏不让就如实交回：聊天栏冒出回应（服务器关了换皮肤）、弹出日程点确认屏（日程点太远或不在同一维度）、
 * 工作按钮是灰的，按游戏拒绝停在那一项；等满两秒没变按没能确认停下。她在睡觉时界面打不开，打开界面那一步如实说。
 */
final class MaidConfigure implements MaidSettings {

    /** 她认的设置项，按说明顺序。 */
    static final List<String> KEYS = List.of("skin", "work", "work_shift", "stay", "pickup",
            "mainhand", "offhand", "head", "chest", "legs", "feet");
    /** 每项改了以后最多等几刻看同步下来的值变没变。 */
    static final int CONFIRM_TICKS = 40;
    /** 工作 ID 省略命名空间时补上车万女仆自己的。 */
    private static final String WORK_NAMESPACE = TouhouLittleMaidCompat.MOD_ID + ":";

    private final TouhouLittleMaidCompat compat;
    private final PlayerServices services;
    private final MenuSteps steps;
    private final Worn worn;

    MaidConfigure(TouhouLittleMaidCompat compat, PlayerServices services) {
        this(compat, services, new LiveMenuSteps(services),
                (maid, slot) -> maid instanceof LivingEntity living ? living.getItemBySlot(slot) : ItemStack.EMPTY);
    }

    /** 离线测试用：开界面、在界面里发包、读她身上穿戴的由替身做。 */
    MaidConfigure(TouhouLittleMaidCompat compat, PlayerServices services, MenuSteps steps, Worn worn) {
        this.compat = Objects.requireNonNull(compat, "compat");
        this.services = Objects.requireNonNull(services, "services");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.worn = Objects.requireNonNull(worn, "worn");
    }

    /** 开她的界面、在开着的界面里经车万女仆自己的包做一下。 */
    interface MenuSteps {

        /** 走到她跟前空手右键，打开她的界面并认出布局。 */
        MenuOpening open(int entityId, Permissions permissions);

        /** 在开着的界面里发一个车万女仆的包，等 confirmed 成立；确认窗到了还没成立按没能确认失败。 */
        Action submit(OpenedMenu menu, String what, Runnable send, BooleanSupplier confirmed, int timeoutTicks);
    }

    /** 她身上某一格此刻穿戴着什么（原版同步的手持与盔甲）。 */
    @FunctionalInterface
    interface Worn {
        ItemStack held(Entity maid, EquipmentSlot slot);
    }

    @Override public Optional<Maid> maid(PlayerContext context, Entity creature) {
        if (!compat.isMaid(creature)) return Optional.empty();
        return Optional.of(new Maid() {
            @Override public String name() {
                return compat.state(creature).name();
            }

            @Override public List<String> keys() {
                return KEYS;
            }

            @Override public Configure configure(List<Setting> settings, Permissions permissions) {
                return new Configuring(creature, settings, permissions);
            }
        });
    }

    /** 装备项对应的原版装备格与她界面里的槽号。 */
    private record Gear(EquipmentSlot slot, int menuSlot) {}

    private static final Map<String, Gear> GEAR = Map.of(
            "mainhand", new Gear(EquipmentSlot.MAINHAND, MaidMenuLayout.MAINHAND),
            "offhand", new Gear(EquipmentSlot.OFFHAND, MaidMenuLayout.OFFHAND),
            "head", new Gear(EquipmentSlot.HEAD, MaidMenuLayout.HEAD),
            "chest", new Gear(EquipmentSlot.CHEST, MaidMenuLayout.CHEST),
            "legs", new Gear(EquipmentSlot.LEGS, MaidMenuLayout.LEGS),
            "feet", new Gear(EquipmentSlot.FEET, MaidMenuLayout.FEET));

    /** 改设置的阶段：核对 → 开界面 → 逐项改 → 关界面 → 完。 */
    private enum Stage { PLAN, OPEN, APPLY, CLOSE, DONE }

    /** 改一只女仆设置的动作。 */
    private final class Configuring implements Configure {

        private final Entity maid;
        private final List<Setting> settings;
        private final Permissions permissions;
        private final List<Setting> pending = new ArrayList<>();
        private final List<Applied> applied = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> workHint = new ArrayList<>();
        private final List<Change> changes = new ArrayList<>();
        private final List<Change> unconfirmed = new ArrayList<>();
        private String shiftNow;
        private Stage stage = Stage.PLAN;
        private MenuOpening opening;
        private OpenedMenu menu;
        private Action current;
        private int index;
        private String before;
        private long chatMark;
        /** 停在某一项的原因：关上界面以后按它失败；按没能确认停下的为 null。 */
        private Problem stopped;

        Configuring(Entity maid, List<Setting> settings, Permissions permissions) {
            this.maid = maid;
            this.settings = List.copyOf(settings);
            this.permissions = permissions;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (stage != Stage.DONE && stage != Stage.CLOSE && (maid.isRemoved() || !maid.isAlive())) {
                return stop(Problem.of(Problem.Kind.TARGET_GONE, "她不在了，设置没改完", null));
            }
            return switch (stage) {
                case PLAN -> plan();
                case OPEN -> open(context);
                case APPLY -> apply(context);
                case CLOSE -> close(context);
                case DONE -> stopped == null ? ActionStatus.done() : ActionStatus.failed(stopped);
            };
        }

        // 先核对每项的值；不认识的值一项都不改就交回（真人只能从界面的列表里挑）。已经是目标值的记成本来就是。
        private ActionStatus plan() {
            MaidState state = compat.state(maid);
            for (Setting setting : settings) {
                Optional<Problem> invalid = invalid(setting);
                if (invalid.isPresent()) {
                    stage = Stage.DONE;
                    stopped = invalid.get();
                    return ActionStatus.failed(stopped);
                }
                if (already(setting, state)) {
                    skipped.add(setting.key());
                } else {
                    pending.add(setting);
                }
            }
            if (pending.isEmpty()) {
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            // 装备项要的东西角色背包里得有：没有就交回缺东西，不替 LLM 去拿。
            for (Setting setting : pending) {
                if (GEAR.containsKey(setting.key()) && !setting.value().isEmpty() && !carried(setting.value())) {
                    stage = Stage.DONE;
                    stopped = Problem.of(Problem.Kind.NEED_ITEM, "角色背包里没有 " + setting.value() + "，放不进她的 "
                            + setting.key(), "先 obtain 拿到再放");
                    return ActionStatus.failed(stopped);
                }
            }
            stage = Stage.OPEN;
            return ActionStatus.progressed();
        }

        // 走到她跟前空手右键打开她的界面；她在睡觉、不是你的女仆，界面打不开，打开那一步如实说。
        private ActionStatus open(TickContext context) {
            if (opening == null) opening = steps.open(maid.getId(), permissions);
            ActionStatus status = opening.tick(context);
            if (status instanceof ActionStatus.Done) {
                menu = opening.opened().orElse(null);
                opening = null;
                if (menu == null) return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME, "她的界面没打开", null));
                stage = Stage.APPLY;
                return ActionStatus.progressed();
            }
            if (status instanceof ActionStatus.Failed failed) {
                opening.close();
                opening = null;
                stage = Stage.DONE;
                stopped = failed.problem();
            }
            return status;
        }

        // 逐项改：开始一项时记下聊天栏读到哪、原来的值；做完记一条变化，换下一项；等满没变停下。
        private ActionStatus apply(TickContext context) {
            if (index >= pending.size()) {
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            Setting setting = pending.get(index);
            if (current == null) {
                chatMark = services.refusals().chatMark();
                before = value(setting.key(), compat.state(maid));
                Optional<Problem> refused = start(setting);
                if (refused.isPresent()) return stop(refused.get());
            }
            // 开驻守时日程点太远、不在同一维度：车万女仆弹一个确认屏拒绝，关掉它如实交回。
            if (compat.schedulePosScreenOpen()) {
                compat.closeSchedulePosScreen();
                return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "车万女仆弹出了日程点确认屏：她的日程点离她超过 32 格或不在同一维度，驻守没开",
                        "带她到日程点附近再开驻守，或在她的界面里重设日程点"));
            }
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Done) {
                current = null;
                String after = value(setting.key(), compat.state(maid));
                applied.add(new Applied(setting.key(), before, after));
                changes.add(new Change(Change.Kind.ENTITY_AFFECTED, compat.state(maid).name(), 1,
                        setting.key() + " 从 " + before + " 改成了 " + after));
                if (setting.key().equals("work")) shiftNow = compat.state(maid).shiftNow().word();
                index++;
                return ActionStatus.progressed();
            }
            if (status instanceof ActionStatus.Failed failed) {
                current.close();
                current = null;
                // 这一项自己说清了原因（格子不收、背包里没了）：照原因停下。
                if (failed.problem().kind() != Problem.Kind.STUCK) return stop(failed.problem());
                // 等满没变：这段时间聊天栏有回应就是游戏不让（附原话），没有就按没能确认停下。
                List<String> notices = services.refusals().systemMessagesSince(chatMark);
                if (!notices.isEmpty()) {
                    return stop(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                            setting.key() + " 没改成，游戏回了：" + String.join(" / ", notices), null));
                }
                unconfirmed.add(new Change(Change.Kind.OTHER, setting.key(), 1,
                        "改成 " + setting.value() + " 的那一下发出去了，2 秒内没看到她的 " + setting.key() + " 变化"));
                index = pending.size();
                stage = Stage.CLOSE;
                return ActionStatus.progressed();
            }
            return status;
        }

        // 开始改一项：设置项发车万女仆的包，装备项点格子；工作按钮是灰的就不点，如实交回界面写的条件。
        private Optional<Problem> start(Setting setting) {
            MaidState state = compat.state(maid);
            int id = maid.getId();
            String value = setting.value();
            switch (setting.key()) {
                case "skin" -> current = steps.submit(menu, "换皮肤", () -> compat.setSkin(id, value),
                        () -> compat.state(maid).skinId().equals(value), CONFIRM_TICKS);
                case "work" -> {
                    String workId = workId(value);
                    MaidReads.Work work = compat.works(maid).stream().filter(each -> each.id().equals(workId))
                            .findFirst().orElseThrow();
                    workHint.clear();
                    workHint.addAll(work.conditions());
                    if (!work.enabled()) {
                        return Optional.of(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                                "她界面里「" + work.name() + "」的按钮是灰的，点不了：" + String.join("；", work.conditions()),
                                null));
                    }
                    current = steps.submit(menu, "换工作", () -> compat.setWork(id, workId),
                            () -> compat.state(maid).workId().equals(workId), CONFIRM_TICKS);
                }
                case "work_shift" -> {
                    MaidState.Shift shift = shift(value);
                    current = steps.submit(menu, "改班次", () -> compat.setConfig(id, state.stayInArea(), state.pickup(),
                            state.rideable(), shift), () -> compat.state(maid).shift() == shift, CONFIRM_TICKS);
                }
                case "stay" -> {
                    boolean inArea = value.equals("in_area");
                    current = steps.submit(menu, "改跟随或驻守", () -> compat.setConfig(id, inArea, state.pickup(),
                            state.rideable(), state.shift()),
                            () -> compat.state(maid).stayInArea() == inArea, CONFIRM_TICKS);
                }
                case "pickup" -> {
                    boolean pickup = Boolean.parseBoolean(value);
                    current = steps.submit(menu, "改拾取", () -> compat.setConfig(id, state.stayInArea(), pickup,
                            state.rideable(), state.shift()),
                            () -> compat.state(maid).pickup() == pickup, CONFIRM_TICKS);
                }
                default -> current = new Equipping(GEAR.get(setting.key()), value);
            }
            return Optional.empty();
        }

        // 关上她的界面；停在某一项的，关好之后按那个原因失败。
        private ActionStatus close(TickContext context) {
            if (menu == null) return finish();
            if (current == null) current = menu.closing();
            ActionStatus status = current.tick(context);
            if (status instanceof ActionStatus.Running) return status;
            current = null;
            menu = null;
            return finish();
        }

        private ActionStatus finish() {
            stage = Stage.DONE;
            return stopped == null ? ActionStatus.done() : ActionStatus.failed(stopped);
        }

        // 停在这一项：记下原因，先关上界面再失败。
        private ActionStatus stop(Problem problem) {
            if (current != null) current.close();
            current = null;
            stopped = problem;
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        // 值对不对：皮肤要在本机模型表里、工作要在她的工作列表里，其余按取值表；装备项给物品 ID、标签或空字符串。
        private Optional<Problem> invalid(Setting setting) {
            String value = setting.value();
            return switch (setting.key()) {
                case "skin" -> {
                    Map<String, String> skins = compat.skins();
                    yield skins.containsKey(value) ? Optional.empty() : Optional.of(Problem.of(
                            Problem.Kind.INVALID_PARAMETER, "本机模型表里没有皮肤 " + value,
                            "最相近的：" + String.join("、", closest(value, skins))));
                }
                case "work" -> {
                    List<MaidReads.Work> works = compat.works(maid);
                    String workId = workId(value);
                    yield works.stream().anyMatch(work -> work.id().equals(workId)) ? Optional.empty()
                            : Optional.of(Problem.of(Problem.Kind.INVALID_PARAMETER, "她的工作列表里没有 " + value,
                                    "能切的工作：" + String.join("、", works.stream()
                                            .map(work -> work.id() + "（" + work.name() + "）").toList())));
                }
                case "work_shift" -> oneOf(setting, List.of("day", "night", "all"));
                case "stay" -> oneOf(setting, List.of("with_you", "in_area"));
                case "pickup" -> oneOf(setting, List.of("true", "false"));
                default -> Optional.empty();
            };
        }

        private boolean already(Setting setting, MaidState state) {
            Gear gear = GEAR.get(setting.key());
            if (gear != null) {
                ItemStack held = worn.held(maid, gear.slot());
                return setting.value().isEmpty() ? held.isEmpty() : !held.isEmpty() && matches(held, setting.value());
            }
            String target = setting.key().equals("work") ? workId(setting.value()) : setting.value();
            return target.equals(value(setting.key(), state));
        }

        // 一项此刻的值，写成和参数一样的字符串。
        private String value(String key, MaidState state) {
            Gear gear = GEAR.get(key);
            if (gear != null) {
                ItemStack held = worn.held(maid, gear.slot());
                return held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            }
            return switch (key) {
                case "skin" -> state.skinId();
                case "work" -> state.workId();
                case "work_shift" -> state.shift().word();
                case "stay" -> state.stayInArea() ? "in_area" : "with_you";
                case "pickup" -> Boolean.toString(state.pickup());
                default -> "";
            };
        }

        @Override public void pause() {
            // 被打断：界面关掉，回来从开界面那一步接着改没改的项。
            if (current != null) current.close();
            current = null;
            if (opening != null) opening.close();
            opening = null;
            if (menu != null) menu.abandon();
            menu = null;
            if (stage == Stage.APPLY || stage == Stage.OPEN) stage = Stage.OPEN;
        }

        @Override public void close() {
            if (current != null) current.close();
            if (opening != null) opening.close();
            if (menu != null) menu.abandon();
        }

        @Override public String describe() {
            return switch (stage) {
                case PLAN -> "核对要改的设置";
                case OPEN -> "打开她的界面";
                case APPLY -> "改她的 " + (index < pending.size() ? pending.get(index).key() : "设置");
                case CLOSE -> "关上她的界面";
                case DONE -> "改完了";
            };
        }

        @Override public Report report() {
            return new Report(applied, skipped, workHint, shiftNow, changes, unconfirmed);
        }

        /**
         * 把角色背包里的一件点进她的装备格：左键拿起整堆、左键放进她那一格（原有的换到光标上）、光标上还有东西就放回原格；
         * 空字符串是把她那一格的东西快速移回角色背包。每刻最多点一下，上一下没结清不点；她那一格变成目标才算做成。
         */
        private final class Equipping implements Action {

            private final Gear gear;
            private final String value;
            private int step;
            private int sourceSlot = -1;
            private int waited;
            private boolean refused;

            Equipping(Gear gear, String value) {
                this.gear = gear;
                this.value = value;
            }

            @Override public ActionStatus tick(TickContext context) {
                // 只在动手前看一眼已经是不是目标：点进她那一格以后光标上可能还拿着换下来的那件，要先放回去才算完。
                if (step == 0 && done()) return ActionStatus.done();
                if (menu.busy()) return ActionStatus.running();
                if (value.isEmpty()) {
                    if (step == 0 && menu.quickMove(gear.menuSlot())) step = 1;
                    return waitFor();
                }
                switch (step) {
                    case 0 -> {
                        sourceSlot = sourceSlot();
                        if (sourceSlot < 0) {
                            return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM,
                                    "角色背包里没有 " + value, "先 obtain 拿到再放"));
                        }
                        if (menu.click(sourceSlot, 0)) {
                            menu.noteCursorTakenFrom(sourceSlot);
                            step = 1;
                        }
                        return ActionStatus.progressed();
                    }
                    case 1 -> {
                        if (menu.click(gear.menuSlot(), 0)) step = 2;
                        return ActionStatus.progressed();
                    }
                    case 2 -> {
                        // 光标上还是要放的那件：她那一格没收下（盔甲格只收能穿在这个位置的），放回原格后如实交回。
                        // 光标上是别的：那是她那一格原来的东西换了上来（或盔甲格只收一件，余下的还在光标上），同样放回刚才那一格。
                        if (!menu.cursorEmpty() && matches(menu.cursor().stack(), value)) refused = true;
                        if (menu.cursorEmpty() || menu.click(sourceSlot, 0)) step = 3;
                        return ActionStatus.progressed();
                    }
                    default -> {
                        if (refused && menu.cursorEmpty()) {
                            return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                                    "她的 " + gear.slot() + " 格没收下 " + value + "，放回了角色背包",
                                    "盔甲格只收能穿在那个位置的东西"));
                        }
                        return waitFor();
                    }
                }
            }

            private ActionStatus waitFor() {
                if (done()) return ActionStatus.done();
                if (++waited > CONFIRM_TICKS) {
                    return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "她的 " + gear.slot() + " 没变", null));
                }
                return ActionStatus.running();
            }

            private boolean done() {
                ItemStack held = worn.held(maid, gear.slot());
                return value.isEmpty() ? held.isEmpty() : !held.isEmpty() && matches(held, value);
            }

            // 角色背包那一侧第一格对得上的东西。
            private int sourceSlot() {
                return menu.reading().map(reading -> {
                    for (int i = 0; i < reading.playerSlotIds().size(); i++) {
                        ItemStack stack = reading.playerSnapshots().get(i).stack();
                        if (!stack.isEmpty() && matches(stack, value)) return reading.playerSlotIds().get(i);
                    }
                    return -1;
                }).orElse(-1);
            }

            @Override public String describe() {
                return value.isEmpty() ? "把她的 " + gear.slot() + " 拿回背包" : "把 " + value + " 放进她的 " + gear.slot();
            }
        }
    }

    // 物品对不对得上：给 ID 比 ID，给 # 标签比标签。
    private boolean matches(ItemStack stack, String value) {
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return value.startsWith("#") ? services.itemTags().tagsOf(itemId).contains(value.substring(1))
                : itemId.equals(value);
    }

    // 角色背包里有没有这件。
    private boolean carried(String value) {
        return services.backpack().stacks().stream().anyMatch(stack -> value.startsWith("#")
                ? services.itemTags().tagsOf(stack.itemId()).contains(value.substring(1))
                : stack.itemId().equals(value));
    }

    private static String workId(String value) {
        return value.contains(":") ? value : WORK_NAMESPACE + value;
    }

    private static MaidState.Shift shift(String value) {
        return switch (value) {
            case "night" -> MaidState.Shift.NIGHT;
            case "all" -> MaidState.Shift.ALL;
            default -> MaidState.Shift.DAY;
        };
    }

    private static Optional<Problem> oneOf(Setting setting, List<String> choices) {
        if (choices.contains(setting.value())) return Optional.empty();
        return Optional.of(Problem.of(Problem.Kind.INVALID_PARAMETER,
                setting.key() + " 只能是 " + String.join(" / ", choices) + "，收到的是 " + setting.value(), null));
    }

    /** 本机模型表里和给的值最相近的三个皮肤 ID：ID 或显示名里含着它的最先，其余按编辑距离。 */
    static List<String> closest(String value, Map<String, String> skins) {
        return skins.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, String> entry) -> distance(value, entry)))
                .limit(3).map(Map.Entry::getKey).toList();
    }

    private static int distance(String value, Map.Entry<String, String> skin) {
        if (skin.getKey().contains(value) || skin.getValue().contains(value)) return 0;
        return Math.min(editDistance(value, skin.getKey()), editDistance(value, skin.getValue())) + 1;
    }

    private static int editDistance(String a, String b) {
        int[] row = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) row[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int diagonal = row[0];
            row[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int above = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j - 1] + 1),
                        diagonal + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                diagonal = above;
            }
        }
        return row[b.length()];
    }

    /** 生产实现：开界面走"点开生物身上的界面"，发包走开着的界面的模组协议入口、按 confirmed 等确认。 */
    private static final class LiveMenuSteps implements MenuSteps {

        private final PlayerServices services;

        LiveMenuSteps(PlayerServices services) {
            this.services = services;
        }

        @Override public MenuOpening open(int entityId, Permissions permissions) {
            return ClientMenuOpening.ofEntity(entityId, false, permissions, services.menuLayouts(), services.approach(),
                    services.interactions(), services.toMainhand(), services.sneak(), services.context());
        }

        @Override public Action submit(OpenedMenu menu, String what, Runnable send, BooleanSupplier confirmed,
                int timeoutTicks) {
            return new Action() {
                private PendingMenuAction pending;

                @Override public ActionStatus tick(TickContext context) {
                    PlayerContext player = context.player();
                    if (player == null) return ActionStatus.running();
                    if (pending == null) {
                        // 本刻发不了（没轮到、上一下没结清）就下一刻再发。
                        Optional<PendingMenuAction> sent = menu.submitModAction(what, send,
                                (c, p) -> confirmed.getAsBoolean() ? MenuConfirmation.Verdict.APPLIED
                                        : MenuConfirmation.Verdict.PENDING, timeoutTicks);
                        if (sent.isEmpty()) return ActionStatus.running();
                        pending = sent.get();
                        return ActionStatus.progressed();
                    }
                    if (!pending.terminal()) pending = player.menuActions().poll(player, pending);
                    if (!pending.terminal()) return ActionStatus.running();
                    return pending.status() == PendingMenuAction.Status.CONFIRMED_APPLIED ? ActionStatus.done()
                            : ActionStatus.failed(Problem.of(Problem.Kind.STUCK, what + "，没能确认", null));
                }

                @Override public String describe() {
                    return what;
                }
            };
        }
    }
}
