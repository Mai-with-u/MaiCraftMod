// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.ability.machine.spi.ExchangePoint;
import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.FullRun;
import org.maiwithu.maicraft.ability.machine.spi.IngredientFeed;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.LineMaterials;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineSetting;
import org.maiwithu.maicraft.ability.machine.spi.MachineState;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.MountedPart;
import org.maiwithu.maicraft.ability.machine.spi.NetworkKind;
import org.maiwithu.maicraft.ability.machine.spi.NetworkLoad;
import org.maiwithu.maicraft.ability.machine.spi.NetworkReader;
import org.maiwithu.maicraft.ability.machine.spi.NetworkSummary;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.ability.machine.spi.RunRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.permission.ReadsRememberedPlaces;
import org.maiwithu.maicraft.behavior.recipe.RecipeLookup;
import org.maiwithu.maicraft.behavior.recipe.RecipeViewer;
import org.maiwithu.maicraft.behavior.recipe.ShownRecipe;
import org.maiwithu.maicraft.behavior.travel.planning.ReadsSeenTargets;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 机器能力离线测试共用的替身：机器类型、网络读取器、范围读端、现场视图、编号与地点、
 * 配方查看器、缺东西去拿、靠近、交互。测试类各自摆场景，这里只放机械。
 */
final class MachineTestDoubles {

    private MachineTestDoubles() {
    }

    /** 跑几刻后按给定结论收场的动作；记下被暂停、被收尾了几次。 */
    static final class Scripted implements Action {
        private final int ticks;
        private final ActionStatus end;
        private final Runnable onEnd;
        private int ran;
        int pauses;
        int closes;

        Scripted(int ticks, Runnable onEnd) {
            this(ticks, ActionStatus.done(), onEnd);
        }

        Scripted(int ticks, ActionStatus end, Runnable onEnd) {
            this.ticks = ticks;
            this.end = end;
            this.onEnd = onEnd;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (++ran >= ticks) {
                onEnd.run();
                return end;
            }
            return ActionStatus.progressed();
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public void close() {
            closes++;
        }

        @Override public String describe() {
            return "替身动作";
        }
    }

    /** 一直在做、停不得的一下（走着、等游戏确认切换）：记下被推进、叫停稳、暂停、收尾了几次。 */
    static final class Held implements Action {
        int ticks;
        int stops;
        int pauses;
        int closes;

        @Override public ActionStatus tick(TickContext context) {
            ticks++;
            return ActionStatus.running();
        }

        // 悬在半空：先到安全处再停要先落地，这几刻里一直没停稳。
        @Override public boolean stopSafely() {
            stops++;
            return true;
        }

        @Override public boolean stoppedSafely() {
            return false;
        }

        @Override public void pause() {
            pauses++;
        }

        @Override public void close() {
            closes++;
        }

        @Override public Interruptibility interruptibility() {
            return Interruptibility.UNSAFE_TO_STOP;
        }

        @Override public String describe() {
            return "停不得的一下";
        }
    }

    /** 一格读出的事实。 */
    record WorldCell(BlockPos pos, BlockState state, boolean hasBlockEntity) {
    }

