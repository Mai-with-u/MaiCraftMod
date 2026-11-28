// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.use;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import net.minecraft.world.phys.Vec3;

import org.maiwithu.maicraft.behavior.interaction.InteractionResult;
import org.maiwithu.maicraft.behavior.interaction.ItemUseAim;
import org.maiwithu.maicraft.kernel.goal.Target;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.result.TaskResult;

/**
 * 用东西的判定：参数组合行不行、目标会不会炸、交互的结论怎么变成任务结果。
 *
 * <p>纯判断，不碰游戏：参数组合在计划阶段一次报全；会爆炸的组合（下界与末地的床、
 * 主世界与末地的重生锚）直接按危险结束，一次都不点；交互结论按生效、没生效、
 * 出乎预料、没能确认四种走向结算，没能确认的交互绝不再赌第二次。
 */
final class UseDecider {

    /** 参数组合的问题：全部一次报全，不挤牙膏。 */
    static List<String> invalidCombinations(boolean hasTarget, String item, String block, String entity,
            List<String> textLines) {
        return invalidCombinations(hasTarget, item, block, entity, textLines, false);
    }

    /** 参数组合的问题（含显式点哪一面）：没给 target 也没给 block 却传了 face 时直接报清矛盾，不静默丢掉 face。 */
    static List<String> invalidCombinations(boolean hasTarget, String item, String block, String entity,
            List<String> textLines, boolean hasFace) {
        List<String> problems = new ArrayList<>();
        if (block != null && entity != null) {
            problems.add("block 与 entity 不能同时给：要点的是方块还是实体，选一个");
        }
        if (!textLines.isEmpty() && item != null) {
            problems.add("text 与 item 不能同时给：写字必须空手");
        }
        if (!hasTarget && block == null && entity == null && item == null) {
            problems.add("target、block、entity、item 一个都没给：不知道要对什么用");
        }
        if (hasFace && !hasTarget && block == null) {
            problems.add("给了 face 却没给 target 或 block：空气没有面可点，点脚下地面请传 target=here，点指定方块请给观察编号、坐标或 block");
        }
        // 刷子没有对着空气的使用方式（原版只对可疑的沙子、沙砾起作用）：
        // 只给 item 不给目标时不知道对着哪里刷，计划阶段一次说清。
        if ("minecraft:brush".equals(lower(item)) && !hasTarget && block == null && entity == null) {
            problems.add("刷子要对着方块刷（可疑的沙子、沙砾），只给 item 不知道对着哪里刷");
        }
        return problems;
    }

    /** 机械动力工作盆的方块 ID。 */
    static final String BASIN_BLOCK = "create:basin";
    /** 机械动力合成器的方块 ID：右键投料、空手取料与扳手改向都必须命中正面（HORIZONTAL_FACING）。 */
    static final String MECHANICAL_CRAFTER_BLOCK = "create:mechanical_crafter";
    /** 原版三类炉子方块 ID（熔炉、烟熏炉、高炉）：右键只会打开界面，不支持直接右键投料。 */
    static final Set<String> VANILLA_FURNACE_BLOCKS = Set.of(
            "minecraft:furnace", "minecraft:smoker", "minecraft:blast_furnace");

    /** 对机械动力工作盆直接右键有原生交互（倒装流体、吸水、旋转拆装、抄写配置、选点）的固定物品名单。类别：F 游戏事实（BasinBlock.useItemOn 与 ValueSettingsInputHandler）。 */
    private static final Set<String> BASIN_DIRECT_USE_ITEMS = Set.of(
            "minecraft:sponge",
            "create:wrench",
            "create:clipboard",
            "create:mechanical_arm",
            "minecraft:glass_bottle",
            "minecraft:potion",
            "minecraft:splash_potion",
            "minecraft:lingering_potion",
            "minecraft:honey_bottle",
            "minecraft:experience_bottle",
            "minecraft:dragon_breath",
            "minecraft:bowl",
            "create:builders_tea",
            "create:potion");

