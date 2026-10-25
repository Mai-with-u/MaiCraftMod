// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.mining;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

/**
 * 原版"挖掉哪种方块掉什么"的常识对照：问一件东西时，先在这里查哪些方块会掉它。
 *
 * <p>客户端读不到方块的掉落表（那是服务端的数据），采掘扫描只靠这一份写死的原版对照
 * 与"同一种方块挖掉掉自己"的通例。覆盖不到的模组方块如实回答"没有方块直接掉出"，
 * 不冒充找遍了。必掉的（石头掉圆石、矿石掉矿物）和概率掉落的（砾石出燧石、草出种子、树叶出树苗）分两份记：
 * 概率掉落按原版战利品表写明每块的机会和时运加成，取物据此按期望放大挖的块数；工具要求由游戏现场说了算。
 */
public final class VanillaBlockDrops {

    /**
     * 一条概率掉落：挖这种方块有一定机会掉这件东西，按原版战利品表的数。
     *
     * @param blockType            方块的注册 ID，例如 minecraft:gravel
     * @param itemId               有机会掉的东西，例如 minecraft:flint
     * @param chanceByFortune      手上工具时运 0、1、2、3……级时每块掉它的机会；时运更高按最后一档
     * @param meanCount            掉了的话平均几件（树叶出木棍一次 1 到 2 根）
     * @param extraCountPerFortune 时运每高一级多出平均几件（草出种子：时运加的是件数，不是机会）
     * @param elseDropsItself      没出它时掉的是方块自己：砾石没出燧石就掉砾石，放下再挖还能接着碰运气
     */
    public record ChanceDrop(String blockType, String itemId, List<Double> chanceByFortune, double meanCount,
            double extraCountPerFortune, boolean elseDropsItself) {

        public ChanceDrop {
            chanceByFortune = List.copyOf(chanceByFortune);
            if (chanceByFortune.isEmpty()) throw new IllegalArgumentException("概率掉落要写机会：" + blockType);
        }

        /** 拿着这么高时运的工具挖一格，掉它的机会与平均件数。 */
        public DropOdds odds(int fortune) {
            int level = Math.max(0, fortune);
            double chance = chanceByFortune.get(Math.min(level, chanceByFortune.size() - 1));
            return new DropOdds(chance, meanCount + extraCountPerFortune * level, false);
        }
    }

    /** 树叶出树苗：原版普通树叶每块 5%，时运每级往上加。 */
    private static final List<Double> SAPLING_CHANCES = List.of(0.05, 0.0625, 0.083333336, 0.1);
    /** 丛林树叶出丛林树苗只有一半：2.5%（丛林树太高大，原版特意压低）。 */
    private static final List<Double> JUNGLE_SAPLING_CHANCES = List.of(0.025, 0.027777778, 0.03125, 0.041666668, 0.1);
    /** 树叶出木棍：每块 2%，一次 1 到 2 根。 */
    private static final List<Double> STICK_CHANCES = List.of(0.02, 0.022222223, 0.025, 0.033333335, 0.1);
    /** 橡木、深色橡木树叶出苹果：每块 0.5%。 */
    private static final List<Double> APPLE_CHANCES = List.of(0.005, 0.0055555557, 0.00625, 0.008333334, 0.025);
    /** 草出小麦种子：每块 12.5%，时运不改机会、改件数（每级平均多一颗）。 */
    private static final List<Double> SEED_CHANCES = List.of(0.125);

    /** 树叶与它掉的树苗；红树树叶不掉树苗（红树胎生苗长在叶子上，挖叶子不掉），只出木棍。 */
    private static final Map<String, String> LEAVES_TO_SAPLING = Map.of(
            "minecraft:oak_leaves", "minecraft:oak_sapling",
            "minecraft:spruce_leaves", "minecraft:spruce_sapling",
            "minecraft:birch_leaves", "minecraft:birch_sapling",
            "minecraft:jungle_leaves", "minecraft:jungle_sapling",
            "minecraft:acacia_leaves", "minecraft:acacia_sapling",
            "minecraft:dark_oak_leaves", "minecraft:dark_oak_sapling",
            "minecraft:cherry_leaves", "minecraft:cherry_sapling",
            "minecraft:azalea_leaves", "minecraft:azalea",
            "minecraft:flowering_azalea_leaves", "minecraft:flowering_azalea");

