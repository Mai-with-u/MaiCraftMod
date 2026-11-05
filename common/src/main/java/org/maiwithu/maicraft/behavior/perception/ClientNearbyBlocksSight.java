// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

import org.maiwithu.maicraft.behavior.survival.beds.BedCandidate;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.game.world.ScanTargets;
import org.maiwithu.maicraft.game.world.ObservationVisibility;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 附近功能方块的读端：在已登记的方块扫描上查角色附近看得见的设施方块。
 *
 * <p>复用游戏接口层的 {@code BlockScanService}：设施的种类（容器、工作设施、床）首次用到时
 * 向扫描服务登记一次，之后每次查询走索引，一刻只花小预算；扫完之前返回已找到的部分，
 * "没扫完"不冒充"没有"。查到的每一格再用与观察同一套遮挡判断筛一遍——
 * 墙后的箱子不报成看见。床占两格、命中两次，折到床头那格只报一张。
 * 索引登记的方块种类以设施分类为限，感知只认设施。
 *
 * <p>告示牌另查一份，不和箱子抢名额：注册表里凡是原版告示牌方块的（立牌、挂墙、悬挂、挂墙悬挂四种，
 * 模组继承它们的也算）都登记，看得见的再读它的方块实体上客户端收到的两面文字。
 */
public final class ClientNearbyBlocksSight implements NearbyBlocksSight {

    private static final Logger LOG = LoggerFactory.getLogger(ClientNearbyBlocksSight.class);

    // 观察要的是"附近有什么"，不是最近的一个：给够数量，超出由场景按远近取舍。
    private static final int WANT = 32;
    private static final int MAX_CHUNK_RADIUS = 2;
    private static final int BUILD_BUDGET = 4096;
    // 设施感知每刻查一遍，逐刻全记会把日志刷满：查够这么多遍才记一行完整口径。
    private static final int REPORT_EVERY_QUERIES = 600;

    private final BlockScanService scans;
    private final Supplier<PlayerContext> context;
    private final FacilityKinds kinds;
    /** 设施种类与这次要看的方块按维度登记进扫描索引：没登记的方块索引里查不到。 */
    private final ScanTargets registered;
    /** 感知常驻关心的设施种类（容器、工作站、床）；第一次用到时按标签与注册表找齐。 */
    private Set<Block> facilities;
    /** 告示牌方块：第一次用到时从注册表找齐。 */
    private Set<Block> signBlocks;
    /** 客户端开着文字过滤：开着时牌子上显示的是过滤后的字，读的也是它。 */
    private final BooleanSupplier textFiltering;
    /** 已经查过多少遍：够数了记一行扫描口径，让事后看得清设施这一轮查到了什么。 */
    private int queriesSinceReport;
    /** 上一遍有没有扫完：扫完与否变了就记一行，"没扫完"和"真没有"必须分得开。 */
    private boolean lastQueryComplete = true;
    /** 类型认不得的方块只记一次，不每刻重复。 */
    private boolean warnedUnknownTypes;

