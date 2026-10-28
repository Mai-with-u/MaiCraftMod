// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * Sable（物理结构）的联动入口：飞艇、船、车这类会动的方块结构。交给登记表两样——寻路的障碍来源
 * （结构的逐格碰撞换到世界、装在上面的螺旋桨这类装置也算，自己站着的那艘不当墙），
 * 观察（物理结构领 s#：名字、动没动、几格方块、几个座位、是不是载具），和上下船这种出行方式。每一下读都经联动入口包一层。
 *
 * <p>逐格读一艘船要读很多格：读一次按地块坐标记下来（{@link StructureScans}），换位姿只换算不重读。
 */
public final class SableCompat extends CompatModule implements StructureReads {

    public static final String MOD_ID = "sable";
    /** 服务端只读操作：一个物理结构的受力。 */
    public static final String FORCES_OPERATION = "sable.body";
    private final SableStructures structures;
    private final StructureScans scans;

    public SableCompat(SableStructures structures) {
        this(structures, new StructureScans());
    }

    /** @param scans 逐格读的记事本，和 Simulated 的入口共用一本：在船上施工后作废一次两边都照新结构读 */
    public SableCompat(SableStructures structures, StructureScans scans) {
        super(MOD_ID, "Sable 物理结构");
        this.structures = Objects.requireNonNull(structures, "structures");
        this.scans = Objects.requireNonNull(scans, "scans");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 会动的船、飞艇当寻路障碍；观察里列物理结构（s#）。
        registry.obstacleSource(this, new SableObstacles(this));
        registry.modSight(this, new SableSight(this));
        // 上下船（via=board）：站在船上先下船、目的地在船上先上船，寻路看不见船上的方块，这一段非它不可。
        registry.travelMode(this, services -> new DeckMode(this, services));
        // 受力只在服务端：看载具的受力经只读操作 sable.body 问（质量、重心、各处的力，两种情况与配重建议）。
        registry.serverRead(this, FORCES_OPERATION, 1);
        // 停稳的结构上施工（machine_build 指 s#）：读格、上船或站到船边去够、按结构的轴放、对着结构上的格点和挖。
        registry.structureWorksites(this, services -> new SableWorksites(this, services));
    }

    /** 和一个区域沾边的物理结构；见 {@link SableStructures#near}。 */
    @Override public List<SableStructures.Structure> structures(ClientLevel level, Player viewer, AABB region) {
        return call("读物理结构", () -> structures.near(level, viewer, region));
    }

    /** 结构地块里一格的方块；见 {@link SableStructures#block}。 */
    @Override public BlockState block(ClientLevel level, String id, BlockPos local) {
        return call("读物理结构的方块", () -> structures.block(level, id, local));
    }

    /** 装在结构上的装置此刻在世界里的范围；见 {@link SableStructures#attachedEntities}。 */
    public List<AABB> attachedEntities(ClientLevel level, String id) {
        return call("读物理结构上的装置", () -> structures.attachedEntities(level, id));
    }

    /** 一个结构的逐格读结果；见 {@link StructureScans}。 */
    @Override public StructureGeometry.Scan scan(ClientLevel level, SableStructures.Structure structure) {
        return scans.scan(level.getGameTime(), structure, local -> block(level, structure.id(), local),
                StructureGeometry.WingRules.of(this::liftDirection));
    }

    // 认机翼时问 Sable 这块方块装在物理结构上产不产生升力、升力朝哪；读失败按不产生。
    private Optional<Direction> liftDirection(BlockState state) {
        Optional<Direction> direction = call("认机翼", () -> structures.liftDirection(state));
        return direction == null ? Optional.empty() : direction;
    }

    /** 这个结构下次重读（在船上施工改了方块）。 */
    void forget(String id) {
        scans.forget(id);
    }
}
