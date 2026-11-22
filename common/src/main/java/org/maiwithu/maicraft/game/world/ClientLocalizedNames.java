// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * 从客户端内置注册表与当前语言读物品、方块与实体类型本地化名称的只读实现。
 *
 * <p>仅读取启动冻结的内置注册表与原版语言组件，不触碰世界状态或玩家对象，线程安全且零硬编码模组表。
 */
public final class ClientLocalizedNames implements ReadsLocalizedNames {

    private ClientLocalizedNames() {}

    /** 创建只读内置注册表的本地化名称读端。 */
    public static ClientLocalizedNames builtIn() {
        return new ClientLocalizedNames();
    }

    @Override
    public Optional<String> labelOf(IdKind kind, String id) {
        if (kind == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        ResourceLocation key = ResourceLocation.tryParse(id.trim().toLowerCase(Locale.ROOT));
        if (key == null) {
            return Optional.empty();
        }
        return switch (kind) {
            case ITEM -> BuiltInRegistries.ITEM.getOptional(key)
                    .filter(item -> item != Items.AIR)
                    .flatMap(item -> validLabel(key.toString(), item.getDescriptionId(),
                            item.getDescription().getString()));
            case BLOCK -> BuiltInRegistries.BLOCK.getOptional(key)
                    .filter(block -> !isAirBlock(block))
                    .flatMap(block -> validLabel(key.toString(), block.getDescriptionId(),
                            block.getName().getString()));
            case ENTITY_TYPE -> BuiltInRegistries.ENTITY_TYPE.getOptional(key)
                    .flatMap(entity -> validLabel(key.toString(), entity.getDescriptionId(),
                            entity.getDescription().getString()));
            default -> Optional.empty();
        };
    }

    @Override
    public List<NamedId> entries(IdKind kind) {
        if (kind == null) {
            return List.of();
        }
        List<NamedId> result = new ArrayList<>();
        switch (kind) {
            case ITEM -> collectItems(result);
            case BLOCK -> collectBlocks(result);
            case ENTITY_TYPE -> collectEntities(result);
            default -> {
                return List.of();
            }
        }
        result.sort(Comparator.comparing(NamedId::id));
        return List.copyOf(result);
    }

    private static void collectItems(List<NamedId> result) {
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            validLabel(id, item.getDescriptionId(), item.getDescription().getString())
                    .ifPresent(label -> result.add(new NamedId(id, label)));
        }
    }

    private static void collectBlocks(List<NamedId> result) {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (isAirBlock(block)) continue;
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            validLabel(id, block.getDescriptionId(), block.getName().getString())
                    .ifPresent(label -> result.add(new NamedId(id, label)));
        }
    }

    private static void collectEntities(List<NamedId> result) {
        for (EntityType<?> entity : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity).toString();
            validLabel(id, entity.getDescriptionId(), entity.getDescription().getString())
                    .ifPresent(label -> result.add(new NamedId(id, label)));
        }
    }

    private static boolean isAirBlock(Block block) {
        return block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR;
    }

    // 过滤空白、未命中语言文件而原样落回翻译键或注册 ID 的无效标签，避免词表出现无意义的原始键名。
    private static Optional<String> validLabel(String id, String descriptionId, String rawLabel) {
        if (rawLabel == null) {
            return Optional.empty();
        }
        String label = rawLabel.strip();
        if (label.isEmpty() || label.equals(descriptionId) || label.equalsIgnoreCase(id)) {
            return Optional.empty();
        }
        return Optional.of(label);
    }
}
