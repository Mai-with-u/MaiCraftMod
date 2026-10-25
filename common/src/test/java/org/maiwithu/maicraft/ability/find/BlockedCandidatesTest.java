// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.find;

import java.util.List;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 被挡候选的收账：配额被看不见的候选占满时不得冒充"扫完"；
 * 只有真扫完该扫的范围、且没有新的看不见候选可排除时，才承认真的没有了。
 */
class BlockedCandidatesTest {

    private static final BlockPos CENTER = new BlockPos(0, 64, 0);
    private static final List<String> TARGETS = List.of("minecraft:stone");

    private static BlockedCandidates ledger() {
        BlockedCandidates ledger = new BlockedCandidates();
        ledger.followQuery(CENTER, TARGETS, 1, 16);
        return ledger;
    }

    @Test
    void 配额满但这轮有新看不见的候选_不算扫完() {
        BlockedCandidates ledger = ledger();
        BlockPos buried = new BlockPos(2, 64, 2);
        assertFalse(ledger.settle(true, 0, 1, List.of(buried)));
        // 看不见的位置进排除名单，下一轮喂回索引让它继续往外扫。
        assertEquals(List.of(buried), List.copyOf(ledger.excluded()));
    }

    @Test
    void 扫完且没有新的看不见候选_才是可信的没有了() {
        BlockedCandidates ledger = ledger();
        BlockPos buried = new BlockPos(2, 64, 2);
        ledger.settle(true, 0, 1, List.of(buried));
        // 下一轮排除名单生效，老位置不再出现，也没有新挡下的：这时才承认扫完、确实没有。
        assertTrue(ledger.settle(true, 0, 1, List.of()));
        assertTrue(ledger.excluded().contains(buried));
    }

    @Test
    void 找满数量即算扫完() {
        BlockedCandidates ledger = ledger();
        assertTrue(ledger.settle(true, 1, 1, List.of(new BlockPos(3, 64, 3))));
    }

    @Test
    void 索引没扫完就不算扫完() {
        BlockedCandidates ledger = ledger();
        assertFalse(ledger.settle(false, 0, 1, List.of()));
    }

    @Test
    void 部分命中且扫完_如实算扫完() {
        BlockedCandidates ledger = ledger();
        assertTrue(ledger.settle(true, 1, 2, List.of()));
    }

    @Test
    void 查询变了就清账_旧排除不带进新查询() {
        BlockedCandidates ledger = ledger();
        ledger.settle(true, 0, 1, List.of(new BlockPos(2, 64, 2)));
        // 角色走动换了中心：旧站位下看不见的位置在新站位未必还看不见，账本清空重来。
        ledger.followQuery(CENTER.offset(1, 0, 0), TARGETS, 1, 16);
        assertTrue(ledger.excluded().isEmpty());
    }
}