    /** 机器类型替身：认领给定的方块状态谓词；设置与动作按测试摆的脚本回答。 */
    static final class FakeMachineType implements MachineType {
        final String id;
        final String name;
        final MachineRole role;
        private final Predicate<BlockState> covers;
        /** 设置项与现值：键 → 值。 */
        final Map<String, String> settingValues = new LinkedHashMap<>();
        /** 改设置时执行的动作：测试用它把读回的值真的改掉或让它打转。 */
        final Map<String, Runnable> onChange = new HashMap<>();
        /** 改设置时交出的动作；没给按 onChange 一刻做完。用来演"正对着机器切换、等游戏确认"。 */
        Action changing;
        /** 投料与取货脚本：每次调用弹一个；空了给空。 */
        final Deque<Action> feedActions = new ArrayDeque<>();
        final Deque<Action> takeActions = new ArrayDeque<>();
        /** 出口读数：测试随时间改这份表。 */
        final Map<String, Integer> output = new LinkedHashMap<>();
        /** 出口站在外面看不看得见；格子在界面里的机器看不见。 */
        boolean outputVisible = true;
        /** 产物掉在地上时落在哪一块；不掉地上为 null。 */
        AABB dropArea;
        Supplier<MachineState> state = () -> MachineState.unknown("读不到");
        Predicate<Installation> installationOk = installation -> false;
        Predicate<PartCell> partOk = part -> false;
        /** 这段现在装成了没有、这个部件装上没有：测试在动作效果里翻转。 */
        Predicate<Installation> installedOk = installation -> false;
        /** 拆整台时这一段自己放下的格；默认不认这种安装段。 */
        Function<Installation, Optional<List<BlockPos>>> placed = installation -> Optional.empty();
        /** 读现场时这种机器自己的抄法；默认没有，照抄方块。 */
        Function<Map<BlockPos, BlockState>, Optional<MachineCopy>> copier = cells -> Optional.empty();
        Predicate<PartCell> mountedOk = part -> false;
        /** 装安装段与装部件的动作脚本：每次调用弹一个；空了给空，按"这种机器不给装"对待。 */
        final Deque<Action> installActions = new ArrayDeque<>();
        final Deque<Action> mountActions = new ArrayDeque<>();
        /** 在物理结构上装安装段的动作脚本；空了给空，按"结构上不会装"对待。 */
        final Deque<Action> structureInstallActions = new ArrayDeque<>();
        /** 动手顺序的流水：施工、装段、装部件、改设置谁先谁后从这里看。 */
        final List<String> touched = new ArrayList<>();
        List<ExchangePoint> ports = List.of();
        /** 按格给的进出口与读数（加工处在这一格下面、几台机械手各拿什么）；没给按上面那份不分格的。 */
        Function<BlockPos, List<ExchangePoint>> portsAt;
        Function<BlockPos, MachineState> stateAt;
        /** 每格各面装着的部件；默认没有。 */
        Function<BlockPos, List<MountedPart>> parts = at -> List.of();
        /** 这种机器在它那种网上吃多少、给多少：审阅估算用；默认没有。 */
        Function<BlockState, Optional<NetworkLoad>> load = state -> Optional.empty();
        /** 机器自己从头做到尾的做法：默认没有，按配方那一套走。 */
        Function<RunRequest, Optional<FullRun>> fullRun = request -> Optional.empty();
        /** machine_run 的 item 对它是什么：默认按配方投料，发电机替身改成烧燃料或不吃料。 */
        FuelUse fuel = FuelUse.RECIPE;
        /** 配方里化学品这类原料能不能用物品投：默认不能；收到的总量记在 ingredientAmounts。 */
        Function<ShownIngredient, Optional<IngredientFeed>> ingredientFeed = ingredient -> Optional.empty();
        final List<Long> ingredientAmounts = new ArrayList<>();
        /** 投料的先后：每次 feed 记下投的是什么。 */
        final List<String> fedItems = new ArrayList<>();

        FakeMachineType(String id, String name, MachineRole role, Predicate<BlockState> covers) {
            this.id = id;
            this.name = name;
            this.role = role;
            this.covers = covers;
        }

        @Override public String id() {
            return id;
        }

        @Override public String name() {
            return name;
        }

        @Override public String modId() {
            return id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
        }

        @Override public boolean covers(BlockState state) {
            return covers.test(state);
        }

        @Override public MachineRole role() {
            return role;
        }

        @Override public List<ExchangePoint> exchangePoints(BlockState state, BlockPos at) {
            return portsAt != null ? portsAt.apply(at) : ports;
        }

        @Override public MachineState state(BlockPos at) {
            return stateAt != null ? stateAt.apply(at) : state.get();
        }

        @Override public List<MountedPart> mountedParts(BlockPos at) {
            return parts.apply(at);
        }

        @Override public Optional<NetworkLoad> load(BlockState blockState) {
            return load.apply(blockState);
        }

        @Override public Optional<String> installationProblem(Installation installation) {
            return installationOk.test(installation) ? Optional.empty()
                    : Optional.of("形状不合规则");
        }

        @Override public Optional<String> partProblem(PartCell part, BlockState host) {
            return partOk.test(part) ? Optional.empty() : Optional.of("宿主不对");
        }

        @Override public Optional<Action> install(Installation installation, Permissions permissions) {
            touched.add("install:" + installation.kind());
            return Optional.ofNullable(installActions.poll());
        }

        // 在物理结构上装：只有测试给了结构上的装法（胶）才肯装，记下是在结构上装的。
        @Override public Optional<Action> installOnStructure(Installation installation, Permissions permissions,
                BringsPlayerClose reach) {
            touched.add("install-on-structure:" + installation.kind());
            return Optional.ofNullable(structureInstallActions.poll());
        }

        @Override public boolean installed(Installation installation) {
            return installedOk.test(installation);
        }

        @Override public Optional<List<BlockPos>> placedBy(Installation installation) {
            return placed.apply(installation);
        }

        @Override public Optional<MachineCopy> copy(Map<BlockPos, BlockState> cells) {
            return copier.apply(cells);
        }

        @Override public Optional<Action> mount(PartCell part, Permissions permissions) {
            touched.add("mount:" + part.itemId());
            return Optional.ofNullable(mountActions.poll());
        }

        @Override public boolean mounted(PartCell part) {
            return mountedOk.test(part);
        }

