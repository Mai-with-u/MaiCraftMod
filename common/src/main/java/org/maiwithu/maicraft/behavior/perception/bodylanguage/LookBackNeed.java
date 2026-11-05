// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.client.player.LocalPlayer;

import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.interrupt.SurvivalNeed;
import org.maiwithu.maicraft.kernel.interrupt.ThreatResponder;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.Task;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.Urgency;

/**
 * 被拍了回头看：别的玩家拍了角色一下，角色当刻转过去看着他 2.5 秒，看完手上的活从原地接着做。
 * 蹲不蹲回去、说什么交给 LLM；不等 LLM 回话（它不理时角色会干站着盯人）。
 *
 * <p>借生存需求的接口挂进控制循环：急迫程度"尽快"，两个动作之间和正常干活时都能插；登记在所有生存需求之后，
 * 同样急的自卫、换气在跑时不插。它不是吃饭睡觉这类起居需求，主目标开着 survival_needs=off 也照样回头；
 * 主任务声明一个生存需求都不要（寻死）时不插。开始、结束都不发临时任务事件：拍一下已经在肢体动作事件里说了。
 *
 * <p>这些时候这一下就算了，不排队：手上的事停下不安全（悬空、驾驶）、骑着坐着东西（转头带着坐骑转）、开着界面、
 * 按着使用键（吃东西、拉弓，一松开就白按了）、在打仗。同一个人 10 秒内只回头一次，不管是谁 5 秒内只回头一次；
 * 两分钟内为同一个人回头三次、LLM 一次都没回他，之后两分钟不再为他回头（人也会不理他了）。
 */
public final class LookBackNeed implements SurvivalNeed {

    /** 被拍以后这么多刻内插不进来就算了。 */
    static final int FRESH_TICKS = 10;
    /** 同一个人多久（刻）内只回头一次：10 秒。 */
    static final int SAME_PLAYER_TICKS = 200;
    /** 不管是谁，多久（刻）内只回头一次：5 秒。 */
    static final int ANYONE_TICKS = 100;
    /** 两分钟内为同一个人回头几次都没回他，就先不理他两分钟。 */
    static final int IGNORED_LOOKS = 3;
    static final int IGNORED_WINDOW_TICKS = 2400;
    static final int COOL_OFF_TICKS = 2400;

    private final PokesFelt pokes;
    private long handledPokeTick = Long.MIN_VALUE;
    private Long lastLook;
    private final Map<UUID, Long> lastLookAt = new HashMap<>();
    private final Map<UUID, Deque<Long>> unanswered = new HashMap<>();
    private final Map<UUID, Long> coolOffUntil = new HashMap<>();
    private PokesFelt.Poke chosen;

    public LookBackNeed(PokesFelt pokes) {
        this.pokes = Objects.requireNonNull(pokes, "pokes");
    }

    /**
     * 此刻手上与身上的处境：回不回头只看这几样。
     *
     * @param unsafe    手上的事停下不安全
     * @param riding    骑着、坐着东西
     * @param screen    开着挡住世界的界面
     * @param usingItem 按着使用键
     * @param fighting  手上的事自己在打仗、还接得住
     */
    record Busy(boolean unsafe, boolean riding, boolean screen, boolean usingItem, boolean fighting) {
        static final Busy FREE = new Busy(false, false, false, false, false);

        boolean any() {
            return unsafe || riding || screen || usingItem || fighting;
        }
    }

    @Override public String name() {
        return "回头看";
    }

    @Override public Urgency urgency(TickContext context) {
        return urgency(context, null);
    }

    @Override public Urgency urgency(TickContext context, Task currentTask) {
        PlayerContext player = context.player();
        if (player == null || player.localPlayer() == null) return null;
        return decide(context.gameTick(), busy(context, player, currentTask));
    }

    /** LLM 回过他了（冲他做了肢体动作）：没回他的次数清零，不再晾着他。 */
    public void answered(UUID who) {
        unanswered.remove(who);
        coolOffUntil.remove(who);
    }

    // 有新的一下拍、还新鲜、这会儿腾得出身子、没超频：尽快插进来回头。不回的这一下记成处理过，不排队。
    Urgency decide(long now, Busy busy) {
        PokesFelt.Poke poke = pokes.latestPoke().orElse(null);
        if (poke == null || poke.tick() <= handledPokeTick) return null;
        if (now - poke.tick() > FRESH_TICKS || busy.any() || tooSoon(poke.who(), now)) {
            handledPokeTick = poke.tick();
            chosen = null;
            return null;
        }
        chosen = poke;
        return Urgency.SOON;
    }

    private boolean tooSoon(UUID who, long now) {
        if (lastLook != null && now - lastLook < ANYONE_TICKS) return true;
        Long last = lastLookAt.get(who);
        if (last != null && now - last < SAME_PLAYER_TICKS) return true;
        Long until = coolOffUntil.get(who);
        return until != null && now < until;
    }

    @Override public Task createTask(TickContext context) {
        PokesFelt.Poke poke = chosen;
        if (poke == null) return null;
        long now = context.gameTick();
        handledPokeTick = poke.tick();
        chosen = null;
        lookedAt(poke.who(), now);
        return new LookBackTask(pokes, poke, now);
    }

    // 记一次回头：两分钟内为他回头满三次、LLM 都没回他，先不理他两分钟。
    void lookedAt(UUID who, long now) {
        lastLook = now;
        lastLookAt.put(who, now);
        Deque<Long> looks = unanswered.computeIfAbsent(who, key -> new ArrayDeque<>());
        looks.addLast(now);
        while (!looks.isEmpty() && now - looks.peekFirst() > IGNORED_WINDOW_TICKS) looks.removeFirst();
        if (looks.size() >= IGNORED_LOOKS) {
            coolOffUntil.put(who, now + COOL_OFF_TICKS);
            looks.clear();
        }
    }

    // 不是起居需求：主目标关掉起居打断也照样回头。
    @Override public boolean routine() {
        return false;
    }

    // 拍一下已经在肢体动作事件里说了，回头看几秒不另发临时任务事件。
    @Override public boolean announcesTempTask() {
        return false;
    }

    // 每一下拍都是新的事，这一次没看成不按住下一次。
    @Override public boolean waitsAfterFailure() {
        return false;
    }

    private static Busy busy(TickContext context, PlayerContext player, Task currentTask) {
        LocalPlayer me = player.localPlayer();
        MenuActions menus = player.menuActions();
        boolean unsafe = currentTask != null
                && currentTask.interruptibility(context) == Interruptibility.UNSAFE_TO_STOP;
        boolean fighting = currentTask instanceof ThreatResponder responder
                && responder.confidentAgainstCurrentThreats();
        return new Busy(unsafe, me.isPassenger(), menus != null && menus.blocksWorld(player), player.usingItem(),
                fighting);
    }
}
