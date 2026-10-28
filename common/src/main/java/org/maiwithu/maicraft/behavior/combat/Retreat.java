// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.combat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.navigation.util.SpotScan;

/**
 * 撤离：往哪跑、甩没甩开、跑不动了没有。点名战斗与自卫用同一份。
 *
 * <p>往哪跑：以所有追兵位置的重心为反方向，左右九十度内依次试八个方向、四十格外，挑已加载、脚下踩得住、
 * 身子两格空的落点交给寻路，寻路同样尽量不进追兵的危险半径；知道家（或打点）在哪时，这几个落点里挑离家最近的。
 * 上一条路走不通就换下一个方向，不反复撞同一堵墙。
 *
 * <p>脱险：拉开之后还得安静一阵才算——连着几秒没有任何追兵锁定自己（没在追、也没打过我的）、
 * 又没有任何怪凑进危险半径，才承认脱险，如实收场交还身体。只认这次参与追击的与新近打过我的——
 * 远处没参与的怪不让这次逃跑无限延长；追兵还在远处晃着但既没追过来也够不着的，不让逃跑无限拖着。
 * 远程追兵（骷髅这类）甩出它的射程就够不着我了，它在射程外亮着攻击标记也不算锁定。
 * 开始了就跑到脱险，回了一点血不回头；身体真走出两格才清失败账，连续三次走不通才承认退无可退、背水一战。
 */
public final class Retreat {

    /** 落点离我多远：跑到就该甩开了。 */
    static final double REACH_OUT = ThreatAssessment.FLEE_CLEAR_DISTANCE;
    /** 从正背离方向起，依次偏这么多度试（左右九十度内八个方向）。 */
    private static final double[] OFFSETS = {0, 22.5, -22.5, 45, -45, 67.5, -67.5, 90};
    /** 一列里上下找落脚处最多看这么多格：再多是坑或悬崖。 */
    private static final int MAX_DROP = 4;
    /** 身体真走出这么远才算甩开了上一个死路，失败账清零。 */
    static final double REAL_PROGRESS = 2.0;

    private String why;
    private final Set<Integer> pursuers = new HashSet<>();
    /** 这次的追兵最后一次露面在哪（x, z）、之后又过了几刻：拐过墙角一时看不见的不当它凭空消失。 */
    private final Map<Integer, double[]> lastSeen = new HashMap<>();
    /** 连着多少刻没人锁定自己、也没有怪凑近（刻）：攒够这一段安静期才算脱险。 */
    private int calmTicks;
    private int failures;
    private int turns;
    private double[] lastFailureAt;

    /** 开始撤离：记下为什么、这次有哪些在追。 */
    void begin(String reason, Battlefield field) {
        why = reason;
        pursuers.clear();
        for (Battlefield.Foe foe : field.chasers()) pursuers.add(foe.id());
        lastSeen.clear();
        calmTicks = 0;
        failures = 0;
        turns = 0;
        lastFailureAt = null;
    }

    String why() {
        return why;
    }

    /** 这次参与追击的（实体编号）。 */
    Set<Integer> pursuers() {
        return Set.copyOf(pursuers);
    }

    /** 新冒出来追我的也算进这次的追兵。 */
    void noteChasers(Battlefield field) {
        for (Battlefield.Foe foe : field.chasers()) {
            if (foe.attacker() || foe.imminent()) pursuers.add(foe.id());
        }
    }

