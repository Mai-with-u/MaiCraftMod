// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.navigation.OthersBlockBroken;
import org.maiwithu.maicraft.behavior.navigation.TerrainPermit;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.OnlinePlayers;
import org.maiwithu.maicraft.kernel.event.TaskEvent;
import org.maiwithu.maicraft.kernel.event.TaskEventSink;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 卡坑自动脱困：到不了目标、人又困在封死的坑里时，不等 LLM 放宽许可，把这一趟的地形许可临时升一级
 * 重新算路——从"只垫不挖"升到"挖天然方块"；已经能挖天然方块还是出不去（多半是被别人放的方块封死了），
 * 再升一级，这一趟放开别人放的普通方块，功能方块、记住的区域与地点旁边的照旧不碰。
 * change_blocks=none 不在此列：none 是调用方显式下达的"一格都不动"，被困也如实按走不通收场，
 * 只读复算会写明哪里必须动土、四周是谁的方块，由调用方决定放开许可或换目标。
 * 水路本来就在只走的路线里，只走不通等于水流这条已经试过。出了坑就降回任务原来的许可、收回放开接着走；
 * 升到头还是出不去，才按走不通收场，并把试过什么写进说明。
 *
 * <p>挖开的每一格别人的方块都记下谁放的、什么方块、在哪，一趟最多挖 {@link #MAX_OTHERS_BLOCKS} 格；
 * 这次脱困一收场（出了坑或走不通）当场发一条事件，宿主不用等走路结束就知道挖了谁的。
 *
 * <p>和意外坠落救命照救一样是生存上的例外：在坑里一站几分钟等许可，是直播里最差的观感；
 * 但这个例外以调用方给过动土余地为前提，none 收得回它。
 */
final class TrapEscape {

    /** 脱困中隔多少刻看一眼出没出坑。 */
    private static final int CHECK_EVERY_TICKS = 10;
    /** 一趟最多升几次：出坑又掉回去、来回折腾时不无限升下去。 */
    static final int MAX_RAISES = 4;
    /** 一趟最多挖开别人放的这么多格：两格高的身子，够挖一条 12 格长的通道；再多按走不通收场。 */
    static final int MAX_OTHERS_BLOCKS = 24;

    private final TerrainPermit base;
    private final TaskEventSink events;
    /** 脱困中升到的那一档；没在脱困为 null。 */
    private TerrainPermit raised;
    /** 脱困中放开了别人放的普通方块（最后一级）。 */
    private boolean throughOthers;
    /** 这次脱困从哪一格开始：事件里说在哪被封住。 */
    private BlockPos trappedAt;
    private long nextCheck;
    private int placed;
    private int dug;
    private int raises;
    /** 这次脱困挖开的别人的方块；报过事件就不再报。 */
    private final List<OthersBlockBroken> othersThisTime = new ArrayList<>();
    private boolean reported;
    /** 这一趟挖开的别人的方块（几次脱困合起来），出行结算逐格列出。 */
    private final List<OthersBlockBroken> othersAll = new ArrayList<>();
    /** 正在挖的那一格与它是谁放的：挖之前趁方块还在问好，挖开后服务端会把这一格的放置记录去掉。 */
    private BlockPos diggingAt;
    private OthersBlockBroken diggingOthers;
    /** 这一趟每次脱困的经过。 */
    private final List<String> notes = new ArrayList<>();

    TrapEscape(TerrainPermit base) {
        this(base, TaskEventSink.NONE);
    }

    /** @param events 这次脱困挖了别人的方块时，当场报给宿主的出口 */
    TrapEscape(TerrainPermit base, TaskEventSink events) {
        this.base = base;
        this.events = events;
    }

    /** 此刻生效的地形许可：脱困中是升过的那一档，平时是任务给的。 */
    TerrainPermit effective() {
        return raised != null ? raised : base;
    }

    boolean active() {
        return raised != null;
    }

    /** 这一趟此刻放没放开别人放的普通方块：装保护要求时带上。 */
    boolean throughOthers() {
        return throughOthers;
    }

    /**
     * 引擎说没路时问一句：人困在坑里，就升一级——只垫升到挖天然方块；已经能挖天然方块还是没路，放开别人放的普通方块。
     * 升不了就不去看坑：none 一格都不许动、已经升到头了、这一趟挖别人的方块到了上限。
     *
     * @param feet 此刻脚下那一格：事件里说在哪被封住
     */
    boolean raise(BlockPos feet, BooleanSupplier trapped, long tick) {
        TerrainPermit next = next(effective());
        boolean openOthers = next == null && mayOpenOthers();
        if (raises >= MAX_RAISES || next == null && !openOthers || !trapped.getAsBoolean()) return false;
        raises++;
        if (raised == null) {
            placed = 0;
            dug = 0;
            othersThisTime.clear();
            reported = false;
            trappedAt = feet == null ? null : feet.immutable();
        }
        if (openOthers) {
            raised = effective();
            throughOthers = true;
        } else {
            raised = next;
        }
        nextCheck = tick + CHECK_EVERY_TICKS;
        return true;
    }

    // 放不放开别人的普通方块：none 不放（一格都不动是调用方明说的）；已经放开的不再放；这一趟挖到上限的不再放。
    private boolean mayOpenOthers() {
        return base.changes() != Permissions.BlockChanges.NONE && !throughOthers
                && othersAll.size() < MAX_OTHERS_BLOCKS;
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

    /** 出坑了：记一笔经过，挖了别人的方块就当场报一条事件；收回放开，降回任务原来的许可。 */
    void restore() {
        if (raised == null) return;
        notes.add(story() + "，出来后按原来的许可接着走");
        report(Boolean.TRUE);
        raised = null;
        throughOthers = false;
    }

    /**
     * 这一趟收场时还在脱困（走到了目的地或停下，没来得及看一眼出没出坑）：把这次的经过记下，
     * 挖过别人的方块就报一条事件；走到了算出来了，停下的不说出没出来。走不通的已经在说明里报过，不重报。
     */
    void finish(boolean arrived) {
        if (raised == null) return;
        notes.add(story() + (arrived ? "，走到了目的地" : ""));
        report(arrived ? Boolean.TRUE : null);
        raised = null;
        throughOthers = false;
    }

    void notePlaced() {
        if (raised != null) placed++;
    }

    /**
     * 每刻挖之前看一眼正在挖的那一格：这一趟放开了别人的普通方块、这一格正是，就趁方块还在记下是谁放的、什么方块。
     * 放的人在线写名字，不在线写编号。
     */
    void watchDigging(BlockPos digging, BlockState state, PlayerContext context) {
        if (!throughOthers || digging == null || state == null || state.isAir() || digging.equals(diggingAt)) return;
        diggingAt = digging.immutable();
        diggingOthers = NavigationProtection.escapeOwner(digging, state)
                .map(owner -> new OthersBlockBroken(digging.toShortString(),
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), nameOf(owner, context)))
                .orElse(null);
    }

    /**
     * 挖开了一格：脱困中记一笔挖开了几格；挖的是放开给脱困的别人的方块，就记下谁放的、什么方块、在哪。
     * 返回这一下是不是挖到了一趟的上限、刚把放开收回：调用方要按新的保护要求重新算路。
     */
    boolean noteBroken(BlockPos at) {
        if (raised != null) dug++;
        OthersBlockBroken others = at != null && at.equals(diggingAt) ? diggingOthers : null;
        diggingAt = null;
        diggingOthers = null;
        if (others == null) return false;
        othersThisTime.add(others);
        othersAll.add(others);
        if (!throughOthers || othersAll.size() < MAX_OTHERS_BLOCKS) return false;
        throughOthers = false;
        return true;
    }

    /** 这一趟每次脱困的经过（怎么脱的、垫了几块、挖开几格、挖了谁的）；没脱过困为空。 */
    List<String> notes() {
        return List.copyOf(notes);
    }

    /** 这一趟挖开的别人的方块（几次脱困合起来）；没挖过为空。 */
    List<OthersBlockBroken> othersBroken() {
        return List.copyOf(othersAll);
    }

    /**
     * 升到头还是出不去：走不通的说明里补上这次脱困实际做了什么，再说身边挡着什么、为什么没挖开、还剩哪条出路；
     * 挖过别人的方块就当场报一条事件。
     *
     * @param walls           身边的坑壁，只在这次确实在脱困时才读
     * @param carriesScaffold 手边有没有能垫的料：只能垫上去时说得出有没有料可垫
     */
    Problem explain(Problem problem, Supplier<TrapWalls> walls, boolean carriesScaffold) {
        if (raised == null || problem == null) return problem;
        report(Boolean.FALSE);
        String capped = othersAll.size() >= MAX_OTHERS_BLOCKS
                ? "，挖别人放的方块到了一趟 " + MAX_OTHERS_BLOCKS + " 格的上限" : "";
        // 垫过、挖过还是没出去才说"还是没出去"；一格没动的直接接着说挡着的是什么，那才是 LLM 要的原因。
        String tried = story() + capped + (placed > 0 || dug > 0 ? "，还是没出去" : "");
        // 出路按任务原来的许可说：脱困临时升的那一档只管寻路，单独下令挖一格仍按任务许可检查。
        String around = walls.get().describe(base.changes(), carriesScaffold);
        return Problem.of(problem.kind(), problem.message() + "；" + tried + (around.isEmpty() ? "" : "；" + around),
                problem.suggestion());
    }

    /**
     * 这次脱困实际发生了什么：垫了几块、挖开几格，一格没动就说一格没动。只有任务许可本来只垫不挖、
     * 这次临时升到能挖天然方块时才说"没等许可"；"别人放的"只在真挖开了有放置记录的格子时才说，并按放的人分开数。
     */
    private String story() {
        String loosened = raised.changes() != base.changes() ? "没等许可临时放开挖天然方块，" : "";
        List<String> did = new ArrayList<>();
        if (placed > 0) did.add("垫了 " + placed + " 块");
        if (dug > 0) did.add("挖开 " + dug + " 格");
        String told = "卡在坑里，" + loosened + "自动脱困" + (did.isEmpty() ? "一格没动" : "：" + String.join("、", did));
        return othersThisTime.isEmpty() ? told : told + "，其中" + whose(othersThisTime);
    }

    /** 挖开的别人的方块按放的人分开数，例如"甲 放的 3 格、乙 放的 1 格"。 */
    static String whose(List<OthersBlockBroken> blocks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (OthersBlockBroken block : blocks) counts.merge(block.placedBy(), 1, Integer::sum);
        StringJoiner joined = new StringJoiner("、");
        counts.forEach((who, count) -> joined.add(who + " 放的 " + count + " 格"));
        return joined.toString();
    }

    // 这次脱困挖了别人的方块：收场时当场报一条事件，说在哪被封住、挖了谁的几格、出没出来（说不准就不说）；同一次只报一次。
    private void report(Boolean out) {
        if (othersThisTime.isEmpty() || reported) return;
        reported = true;
        String where = trappedAt == null ? "坑里" : trappedAt.toShortString() + " 的坑里";
        String outcome = out == null ? "" : out ? "，出来了" : "，还是没出去";
        events.publish(TaskEvent.Kind.OTHERS_BLOCKS_BROKEN, "被别人放的方块封在 " + where + "，挖开了"
                + whose(othersThisTime) + outcome);
    }

    // 放的人在线就写名字，不在线写编号；编号读不出来照原样写。
    static String nameOf(String owner, PlayerContext context) {
        return OnlinePlayers.nameOrId(context, owner);
    }
}
