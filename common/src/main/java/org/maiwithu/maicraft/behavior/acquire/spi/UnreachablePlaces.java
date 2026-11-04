// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 这一次拿东西里走不到跟前的地方：来源去一处（一只箱子）靠近收场为到不了，记下这一处和第一次的经过；
 * 这一次里后面的原料不再拿去问它，只记下问到它的是哪几样东西。失败说明里每一处合成一句
 * "记得的箱子 X 走不到（问了 N 种东西）"，经过只接一次，长度不随原料种数增长。
 * 由引擎的最外层那一场持有，备料的每一层共用；不跨下一次拿东西。
 */
public final class UnreachablePlaces {

    /** 来源跳过了走不到的地方、别的也给不了时的那句：引擎不把它当成一条原因，统一在最外层说一次。 */
    public static final String SKIPPED = "记得的那几只这次走不到跟前，不再去问";

    /** 一处走不到的地方：怎么称呼、第一次为什么走不到、问到过它的东西。 */
    private record Place(String label, String why, Set<String> asked) {}

    private final Map<String, Place> places = new LinkedHashMap<>();

    /** 记下：去 at 拿 asking 时走不到跟前。第一次记经过；再记只多一样问到它的东西。 */
    public void note(WorldPosition at, String label, String asking, String why) {
        places.computeIfAbsent(key(at), unused -> new Place(label, why, new LinkedHashSet<>())).asked().add(asking);
    }

    /** 要 asking 时这一处跳不跳过：记着走不到就跳过，并算它又被问到一次。 */
    public boolean skips(WorldPosition at, String asking) {
        Place place = places.get(key(at));
        if (place == null) return false;
        place.asked().add(asking);
        return true;
    }

    /** 记了几处：引擎比一比一步前后，知道这一步是不是败在走不到跟前。 */
    public int size() {
        return places.size();
    }

    /** 失败说明里的那几句，每处一句。 */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        for (Place place : places.values()) {
            lines.add(place.label() + " 走不到（问了 " + place.asked().size() + " 种东西）：" + place.why());
        }
        return lines;
    }

    // 只按坐标认：同一次拿东西里不会换维度，记忆与现场扫描对维度的写法可能不一样。
    private static String key(WorldPosition at) {
        return at.x() + "," + at.y() + "," + at.z();
    }
}
