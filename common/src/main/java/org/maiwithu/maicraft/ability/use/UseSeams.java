// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.core.BlockPos;

import org.maiwithu.maicraft.behavior.approach.ApproachTarget;
import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.behavior.interaction.SignEditor;
import org.maiwithu.maicraft.behavior.permission.ContainerWhose;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 用东西能力的执行接缝。任务只经这些窄接口读世界、拿动作，游戏对象全留在生产实现里，
 * 任务因此能离线测试。可选的接缝没接（传 null）时，能力按"这条路还没通"如实处理，不假装做过。
 */
final class UseSeams {

    private UseSeams() {}

    /** 手上拿什么：看身上有没有、在不在主手，给出这一刻该怎么办。 */
    interface PreparesHand {
        /** item 为 null 表示要空手；标签（# 开头）表示其中任意一种。 */
        HandPlan hold(String item);
    }

    /** 备手的打算。 */
    sealed interface HandPlan {
        /** 已经拿好（或已经空手）。 */
        record Ready() implements HandPlan {}

        /** 东西在身上，按这个动作分刻换到主手（或切到空快捷栏格）。 */
        record Move(Action action) implements HandPlan {}

        /** 身上没有要拿的东西：缺的去拿是拿到物品引擎的事。 */
        record NotCarried() implements HandPlan {}

        /** 在身上也换不过来（穿在盔甲格、快捷栏没有空格），带原因。 */
        record Cannot(Problem problem) implements HandPlan {}
    }

    /** 用东西要读的世界事实：都按这一刻的现场回答，没加载的格子不冒充读到了。 */
    interface ReadsWorld {
        /** 角色此刻所在维度的 ID；不在世界里为 null。 */
        String dimension();

        /** 角色脚下那一格；不在世界里为 null。 */
        BlockPos feet();

        /** 那一格所在的区块加载了没有。 */
        boolean loaded(BlockPos cell);

        /** 那一格方块的注册 ID；没加载为空。 */
        Optional<String> blockId(BlockPos cell);

        /** 那一格是不是空气（含洞穴空气、虚空空气）；没加载按真算。 */
        default boolean air(BlockPos cell) {
            return blockId(cell).map(id -> id.equals("minecraft:air")
                    || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air")).orElse(true);
        }

        /** 那一格是不是空气或可被直接顶替的方块（短草、蕨、薄雪、流体）；没加载按真算。 */
        default boolean replaceable(BlockPos cell) {
            return air(cell);
        }

        /** 那一格是不是给定的方块，或在给定的标签里（# 开头）；没加载为假。 */
        boolean blockIs(BlockPos cell, String blockOrTag);

        /** 那一格是门、活板门或栅栏门时此刻开着没有；不是这几样、或没加载为空。 */
        default Optional<Boolean> doorOpen(BlockPos cell) {
            return Optional.empty();
        }

        /** x、z 那一列最上面一块挡得住身体或有流体的方块；那一列没加载为空。 */
        Optional<BlockPos> ground(int x, int z);

        /** 那一格的流体：不是流体、源格还是流动的。 */
        Fluid fluid(BlockPos cell);

        /** 离 near 最近、和它同种流体的源格（radius 格以内）；没有为空。 */
        Optional<BlockPos> nearestSource(BlockPos near, int radius);

        /** 那一格是不是告示牌、上没上过蜡。 */
        Sign sign(BlockPos cell);

        /** 一只还在世界里的实体此刻的样子；不在了为空。 */
        Optional<SeenEntity> entity(int entityId);

        /** 角色此刻是不是正骑着这只实体。 */
        boolean riding(int entityId);

        /** 角色主手上拿着什么；空手为空。 */
        Optional<Held> heldItem();

        /**
         * 靠近目标的形态：方块用格子，实体用它此刻的包围盒；实体不在了为空。
         * writesSign 为真（写告示牌）时方块目标只站正面那一侧：从哪一面点就编辑哪一面，
         * 那一格不是告示牌了给空，任务按目标没了收场。
         */
        Optional<ApproachTarget> approachTarget(ResolvedTarget target, boolean writesSign);

        /** 流体格的三种样子。 */
        enum Fluid { NONE, SOURCE, FLOWING }

        /** 告示牌的三种情形：不是告示牌、上过蜡（打不开编辑界面）、能写。 */
        enum Sign { NOT_A_SIGN, WAXED, WRITABLE }

        /**
         * 一只实体此刻的样子。
         *
         * @param typeId   实体类型的注册 ID
         * @param cell     它所在的格
         * @param pickable 准星能不能选中它；掉落物、经验球、射出去的箭选不中
         */
        record SeenEntity(String typeId, BlockPos cell, boolean pickable) {}

        /**
         * 手上拿着的一堆东西。
         *
         * @param itemId 物品的注册 ID
         * @param count  这一堆有几个
         */
        record Held(String itemId, int count) {}
    }

