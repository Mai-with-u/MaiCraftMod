// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.perception.spi.ModSight;
import org.maiwithu.maicraft.behavior.perception.spi.ModThing;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;

/**
 * 女仆在观察里的样子：和原版实体共用 e#，场景里那只生物照常出现在 entities，这里只多给她的女仆状态——
 * 皮肤、工作、班次与此刻活动、跟随或驻守、拾取、好感、乞食、不会受伤、在想（她在调哪个工具）、头上的话；
 * 详情另给让不让抱、背包种类、经验。女仆墓碑给墓碑上的女仆名。驯服关系、坐没坐、血量这些原版事实走实体本身。
 * 五子棋盘按中心那一格领 b#：轮到谁、下了几手、最后一手、她坐没坐上座位、谁赢了；详情给整个棋盘。
 */
final class MaidSight implements ModSight {

    private final TouhouLittleMaidCompat compat;

    MaidSight(TouhouLittleMaidCompat compat) {
        this.compat = Objects.requireNonNull(compat, "compat");
    }

    @Override public void sight(ClientLevel level, Player viewer, AABB region, Consumer<ModThing> sink) {
        String dimension = level.dimension().location().toString();
        for (GomokuState board : compat.gomokuBoards(level, region)) {
            BlockPos center = board.center();
            sink.accept(new ModThing("gomoku_board", ModThing.Identity.BLOCK, "",
                    new WorldPosition(center.getX(), center.getY(), center.getZ(), dimension), gomoku(board),
                    gomokuDetail(board)));
        }
        for (Entity entity : level.getEntities((Entity) null, region, found -> !found.isRemoved())) {
            WorldPosition at = new WorldPosition(entity.getBlockX(), entity.getBlockY(), entity.getBlockZ(), dimension);
            if (compat.isMaid(entity)) {
                MaidState state = compat.state(entity);
                sink.accept(new ModThing("maid", ModThing.Identity.ENTITY, Integer.toString(entity.getId()), at,
                        summary(state), detail(state)));
                continue;
            }
            Optional<String> tombstone = compat.tombstoneName(entity);
            if (tombstone.isPresent()) {
                JsonObject summary = new JsonObject();
                summary.addProperty("maid_name", tombstone.get());
                sink.accept(new ModThing("maid_tombstone", ModThing.Identity.ENTITY, Integer.toString(entity.getId()),
                        at, summary, null));
            }
        }
    }

    // 棋盘在场景里列的：轮到谁、下了几手、最后一手（记谱写法）、她坐没坐上座位；结束了写谁赢。
    static JsonObject gomoku(GomokuState board) {
        JsonObject summary = new JsonObject();
        summary.addProperty("turn", board.turn());
        summary.addProperty("moves", board.moves());
        board.lastMove().ifPresent(move -> summary.addProperty("last_move", move));
        summary.addProperty("maid_seated", board.maidSeated());
        board.winner().ifPresent(winner -> summary.addProperty("winner", winner));
        return summary;
    }

    // observe(detail) 给整个棋盘：15 行，X 你、O 她、. 空，第一行是离你最远的第 15 路。
    static JsonObject gomokuDetail(GomokuState board) {
        JsonObject detail = new JsonObject();
        JsonArray rows = new JsonArray();
        GomokuNotation.rows(board).forEach(rows::add);
        detail.add("board", rows);
        return detail;
    }

    // 场景里列的：她是谁的皮肤、在做什么工作、此刻在干什么、跟着还是驻守、好感几级；乞食、无敌、在想只在是的时候给。
    static JsonObject summary(MaidState state) {
        JsonObject summary = new JsonObject();
        summary.addProperty("skin_id", state.skinId());
        if (state.skinName() != null) summary.addProperty("skin_name", state.skinName());
        summary.addProperty("work", state.workId());
        if (state.workName() != null) summary.addProperty("work_name", state.workName());
        summary.addProperty("work_shift", state.shift().word());
        summary.addProperty("shift_now", state.shiftNow().word());
        summary.add("stay", stay(state));
        summary.addProperty("pickup", state.pickup());
        summary.add("affection", affection(Affection.of(state.affection())));
        if (state.begging()) summary.addProperty("begging", true);
        if (state.invulnerable()) summary.addProperty("invulnerable", true);
        if (state.thinking()) {
            JsonObject thinking = new JsonObject();
            if (state.thinkingNote() != null) thinking.addProperty("note", state.thinkingNote());
            summary.add("thinking", thinking);
        }
        List<String> texts = state.texts();
        if (!texts.isEmpty()) {
            JsonArray bubbles = new JsonArray();
            texts.forEach(bubbles::add);
            summary.add("bubbles", bubbles);
        }
        return summary;
    }

    // 详情另给的：让不让抱、背包种类、经验点。
    private static JsonObject detail(MaidState state) {
        JsonObject detail = new JsonObject();
        detail.addProperty("rideable", state.rideable());
        if (state.backpackId() != null) detail.addProperty("backpack", state.backpackId());
        detail.addProperty("experience", state.experience());
        return detail;
    }

    // 跟着主人：with_you；驻守：in_area，带活动范围的圆心与半径。
    private static JsonObject stay(MaidState state) {
        JsonObject stay = new JsonObject();
        if (!state.stayInArea()) {
            stay.addProperty("mode", "with_you");
            return stay;
        }
        stay.addProperty("mode", "in_area");
        if (state.areaCenter() != null) {
            JsonArray center = new JsonArray();
            center.add(state.areaCenter().getX());
            center.add(state.areaCenter().getY());
            center.add(state.areaCenter().getZ());
            stay.add("center", center);
        }
        stay.addProperty("radius", Math.round(state.areaRadius()));
        return stay;
    }

    private static JsonObject affection(Affection affection) {
        JsonObject json = new JsonObject();
        json.addProperty("level", affection.level());
        json.addProperty("points", affection.points());
        if (affection.toNext() != null) json.addProperty("to_next", affection.toNext());
        return json;
    }
}
