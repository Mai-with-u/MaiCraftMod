// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.skill;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 一项随包的玩法技能：名字、一句话用途、分类、地址、正文与前提模组。
 *
 * <p>技能是写给模型的玩法经验文档，不是能力，也不是替模型做决定的代码；正文原样经
 * MCP 资源读出，地址是 {@code skill://<分类>/<名字>/SKILL.md}。
 */
public record Skill(
        String name,
        String description,
        String category,
        String uri,
        String text,
        Set<String> requiredMods) {

    public Skill {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(text, "text");
        requiredMods = Set.copyOf(Objects.requireNonNull(requiredMods, "requiredMods"));
    }

    /** 本局这些模组的联动都接上了，这项技能才发布。 */
    public boolean availableWhen(Predicate<String> modAvailable) {
        return requiredMods.stream().allMatch(modAvailable);
    }
}
