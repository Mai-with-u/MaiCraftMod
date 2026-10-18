// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.maiwithu.maicraft.mcp.transport.McpResources;

/**
 * 随包的玩法技能：从索引读出全部 {@code SKILL.md}，按前提模组筛选，作为 MCP 资源发布。
 *
 * <p>索引是唯一的清单，没写进去的文件不发布；文件缺失、frontmatter 不合法、名字与目录
 * 对不上、地址重复，都在建库时失败，不把空正文或坏名字交给模型。技能正文原样读出，
 * 不做拼接或改写；列表里只给地址、名字、一句话用途和类型。
 */
public final class SkillLibrary implements McpResources {
    private static final String INDEX = "/assets/maicraft/skills/index.txt";
    private static final String ROOT = "/assets/maicraft/skills/";
    private static final String MIME = "text/markdown";
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern MOD_ID = Pattern.compile("[a-z0-9_]+");
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    private final Predicate<String> modAvailable;
    private final Map<String, Skill> byUri = new LinkedHashMap<>();
    private final List<Skill> catalog;

    /**
     * @param modAvailable 本局某个模组的联动接上了没有；技能只在需要的模组都在时发布
     */
    public SkillLibrary(Predicate<String> modAvailable) {
        this.modAvailable = Objects.requireNonNull(modAvailable, "modAvailable");
        List<Skill> loaded = new ArrayList<>();
        for (String path : readIndex()) {
            Skill skill = load(path);
            if (byUri.putIfAbsent(skill.uri(), skill) != null) {
                throw new IllegalStateException("技能地址重复：" + skill.uri());
            }
            loaded.add(skill);
        }
        catalog = List.copyOf(loaded);
    }

    /** 当前适用的技能，按地址排序；测试与面板读它。 */
    public List<Skill> catalog() {
        return applicable();
    }

    @Override
    public boolean isEmpty() {
        return applicable().isEmpty();
    }

    @Override
    public JsonObject list() {
        JsonArray resources = new JsonArray();
        for (Skill skill : applicable()) {
            JsonObject item = new JsonObject();
            item.addProperty("uri", skill.uri());
            item.addProperty("name", skill.name());
            item.addProperty("description", skill.description());
            item.addProperty("mimeType", MIME);
            resources.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("resources", resources);
        return result;
    }

    @Override
    public JsonObject read(String uri) {
        Skill skill = byUri.get(uri);
        if (skill == null) {
            throw new IllegalArgumentException("没有这项技能：" + uri);
        }
        if (!skill.availableWhen(modAvailable)) {
            throw new IllegalArgumentException("这项技能要的模组没装：" + uri
                    + "（需要 " + String.join(", ", skill.requiredMods()) + "）");
        }
        JsonObject content = new JsonObject();
        content.addProperty("uri", skill.uri());
        content.addProperty("mimeType", MIME);
        content.addProperty("text", skill.text());
        JsonArray contents = new JsonArray();
        contents.add(content);
        JsonObject result = new JsonObject();
        result.add("contents", contents);
        return result;
    }

    private List<Skill> applicable() {
        return catalog.stream()
                .filter(skill -> skill.availableWhen(modAvailable))
                .sorted(Comparator.comparing(Skill::uri))
                .toList();
    }

    // 索引一行一项，写 <分类>/<名字>；空行与 # 开头的行是注释。
    private static List<String> readIndex() {
        List<String> paths = new ArrayList<>();
        for (String line : readResource(INDEX).split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            paths.add(trimmed);
        }
        if (paths.isEmpty()) {
            throw new IllegalStateException("技能索引是空的：" + INDEX);
        }
        return paths;
    }

    private static Skill load(String path) {
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1 || path.indexOf('/', slash + 1) >= 0) {
            throw new IllegalStateException("技能索引的一行要写 <分类>/<名字>：" + path);
        }
        String category = path.substring(0, slash);
        String name = path.substring(slash + 1);
        if (!NAME.matcher(category).matches()) {
            throw new IllegalStateException("技能分类只能用连字符小写名：" + category);
        }
        validateName(name, path);
        String text = readResource(ROOT + path + "/SKILL.md");
        Frontmatter frontmatter = Frontmatter.parse(text, path);
        if (!frontmatter.name().equals(name)) {
            throw new IllegalStateException("技能名和目录对不上：" + path + " 里写的是 " + frontmatter.name());
        }
        return new Skill(frontmatter.name(), frontmatter.description(), category,
                "skill://" + path + "/SKILL.md", text, frontmatter.requiredMods());
    }

