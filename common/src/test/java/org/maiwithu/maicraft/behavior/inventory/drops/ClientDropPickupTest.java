// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.inventory.drops;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.kernel.task.ActionStatus;

/**
 * 捡掉落物的收场判据：捡到过、或压根没有东西要捡，才算这一批做完；
 * 一件都没捡到又有东西没进包，如实报失败——「做完了但一件都没拿到」的空手结果不允许再出现。
 */
class ClientDropPickupTest {

    @Test
    void 捡到过就算这批做完_差的由上层按身上的清点() {
        // 砍 10 根原木捡到 9 根、剩 1 根卡在树冠里：这批照常收，还缺的上层清点后再补。
        assertInstanceOf(ActionStatus.Done.class, ClientDropPickup.conclude(9, 1, 0));
    }

    @Test
    void 没有东西要捡算做完() {
        // 挖了但没等来新掉落物（或这一下本来就不掉东西）：没什么可捡，照常收。
        assertInstanceOf(ActionStatus.Done.class, ClientDropPickup.conclude(0, 0, 0));
    }

    @Test
    void 东西全不见了又不是本玩家收的_如实报失败() {
        // 拆了方块掉出的东西全从地上消失了，服务器却没有一条拾取记到本玩家头上：
        // 被别人捡走、烧毁或消失了——不按做完收，报出去上层才会换路补或把缺如实说出来。
        ActionStatus status = ClientDropPickup.conclude(0, 2, 2);
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("2 件掉出的东西不见了"), status.toString());
    }

    @Test
    void 有件还在地上没捡到_如实报失败() {
        ActionStatus status = ClientDropPickup.conclude(0, 1, 0);
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("1 件掉出的东西没捡到"), status.toString());
    }

    @Test
    void 没捡到的件数里不见的与还在地的分得清() {
        ActionStatus status = ClientDropPickup.conclude(0, 3, 1);
        assertTrue(status instanceof ActionStatus.Failed failed
                && failed.problem().message().contains("2 件掉出的东西没捡到")
                && failed.problem().message().contains("另有 1 件不见了"), status.toString());
    }
}