    /**
     * 脱险了没有：连着一段安静期（{@link #CALM_TICKS}）里没有任何这次追兵或打过我的在锁定自己、
     * 没有任何怪凑进危险半径、追兵也没有刚在近处消失，才算甩开。追兵还在远处但既没追也没够得着的，
     * 不打断安静期——撤离收场只看眼前还危不危险，不看它离得多远。
     */
    boolean escaped(Battlefield field) {
        remember(field);
        boolean calm = true;
        // 锁定：这次参与追击的或新近打过我的正在追我。远程追兵甩出它的射程就射不着了，
        // 它在射程外亮着攻击标记不算锁定——不然被骷髅追着一路跑进夜里（F 游戏事实：原版骷髅索敌与射箭 15 格）。
        for (Battlefield.Foe foe : field.chasers()) {
            if (!pursuers.contains(foe.id()) && !foe.attacker()) continue;
            if (foe.threat().kind() == ThreatAssessment.Kind.RANGED
                    && foe.distance() > RANGED_ESCAPE_DISTANCE) continue;
            calm = false;
        }
        // 危险半径：任何怪（含没参与的）凑到它的危险半径外加余量以内、或点着引信的进了波及范围，都还没脱险。
        for (Battlefield.Foe foe : field.foes()) {
            if (foe.fusing() && foe.distance() < foe.blastClearance()) calm = false;
            if (foe.distance() < foe.threat().dangerRadius() + DANGER_MARGIN) calm = false;
        }
        // 还看得见、会还手的就在 10 格内：它下一刻就能锁上来（目标标记一闪一闪也不当它没敌意），不算脱险。
        for (Battlefield.Foe foe : field.foes()) {
            if (foe.fightsBack() && foe.threat().visible() && foe.distance() < CLEAR_GAP) calm = false;
        }
        // 这次追兵刚从视野里消失、最后露面还在近处：多半就在墙后接着走，不当它走了。
        Battlefield.Me me = field.me();
        for (double[] seen : lastSeen.values()) {
            if (Math.hypot(seen[0] - me.x(), seen[1] - me.z()) < CLEAR_GAP) calm = false;
        }
        if (!calm) {
            calmTicks = 0;
            return false;
        }
        return ++calmTicks >= CALM_TICKS;
    }

    // 每刻记下这次追兵露面的位置；没露面的又老一刻，老过三秒就忘掉。
    private void remember(Battlefield field) {
        for (double[] seen : lastSeen.values()) seen[2]++;
        for (Battlefield.Foe foe : field.foes()) {
            if (pursuers.contains(foe.id()) || foe.attacker()) {
                lastSeen.put(foe.id(), new double[] {foe.threat().x(), foe.threat().z(), 0});
            }
        }
        lastSeen.values().removeIf(seen -> seen[2] > REMEMBER_TICKS);
    }

    /**
     * 往哪个反方向跑用的追兵重心：此刻露面的按此刻位置，一时看不见的按最后露面的位置；一个都没记着为 null。
     */
    double[] pursuersCenter() {
        if (lastSeen.isEmpty()) return null;
        double x = 0;
        double z = 0;
        for (double[] seen : lastSeen.values()) {
            x += seen[0];
            z += seen[1];
        }
        return new double[] {x / lastSeen.size(), z / lastSeen.size()};
    }

    /** 甩开要拉开的距离（格）：看得见的会还手的怪都在这之外。 */
    static final double CLEAR_GAP = 10;
    /** 追兵看不见多久（刻）才当它不在了：三秒。 */
    static final int REMEMBER_TICKS = 60;

    /**
     * 脱险要的安静期（刻）：连着三秒没人锁定自己、也没有怪凑近才收场。类别：玩家常识——甩开追兵后
     * 站下缓几秒没被再咬才算真脱险；太短会在边界上一进一出、撤离一秒收场一回。这个时长也覆盖
     * 「甩掉视线后短时间无新箭」：骷髅拉满一张弓约两秒，三秒没挨新箭就是它已经射不着了。
     */
    static final int CALM_TICKS = 60;

    /**
     * 危险半径外再留的余量（格）：刚停下时贴得近的怪一迈步就够得着，多留两格再算脱险。类别：玩家常识。
     */
    static final double DANGER_MARGIN = 2.0;

    /**
     * 远程追兵算甩开的距离（格）：原版骷髅索敌与射箭 15 格（游戏事实），再加 5 格余量；
     * 甩出这条线它就够不着我了，不用等它出四十格的视野圈。类别：游戏事实加常识余量。
     */
    static final double RANGED_ESCAPE_DISTANCE = 20;

