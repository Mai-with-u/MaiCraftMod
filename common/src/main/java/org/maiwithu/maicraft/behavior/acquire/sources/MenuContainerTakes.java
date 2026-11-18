// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.acquire.engine.CarriedItems;
import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.inventory.containers.RareLootNotice;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.permission.OthersThingsNotice;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.game.world.ContainerFeeds;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.ReportsUnconfirmed;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.StarvingFoodReport;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;

/**
 * 开箱取货的生产实现：走到记得的箱子跟前、点开、认下界面，把这一趟要的几样东西搬进背包
 * （整堆够用整堆拿，尾数按件拿，拿够就停），搬完、拿够或背包放不下了就关上。
 * 一次要几样时只开一次箱：箱里有的那几样一起拿，箱里没有的不碰、也不因此失败。
 *
 * <p>点开、认领界面与等同步走玩家行为层共用的打开容器动作（每次点开都新建读端）；
 * 界面认下来之后的一轮取货走共用的取货一轮，这里只给差异点：机器料箱每样留一半
 * （按见过最满的时候算，不一回回再削半），关箱前把箱里此刻有什么记进世界记忆。
 * 拿走多少以重新清点为准：引擎在动作结束后按身上的数量结算，这里保证每一笔都等确认过、界面关上了。
 * 点出去了却一直没等到结果的那几下（背包塞满点不进、界面中途被关）原样交回接缝，
 * 由引擎转述，不与确认拿到的混在一起。记得的箱子到现场可能已经不在：
 * 点不开、界面一直没同步，都如实失败，引擎换别的路。
 *
 * <p>请求带着绑到这只箱子上的饿急通报（饿急了弄口粮、这只箱子不是角色自己放的）时，只拿通报点名的那一样口粮、
 * 拿到刚好够吃回危险线的件数为止，关箱前把箱里确认少了几件报回去；界面中途没了、说不清少了几件的不报。
 */
public final class MenuContainerTakes implements ContainerBatchTakes {

    /** 一次开箱取货的总期限（刻）；到点如实收场，不在一只箱子上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 30;

    private final ReadsItemTags tags;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;
    /** 打开一只箱子的动作怎么建：按箱子位置与这次任务的许可。 */
    private final BiFunction<BlockPos, Permissions, MenuOpening> openings;
    /** 这只容器是不是在给机器供料（进料箱、补料箱）：是的话每样最多拿走一半，给机器留着。 */
    private final Predicate<BlockPos> feedsMachine;
    /** 动了别人放的容器时发事件告诉宿主；饿急了拿口粮的那一条仍走饿急通报，不重复发。 */
    private final OthersThingsNotice othersNotice;
    /** 打开战利品容器看到稀罕贵重物品时按世界记忆去重发里程碑事件。 */
    private final RareLootNotice rareLootNotice;
    /** 机器料箱里每种东西见过最满的时候有几件（按维度、位置、要的东西记）：留一半按它算，不按这一回剩的算。 */
    private final Map<String, Integer> fullestFeeders = new HashMap<>();