    /** 概率掉落全表：砾石出燧石，草与蕨出小麦种子，树叶出树苗、木棍、苹果。 */
    private static final List<ChanceDrop> CHANCE_DROPS = chanceDrops();

    private static List<ChanceDrop> chanceDrops() {
        List<ChanceDrop> drops = new ArrayList<>();
        // 砾石：每块 10% 出燧石（时运 I 14%、II 25%、III 必出），没出就掉砾石自己。
        drops.add(new ChanceDrop("minecraft:gravel", "minecraft:flint", List.of(0.1, 0.14285715, 0.25, 1.0), 1, 0, true));
        // 短草、蕨：12.5% 出种子，时运按件数加；高草、大型蕨只有下半截掉，时运不加件数。
        for (String grass : List.of("minecraft:short_grass", "minecraft:fern")) {
            drops.add(new ChanceDrop(grass, "minecraft:wheat_seeds", SEED_CHANCES, 1, 1, false));
        }
        for (String tall : List.of("minecraft:tall_grass", "minecraft:large_fern")) {
            drops.add(new ChanceDrop(tall, "minecraft:wheat_seeds", SEED_CHANCES, 1, 0, false));
        }
        for (var leaves : LEAVES_TO_SAPLING.entrySet()) {
            List<Double> chances = leaves.getKey().equals("minecraft:jungle_leaves") ? JUNGLE_SAPLING_CHANCES
                    : SAPLING_CHANCES;
            drops.add(new ChanceDrop(leaves.getKey(), leaves.getValue(), chances, 1, 0, false));
        }
        List<String> allLeaves = new ArrayList<>(LEAVES_TO_SAPLING.keySet());
        allLeaves.add("minecraft:mangrove_leaves");
        for (String leaves : allLeaves) {
            drops.add(new ChanceDrop(leaves, "minecraft:stick", STICK_CHANCES, 1.5, 0, false));
        }
        for (String leaves : List.of("minecraft:oak_leaves", "minecraft:dark_oak_leaves")) {
            drops.add(new ChanceDrop(leaves, "minecraft:apple", APPLE_CHANCES, 1, 0, false));
        }
        return List.copyOf(drops);
    }

    /** 不会掉自己的方块：挖掉掉的是别的（石头掉圆石这类），按这份对照查。 */
    private static final Map<String, String> BLOCK_TO_DROP = Map.ofEntries(
            Map.entry("minecraft:stone", "minecraft:cobblestone"),
            Map.entry("minecraft:deepslate", "minecraft:cobbled_deepslate"),
            Map.entry("minecraft:grass_block", "minecraft:dirt"),
            Map.entry("minecraft:mycelium", "minecraft:dirt"),
            Map.entry("minecraft:podzol", "minecraft:dirt"),
            Map.entry("minecraft:coal_ore", "minecraft:coal"),
            Map.entry("minecraft:deepslate_coal_ore", "minecraft:coal"),
            Map.entry("minecraft:iron_ore", "minecraft:raw_iron"),
            Map.entry("minecraft:deepslate_iron_ore", "minecraft:raw_iron"),
            Map.entry("minecraft:copper_ore", "minecraft:raw_copper"),
            Map.entry("minecraft:deepslate_copper_ore", "minecraft:raw_copper"),
            Map.entry("minecraft:gold_ore", "minecraft:raw_gold"),
            Map.entry("minecraft:deepslate_gold_ore", "minecraft:raw_gold"),
            Map.entry("minecraft:nether_gold_ore", "minecraft:gold_nugget"),
            Map.entry("minecraft:diamond_ore", "minecraft:diamond"),
            Map.entry("minecraft:deepslate_diamond_ore", "minecraft:diamond"),
            Map.entry("minecraft:emerald_ore", "minecraft:emerald"),
            Map.entry("minecraft:deepslate_emerald_ore", "minecraft:emerald"),
            Map.entry("minecraft:lapis_ore", "minecraft:lapis_lazuli"),
            Map.entry("minecraft:deepslate_lapis_ore", "minecraft:lapis_lazuli"),
            Map.entry("minecraft:redstone_ore", "minecraft:redstone"),
            Map.entry("minecraft:deepslate_redstone_ore", "minecraft:redstone"),
            Map.entry("minecraft:nether_quartz_ore", "minecraft:quartz"),
            Map.entry("minecraft:amethyst_cluster", "minecraft:amethyst_shard"),
            Map.entry("minecraft:glowstone", "minecraft:glowstone_dust"),
            Map.entry("minecraft:clay", "minecraft:clay_ball"));

