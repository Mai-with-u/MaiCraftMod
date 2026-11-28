// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.ae2.inscriber;

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

    /**
     * 压印器同步到客户端的全部四个工作格快照（含数量，空槽为 null）：
     * 0 顶模板格、1 底模板格、2 中间输入格、3 输出格。
     */
    record Slots(MachineState.Shown top, MachineState.Shown bottom,
            MachineState.Shown middle, MachineState.Shown output) {

        public Slots {
            top = normalize(top);
            bottom = normalize(bottom);
            middle = normalize(middle);
            output = normalize(output);
        }

        private static MachineState.Shown normalize(MachineState.Shown slot) {
            return slot == null || slot.itemId().isEmpty() || slot.count() <= 0 ? null : slot;
        }

        /** 转换成前三个投入工作格的物品 ID 快照。 */
        public Inputs toInputs() {
            return new Inputs(
                    top == null ? "" : top.itemId(),
                    bottom == null ? "" : bottom.itemId(),
                    middle == null ? "" : middle.itemId());
        }

        /** 按格号（0 顶、1 底、2 中间、3 输出）取单格快照；空槽或越界返回 null。 */
        public MachineState.Shown slot(int index) {
            return switch (index) {
                case 0 -> top;
                case 1 -> bottom;
                case 2 -> middle;
                case 3 -> output;
                default -> null;
            };
        }

        /** 按顶、底、中间、输出顺序列出当前所有非空工作格的物品与数量。 */
        public List<MachineState.Shown> allShown() {
            List<MachineState.Shown> list = new ArrayList<>(4);
            if (top != null) list.add(top);
            if (bottom != null) list.add(bottom);
            if (middle != null) list.add(middle);
            if (output != null) list.add(output);
            return List.copyOf(list);
        }

        /** 四个工作格里当前非空的物品 ID 列表（去重保序）。 */
        public List<String> occupiedIds() {
            List<String> ids = new ArrayList<>(4);
            for (MachineState.Shown shown : allShown()) {
                if (!ids.contains(shown.itemId())) ids.add(shown.itemId());
            }
            return List.copyOf(ids);
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
     * 这一格压印器客户端同步到的全部四个工作格（0 顶、1 底、2 中间、3 输出）的物品与数量；不是压印器、没加载给空。
     */
    default Optional<Slots> slots(ClientLevel level, BlockPos at) {
        Optional<Inputs> in = inputs(level, at);
        Optional<List<MachineState.Shown>> out = output(level, at);
        if (in.isEmpty() && out.isEmpty()) return Optional.empty();
        Inputs three = in.orElseGet(() -> new Inputs("", "", ""));
        MachineState.Shown outSlot = out.filter(list -> !list.isEmpty()).map(List::getFirst).orElse(null);
        return Optional.of(new Slots(
                three.top().isEmpty() ? null : new MachineState.Shown(three.top(), 1),
                three.bottom().isEmpty() ? null : new MachineState.Shown(three.bottom(), 1),
                three.middle().isEmpty() ? null : new MachineState.Shown(three.middle(), 1),
                outSlot));
    }

    /** 离线测试用：直接给出半径内已知的压印器坐标；生产侧走方块扫描服务，默认返回空表。 */
    default List<BlockPos> near(ClientLevel level, BlockPos center, int radius) {
        return List.of();
    }

    /**
     * 这一格压印器的顶、底两个模板格（0、1 格）里当前非空的物品 ID 列表；不是压印器、没加载给空。
     */
    default Optional<List<String>> plates(ClientLevel level, BlockPos at) {
        return inputs(level, at).map(Inputs::plates);
    }
}
