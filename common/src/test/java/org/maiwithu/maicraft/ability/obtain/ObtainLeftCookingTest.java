// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.obtain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.cook.FurnacesLeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.LeftCooking;
import org.maiwithu.maicraft.behavior.acquire.cook.WorkstationRecipe;
import org.maiwithu.maicraft.behavior.acquire.engine.ItemAcquisition;
import org.maiwithu.maicraft.behavior.acquire.engine.StartsAcquisition;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.CloseReason;
import org.maiwithu.maicraft.kernel.task.TaskRecords;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.kernel.task.TickResult;
import org.maiwithu.maicraft.mcp.tool.ResultJson;

/**
 * obtain 装好炉先走了怎么收场：一件没进包也是 partial，问题按"还没到取的时候"说清那一炉，
 * details.left_cooking 逐台给数；开场前留下的那几炉不算这一场的。引擎是替身，不碰游戏。
 */
class ObtainLeftCookingTest {

    private static final String KELP = "minecraft:dried_kelp";

    /** 挂计时总给 7 号的替身。 */
    private static final FurnacesLeftCooking.SetsTimers TIMER_SEVEN = new FurnacesLeftCooking.SetsTimers() {
        @Override public Optional<Integer> set(int afterSeconds, String note, long gameTick) {
            return Optional.of(7);
        }

        @Override public void cancel(int timerId) {}
    };

    private final FurnacesLeftCooking left = new FurnacesLeftCooking(TIMER_SEVEN);

    private static LeftCooking kelp(WorldPosition at, long now) {
        return new LeftCooking(at, WorkstationRecipe.Kind.SMELTING, "minecraft:dried_kelp_from_smelting", KELP,
                "干海带", "minecraft:kelp", "海带", "minecraft:coal", 64, 64, 167, now, now + 12800, null);
    }

    /** 引擎替身：推进一刻就装好一炉就走，以"还没到取的时候"收场；一件都不往背包里放。 */
    private final class LeavesAFurnace implements StartsAcquisition {
        @Override public Action need(ItemRequest request, Permissions permissions, ItemAcquisition.Scope scope,
                Consumer<String> onDelivered, TaskRecords records) {
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    left.leave(kelp(new WorldPosition(12, 64, -30, "minecraft:overworld"), context.gameTick()));
                    return ActionStatus.failed(left.lastLeft());
                }

                @Override public String describe() {
                    return "装好炉就走";
                }
            };
        }

        @Override public Action needSeveral(List<ItemRequest> requests, Permissions permissions,
                ItemAcquisition.Scope scope, Consumer<String> onDelivered, TaskRecords records) {
            return need(requests.getFirst(), permissions, scope, onDelivered, records);
        }
    }

    private static TickContext tick(long gameTick) {
        return new TickContext() {
            @Override public long gameTick() {
                return gameTick;
            }

            @Override public PlayerContext player() {
                return null;
            }
        };
    }

    private TaskResult run(ObtainTask task) {
        task.start(tick(0));
        for (int i = 1; i <= 100; i++) {
            if (task.tick(tick(i)) instanceof TickResult.Finished finished) {
                task.close(CloseReason.FINISHED);
                return finished.result();
            }
        }
        throw new AssertionError("任务一百刻都没跑完");
    }

    @Test
    void leavingAFurnaceIsPartialWithTheBatchInDetails() {
        // 开场前另一台已经留着烧：不算这一场的。
        left.leave(kelp(new WorldPosition(40, 64, 0, "minecraft:overworld"), 0));
        ObtainTaskTest.FakeBackpack backpack = new ObtainTaskTest.FakeBackpack();
        ObtainItems input = new ObtainItems(WantedItem.ofItem(KELP), 231,
                new ItemAcquisition.Scope(Set.of("smelt"), null, null, true), Permissions.DEFAULT, "按需要拿取",
                "再拿 231 个 " + KELP);
        TaskResult result = run(new ObtainTask(input, new LeavesAFurnace(), backpack, null, itemId -> Set.of(), left));

        assertEquals(TaskResult.Status.PARTIAL, result.status());
        assertTrue(result.summary().startsWith("还没拿到；一炉留在熔炉里烧，约 11 分钟后回来取"), result.summary());
        assertEquals(1, result.remaining().size());
        assertEquals(Problem.Kind.WRONG_TIME, result.problem().kind());
        assertEquals("装好炉先走了：(12, 64, -30) 的熔炉里在烧 64 个海带，约 11 分钟烧好；背包里还有 167 个海带没装",
                result.problem().message());
        assertTrue(result.problem().suggestion().contains("计时 7"), result.problem().suggestion());

        // LLM 看到的键：obtained_via 为空，left_cooking 只有这一场留下的那一台。
        JsonObject details = ResultJson.result(result).getAsJsonObject("details");
        assertEquals(0, details.getAsJsonArray("obtained_via").size());
        JsonArray cooking = details.getAsJsonArray("left_cooking");
        assertEquals(1, cooking.size());
        JsonObject furnace = cooking.get(0).getAsJsonObject();
        assertEquals(12, furnace.get("x").getAsInt());
        assertEquals("minecraft:overworld", furnace.get("dimension").getAsString());
        assertEquals("minecraft:furnace", furnace.get("block").getAsString());
        assertEquals(KELP, furnace.get("item").getAsString());
        assertEquals("minecraft:kelp", furnace.get("ingredient").getAsString());
        assertEquals(64, furnace.get("loaded").getAsInt());
        assertEquals(640, furnace.get("ready_in_seconds").getAsInt());
        assertEquals(167, furnace.get("ingredients_in_backpack").getAsInt());
        assertEquals(7, furnace.get("timer_id").getAsInt());
    }

    @Test
    void nothingLeftKeepsTheUsualDetails() {
        ObtainedVia via = new ObtainedVia(List.of("craft"));
        JsonObject details = ResultJson.result(TaskResult.builder(TaskResult.Status.DONE, "拿到了").details(via).build())
                .getAsJsonObject("details");
        assertFalse(details.has("left_cooking"));
    }
}
