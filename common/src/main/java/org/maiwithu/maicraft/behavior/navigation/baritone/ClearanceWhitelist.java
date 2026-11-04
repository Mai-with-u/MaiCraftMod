// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.maiwithu.maicraft.behavior.acquire.mining.NaturalBlocks;
import org.maiwithu.maicraft.behavior.acquire.mining.NaturalTrees;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 寻路挖路与施工清场共用的"路上能挖开哪些方块"：天然地形、自然长的树叶、长在树上的原木、野生草木
 * （按标签和方块类别认，见 {@link NaturalBlocks#wildAt}），再加上这个实例在配置文件里额外列的方块或标签。
 * 不依赖服务端安装本模组。替换许可、流体和保护格仍由各执行段另行核验。
 */
public final class ClearanceWhitelist {
    private static final Logger LOG = LoggerFactory.getLogger(ClearanceWhitelist.class);
    public static final String CONFIG = "config/maicraft-clearance.json";
    /**
     * 旧版自动写进配置文件的默认名单。读配置时这些条目不再起作用：地形和草木改按标签、类别认；
     * 火把、黑曜石、南瓜、西瓜、睡莲、苔藓地毯不再默认挖开。玩家自己加的条目照旧有效。
     * 这份表冻结，以后不增不删：它只用来认出旧版自动写进文件的条目，不决定路上挖不挖。
     * 哪些方块能挖开按 {@link NaturalBlocks} 的标签与类别认，不往这里加方块 ID。
     */
    private static final Set<ResourceLocation> LEGACY_DEFAULTS = Arrays.stream(("""
            stone granite diorite andesite deepslate tuff calcite dripstone_block pointed_dripstone
            dirt grass_block coarse_dirt podzol mycelium rooted_dirt mud muddy_mangrove_roots clay
            sand red_sand gravel sandstone red_sandstone terracotta white_terracotta orange_terracotta
            yellow_terracotta brown_terracotta red_terracotta light_gray_terracotta
            netherrack soul_sand soul_soil basalt smooth_basalt blackstone end_stone obsidian
            coal_ore iron_ore copper_ore gold_ore redstone_ore emerald_ore lapis_ore diamond_ore
            deepslate_coal_ore deepslate_iron_ore deepslate_copper_ore deepslate_gold_ore
            deepslate_redstone_ore deepslate_emerald_ore deepslate_lapis_ore deepslate_diamond_ore
            nether_gold_ore nether_quartz_ore ancient_debris raw_iron_block raw_copper_block
            snow snow_block ice packed_ice blue_ice powder_snow moss_block moss_carpet
            oak_log spruce_log birch_log jungle_log acacia_log dark_oak_log mangrove_log cherry_log
            oak_leaves spruce_leaves birch_leaves jungle_leaves acacia_leaves dark_oak_leaves
            mangrove_leaves cherry_leaves azalea_leaves flowering_azalea_leaves mangrove_roots
            crimson_stem warped_stem nether_wart_block warped_wart_block shroomlight
            crimson_nylium warped_nylium brown_mushroom_block red_mushroom_block mushroom_stem
            short_grass tall_grass fern large_fern dead_bush vine glow_lichen hanging_roots
            azalea flowering_azalea small_dripleaf big_dripleaf big_dripleaf_stem spore_blossom
            cave_vines cave_vines_plant weeping_vines weeping_vines_plant twisting_vines twisting_vines_plant
            crimson_roots warped_roots nether_sprouts crimson_fungus warped_fungus
            dandelion poppy blue_orchid allium azure_bluet red_tulip orange_tulip white_tulip pink_tulip
            oxeye_daisy cornflower lily_of_the_valley sunflower lilac rose_bush peony pink_petals
            brown_mushroom red_mushroom sugar_cane cactus bamboo bamboo_sapling sweet_berry_bush
            pumpkin melon lily_pad seagrass tall_seagrass kelp kelp_plant chorus_plant chorus_flower
            torch wall_torch
            """).trim().split("\\s+")).map(name -> ResourceLocation.withDefaultNamespace(name))
            .collect(Collectors.toUnmodifiableSet());
    private static final AtomicReference<Rules> CURRENT = new AtomicReference<>(Rules.NONE_EXTRA);

    private ClearanceWhitelist() {}

    /**
     * 客户端启动时读取本实例额外列的方块：文件不存在就写一个空表；旧版写进去的默认条目不再起作用，文件不删不改。
     * 读不出来（手滑写错一个逗号）按没有额外条目处理：天然方块照常挖开，不比空表还严，日志写明哪个文件、哪里错了。
     * 不想让角色在路上动土用 change_blocks=none。
     */
    public static void initialize(Path gameDirectory) {
        Path file = gameDirectory.resolve(CONFIG);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "[]\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
            Rules rules = read(Files.readString(file, StandardCharsets.UTF_8));
            if (rules.legacyIgnored() > 0) {
                LOG.info("[clearance] {} 里有 {} 条旧版默认条目不再起作用：地形和草木改按标签与类别认，"
                        + "火把、黑曜石、南瓜这些不再默认挖开", file, rules.legacyIgnored());
            }
            CURRENT.set(rules);
        } catch (IOException | RuntimeException invalid) {
            CURRENT.set(Rules.NONE_EXTRA);
            LOG.warn("[clearance] {} 读不出来（{}），按没有额外条目处理：天然方块照常挖开，文件里列的不生效",
                    file, invalid.getMessage());
        }
    }

    /**
     * 只看方块种类，这一格能不能挖开：已经是空气就无需清障；地形、野生草木、树叶和原木这几类（原木和树叶还要看
     * 长没长在树上，见 {@link #allowsAt}），再加上实例额外列的。
     */
    public static boolean allows(BlockState state) {
        if (state.isAir()) return true;
        return NaturalBlocks.terrain(state) || NaturalBlocks.wildPlant(state)
                || state.getBlock() instanceof LeavesBlock || NaturalTrees.isLog(state) || CURRENT.get().extra(state);
    }

    /**
     * 这一格能不能当地形清掉：天然的按 {@link NaturalBlocks#wildAt} 认（原木得长在天然树上、树叶得是自然长的、
     * 田里种的不算），再加上实例额外列的方块。
     * 实机里角色困在村庄木屋里，只认类型的名单把原木地板挖了个坑、又把原木屋顶拆开钻出去。
     *
     * @param world  读这一格周围的方块（算路在后台线程上读它自己那份）
     * @param loaded 哪些格读得到
     * @param trunks 一次算路里查过的树干（键是树干顶），同一棵树不重复数叶子
     */
    public static boolean allowsAt(BlockState state, BlockPos pos, BlockGetter world, Predicate<BlockPos> loaded,
            Map<BlockPos, Boolean> trunks) {
        if (state.isAir()) return true;
        if (NaturalBlocks.wildAt(state, pos, world, loaded, trunks)) return true;
        // 实例额外列了黑曜石也不拆门框：挨着传送门的是门的一部分，拆了门就没了。
        // 实机 10-14 进门失败后为了自己建门去挖黑曜石，把旁边那扇亮着的门框整个拆了。
        if (state.is(Blocks.OBSIDIAN) && NaturalBlocks.touchesPortal(pos, world, loaded)) return false;
        return CURRENT.get().extra(state);
    }

    // 读配置：一个字符串数组，每条是方块 ID 或 #方块标签；不凭名称包含 stone、ore 等文字猜测模组机器是否属于自然地形。
    // 旧版自动写进去的默认条目跳过不算，数一下跳过了几条。
    static Rules read(String json) {
        var parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("expected an array of block IDs or #block_tags");
        Set<ResourceLocation> blocks = new LinkedHashSet<>();
        Set<TagKey<Block>> tags = new LinkedHashSet<>();
        int legacy = 0;
        for (var value : parsed.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("clearance entries must be strings");
            String text = value.getAsString().trim();
            boolean tag = text.startsWith("#");
            ResourceLocation id = ResourceLocation.tryParse(tag ? text.substring(1) : text);
            if (id == null) throw new IllegalArgumentException("invalid clearance entry: " + text);
            if (tag) {
                tags.add(TagKey.create(Registries.BLOCK, id));
            } else if (LEGACY_DEFAULTS.contains(id)) {
                legacy++;
            } else {
                blocks.add(id);
            }
        }
        return new Rules(Set.copyOf(blocks), Set.copyOf(tags), legacy);
    }

    /**
     * 实例的清障配置：额外能挖的方块和标签、旧版默认条目跳过了几条。
     */
    record Rules(Set<ResourceLocation> blocks, Set<TagKey<Block>> tags, int legacyIgnored) {
        static final Rules NONE_EXTRA = new Rules(Set.of(), Set.of(), 0);

        /** 这个方块是不是实例额外列出来能挖的。 */
        boolean extra(BlockState state) {
            return blocks.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock())) || tags.stream().anyMatch(state::is);
        }
    }
}
