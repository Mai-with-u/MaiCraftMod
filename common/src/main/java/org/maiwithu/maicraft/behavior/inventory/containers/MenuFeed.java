// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.containers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.menu.ClientMenuOpening;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 带界面的机器经界面投料：走过去右键点开机器的界面，把料从背包搬进去（整堆快速移动，机器自己把它放进收它的格；
 * 只放一部分时拆出这几件放进一格收它的空格），关上界面。界面认不认得、哪几格能点，看联动登记的界面布局。
 * 搬运与存东西能力同一套，每一笔按背包少了几件、界面里多了几件对上才算放进去。
 *
 * <p>做完交出实情：放进去几件（{@link #delivered()}）、过程中的说明（{@link #notes()}）；
 * 放不下（输入格满了、这样东西它不收）、两边件数对不上的写进说明。一件都没放进去时失败。
 *
 * <p>机器里已经有的够几件就少投几件的（按机器储罐或格子里现成的扣），由构造方给 {@link TopUp}：
 * 界面一打开、第一次要搬之前问一次要扣掉几件，扣完不用投就算做完。
 *
 * <p>投料前后要就地记一笔的（投之前机器格里原有什么、确认放进去了、一件都没放进时补现场），
 * 由构造方给 {@link Hooks}。
 */
public final class MenuFeed implements Action, FeedReport {

    /** 界面开着、第一次要搬之前问一次：少投几件，原因写进 notes。 */
    public interface TopUp {
        int skip(int asked, List<String> notes);
    }

    /** 投料前后就地记账的时机：首次推进前记一笔、确认至少放进一件并做成时记一笔、一件没放进失败时补现场。 */
    public record Hooks(Runnable onStart, Runnable onDelivered, UnaryOperator<Problem> onFailed) {
        public Hooks {
            Objects.requireNonNull(onStart, "onStart");
            Objects.requireNonNull(onDelivered, "onDelivered");
            Objects.requireNonNull(onFailed, "onFailed");
        }
    }

    private final String itemId;
    private int asked;
    private final StoresInContainer storing;
    private int stored;
    private final List<String> notes = new ArrayList<>();
    /** 投之前要扣掉机器里已有的；不用扣为 null。 */
    private final TopUp topUp;
    private boolean toppedUp;
    /** 投料前后就地记账的时机；不用记账为 null。 */
    private final Hooks hooks;
    private boolean started;
    private boolean deliveredNoted;

    /**
     * @param machine     机器叫什么，给描述与说明用，例如"富集仓"
     * @param blockTypeId 机器方块的注册 ID
     */
    public MenuFeed(PlayerServices services, String machine, BlockPos at, String blockTypeId, String itemId, int count,
            Permissions permissions) {
        this(services, machine, at, blockTypeId, itemId, count, permissions, null);
    }

    /** @param topUp 界面打开后先扣掉机器里已有的几件；不用扣给 null */
    public MenuFeed(PlayerServices services, String machine, BlockPos at, String blockTypeId, String itemId, int count,
            Permissions permissions, TopUp topUp) {
        this(services, machine, at, blockTypeId, itemId, count, permissions, topUp, null);
    }

    /** @param hooks 投料前后就地记账的时机；不用记账给 null */
    public MenuFeed(PlayerServices services, String machine, BlockPos at, String blockTypeId, String itemId, int count,
            Permissions permissions, TopUp topUp, Hooks hooks) {
        this(machine, at, blockTypeId, itemId, count, permissions, topUp, hooks,
                (cell, allowed) -> {
                    Objects.requireNonNull(services, "services");
                    return new ClientMenuOpening(cell, allowed, services.menuLayouts(), services.approach(),
                            services.interactions(), services.context());
                });
        Objects.requireNonNull(services, "services");
    }

    // 包内测试可注入替身界面打开动作，离线核对按标签投料与瞬间吃料记账。
    MenuFeed(String machine, BlockPos at, String blockTypeId, String itemId, int count,
            Permissions permissions, TopUp topUp, Hooks hooks, StoresInContainer.OpensContainer opens) {
        Objects.requireNonNull(opens, "opens");
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        this.asked = count;
        this.topUp = topUp;
        this.hooks = hooks;
        StoresInContainer.Ledger ledger = new StoresInContainer.Ledger() {
            // 搬第一笔前界面已经开着、储罐也同步过来了：这时问一次机器里已有多少，扣掉够数的几件。
            @Override public int left(String id) {
                if (!id.equals(itemId)) return 0;
                if (MenuFeed.this.topUp != null && !toppedUp) {
                    toppedUp = true;
                    asked -= Math.max(0, Math.min(asked, MenuFeed.this.topUp.skip(asked, notes)));
                }
                return asked - stored;
            }

            @Override public void stored(String id, int amount) {
                stored += amount;
            }

            @Override public void passedThrough(String id, int amount, String fact) {
                // 快速加工格在点击瞬间就把物品吃掉或转成灌注物时，物品已离开背包进入机器，计入已投件数并留说明。
                stored += amount;
                notes.add(fact);
            }

            @Override public void unconfirmed(String fact) {
                notes.add(fact);
            }

            @Override public void attempt(String tried, String whatHappened) {
                notes.add(tried + "：" + whatHappened);
            }
        };
        // 点开机器界面和点开箱子是同一个动作：走到够得着、看得见的地方右键，认领界面并等内容同步。
        StoresInContainer.Parts parts = new StoresInContainer.Parts(opens, null);
        this.storing = new StoresInContainer(new KnownContainer(machine,
                WorldPosition.here(at.getX(), at.getY(), at.getZ()), blockTypeId), List.of(itemId), ledger,
                permissions, parts);
    }

    // 搬完确认件数：一件没放进去按失败，带上点开、放不下的经过；放进去的少于要的写一句为什么。
    // 机器里已有的够了、扣完一件都不用投的，不算失败。
    @Override public ActionStatus tick(TickContext context) {
        // 首次推进先记投料前的现场（机器格里原有什么），再开始点开与搬运。
        if (hooks != null && !started) {
            started = true;
            hooks.onStart().run();
        }
        ActionStatus status = storing.tick(context);
        if (status instanceof ActionStatus.Failed failed) {
            return failedWithNotes(failed.problem());
        }
        if (!(status instanceof ActionStatus.Done)) return status;
        // 确认至少放进一件并做成了才记"放进去了"，重推到的完成不算第二笔。
        if (hooks != null && !deliveredNoted) {
            deliveredNoted = true;
            if (stored > 0) hooks.onDelivered().run();
        }
        if (asked == 0) return status;
        if (stored == 0) {
            String seen = notes.isEmpty() ? "" : "：" + String.join("；", notes);
            return failedWithNotes(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    "界面里一件 " + itemId + " 都没放进去" + seen));
        }
        if (stored < asked) {
            notes.add("界面里放进去 " + stored + " 件 " + itemId + "，要的是 " + asked + " 件：收它的格满了，或身上不够");
        }
        return status;
    }

    // 失败先经记账口补机器槽内的现场实情，再交回给调用方。
    private ActionStatus failedWithNotes(Problem problem) {
        return ActionStatus.failed(hooks == null ? problem : hooks.onFailed().apply(problem));
    }

    /** 确认放进界面的件数；给调用方交投料实情用。 */
    public int delivered() {
        return stored;
    }

    /** 放不下的、两边对不上的那些事，一句一条。 */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    @Override public void pause() {
        storing.pause();
    }

    @Override public void close() {
        storing.close();
    }

    @Override public Interruptibility interruptibility() {
        return storing.interruptibility();
    }

    @Override public String describe() {
        return storing.describe();
    }
}
