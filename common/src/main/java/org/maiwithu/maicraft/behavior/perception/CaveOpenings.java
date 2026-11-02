// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * 洞口判断：认出眼前看得见的、通往地下的开口——洞口与峡谷。纯判断，只读 {@link Ground}，离线测试用替身。
 *
 * <p>洞口：能进人的空格，头顶不见天、挨着见天的空气（地面上那一片），从开口往里沿能进人的空格量得到
 * 一片天空光不到 8 的暗处（至少 24 格）。崖檐下、树底下浅浅凹进去几格的，里面不够暗，不算洞。
 * 峡谷：地表比两侧 8 格内都低 10 格以上的列，四邻相连成片，最长那一向至少 16 格。
 * 两样都要从眼睛看得见：洞口看开口格，峡谷看沟底或沟里的空气；墙后的、拐角后的不报。
 * 往里量只用来判断"是不是个洞、往哪边去"，不报里面有什么，更不拿它找矿。
 *
 * <p>感知的场景与钻洞起步用的是同一份结论：钻洞挑洞口就挑场景里报的那几处。
 */
public final class CaveOpenings {

    /** 水平看多远（格）：俯视网格只看 8 格，洞口要远一点就看得见。 */
    public static final int RADIUS = 32;
    /** 上下看多远（格）。 */
    public static final int HEIGHT = 24;
    /** 往里量到这么多格暗处才算洞。 */
    static final int DARK_CELLS = 24;
    /** 天空光低于它的空格算暗处。 */
    static final int DARK_SKY_LIGHT = 8;
    /** 露天的空格天空光是 15；低于它就是头顶有遮挡。 */
    static final int OPEN_SKY_LIGHT = 15;
    /** 往里最多量多少格：量到就停，说不出整个洞多大，只说连着一片暗处。 */
    static final int MAX_FLOOD = 512;
    /** 同一个洞的开口相隔几格以内合成一处。 */
    static final int MERGE = 6;
    /** 峡谷：比两侧低多少格。 */
    static final int RAVINE_DEPTH = 10;
    /** 峡谷：两侧各看多远。 */
    static final int RAVINE_SIDE = 8;
    /** 峡谷：最长那一向至少多长；短的是坑，照旧当落差。 */
    static final int RAVINE_LENGTH = 16;
    /** 每处最多看几格视线：离角色近的先看，看见一格就算看得见。 */
    static final int SIGHT_CHECKS = 8;
    /** 暗处大多在开口这么多格以下，算"往下"。 */
    static final int DOWNWARD = 4;

    private CaveOpenings() {}

    /** 只读现场：洞口判断要的几样世界事实。生产读端读客户端世界，测试用替身。 */
    public interface Ground {

        /** 这一列加载了没有；没加载的不判。 */
        boolean loaded(int x, int z);

        /** 地表：这一列跳过树叶、树干后的最高遮挡物（含水、岩浆）上面那一格的高度；再往上是见天的空气。 */
        int surfaceY(int x, int z);

        /** 能进人：身体占得下这一格，不是水也不是岩浆。 */
        boolean roomy(int x, int y, int z);

        /** 能站：脚、头两格能进人，脚下踩得住，不在水里。 */
        boolean standable(int x, int y, int z);

        /** 这一格的天空光（0..15）。 */
        int skyLight(int x, int y, int z);

        /** 从角色眼睛看得见这一格（过视线闸）。 */
        boolean visible(int x, int y, int z);
    }

    /** 开口的种类。 */
    public enum Kind {
        /** 洞口：往里连着一片暗处的开口。 */
        CAVE_MOUTH("洞口"),
        /** 峡谷：见天的又长又深的沟。 */
        RAVINE("峡谷");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** 对 LLM 说的名字：场景里地形特征的种类、世界记忆里线索的内容都用它。 */
        public String word() {
            return word;
        }
    }

