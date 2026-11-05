// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.bodylanguage;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.maiwithu.maicraft.behavior.inventory.drops.ThrowsItems;
import org.maiwithu.maicraft.behavior.perception.looking.Aim;
import org.maiwithu.maicraft.behavior.perception.looking.TurnsHead;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 做肢体动作要的现场：认出对着谁、离他多远、换手、走过去、扔、捡，和蹲起点头挥手用的转头、按键。
 * 生产实现接在真实客户端上；离线测试换成替身。
 */
interface NonverbalMoves {

    /** 对着的那个人：游戏实体编号、UUID、怎么称呼他。 */
    record Partner(int entityId, UUID uuid, String name) {}

    /** 看他哪里：眼睛（看着、点头摇头）、身体中间（拍一下）、脚下（扔东西）。 */
    enum AimPoint { EYES, CENTER, FEET }

    /** 对着谁：给了玩家名或观察编号就是他；不给就是最近 30 秒里最后一个冲角色做过肢体动作的玩家。找不到为 null。 */
    Partner partner(TickContext context, Target target);

    /** 离他（或地上那件东西）多远（格）；不在世界里了为正无穷。 */
    double distanceTo(TickContext context, int entityId);

    /** 骑着、坐着东西。 */
    boolean riding(TickContext context);

    /** 身上带着的、对得上这个物品 ID 或 # 标签的那一种；没有为空。 */
    Optional<String> carried(TickContext context, String itemOrTag);

    /** 主手此刻选中快捷栏第几格。 */
    int selectedSlot(TickContext context);

    /** 选回快捷栏这一格；本刻出不了手返回 false，下一刻再选。 */
    boolean selectSlot(TickContext context, int slot);

    /** 把这件东西拿到主手；已经在手上时是一个立刻做完的动作，换不了为空。 */
    Optional<Action> takeOut(TickContext context, String itemId);

    /** 空出手；快捷栏与背包都没地方放时为空。 */
    Optional<Action> emptyHand();

    /** 走到离他 within 格以内：只走路，不为递个东西挖方块、垫方块。 */
    Action walkNear(int entityId, double within);

    /** 把主手上的这件东西扔出去 count 件（和丢东西同一下"抛"），扔出几件、没等到确认几件从它读。 */
    ThrowsItems toss(String itemId, int count);

    /** 能捡的：给了观察编号就那一件；不给就最近 60 秒里别的玩家扔到身边的。离角色 6 格以外的不算。 */
    Set<Integer> pickable(TickContext context, Target target);

    /** 走过去把这些东西捡起来，等它们进包。 */
    Action pickUp(Set<Integer> items);

    /** 冲他做了肢体动作：回过他了，告诉被拍了回头看。 */
    void answered(UUID who);

    /** 这一刻的转头接缝；不在世界里为 null。 */
    TurnsHead head(TickContext context);

    /** 从眼睛看向他身上这一处的方向；他不在了为 null。 */
    Aim aimAt(TickContext context, int entityId, AimPoint point);

    /** 本刻按不按着潜行、跳跃（不走路）。 */
    void press(TickContext context, boolean sneak, boolean jump);

    /** 服务器那边看到的角色正蹲着（潜行键按下去生效了）。 */
    boolean crouched(TickContext context);

    /** 角色脚踩在地上。 */
    boolean onGround(TickContext context);

    /** 准星此刻什么都没指着。 */
    boolean aimsAtNothing();

    /** 准星此刻指着的实体编号；没有为 -1。 */
    int aimedEntity();

    /** 按一下攻击键；本刻出不了手返回 false。 */
    boolean pressAttack(TickContext context);
}