    private static String readResource(String resource) {
        try (InputStream input = SkillLibrary.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("缺少技能文件：" + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("读技能文件失败：" + resource, failure);
        }
    }

    private static void validateName(String name, String path) {
        if (name.length() > MAX_NAME_LENGTH || !NAME.matcher(name).matches()) {
            throw new IllegalStateException("技能名只能用小写字母、数字、连字符（" + path + "）：" + name);
        }
    }

    /** frontmatter 的严格子集：name、description、metadata 三个字段，metadata 一行 JSON。 */
    private record Frontmatter(String name, String description, Set<String> requiredMods) {

        static Frontmatter parse(String text, String path) {
            String normalized = text.replace("\r\n", "\n");
            if (!normalized.startsWith("---\n")) {
                throw new IllegalStateException("技能文件缺少 frontmatter：" + path);
            }
            int end = normalized.indexOf("\n---\n", 4);
            if (end < 0) {
                throw new IllegalStateException("技能 frontmatter 没有结束行：" + path);
            }
            if (normalized.substring(end + 5).isBlank()) {
                throw new IllegalStateException("技能正文是空的：" + path);
            }
            String name = null;
            String description = null;
            Set<String> mods = Set.of();
            boolean metadataSeen = false;
            for (String line : normalized.substring(4, end).split("\n")) {
                if (line.isBlank()) continue;
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new IllegalStateException("技能 frontmatter 这一行读不懂（" + path + "）：" + line);
                }
                String key = line.substring(0, colon).strip();
                String value = line.substring(colon + 1).strip();
                switch (key) {
                    case "name" -> {
                        if (name != null) throw new IllegalStateException("技能 frontmatter 重复写了 name：" + path);
                        name = value;
                    }
                    case "description" -> {
                        if (description != null) {
                            throw new IllegalStateException("技能 frontmatter 重复写了 description：" + path);
                        }
                        description = value;
                    }
                    case "metadata" -> {
                        if (metadataSeen) throw new IllegalStateException("技能 frontmatter 重复写了 metadata：" + path);
                        metadataSeen = true;
                        mods = requiresMods(value, path);
                    }
                    default -> throw new IllegalStateException("技能 frontmatter 不认识的字段（" + path + "）：" + key);
                }
            }
            if (name == null || description == null) {
                throw new IllegalStateException("技能 frontmatter 缺少 name 或 description：" + path);
            }
            validateName(name, path);
            if (description.isEmpty() || description.length() > MAX_DESCRIPTION_LENGTH) {
                throw new IllegalStateException("技能 description 要在 1 到 " + MAX_DESCRIPTION_LENGTH + " 字之间：" + path);
            }
            return new Frontmatter(name, description, mods);
        }

        // metadata 是 YAML 里的一个 JSON 对象，值只能是字符串；这里只认 requires-mods。
        private static Set<String> requiresMods(String value, String path) {
            if (value.isEmpty()) return Set.of();
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(value);
            } catch (JsonParseException failure) {
                throw new IllegalStateException("技能 metadata 不是合法 JSON（" + path + "）：" + value, failure);
            }
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("技能 metadata 要是 JSON 对象：" + path);
            }
            JsonElement mods = parsed.getAsJsonObject().get("requires-mods");
            if (mods == null || mods.isJsonNull()) return Set.of();
            if (!mods.isJsonPrimitive() || !mods.getAsJsonPrimitive().isString()) {
                throw new IllegalStateException("技能 metadata.requires-mods 要是字符串：" + path);
            }
            String raw = mods.getAsString().strip();
            if (raw.isEmpty()) return Set.of();
            Set<String> result = new LinkedHashSet<>();
            for (String part : raw.split(",")) {
                String mod = part.strip();
                if (!MOD_ID.matcher(mod).matches()) {
                    throw new IllegalStateException("技能 metadata.requires-mods 里的模组 ID 不合法（" + path + "）：" + mod);
                }
                result.add(mod);
            }
            return Set.copyOf(result);
        }
    }
}
