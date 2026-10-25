// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 登记表里一种 spi 能力的槽：一个联动入口交上来的建法或现成的东西都收在这一个对象里，
 * 建出来包一层（模组停用、接口对不上时如实答"用不了"）与交接到一半出错时按交接前的长度撤回，
 * 这两套逻辑在这里只写一份，全部槽共用。
 *
 * <p>槽在登记表里是一行字段声明，声明这个动作同时把它放进登记表的槽集合；
 * 没有第二份要手工同步的槽清单，新加一种槽不会漏进回滚集合。
 */
final class Slot<T> {
    private static final Logger LOG = LoggerFactory.getLogger(Slot.class);

    /** 槽里的一件东西：要么是登记时就建好的，要么是进世界才建的建法。 */
    private interface Entry<T> {}

    /** 登记时就建好、已包好一层的。 */
    private record Ready<T>(T value) implements Entry<T> {}

    /** 进世界才建的：带着交它的联动入口，建的时候出错算这个模组的，不连累别的。 */
    private record Deferred<T>(CompatModule module, Function<PlayerServices, T> build) implements Entry<T> {
        Deferred {
            Objects.requireNonNull(module, "module");
            Objects.requireNonNull(build, "build");
        }
    }

    /** 这个槽装的是什么（日志里说"建物品来源"），用 09 的说法。 */
    private final String what;
    /** 建出来后包的一层；不包的槽（随身背包、只读操作声明）给 null。 */
    private final BiFunction<CompatModule, T, T> wrap;
    private final List<Entry<T>> entries = new ArrayList<>();

    Slot(String what, BiFunction<CompatModule, T, T> wrap) {
        this.what = Objects.requireNonNull(what, "what");
        this.wrap = wrap;
    }

    /** 联动入口交一个进世界才建的建法：登记时只收着，进世界带着玩家行为再建。 */
    void add(CompatModule module, Function<PlayerServices, T> build) {
        entries.add(new Deferred<>(module, build));
    }

    /** 联动入口交一个登记时就建好的：当场包一层，模组停用后由这一层回答"用不了"。 */
    void add(CompatModule module, T value) {
        Objects.requireNonNull(value, "value");
        entries.add(new Ready<>(wrap.apply(module, value)));
    }

    /** 交一样不挂模组、也不用包一层的（随身背包、只读操作声明）。 */
    void add(T value) {
        entries.add(new Ready<>(Objects.requireNonNull(value, "value")));
    }

    /** 登记时就有的东西，按登记顺序；进世界时原样交出去。 */
    List<T> ready() {
        List<T> made = new ArrayList<>();
        for (Entry<T> entry : entries) {
            if (entry instanceof Ready<T> present) {
                made.add(present.value());
            }
        }
        return List.copyOf(made);
    }

    /**
     * 进世界时逐个建：停用了的跳过；建法碰到 LinkageError 由联动入口停用模组，别的错只少这一个，写一行日志。
     * 建出来的都包一层（登记时声明了包装的槽）。
     */
    List<T> build(PlayerServices services) {
        List<T> made = new ArrayList<>();
        for (Entry<T> entry : entries) {
            if (!(entry instanceof Deferred<T> recipe)) continue;
            CompatModule module = recipe.module();
            try {
                T built = Objects.requireNonNull(module.call("建" + what, () -> recipe.build().apply(services)),
                        "建法返回了 null");
                // 建出来的包一层：推进到一半模组停用时按不支持收场，不当成内部错误。包的时候出错也算这个模组建不出来。
                made.add(wrap != null ? wrap.apply(module, built) : built);
            } catch (RuntimeException failure) {
                LOG.error("联动 {}（{}）的{}建不出来，这次进世界少这一个", module.name(), module.modId(), what, failure);
            }
        }
        return List.copyOf(made);
    }

    /** 交接到一半出错时先记下这个数，回滚时按它撤。 */
    int size() {
        return entries.size();
    }

    /** 回滚：把交接开始之后交上来的全撤掉，交接之前的不动。 */
    void truncate(int before) {
        entries.subList(before, entries.size()).clear();
    }
}
