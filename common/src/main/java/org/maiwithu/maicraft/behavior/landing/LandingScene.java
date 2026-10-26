// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.landing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.game.player.FallDamage;
import org.maiwithu.maicraft.game.player.PlayerContext;

/**
 * 掉落救援这一刻的只读现场：眼睛在哪、够得着多远、在不在地上、按没按潜行、摔落取样、
 * 沿视线的射线、落点放不放得下、身上带没带哪一种。出手窗口与换办法的判断只从这里读现场，
 * 不直接摸玩家对象；离线测试用替身按脚本回答，真实实现只做转发。
 */
public interface LandingScene {

    /** 角色眼睛的世界坐标；够不够得着、射线都从它出发。 */
    Vec3 eyePosition();

    /** 方块触及距离：眼睛到被点的那一面高过它就是还够不着。 */
    double blockReach();

    /** 被点的那一面有多高（含植物、半砖这类不满格的形状）；算什么时候够得着用。 */
    double placementHeight(LandingPlan plan);

    /** 脚下的世界坐标：干草减伤按脚下离落点那一格的高度算。 */
    double feetY();

    /** 脚在地上：站着就不用放，也谈不上对准。 */
    boolean onGround();

    /** 按住潜行：方块类放之前要按住，点到的方块不会被"使用"。 */
    boolean sneaking();

    /** 摔落取样（生命、黄心、安全摔落距离、已经在掉的那段……）：干草减伤后活不活得成用它算，取样是游戏接口层的同一条事实。 */
    FallDamage fallSample();

    /** 沿当前视线按方块外形的射线：放水与放方块核对准星用，液体不算命中。 */
    BlockHitResult sightHit();

    /** 沿当前视线按方块外形的射线，液体也算命中：放船对着水面时准星要能点到水面。 */
    BlockHitResult sightHitThroughFluids();

    /** 落点那一格此刻放不放得下这一种（被占了、立不住都算放不下）。 */
    boolean canPlaceAid(LandingAid aid, BlockPos cell);

    /** 身上（背包三十六格或副手）带着这一种没有：换办法时现成的不用带，要放的要带着才算数。 */
    boolean carries(Item item);

    /** 从当刻的角色上下文取现场；判断每刻取一次，不留到下一刻。 */
    static LandingScene of(PlayerContext context) {
        return new LiveLandingScene(context.localPlayer());
    }
}
