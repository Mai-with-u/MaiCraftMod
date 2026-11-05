// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.cook;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.CookingLeftBehind;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 装炉就走留下的那几台：obtain 一炉要烧很久时装好就走，记在这里；回来再要同一样东西时先回记着的那台、
 * 原料按炉子里还在烧的少备；回到那台点开核对过就划掉（人已经回来了，接下来按炉子里实际有什么做）。
 * 每个世界一份，换世界不带过去。挂计时、摘计时经启动时接上的口子做：留下一炉挂一个，
 * 同一台换下或划掉时把还挂着的旧计时摘掉，免得人回来过了还被叫。只在客户端线程用。
 */
public final class FurnacesLeftCooking implements CookingLeftBehind {

    /** 挂计时、摘计时的口子：计时表在能力层，启动时接上。 */
    public interface SetsTimers {

        /** 不挂计时：测试和没接上计时表时用，留下的那一炉照样记，只是没有计时编号。 */
        SetsTimers NONE = new SetsTimers() {
            @Override public Optional<Integer> set(int afterSeconds, String note, long gameTick) {
                return Optional.empty();
            }

            @Override public void cancel(int timerId) {}
        };

        /** 挂一个过 afterSeconds 秒（游戏时间）叫的计时，带备注；挂满了为空。 */
        Optional<Integer> set(int afterSeconds, String note, long gameTick);

        /** 摘掉一个计时；已经到点叫过、早就不在了就算了。 */
        void cancel(int timerId);
    }

    /** 计时比估的烧完时刻再晚这么久叫：宁可晚一点回来，也别回来看见还差一件。 */
    static final int TIMER_MARGIN_SECONDS = 1;

    /** 记下的一炉连同它是第几回留下的：obtain 收场时按回数挑出这一场留下的。 */
    private record Kept(LeftCooking cooking, int number) {}

    private final SetsTimers timers;
    private final List<Kept> kept = new ArrayList<>();
    private int timesLeft;
    private LeftCooking last;

    public FurnacesLeftCooking(SetsTimers timers) {
        this.timers = Objects.requireNonNull(timers, "timers");
    }

    /**
     * 装好一炉就走：挂上计时，记下这一炉。同一台炉子原先记着的那一炉换下来，它还挂着的计时摘掉。
     *
     * @return 记下的那一炉，挂上了计时就带着计时编号
     */
    public LeftCooking leave(LeftCooking cooking) {
        removeAt(cooking.furnace());
        int afterSeconds = (int) Math.min(Integer.MAX_VALUE, cooking.readyInSeconds() + TIMER_MARGIN_SECONDS);
        LeftCooking stored = cooking.withTimer(
                timers.set(afterSeconds, cooking.timerNote(), cooking.leftAtGameTick()).orElse(null));
        kept.add(new Kept(stored, ++timesLeft));
        last = stored;
        return stored;
    }

    /** 这台炉子记着留下的那一炉；没有为空。 */
    public Optional<LeftCooking> at(WorldPosition furnace) {
        return kept.stream().map(Kept::cooking).filter(cooking -> sameFurnace(cooking.furnace(), furnace)).findFirst();
    }

    /**
     * 在烧这样东西、按这条配方之一的那几台，只算 dimension 这个维度里的（null 不分维度）：回来再要它时先回这几台。
     */
    public List<LeftCooking> cooking(String resultItemId, String dimension) {
        return kept.stream().map(Kept::cooking)
                .filter(cooking -> cooking.resultItemId().equals(resultItemId))
                .filter(cooking -> sameDimension(cooking.furnace().dimension(), dimension))
                .toList();
    }

    /** 回到这台点开核对过：划掉它，还挂着的计时摘掉。 */
    public void collected(WorldPosition furnace) {
        removeAt(furnace);
    }

    /** 第 mark 回以后留下、此刻还记着的那几炉，先留下的在前：obtain 开场记下回数，收场时挑出这一场留下的。 */
    public List<LeftCooking> since(int mark) {
        return kept.stream().filter(entry -> entry.number() > mark).map(Kept::cooking).toList();
    }

    @Override public int timesLeft() {
        return timesLeft;
    }

    // 最近留下的那一炉：结论句说在哪、烧几件、约几分钟好、背包里还剩多少；建议说计时编号和到点怎么做。
    // 还没到取的时候，按"时间不对"交出去。
    @Override public Problem lastLeft() {
        if (last == null) throw new IllegalStateException("没有留下过一炉，不该问怎么说");
        return Problem.of(Problem.Kind.WRONG_TIME, "装好炉先走了：" + last.sentence(), last.suggestion());
    }

    private void removeAt(WorldPosition furnace) {
        kept.removeIf(entry -> {
            if (!sameFurnace(entry.cooking().furnace(), furnace)) return false;
            if (entry.cooking().timerId() != null) timers.cancel(entry.cooking().timerId());
            return true;
        });
    }

    // 同一台炉子：格子一样，维度有一边没写（就是角色当前的维度）或一样。
    private static boolean sameFurnace(WorldPosition a, WorldPosition b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && sameDimension(a.dimension(), b.dimension());
    }

    private static boolean sameDimension(String a, String b) {
        return a == null || b == null || a.equals(b);
    }
}
