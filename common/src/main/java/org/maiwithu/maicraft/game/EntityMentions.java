// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 从文字组件里认出点到的实体。原版写一只实体的名字（{@code Entity.getDisplayName}）时，会给那段文字挂上
 * "悬停显示实体"（实体类型、UUID、名字），插入文本（Shift 点击插进聊天框的字）写成它的 UUID；玩家的插入文本是玩家名。
 * 死亡播报、进出提示、女仆墓碑上的名字都是这样带过来的，服务器转发聊天时样式原样保留。
 *
 * <p>按组件结构走，不看本机语言文件：先看这一段自己的样式，认出了就整段算一只（团队前缀、后缀是它的子段，
 * 样式跟着它），不再往里看；认不出再看可翻译内容的参数（死亡播报里死的和凶手都是参数），最后看接在后面的兄弟段。
 * 本机语言里缺这条翻译时参数照样读得到。同一只实体在一句里出现几次只记第一次。
 */
public final class EntityMentions {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private EntityMentions() {}

    /** 一段文字里按出现先后点到的实体；一只都没点到时为空。 */
    public static List<MentionedEntity> in(Component text) {
        Map<UUID, MentionedEntity> found = new LinkedHashMap<>();
        walk(text, found);
        return List.copyOf(found.values());
    }

    /**
     * 这段文字整段说的是哪只实体：墓碑上的女仆名、聊天格式里的发言人名字用它。整段自己挂着实体时就是它；
     * 外面只套了一层没有样式的空壳时，里面恰好一只、名字就是整段文字的，也算它。认不出时为空。
     */
    public static Optional<MentionedEntity> named(Component text) {
        Optional<MentionedEntity> own = ofPart(text);
        if (own.isPresent()) return own;
        List<MentionedEntity> inside = in(text);
        if (inside.size() != 1) return Optional.empty();
        MentionedEntity only = inside.getFirst();
        return text.getString().strip().equals(only.name()) ? Optional.of(only) : Optional.empty();
    }

    // 这一段挂着实体就整段记下、不往里看；没挂就依次看可翻译内容的参数和接在后面的兄弟段。
    private static void walk(Component part, Map<UUID, MentionedEntity> found) {
        Optional<MentionedEntity> own = ofPart(part);
        if (own.isPresent()) {
            found.putIfAbsent(own.get().uuid(), own.get());
            return;
        }
        if (part.getContents() instanceof TranslatableContents translatable) {
            for (Object argument : translatable.getArgs()) {
                if (argument instanceof Component component) walk(component, found);
            }
        }
        for (Component sibling : part.getSiblings()) {
            walk(sibling, found);
        }
    }

    // 一段文字自己的样式说的是哪只实体：悬停显示实体优先（带类型）；没有悬停时插入文本正好是 UUID 的，只认得出 UUID。
    private static Optional<MentionedEntity> ofPart(Component part) {
        Style style = part.getStyle();
        HoverEvent hover = style.getHoverEvent();
        HoverEvent.EntityTooltipInfo info = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ENTITY);
        if (info != null) {
            String shown = shownName(part);
            String name = shown != null ? shown : info.name.map(Component::getString).map(String::strip)
                    .filter(text -> !text.isEmpty()).orElse(null);
            return Optional.of(new MentionedEntity(name, BuiltInRegistries.ENTITY_TYPE.getKey(info.type).toString(),
                    info.id));
        }
        String insertion = style.getInsertion();
        if (insertion != null && UUID_TEXT.matcher(insertion).matches()) {
            return Optional.of(new MentionedEntity(shownName(part), null, UUID.fromString(insertion)));
        }
        return Optional.empty();
    }

    // 文字里显示的那段名字；空的给 null，交给悬停里的名字补。
    private static String shownName(Component part) {
        String shown = part.getString().strip();
        return shown.isEmpty() ? null : shown;
    }
}