    /**
     * 一处看得见的开口。
     *
     * @param kind     洞口还是峡谷
     * @param at       离角色最近的能站的那一格（真实高度）：洞口是开口格，峡谷是沟底
     * @param goesDown 洞口的暗处大多在开口以下（往下）；横着进山、峡谷为假
     * @param length   峡谷长约几格；洞口为 0
     * @param depth    峡谷深约几格；洞口为 0
     * @param minX     占的范围（水平），西边界
     * @param minZ     北边界
     * @param maxX     东边界
     * @param maxZ     南边界
     */
    public record Opening(Kind kind, BlockPos at, boolean goesDown, int length, int depth,
            int minX, int minZ, int maxX, int maxZ) {

        public Opening {
            at = at.immutable();
        }

        /** 一列落不落在这处开口占的范围里（含四边各放宽 margin 格）：报了峡谷的那一片不再报成落差。 */
        public boolean covers(int x, int z, int margin) {
            return x >= minX - margin && x <= maxX + margin && z >= minZ - margin && z <= maxZ + margin;
        }
    }

    /**
     * 扫一圈：以出发时角色脚下为中心分刻走，一刻只读一小段，不占满一刻。
     * 先读周围各列的地表，再逐列找开口格与沟，最后聚成一处一处、往里量暗处、看视线。
     */
    public static final class Sweep {

        /** 一刻读几列地表。 */
        static final int SURFACE_COLUMNS_PER_TICK = 512;
        /** 一刻找几列的开口格与沟。 */
        static final int SCAN_COLUMNS_PER_TICK = 160;
        /** 一刻往里量多少格暗处。 */
        static final int FLOOD_CELLS_PER_TICK = 2048;
        /** 没加载的列在地表表里的记号。 */
        private static final int UNLOADED = Integer.MIN_VALUE;

        private final Ground ground;
        private final BlockPos center;
        /** 地表表的半边长：峡谷两侧还要往外看几格，比看的范围多出那几格。 */
        private final int span = RADIUS + RAVINE_SIDE;
        private final int side = 2 * span + 1;
        private final int[] surface = new int[side * side];
        private int surfaceCursor;
        private int scanCursor;
        private final Set<BlockPos> mouthCells = new HashSet<>();
        /** 沟里的列与它比两侧低多少格。 */
        private final Map<Long, Integer> slotDepth = new HashMap<>();
        private List<List<BlockPos>> mouthGroups;
        private int groupCursor;
        private final List<Opening> found = new ArrayList<>();
        private boolean done;

        public Sweep(Ground ground, BlockPos center) {
            this.ground = ground;
            this.center = center.immutable();
        }

        /** 这一圈的中心（出发时角色脚下）。 */
        public BlockPos center() {
            return center;
        }

        /** 推进一刻；扫完返回真。 */
        public boolean tick() {
            if (done) {
                return true;
            }
            if (surfaceCursor < side * side) {
                readSurfaces();
                return false;
            }
            if (scanCursor < side * side) {
                scanColumns();
                return false;
            }
            if (mouthGroups == null) {
                // 开口格聚成一处一处；峡谷只看地表表，这一刻一并聚完。
                mouthGroups = group(new ArrayList<>(mouthCells));
                judgeRavines();
                return false;
            }
            if (groupCursor < mouthGroups.size()) {
                judgeMouths();
                return false;
            }
            found.sort(Comparator.comparingDouble(opening -> opening.at().distSqr(center)));
            done = true;
            return true;
        }

        /** 这一圈看到的开口，近的在前；没扫完时是空的。 */
        public List<Opening> openings() {
            return done ? List.copyOf(found) : List.of();
        }

        // 地表表：中心周围一个方块里每列读一次，没加载的记下来不判。
        private void readSurfaces() {
            int end = Math.min(side * side, surfaceCursor + SURFACE_COLUMNS_PER_TICK);
            for (; surfaceCursor < end; surfaceCursor++) {
                int x = center.getX() + surfaceCursor % side - span;
                int z = center.getZ() + surfaceCursor / side - span;
                surface[surfaceCursor] = ground.loaded(x, z) ? ground.surfaceY(x, z) : UNLOADED;
            }
        }

