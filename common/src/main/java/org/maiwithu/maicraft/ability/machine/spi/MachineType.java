// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.maiwithu.maicraft.behavior.recipe.ShownIngredient;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 一种机器：Create 的压机、Mekanism 的冶金灌注机、AE2 的控制器……一个联动模组登记很多个。
 * 机器能力只认这个接口：这格是不是这种机器、在机器里干什么、哪几面和外界交换东西、现在转不转，
 * 以及只能用模组自己的方式做的那几件事（装传送带、装部件、改设置、投料取货）怎么做。
 *
 * <p>只用 Minecraft 与 Java 的类型；模组的类只出现在 NeoForge 模块的读写端。实现由联动入口经登记表交来，
 * 进世界时带着联动能用的玩家行为建，返回的动作和自带能力一样走过去、换到主手、点、等游戏确认。
 * 动作只负责"做一次原生操作并如实报告"，做得成做不成、机器能不能转，由机器能力按读回的事实判断。
 */
public interface MachineType {

    /** 这种机器的编号，通常就是主方块的注册 ID，例如 create:mechanical_press。 */
    String id();

    /** 给 LLM 看的名字，例如"动力压机"。 */
    String name();

    /** 来自哪个模组（模组 ID）。 */
    String modId();

    /** 这一格（方块状态）是不是这种机器；认领了才归进机器。 */
    boolean covers(BlockState state);

    /** 这种机器的方块在机器里干什么。 */
    MachineRole role();

    /** 这一格和外界交换东西的面：介质、进还是出，加工处在哪（压机正下方、锯的前方）。 */
    List<ExchangePoint> exchangePoints(BlockState state, BlockPos at);

    /** 这一格此刻的运行状态，只读客户端看得见的；读不到的写进说明，不猜。 */
    MachineState state(BlockPos at);

    /**
     * 这一格各面此刻装着的部件（AE2 线缆上的总线、终端）与它们在不在干活。看机器时线缆合进网络，
     * 装在线缆上的部件才是干活的东西，要单列出来；没有部件这种东西的机器给空表。
     */
    default List<MountedPart> mountedParts(BlockPos at) {
        return List.of();
    }

    /**
     * 这种方块在它那种网上吃多少、给多少（每转应力、频道）：审阅估算整份蓝图够不够用。
     * 只作估计，不拦施工；没有这类读数给空。
     */
    default Optional<NetworkLoad> load(BlockState state) {
        return Optional.empty();
    }

    /**
     * 这段原生安装段的形状合不合规则（直线、长度不超模组配置、两端是不是该有的方块）；合规则给空，
     * 不合给一句说明。只用来审阅与计划阶段报错，不拦施工：形状不对的段照样可以试着装，结果如实报。
     */
    Optional<String> installationProblem(Installation installation);

    /** 这个部件能不能装在这一格宿主的这一面；能给空，不能给一句说明。用法同上。 */
    Optional<String> partProblem(PartCell part, BlockState host);

    /** 用模组自己的方式装这一段（两端的轴已就位后用传送带连上）；这种机器不管这种安装段时给空。 */
    Optional<Action> install(Installation installation, Permissions permissions);

    /** 这一段现在是不是按要求装成了；按最终结构核对，给整机比对用。 */
    boolean installed(Installation installation);

    /**
     * 拆整台时这段安装段里要跟着拆的格：这一段自己放下的方块（机械臂、一格格接上的 ME 线缆）。
     * 两头本来就在的方块不算：链条输送机、传送带两头的轴写在蓝图 cells 里随整台拆；机械臂的取放点、
     * 链条连到的另一台可能是别的机器或主城原有的东西，拆这一台不能拆到它们。
     * 不认这种安装段时给空，认但这一段没放方块时给空表。
     */
    default Optional<List<BlockPos>> placedBy(Installation installation) {
        return Optional.empty();
    }

    /**
     * 要让这一片方块跟着一起动（组装成会动的结构），还缺哪些安装段：Create 是还没被强力胶框住的地方各补一框。
     * 只算不动手，装交给 {@link #install}；不管这件事的机器类型给空，管但已经都粘好时给空表。
     * 片外的方块不往里框，免得垫的、地面跟着一起走。
     *
     * @param cells 要一起动的方块，世界坐标；此刻那一格是空气或流体的不算
     */
    default Optional<List<Installation>> stickTogether(Set<BlockPos> cells) {
        return Optional.empty();
    }