    /**
     * 组装这一下交互：出手前的现场（手上的东西、界面编号、目标格）在组装时冻结，
     * 手势按手上实际拿着的东西定，确认条件随手势取。target 为 null 表示只对手上的东西用。
     */
    interface BuildsInteraction {
        Built build(ResolvedTarget target, boolean writesSign);
    }

    /** 组装的结论。 */
    sealed interface Built {
        /**
         * 组装好了。
         *
         * @param action     逐刻推进的交互动作
         * @param result     动作结束后的结论；瞄不准、目标没了这类没出手的失败给 null
         * @param gesture    按手上实际拿着的东西归出的手势
         * @param heldBefore 出手前主手上拿着的东西；空手为 null
         * @param menuBefore 出手前的界面编号；用它认"点开了新界面"
         * @param effectCell 效果落下的那一格（倒流体、点火）；对实体或只对手上的东西用时为 null
         */
        record Ready(Action action, Supplier<InteractionResult> result, ItemUseAim.Gesture gesture,
                ReadsWorld.Held heldBefore, int menuBefore, BlockPos effectCell) implements Built {}

        /** 组装不出来：从这里瞄不准、这样用不了、或目标已经不在了。 */
        record CannotAim(Problem problem) implements Built {}

        /**
         * 锄、锹被压在目标上面的野生草木挡着（短草、蕨、花）：先打掉那一格再出手。
         *
         * @param cell    压着的那一格
         * @param blockId 压着的是什么
         */
        record ClearAbove(BlockPos cell, String blockId) implements Built {}
    }

    /** 锄地、压土径前打掉压在目标上面的野生草木：先过挖方块的许可，再用挖一格的动作打掉。 */
    interface ClearsAbove {
        /** 为了锄地、压土径打掉这一格行不行：别人的、受保护的、change_blocks 不到 natural 的给拒绝。 */
        Optional<Problem> allowed(Permissions permissions, BlockPos cell, String blockId);

        /** 打掉这一格的动作：那一格不再是原来的东西就算做完。 */
        Action clear(BlockPos cell, String blockId);
    }

    /** 告示牌编辑界面：右键提交后界面的读取入口；界面没开着给空。 */
    interface ReadsSignEditor {
        Optional<SignEditor> current();
    }

    /** 游戏的回应：动作栏此刻显示的提示语（"箱子已上锁"、领地保护），与聊天栏里的服务端系统消息。 */
    interface ReadsGameRefusal {
        /** 动作栏此刻显示的提示语；没有在显示为空。 */
        Optional<String> latestMessage();

        /** 出手前记一下聊天栏读到哪了；没接聊天栏为 0。 */
        default long chatMark() {
            return 0;
        }

        /** 记号之后聊天栏收到的服务端系统消息原话，按收到先后；没接聊天栏为空。 */
        default List<String> systemMessagesSince(long mark) {
            return List.of();
        }
    }

