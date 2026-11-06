// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.List;
import java.util.Map;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 寻找的结算：count 是最多找几个，找满即收工；这一片看完不够也算找完，写清找到几个、查了什么范围；一个都没有才算没找到。
 * "查过已加载区没有"不等于"世界里没有"——这句话术只在这里出现一次，
 * 成功的结算里不出现。纯计算，不读世界；任务在收束时调用。
 */
final class FindSettlement {

    private FindSettlement() {}

    /** 找满了：把命中交给 LLM，下一步去那里还是采掉由它决定，不写死指路话术。 */
    static TaskResult done(FindInput input, List<FindDetails.Found> found, Map<String, Integer> byType) {
        return TaskResult.builder(TaskResult.Status.DONE, summary(input, found.size()))
                .details(new FindDetails(found, input.radius(), true, byType))
                .build();
    }

    /** 扔末影之眼找到了要塞：结论用扔眼那一趟的一句话（在哪、离多远、扔了几只碎了几只），再说去末地怎么接。 */
    static TaskResult byEyes(FindInput input, List<FindDetails.Found> found, Map<String, Integer> byType,
            String eyes) {
        return TaskResult.builder(TaskResult.Status.DONE,
                        eyes + "；要去末地，走到那附近再用 travel 去 minecraft:the_end，会按这条线索找末地门")
                .details(new FindDetails(found, input.radius(), true, byType))
                .build();
    }

    /**
     * 这一片看完了却没找满：count 是最多找几个，有收获就按找完收场，写明找到几个、这一片看完了；
     * 要凑够数的调用方看个数自己决定换地方接着找。一个都没有按没找到失败。
     */
    static TaskResult insufficient(FindInput input, List<FindDetails.Found> found,
            Map<String, Integer> byType) {
        return insufficient(input, found, byType, null);
    }

    /** 同上；{@code hiddenLead} 是范围内被挡住、看不见的那些的线索（几只、最近一只的方位），没有为 null。 */
    static TaskResult insufficient(FindInput input, List<FindDetails.Found> found,
            Map<String, Integer> byType, String hiddenLead) {
        Problem problem = Problem.of(Problem.Kind.NOT_FOUND, searched(input),
                "走近一些再找，或先出行到大概会有的地方再找；更大的一片用 explore 跑一趟（路过的产地线索会记下）；"
                        + "埋在石头里的矿看不见，要拿到它直接用 obtain，会自己往下挖着找");
        if (found.isEmpty()) {
            // 一个都看不见、却有被挡住的：结论里就说清"有，在哪，被挡住了"，只读结论的也不会当成附近没有。
            String wanted = String.join("、", input.selectors());
            return TaskResult.failed(hiddenLead == null ? "寻找：这一片没有找到" + wanted
                    : "寻找：这一片没有看得见的" + wanted + "；" + hiddenLead, problem);
        }
        // 找到几个就是几个：LLM 常把 count 当上限（"附近所有墓碑"填 10），找到 7 个照实交出来，不判没做成。
        // 看不见的那几个的线索照样带上，想找全的自己走近再找。
        String rest = "最多找 " + input.count() + " 个，半径 " + input.radius() + " 格的已加载区看完了，看得见的就这些"
                + (hiddenLead == null ? "" : "；" + hiddenLead);
        return TaskResult.builder(TaskResult.Status.DONE, summary(input, found.size()) + "（" + rest + "）")
                .details(new FindDetails(found, input.radius(), true, byType))
                .build();
    }

    /** 结算共用的查证话术：查了多大范围、查过没有不等于没有。 */
    static String searched(FindInput input) {
        return "半径 " + input.radius() + " 格的已加载区已经找完，没有更多看得见的"
                + input.selectors().get(0) + "；查过没有不等于世界里没有";
    }

    // 一句话结论：找到了几个、最近的一个在哪，直播解说可以直接念。
    private static String summary(FindInput input, int foundCount) {
        return "找到 " + foundCount + " 个";
    }
}
