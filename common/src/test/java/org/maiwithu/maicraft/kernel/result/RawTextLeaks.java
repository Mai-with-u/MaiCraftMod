// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.result;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 扫一遍写给 LLM 看的结果文字（问题说明、确认记录、结果摘要），找出漏进来的英文句子和原始对象串。
 *
 * <p>英文句子按"连着三个以上、只隔空白的英文单词"认；注册 ID（{@code minecraft:stone}）、
 * MCP 字段名这类下划线连写的小写名字（{@code dig_tool}）、全大写的枚举值（{@code REFUSED_BY_GAME}）
 * 是给 LLM 用的名字，先抠掉再数，不算句子。原始对象串按 toString 的痕迹认：名字后面紧跟花括号
 * （{@code Block{…}}、{@code Item{…}}）、{@code @} 加十六进制哈希、驼峰类名、带包名的全限定名。
 */
public final class RawTextLeaks {

    /** 给 LLM 用的名字：注册 ID、下划线连写的小写字段名、全大写的枚举值；抠掉后不参与数英文单词。 */
    private static final Pattern NAMES = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9_./-]+"
                    + "|\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b"
                    + "|\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*\\b");
    /** 连着三个以上、只隔空白的英文单词。 */
    private static final Pattern ENGLISH_SENTENCE = Pattern.compile(
            "[A-Za-z][A-Za-z'-]*(?:\\s+[A-Za-z][A-Za-z'-]*){2,}");
    /** toString 的痕迹：名字紧跟花括号、对象哈希、驼峰类名、全限定名。 */
    private static final Pattern RAW_OBJECT = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*\\{"
                    + "|@[0-9a-fA-F]{4,}\\b"
                    + "|\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+\\b"
                    + "|\\b(?:java|javax|net\\.minecraft|com\\.mojang|org\\.maiwithu)\\.[a-z]");

    private RawTextLeaks() {}

    /** 一段文字里漏出来的每一处：英文句子与原始对象串，按出现先后；干净时为空。 */
    public static List<String> find(String text) {
        List<String> leaks = new ArrayList<>();
        if (text == null) return leaks;
        Matcher raw = RAW_OBJECT.matcher(text);
        while (raw.find()) leaks.add("原始对象串「" + raw.group() + "」");
        // 名字换成一个中文点，把前后两段英文隔开：名字本身不算单词，也不能把两边连成一句。
        String withoutNames = NAMES.matcher(text).replaceAll("·");
        Matcher sentence = ENGLISH_SENTENCE.matcher(withoutNames);
        while (sentence.find()) leaks.add("英文句子「" + sentence.group() + "」");
        return leaks;
    }

    /** 断言这段结果文字里没有英文句子和原始对象串；有就连同原文一起报出来。 */
    public static void assertNone(String text) {
        List<String> leaks = find(text);
        if (!leaks.isEmpty()) fail("结果文字漏出了 " + leaks + "：" + text);
    }

    /** 逐条断言；报错时带上是第几条，方便对上是哪一种收场。 */
    public static void assertNoneIn(List<String> texts) {
        for (int i = 0; i < texts.size(); i++) {
            List<String> leaks = find(texts.get(i));
            if (!leaks.isEmpty()) fail("第 " + (i + 1) + " 条结果文字漏出了 " + leaks + "：" + texts.get(i));
        }
    }
}
