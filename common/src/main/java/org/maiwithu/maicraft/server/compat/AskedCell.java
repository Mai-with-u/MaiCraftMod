// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.compat;

import java.util.Objects;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import org.maiwithu.maicraft.network.ServerOperationException;

/**
 * 只读操作问的那一格：请求体里的 x、y、z，核对过在角色所在的维度、已加载、离角色不远。
 * 按格回答的只读操作（某格所在的 ME 网、某格管道所在的管网）都先过这一道：看得见那台设备的玩家才问得着，
 * 远处别人家的网不回答，不让只读操作变成远程探查。
 *
 * @param level 角色所在的服务端世界
 * @param at    问的那一格
 */
public record AskedCell(ServerLevel level, BlockPos at) {

    /** 只答角色这么远以内的格子（格）：和角色能看见的范围同一量级。 */
    public static final int MAX_DISTANCE = 128;

    public AskedCell {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(at, "at");
    }

    /**
     * 从请求体读出问的那一格并核对：缺坐标按 bad_request、没加载按 not_loaded、太远按 too_far 回"没执行"，
     * 客户端照原因如实说读不到。
     */
    public static AskedCell nearPlayer(ServerPlayer player, JsonObject body) {
        BlockPos at = new BlockPos(intOf(body, "x"), intOf(body, "y"), intOf(body, "z"));
        if (!(player.level() instanceof ServerLevel level) || !level.isLoaded(at)) {
            throw ServerOperationException.notApplied("not_loaded", "那一格没加载");
        }
        if (player.blockPosition().distSqr(at) > (double) MAX_DISTANCE * MAX_DISTANCE) {
            throw ServerOperationException.notApplied("too_far", "那一格离角色太远，只答附近的网");
        }
        return new AskedCell(level, at);
    }

    private static int intOf(JsonObject body, String key) {
        if (!body.has(key) || !body.get(key).isJsonPrimitive()) {
            throw ServerOperationException.notApplied("bad_request", "请求缺坐标 " + key);
        }
        return body.get(key).getAsInt();
    }
}
