// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.mekanism;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.maiwithu.maicraft.ability.machine.spi.FuelUse;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;

/**
 * 通用机械里认得的机器与管道：每一种的编号、中文名、在机器里干什么、由哪几种方块组成（四个等级的同一种算一种），
 * machine_run 给的 item 对它是什么（发电机烧燃料或不吃料，别的按配方）。
 * 编号就是主方块的注册 ID，也是配方查看器里这道工序的工作站物品 ID。没列的、能配侧面的 Mekanism 机器
 * 由"其他 Mekanism 机器"兜住，至少看得出侧面配置。发电机在通用机械发电机这个附属模组里，没装时认不出它的方块。
 */
final class MekanismMachineCatalog {

    /** 一种机器或管道。 */
    record Kind(String id, String name, MachineRole role, List<String> blocks, FuelUse fuel) {
        Kind {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(role, "role");
            blocks = List.copyOf(blocks);
            Objects.requireNonNull(fuel, "fuel");
        }

        /** 按配方投料的一种。 */
        Kind(String id, String name, MachineRole role, List<String> blocks) {
            this(id, name, role, blocks, FuelUse.RECIPE);
        }
    }

    static final String MOD_ID = "mekanism";
    /** 通用机械发电机（附属模组）的模组 ID：发电机方块与界面都在它名下。 */
    static final String GENERATORS_MOD_ID = "mekanismgenerators";

    private MekanismMachineCatalog() {
    }

    /** 认得的全部机器与管道。 */
    static List<Kind> kinds() {
        return List.of(
                tiered("energy_cube", "能量立方", MachineRole.STORAGE, true),
                tiered("universal_cable", "通用线缆", MachineRole.CABLE, false),
                tiered("mechanical_pipe", "机械管道", MachineRole.CABLE, false),
                tiered("pressurized_tube", "加压管道", MachineRole.CABLE, false),
                withExtras(tiered("logistical_transporter", "物流传输管道", MachineRole.LOGISTICS, false),
                        "restrictive_transporter", "diversion_transporter"),
                single("metallurgic_infuser", "冶金灌注机", MachineRole.PROCESSING),
                single("enrichment_chamber", "富集仓", MachineRole.PROCESSING),
                single("crusher", "粉碎机", MachineRole.PROCESSING),
                single("osmium_compressor", "锇压缩机", MachineRole.PROCESSING),
                single("logistical_sorter", "物流分拣器", MachineRole.LOGISTICS),
                // 发电机：热能、生物、燃气三种有燃料格，machine_run 把 item 当燃料投；太阳能、风力不吃料。
                // 收什么燃料是燃料格自己的判断（TileEntityHeatGenerator 等的燃料格），这里只给 LLM 看的说明。
                generator("heat_generator", "热能发电机", FuelUse.fuel(
                        "能在熔炉里烧的东西（煤、木炭、木头……）或岩浆桶；岩浆也能用机械管道灌，贴着岩浆方块、在下界时还会多发一点")),
                generator("bio_generator", "生物发电机", FuelUse.fuel(
                        "生物燃料、生物燃料块，或装着生物乙醇的桶与储罐；生物乙醇也能用机械管道灌")),
                generator("gas_burning_generator", "燃气发电机", FuelUse.fuel(
                        "装着氢气、乙烯这类可燃化学品的储罐类物品；平时用加压管道直接送")),
                generator("solar_generator", "太阳能发电机", FuelUse.noFuel("靠阳光发电：白天、头顶露天没遮挡才发")),
                generator("advanced_solar_generator", "高级太阳能发电机",
                        FuelUse.noFuel("靠阳光发电：白天、头顶露天没遮挡才发")),
                generator("wind_generator", "风力发电机", FuelUse.noFuel("靠风发电：放得越高发得越多，上方要空出来")));
    }

    private static Kind generator(String id, String name, FuelUse fuel) {
        String block = GENERATORS_MOD_ID + ":" + id;
        return new Kind(block, name, MachineRole.POWER_SOURCE, List.of(block), fuel);
    }

    private static Kind single(String id, String name, MachineRole role) {
        return new Kind(MOD_ID + ":" + id, name, role, List.of(MOD_ID + ":" + id));
    }

    // 基础、高级、精英、终极四个等级（能量立方还有创造）的同一种算一种，编号用基础那一级。
    private static Kind tiered(String id, String name, MachineRole role, boolean creative) {
        List<String> blocks = new ArrayList<>();
        for (String tier : List.of("basic", "advanced", "elite", "ultimate")) blocks.add(MOD_ID + ":" + tier + "_" + id);
        if (creative) blocks.add(MOD_ID + ":creative_" + id);
        return new Kind(MOD_ID + ":basic_" + id, name, role, blocks);
    }

    private static Kind withExtras(Kind kind, String... extras) {
        List<String> blocks = new ArrayList<>(kind.blocks());
        for (String extra : extras) blocks.add(MOD_ID + ":" + extra);
        return new Kind(kind.id(), kind.name(), kind.role(), blocks, kind.fuel());
    }
}
