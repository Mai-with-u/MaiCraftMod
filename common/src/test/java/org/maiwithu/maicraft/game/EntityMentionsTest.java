// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 文字里点到的实体：死亡播报的死者与凶手按出现先后读出来，玩家名带着团队前缀整段算一个，
 * 同一只只记一次，只有插入文本的认得出 UUID，没挂实体的文字什么都不给。组件按原版写实体名字的样子手搭。
 */
class EntityMentionsTest {
    private static final UUID MOMO = UUID.fromString("5f2b9c1e-0d4a-4c1b-9e7f-1a2b3c4d5e6f");
    private static final UUID CREEPER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** 原版写一只实体名字的样子：挂悬停显示实体，插入文本是 UUID。 */
    static MutableComponent named(String name, EntityType<?> type, UUID uuid) {
        return Component.literal(name).withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
                        new HoverEvent.EntityTooltipInfo(type, uuid, Component.literal(name))))
                .withInsertion(uuid.toString()));
    }

    @Test
    void aDeathMessageNamesTheVictimThenTheKiller() {
        Component death = Component.translatableWithFallback("death.attack.explosion.player", "%s 被 %s 炸死了",
                named("momo", EntityType.CAT, MOMO), named("苦力怕", EntityType.CREEPER, CREEPER));

        assertEquals(List.of(new MentionedEntity("momo", "minecraft:cat", MOMO),
                new MentionedEntity("苦力怕", "minecraft:creeper", CREEPER)), EntityMentions.in(death));
    }

    @Test
    void aPlayerNameWithATeamPrefixIsOneMention() {
        // 玩家显示名：外面一层空字挂着悬停，团队前缀和名字是它的子段，整段算一个。
        MutableComponent shown = Component.literal("").append("[红队] ").append("Steve").withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ENTITY,
                        new HoverEvent.EntityTooltipInfo(EntityType.PLAYER, STEVE, Component.literal("Steve"))))
                .withInsertion("Steve"));
        Component joined = Component.literal("看，").append(shown).append(" 来了，").append(shown);

        assertEquals(List.of(new MentionedEntity("[红队] Steve", "minecraft:player", STEVE)), EntityMentions.in(joined),
                "同一个人出现两次只记一次");
    }

    @Test
    void anInsertionThatIsAUuidStillNamesTheEntityWithoutAType() {
        Component bare = Component.literal("momo").withStyle(style -> style.withInsertion(MOMO.toString()));
        Component playerName = Component.literal("Steve").withStyle(style -> style.withInsertion("Steve"));

        List<MentionedEntity> found = EntityMentions.in(Component.literal("").append(bare).append(playerName));

        assertEquals(List.of(new MentionedEntity("momo", null, MOMO)), found, "插入文本不是 UUID 的不算");
        assertNull(found.getFirst().type());
    }

    @Test
    void plainTextNamesNobody() {
        assertTrue(EntityMentions.in(Component.literal("Alex 加入了游戏")).isEmpty());
        assertEquals(Optional.empty(), EntityMentions.named(Component.literal("Server")));
    }

    @Test
    void aWholeNameIsReadEvenInsideAnEmptyWrapper() {
        assertEquals(MOMO, EntityMentions.named(named("momo", EntityType.CAT, MOMO)).orElseThrow().uuid());
        assertEquals(MOMO, EntityMentions.named(Component.empty().append(named("momo", EntityType.CAT, MOMO)))
                .orElseThrow().uuid());
        // 名字只是整段文字里的一部分时，整段说的不是它。
        assertEquals(Optional.empty(), EntityMentions.named(
                Component.literal("女仆 ").append(named("momo", EntityType.CAT, MOMO))));
    }
}