        @Override public List<MachineSetting> settings(BlockPos at) {
            List<MachineSetting> out = new ArrayList<>();
            settingValues.forEach((key, value) -> out.add(
                    new MachineSetting(key, value, List.of(), key + " 的说明")));
            return out;
        }

        @Override public Optional<Action> change(BlockPos at, String key, String value, Permissions permissions) {
            if (changing != null) {
                touched.add("change:" + key);
                return Optional.of(changing);
            }
            Runnable effect = onChange.get(key);
            if (effect == null) {
                return Optional.empty();
            }
            touched.add("change:" + key);
            return Optional.of(new Scripted(1, effect));
        }

        @Override public Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions) {
            fedItems.add(itemId);
            return Optional.ofNullable(feedActions.poll());
        }

        @Override public boolean outputVisible(BlockPos at) {
            return outputVisible;
        }

        @Override public Optional<AABB> dropArea(BlockPos at) {
            return Optional.ofNullable(dropArea);
        }

        @Override public List<MachineState.Shown> output(BlockPos at) {
            List<MachineState.Shown> out = new ArrayList<>();
            output.forEach((itemId, count) -> out.add(new MachineState.Shown(itemId, count)));
            return out;
        }

        @Override public Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions) {
            return Optional.ofNullable(takeActions.poll());
        }

