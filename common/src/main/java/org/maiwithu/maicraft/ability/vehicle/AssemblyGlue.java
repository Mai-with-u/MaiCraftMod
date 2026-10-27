// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 组装前补胶：物理组装器落在哪份机器档案里，那份档案的方块还缺哪几框胶，一框框交给会装它的机器类型去粘。
 * 胶怎么框、框得合不合规矩是模组的事（机械动力的强力胶），这里只问、只交，不认识任何一种胶。
 * 组装器不在任何档案里时不知道整艘船是哪些方块，就不补，组装照旧直接拉杆。
 */
final class AssemblyGlue {

    private final ArchivedMachines archived;
    private final List<MachineType> machineTypes;
    private final Supplier<String> dimension;

    /**
     * @param archived     已建档的机器，按一格找它属于哪一台
     * @param machineTypes 各联动的机器类型：谁答得上"还缺哪几段"、谁肯装那一段
     * @param dimension    角色此刻所在的维度 ID
     */
    AssemblyGlue(ArchivedMachines archived, List<MachineType> machineTypes, Supplier<String> dimension) {
        this.archived = Objects.requireNonNull(archived, "archived");
        this.machineTypes = List.copyOf(machineTypes);
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    /**
     * 一次查看的结论。
     *
     * @param archive 组装器所在的机器档案名；不在任何档案里为 null
     * @param blocks  那份档案声明要放方块的格（世界坐标）；没有档案为空
     * @param missing 还缺的胶段；没有联动答得上为空（不知道），都粘好了为空表
     */
    record Check(String archive, Set<BlockPos> blocks, Optional<List<Installation>> missing) {
        Check {
            blocks = Set.copyOf(blocks);
        }
    }

    /** 组装器在这一格时，整艘船的胶盖全没有。 */
    Check check(BlockPos assembler) {
        Optional<ArchivedMachines.Machine> machine = archived.covering(dimension.get(), assembler);
        if (machine.isEmpty()) return new Check(null, Set.of(), Optional.empty());
        return new Check(machine.get().name(), machine.get().blocks(), missing(machine.get().blocks()));
    }

    /** 这些方块还缺的胶段：第一家答得上的机器类型说了算；都答不上为空。 */
    Optional<List<Installation>> missing(Set<BlockPos> blocks) {
        for (MachineType type : machineTypes) {
            Optional<List<Installation>> answer = type.stickTogether(blocks);
            if (answer.isPresent()) return answer;
        }
        return Optional.empty();
    }

    /** 粘这一段的动作：肯装的第一家给的；都不肯为空。 */
    Optional<Action> install(Installation segment, Permissions permissions) {
        for (MachineType type : machineTypes) {
            Optional<Action> offered = type.install(segment, permissions);
            if (offered.isPresent()) return offered;
        }
        return Optional.empty();
    }

    /** 这一段此刻粘上了没有：有一家机器类型认它装好了就算。 */
    boolean installed(Installation segment) {
        return machineTypes.stream().anyMatch(type -> type.installed(segment));
    }

    /** 一段胶的说法："x, y, z 到 x, y, z"。 */
    static String describe(Installation segment) {
        return String.join(" 到 ", segment.cells().stream().map(BlockPos::toShortString).toList());
    }
}
