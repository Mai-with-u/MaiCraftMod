// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.bodylanguage;

import java.util.function.Function;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.perception.looking.Aim;
import org.maiwithu.maicraft.behavior.perception.looking.TurnsHead;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 转过去看着一个人：每刻按正常转头速度对准他的眼睛，他走动就跟着看；他不见了就停在最后看的方向。
 * 只转头不走路，看多久由用它的任务定。被拍了回头看、看着人做肢体动作都用它。
 */
public final class FacePlayer implements Action {

    private final int entityId;
    private final Function<TickContext, TurnsHead> heads;
    private TurnsHead head;
    private boolean holding;
    private boolean seen;

    /** @param entityId 要看的人的实体编号 */
    public FacePlayer(int entityId) {
        this(entityId, TurnsHead::of);
    }

    FacePlayer(int entityId, Function<TickContext, TurnsHead> heads) {
        this.entityId = entityId;
        this.heads = heads;
    }

    // 第一刻记下站定朝向（看人只是转头，观察的前后左右不跟着头走），之后每刻对准他的眼睛。
    @Override public ActionStatus tick(TickContext context) {
        head = heads.apply(context);
        if (head == null || context.player() == null || context.player().level() == null) return ActionStatus.running();
        if (!holding) {
            head.hold();
            holding = true;
        }
        Entity him = context.player().level().getEntity(entityId);
        seen = him != null && !him.isRemoved();
        if (!seen) return ActionStatus.running();
        Vec3 eye = context.player().localPlayer().getEyePosition();
        Vec3 his = him.getEyePosition();
        Aim aim = Aim.between(eye.x, eye.y, eye.z, his.x, his.y, his.z);
        head.turnToward(aim.yaw(), aim.pitch());
        return ActionStatus.running();
    }

    /** 上一刻还找得到他。 */
    public boolean stillThere() {
        return seen;
    }

    // 被打断、做完：不再为看人转头，镜头停在哪就是哪，不往回甩。
    @Override public void pause() {
        letGo();
    }

    @Override public void close() {
        letGo();
    }

    private void letGo() {
        if (head != null && holding) head.letGo();
        holding = false;
    }

    // 只是转头：两个动作之间，什么都能插进来。
    @Override public Interruptibility interruptibility() {
        return Interruptibility.BETWEEN_ACTIONS;
    }

    @Override public String describe() {
        return "看着他";
    }
}