        @Override public Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
            return fullRun.apply(request);
        }

        @Override public FuelUse fuelUse(BlockPos at) {
            return fuel;
        }

        @Override public Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
            ingredientAmounts.add(amount);
            return ingredientFeed.apply(ingredient);
        }
    }

    /** 网络读取器替身：成员表与汇总按测试摆的给。 */
    static final class FakeNetworkReader implements NetworkReader {
        private final NetworkKind kind;
        private final Map<BlockPos, String> members = new HashMap<>();
        private NetworkSummary summary = null;
        /** 回答悬在路上：摆了就 summaryPending 一直为真，模拟要过真实时间才到的服务端读数。 */
        private boolean pending = false;
        /** 这种网怎么用方块接线；没给就是不会接线的网。 */
        LineMaterials lines;
        /** 问过几次整网读数。 */
        int asked;

        FakeNetworkReader(String id, String description) {
            this.kind = new NetworkKind(id, description);
        }

        void put(BlockPos at, String networkId) {
            members.put(at, networkId);
        }

        void unreadable(String why) {
            summary = NetworkSummary.unreadable("net1", why);
        }

        /** 让回答悬在路上。 */
        void holdAnswer() {
            pending = true;
        }

        /** 回答到了：之后的汇总就是它，不再在路上。 */
        void answer(NetworkSummary arrived) {
            summary = arrived;
            pending = false;
        }

        @Override public NetworkKind kind() {
            return kind;
        }

        @Override public boolean summaryPending(String networkId) {
            return pending;
        }

        @Override public Optional<String> membership(BlockPos at) {
            return Optional.ofNullable(members.get(at));
        }

        @Override public Optional<LineMaterials> lineMaterials() {
            return Optional.ofNullable(lines);
        }

        @Override public NetworkSummary summary(String networkId) {
            asked++;
            if (summary != null) {
                return summary;
            }
            Map<String, Object> readings = new LinkedHashMap<>();
            readings.put("成员数", members.size());
            return new NetworkSummary(networkId, true, readings, "");
        }
    }

    /** 范围读端替身：按脚本一轮一轮给，最后一直给扫完的空轮。 */
    static final class FakeArea implements ReadsMachineArea {
        private final Deque<Round> script = new ArrayDeque<>();

        void offer(Round round) {
            script.add(round);
        }

        @Override public Round scan(String dimension, BlockPos center, int radius) {
            return script.isEmpty() ? new Round(List.of(), 0, true, false) : script.poll();
        }
    }

    /** 现场视图替身：位置、维度与每格的方块状态按测试摆的给。 */
    static final class FakeWorld implements MachineWorldView {
        String dimension = "minecraft:overworld";
        BlockPos playerAt = BlockPos.ZERO;
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        boolean inWorld = true;
        /** 地上躺着的掉落物：物品 → 件数，不分在哪一块。 */
        final Map<String, Integer> onGround = new HashMap<>();

        @Override public Optional<Spot> playerSpot() {
            return inWorld ? Optional.of(new Spot(playerAt, dimension)) : Optional.empty();
        }

        @Override public Optional<BlockState> stateAt(BlockPos at) {
            return Optional.ofNullable(blocks.get(at));
        }

        @Override public int itemsOnGround(AABB area, String itemId) {
            return onGround.getOrDefault(itemId, 0);
        }
    }

    /** 观察编号替身。 */
    static final class FakeSeen implements ReadsSeenTargets {
        final Map<String, WorldPosition> positions = new HashMap<>();

        @Override public Optional<WorldPosition> positionOf(String observationId) {
            return Optional.ofNullable(positions.get(observationId));
        }
    }

    /** 记过地点替身。 */
    static final class FakePlaces implements ReadsRememberedPlaces {
        final Map<String, WorldPosition> places = new HashMap<>();

        @Override public Optional<WorldPosition> place(String name) {
            return Optional.ofNullable(places.get(name));
        }
    }

    /** 配方查看器替身：making 与 atWorkstation 按给定的配方回答。 */
    static final class FakeViewer implements RecipeViewer {
        private final Map<String, List<ShownRecipe>> making = new HashMap<>();
        private final Map<String, List<ShownRecipe>> atWorkstation = new HashMap<>();

        void putMaking(String itemId, List<ShownRecipe> recipes) {
            making.put(itemId, recipes);
        }

        void putAtWorkstation(String itemId, List<ShownRecipe> recipes) {
            atWorkstation.put(itemId, recipes);
        }

        @Override public String name() {
            return "fake";
        }

        @Override public boolean importsOtherViewers() {
            return false;
        }

        @Override public Readiness readiness() {
            return Readiness.yes();
        }

        @Override public List<ShownRecipe> making(String itemId) {
            return making.getOrDefault(itemId, List.of());
        }

        @Override public List<ShownRecipe> using(String itemId) {
            return List.of();
        }

        @Override public List<ShownRecipe> atWorkstation(String itemId) {
            return atWorkstation.getOrDefault(itemId, List.of());
        }
    }

    /** 缺东西去拿的替身：记下请求，动作两刻做完。 */
    static final class FakeNeeds implements ItemNeeds {
        final List<ItemRequest> requests = new ArrayList<>();
        boolean fail = false;

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            requests.add(request);
            if (fail) {
                return new Scripted(1, ActionStatus.failed(
                        Problem.of(Problem.Kind.NOT_FOUND, "没有", null)), () -> { });
            }
            return new Scripted(2, () -> { });
        }
    }

    /** 靠近替身：一步就到。 */
    static final class FakeClose implements BringsPlayerClose {
        int calls;

        @Override public Action toward(ApproachTarget target, Permissions permissions) {
            calls++;
            return new Scripted(1, () -> { });
        }
    }

    /**
     * 交互替身：记下右键了哪格，动作两刻做完；给了 menu 就能点开机器界面看出口（两刻看完，要收就把那样东西全拿走），
     * 给了 ground 就能捡地上的（两刻捡完）。每次点开记下是第几刻、收不收。
     */
    static final class FakeClicks implements MachineInteractions {
        final List<BlockPos> clicked = new ArrayList<>();
        Runnable onDone = () -> { };
        /** 机器界面里的东西：物品 → 件数；为 null 时点不开界面。 */
        Map<String, Integer> menu;
        /** 地上的掉落物，和现场替身的同一份；为 null 时捡不了。 */
        Map<String, Integer> ground;
        LongSupplier now = () -> 0;
        final List<Long> lookTicks = new ArrayList<>();
        final List<Boolean> lookCollects = new ArrayList<>();

        @Override public Action useBlock(BlockPos target, InteractionConfirmation confirmation) {
            clicked.add(target);
            return new Scripted(2, onDone);
        }

        @Override public Optional<OutputLook> lookIntoMenu(BlockPos machine, String itemId, boolean collect,
                Permissions permissions) {
            if (menu == null) return Optional.empty();
            lookTicks.add(now.getAsLong());
            lookCollects.add(collect);
            return Optional.of(new ScriptedLook(menu, itemId, collect));
        }

        @Override public Optional<OutputLook> pickUpFrom(AABB area, String itemId) {
            return ground == null ? Optional.empty() : Optional.of(new ScriptedLook(ground, itemId, true));
        }
    }

    /** 看一眼出口的替身：第二刻读那一份表里有几件，要收就整样拿走。 */
    static final class ScriptedLook implements OutputLook {
        private final Map<String, Integer> where;
        private final String itemId;
        private final boolean collect;
        private int ran;
        private int seen;
        private int taken;

        ScriptedLook(Map<String, Integer> where, String itemId, boolean collect) {
            this.where = where;
            this.itemId = itemId;
            this.collect = collect;
        }

        @Override public ActionStatus tick(TickContext context) {
            if (++ran < 2) return ActionStatus.progressed();
            seen = where.getOrDefault(itemId, 0);
            if (collect) {
                taken = seen;
                where.remove(itemId);
            }
            return ActionStatus.done();
        }

        @Override public int seen() {
            return seen;
        }

        @Override public int taken() {
            return taken;
        }

        @Override public List<String> notes() {
            return List.of();
        }

        @Override public String describe() {
            return "看一眼出口的替身";
        }
    }
}