    /** 走到没加载的坐标：一直走到那一片加载出来；给不出走法时为空。 */
    interface TravelsTo {
        /**
         * @param where       要去的位置
         * @param heightKnown 高度核实过没有；没核实只走到那一柱列
         * @param permissions 这次任务的许可：路上能动多少地形按它来
         */
        Optional<Action> toward(WorldPosition where, boolean heightKnown, Permissions permissions);
    }

    /**
     * 别人的东西：会改动世界的用法（倒流体、点火、耕地、剪毛、挤奶这类）碰到角色自己挑的目标时，
     * 别人的不碰；开门、开箱看看、按按钮、骑乘不改动世界，别人的也可以。LLM 用观察编号点名的不问这里。
     */
    interface OthersThings {
        /** 拿着这件东西（空手为 null）对这个目标用一下，算不算改动世界；拿不准算。 */
        boolean changesWorld(String heldItem, ResolvedTarget target);

        /**
         * 这个目标是不是别人的：方块按保护判断，生物按有主（有名字、驯服、拴绳、圈养、玩家）判断；拿不准算别人的。
         * 按类型自己找的目标问这一份，家里别人放的照旧算别人的。
         */
        boolean someoneElses(ResolvedTarget target, Set<String> protectedLandmarks);

        /**
         * 同上，给调用方指着的那一格（坐标、地点）用：在角色自己的家里，别人放的方块也算她能用的；
         * 记住的区域、额外保护的地标照旧算别人的。
         */
        default boolean someoneElsesPointedAt(ResolvedTarget target, Set<String> protectedLandmarks) {
            return someoneElses(target, protectedLandmarks);
        }

        /**
         * 为什么算别人的，说成一小句，例如"服务端归属记录写着是 玩家甲 放的"：拒绝时照它说清是哪一路挡的。
         * 说不清的给一句笼统的。
         */
        default String whyOthers(ResolvedTarget target, Set<String> protectedLandmarks) {
            return "玩家放的或在玩家的地盘里";
        }

        /**
         * 这一格最后由谁放下，说成名字（在线写玩家名，不在线写"一位不在线的玩家"）：
         * 打开容器看内容时结果里带一句，宿主与主播看得出动的是谁的东西。归属没问到、角色自己放的给 null。
         */
        default String lastModifiedBy(ResolvedTarget target) {
            return null;
        }

        /**
         * 点开一只方块容器时，断定它是谁的以及这一次点开之前开没开过。
         * 默认按 {@link #lastModifiedBy} 与战利品标签兜底，方便离线测试替身不另写归属表也能工作。
         */
        default ContainerWhose containerWhose(ResolvedTarget target, boolean naturalLoot, boolean openedBefore) {
            if (naturalLoot) {
                return new ContainerWhose(ContainerWhose.Kind.NATURAL_LOOT, null, openedBefore);
            }
            String modifier = lastModifiedBy(target);
            if (modifier != null) {
                return new ContainerWhose(ContainerWhose.Kind.PLAYER, modifier, openedBefore);
            }
            return new ContainerWhose(ContainerWhose.Kind.UNKNOWN, null, openedBefore);
        }

        /**
         * 出手前目标方块还在原位时，若这一下属于会改动方块的用法，就先抄下它是不是其他玩家放的；
         * 交互确认生效时调用返回的回调发出一条 others_things_touched（action=changed）事件。
         */
        default Runnable prepareWorldChangeNotice(String heldItem, ResolvedTarget target) {
            return () -> {};
        }
    }

    /** 看看点开的界面：等内容同步完、列出里面有什么、再关上；打开者负责关闭。 */
    interface LooksInMenus {
        /** 出手后点开了新界面（编号和出手前不同）时给看界面的动作；没点开界面给空。 */
        Optional<MenuLook> opened(int menuBefore);
    }

    /** 看一次界面的动作：做完后给出界面里有什么。 */
    interface MenuLook extends Action {
        /** 界面里有什么：物品 ID 到件数，按界面里的先后；内容没同步完或界面认不出时为空。 */
        Optional<Map<String, Integer>> contents();
    }
}
