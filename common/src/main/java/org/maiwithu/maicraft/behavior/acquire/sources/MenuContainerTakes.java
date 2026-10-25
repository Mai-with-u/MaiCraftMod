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
import org.maiwithu.maicraft.behavior.menu.ContainerTakePlan;
import org.maiwithu.maicraft.behavior.menu.MenuClick;
import org.maiwithu.maicraft.behavior.menu.MenuContent;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.menu.MenuOpening;
import org.maiwithu.maicraft.behavior.menu.OpenedMenu;
import org.maiwithu.maicraft.behavior.menu.SlotSnapshot;
import org.maiwithu.maicraft.behavior.menu.TransferRun;
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
 * <p>点开、认领界面与等同步走玩家行为层共用的打开容器动作（每次点开都新建读端）。
 * 每一笔拿几件、点哪几下由取货计划给出，逐刻点、等结清、出结论交给搬运执行；
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
    public Optional<Action> take(KnownContainer container, List<ItemRequest> wants, Permissions permissions) {
        if (wants.isEmpty()) return Optional.empty();
        return Optional.of(new TakeAction(container, List.copyOf(wants), permissions));
    }

    /** 开一次箱取几样的动作：点开 → 逐样搬 → 关上，跨刻推进；没能确认的点击经交回接缝原样交回。 */
    private final class TakeAction implements Action, ReportsUnconfirmed {

        private final KnownContainer container;
        /** 这一趟要拿的几样；每样写着这一刻还缺几件。 */
        private final List<ItemRequest> wants;
        private final BlockPos at;
        private final MenuOpening opening;
        private Stage stage = Stage.OPEN;
        private OpenedMenu menu;
        private Action closing;
        private int waited;
        /** 刚打开时容器那一侧每样装着几件：拿走多少按它与当刻的差算。 */
        private final int[] startTotals;
        /** 每样最多拿几件：给机器供料的箱子每样留一半，别的不限。 */
        private final int[] allowances;
        /** 是机器料箱时，它见过最满的时候每样有几件（留一半按它算）；不是为 -1。 */
        private final int[] limitedFor;
        /** 这一趟已经取不动的那几样：箱里没有、背包这一刻放不下、或点了没反应。 */
        private final boolean[] settled;
        /** 机器料箱只剩一半、一样没拿时的说明：关上界面后以它收场；别的情况为 null。 */
        private Problem held;
        /** 这一趟是不是真的拿到了东西：拿到过就不拿"给机器留一半"当整场失败。 */
        private boolean tookAnything;
        /** 正在点的那样在清单里的第几项；没有在点的为 -1：交回"没能确认"时说得清是哪样。 */
        private int wantIndex = -1;
        /** 正在点的那一笔按计划要搬几件：交回"没能确认"时说得清是多大的笔。 */
        private int moveAmount;
        /** 手上的一笔交给它逐刻点、等结清；界面点开后才有。 */
        private TransferRun run;
        /** 点出去了却没能确认结果的交互，一句一笔，经交回接缝交给引擎。 */
        private final List<String> unconfirmed = new ArrayList<>();
        /** 第一份读数到了没有：到了才按它定每样的起点。 */
        private boolean opened;

        TakeAction(KnownContainer container, List<ItemRequest> wants, Permissions permissions) {
            this.container = container;
            this.wants = wants;
            this.at = container.cell();
            this.startTotals = new int[wants.size()];
            this.allowances = new int[wants.size()];
            this.limitedFor = new int[wants.size()];
            this.settled = new boolean[wants.size()];
            for (int i = 0; i < wants.size(); i++) {
                startTotals[i] = -1;
                allowances[i] = Integer.MAX_VALUE;
                limitedFor[i] = -1;
            }
            // 走过去能动多少地形按这次任务的许可来，不另开一套默认档。
            this.opening = openings.apply(at, permissions);
        }

        @Override
        public ActionStatus tick(TickContext tick) {
            // 总期限从点开箱子算起：走过去的那段路由走到自己管（有没有进展、走不走得到），
            // 原先连走路一起算 30 秒，六十格外的箱子还没走到就"开箱取货超时"。
            if (stage != Stage.OPEN && ++waited > GIVE_UP_TICKS) {
                // 总期限到了还在一笔上：点出去没等到结果的那几下照实交回，再以卡住收场。
                noteAbandonedMove("总期限到了");
                return ActionStatus.failed(Problem.of(Problem.Kind.STUCK, "开箱取货超时：" + container.name(), null));
            }
            return switch (stage) {
                case OPEN -> open(tick);
                case TRANSFER -> transfer();
                case CLOSE -> closeMenu(tick);
                case DONE -> held != null && !tookAnything ? ActionStatus.failed(held) : ActionStatus.done();
            };
        }

        // 点开：走不到、点不开、界面认不出或一直没同步，都把打开动作的原因原样交回，引擎换路。
        private ActionStatus open(TickContext tick) {
            ActionStatus status = opening.tick(tick);
            if (!(status instanceof ActionStatus.Done)) return status;
            menu = opening.opened().orElseThrow();
            run = new TransferRun(menu);
            stage = Stage.TRANSFER;
            return ActionStatus.progressed();
        }

        // 按量搬：手上的笔交给搬运执行逐刻点、等结清、出结论；空手时盘下一笔。
        private ActionStatus transfer() {
            if (!run.idle()) return run.tickAction(this::onSettled);
            Optional<MenuContent.Reading> reading = menu.reading();
            if (reading.isEmpty()) {
                // 界面被合上了（打断或服务端关的）：拿走多少由引擎清点，这里照常收场。
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            MenuContent.Reading open = reading.get();
            if (!opened) {
                opened = true;
                // 机器的进料箱、补料箱（下面挂着溜槽漏斗、旁边贴着往外抽的漏斗）能翻，但每样最多拿走一半，
                // 给机器留着，不把人家搬空（用户 10-13 定）。出料箱不算，成品照样能拿完。
                boolean feeder = feedsMachine.test(at);
                for (int i = 0; i < wants.size(); i++) {
                    startTotals[i] = matchedTotal(open, i);
                    if (feeder) allowances[i] = feederAllowance(i, startTotals[i]);
                }
            }
            if (planNext(open)) return run.tickAction(this::onSettled);
            return toClose(open);
        }

        // 一笔出了结论：结清就接着盘下一笔；点出去了身上一直没多（多半背包放不下）记下没能确认，
        // 这一样这一趟先放下；对不上或光标没放下来的，这一笔搬走多少说不清，下一笔按现场重新盘。
        private ActionStatus onSettled(TransferRun.MoveResult result) {
            if (result instanceof TransferRun.MoveResult.Confirmed(int amount)) {
                tookAnything = true;
            } else if (result instanceof TransferRun.MoveResult.NotMoved) {
                unconfirmed.add(wantedName(wantIndex) + "：搬 " + moveAmount
                        + " 件的那几下都点出去了，身上一直没多，没能确认结果");
                // 这一样这一趟先放下，别的样还可以接着试；都取不动就关。
                settled[wantIndex] = true;
            } else if (result instanceof TransferRun.MoveResult.Diverged) {
                tookAnything = true;
                unconfirmed.add(wantedName(wantIndex) + "：这一笔点完对不上账，搬走多少没能确认");
            } else if (result instanceof TransferRun.MoveResult.MenuGone(int submitted)) {
                if (submitted > 0) {
                    unconfirmed.add(wantedName(wantIndex) + "：搬 " + moveAmount + " 件的这笔点了 " + submitted
                            + " 下，界面关上时没能等到结算结果");
                }
                stage = Stage.DONE;
                return ActionStatus.done();
            }
            wantIndex = -1;
            return ActionStatus.progressed();
        }

        // 盘下一笔：按清单顺序找第一样还缺、且这一刻取得到的；取得到就提交给搬运执行，
        // 动手前的证据由它在第一下点出去之前冻结。
        // 取不动（箱里没有、背包这一刻放不下）就这一趟先放下它，试试别的；几样都取不动时关界面。
        private boolean planNext(MenuContent.Reading open) {
            for (int i = 0; i < wants.size(); i++) {
                int index = i;
                if (settled[index]) continue;
                int now = matchedTotal(open, index);
                int remaining = Math.min(wants.get(index).count(), allowances[index])
                        - (startTotals[index] - now);
                if (remaining <= 0) {
                    settled[index] = true;
                    if (allowances[index] == 0 && startTotals[index] > 0) noteFeederKept(index);
                    continue;
                }
                Optional<ContainerTakePlan.Move> next = ContainerTakePlan.next(open,
                        snapshot -> matches(snapshot, index), remaining);
                if (next.isEmpty()) {
                    // 背包这一刻放不下这样（没有空格，同种的堆也满了），或箱里已经没有了：
                    // 这一趟不再点它，别的样还可以接着拿。
                    settled[index] = true;
                    if (allowances[index] == 0 && startTotals[index] > 0) noteFeederKept(index);
                    continue;
                }
                wantIndex = index;
                moveAmount = next.get().amount();
                run.begin(new TransferRun.Move(next.get().clicks(),
                        new TransferRun.Check.IntoBackpack(snapshot -> matches(snapshot, index), moveAmount),
                        wantedName(index)));
                return true;
            }
            return false;
        }

        // 机器料箱已经只剩一半了：一件不拿，说清为什么，引擎好换别处。
        private void noteFeederKept(int index) {
            if (held != null) return;
            held = Problem.of(Problem.Kind.NOT_FOUND, container.name() + " 是给机器供料的箱子，"
                    + wants.get(index).wanted().describe() + " 最满时 " + limitedFor[index] + " 件，至少留 "
                    + (limitedFor[index] + 1) / 2 + " 件给机器，现在只剩 " + startTotals[index] + " 件，不再拿");
        }

        // 中途收手（总期限到、被收尾）：手上还有一笔的，点出去没等到结果的那几下原样交回；
        // 一下都没发出去的不算，那只是没点成。
        private void noteAbandonedMove(String circumstance) {
            if (run.idle()) return;
            int submitted = run.abandon();
            if (submitted > 0) {
                unconfirmed.add(wantedName(wantIndex) + "：搬 " + moveAmount + " 件的这笔点了 " + submitted + " 下，"
                        + circumstance + "没能等到结算结果");
            }
            wantIndex = -1;
        }

        // 去关之前把这只箱子此刻有什么记进世界记忆：位置带维度，种类按现场的方块读。
        private ActionStatus toClose(MenuContent.Reading reading) {
            rememberContents(reading);
            stage = Stage.CLOSE;
            return ActionStatus.progressed();
        }

        // 关界面：打开者负责关闭；关不上不冒充没开过，照常收场让引擎清点。
        private ActionStatus closeMenu(TickContext tick) {
            if (closing == null) closing = menu.closing();
            ActionStatus status = closing.tick(tick);
            if (status instanceof ActionStatus.Running) return status;
            stage = Stage.DONE;
            return held != null && !tookAnything ? ActionStatus.failed(held) : ActionStatus.done();
        }

        // 机器料箱这一回一样最多拿几件：按见过最满的时候留一半。一次没拿够、引擎回头再开，还按那时候的一半留，
        // 不一回回按剩下的再削一半（实机 10-13：273 个大齿轮要 150，先拿 136 关上，还缺 14 再开，
        // 按剩的 137 又算一半，最后只剩 123）。料箱被补满了，最满的数跟着涨。
        private int feederAllowance(int index, int total) {
            PlayerContext current = contexts.get();
            ClientLevel level = current == null ? null : current.level();
            String key = (level == null ? "" : level.dimension().location().toString()) + "@" + at.asLong()
                    + "#" + wants.get(index).wanted().describe();
            int fullest = fullestFeeders.merge(key, total, Math::max);
            limitedFor[index] = fullest;
            return Math.max(0, total - (fullest + 1) / 2);
        }

        // 容器那一侧装着某一样想要的东西的总件数；界面内容按物品标签对上请求。
        private int matchedTotal(MenuContent.Reading reading, int index) {
            int total = 0;
            for (SlotSnapshot snapshot : reading.containerSnapshots()) {
                if (matches(snapshot, index)) total += snapshot.count();
            }
            return total;
        }

        private boolean matches(SlotSnapshot snapshot, int index) {
            if (snapshot.isEmpty()) return false;
            String itemId = BuiltInRegistries.ITEM.getKey(snapshot.stack().getItem()).toString();
            ItemRequest want = wants.get(index);
            return want.wanted().matches(itemId, tags.tagsOf(itemId));
        }

        private String wantedName(int index) {
            return "从" + container.name() + "拿" + wants.get(index).wanted().describe();
        }

        // 世界记忆按物品记"里面有什么"：取东西时按它找箱子；件数以到场再开为准。
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
        // 手上还没有一笔搬运时（界面还没点开，run 只在界面认领后才建）没有在点的笔，
        // 也就没有停不得的窗口，照常报"在做"；这时被打断由打开动作自己收手，回来引擎按身上的清点再决定。
        // 控制循环每刻都会来问这一句，不能在这里抛：一抛整刻的推进被跳过，动作到不了终态，临时任务永远占着身体。
        @Override
        public Interruptibility interruptibility() {
            return run != null && run.unsafeToStop() ? Interruptibility.UNSAFE_TO_STOP : Interruptibility.WORKING;
        }

        // 被生存需求打断：正在走、正在点的先停住；界面开着就请游戏关上，回来时引擎按身上的清点再决定。
        @Override
        public void pause() {
            opening.pause();
            if (menu != null) menu.abandon();
        }

        // 收尾：打开动作一并收尾；界面还开着就请游戏关上，不让它悬着占着身体。
        // 收尾时一笔还没结清的，点出去的那几下先交回没能确认，再关界面。
        @Override
        public void close() {
            opening.close();
            if (closing != null) closing.close();
            if (menu != null && stage != Stage.DONE) {
                noteAbandonedMove("收尾时");
                menu.abandon();
            }
        }

        @Override
        public List<String> unconfirmedFacts() {
            return List.copyOf(unconfirmed);
        }

        @Override
        public String describe() {
            StringBuilder what = new StringBuilder();
            for (ItemRequest want : wants) {
                if (!what.isEmpty()) what.append("、");
                what.append(want.wanted().describe());
            }
            // 是机器料箱就说一句留了多少：结果里看得出为什么没拿够。
            for (int i = 0; i < wants.size(); i++) {
                if (limitedFor[i] < 0) continue;
                what.append("（这是给机器供料的箱子，").append(wants.get(i).wanted().describe())
                        .append(" 最满时 ").append(limitedFor[i]).append(" 件，至少留 ")
                        .append((limitedFor[i] + 1) / 2).append(" 件给机器）");
                break;
            }
            return "从" + container.name() + "拿" + what;
        }
    }

    /** 开箱取货的先后顺序。 */
    private enum Stage { OPEN, TRANSFER, CLOSE, DONE }
}
