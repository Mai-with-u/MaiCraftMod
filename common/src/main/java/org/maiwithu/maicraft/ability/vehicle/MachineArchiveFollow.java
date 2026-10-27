// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.world.level.block.Rotation;

import org.maiwithu.maicraft.ability.machine.api.ArchivedMachines;
import org.maiwithu.maicraft.ability.vehicle.spi.VehicleAssembler;

/**
 * 机器档案（machine_build 建的那份）跟着结构走：组装出新结构时，组装器所在的那份档案平移进结构的地块；
 * 拆回时，记在这个结构上的档案按组装器的落点和转的整 90° 跟回世界。和操纵表在同一次组装、拆回里一起跟，
 * 两份档案不会一份在船上一份在地上。档案怎么换算由机器能力做，这里只在转换之后递事实、照结局写一句话。
 */
final class MachineArchiveFollow {

    private final ArchivedMachines machines;
    private final Supplier<String> dimension;

    MachineArchiveFollow(ArchivedMachines machines, Supplier<String> dimension) {
        this.machines = Objects.requireNonNull(machines, "machines");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    /**
     * 组装转换了：组装器在世界里那一格落在哪份档案里，那份跟到新结构上。组装器不在任何机器档案里、
     * 不是物理组装器组的（没有组装器的两头）为 null。
     */
    VehicleAssembleDetails.MachineArchiveMove afterAssemble(VehicleAssembler.Conversion conversion) {
        if (conversion.assembler() == null || conversion.plotAssembler() == null) return null;
        Optional<ArchivedMachines.Moved> moved = machines.moveOntoStructure(dimension.get(), conversion.assembler(),
                conversion.structureId(), conversion.plotAssembler());
        return moved.map(found -> found.saved()
                ? new VehicleAssembleDetails.MachineArchiveMove(found.name(), VehicleAssembleDetails.ON_STRUCTURE,
                        "机器档案「" + found.name() + "」跟到结构上，蓝图偏移不变，在船上施工按它的锚点算")
                : new VehicleAssembleDetails.MachineArchiveMove(found.name(), VehicleAssembleDetails.KEPT,
                        "机器档案「" + found.name() + "」没存进去，还记在世界里")).orElse(null);
    }

    /**
     * 拆回转换了：记在这个结构上的机器档案跟回世界。拆回以组装器为轴转整 90°，转多少按拆之前结构自己的 +z
     * 朝哪算；拆前没读到朝向、或不知道组装器落在哪，就留在结构身份下照实说。结构上没记机器档案为 null。
     *
     * @param heading 拆之前结构自己的 +z 朝哪（度：0 南、90 西、180 北、270 东）；没读到为 null
     */
    VehicleAssembleDetails.MachineArchiveMove afterDisassemble(String structureId, Double heading,
            VehicleAssembler.Conversion conversion) {
        if (conversion.plotAssembler() == null) return null;
        if (conversion.assembler() == null || heading == null) {
            // 换算不了就不动档案：结构上记着档案才说没跟成、为什么，没记就不提。
            String why = conversion.assembler() == null ? "拆回后没找到组装器落在哪" : "拆前没读到结构朝哪";
            return machines.onStructure(structureId).map(name -> new VehicleAssembleDetails.MachineArchiveMove(name,
                    VehicleAssembleDetails.KEPT, why + "，机器档案「" + name + "」留在结构身份下")).orElse(null);
        }
        Rotation turn = turnOf(heading);
        Optional<ArchivedMachines.Moved> moved = machines.moveToWorld(structureId, conversion.plotAssembler(),
                conversion.assembler(), turn);
        return moved.map(found -> found.saved()
                ? new VehicleAssembleDetails.MachineArchiveMove(found.name(), VehicleAssembleDetails.IN_WORLD,
                        "机器档案「" + found.name() + "」跟回世界，组装器落在 " + conversion.assembler().toShortString()
                                + (turn == Rotation.NONE ? "" : "，蓝图跟着转了 " + degrees(turn) + "°"))
                : new VehicleAssembleDetails.MachineArchiveMove(found.name(), VehicleAssembleDetails.KEPT,
                        "机器档案「" + found.name() + "」没存进去，还记在结构身份下")).orElse(null);
    }

    /** 结构自己的 +z 朝哪换成拆回时转的整 90°：朝西是顺时针转了一下，朝北两下，朝东逆时针一下。 */
    static Rotation turnOf(double heading) {
        return switch ((int) Math.floorMod(Math.round(heading / 90.0), 4L)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static int degrees(Rotation turn) {
        return switch (turn) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }
}
