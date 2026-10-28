// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.reserve;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.maiwithu.maicraft.behavior.acquire.engine.ReadsItemRegistry;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.inventory.ReservedItems;
import org.maiwithu.maicraft.kernel.ability.AbilityDoc;
import org.maiwithu.maicraft.kernel.ability.AbilityModule;
import org.maiwithu.maicraft.kernel.ability.AbilitySpec;
import org.maiwithu.maicraft.kernel.ability.ExecutionMode;
import org.maiwithu.maicraft.kernel.ability.Listing;
import org.maiwithu.maicraft.kernel.goal.StepContext;
import org.maiwithu.maicraft.kernel.goal.StepDecision;
import org.maiwithu.maicraft.kernel.param.ParamSpec;
import org.maiwithu.maicraft.kernel.param.ParamSpecs;
import org.maiwithu.maicraft.kernel.param.ParamType;
import org.maiwithu.maicraft.kernel.param.ParamValues;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.TaskFactories;

/**
 * 预留能力：调用方攒着东西时写下"身上这几样各留够几件"，角色自己做主时（现做工具、垫脚、趁空吃、腾背包……）
 * 就不动它们；下令的目标要用时先用不留的，不够才动预留的并扣掉。不占身体，execute 当场返回，手上的活照做；
 * 写下的预留一直有效，跟着这个世界存下来，用 cancel 撤。
 *
 * <p>参数在这里一次核对齐：物品 ID 写岔、件数写成 count、写了不认识的字段，全部攒齐一次报全，一条都不写进去。
 */
public final class ReserveAbility implements AbilityModule {

    /** 一次最多写几样：攒料用的预留是几样要紧的东西，几十样就该想想是不是在把整个背包锁住。 */
    static final int MAX_ITEMS = 32;

    private final ReservedItems reserved;
    private final ReadsItemRegistry registry;