        // 逐列找：圈里的每一列看一眼它挨着的低处有没有能进人的开口格，再看它是不是夹在两侧高地中间的沟。
        private void scanColumns() {
            int end = Math.min(side * side, scanCursor + SCAN_COLUMNS_PER_TICK);
            for (; scanCursor < end; scanCursor++) {
                int dx = scanCursor % side - span;
                int dz = scanCursor / side - span;
                if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int top = surfaceAt(x, z);
                if (top == UNLOADED) continue;
                findMouthCells(x, z, top);
                noteSlot(x, z, top);
            }
        }

        // 开口格：这一列地表之下、头顶有遮挡的两格能进人的空格，挨着旁边一列地表之上的空气（见天的那一片）。
        // 旁边那列至少低三格，开口才放得下脚和头两格又都在这一列的顶盖下面。
        private void findMouthCells(int x, int z, int top) {
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] step : sides) {
                int nx = x + step[0];
                int nz = z + step[1];
                int lower = surfaceAt(nx, nz);
                if (lower == UNLOADED || lower > top - 3) continue;
                int from = Math.max(lower, center.getY() - HEIGHT);
                int to = Math.min(top - 3, center.getY() + HEIGHT);
                for (int y = from; y <= to; y++) {
                    if (ground.roomy(x, y, z) && ground.roomy(x, y + 1, z) && ground.roomy(nx, y, nz)) {
                        mouthCells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }

        // 沟：东西或南北两侧 8 格内的最高地表都比这一列高 10 格以上。
        private void noteSlot(int x, int z, int top) {
            int depth = Math.max(sideRise(x, z, top, 1, 0), sideRise(x, z, top, 0, 1));
            if (depth >= RAVINE_DEPTH) {
                slotDepth.put(columnKey(x, z), depth);
            }
        }

        // 沿一个方向看两侧：两边各取最高的地表，较矮的那边比这一列高几格；有一边没加载就不算。
        private int sideRise(int x, int z, int top, int stepX, int stepZ) {
            int highA = UNLOADED;
            int highB = UNLOADED;
            for (int i = 1; i <= RAVINE_SIDE; i++) {
                int a = surfaceAt(x + stepX * i, z + stepZ * i);
                int b = surfaceAt(x - stepX * i, z - stepZ * i);
                if (a == UNLOADED || b == UNLOADED) return 0;
                highA = Math.max(highA, a);
                highB = Math.max(highB, b);
            }
            return Math.min(highA, highB) - top;
        }

        // 一刻判几处洞口：往里量暗处、够暗才看视线、找能站的那一格；量的格数按预算分刻。
        private void judgeMouths() {
            int budget = FLOOD_CELLS_PER_TICK;
            while (budget > 0 && groupCursor < mouthGroups.size()) {
                List<BlockPos> cells = mouthGroups.get(groupCursor++);
                DarkCount dark = measureDark(cells);
                budget -= dark.visited();
                if (dark.cells() < DARK_CELLS) continue;
                List<BlockPos> nearFirst = new ArrayList<>(cells);
                nearFirst.sort(Comparator.comparingDouble(cell -> cell.distSqr(center)));
                if (!anyVisible(nearFirst)) continue;
                BlockPos stand = firstStandable(nearFirst);
                if (stand == null) continue;
                found.add(new Opening(Kind.CAVE_MOUTH, stand, dark.below() * 2 > dark.cells(), 0, 0,
                        minX(cells), minZ(cells), maxX(cells), maxZ(cells)));
            }
        }

        // 从这处的开口格往里量：只走头顶有遮挡的能进人的空格，数天空光不到 8 的暗处，顺带数在开口以下的。
        private DarkCount measureDark(List<BlockPos> cells) {
            int mouthY = cells.stream().mapToInt(BlockPos::getY).min().orElse(center.getY());
            Deque<BlockPos> pending = new ArrayDeque<>(cells);
            Set<BlockPos> seen = new HashSet<>(cells);
            int dark = 0;
            int below = 0;
            while (!pending.isEmpty() && seen.size() < MAX_FLOOD) {
                BlockPos cell = pending.poll();
                if (ground.skyLight(cell.getX(), cell.getY(), cell.getZ()) < DARK_SKY_LIGHT) {
                    dark++;
                    if (cell.getY() < mouthY - DOWNWARD) below++;
                }
                for (BlockPos next : List.of(cell.north(), cell.south(), cell.east(), cell.west(),
                        cell.above(), cell.below())) {
                    if (seen.contains(next) || !ground.loaded(next.getX(), next.getZ())) continue;
                    if (!ground.roomy(next.getX(), next.getY(), next.getZ())) continue;
                    if (ground.skyLight(next.getX(), next.getY(), next.getZ()) >= OPEN_SKY_LIGHT) continue;
                    seen.add(next);
                    pending.add(next);
                }
            }
            return new DarkCount(dark, below, seen.size());
        }

        /** 往里量的结果：暗处几格、其中在开口以下的几格、一共量了几格。 */
        private record DarkCount(int cells, int below, int visited) {}

        // 峡谷：沟里的列四邻相连成片，最长那一向够长、沟底或沟里看得见、有能站的沟底，才报一处。
        private void judgeRavines() {
            List<BlockPos> columns = new ArrayList<>();
            for (long key : slotDepth.keySet()) {
                columns.add(BlockPos.of(key));
            }
            for (List<BlockPos> slot : connected(columns)) {
                int length = Math.max(maxX(slot) - minX(slot), maxZ(slot) - minZ(slot)) + 1;
                if (length < RAVINE_LENGTH) continue;
                slot.sort(Comparator.comparingDouble(column -> horizontalDistSqr(column)));
                int depth = (int) Math.round(slot.stream()
                        .mapToInt(column -> slotDepth.get(columnKey(column.getX(), column.getZ()))).average()
                        .orElse(0));
                List<BlockPos> floors = new ArrayList<>(slot.size());
                for (BlockPos column : slot) {
                    floors.add(new BlockPos(column.getX(), surfaceAt(column.getX(), column.getZ()), column.getZ()));
                }
                if (!ravineVisible(floors, depth)) continue;
                BlockPos stand = firstStandable(floors);
                if (stand == null) continue;
                found.add(new Opening(Kind.RAVINE, stand, false, length, depth,
                        minX(slot), minZ(slot), maxX(slot), maxZ(slot)));
            }
        }

        // 沟底看得见，或沟里半深处的空气看得见（站在沟沿往下看，沟底常被沟壁挡住一半）。
        private boolean ravineVisible(List<BlockPos> floors, int depth) {
            int checks = Math.min(SIGHT_CHECKS, floors.size());
            for (int i = 0; i < checks; i++) {
                BlockPos floor = floors.get(i);
                if (ground.visible(floor.getX(), floor.getY(), floor.getZ())
                        || ground.visible(floor.getX(), floor.getY() + depth / 2, floor.getZ())) {
                    return true;
                }
            }
            return false;
        }

        private boolean anyVisible(List<BlockPos> nearFirst) {
            int checks = Math.min(SIGHT_CHECKS, nearFirst.size());
            for (int i = 0; i < checks; i++) {
                BlockPos cell = nearFirst.get(i);
                if (ground.visible(cell.getX(), cell.getY(), cell.getZ())) return true;
            }
            return false;
        }

        private BlockPos firstStandable(List<BlockPos> nearFirst) {
            for (BlockPos cell : nearFirst) {
                if (ground.standable(cell.getX(), cell.getY(), cell.getZ())) return cell;
            }
            return null;
        }

        private int surfaceAt(int x, int z) {
            int dx = x - center.getX() + span;
            int dz = z - center.getZ() + span;
            if (dx < 0 || dz < 0 || dx >= side || dz >= side) return UNLOADED;
            return surface[dz * side + dx];
        }

        private double horizontalDistSqr(BlockPos column) {
            double dx = column.getX() - center.getX();
            double dz = column.getZ() - center.getZ();
            return dx * dx + dz * dz;
        }
    }

    // 开口格按相隔几格以内聚成一处：同一个洞口的两三格开口、隔着一根石柱的两半，都是一处。
    static List<List<BlockPos>> group(List<BlockPos> cells) {
        Map<Long, List<BlockPos>> buckets = new HashMap<>();
        for (BlockPos cell : cells) {
            buckets.computeIfAbsent(bucketKey(cell), key -> new ArrayList<>()).add(cell);
        }
        Set<BlockPos> taken = new HashSet<>();
        List<List<BlockPos>> groups = new ArrayList<>();
        for (BlockPos start : cells) {
            if (!taken.add(start)) continue;
            List<BlockPos> members = new ArrayList<>();
            Deque<BlockPos> pending = new ArrayDeque<>(List.of(start));
            while (!pending.isEmpty()) {
                BlockPos cell = pending.poll();
                members.add(cell);
                for (BlockPos near : nearbyInBuckets(buckets, cell)) {
                    if (taken.add(near)) pending.add(near);
                }
            }
            groups.add(members);
        }
        return groups;
    }

    // 相邻几只桶里离这一格不超过合并距离的开口格。
    private static List<BlockPos> nearbyInBuckets(Map<Long, List<BlockPos>> buckets, BlockPos cell) {
        List<BlockPos> near = new ArrayList<>();
        int bx = Math.floorDiv(cell.getX(), MERGE);
        int by = Math.floorDiv(cell.getY(), MERGE);
        int bz = Math.floorDiv(cell.getZ(), MERGE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    List<BlockPos> bucket = buckets.get(BlockPos.asLong(bx + dx, by + dy, bz + dz));
                    if (bucket == null) continue;
                    for (BlockPos other : bucket) {
                        if (Math.abs(other.getX() - cell.getX()) <= MERGE && Math.abs(other.getY() - cell.getY()) <= MERGE
                                && Math.abs(other.getZ() - cell.getZ()) <= MERGE) {
                            near.add(other);
                        }
                    }
                }
            }
        }
        return near;
    }

    // 沟里的列四邻相连成片：对角相接不算连。
    static List<List<BlockPos>> connected(List<BlockPos> columns) {
        Set<Long> left = new HashSet<>();
        for (BlockPos column : columns) left.add(columnKey(column.getX(), column.getZ()));
        List<List<BlockPos>> parts = new ArrayList<>();
        for (BlockPos start : columns) {
            if (!left.remove(columnKey(start.getX(), start.getZ()))) continue;
            List<BlockPos> part = new ArrayList<>();
            Deque<BlockPos> pending = new ArrayDeque<>(List.of(start));
            while (!pending.isEmpty()) {
                BlockPos column = pending.poll();
                part.add(column);
                int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int[] step : steps) {
                    int nx = column.getX() + step[0];
                    int nz = column.getZ() + step[1];
                    if (left.remove(columnKey(nx, nz))) pending.add(new BlockPos(nx, 0, nz));
                }
            }
            parts.add(part);
        }
        return parts;
    }

    private static long bucketKey(BlockPos cell) {
        return BlockPos.asLong(Math.floorDiv(cell.getX(), MERGE), Math.floorDiv(cell.getY(), MERGE),
                Math.floorDiv(cell.getZ(), MERGE));
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static int minX(List<BlockPos> cells) {
        return cells.stream().mapToInt(BlockPos::getX).min().orElse(0);
    }

    private static int maxX(List<BlockPos> cells) {
        return cells.stream().mapToInt(BlockPos::getX).max().orElse(0);
    }

    private static int minZ(List<BlockPos> cells) {
        return cells.stream().mapToInt(BlockPos::getZ).min().orElse(0);
    }

    private static int maxZ(List<BlockPos> cells) {
        return cells.stream().mapToInt(BlockPos::getZ).max().orElse(0);
    }
}
