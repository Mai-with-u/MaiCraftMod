// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.maiwithu.maicraft.behavior.acquire.mining.ChainMiningLedger;
import org.maiwithu.maicraft.behavior.inventory.spi.CarriedBackpack;
import org.maiwithu.maicraft.behavior.perception.SceneSelf;
import org.maiwithu.maicraft.behavior.perception.SelfSight;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.StackKey;
import org.maiwithu.maicraft.game.player.ReadsFoodValues;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.world.KeepInventoryRule;
import org.maiwithu.maicraft.game.world.RespawnPoint;
import org.maiwithu.maicraft.game.world.SaveIdentity;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.goal.GoalRun;
import org.maiwithu.maicraft.kernel.goal.Permissions;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * observe(self) 的样子：角色自己的状态、背包、手上在做的事和这件事的许可。
 *
 * <p>背包按物品合并（"圆石 130"），不按格子列出：LLM 关心有多少，不关心放在第几格；还剩几格空位单独给。
 */
public final class SelfView {
    private SelfView() {}

    /**
     * 预留里的一样：调用方写下要身上留够几件的东西。
     *
     * @param item    物品 ID 或 # 标签
     * @param atLeast 留够几件；全都留着时为 null
     * @param carried 身上现有几件（标签按合计）
     */
    public record Reserved(String item, Integer atLeast, int carried) {}

    /**
     * 角色是谁、在哪个世界：账号名、UUID 与所在的世界。宿主拿它认出聊天、死亡播报、场景里哪一个是角色自己，
     * 按世界编号把不同存档、服务器里记下的事分开。
     *
     * @param name     角色的账号名
     * @param uuid     角色的 UUID
     * @param world    所在的世界：编号、给人看的名字与编号从哪来，和 events 每一页带的是同一个；认不出时为 null
     * @param worldKey 过渡用：这个世界的数据在旧版本里存在哪个编号名下（接手旧数据的世界是旧编号，刚建好的是世界编号）；
     *                 旧版宿主按它当键照常能用，新版宿主看到它和世界编号不同就把这个编号名下的数据搬过去；认不出时为 null
     */
    public record Identity(String name, UUID uuid, SaveIdentity world, String worldKey) {}

    /** 把角色是谁写进自己这一栏；读不到的项不写，不编一个。 */
    public static void addIdentity(JsonObject view, Identity identity) {
        if (identity == null) return;
        if (identity.name() != null) view.addProperty("name", identity.name());
        if (identity.uuid() != null) view.addProperty("uuid", identity.uuid().toString());
        if (identity.world() != null) view.add("world", WorldIdentityView.of(identity.world()));
        if (identity.worldKey() != null) view.addProperty("world_key", identity.worldKey());
    }

    /**
     * 死一次会怎样：东西掉不掉、会在哪重生。LLM 看着它和血、饱食度，自己决定要不要寻死回家。
     *
     * @param keepInventory 死亡不掉落：{@code on} / {@code off} / 别人的服务器读不到 {@code unknown}；规则还在读为 null
     * @param respawn       会在哪重生；刚进世界什么都读不到为 null
     */
    public record AfterDeath(String keepInventory, Respawn respawn) {}

    /**
     * 会在哪重生。
     *
     * @param dimension 维度 ID；记忆里的床没写维度时为 null
     * @param source    {@code bed} / {@code respawn_anchor} / {@code command}（/spawnpoint 设的） / {@code world_spawn}
     * @param confirmed true：单人世界、局域网主机在服务端读到的；false：别人的服务器不告诉客户端，是推断
     */
    public record Respawn(int x, int y, int z, String dimension, String source, boolean confirmed) {}