    /**
     * 一条撤离路线走不通：换下一个方向再试；身体比上次失败处真走出两格就清账重数。
     *
     * @return 连续走不通到上限，退无可退
     */
    boolean routeFailed(double x, double z) {
        if (lastFailureAt != null && Math.hypot(x - lastFailureAt[0], z - lastFailureAt[1]) >= REAL_PROGRESS) {
            failures = 0;
        }
        lastFailureAt = new double[] {x, z};
        failures++;
        turns++;
        return failures >= ThreatAssessment.ESCAPE_FAILURES_BEFORE_LAST_STAND;
    }

    int turns() {
        return turns;
    }

    /**
     * 挑一个落点，往家（没记过家就往打点）那一侧跑：同样背离追兵重心、在左右九十度的八个方向里，挑能站、离家最近的那个；
     * 上一条走不通就按离家远近换下一个。实机 10-15 夜里低血撤离五次，每次只按追兵反方向跑，越跑离家越远，最远到西边 130 格外。
     * 不知道家和打点在哪（toward 为 null）时按八个方向挨个试，不给「离家最近」的偏好。
     *
     * @param toward 家或打点的水平位置 {x, z}
     */
    static int[] haven(double x, double y, double z, double fromX, double fromZ, int turns,
                       Predicate<BlockPos> standable, double[] toward) {
        if (toward == null) return haven(x, y, z, fromX, fromZ, turns, standable);
        double base = awayAngle(x, z, fromX, fromZ);
        List<int[]> landings = new ArrayList<>();
        for (double offset : OFFSETS) {
            int[] landing = landingAt(x, y, z, base + Math.toRadians(offset), standable);
            if (landing != null) landings.add(landing);
        }
        if (landings.isEmpty()) return null;
        // 按整格比远近，一样远时保持正背离在前（排序是稳定的）：人就站在打点上时，八个落点离打点一样远（落点取整差不到一格），
        // 照旧正背离着跑。
        landings.sort(Comparator.comparingLong(landing -> Math.round(Math.hypot(landing[0] - toward[0], landing[2] - toward[1]))));
        return landings.get(turns % landings.size());
    }

    /**
     * 挑一个落点：背离追兵重心，从第 turns 个方向试起，返回第一个能站的 {x, y, z}；都不行为 null（交给"离开追兵"的寻路）。
     */
    static int[] haven(double x, double y, double z, double fromX, double fromZ, int turns,
                       Predicate<BlockPos> standable) {
        double base = awayAngle(x, z, fromX, fromZ);
        for (int i = 0; i < OFFSETS.length; i++) {
            int[] landing = landingAt(x, y, z, base + Math.toRadians(OFFSETS[(i + turns) % OFFSETS.length]), standable);
            if (landing != null) return landing;
        }
        return null;
    }

    // 背离追兵重心的方向（弧度）；人正好站在重心上时随便取一个。
    private static double awayAngle(double x, double z, double fromX, double fromZ) {
        double awayX = x - fromX;
        double awayZ = z - fromZ;
        if (Math.hypot(awayX, awayZ) < 1.0e-4) {
            awayX = 1;
            awayZ = 0;
        }
        return Math.atan2(awayZ, awayX);
    }

    // 朝这个方向四十格外的那一列里找能站的落脚处。
    private static int[] landingAt(double x, double y, double z, double angle, Predicate<BlockPos> standable) {
        int cx = (int) Math.round(x + Math.cos(angle) * REACH_OUT);
        int cz = (int) Math.round(z + Math.sin(angle) * REACH_OUT);
        return standableNear(cx, (int) Math.floor(y), cz, standable);
    }

    // 这一列上离原高度最近的能站处：先同高、再上下交替往外各试到四格，列内挑选交给环扫内核。
    private static int[] standableNear(int x, int aroundY, int z, Predicate<BlockPos> standable) {
        return SpotScan.inColumn(new BlockPos(x, aroundY, z), MAX_DROP, standable)
                .map(cell -> new int[] {cell.getX(), cell.getY(), cell.getZ()})
                .orElse(null);
    }
}
