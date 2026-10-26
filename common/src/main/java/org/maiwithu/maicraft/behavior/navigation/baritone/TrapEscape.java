// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 卡坑自动脱困：只垫到不了目标、人又困在封死的坑里时，不等 LLM 放宽许可，把这一趟的地形许可临时升一级
 * 重新算路——从"只垫不挖"升到"挖天然方块"，最多到挖天然方块，不碰受保护的、玩家的方块。
 * change_blocks=none 不在此列：none 是调用方显式下达的"一格都不动"，被困也如实按走不通收场，
 * 只读复算会写明哪里必须动土，由调用方决定放开许可或换目标。
 * 水路本来就在只走的路线里，只走不通等于水流这条已经试过。出了坑就降回任务原来的许可接着走；
 * 升到头还是出不去，才按走不通收场，并把试过什么写进说明。
 *
 * <p>和意外坠落救命照救一样是生存上的例外：在坑里一站几分钟等许可，是直播里最差的观感；
 * 但这个例外以调用方给过动土余地为前提，none 收得回它。
 */
final class TrapEscape {

    /** 脱困中隔多少刻看一眼出没出坑。 */
    private static final int CHECK_EVERY_TICKS = 10;
    /** 一趟最多升几次：出坑又掉回去、来回折腾时不无限升下去。 */
    static final int MAX_RAISES = 4;

    private final TerrainPermit base;
    /** 脱困中升到的那一档；没在脱困为 null。 */
    private TerrainPermit raised;
    private long nextCheck;
    private int placed;
    private int dug;
    private int raises;
    /** 这一趟每次脱困的经过。 */
    private final List<String> notes = new ArrayList<>();

    TrapEscape(TerrainPermit base) {
        this.base = base;
    }

    /** 此刻生效的地形许可：脱困中是升过的那一档，平时是任务给的。 */
    TerrainPermit effective() {
        return raised != null ? raised : base;
    }

    boolean active() {
        return raised != null;
    }

    /**
     * 引擎说没路时问一句：还能升（任务许可给了动土余地但低于挖天然方块）且人困在坑里，就升一级（只垫 → 挖）。
     * 升不了就不去看坑——none 一格都不许动、任务许可本来够、或已经升到头了。
     */
    boolean raise(BooleanSupplier trapped, long tick) {
        TerrainPermit next = next(effective());
        if (next == null || raises >= MAX_RAISES || !trapped.getAsBoolean()) return false;
        raises++;
        if (raised == null) {
            placed = 0;
            dug = 0;
        }
        raised = next;
        nextCheck = tick + CHECK_EVERY_TICKS;
        return true;
    }

    /**
     * 往上一级：只垫升到挖天然方块；已经能挖的不再升（不会升到"一切不受保护的"）。
     * none 不升：一格都不改是调用方明说的，宁可按走不通收场，也不背着许可动土。
     * 落地办法照任务的，跑酷开关也照原路的。
     */
    static TerrainPermit next(TerrainPermit permit) {
        return switch (permit.changes()) {
            case NONE -> null;
            case TEMPORARY -> new TerrainPermit(Permissions.BlockChanges.NATURAL, permit.mayUseLandingAids(), permit.mayParkour());
            case NATURAL, ANY -> null;
        };
    }

    /** 脱困中该不该看一眼出没出坑：隔几刻看一次。 */
    boolean dueCheck(long tick) {
        if (raised == null || tick < nextCheck) return false;
        nextCheck = tick + CHECK_EVERY_TICKS;
        return true;
    }

    /** 出坑了：记一笔经过，降回任务原来的许可。 */
    void restore() {
        if (raised == null) return;
        notes.add(story() + "，出来后按原来的许可接着走");
        raised = null;
    }

    void notePlaced() {
        if (raised != null) placed++;
    }

    void noteDug() {
        if (raised != null) dug++;
    }

    /** 这一趟每次脱困的经过（怎么脱的、垫了几块、挖开几格）；没脱过困为空。 */
    List<String> notes() {
        return List.copyOf(notes);
    }

    /** 升到头还是出不去：走不通的说明里补上试过什么。 */
    Problem explain(Problem problem) {
        if (raised == null || problem == null) return problem;
        return Problem.of(problem.kind(), problem.message() + "；" + story() + "，还是没出去", problem.suggestion());
    }

    private String story() {
        String how = raised.changes() == Permissions.BlockChanges.TEMPORARY ? "垫方块往上搭" : "挖开天然方块";
        return "卡在坑里，没等许可自动" + how + "脱困：垫了 " + placed + " 块、挖开 " + dug + " 格";
    }
}
