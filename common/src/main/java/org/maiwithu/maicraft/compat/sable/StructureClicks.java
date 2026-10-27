// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.sable;

import java.util.Objects;
import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.maiwithu.maicraft.behavior.construction.ConstructionSeams;
import org.maiwithu.maicraft.behavior.construction.PlacementConfirmation;
import org.maiwithu.maicraft.behavior.construction.PlacementPrediction;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;
import org.maiwithu.maicraft.behavior.interaction.AimAndInteract;
import org.maiwithu.maicraft.behavior.interaction.FirstPersonScene;
import org.maiwithu.maicraft.behavior.interaction.InteractionTarget;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.interaction.InteractionConfirmation;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;

/**
 * 物理结构上的出手：放方块、扳手转、开关门都是同一个"看准、点下去、看有没有反应"的交互动作，只是现场换成结构上的——
 * 瞄准点按结构此刻的位姿换到世界里转头，准星打中的是地块里的格，交给游戏的也是它。被点的方块会开界面时整个动作按着潜行；
 * 扳手转向不潜行（潜行右键是拆下来）。结构上倒桶、舀水还不会，照实交一个做不下去的出手。
 */
final class StructureClicks implements ConstructionSeams.Clicks {

    private final PlayerServices services;
    private final Function<PlayerContext, FirstPersonScene> scene;

    StructureClicks(StructureReads reads, PlayerServices services, String structureId) {
        this.services = Objects.requireNonNull(services, "services");
        this.scene = StructureScene.of(reads, structureId);
    }

    // 点被点方块朝那个方向的任一处表面；会开界面的方块按着潜行点，手里的方块才放得下去。
    @Override public Click place(PlannedCell cell, PlacementPrediction.Placement placement, boolean sneak,
            PlacementConfirmation confirmation) {
        AimAndInteract use = new AimAndInteract(InteractionTarget.BlockTarget.facing(placement.clicked(), placement.face()),
                confirmation, scene);
        return new Click(sneak ? services.sneak().sneaking(use) : use, use::result);
    }

    @Override public Click pour(PlannedCell cell, InteractionConfirmation confirmation) {
        return refused("在物理结构上倒桶还不会");
    }

    @Override public Click scoop(BlockPos source, InteractionConfirmation confirmation) {
        return refused("在物理结构上舀水还不会");
    }

    @Override public Click use(BlockPos block, InteractionConfirmation confirmation) {
        AimAndInteract use = new AimAndInteract(new InteractionTarget.BlockTarget(block), confirmation, scene);
        return new Click(use, use::result);
    }

    // 拿扳手转：只点朝 face 的那一面，不潜行（潜行右键会把方块拆下来）。
    @Override public Click turn(BlockPos block, Direction face, InteractionConfirmation confirmation) {
        AimAndInteract use = new AimAndInteract(InteractionTarget.BlockTarget.facing(block, face), confirmation, scene);
        return new Click(use, use::result);
    }

    private static Click refused(String why) {
        return new Click(new Refused(Problem.of(Problem.Kind.UNSUPPORTED, why)), () -> null);
    }
}