    /** 名字对得上方块、挖掉却不掉自己的例外：玻璃碎了什么都没有，冰化成水，雪堆掉雪球。 */
    private static final List<String> DROPS_NOTHING = List.of(
            "minecraft:glass", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
            "minecraft:snow", "minecraft:snow_block");

    /**
     * 谁都知道埋在脚下的石头：地表往下挖几格就有，不用先看见。
     * 只有它是例外，矿石（煤、铁、钻石）照旧要看得见才去挖。
     */
    /**
     * 地下常见、往下挖楼梯多半挖得到的石头：石头本身，成团长在石头里的安山岩、闪长岩、花岗岩，再往下的深板岩、凝灰岩。
     * 要安山岩做安山合金时像玩家一样往下挖去找，不只说"看得见的地方没有"（实机主城平台上要 40 块安山岩就卡住）。
     */
    private static final Set<String> BURIED_STONES = Set.of("minecraft:stone", "minecraft:andesite", "minecraft:diorite",
            "minecraft:granite", "minecraft:tuff", "minecraft:deepslate");

    private VanillaBlockDrops() {}

    /** 这件东西是不是埋在脚下的石头掉的（圆石）：看不见石头时也能往下挖楼梯去找。 */
    public static boolean buriedUnderfoot(String itemId) {
        return blocksDropping(itemId).stream().anyMatch(BURIED_STONES::contains);
    }

    /** 挖掉会掉出这件东西的方块类型；对照里没有、也不掉自己的物品给空。 */
    public static List<String> blocksDropping(String itemId) {
        if (DROPS_NOTHING.contains(itemId)) {
            return List.of();
        }
        List<String> blockTypes = new ArrayList<>();
        for (var entry : BLOCK_TO_DROP.entrySet()) {
            if (entry.getValue().equals(itemId)) {
                blockTypes.add(entry.getKey());
            }
        }
        // 掉自己的通例：方块注册里与物品同名的那些（泥土、圆石、原木这类），挖掉掉自己；
        // 对照里已有条目的也一并算上（泥土既掉自泥土也掉自草方块）。没有同名方块的写法
        // 在扫描里解析不到方块，自然落空，不碍事。对照里写明掉别的（石头掉圆石、矿石掉矿物）
        // 的方块不掉自己：要石头不能去挖石头，挖了拿到的是圆石。
        if (!BLOCK_TO_DROP.containsKey(itemId)) {
            blockTypes.add(itemId);
        }
        return List.copyOf(blockTypes);
    }

    /** 挖掉这种方块掉什么；对照里没有的按"掉自己"的通例回答。 */
    public static String droppedBy(String blockTypeId) {
        return BLOCK_TO_DROP.getOrDefault(blockTypeId, blockTypeId);
    }

    /** 挖了有机会掉出这件东西的方块：燧石问砾石，种子问草，树苗问树叶；没有为空。 */
    public static List<ChanceDrop> chanceSources(String itemId) {
        return CHANCE_DROPS.stream().filter(drop -> drop.itemId().equals(itemId)).toList();
    }

    /** 这种方块挖了有机会掉的东西：树叶掉树苗、木棍、苹果；不是概率掉落的方块为空。 */
    public static List<ChanceDrop> chanceDropsOf(String blockTypeId) {
        return CHANCE_DROPS.stream().filter(drop -> drop.blockType().equals(blockTypeId)).toList();
    }
}