    /**
     * 拼出死一次会怎样：死亡不掉落读到什么写什么，还在读就不写；
     * 重生点本机读到真实的就用它（还在读就不写），别人的服务器按最近睡过的床推断，没睡过就是世界出生点。
     *
     * @param lastBed    世界记忆里最近一次睡过的床
     * @param worldSpawn 客户端知道的世界出生点
     */
    public static AfterDeath afterDeath(KeepInventoryRule.Reading keepInventory, RespawnPoint.Reading respawn,
            Optional<WorldPosition> lastBed, Optional<RespawnPoint.Spot> worldSpawn) {
        String keep = switch (keepInventory) {
            case ON -> "on";
            case OFF -> "off";
            case UNREADABLE -> "unknown";
            case READING -> null;
        };
        Respawn at = switch (respawn.status()) {
            case READ -> respawn(respawn.spot(), true);
            case READING -> null;
            case UNREADABLE -> lastBed.map(bed -> new Respawn(bed.x(), bed.y(), bed.z(), bed.dimension(), "bed", false))
                    .orElseGet(() -> worldSpawn.map(spawn -> respawn(spawn, false)).orElse(null));
        };
        return new AfterDeath(keep, at);
    }

    private static Respawn respawn(RespawnPoint.Spot spot, boolean confirmed) {
        return new Respawn(spot.x(), spot.y(), spot.z(), spot.dimension(),
                spot.source().name().toLowerCase(Locale.ROOT), confirmed);
    }