    /**
     * 按物品或标签名判断这件东西对工作盆右键是不是合法的流体装倒、海绵吸水或扳手操作。
     * 工作盆对普通固体物品（铁粒、矿石、海带、过滤器等）右键不会投料，非潜行点到水平侧面中心还会误写配方产物过滤框。
     */
    static boolean interactsWithBasinById(String itemOrTag) {
        if (itemOrTag == null || itemOrTag.isBlank()) return true;
        String spec = lower(itemOrTag.trim());
        int bracket = spec.indexOf('[');
        if (bracket > 0) spec = spec.substring(0, bracket);
        if (spec.startsWith("#")) {
            return spec.contains("wrench") || spec.contains("bucket") || spec.contains("bottle");
        }
        if (BASIN_DIRECT_USE_ITEMS.contains(spec)) return true;
        String path = spec.contains(":") ? spec.substring(spec.indexOf(':') + 1) : spec;
        return path.equals("bucket") || path.endsWith("_bucket")
                || path.endsWith("_wrench") || path.equals("wrench")
                || path.endsWith("_bottle")
                || path.contains("fluid_tank") || path.contains("canister")
                || path.contains("jerrycan") || path.contains("canteen") || path.contains("reservoir");
    }

    /** 手持普通固体对工作盆调用 use 时的游戏事实说明：防止调用方以为能塞料，或误触侧面产物过滤框锁死配方。 */
    static Problem basinSolidUseProblem(String heldItem) {
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                "固体物品 " + heldItem + " 不能手持右键塞进工作盆（右键水平侧面中心反而会把它挂上配方产物过滤框，锁死别的配方）",
                "投固体原料请站到盆边用 drop 扔进盆口、用 machine_run，或用漏斗对准工作盆所在的那一格本身输入；真要改工作盆的配方产物过滤请用 machine_configure 设 filter");
    }

    /** 空手右键营火没有交互效果的游戏事实说明。类别：F 游戏事实（原版营火仅接受放入可烤食物，烤熟自动弹出成掉落物，不支持右键取物）。 */
    static Problem campfireEmptyHandProblem() {
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                "营火不支持右键取物：点燃后约 30 秒烤熟会自动弹出成掉落物（走近自动捡起或用 gather 捡掉落物）；未点燃或未烤熟的食物无法右键取下",
                "先用 observe(detail) 查看营火是否点燃及架上食物；未点燃请先用打火石或火焰弹点火，烤熟弹出后走近或用 gather 捡起掉落物");
    }

    /** 按方块 ID 判定是不是原版炉类（熔炉、烟熏炉、高炉）。 */
    static boolean isVanillaFurnaceId(String blockTypeId) {
        return blockTypeId != null && VANILLA_FURNACE_BLOCKS.contains(lower(blockTypeId));
    }

    /**
     * 拿着物品对原版炉类（熔炉、烟熏炉、高炉）非潜行调用 use、结果只打开界面却未放入任何物品时，
     * 返回清晰的游戏事实失败说明，不再误报“确认生效（里面有：空的）”。
     */
    static Optional<Problem> vanillaFurnaceNoInsert(ResolvedTarget target, String requestedItem, boolean sneak,
            boolean consumedHeld, boolean openedBlockMenu, UseSeams.MenuLook.Insertion menuInsertion,
            String openedMenu, boolean isVanillaFurnace) {
        if (target == null || target.isEntity() || requestedItem == null || sneak || consumedHeld
                || !openedBlockMenu || menuInsertion == UseSeams.MenuLook.Insertion.INSERTED) {
            return Optional.empty();
        }
        if (!isVanillaFurnace && !isVanillaFurnaceId(target.typeId())) {
            return Optional.empty();
        }
        String inside = openedMenu == null || openedMenu.isBlank() ? "" : "；里面有：" + openedMenu;
        return Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                "原版炉类不支持 use 直接投料（仅打开了界面，手上的 " + requestedItem + " 未放入" + inside
                        + "），请用 obtain(via=\"smelt\") 或 machine_run 烧炼",
                "烧炼物品请改用 obtain(via=\"smelt\") 或 machine_run；只想查看炉内内容请空手调用 use"));
    }

    /** 非潜行持方块物品点击机器/带方块实体方块却落块在邻格时的问题结论：立即停手并引导对准收料面或显式潜行。 */
    static Problem placedBesideProblem(String message) {
        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE, message,
                "检查目标方块实际接收物品的朝向面（或改用 machine_configure 配置机器格位）；若确实想贴着目标放方块，请加 sneak=true");
    }

    /** 写字只认还能写的告示牌：不是告示牌或已上蜡时直接交回失败问题。 */
    static Optional<Problem> unwritableSign(UseSeams.ReadsWorld.Sign sign, ResolvedTarget target) {
        return switch (sign) {
            case NOT_A_SIGN -> Optional.of(Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                    target.describe() + " 不是告示牌，写不了字", null));
            case WAXED -> Optional.of(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                    "告示牌上过蜡，游戏不让再改字", null));
            case WRITABLE -> Optional.empty();
        };
    }

    /** 判断本次交互是对着空气、方块还是实体用的（纯函数，用于结果细节 used_on）。 */
    static String usedOn(ResolvedTarget target, UseInput input) {
        if (target != null) return target.isEntity() ? "entity" : "block";
        if (input.entity() != null) return "entity";
        if (input.block() != null || input.face() != null) return "block";
        if (input.target() instanceof Target.Seen seen) {
            return seen.id().startsWith("e") ? "entity" : "block";
        }
        return input.target() == null || input.target() instanceof Target.Here ? "air" : "block";
    }

    /** 计划阶段的一次拒绝：参数怎么改能继续。 */
    record Rejection(String message, String suggestion) {}

    /** 给了不接的物品时在计划阶段拒绝：凭时机松手的指向对应的专门能力。刷子是对格按住的手势，正常接。 */
    static Optional<Rejection> rejectedItem(String item) {
        if (item == null) {
            return Optional.empty();
        }
        String id = lower(item);
        if (ItemUseAim.endsOnlyWhenReleased(item)) {
            return Optional.of(new Rejection(
                    item + " 要凭时机松手（弓、弩、三叉戟、盾牌、望远镜、钓竿），use 不接受",
                    id.endsWith("fishing_rod") ? "钓鱼用 fish" : "打怪用 fight"));
        }
        return Optional.empty();
    }

    /**
     * 会爆炸的组合：下界、末地的床和主世界、末地的重生锚右键会炸。
     * 维度传角色此刻所在的维度（目标就在身边才点得到）；读不到维度时不冒充知道，按不炸处理。
     */
    static Optional<Problem> explosionDanger(String dimension, String blockTypeId) {
        if (dimension == null || blockTypeId == null) {
            return Optional.empty();
        }
        if ((blockTypeId.endsWith(":bed") || blockTypeId.endsWith("_bed"))
                && (dimension.equals("minecraft:the_nether") || dimension.equals("minecraft:the_end"))) {
            return Optional.of(Problem.of(Problem.Kind.DANGER,
                    "在" + dimensionName(dimension) + "右键床会爆炸，不点", null));
        }
        if (blockTypeId.endsWith(":respawn_anchor")
                && (dimension.equals("minecraft:overworld") || dimension.equals("minecraft:the_end"))) {
            return Optional.of(Problem.of(Problem.Kind.DANGER,
                    "在" + dimensionName(dimension) + "右键重生锚会爆炸，不点", null));
        }
        return Optional.empty();
    }

    private static String dimensionName(String dimension) {
        return switch (dimension) {
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            case "minecraft:overworld" -> "主世界";
            default -> dimension;
        };
    }

    private static String lower(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    /** 交互结论结算后的走向。 */
    sealed interface Settlement {
        /** 生效了：记进变化，做满次数就完成。 */
        record Applied() implements Settlement {}

        /** 出乎预料：交互本身生效了，世界变成了预料之外的样子；结果照实写，按完成收尾，设计对不对交给 LLM。 */
        record Unexpected(String scene) implements Settlement {}

        /** 没能确认：部分完成，进未确认清单，绝不再试。 */
        record Unconfirmed(String scene) implements Settlement {}

        /** 没生效，带该上报的问题。 */
        record NotApplied(Problem problem) implements Settlement {}
    }

    /**
     * 把交互结论结算成走向。
     *
     * @param result      交互动作给出的结论与现场
     * @param gameMessage 出手后动作栏新冒出的提示语（"箱子已上锁"、领地保护）；没有为空
     */
    static Settlement settle(InteractionResult result, Optional<String> gameMessage) {
        return settle(result, gameMessage, List.of());
    }

    /**
     * 把交互结论结算成走向，连同这一下前后聊天栏收到的服务端系统消息。
     *
     * <p>聊天栏的话只在没生效时拿来当原因：游戏没接、确认等满了，而这段时间里服务端在聊天栏说了话（模组的
     * "名额满了"、插件的领地保护），就按游戏拒绝交回、附上原话。生效了照常算生效，原话由调用方附进结果。
     * 不按文字猜哪条和这一下有关：同一段时间里别人进出服务器的提示也会进来。
     *
     * @param chatMessages 出手后聊天栏收到的服务端系统消息原话，按收到先后
     */
    /** 对着空气用东西却没生效时的建议：写清怎么把用法改成对着脚下地面、指定方块或生物用。 */
    static final String AIR_USE_SUGGESTION = "不给 target 或只给 target=here（不带 face）都是对着空气用；"
            + "放在脚下地面上请传 target=here 并配 face=up，对指定方块用给那一格（观察编号 b# 或坐标，放顶面配 face=up），对生物用给 e#";

    static Settlement settle(InteractionResult result, Optional<String> gameMessage, List<String> chatMessages) {
        return settle(result, gameMessage, chatMessages, false, null);
    }

    /**
     * 把交互结论结算成走向：对着空气用却没生效时，说明这次是对着空气用的，并写清要对着方块还是生物怎么传参，
     * 不让调用方把用法不对误当成这件东西不能用。
     */
    static Settlement settle(InteractionResult result, Optional<String> gameMessage, List<String> chatMessages,
            boolean onAir, String heldItem) {
        Optional<Problem> chatRefusal = chatMessages.isEmpty() ? Optional.empty()
                : Optional.of(Problem.of(Problem.Kind.REFUSED_BY_GAME,
                        "游戏没有接这一下，聊天栏里说：" + String.join("；", chatMessages), null));
        return switch (result.verdict()) {
            case APPLIED -> new Settlement.Applied();
            case UNEXPECTED -> new Settlement.Unexpected(result.scene());
            // 确认等满了却在聊天栏收到了服务端的话：多半是被拒绝了，附原话交回，不再报说不清。
            case UNCONFIRMED -> chatRefusal.<Settlement>map(Settlement.NotApplied::new)
                    .orElseGet(() -> new Settlement.Unconfirmed(result.scene()));
            // 游戏说了为什么就附原话（动作栏优先，其次聊天栏）；对着空气用却没反应时说明是对着空气用的并给出对准方块或生物的用法。
            case NOT_APPLIED -> new Settlement.NotApplied(gameMessage
                    .map(message -> Problem.of(Problem.Kind.REFUSED_BY_GAME, "游戏拒绝了：" + message, null))
                    .or(() -> chatRefusal)
                    .orElseGet(() -> {
                        if (onAir) {
                            String what = heldItem == null || heldItem.isBlank() ? "手上的东西" : heldItem;
                            return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                                    "对着空气用了 " + what + " 没有效果：" + result.scene(), AIR_USE_SUGGESTION);
                        }
                        return Problem.of(Problem.Kind.NOT_POSSIBLE_HERE,
                                "这样用没有效果：" + result.scene(), null);
                    }));
        };
    }

    /**
     * 某一次没有效果就停下：一次都没做成按失败；做成过几次按部分完成，写明做成了几次，
     * 不把没做成的次数也算进去。
     */
    static TaskResult stopped(long appliedTimes, long count, Problem problem) {
        if (appliedTimes == 0) {
            // 一句话结论只带卡住的那句；去拿东西没拿到这类原因可能很长，展开的只留在问题里一份。
            return TaskResult.failed("用东西没有生效：" + problem.headline(), problem);
        }
        return TaskResult.builder(TaskResult.Status.PARTIAL,
                        "确认生效了 " + appliedTimes + " 次，第 " + (appliedTimes + 1) + " 次没有效果，停下了（要做 "
                                + count + " 次）")
                .problem(problem).build();
    }

    /** 这一下没能确认生效没有：部分完成，写明前面确认过几次；不盲目重做，免得再花一次材料去赌。 */
    static TaskResult unconfirmed(long appliedTimes, String scene) {
        String before = appliedTimes == 0 ? "" : "前面确认生效了 " + appliedTimes + " 次；";
        return TaskResult.builder(TaskResult.Status.PARTIAL,
                before + "这一下没能确认有没有生效，不盲目重做：" + scene).build();
    }

    /**
     * 连点（keep_trying）期间的位置跳变监视：角色处于原地静止连续右键时，若服务端强制瞬移或位置大幅跳变
     * （单次位移或累计偏离超过 1.5 格），或者连续两次尝试均出现超过 0.6 格的异常位置跳动，
     * 立刻中止连点并如实回报，防止误触瞬移道具/服务端权限机制时继续无脑连点导致被踢出。
     */
    static final class PositionShiftWatch {
        /** 单次点击或累计偏离超过该距离（格）即视为意外位置跳变。 */
        static final double SINGLE_JUMP_THRESHOLD = 1.5;
        /** 连续多次点击间每次偏移超过该距离（格）即视为连续异常跳动。 */
        static final double CONSECUTIVE_SHIFT_THRESHOLD = 0.6;
        /** 连续异常跳动达到此次数即中止连点。 */
        static final int CONSECUTIVE_SHIFT_LIMIT = 2;

        private Vec3 origin;
        private Vec3 clickStart;
        private int consecutiveShifts;

        /** 进入靠近或新一轮交互前清空基准位置，避免把合法走位当成异常跳变。 */
        void reset() {
            origin = null;
            clickStart = null;
            consecutiveShifts = 0;
        }

        /** 本次连点出手前冻结位置：首次出手记作整段连点的初始站位。 */
        void beforeClick(Vec3 current) {
            if (current == null) return;
            if (origin == null) origin = current;
            clickStart = current;
        }

        /** 交互等待确认的每一刻检查是否发生大幅瞬移或偏离起点过远。 */
        Optional<Problem> checkDuringTick(Vec3 current) {
            if (current == null) return Optional.empty();
            if (clickStart != null && clickStart.distanceTo(current) > SINGLE_JUMP_THRESHOLD) {
                return Optional.of(jumpProblem(clickStart, current, "交互连点期间角色发生意外位置跳变"));
            }
            if (origin != null && origin.distanceTo(current) > SINGLE_JUMP_THRESHOLD) {
                return Optional.of(jumpProblem(origin, current, "交互连点期间角色偏离原站位过远"));
            }
            return Optional.empty();
        }

        /** 一次连点未生效准备继续下一击前复核：捕获单次大幅跳变与连续异常位置跳动。 */
        Optional<Problem> checkBeforeRetry(Vec3 current) {
            Optional<Problem> largeJump = checkDuringTick(current);
            if (largeJump.isPresent()) return largeJump;
            if (current == null || clickStart == null) return Optional.empty();
            double stepDistance = clickStart.distanceTo(current);
            if (stepDistance > CONSECUTIVE_SHIFT_THRESHOLD) {
                consecutiveShifts++;
                if (consecutiveShifts >= CONSECUTIVE_SHIFT_LIMIT) {
                    return Optional.of(jumpProblem(clickStart, current,
                            "交互连点期间连续 " + consecutiveShifts + " 次发生异常位置跳动"));
                }
            } else {
                consecutiveShifts = 0;
            }
            return Optional.empty();
        }

        private static Problem jumpProblem(Vec3 from, Vec3 to, String headline) {
            double distance = from.distanceTo(to);
            String detail = String.format(Locale.ROOT,
                    "%s（位移 %.1f 格，从 (%.1f, %.1f, %.1f) 到 (%.1f, %.1f, %.1f)），已中止继续连点",
                    headline, distance, from.x, from.y, from.z, to.x, to.y, to.z);
            return Problem.of(Problem.Kind.STUCK, detail,
                    "先用 observe 检查当前位置、手中物品与周围状态，确认没有误触传送或被外力移位后再继续");
        }
    }

    private UseDecider() {}
}