    /**
     * @param textFiltering 客户端此刻开没开文字过滤；读告示牌时按它取画面上显示的那一份字
     */
    public ClientNearbyBlocksSight(BlockScanService scans, Supplier<PlayerContext> context, FacilityKinds kinds,
            BooleanSupplier textFiltering) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.context = Objects.requireNonNull(context, "context");
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.textFiltering = Objects.requireNonNull(textFiltering, "textFiltering");
        this.registered = new ScanTargets(scans);
    }

    @Override
    public List<BlockSighting> nearby(Set<String> blockTypes) {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null
                || blockTypes.isEmpty()) {
            return List.of();
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        Collection<Block> targets = blocksOf(blockTypes);
        if (targets.isEmpty()) {
            if (!warnedUnknownTypes) {
                warnedUnknownTypes = true;
                LOG.warn("设施感知认不得要查的方块类型，这一遍什么都查不了：{}", blockTypes);
            }
            return List.of();
        }
        // 设施种类常驻登记；这次要看的方块不在其中时一并补登记，否则索引里查不到。
        registered.ensure(level, facilities());
        registered.ensure(level, targets);
        var result = scans.query(level, player.blockPosition(), targets,
                WANT, MAX_CHUNK_RADIUS, BUILD_BUDGET);
        SeenFacilities seen = seenFacilitiesWithCounts(result.hits(),
                p -> ObservationVisibility.block(player, p), level::getBlockState);
        reportScan(result.complete(), result.hits().size(), seen);
        return seen.sightings();
    }

    /**
     * 记下设施这一遍查到了什么：扫到几格、报出几处、视线挡掉几格、床折格去掉几处、扫完没有。
     *
     * <p>这一层每刻都查一遍，逐刻全记会把日志刷满，所以完整的一遍每查够 {@link #REPORT_EVERY_QUERIES}
     * 遍才记一行；没扫完的那一遍另外记一行——"没扫完"和"真没有"分不开，事后就说不清床为什么没报出来。
     */
    private void reportScan(boolean complete, int hits, SeenFacilities seen) {
        if (!complete && lastQueryComplete) {
            LOG.info("设施这一遍还没扫完：扫到 {} 格，报出 {} 处；没扫完的部分不能当成没有",
                    hits, seen.sightings().size());
        }
        lastQueryComplete = complete;
        queriesSinceReport++;
        if (queriesSinceReport < REPORT_EVERY_QUERIES) return;
        queriesSinceReport = 0;
        LOG.info("设施这一遍：扫到 {} 格，报出 {} 处，视线挡掉 {} 格，床折格去掉 {} 处，扫完={}",
                hits, seen.sightings().size(), seen.blocked(), seen.duplicates(), complete);
    }

    /**
     * 设施这一遍查完的账：报出去的、视线挡掉的、床折格去掉的各是多少。
     *
     * @param sightings 看得见、也没被折格去掉的设施
     * @param blocked 被视线挡住的命中格数
     * @param duplicates 同一张床命中两次时去掉的那一次
     */
    record SeenFacilities(List<BlockSighting> sightings, int blocked, int duplicates) {
    }

    /**
     * 扫描命中变看得见的设施清单：视线被挡的不报；床占两格、扫描命中两次，与睡觉同用折格判断
     * 折到床头那格，同一张床只报一条，观察与记忆里不把一张床数成两张。可见性按命中格判——
     * 只看得见床尾也算看见了这张床，报的位置仍是折后的床头格。
     */
    static List<BlockSighting> seenFacilities(Collection<BlockPos> hits, Predicate<BlockPos> visible,
            Function<BlockPos, BlockState> stateAt) {
        return seenFacilitiesWithCounts(hits, visible, stateAt).sightings();
    }

    /**
     * 同上，另外把被挡掉与被折格去掉的算出来：这几样决定"为什么这处设施没报出来"。
     */
    static SeenFacilities seenFacilitiesWithCounts(Collection<BlockPos> hits, Predicate<BlockPos> visible,
            Function<BlockPos, BlockState> stateAt) {
        List<BlockSighting> sightings = new ArrayList<>();
        Set<BlockPos> seenBeds = new HashSet<>();
        int blocked = 0;
        int duplicates = 0;
        for (BlockPos hit : hits) {
            if (!visible.test(hit)) {
                blocked++;
                continue;
            }
            BlockState state = stateAt.apply(hit);
            BlockPos cell = hit;
            if (state.getBlock() instanceof BedBlock) {
                // 床头在床头那格：朝床头的邻居读一下状态，折格判断自己认床头在不在。
                BlockState stateTowardHead = stateAt.apply(hit.relative(state.getValue(BedBlock.FACING)));
                cell = BedCandidate.clickCell(hit, state, stateTowardHead);
                if (!seenBeds.add(cell)) {
                    duplicates++;
                    continue;
                }
            }
            String typeId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            sightings.add(new BlockSighting(
                    WorldPosition.here(cell.getX(), cell.getY(), cell.getZ()), typeId));
        }
        return new SeenFacilities(sightings, blocked, duplicates);
    }

    @Override
    public List<SignSighting> signs() {
        PlayerContext current = context.get();
        if (current == null || current.localPlayer() == null || current.level() == null) {
            return List.of();
        }
        LocalPlayer player = current.localPlayer();
        ClientLevel level = current.level();
        Set<Block> targets = signBlocks();
        if (targets.isEmpty()) {
            return List.of();
        }
        // 告示牌单独一份查询：主播基地里的箱子再多，也挤不掉看得见的路牌。
        registered.ensure(level, targets);
        var result = scans.query(level, player.blockPosition(), targets, WANT, MAX_CHUNK_RADIUS, BUILD_BUDGET);
        List<SignSighting> sightings = new ArrayList<>();
        for (BlockPos hit : result.hits()) {
            // 墙后的牌子看不见上面的字，也不报成看见。
            if (!ObservationVisibility.block(player, hit)) {
                continue;
            }
            String typeId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit).getBlock()).toString();
            sightings.add(new SignSighting(WorldPosition.here(hit.getX(), hit.getY(), hit.getZ()), typeId,
                    linesAt(level, hit, textFiltering.getAsBoolean())));
        }
        return sightings;
    }

    // 牌子上写的字以客户端收到的方块实体为准：模组的告示牌只要方块实体是原版告示牌那一类就照读；
    // 方块实体还没同步过来时不知道写了什么，交空，不冒充一块空牌子。
    private static SignLines linesAt(ClientLevel level, BlockPos at, boolean filtered) {
        if (!(level.getBlockEntity(at) instanceof SignBlockEntity sign)) {
            return null;
        }
        return new SignLines(linesOf(sign.getFrontText(), filtered), linesOf(sign.getBackText(), filtered));
    }

    // 一面的四行字，按牌子上从上到下；客户端开着文字过滤就读过滤后的那一份，和画面上显示的一致。
    private static List<String> linesOf(SignText side, boolean filtered) {
        List<String> lines = new ArrayList<>();
        for (Component line : side.getMessages(filtered)) {
            lines.add(line.getString());
        }
        return lines;
    }

    // 告示牌方块：注册表里凡是原版告示牌方块一类的都算，第一次用到时找齐，之后复用。
    private Set<Block> signBlocks() {
        if (this.signBlocks == null) {
            Set<Block> signs = new LinkedHashSet<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (block instanceof SignBlock) {
                    signs.add(block);
                }
            }
            this.signBlocks = Set.copyOf(signs);
        }
        return this.signBlocks;
    }

    // 感知常驻关心的设施方块：容器、工作设施与床，种类由设施分类给齐（床在注册表里找），第一次用到时换成方块对象复用。
    private Set<Block> facilities() {
        if (this.facilities != null) {
            return this.facilities;
        }
        Set<Block> facilities = new LinkedHashSet<>();
        for (String typeId : kinds.facilityBlockTypes()) {
            blockOf(typeId).ifPresent(facilities::add);
        }
        this.facilities = Set.copyOf(facilities);
        LOG.info("设施感知认了 {} 种方块：{}", facilities.size(), kinds.facilityBlockTypes());
        return this.facilities;
    }

    // 调用方给的是方块类型注册 ID 字符串；认得的换成方块对象，认不得的忽略。
    private static Collection<Block> blocksOf(Set<String> blockTypes) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String typeId : blockTypes) {
            blockOf(typeId).ifPresent(blocks::add);
        }
        return blocks;
    }

    private static Optional<Block> blockOf(String typeId) {
        return Optional.ofNullable(
                BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(typeId.toLowerCase(Locale.ROOT))));
    }
}
