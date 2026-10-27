// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.vehicle.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 把建好、胶好的方块组装成会动的结构（或拆回方块）的一种办法：物理组装器拉一次杆、车站按组车按钮。
 * 联动模组实现它；组装能力只认这个接口，不认具体是哪个模组。
 */
public interface VehicleAssembler {

    /**
     * 一次组装或拆回的请求。
     *
     * @param assemble    组装（true）还是拆回（false）
     * @param near        组装器（或要组装的方块）大概在哪（世界坐标）；按结构指的时候是结构所在
     * @param structureId 指的是哪个结构（拆回时用；观察编号 s# 换来的结构身份）；没指结构为 null
     * @param permissions 这次的许可：别人的载具点名即许可，但保护照归属看
     */
    record Request(boolean assemble, BlockPos near, String structureId, Permissions permissions) {
        public Request {
            Objects.requireNonNull(near, "near");
            Objects.requireNonNull(permissions, "permissions");
        }
    }

    /** 这里有没有这种办法能做的组装：有就给一次运行（走过去、拉一次、确认），没有为空。 */
    Optional<Run> start(Request request);

    /**
     * 一次组装运行：做完后读结局。
     */
    interface Run extends Action {

        /** 做完后的结局；还没做完时为 null。 */
        Conversion conversion();

        /**
         * 这次要拆回的是哪个结构（开始前就认出来的）：拆回后结构就没了，档案要在拉杆之前按它读下来。
         * 组装、或说不清是哪个时为空。
         */
        default Optional<String> structure() {
            return Optional.empty();
        }

        /**
         * 组装时拉的那台物理组装器在世界里的哪一格（开始前就找到的）：组装能力拉杆前按它找机器档案、补胶。
         * 拆回、车站组车、已经是要的样子时为空。
         */
        default Optional<BlockPos> assembler() {
            return Optional.empty();
        }
    }

    /**
     * 结局：动作成没成与有没有变成结构分开说。
     *
     * @param alreadyDone 开始时就已经是要的样子（已经组装过 / 已经是方块），什么都没做
     * @param converted   拉了之后真的转换了
     * @param structureId 组装出来（或拆回前）的结构身份；没有为空串
     * @param blocks      组装进结构的方块数；拆回或不知道为 -1
     * @param note        一句经过：拉了几次、等了多久、没转换时看到的原因
     * @param assembler   转换后组装器在世界里的哪一格：组装是拉杆时它所在的格，拆回是它落回世界的格；
     *                    重新组装时按它认是不是拆回过的那一艘。不知道为 null
     * @param makeup      结构的方块清单（方块 ID → 格数）：组装是组装出来的，拆回是拆之前的；不知道为空
     * @param plotAssembler 组装器在结构地块里的格：组装是组装出来以后的，拆回是拆之前的；机器档案按它和
     *                    {@code assembler} 在结构与世界之间换算。不知道为 null
     */
    record Conversion(boolean alreadyDone, boolean converted, String structureId, int blocks, String note,
                      BlockPos assembler, Map<String, Integer> makeup, BlockPos plotAssembler) {

        public Conversion {
            makeup = Map.copyOf(makeup);
        }

        /** 不带组装器在地块里的格（只认组装器落点与方块清单的）。 */
        public Conversion(boolean alreadyDone, boolean converted, String structureId, int blocks, String note,
                BlockPos assembler, Map<String, Integer> makeup) {
            this(alreadyDone, converted, structureId, blocks, note, assembler, makeup, null);
        }

        /** 只说结局、不带组装器落点与方块清单（车站组车这类没有物理组装器的）。 */
        public Conversion(boolean alreadyDone, boolean converted, String structureId, int blocks, String note) {
            this(alreadyDone, converted, structureId, blocks, note, null, Map.of(), null);
        }
    }
}
