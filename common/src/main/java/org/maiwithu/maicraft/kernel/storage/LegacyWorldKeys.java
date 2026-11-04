// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.kernel.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;

/**
 * 旧版本的世界身份编号：按存档目录或服务器地址算，删了存档用同名文件夹重建会认成同一个，新存档读到旧存档的记忆。
 * 现在按存档自己的世界编号存；第一次用世界编号进一个世界时，决定要不要把旧编号名下的数据（地点、区域、
 * 没做完的目标、预留、各种档案）整体搬到世界编号名下，每个世界编号只决定一次。
 *
 * <p>刚建好的世界不搬：旧编号名下的数据只可能是同一个文件夹里删掉的那个旧存档留下的。
 * 玩过一阵的世界搬：这是旧版本里玩过的存档，或旧版本里连过的服务器，第一次在新版本里进。
 * 决定写成世界编号名下的一条记录，下次进来不再搬，旧编号名下没搬的数据留在原处不动。
 *
 * <p>宿主（例如主播那边的生物名册）也按旧编号存过数据：同一个决定还回答"这个世界的数据在旧版本里存在哪个编号名下"，
 * 宿主按它把自己的旧数据搬到世界编号名下，和这里搬世界记忆的判断一致，不会把删掉的同名旧存档的数据搬进新存档。
 */
public final class LegacyWorldKeys {
    static final String SCOPE = "world_identity";
    static final String DECIDED = "legacy_key";
    private static final int DECISION_LIMIT = 1024;

    private LegacyWorldKeys() {}

    /**
     * 这个世界的旧数据怎么处理。
     *
     * @param moved    这一次搬过来几条；不用搬、之前已经决定过时为 0
     * @param worldKey 这个世界的数据在旧版本里存在哪个编号名下：接手旧数据的世界是旧编号，
     *                 刚建好的世界、认不出旧编号或编号没变的就是世界编号本身
     */
    public record Decision(int moved, String worldKey) {}

    /**
     * 进世界、建世界记忆之前调一次：该搬就把旧编号名下的数据搬到世界编号名下，再记下已经决定过。
     * 已经决定过的照记下的答，之后游戏时间走了也不改主意。
     *
     * @param legacyKey 旧版本那串按存档目录或服务器地址算的编号；认不出时为 null
     * @param key       这个世界的世界编号
     * @param newWorld  这个世界刚建好（游戏时间还没走多少）；只在第一次决定时用
     */
    public static Decision adopt(DocumentStore documents, String legacyKey, String key, boolean newWorld) throws IOException {
        if (legacyKey == null || legacyKey.equals(key)) return new Decision(0, key);
        String decided = documents.read(SCOPE, key, DECIDED, DECISION_LIMIT);
        if (decided != null) return new Decision(0, wasNewWorld(decided, newWorld) ? key : legacyKey);
        // 世界编号名下已经有数据（之前搬过、只是没来得及记下决定）时，搬家这一步本身不动，只补记决定。
        int moved = newWorld ? 0 : documents.moveIdentity(legacyKey, key);
        documents.write(SCOPE, key, DECIDED, "{\"moved\":" + moved + ",\"new_world\":" + newWorld + "}", true);
        return new Decision(moved, newWorld ? key : legacyKey);
    }

    // 记下的决定里当时算不算刚建好的世界；记录读不懂时按这一次现场的判断答，不让一条坏记录挡住进世界。
    private static boolean wasNewWorld(String decided, boolean fallback) {
        try {
            JsonObject json = JsonParser.parseString(decided).getAsJsonObject();
            return json.has("new_world") ? json.get("new_world").getAsBoolean() : fallback;
        } catch (RuntimeException unreadable) {
            return fallback;
        }
    }
}