    /**
     * 读现场时把一台机器里归这种机器认领的格抄成机器蓝图的写法：传送带、链条输送机之间的链、机械臂、
     * ME 线缆与部件照抄方块建不回来，写成安装段与部件；照抄方块与属性就建得回来的给空，由机器能力照抄。
     *
     * @param cells 这台机器里这种机器认领的格与它们此刻的方块状态，世界坐标
     */
    default Optional<MachineCopy> copy(Map<BlockPos, BlockState> cells) {
        return Optional.empty();
    }

    /** 手持部件右键宿主的那一面把它装上；这种机器不管这种部件时给空。 */
    Optional<Action> mount(PartCell part, Permissions permissions);

    /** 这个部件现在是不是装在那一面上。 */
    boolean mounted(PartCell part);

    /** 这一格有哪些设置项、现在是什么值；没有设置项给空列表。 */
    List<MachineSetting> settings(BlockPos at);

    /** 把一项设置改成要的值：手持配置器点面、开界面加规则；改完由调用方读回核对。这种机器没有这一项时给空。 */
    Optional<Action> change(BlockPos at, String key, String value, Permissions permissions);

    /**
     * 往这台机器投料：置物台是手持右键顶面，工作盆、石磨这类扔上去，带界面的点开界面放进去。这种机器不收料时给空。
     * 投法不一定件件都进时，动作再实现 {@link FeedReport} 交出实情。
     */
    Optional<Action> feed(BlockPos at, String itemId, int count, Permissions permissions);

    /** 这台机器出口现在有什么：置物台台面、出口箱、传送带末端；只列看得见的。 */
    List<MachineState.Shown> output(BlockPos at);

    /**
     * 出口站在外面读不读得到：置物台、工作盆上画出来的东西，石磨同步过来的产物格，都读得到；
     * 格子在界面里的（通用机械的机器）读不到。读不到时 machine_run 投完料隔一个加工周期走过去点开界面看一眼，
     * 看到了就收，几回都没出才按部分完成收场；产物掉在地上的机器改给 {@link #dropArea}。
     */
    default boolean outputVisible(BlockPos at) {
        return true;
    }

    /**
     * 做好的东西掉在旁边地上的机器（朝上的锯往前弹、粉碎轮从中间那格往下漏）：产物落在哪一块，世界坐标。
     * 给了它，machine_run 就数这一块地上新多出来的掉落物、走过去捡，不再问 {@link #output} 与 {@link #take}；
     * 产物不落地的机器给空。
     */
    default Optional<AABB> dropArea(BlockPos at) {
        return Optional.empty();
    }

    /** 从出口把东西拿进背包；出口没有这种东西或这种机器不出东西时给空。 */
    Optional<Action> take(BlockPos at, String itemId, int count, Permissions permissions);

    /**
     * 这台机器自己从头做到尾的一套做法（蓝图桌写蓝图、蓝图加农炮照蓝图施工）：不按配方投料，整段交给机器类型。
     * 不是这种机器、或这次的请求不归它管给空，machine_run 照配方那一套走。
     */
    default Optional<FullRun> fullRun(BlockPos at, RunRequest request, Permissions permissions) {
        return Optional.empty();
    }

    /**
     * machine_run 给的 item 对这台机器是什么：发电机这类烧燃料的，item 就是燃料，备齐了直接经 {@link #feed} 投，不查配方；
     * 不吃料的发电机说一句它靠什么发电。大多数机器按配方投料，给 {@link FuelUse#RECIPE}。
     */
    default FuelUse fuelUse(BlockPos at) {
        return FuelUse.RECIPE;
    }

    /**
     * 配方里背包带不进机器的那一格原料（化学品这类）能不能用物品投：通用机械的机器从附加格吃煤、红石这些物品，
     * 再换成配方要的灌注物。能就给投哪样、几件，machine_run 把它排在主料前面经 {@link #feed} 投；
     * 换不了给空，machine_run 照旧写一句"带不进机器，做不做得看机器里还有没有"。
     *
     * @param ingredient 配方里的那一格（查看器认得、但不是物品也不是流体的东西）
     * @param amount     这次要的总量：配方的量乘上批数
     */
    default Optional<IngredientFeed> ingredientFeed(BlockPos at, ShownIngredient ingredient, long amount) {
        return Optional.empty();
    }
}
