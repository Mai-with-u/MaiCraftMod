// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界生成往地上铺的实心方块：开服时读一次这个世界的已配置地物与噪声设置，握手时报给客户端，
 * 客户端把它们和方块标签一起当天然地形认（火山群系的黑沙岩、机械动力按矿脉铺的石头这类不在地形标签里的）。
 *
 * <p>地物按它自己的编解码器转成通用的数据树来读，不按地物类型的 ID 认，也不写方块名单：树里同一层既有
 * 替换目标（target / targets）又有换成的方块（state / state_provider）的，就是"把地里的某种方块换成这种"，
 * 圆盘、矿脉、单格替换、成团替换、模组自定的分层矿脉都是这个样子。树、花、植被、晶洞没有这一层，不收；
 * 村庄、神殿是结构不是地物，切制、錾制的沙岩不会混进来。噪声设置收默认方块与地表规则铺的方块。
 * 只收碰撞箱整格的实心方块，水、熔岩、空气不收。
 */
public final class GeneratedTerrainBlocks {
    private static final Logger LOG = LoggerFactory.getLogger(GeneratedTerrainBlocks.class);

    private GeneratedTerrainBlocks() {}

    /**
     * 读这个世界的世界生成数据，给出往地上铺的实心方块 ID，按字母排好。
     * 某个地物编码失败（模组的编解码器不完整）就跳过它，日志里数一下跳过了几个，不影响别的。
     */
    public static List<String> collect(HolderLookup.Provider registries) {
        RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
        Set<ResourceLocation> found = new TreeSet<>();
        int skipped = 0;
        for (var holder : registries.lookupOrThrow(Registries.CONFIGURED_FEATURE).listElements().toList()) {
            Optional<JsonElement> tree;
            try {
                tree = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, holder.value()).result();
            } catch (RuntimeException broken) {
                tree = Optional.empty();
            }
            if (tree.isEmpty()) {
                skipped++;
                continue;
            }
            found.addAll(replacementsIn(tree.get()));
        }
        for (var holder : registries.lookupOrThrow(Registries.NOISE_SETTINGS).listElements().toList()) {
            NoiseGeneratorSettings noise = holder.value();
            // 维度的默认方块（主世界的石头、下界的下界岩、末地的末地石）是整片地层的底子。
            found.add(BuiltInRegistries.BLOCK.getKey(noise.defaultBlock().getBlock()));
            SurfaceRules.RuleSource.CODEC.encodeStart(ops, noise.surfaceRule()).result()
                    .ifPresent(rules -> found.addAll(surfaceBlocksIn(rules)));
        }
        List<String> solid = found.stream().filter(GeneratedTerrainBlocks::solidGround)
                .map(ResourceLocation::toString).toList();
        LOG.info("世界生成往地上铺的实心方块 {} 种（其中非原版 {} 种），{} 个地物读不出配置跳过", solid.size(),
                solid.stream().filter(id -> !id.startsWith("minecraft:")).count(), skipped);
        return solid;
    }

    /**
     * 地物配置的数据树里"把地里的方块换成什么"：同一层既有替换目标又有换成的方块时，收下换成的方块。
     * 替换目标本身、换成的方块里按条件挑的判断条件不收。
     */
    static Set<ResourceLocation> replacementsIn(JsonElement tree) {
        Set<ResourceLocation> found = new TreeSet<>();
        collectReplacements(tree, found);
        return found;
    }

    /** 地表规则的数据树里铺的方块：每条"铺这种方块"的结果都收。 */
    static Set<ResourceLocation> surfaceBlocksIn(JsonElement rules) {
        Set<ResourceLocation> found = new TreeSet<>();
        collectSurface(rules, found);
        return found;
    }

    // 逐层往下找：这一层既有替换目标又有换成的方块，就收换成的方块；不管收没收，子层照样往下找（矿脉的替换写在 targets 的每一项里）。
    private static void collectReplacements(JsonElement node, Set<ResourceLocation> found) {
        if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(child -> collectReplacements(child, found));
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject object = node.getAsJsonObject();
        if (object.has("target") || object.has("targets")) {
            statesIn(object.get("state"), found);
            statesIn(object.get("state_provider"), found);
        }
        object.entrySet().forEach(entry -> collectReplacements(entry.getValue(), found));
    }

    // 地表规则里"铺这种方块"写在 result_state；条件（高度、群系、噪声）里没有方块，照样往下找嵌套的规则。
    private static void collectSurface(JsonElement node, Set<ResourceLocation> found) {
        if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(child -> collectSurface(child, found));
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject object = node.getAsJsonObject();
        statesIn(object.get("result_state"), found);
        object.entrySet().forEach(entry -> collectSurface(entry.getValue(), found));
    }

    // 换成的方块：单个方块状态写作 {"Name": ...}；方块提供者（随机挑、按噪声挑、按规则挑）里的每个候选都收，
    // 按规则挑时的判断条件（if_true）里提到的方块只是条件，不是铺下去的，不收。
    private static void statesIn(JsonElement node, Set<ResourceLocation> found) {
        if (node == null) return;
        if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(child -> statesIn(child, found));
            return;
        }
        if (!node.isJsonObject()) return;
        JsonObject object = node.getAsJsonObject();
        JsonElement name = object.get("Name");
        if (name != null && name.isJsonPrimitive() && name.getAsJsonPrimitive().isString()) {
            ResourceLocation id = ResourceLocation.tryParse(name.getAsString());
            if (id != null) found.add(id);
            return;
        }
        object.entrySet().stream().filter(entry -> !entry.getKey().equals("if_true"))
                .forEach(entry -> statesIn(entry.getValue(), found));
    }

    /** 能站能挖的实心地面：本机认得这个方块，碰撞箱是整格，不是流体也不是空气。 */
    static boolean solidGround(ResourceLocation id) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty() || block.get() instanceof LiquidBlock) return false;
        BlockState state = block.get().defaultBlockState();
        if (state.isAir() || !state.getFluidState().isEmpty()) return false;
        try {
            return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException needsWorld) {
            // 碰撞箱要读周围世界才算得出的模组方块：拿不准，不收，留给方块标签去认。
            return false;
        }
    }
}
