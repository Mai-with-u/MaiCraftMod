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
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.containers.KnownContainer;
import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.MenuTakeRound;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.worldmemory.WorldMemory;
import org.maiwithu.maicraft.game.player.PlayerContext;
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
 */
public final class MenuContainerTakes implements ContainerTakes {

    /** 一次开箱取货的总期限（刻）；到点如实收场，不在一只箱子上耗死。 */
    private static final long GIVE_UP_TICKS = 20L * 30;

    private final ReadsItemTags tags;
    private final WorldMemory memory;
    private final Supplier<PlayerContext> contexts;
    /** 打开一只箱子的动作怎么建：按箱子位置与这次任务的许可。 */
    private final BiFunction<BlockPos, Permissions, MenuOpening> openings;
    /** 这只容器是不是在给机器供料（进料箱、补料箱）：是的话每样最多拿走一半，给机器留着。 */
    private final Predicate<BlockPos> feedsMachine;
    /** 机器料箱里每种东西见过最满的时候有几件（按维度、位置、要的东西记）：留一半按它算，不按这一回剩的算。 */
    private final Map<String, Integer> fullestFeeders = new HashMap<>();

    /**
     * @param layouts 认得出哪些界面：原版加上联动模组证明过的（例如模组的箱子）
     */
    public MenuContainerTakes(BringsPlayerClose close, Interactions interactions, ReadsItemTags tags,
            WorldMemory memory, Supplier<PlayerContext> contexts, MenuLayouts layouts) {
        this(tags, memory, contexts, clientOpenings(close, interactions, contexts, layouts));
    }

    /** 打开箱子的动作由调用方给：生产走靠近与右键点开，测试换成替身箱子。 */
    MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings) {
        this(tags, memory, contexts, openings, at -> {
            PlayerContext player = contexts.get();
            return player != null && player.level() != null && ContainerFeeds.feedsAutomation(player.level(), at);
        });
    }

    /** 供料判断也由调用方给：测试换成替身。 */
    MenuContainerTakes(ReadsItemTags tags, WorldMemory memory, Supplier<PlayerContext> contexts,
            BiFunction<BlockPos, Permissions, MenuOpening> openings, Predicate<BlockPos> feedsMachine) {
        this.tags = Objects.requireNonNull(tags, "tags");
        this.memory = Objects.requireNonNull(memory, "memory");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.openings = Objects.requireNonNull(openings, "openings");
        this.feedsMachine = Objects.requireNonNull(feedsMachine, "feedsMachine");
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
        return Optional.of(new TakeAction(container, List.copyOf(requests), permissions));
    }

    /** 开一次箱取几样的动作：点开 → 逐样搬 → 关上，跨刻推进；没能确认的点击经交回接缝原样交回。 */
    private final class TakeAction implements Action, ReportsUnconfirmed {

        private final KnownContainer container;
        /** 这一趟要拿的几样：每样带自己的匹配、缺口与留半限额。 */
        private final List<ContainerWant> wants;
        private final BlockPos at;
        private final MenuOpening opening;
        /** 认领到的界面：读数、点击与关闭都经它。 */
        private OpenedMenu menu;
        /** 界面认下来之后的一轮取货：逐样盘、记账、关箱都走它。 */
        private MenuTakeRound round;
        private int waited;
        /** 机器料箱只剩一半、一样没拿时的说明：关上界面后以它收场；别的情况为 null。 */
        private Problem held;

        TakeAction(KnownContainer container, List<ItemRequest> requests, Permissions permissions) {
            this.container = container;
            this.at = container.cell();
            List<ContainerWant> wants = new ArrayList<>();
            for (ItemRequest request : requests) wants.add(new ContainerWant(request));
            this.wants = List.copyOf(wants);
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
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
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

        // 什么样的槽位算这一样：按物品 ID 与它挂着的标签匹配。
        private boolean matches(SlotSnapshot snapshot, ItemRequest want) {
            if (snapshot.isEmpty()) return false;
            String itemId = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
            return want.wanted().matches(itemId, tags.tagsOf(itemId));
        }

        // 去关之前把这只箱子此刻有什么记进世界记忆：位置带维度，种类按现场的方块读。
        // 界面中途没了时没有读数可记，取东西时按旧记忆找，件数以到场再开为准。
        private void rememberContents(MenuContent.Reading reading) {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            if (level == null) return;
            Map<String, Integer> contents = new LinkedHashMap<>();
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (snapshot.isEmpty()) continue;
                contents.merge(BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString(),
                        snapshot.count(), Integer::sum);
            }
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

        // 收尾：打开动作一并收尾；一轮还没收场的，先撒手（点出去的那几下交回没能确认）再请游戏关上界面。
        @Override
        public void close() {
            opening.close();
            if (round != null) round.close(true);
        }

        @Override
        public List<String> unconfirmedFacts() {
            return round == null ? List.of() : round.unconfirmedFacts();
        }

        @Override
        public String describe() {
            StringBuilder what = new StringBuilder();
            for (ContainerWant want : wants) {
                if (!what.isEmpty()) what.append("、");
                what.append(want.request().wanted().describe());
            }
            // 是机器料箱就说一句留了多少：结果里看得出为什么没拿够。
            for (ContainerWant want : wants) {
                if (!want.limited()) continue;
                what.append("（这是给机器供料的箱子，").append(want.request().wanted().describe())
                        .append(" 最满时 ").append(want.fullest()).append(" 件，至少留 ")
                        .append((want.fullest() + 1) / 2).append(" 件给机器）");
                break;
            }
            return "从" + container.name() + "拿" + what;
        }

        /** 一轮取货的收场：关箱前记箱子内容；关上后手里留着半的缘由还在且一样没拿到，就如实失败。 */
        private final class RoundEndings implements MenuTakeRound.Endings {

            @Override
            public void rememberBeforeClose(Optional<MenuContent.Reading> lastReading) {
                lastReading.ifPresent(MenuContainerTakes.TakeAction.this::rememberContents);
            }

            @Override
            public ActionStatus finishAfterClosed(boolean tookAnything) {
                return finish(tookAnything);
            }

            // 界面中途没了：搬走多少由引擎清点，照常收场。
            @Override
            public ActionStatus finishAfterMenuGone(boolean tookAnything) {
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
                return snapshot -> matches(snapshot, request);
            }

            @Override
            public void firstReading(MenuContent.Reading reading) {
                startTotal = matchedTotal(reading, matcher());
                // 机器的进料箱、补料箱（下面挂着溜槽漏斗、旁边贴着往外抽的漏斗）能翻，但每样最多拿走一半，
                // 给机器留着，不把人家搬空（用户 10-13 定）。出料箱不算，成品照样能拿完。
                if (feedsMachine.test(at)) {
                    limitedFor = fullestFeederCount(request.wanted().describe(), startTotal);
                    allowance = Math.max(0, startTotal - (limitedFor + 1) / 2);
                }
            }

            @Override
            public int stillNeeded(MenuContent.Reading reading) {
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
    }
}
