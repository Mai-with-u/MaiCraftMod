// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemTags;

/** 测试替身：物品标签就是一张查表，摆哪个物品挂哪些标签由测试说了算。 */
public final class FakeTags implements ReadsItemTags {
    private final Map<String, Set<String>> table = new HashMap<>();

    public void put(String itemId, String... tags) {
        table.put(itemId, Set.of(tags));
    }

    @Override public Set<String> tagsOf(String itemId) {
        return table.getOrDefault(itemId, Set.of());
    }
}
