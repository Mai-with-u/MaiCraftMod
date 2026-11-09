// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 闲时看读的现场：角色能不能只转头看东西、眼睛看向一样东西要转到哪、身边有什么可看、LLM 读到的那样东西在哪。
 *
 * <p>全从感知来：只给看得见的实体、听见的声音来处，和 LLM 刚读到的、角色本来就知道的东西。
 * 生产实现读当刻的角色与场景，离线测试换成替身。
 */
interface LookingWorld {

    /** 读到的那样东西是什么：看实体、看告示牌、看容器、看别的。 */
    enum Kind { ENTITY, SIGN, CONTAINER, OTHER }

    /**
     * 一个容器能不能顺手打开看一眼的现场：五条里由现场回答的四条（骑乘已在闲着的判定里挡住）。
     *
     * @param reachableAndVisible 站在原地够得着、看得见
     * @param mayUse              存储取用许可允许（额外保护的地标旁与记住的区域里的不开）
     * @param signalSource        方块状态是信号源（陷阱箱），开了会触发机关
     * @param hostileNear         附近十六格内看得见敌对生物（猪灵也算）
     */
    record ContainerFacts(WorldPosition at, String blockType, boolean reachableAndVisible, boolean mayUse,
            boolean signalSource, boolean hostileNear) {}

    /**
     * 能看的一样东西。
     *
     * @param key       认它的键：连着两回不看同一处、冷却都按它
     * @param yaw       从眼睛看向它的偏航，用来排左右
     * @param distance  离角色多远（格）
     * @param container 是容器时的开箱现场；不是容器为 null
     */
    record Sighted(String key, LookTarget target, Kind kind, float yaw, double distance, ContainerFacts container) {}

    /** 闲看的候选种类：玩家、别的生物、声音来处。 */
    enum CandidateKind { PLAYER, MOB, SOUND }

    /**
     * 闲看的一个候选。
     *
     * @param moving     这一刻在动（生物动着的先看）
     * @param approaching 正朝角色走过来（闲久了只对走近的玩家有反应）
     * @param playerId   玩家的编号，对说过话的人用；不是玩家为 null
     */
    record Candidate(CandidateKind kind, String key, LookTarget target, float yaw, double distance,
            boolean moving, boolean approaching, UUID playerId) {}

    /** 角色能只转头看东西：背包到了、脚踏实地，没骑着、没坐着、没睡着、没飞着。 */
    boolean playerCanLook(TickContext context);

    /** 从眼睛看向它此刻要转到的方向；它不见了（实体走了、不在世界里）为空。站定朝向由调用方自己算。 */
    Optional<Aim> aim(TickContext context, LookTarget target);

    /** 身边看得见的玩家、生物和此刻听见的声音来处。 */
    List<Candidate> around(TickContext context);

    /** 周围看得见的几样东西（实体、地形特征、设施）：读周围时从中挑两三样看。 */
    List<Sighted> sceneSpots(TickContext context);

    /** 观察编号或地标名对应的东西；不认识为空。 */
    Optional<Sighted> resolve(TickContext context, String idOrLandmark);

    /**
     * 拍画面要看的那样东西：和 {@link #resolve} 一样认，只是看得见的不论多远都精确对准
     * （读后看把六十四格外的也当成只朝方位瞥；拍画面要的是把它拍进画面中间）。
     */
    default Optional<Sighted> resolveForPicture(TickContext context, String idOrLandmark) {
        return resolve(context, idOrLandmark);
    }

    /** 世界里的这几处：不在当前维度的跳过。 */
    List<Sighted> places(TickContext context, List<WorldPosition> places);
}
