// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.world.DropConfirmations;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 捡掉落物的两个判据：贴身的东西按原版拾取范围站着就能吸进包，不必为它寻路；
 * 收场判据上，捡到过、或压根没有东西要捡，才算这一批做完，一件都没捡到又有东西没进包时如实报失败，
 * 并把放弃时的现场（离几格、怎么失败的）带给上层——「做完了但一件都没拿到」的空手结果与
 * 一句「走不过去」盖掉矛盾的说法都不允许再出现。
 */
class ClientDropPickupTest {

    private static DropConfirmations.Pickup pickup(long cursor, long tick, int entityId, int amount, String itemId) {
        return new DropConfirmations.Pickup(cursor, tick, UUID.nameUUIDFromBytes(("e" + entityId).getBytes()), entityId,
                amount, itemId);
    }

    @Test
    void 进包的只认对得上那几件的拾取包_同种合在一起() {
        // 玩家甲扔来的两件（实体 7、8）落地后自己进了包；角色自己挖的那件（实体 9）不算扔来的。
        List<DropConfirmations.Pickup> pickups = List.of(pickup(1, 100, 7, 2, "minecraft:iron_ingot"),
                pickup(2, 101, 8, 1, "minecraft:iron_ingot"), pickup(3, 102, 9, 5, "minecraft:cobblestone"));
        assertEquals(Map.of("minecraft:iron_ingot", 3),
                ClientDropPickup.pickedUpAmong(pickups, Map.of(7, 90L, 8, 95L), Long.MIN_VALUE));
    }

    @Test
    void 落地之前的拾取不算这一件() {
        List<DropConfirmations.Pickup> pickups = List.of(pickup(1, 80, 7, 2, "minecraft:iron_ingot"));
        assertTrue(ClientDropPickup.pickedUpAmong(pickups, Map.of(7, 90L), Long.MIN_VALUE).isEmpty());
    }

    @Test
    void 连着叫捡两回_第二回不把上回报过的再报一遍() {
        List<DropConfirmations.Pickup> pickups = new ArrayList<>(List.of(pickup(4, 100, 7, 2, "minecraft:iron_ingot")));
        Map<Integer, Long> tossed = Map.of(7, 90L, 8, 150L);
        assertEquals(Map.of("minecraft:iron_ingot", 2), ClientDropPickup.pickedUpAmong(pickups, tossed, Long.MIN_VALUE));
        // 上一回收场时序号是 4：第二回只认 4 之后的，实体 7 那一包不再报。
        assertTrue(ClientDropPickup.pickedUpAmong(pickups, tossed, 4).isEmpty());
        // 之后又扔来一件（实体 8）进了包：照报这一件。
        pickups.add(pickup(5, 160, 8, 1, "minecraft:bread"));
        assertEquals(Map.of("minecraft:bread", 1), ClientDropPickup.pickedUpAmong(pickups, tossed, 4));
    }

    @Test
    void 捡到过就算这批做完_差的由上层按身上的清点() {
        // 砍 10 根原木捡到 9 根、剩 1 根卡在树冠里：这批照常收，还缺的上层清点后再补。
        assertInstanceOf(ActionStatus.Done.class, ClientDropPickup.conclude(9, 1, 0, List.of()));
    }

    @Test
    void 没有东西要捡算做完() {
        // 挖了但没等来新掉落物（或这一下本来就不掉东西）：没什么可捡，照常收。
        assertInstanceOf(ActionStatus.Done.class, ClientDropPickup.conclude(0, 0, 0, List.of()));
    }

    @Test
    void 东西全不见了又不是本玩家收的_如实报失败() {
        // 拆了方块掉出的东西全从地上消失了，服务器却没有一条拾取记到本玩家头上：
        // 被别人捡走、烧毁或消失了——不按做完收，报出去上层才会换路补或把缺如实说出来。
        ActionStatus status = ClientDropPickup.conclude(0, 2, 2, List.of());
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("2 件掉出的东西不见了"), status.toString());
    }

    @Test
    void 有件还在地上没捡到_如实报失败() {
        ActionStatus status = ClientDropPickup.conclude(0, 1, 0, List.of());
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("1 件掉出的东西没捡到"), status.toString());
    }

    @Test
    void 没捡到的件数里不见的与还在地的分得清() {
        ActionStatus status = ClientDropPickup.conclude(0, 3, 1, List.of());
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("2 件掉出的东西没捡到")
                && failed.problem().message().contains("另有 1 件不见了"), status.toString());
    }

    @Test
    void 没捡到的收场话带上放弃时的现场() {
        // attempts 只剩一句「走不过去」会跟现场矛盾：离几格、走过去为什么失败要跟着说出去。
        ActionStatus status = ClientDropPickup.conclude(0, 1, 0,
                List.of("离它 1.6 格，走过去失败：没有能走到目标的路线"));
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("离它 1.6 格")
                && failed.problem().message().contains("没有能走到目标的路线"), status.toString());
    }

    // —— 原版拾取范围（纯函数）：玩家包围盒外扩横向 1.0、纵向 0.5 后与掉落物包围盒相交就吸进包 ——

    private static final double PX = 10.5, FEET_Y = 64.0, PZ = 10.5;

    @Test
    void 挖下的东西落在旁边一格_站着就能吸进包_不必寻路() {
        // 掉落物落在脚边相邻格的地面上：原版每刻都会把它吸进来，走一趟寻路反而可能按「走不过去」放弃。
        assertTrue(ClientDropPickup.withinPickupReach(PX, FEET_Y, PZ, 11.4, 64.3, 10.5));
    }

    @Test
    void 贴身斜对角_原版也吸得着() {
        assertTrue(ClientDropPickup.withinPickupReach(PX, FEET_Y, PZ, 11.5, 64.3, 11.5));
    }

    @Test
    void 两格外吸不着_要走过去捡() {
        assertFalse(ClientDropPickup.withinPickupReach(PX, FEET_Y, PZ, 12.7, 64.3, 10.5));
    }

    @Test
    void 东西在脚下一格的浅坑里_站边上吸不着() {
        // 纵向不在拾取盒里：站着干等不会自己吸进来，得想别的办法够到它。
        assertFalse(ClientDropPickup.withinPickupReach(PX, FEET_Y, PZ, 10.5, 63.2, 10.5));
    }

    @Test
    void 东西悬在头顶一格_也算在拾取范围里() {
        assertTrue(ClientDropPickup.withinPickupReach(PX, FEET_Y, PZ, 10.5, 65.2, 10.5));
    }

    @Test
    void 服务器说收下了背包还没涨上来_不算跟上() {
        // 贴身等吸一块煤：拾取包先到、背包格子下一刻才同步，此刻身上还是 2 块，确认后该有 3 块。
        assertFalse(ClientDropPickup.inventoryCaughtUp(Map.of("minecraft:coal", 3), Map.of("minecraft:coal", 2)));
        assertFalse(ClientDropPickup.inventoryCaughtUp(Map.of("minecraft:coal", 1), Map.of()));
    }

    @Test
    void 背包涨到确认的数就算跟上() {
        assertTrue(ClientDropPickup.inventoryCaughtUp(Map.of("minecraft:coal", 3), Map.of("minecraft:coal", 3)));
        assertTrue(ClientDropPickup.inventoryCaughtUp(Map.of(), Map.of()));
    }
}