    /** 把死一次会怎样写进自己这一栏：读不到的项不写，不编一个。 */
    public static void addAfterDeath(JsonObject view, AfterDeath afterDeath) {
        if (afterDeath == null) return;
        if (afterDeath.keepInventory() != null) view.addProperty("keep_inventory", afterDeath.keepInventory());
        Respawn respawn = afterDeath.respawn();
        if (respawn == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("x", respawn.x());
        json.addProperty("y", respawn.y());
        json.addProperty("z", respawn.z());
        if (respawn.dimension() != null) json.addProperty("dimension", respawn.dimension());
        json.addProperty("source", respawn.source());
        json.addProperty("confirmed", respawn.confirmed());
        view.add("respawn", json);
    }

    /** 把预留写进自己这一栏：每样留几件、身上几件；全都留着写 all。没有预留时不写，免得多一项空数组。 */
    public static void addReserved(JsonObject view, List<Reserved> reserved) {
        if (reserved.isEmpty()) return;
        JsonArray list = new JsonArray();
        for (Reserved entry : reserved) {
            JsonObject json = new JsonObject();
            json.addProperty("item", entry.item());
            if (entry.atLeast() == null) json.addProperty("all", true);
            else json.addProperty("at_least", entry.atLeast());
            json.addProperty("carried", entry.carried());
            list.add(json);
        }
        view.add("reserved", list);
    }

    /**
     * 把角色身上背着或揣着的随身背包写进自己这一栏：穿在 Curios 等非原版栏位时也能看到，
     * 五分钟内打开过的附上主存储剩余通用空槽数与看到的物品计数；身上没带随身背包时不写这一项。
     */
    public static void addCarriedBackpacks(JsonObject view, List<CarriedBackpack.CarriedPack> packs) {
        if (packs == null || packs.isEmpty()) return;
        JsonArray list = new JsonArray();
        for (CarriedBackpack.CarriedPack pack : packs) {
            JsonObject json = new JsonObject();
            json.addProperty("item", pack.item());
            json.addProperty("where", pack.where());
            json.addProperty("seen", pack.seen());
            if (pack.seen()) {
                json.addProperty("free_slots", pack.freeSlots());
                JsonArray contents = new JsonArray();
                pack.contents().forEach((item, count) -> {
                    JsonObject stack = new JsonObject();
                    stack.addProperty("item", item);
                    stack.addProperty("count", count);
                    contents.add(stack);
                });
                json.add("contents", contents);
            }
            list.add(json);
        }
        view.add("carried_backpacks", list);
    }

    /** 当前世界真正就绪可用的模组能力（按字母排；空数组也原样保留）。 */
    public static void addModFeatures(JsonObject view, List<String> modFeatures) {
        JsonArray list = new JsonArray();
        if (modFeatures != null) {
            modFeatures.stream().sorted().forEach(list::add);
        }
        view.add("mod_features", list);
    }

    /** 本世界最近一次挖掘动作的连锁统计；本世界还没挖过方块时不写这一项。 */
    public static void addLastChainMining(JsonObject view, ChainMiningLedger.Summary summary) {
        if (summary == null) return;
        JsonObject json = new JsonObject();
        json.addProperty("chained_batches", summary.chainedBatches());
        json.addProperty("chained_blocks", summary.chainedBlocks());
        json.addProperty("single_blocks", summary.singleBlocks());
        json.add("not_chained_reason", summary.notChainedReason() == null
                ? JsonNull.INSTANCE : new JsonPrimitive(summary.notChainedReason()));
        view.add("last_chain_mining", json);
    }

    /**
     * @param dimension 所在维度；读不到时为 null
     * @param backpack  背包视图；读不到时为 null
     * @param mainGoal  手上的主任务；没有时为 null
     * @param doing     主任务此刻在做什么的一句话；没有主任务时是身体被哪件临时任务占着的说明，真闲着为 null
     * @param automationControls 自动化此刻是否拿着角色；读不到时为 null
     * @param playerTookOver     玩家按 F8 收回了角色、还没交回
     */
    public static JsonObject of(SceneSelf self, String dimension, BackpackView backpack, GoalRun mainGoal, String doing,
                                Boolean automationControls, boolean playerTookOver) {
        return of(self, dimension, backpack, null, mainGoal, doing, automationControls, playerTookOver);
    }

    /** @param foods 读食物数值；读不到为 null，背包里的吃的就不写补多少 */
    public static JsonObject of(SceneSelf self, String dimension, BackpackView backpack, ReadsFoodValues foods,
                                GoalRun mainGoal, String doing, Boolean automationControls, boolean playerTookOver) {
        JsonObject json = new JsonObject();
        JsonObject position = new JsonObject();
        position.addProperty("x", Math.round(self.x() * 10) / 10.0);
        position.addProperty("y", Math.round(self.y() * 10) / 10.0);
        position.addProperty("z", Math.round(self.z() * 10) / 10.0);
        if (dimension != null) position.addProperty("dimension", dimension);
        json.add("position", position);
        json.addProperty("facing", self.facingWord());
        // 血量保留一位小数、向上取：还活着就不会显示成 0。
        json.addProperty("health", self.shownHealth());
        json.addProperty("food", self.food());
        json.addProperty("air", self.air());
        json.addProperty("max_air", self.maxAir());
        json.addProperty("on_ground", self.onGround());
        json.addProperty("in_water", self.inWater());
        if (self.heldItem() != null) json.addProperty("held", self.heldItem());
        // 副手拿着的单列：盾、火把这类常备的，或者顺手攥着的零碎，整理背包时一眼看得出来。
        if (self.offhandItem() != null) json.addProperty("offhand", self.offhandItem());
        JsonArray armor = new JsonArray();
        self.armor().forEach(armor::add);
        json.add("armor", armor);
        JsonArray effects = new JsonArray();
        self.effects().forEach(effects::add);
        json.add("effects", effects);
        // 装备与手里东西自己画的那条：耐久、背罐的气、电量，物品不画条的不列。
        if (!self.gearBars().isEmpty()) {
            JsonArray bars = new JsonArray();
            for (SelfSight.GearBar bar : self.gearBars()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("slot", bar.slot());
                entry.addProperty("item", bar.item());
                entry.addProperty("bar_percent", bar.percent());
                bars.add(entry);
            }
            json.add("gear_bars", bars);
        }
        if (backpack != null) {
            json.add("inventory", inventory(backpack, foods));
            json.addProperty("free_slots", backpack.freeSlots());
        }
        // 谁在操作角色：在玩家手上时主任务不推进。玩家按 F8 收回的，要等玩家再按 F8 交回，
        // 重新下达也抢不回来，单独写明，免得 LLM 反复下达。
        if (automationControls != null) {
            json.addProperty("control", automationControls ? "automation" : "player");
            if (!automationControls && playerTookOver) {
                json.addProperty("player_took_over", true);
            }
        }
        json.add("goal", goal(mainGoal, doing));
        json.add("permissions", permissions(mainGoal == null ? Permissions.DEFAULT : mainGoal.goal().permissions()));
        return json;
    }

    // 同一样东西合成一项；药水按种类分开列，每项多写一个 potion：几瓶长得一样的药水分得清哪瓶是哪种。
    private static JsonArray inventory(BackpackView backpack, ReadsFoodValues foods) {
        Map<StackKey, Integer> merged = new LinkedHashMap<>();
        for (BackpackStack stack : backpack.stacks()) {
            merged.merge(StackKey.of(stack), stack.count(), Integer::sum);
        }
        JsonArray list = new JsonArray();
        merged.forEach((key, count) -> {
            String item = key.itemId();
            JsonObject entry = new JsonObject();
            entry.addProperty("item", item);
            if (key.potion() != null) entry.addProperty("potion", key.potion());
            entry.addProperty("count", count);
            // 吃的写上吃一个补多少、可能带什么效果：模组食物认不全，挑哪样吃、要不要先烤看这一项。
            if (foods != null) foods.of(item).ifPresent(food -> entry.add("food", food(food)));
            list.add(entry);
        });
        return list;
    }

    private static JsonObject food(ReadsFoodValues.FoodValue value) {
        JsonObject food = new JsonObject();
        food.addProperty("nutrition", value.nutrition());
        food.addProperty("saturation", Math.round(value.saturation() * 10) / 10.0);
        food.addProperty("eat_seconds", Math.round(value.eatSeconds() * 10) / 10.0);
        if (value.canAlwaysEat()) food.addProperty("edible_when_full", true);
        if (!value.effects().isEmpty()) {
            JsonArray effects = new JsonArray();
            for (ReadsFoodValues.FoodEffect effect : value.effects()) {
                effects.add(effect.name() + (effect.beneficial() ? "（有益）" : "（有害）"));
            }
            food.add("effects", effects);
        }
        return food;
    }

    /**
     * 手上在做的目标（主任务）一句话；没有主任务时，身体被生存需求的临时任务占着就说被哪件占着，
     * 真闲着才说"空闲"，免得 LLM 以为没读到。
     */
    private static JsonObject goal(GoalRun mainGoal, String doing) {
        JsonObject json = new JsonObject();
        if (mainGoal == null) {
            json.addProperty("doing", doing != null ? doing : "空闲，没有在做的目标");
            return json;
        }
        json.addProperty("goal_id", mainGoal.id());
        json.addProperty("ability", mainGoal.goal().ability());
        json.addProperty("state", SceneView.lower(mainGoal.state()));
        if (doing != null) json.addProperty("doing", doing);
        return json;
    }

    /** 当前许可：主任务带的，没有主任务时是默认的"像正常玩家一样"。不是游戏或服务器的 OP 权限。 */
    private static JsonObject permissions(Permissions permissions) {
        JsonObject json = new JsonObject();
        json.addProperty("change_blocks", SceneView.lower(permissions.changeBlocks()));
        json.addProperty("fight", SceneView.lower(permissions.fight()));
        json.addProperty("use_rare_items", permissions.useRareItems());
        json.addProperty("kill_animals", SceneView.lower(permissions.killAnimals()));
        json.addProperty("survival_needs", SceneView.lower(permissions.survivalNeeds()));
        JsonArray landmarks = new JsonArray();
        permissions.protectedLandmarks().stream().sorted().forEach(landmarks::add);
        json.add("protected_landmarks", landmarks);
        return json;
    }
}
