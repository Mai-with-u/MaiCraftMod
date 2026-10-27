// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.approach.BringsPlayerClose;
import org.maiwithu.maicraft.behavior.construction.ConstructionServices;
import org.maiwithu.maicraft.behavior.construction.ReadsBlocks;
import org.maiwithu.maicraft.kernel.goal.Permissions;

/**
 * 物理结构上的工地：组装好的飞艇、船、车（Sable 子世界）的方块不在世界坐标上，存在远处的地块里，按结构此刻的位姿换到世界。
 * 在上面施工时读格、靠近、放置预测、点击、挖都要按位姿换算；联动按结构身份给出这一份，施工引擎照常用。
 * 坐标一律是结构的地块坐标（机器能力自己再换成以原点为零的局部坐标给 LLM 看）。
 */
public interface StructureWorksites {

    /** 这个结构身份的工地；不是这种联动认得的结构、或附近看不到为空。 */
    Optional<Worksite> at(String structureId);

    /** 一个结构上的工地：每次问都按此刻现场回答，结构挪了、改了照新的答。 */
    interface Worksite {

        /** 结构身份。 */
        String structureId();

        /** 结构还在、看得到；最后一块方块拆掉后 Sable 会删掉整个结构。 */
        boolean present();

        /** 此刻在动（挪或转）。 */
        boolean moving();

        /** 结构的名字；没起名为空串。 */
        String name();

        /** 结构上的物理组装器所在的地块格；不止一台时取扫描到的第一台，没有为空。 */
        Optional<BlockPos> assembler();

        /** 地块中心：组装时收进的第一块方块落在这里，没有档案、没有组装器时拿它当原点。 */
        BlockPos plotCenter();

        /** 地块里方块范围的最小角（含）。 */
        BlockPos min();

        /** 地块里方块范围的最大角（含）。 */
        BlockPos max();

        /** 世界里一点按此刻的位姿落在地块的哪一格。 */
        BlockPos plotCellAt(Vec3 world);

        /**
         * 把地上那份施工服务换成结构上的：读格、靠近、放置预测、点击、挖换掉，记账不记临时方块（结构上不垫），
         * 没加载的地块不走过去加载，拆机器件照挖；备料、备手、许可、放置规则照用地上那份。
         */
        ConstructionServices services(ConstructionServices onLand, Permissions permissions);

        /**
         * 靠近结构上的一格：船上的甲板或船边的地上，够得着、看得见那一格（补胶这类只发一包的安装段用）；
         * 人坐在船上的座位上够不着时先下座位再挪。
         */
        BringsPlayerClose close();

        /** 同上，但人坐在座位上够不着时不离开座位、照实说够不着：改船上控制件的设置用（坐着够得着就坐着改）。 */
        BringsPlayerClose closeKeepingSeat();

        /** 读地块里的格：没加载的如实说没加载（整机比对用）。 */
        ReadsBlocks blocks();

        /** 施工改了方块：这个结构下次重新逐格读（观察、看载具照新结构读）。 */
        void forgetScan();
    }
}
