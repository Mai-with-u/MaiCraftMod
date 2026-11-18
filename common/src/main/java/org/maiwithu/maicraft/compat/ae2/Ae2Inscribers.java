// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.spi.MachineState;

/**
 * 读 AE2 压印器的模组读写接缝（客户端）：同步过来的四个工作格里有什么。压印器的顶模板、底模板、中间输入、输出四个工作格
 * 全都随方块实体同步给客户端画出来，站在外面就读得到；进度与有没有电不同步，读不到。读写端在 NeoForge 模块里，测试里用替身。
 */
public interface Ae2Inscribers {

    /**
     * 压印器同步到客户端的前三个投入工作格快照（不含输出格）：0 顶模板格、1 底模板格、2 中间输入格的物品 ID（空槽为空串）。
     */
    record Inputs(String top, String bottom, String middle) {
        public Inputs {
            top = top == null ? "" : top;
            bottom = bottom == null ? "" : bottom;
            middle = middle == null ? "" : middle;
        }

        /** 顶、底两个模板格里当前非空的物品 ID。 */
        public List<String> plates() {
            List<String> result = new ArrayList<>(2);
            if (!top.isEmpty()) result.add(top);
            if (!bottom.isEmpty()) result.add(bottom);
            return List.copyOf(result);
        }

        /** 顶、底、中间三个投入格里当前非空的物品 ID。 */
        public List<String> occupied() {
            List<String> result = new ArrayList<>(3);
            if (!top.isEmpty()) result.add(top);
            if (!bottom.isEmpty()) result.add(bottom);
            if (!middle.isEmpty()) result.add(middle);
            return List.copyOf(result);
        }
    }

    /** 没有读写端时用：读不出任何槽位。 */
    Ae2Inscribers NONE = new Ae2Inscribers() {
        @Override public Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at) {
            return Optional.empty();
        }
    };

    /**
     * 这一格压印器的输出槽里现在有什么；不是压印器、没加载给空，空槽给空表。
     */
    Optional<List<MachineState.Shown>> output(ClientLevel level, BlockPos at);

    /**
     * 这一格压印器的前三个投入工作格（0 顶模板、1 底模板、2 中间输入）现在放着什么；不是压印器、没加载给空。
     */
    default Optional<Inputs> inputs(ClientLevel level, BlockPos at) {
        return Optional.empty();
    }

    /**
     * 这一格压印器的顶、底两个模板格（0、1 格）里当前非空的物品 ID 列表；不是压印器、没加载给空。
     */
    default Optional<List<String>> plates(ClientLevel level, BlockPos at) {
        return inputs(level, at).map(Inputs::plates);
    }
}
