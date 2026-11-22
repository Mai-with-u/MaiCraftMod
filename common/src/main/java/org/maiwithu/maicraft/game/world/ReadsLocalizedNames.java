// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 读当前游戏语言里物品、方块、实体类型显示名称与按名称反查注册 ID 的只读接缝。
 *
 * <p>名称实时来自游戏当前语言包，不维护硬编码模组词表；离线测试与默认回退使用 {@link #NONE}。
 */
public interface ReadsLocalizedNames {

    /** 离线或未接入语言读端时的空实现：所有查询均为空，不改写原始入参也不附加模组词表。 */
    ReadsLocalizedNames NONE = new ReadsLocalizedNames() {
        @Override public Optional<String> labelOf(IdKind kind, String id) {
            return Optional.empty();
        }

        @Override public List<NamedId> entries(IdKind kind) {
            return List.of();
        }
    };

    /**
     * 一条注册 ID 与它在当前游戏语言里的显示名称。
     *
     * @param id    标准注册 ID（命名空间:路径）
     * @param label 当前游戏语言下的可读名称（例如中文名）
     */
    record NamedId(String id, String label) {
        public NamedId {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    /** 读指定类别下某一个注册 ID 在当前游戏语言里的显示名称；未注册或无有效翻译时为空。 */
    Optional<String> labelOf(IdKind kind, String id);

    /** 按物品、方块、实体类型的先后查找任意注册 ID 的当前游戏语言名称；均无有效翻译时为空。 */
    default Optional<String> labelOfAny(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Optional<String> item = labelOf(IdKind.ITEM, id);
        if (item.isPresent()) {
            return item;
        }
        Optional<String> block = labelOf(IdKind.BLOCK, id);
        if (block.isPresent()) {
            return block;
        }
        return labelOf(IdKind.ENTITY_TYPE, id);
    }

    /** 列出指定类别下全部有有效当前语言名称的注册项，按注册 ID 排序，供入参名称反查使用。 */
    List<NamedId> entries(IdKind kind);
}