    /**
     * @param layouts 认得出哪些界面：原版加上联动模组证明过的（例如模组的箱子）
     */
    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        this(close, interactions, tags, memory, contexts, layouts, OthersThingsNotice.NONE, RareLootNotice.NONE);
    }

    /** 生产接入动别人东西的通报器：确认从别人放的容器里拿走了东西时合发一条事件。 */
    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts,
            OthersThingsNotice othersNotice) {
        this(close, interactions, tags, memory, contexts, layouts, othersNotice, RareLootNotice.NONE);
    }

    /** 生产同时接入动别人东西与稀罕战利品两个通报器。 */
    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts,
            OthersThingsNotice othersNotice, RareLootNotice rareLootNotice) {
        this(tags, memory, contexts, clientOpenings(close, interactions, contexts, layouts), at -> {
            PlayerContext player = contexts.get();
            return player != null && player.level() != null && ContainerFeeds.feedsAutomation(player.level(), at);
        }, othersNotice, rareLootNotice);
    }

    /** 打开箱子的动作由调用方给：生产走靠近与右键点开，替身接入（离线测试）换自己的打开动作。 */
    public MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings) {
        this(tags, memory, contexts, openings, at -> {
            PlayerContext player = contexts.get();
            return player != null && player.level() != null && ContainerFeeds.feedsAutomation(player.level(), at);
        }, OthersThingsNotice.NONE, RareLootNotice.NONE);
    }

    /** 供料判断也由调用方给：替身接入时换成替身。 */
    public MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings, Predicate<BlockPos> feedsMachine) {
        this(tags, memory, contexts, openings, feedsMachine, OthersThingsNotice.NONE, RareLootNotice.NONE);
    }

    /** 全参入口：替掉打开动作、供料判断与通报器的调用方（含离线测试）都用它。 */
    public MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings, Predicate<BlockPos> feedsMachine,
            OthersThingsNotice othersNotice) {
        this(tags, memory, contexts, openings, feedsMachine, othersNotice, RareLootNotice.NONE);
    }

    public MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings, Predicate<BlockPos> feedsMachine,
            OthersThingsNotice othersNotice, RareLootNotice rareLootNotice) {
        this.tags = Objects.requireNonNull(tags, "tags");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.openings = Objects.requireNonNull(openings, "openings");
        this.feedsMachine = Objects.requireNonNull(feedsMachine, "feedsMachine");
        this.othersNotice = othersNotice == null ? OthersThingsNotice.NONE : othersNotice;
        this.rareLootNotice = rareLootNotice == null ? RareLootNotice.NONE : rareLootNotice;
    }

    // 生产的打开方式：走到够得着的地方、右键点开、认领并等同步；走过去能动多少地形按这次任务的许可来。
    private static BiFunction<BlockPos, Permissions, MenuOpening> clientOpenings(BringsPlayerClose close,
            Interactions interactions, Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(interactions, "interactions");
        Objects.requireNonNull(layouts, "layouts");
        return (at, permissions) -> new ClientMenuOpening(at, permissions, layouts, close, interactions, contexts);
    }

    @Override
    public Optional<Action> take(KnownContainer container, List<ItemRequest> requests, Permissions permissions) {
        if (requests.isEmpty()) return Optional.empty();
        return Optional.of(new TakeAction(container, requests, permissions));
    }

    @Override
    public Action take(KnownContainer container, ContainerTakeOrder order, Permissions permissions) {
        return new TakeAction(container, order, permissions);
    }

    // 一格里放的东西的物品 ID；空格为空串。
    private static String itemIdOf(SlotSnapshot snapshot) {
        return snapshot.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
    }

    /** 开一次箱取几样的动作：点开 → 逐样搬 → 关上，跨刻推进；没能确认的点击经交回接缝原样交回。 */
    private final class TakeAction implements Action, ReportsUnconfirmed, ReportsTakeSettlement {

        private final KnownContainer container;
        /** 这一趟要拿的几样：引擎路径按要货单逐条，整批搬按取货单；每样带自己的匹配与缺口。 */
        private final List<MenuTakeRound.Want> wants;
        private final BlockPos at;
        private final MenuOpening opening;
        /** 按取货单整批搬时是那张单子（总件数线在这里）；引擎按要货单搬时为 null。 */
        private final ContainerTakeOrder order;
        /** 描述里"拿什么"的那段：整批搬说"整箱"，引擎按要货单逐样列。 */
        private final String wantedText;
        /** 按取货单整批搬时收场要算结算账。 */
        private final boolean batch;
        /** 认领到的界面：读数、点击与关闭都经它。 */
        private OpenedMenu menu;
        /** 界面认下来之后的一轮取货：逐样盘、记账、关箱都走它。 */
        private MenuTakeRound round;
        private int waited;
        /** 机器料箱只剩一半、一样没拿时的说明：关上界面后以它收场；别的情况为 null。 */
        private Problem held;
        /** 点不开容器时的原因：打开动作失败时记下；点开过或还没试完时为 null。 */
        private Problem openFailure;
        /** 收场结算：实际搬进了什么、箱里本来有没有要的、还剩没搬完的没有；没算出来前按没有算。 */
        private TakeSettlement settlement = TakeSettlement.none();
        /** 开箱第一份读数里匹配本次请求的各物品件数与显示名、角色背包起点，以及最新确认过的容器读数：
         * 收尾时据此合算一次开箱拿走了什么。 */
        private Map<String, Integer> initialMatchedCounts;
        private final Map<String, String> matchedNames = new HashMap<>();
        private Map<String, Integer> initialPlayerCounts;
        /** 开箱那一刻背包主格的各物品件数（按物品 ID）：界面半途没了时按它的差额兜底结算。 */
        private Map<String, Integer> backpackAtOpen;
        private int totalMatchedAtOpen;
        private List<RareLootNotice.SeenPrecious> initialPrecious;
        private Set<String> previouslySeenInContainer;
        private MenuContent.Reading latestReading;
        private boolean othersReported;
        private boolean rareLootReported;

        /** 按要货单取货：引擎挑好的路，逐条要货单盘缺口。 */
        TakeAction(KnownContainer container, List<ItemRequest> requests, Permissions permissions) {
            this.container = container;
            this.at = container.cell();
            List<MenuTakeRound.Want> built = new ArrayList<>();
            StringBuilder wanted = new StringBuilder();
            for (ItemRequest request : requests) {
                if (!wanted.isEmpty()) wanted.append("、");
                wanted.append(request.wanted().describe());
                built.add(new ContainerWant(request));
            }
            this.wants = List.copyOf(built);
            this.batch = false;
            this.wantedText = wanted.toString();
            this.order = null;
            // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
            this.opening = openings.apply(at, permissions);
        }

        /** 按取货单整批搬：整箱或点名的几样合用一条总件数线，收场算结算账。 */
        TakeAction(KnownContainer container, ContainerTakeOrder order, Permissions permissions) {
            this.container = container;
            this.at = container.cell();
            List<MenuTakeRound.Want> built = new ArrayList<>();
            if (order.items().isEmpty()) {
                built.add(new BatchWant(null));
            } else {
                for (WantedItem wanted : order.items()) built.add(new BatchWant(wanted));
            }
            this.wants = List.copyOf(built);
            this.batch = true;
            this.wantedText = order.describe();
            this.order = order;
            // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
            this.opening = openings.apply(at, permissions);
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            // 总期限从点开箱子算起：走过去的那段路由走到自己管（有没有进展、走不走得到），
            // 连走路一起算会让六十格外的箱子还没走到就"开箱取货超时"。
            if (menu != null && ++waited > GIVE_UP_TICKS) {
                // 总期限到了还在一笔上：点出去没等到结果的那几下照实交回，再以卡住收场。
                round.abandonAndNote("总期限到了");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开箱取货超时：" + container.name(), null));
            }
            if (menu == null) return open(tick);
            return round.tick(tick, menu.reading());
        }

        // 点开：走不到、点不开、界面认不出或一直没同步，都把打开动作的原因原样交回，引擎换路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (status instanceof ActionStatus.Failed failed) {
                openFailure = failed.problem();
            }
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            snapshotBackpackAtOpen();
            round = new MenuTakeRound(menu, List.copyOf(wants), new RoundEndings());
            return ActionStatus.progressed();
        }

        // 机器料箱里这种东西见过最满的时候有几件。一次没拿够、引擎回头再开，留一半还按那时候算，
        // 不一回回按剩下的再削一半（实机 10-13：273 个大齿轮要 150，先拿 136 关上，还缺 14 再开，
        // 按剩的 137 又算一半，最后只剩 123）。料箱被补满了，最满的数跟着涨。
        private int fullestFeederCount(String describe, int total) {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            String key = (level == null ? "" : level.dimension().location().toString()) + "@" + at.asLong()
                    + "#" + describe;
            return fullestFeeders.merge(key, total, Math::max);
        }

        // 容器那一侧装着匹配得上的东西的总件数；界面内容按物品标签对上请求。
        private int matchedTotal(MenuContent.Reading reading, Predicate<SlotSnapshot> matched) {
            int total = 0;
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (!snapshot.isEmpty() && matched.test(snapshot)) total += snapshot.count();
            }
            return total;
        }

        // 什么样的槽位算这一样：按物品 ID 与它挂着的标签匹配；点名了药水种类的按这一格写着的种类挑，不拿旁边的水瓶。
        private boolean matches(SlotSnapshot snapshot, ItemRequest want) {
            if (snapshot.isEmpty()) return false;
            return want.wanted().matches(snapshot.key(), tags.tagsOf(itemIdOf(snapshot)));
        }

        // 去关之前把这只箱子此刻有什么记进世界记忆：位置带维度，种类按现场的方块读。
        // 界面中途没了时没有读数可记，取东西时按旧记忆找，件数以到场再开为准。
        private void rememberContents(MenuContent.Reading reading) {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            if (level == null) return;
            // 药水连种类一起记：下回点名要抗火药水时认得出是这只箱子。
            Map<String, Integer> contents = reading.containerContents();
            WorldPosition position = new WorldPosition(at.getX(), at.getY(), at.getZ(),
                    level.dimension().location().toString());
            String blockType = BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
            memory.rememberContainerOpened(position, blockType, List.copyOf(contents.keySet()), Instant.now());
        }

        // 光标上拿着东西的那几下停不得：半途撒手会把拆出来的尾数交给关界面的流程，先等它放进背包。
        // 界面还没点开时（round 只在界面认领后才建）没有在点的笔，照常报"在做"；
        // 这时被打断由打开动作自己收手，回来引擎按身上的清点再决定。
        // 控制循环每刻都会来问这一句，不能在这里抛：一抛整刻的推进被跳过，动作到不了终态，临时任务永远占着身体。
        @Override
        public Interruptibility interruptibility() {
            return round != null && round.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        // 被生存需求打断：正在走、正在点的先停住；界面开着就请游戏关上，回来时引擎按身上的清点再决定。
        @Override
        public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；一轮还没收场的，先撒手（点出去的那几下交回没能确认）、结算已确认拿走的部分，再请游戏关上界面。
        @Override
        public void close() {
            opening.close();
            if (round != null) {
                round.close(true);
                if (!batch) reportRareLoot();
                reportOthersTook();
            }
        }

        @Override
        public List<String> unconfirmedFacts() {
            return round == null ? List.of() : round.unconfirmedFacts();
        }

        @Override
        public TakeSettlement settlement() {
            return settlement;
        }

        @Override
        public Optional<Problem> openFailure() {
            return Optional.ofNullable(openFailure);
        }

        @Override
        public String describe() {
            StringBuilder what = new StringBuilder(wantedText);
            // 是机器料箱就说一句留了多少：结果里看得出为什么没拿够。
            for (MenuTakeRound.Want want : wants) {
                if (!(want instanceof ContainerWant limited) || !limited.limited()) continue;
                what.append("（这是给机器供料的箱子，").append(limited.request().wanted().describe())
                        .append(" 最满时 ").append(limited.fullest()).append(" 件，至少留 ")
                        .append((limited.fullest() + 1) / 2).append(" 件给机器）");
                break;
            }
            return "从" + container.name() + "拿" + what;
        }

        /** 一轮取货的收场：关箱前记箱子内容；按要货单搬的关上后手里留着半的缘由还在且一样没拿到就如实失败，整批搬的算结算账。 */
        private final class RoundEndings implements MenuTakeRound.Endings {

            @Override
            public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                lastReading.ifPresent(reading -> latestReading = reading);
                // 先在把这次开箱内容写进世界记忆前通报新看到的稀罕战利品，按上回记忆去重。
                if (!batch) reportRareLoot();
                lastReading.ifPresent(MenuContainerTakes.TakeAction.this::rememberContents);
                // 从不是自己放的箱子按饿急通报拿的：关箱前按箱里确认少了几件报回去，由发起的一方发事件告诉宿主。
                if (!batch) {
                    lastReading.ifPresent(reading -> wants.forEach(want -> {
                        if (want instanceof ContainerWant reported) reported.reportTaken(reading);
                    }));
                }
                // 普通取货动了别人放的容器时，一次开箱合发一条 others_things_touched；饿急通报走原有事件不重发。
                reportOthersTook();
                // 整批搬的关箱前按收场读数算结算账，调用方从结算接口读。
                if (batch) lastReading.ifPresent(MenuContainerTakes.TakeAction.this::settle);
            }

            @Override
            public ActionStatus finishAfterClosed(boolean tookAnything) {
                return batch ? ActionStatus.done() : finish(tookAnything);
            }

            // 界面中途没了：引擎搬的搬走多少由引擎清点；整批搬的按背包差额兜底结算，照常收场。
            @Override
            public ActionStatus finishAfterMenuGone(boolean tookAnything) {
                if (batch) settleFromBackpackFallback();
                return ActionStatus.done();
            }

            private ActionStatus finish(boolean tookAnything) {
                return held != null && !tookAnything ? ActionStatus.failed(held) : ActionStatus.done();
            }
        }

        /** 要货单里的一样：怎么认、这一刻还缺几件；机器料箱的留半限额与"不再拿"的说明也长在这里。 */
        private final class ContainerWant implements MenuTakeRound.Want {

            private final ItemRequest request;
            /** 刚打开时容器那一侧装着几件：拿走多少按它与当刻的差算；第一份读数前为 -1。 */
            private int startTotal = -1;
            /** 这一趟最多拿几件：给机器供料的箱子每样留一半，别的不限。 */
            private int allowance = Integer.MAX_VALUE;
            /** 是机器料箱时，它见过最满的时候有几件（留一半按它算）；不是为 -1。 */
            private int limitedFor = -1;
            /** 按饿急通报从这只箱子拿的那一样（物品 ID）与它显示的名字；没绑通报、或箱里没有通报认的为 null。 */
            private String othersItem;
            private String othersItemName;

            ContainerWant(ItemRequest request) {
                this.request = request;
            }

            ItemRequest request() {
                return request;
            }

            boolean limited() {
                return limitedFor >= 0;
            }

            int fullest() {
                return limitedFor;
            }

            @Override
            public String label() {
                return "从" + container.name() + "拿" + request.wanted().describe();
            }

            @Override
            public Predicate<SlotSnapshot> matcher() {
                // 通报只认挑中的那一样：一种口粮一件补多少不一样，刚好够的件数按它算。
                return snapshot -> matches(snapshot, request)
                        && (othersItem == null || othersItem.equals(itemIdOf(snapshot)));
            }

            @Override
            public void firstReading(MenuContent.Reading reading) {
                recordInitialMatched(reading);
                latestReading = reading;
                StarvingFoodReport report = request.foodReport();
                // 绑了饿急通报的箱子（不是自己放的）：先挑箱里第一样通报认的东西，只拿它；一样认的都没有就一件不拿。
                if (report.bound()) pickReportedItem(reading, report);
                startTotal = matchedTotal(reading, matcher());
                // 机器的进料箱、补料箱（下面挂着溜槽漏斗、旁边贴着往外抽的漏斗）能翻，但每样最多拿走一半，
                // 给机器留着，不把人家搬空（用户 10-13 定）。出料箱不算，成品照样能拿完。
                if (feedsMachine.test(at)) {
                    limitedFor = fullestFeederCount(request.wanted().describe(), startTotal);
                    allowance = Math.max(0, startTotal - (limitedFor + 1) / 2);
                }
                if (report.bound()) allowance = Math.min(allowance, othersItem == null ? 0 : report.atMost(othersItem));
            }

            // 按容器格的先后挑第一样通报认的（能拿的件数大于零）；挑中了记下它和它显示的名字。
            private void pickReportedItem(MenuContent.Reading reading, StarvingFoodReport report) {
                for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                    if (!matches(snapshot, request) || report.atMost(itemIdOf(snapshot)) <= 0) continue;
                    othersItem = itemIdOf(snapshot);
                    othersItemName = snapshot.stack().getHoverName().getString();
                    return;
                }
            }

            // 关箱前：从绑定的箱子拿了几件按箱里确认少了的算，一件都没少不报。
            void reportTaken(MenuContent.Reading reading) {
                if (othersItem == null || startTotal < 0) return;
                int taken = startTotal - matchedTotal(reading, matcher());
                if (taken > 0) {
                    request.foodReport().taken(List.of(new StarvingFoodReport.Taken(othersItem, othersItemName, taken)));
                }
            }

            @Override
            public int stillNeeded(MenuContent.Reading reading) {
                latestReading = reading;
                return Math.min(request.count(), allowance) - (startTotal - matchedTotal(reading, matcher()));
            }

            // 机器料箱已经只剩一半了：一件不拿，说清为什么，引擎好换别处。只说第一样，同样的缘由不重三遍。
            @Override
            public void gaveUp() {
                if (held != null || allowance != 0 || startTotal <= 0) return;
                held = Problem.of(Problem.Kind.NOT_FOUND, container.name() + " 是给机器供料的箱子，"
                        + request.wanted().describe() + " 最满时 " + limitedFor + " 件，至少留 "
                        + (limitedFor + 1) / 2 + " 件给机器，现在只剩 " + startTotal + " 件，不再拿");
            }

            // 对不上账的一笔之后按现场重新盘：这一样还缺几件按新的读数算，下一笔照常出。
            @Override
            public boolean settleAfterDiverge() {
                return false;
            }
        }

        // 第一份读数来时记下容器里匹配本次请求的各物品初始总数、角色背包起点、稀罕物品与上回记忆，
        // 后面按关箱读数的差核实拿走了几件、箱里还剩没搬完的没有。
        private void recordInitialMatched(MenuContent.Reading reading) {
            if (initialMatchedCounts != null) return;
            initialMatchedCounts = new LinkedHashMap<>();
            initialPlayerCounts = new HashMap<>();
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (snapshot.isEmpty() || !matchesAnyWant(snapshot)) continue;
                String itemId = itemIdOf(snapshot);
                initialMatchedCounts.merge(itemId, snapshot.count(), Integer::sum);
                matchedNames.putIfAbsent(itemId, snapshot.stack().getHoverName().getString());
                initialPlayerCounts.put(itemId, countInSnapshots(reading.playerSnapshots(), itemId));
            }
            totalMatchedAtOpen = initialMatchedCounts.values().stream().mapToInt(Integer::intValue).sum();
            previouslySeenInContainer = rareLootNotice.snapshotPreviouslySeen(currentPosition());
            initialPrecious = RareLootNotice.collectInitial(reading.containerSnapshots());
        }

        // 开箱那一刻记下背包主格的各物品件数：界面半途没了时按它的差额兜底结算；这趟搬货只会进背包主格。
        private void snapshotBackpackAtOpen() {
            PlayerContext current = contexts.get();
            if (current == null) return;
            backpackAtOpen = plainCounts(CarriedItems.byItem(current.backpack(), null));
        }

        // 关箱前按收场读数结算：这一趟实际搬进背包什么各几件（按角色背包那侧的差算），箱里还剩没搬完的没有。
        private void settle(MenuContent.Reading reading) {
            Map<String, Integer> taken = new LinkedHashMap<>();
            for (String itemId : initialMatchedCounts.keySet()) {
                int gained = countInSnapshots(reading.playerSnapshots(), itemId)
                        - initialPlayerCounts.getOrDefault(itemId, 0);
                if (gained > 0) taken.put(itemId, gained);
            }
            settlement = new TakeSettlement(taken, totalMatchedAtOpen > 0, matchedInContainer(reading) > 0);
        }

        // 界面半途没了拿不到关箱读数：按开箱前后背包主格的差额兜底结算，
        // 剩没剩按搬到的总件数与开箱时的总数比（搬到的少就是还有剩的）。
        private void settleFromBackpackFallback() {
            Map<String, Integer> taken = new LinkedHashMap<>();
            int totalTaken = 0;
            if (backpackAtOpen != null) {
                PlayerContext current = contexts.get();
                Map<String, Integer> now = plainCounts(CarriedItems.byItem(current.backpack(), null));
                for (String itemId : initialMatchedCounts.keySet()) {
                    int gained = now.getOrDefault(itemId, 0) - backpackAtOpen.getOrDefault(itemId, 0);
                    if (gained > 0) {
                        taken.put(itemId, gained);
                        totalTaken += gained;
                    }
                }
            }
            settlement = new TakeSettlement(taken, totalMatchedAtOpen > 0, totalTaken < totalMatchedAtOpen);
        }

        // 容器那一侧装着匹配过的那些物品的总件数（按物品 ID 认，药水合并算一种）；关箱时还剩没有按它判断。
        private int matchedInContainer(MenuContent.Reading reading) {
            int sum = 0;
            for (String itemId : initialMatchedCounts.keySet()) {
                sum += countInSnapshots(reading.containerSnapshots(), itemId);
            }
            return sum;
        }

        private static int countInSnapshots(List<SlotSnapshot> snapshots, String itemId) {
            int sum = 0;
            for (SlotSnapshot snapshot : snapshots) {
                if (!snapshot.isEmpty() && itemIdOf(snapshot).equals(itemId)) {
                    sum += snapshot.count();
                }
            }
            return sum;
        }

        // 清点按键是带种类的写法，结算的账按物品 ID 记：药水几种合并在一个物品名下，与匹配过的键一致。
        private static Map<String, Integer> plainCounts(Map<String, Integer> writtenCounts) {
            Map<String, Integer> plain = new HashMap<>();
            writtenCounts.forEach((written, count) -> plain.merge(StackKey.parse(written).itemId(), count, Integer::sum));
            return plain;
        }

        /** 取货单里的一样：整箱搬时只有"箱里的东西"这一样，点名的每样一条；几样合用取货单的总件数线。 */
        private final class BatchWant implements MenuTakeRound.Want {

            /** 点名的这样；整箱搬时为 null，所有非空格都算。 */
            private final WantedItem wanted;

            BatchWant(WantedItem wanted) {
                this.wanted = wanted;
            }

            @Override
            public String label() {
                return "从" + container.name() + "拿" + (wanted == null ? "整箱" : wanted.describe());
            }

            @Override
            public Predicate<SlotSnapshot> matcher() {
                return snapshot -> !snapshot.isEmpty()
                        && (wanted == null || wanted.matches(snapshot.key(), tags.tagsOf(itemIdOf(snapshot))));
            }

            @Override
            public void firstReading(MenuContent.Reading reading) {
                recordInitialMatched(reading);
                latestReading = reading;
            }

            // 这一刻还差几件：箱里现存的与总件数线的余量取小——几样合起来拿够 maxCount 就停，
            // 不大于零（箱里这样没了或线上余量用完）就放下这一样。
            @Override
            public int stillNeeded(MenuContent.Reading reading) {
                latestReading = reading;
                int budgetLeft = order.maxCount() <= 0 ? Integer.MAX_VALUE
                        : Math.max(0, order.maxCount() - (totalMatchedAtOpen - matchedInContainer(reading)));
                return Math.min(matchedTotal(reading, matcher()), budgetLeft);
            }

            @Override
            public boolean settleAfterDiverge() {
                return false;
            }
        }

        private void reportRareLoot() {
            if (rareLootReported || initialPrecious == null || initialPrecious.isEmpty() || latestReading == null) {
                return;
            }
            rareLootReported = true;
            rareLootNotice.report(currentPosition(), currentBlockType(), previouslySeenInContainer,
                    RareLootNotice.settleTaken(initialPrecious, latestReading.containerSnapshots()));
        }

        private WorldPosition currentPosition() {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            return level == null ? container.at()
                    : new WorldPosition(at.getX(), at.getY(), at.getZ(), level.dimension().location().toString());
        }

        private String currentBlockType() {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            return level == null ? container.blockTypeId()
                    : BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
        }

        private boolean matchesAnyWant(SlotSnapshot snapshot) {
            for (MenuTakeRound.Want want : wants) {
                if (want.matcher().test(snapshot)) return true;
            }
            return false;
        }

        // 一次开箱只报一条：核算出容器侧确认少掉的各物品件数，交给通报器对别人放的容器发事件；饿急通报走原有事件不重发。
        private void reportOthersTook() {
            if (othersReported || initialMatchedCounts == null || latestReading == null) return;
            othersReported = true;
            for (MenuTakeRound.Want want : wants) {
                if (want instanceof ContainerWant reported && reported.request().foodReport().bound()) return;
            }
            Map<String, Integer> remaining = new HashMap<>();
            for (SlotSnapshot snapshot : latestReading.containerSnapshots()) {
                if (snapshot.isEmpty()) continue;
                remaining.merge(itemIdOf(snapshot), snapshot.count(), Integer::sum);
            }
            List<OthersThingsNotice.MovedItem> taken = new ArrayList<>();
            initialMatchedCounts.forEach((itemId, before) -> {
                int delta = before - remaining.getOrDefault(itemId, 0);
                if (delta > 0) {
                    taken.add(new OthersThingsNotice.MovedItem(itemId, matchedNames.get(itemId), delta));
                }
            });
            if (taken.isEmpty()) return;
            othersNotice.containerTook(currentPosition(), currentBlockType(), taken);
        }
    }
}