    public ReserveAbility(ReservedItems reserved, ReadsItemRegistry registry) {
        this.reserved = Objects.requireNonNull(reserved, "reserved");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public AbilitySpec spec() {
        return new AbilitySpec(
                "maicraft:reserve",
                "预留身上攒着的东西：写下这几样各留够几件，角色自己做主时不动它们，下令的目标不够才用、用了扣掉；一直有效，不占身体",
                AbilityDoc.forAbility("reserve"),
                ParamSpecs.of(
                        ParamSpec.of("operation", ParamType.CHOICE).defaultValue("set")
                                .choices("set", "cancel")
                                .doc("set 写下这几样各留够几件（默认，没提到的不变）；cancel 这几样不再留，不给 items 就全部撤掉").build(),
                        ParamSpec.of("items", ParamType.JSON_ARRAY)
                                .doc("[{item, at_least}]：item 是物品 ID 或 # 标签；at_least 是身上要留够的件数（1 起），"
                                        + "不写就是这一样全都留着；cancel 时只看 item，也可以直接写物品 ID 的列表").build()),
                Set.of(),
                ExecutionMode.MEMORY_ONLY,
                Set.of(),
                List.of(),
                Listing.LISTED);
    }

    @Override
    public StepDecision decide(StepContext step) {
        ParamValues params = step.goal().params();
        boolean cancel = params.has("operation") && "cancel".equals(params.text("operation"));
        List<String> wrong = new ArrayList<>();
        if (!cancel && !params.has("items")) {
            wrong.add("要预留什么：items 写 [{\"item\": …, \"at_least\": …}]");
        }
        List<ReservedItems.Reservation> entries = params.has("items")
                ? read(params.json("items"), cancel, wrong) : List.of();
        if (!wrong.isEmpty()) {
            return new StepDecision.Finish(TaskResult.failed("预留的参数不对，一条都没写进去",
                    Problem.of(Problem.Kind.INVALID_PARAMETER, String.join("；", wrong),
                            "写 items: [{\"item\": \"minecraft:oak_planks\", \"at_least\": 40}]；撤掉写 operation=cancel")));
        }
        if (cancel) {
            return new StepDecision.Run(params.has("items")
                    ? ReserveInput.cancel(entries.stream().map(ReservedItems.Reservation::item).toList())
                    : ReserveInput.cancelAll());
        }
        return new StepDecision.Run(ReserveInput.set(entries));
    }

    // 逐项读：对象写 {item, at_least}，也认直接写物品 ID 的字符串（全都留着）；同一样写两次取后写的。
    // 一处写坏不打断别的项，攒齐了一次报全。
    private List<ReservedItems.Reservation> read(JsonElement raw, boolean cancel, List<String> wrong) {
        if (!raw.isJsonArray()) {
            wrong.add("items 必须是数组，每项 {\"item\": …, \"at_least\": …}");
            return List.of();
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.isEmpty()) wrong.add("items 是空的：至少写一样");
        if (array.size() > MAX_ITEMS) wrong.add("items 最多 " + MAX_ITEMS + " 项，这次写了 " + array.size() + " 项");
        Map<String, ReservedItems.Reservation> entries = new LinkedHashMap<>();
        for (int i = 0; i < array.size(); i++) {
            ReservedItems.Reservation entry = entry(array.get(i), "items 第 " + (i + 1) + " 项", cancel, wrong);
            if (entry != null) entries.put(entry.item(), entry);
        }
        return List.copyOf(entries.values());
    }

    private ReservedItems.Reservation entry(JsonElement element, String at, boolean cancel, List<String> wrong) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return known(element.getAsString(), null, at, wrong);
        }
        if (!element.isJsonObject()) {
            wrong.add(at + "必须是对象，写 {\"item\": …, \"at_least\": …}");
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        JsonElement item = entry.get("item");
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
            wrong.add(at + "缺少 item（物品 ID 或 # 标签）");
            return null;
        }
        // 件数写成 count 的单独说清：count 是"这次要多做多少"，这里要的是身上留够几件。
        if (entry.has("count")) wrong.add(at + "的件数写 at_least（身上留够几件），不是 count");
        for (String key : entry.keySet()) {
            if (!key.equals("item") && !key.equals("at_least") && !key.equals("count")) {
                wrong.add(at + "有不认识的字段 " + key + "：只认 item 和 at_least");
            }
        }
        Integer atLeast = cancel ? null : atLeast(entry.get("at_least"), at, wrong);
        if (!cancel && entry.has("at_least") && atLeast == null) return null;
        return known(item.getAsString(), atLeast, at, wrong);
    }

    // 件数：整数、至少 1；不写就是全都留着（返回 null，由调用处看有没有写过）。
    private static Integer atLeast(JsonElement raw, String at, List<String> wrong) {
        if (raw == null) return null;
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()
                || raw.getAsDouble() != Math.rint(raw.getAsDouble())) {
            wrong.add(at + "的 at_least 必须是整数");
            return null;
        }
        long value = raw.getAsLong();
        if (value < 1 || value > Integer.MAX_VALUE - 1) {
            wrong.add(at + "的 at_least 至少为 1（全都留着就不写 at_least）");
            return null;
        }
        return (int) value;
    }

    // 物品或标签在游戏里立不立得住：写岔的不写进预留，说成"…不存在；你是不是要 …"，照着改一次就对。
    private ReservedItems.Reservation known(String specifier, Integer atLeast, String at, List<String> wrong) {
        WantedItem wanted;
        try {
            wanted = new WantedItem(specifier);
        } catch (IllegalArgumentException bad) {
            wrong.add(at + "写法不对：" + bad.getMessage());
            return null;
        }
        boolean exists = wanted.isTag() ? registry.tagHasItems(wanted.tagId()) : registry.itemExists(wanted.itemId());
        if (!exists) {
            wrong.add(registry.missing(wanted.specifier()));
            return null;
        }
        return new ReservedItems.Reservation(wanted.specifier(), atLeast);
    }

    @Override
    public void registerTasks(TaskFactories factories) {
        // 预留表是客户端全程一份、进世界时换成那个世界存着的；任务只把这次的改动写进去。
        factories.register(ReserveInput.class, input -> new ReserveTask(input, reserved));
    }
}
