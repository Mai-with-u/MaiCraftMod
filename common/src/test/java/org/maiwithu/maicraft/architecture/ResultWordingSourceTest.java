// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.kernel.result.RawTextLeaks;

/**
 * 源码检查：游戏接口层的交互与容器界面两个包，写进确认记录、拒绝原因与异常说明的字符串一律是中文，
 * 不夹英文句子，也不夹类名、方块对象串这类原始写法——这些话会原样拼进 LLM 看到的问题说明。
 * 只进日志的字符串（{@code LOG.} 开头的语句里）不查。
 */
class ResultWordingSourceTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final List<Path> ROOTS = List.of(
            REPO.resolve("common/src/main/java/org/maiwithu/maicraft/game/interaction"),
            REPO.resolve("common/src/main/java/org/maiwithu/maicraft/game/menu"));

    /** 一个字符串字面量：内容、所在行、它所在的那条语句是不是写日志。 */
    private record Literal(String text, int line, boolean logged) {}

    @Test
    void stringsThatReachResultTextAreChinese() {
        List<String> violations = new ArrayList<>();
        for (Path root : ROOTS) {
            for (Path file : javaFiles(root)) {
                for (Literal literal : literals(read(file))) {
                    if (literal.logged()) continue;
                    List<String> leaks = RawTextLeaks.find(literal.text());
                    if (!leaks.isEmpty()) {
                        violations.add(REPO.relativize(file) + ":" + literal.line() + " " + leaks);
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "这些字符串会进结果文字，要改成中文结论句：\n" + String.join("\n", violations));
    }

    // 逐字符走一遍：跳过注释，取出每个字符串字面量，并按上一个分号或花括号之后的代码判断它在不在写日志的语句里。
    private static List<Literal> literals(String text) {
        List<Literal> found = new ArrayList<>();
        StringBuilder statement = new StringBuilder();
        int line = 1;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (text.startsWith("//", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? text.length() : end + 2;
                line += (int) text.substring(i, stop).chars().filter(ch -> ch == '\n').count();
                i = stop;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != c && text.charAt(end) != '\n') {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                if (c == '"') found.add(new Literal(text.substring(i + 1, Math.min(end, text.length())), line,
                        statement.toString().contains("LOG.")));
                i = Math.min(end + 1, text.length());
            } else {
                if (c == '\n') line++;
                if (c == ';' || c == '{' || c == '}') statement.setLength(0);
                else statement.append(c);
                i++;
            }
        }
        return found;
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
