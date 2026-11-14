// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 用东西能力的结果细节：确认生效了几次、对着什么用的、打开的界面里有什么、容器是谁的与开没开过、告示牌上实际写下的字、骑上了什么、聊天栏里服务端说了什么。
 *
 * @param appliedTimes     确认生效了几次
 * @param usedOn           这一下对着什么用的：air（没指方块也没指实体，对着空气用手上的东西）、block、entity
 * @param opened           打开过的界面：是什么、里面有什么（按物品合并）；没打开过界面为 null
 * @param whose            点开方块容器时它的来源五态（natural_loot / self / player / no_record / unknown）；没点开方块容器为 null
 * @param lastModifiedBy   点开的容器最后由谁放下（在线写玩家名，不在线写"一位不在线的玩家"）；
 *                         不是容器、归属没问到、角色自己放的为 null，字段整个不出现
 * @param openedBySelf     这一次点开之前世界记忆里有没有角色亲手打开过这只方块容器的记录；没点开方块容器为 null
 * @param signText         告示牌上实际写下的字，逐行；没写告示牌为空列表
 * @param riding           骑上的东西；没骑为 null
 * @param gameMessages          生效的那几下前后聊天栏收到的服务端系统消息原话，按收到先后；没有为 null
 * @param unrecognizedProcessor 对着不认识的加工容器交互生效时为 true（塞进了东西不确认会不会出产物，或点开了不认识的加工界面）；其余为 null
 */
record UseDetails(long appliedTimes, String usedOn, String opened, String whose, String lastModifiedBy,
        Boolean openedBySelf, List<String> signText, String riding, List<String> gameMessages,
        Boolean unrecognizedProcessor) implements ResultDetails {
    UseDetails {
        signText = List.copyOf(signText);
        gameMessages = gameMessages == null || gameMessages.isEmpty() ? null : List.copyOf(gameMessages);
    }

    UseDetails(long appliedTimes, String usedOn, String opened, String whose, String lastModifiedBy,
            Boolean openedBySelf, List<String> signText, String riding, List<String> gameMessages) {
        this(appliedTimes, usedOn, opened, whose, lastModifiedBy, openedBySelf, signText, riding, gameMessages, null);
    }

    UseDetails(long appliedTimes, String usedOn, String opened, String lastModifiedBy, List<String> signText,
            String riding, List<String> gameMessages) {
        this(appliedTimes, usedOn, opened, null, lastModifiedBy, null, signText, riding, gameMessages);
    }

    UseDetails(long appliedTimes, String opened, String lastModifiedBy, List<String> signText, String riding,
            List<String> gameMessages) {
        this(appliedTimes, null, opened, null, lastModifiedBy, null, signText, riding, gameMessages);
    }

    UseDetails(long appliedTimes, String opened, List<String> signText, String riding) {
        this(appliedTimes, null, opened, null, null, null, signText, riding, null);
    }
}
