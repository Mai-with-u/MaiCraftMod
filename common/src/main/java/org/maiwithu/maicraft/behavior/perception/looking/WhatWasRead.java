// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 刚读了什么：读信息的路径（observe、lookup、只读能力出报告、目标做完）交给闲时看的一句话。
 *
 * <p>只说读了什么，不说怎么看：开不开背包、看向哪里、停多久都由闲时看定，全仓一份。
 */
public sealed interface WhatWasRead {

    /** observe(self)：看了看自己。 */
    record Self() implements WhatWasRead {}

    /** observe(scene)：看了看周围。 */
    record Surroundings() implements WhatWasRead {}

    /** observe(detail)：看了某个观察编号（e#、b#、f#、s#）或记住的地标名。 */
    record Seen(String id) implements WhatWasRead {
        public Seen {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * 结果指着世界里的这几处：find 找到的、machine_inspect 看的机器、read_site 读的那一栋。
     *
     * @param sweep 真：从左到右每处看一眼；假：盯着第一处看一会儿
     */
    record Places(List<WorldPosition> places, boolean sweep) implements WhatWasRead {
        public Places {
            places = List.copyOf(places);
        }
    }

    /** lookup(topic=recipe, id)：查了这样东西的配方（uses 为真时查的是用途）。 */
    record RecipePage(String itemId, boolean uses) implements WhatWasRead {
        public RecipePage {
            Objects.requireNonNull(itemId, "itemId");
        }
    }

    /** 看了自己。 */
    static WhatWasRead self() {
        return new Self();
    }

    /** 看了周围。 */
    static WhatWasRead surroundings() {
        return new Surroundings();
    }

    /** 看了这个观察编号或地标。 */
    static WhatWasRead seen(String id) {
        return new Seen(id);
    }

    /** 查了这样东西的配方或用途。 */
    static WhatWasRead recipe(String itemId, boolean uses) {
        return new RecipePage(itemId, uses);
    }

    /** 结果指着的地方；没有指着哪里时为 null。 */
    static WhatWasRead pointedAt(PointsAt result) {
        List<WorldPosition> places = result.pointsAt();
        return places.isEmpty() ? null : new Places(places, result.sweep());
    }
}
