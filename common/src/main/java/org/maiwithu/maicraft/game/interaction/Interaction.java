// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.maiwithu.maicraft.game.menu.MenuActions;
import org.maiwithu.maicraft.game.menu.PendingMenuAction;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.player.PlayerInput;
import org.maiwithu.maicraft.game.world.InteractionRange;

/**
 * 把一次鼠标操作分成几刻完成：先瞄准，再出手，再观察有没有生效。
 * 左键实体是攻击，右键空气是使用手中物品（点按一下或持续按住）。
 * 它负责停步、转头和点击，不会自己走近目标。需要靠近时由外层任务先寻路。
 * 交互提交、容器界面与按键入口都从构造函数传入；每刻推进时传入当刻的角色上下文。
 */
public final class Interaction {

    private static final Logger LOG = LoggerFactory.getLogger(Interaction.class);

    public enum Status { RUNNING, DONE, FAILED }
    public enum Button { ATTACK, USE }

    /**
     * 说明按一下、重复按，还是持续按住。例如拉弓持续按住，开箱子按一下。
     * 重复动作按 limit 计次；持续使用物品按 maxHold 决定何时松开，零表示等物品自行结束。
     */
    public static final class Timing {
        final boolean hold;
        final int limit;     // 重复操作至少执行一次；持续按住时忽略此值。
        final int interval;  // 重复点击之间的游戏刻数，至少为一。
        final int maxHold;   // 持续按住的最大游戏刻数；0 表示等待物品自行完成。

        private Timing(boolean hold, int limit, int interval, int maxHold) {
            this.hold = hold;
            this.limit = limit;
            this.interval = interval;
            this.maxHold = maxHold;
        }

        /** 只完成一次点击。 */
        public static Timing once() {
            return new Timing(false, 1, 1, 0);
        }

        /** 重复点击；两次之间先等待 interval 次 tick，次数和间隔至少为一。 */
        public static Timing repeat(int times, int interval) {
            return new Timing(false, Math.max(1, times), Math.max(1, interval), 0);
        }

        /** 持续使用，直到食物吃完等行为自行结束。 */
        public static Timing hold() {
            return new Timing(true, -1, 1, 0);
        }

        /** 持续使用指定刻数后松开，例如拉弓后放箭；具体计数从使用得到确认后开始。 */
        public static Timing hold(int maxTicks) {
            return new Timing(true, -1, 1, Math.max(1, maxTicks));
        }
    }

    private static final int CONFIRM_TIMEOUT_TICKS = 20;

    private final LocalPlayer player;
    private final InteractionSender sender;
    private final MenuActions menuActions;
    private final PlayerInput playerInput;
    private final Button button;
    private final Entity entity;      // 非空时表示实体目标。
    private final InteractionHand hand;
    private final Timing timing;

    /** 左键打实体时由调用方给的"打成了"条件（展示框这类不掉血的目标）；为 null 时按活物挨打的三证据确认。 */
    private InteractionConfirmation strikeConfirmation;
    private PendingMenuAction closePending;
    private PendingInteraction pending;
    private boolean releasing;
    private int fires;
    private int cooldown;             // 距下一次重复点击还需等待的游戏刻数。
    private int held;                 // 右键空气时，物品已持续使用的游戏刻数。
    private boolean hardFail;         // 一次点击遇到不可恢复错误时阻止后续操作。
    private String failReason = "交互没成";
    /** 近战上一次记下的"还没出手"原因。 */
    private String lastAttackWait = "";
    /** 镜头已经转到位、准星却连着落在方块或空气上的刻数：到了上限这一下按打不到收场，不无限等。 */
    private int aimBlockedTicks;
    /** 镜头到位后准星连着这么多刻落不到它身上，就当这一下打不到（它站在台沿上被台面挡着、离得差一点）。 */
    static final int AIM_BLOCKED_GIVE_UP_TICKS = 10;
    private final PlantClearing plants;
    /** 仅在单次实体交互实例中保留并平滑瞄准点。 */
    private Vec3 trackedEntityAim;

    private Interaction(LocalPlayer player, InteractionSender sender, MenuActions menuActions,
                        PlayerInput playerInput, Button button, Entity entity,
                        InteractionHand hand, Timing timing) {
        this.player = player;
        this.sender = sender;
        this.plants = new PlantClearing(sender);
        this.menuActions = menuActions;
        this.playerInput = playerInput;
        this.button = button;
        this.entity = entity;
        this.hand = hand;
        this.timing = timing;
    }

    /** 左键攻击实体一次，并遵循原版攻击冷却。 */
    public static Interaction attackEntity(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                           PlayerInput playerInput, Entity target) {
        return new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.ATTACK, target, InteractionHand.MAIN_HAND, Timing.once());
    }

    /**
     * 左键攻击实体一次，打没打成按调用方给的条件确认：展示框、盔甲架这类不掉血、不红闪的目标，
     * 由调用方说清打成了是什么样（框里的东西弹出来了），不套用活物的掉血红闪。
     */
    public static Interaction attackEntity(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                           PlayerInput playerInput, Entity target, InteractionConfirmation struck) {
        Interaction interaction = attackEntity(context, sender, menuActions, playerInput, target);
        interaction.strikeConfirmation = struck;
        return interaction;
    }

    /** 按给定 {@link Timing} 在空气中右键使用手持物品；{@code hold()} 用于吃完食物，{@code hold(n)} 用于拉弓后放箭。 */
    public static Interaction useInAir(PlayerContext context, InteractionSender sender, MenuActions menuActions,
                                       PlayerInput playerInput, InteractionHand hand, Timing timing) {
        return new Interaction(context.localPlayer(), sender, menuActions, playerInput,
                Button.USE, null, hand, timing);
    }

    /**
     * 按原版准星规则从眼部沿当前视线发出一条射线，返回更近的方块或实体，否则为 MISS。
     * 墙会遮住后方生物，实体只在方块命中位置以内搜索；{@code reach} 为 4.5 时对应生存模式距离。
     */
    // 沿玩家当前准星看出去。先找到挡路方块，再看前面是否有更近的可点击实体，不能点穿墙。
    public static HitResult nativeRaytrace(LocalPlayer player, double reach) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 reachVec = player.getViewVector(1.0f).scale(reach);
        Vec3 end = eye.add(reachVec);
        AABB box = player.getBoundingBox().expandTowards(reachVec).inflate(1.0);
        HitResult block = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double maxSq = block.getType() == HitResult.Type.MISS
                ? reach * reach : block.getLocation().distanceToSqr(eye);
        EntityHitResult ent = ProjectileUtil.getEntityHitResult(
                player, eye, end, box, e -> !e.isSpectator() && e.isPickable(), maxSq);
        return ent != null ? ent : block;   // 实体比方块命中更近时优先返回实体，否则返回方块命中或未命中。
    }

    public String failReason() {
        return failReason;
    }

    // 先处理还没关好的界面，再推进对空气使用物品或对实体的攻击。
    // 已经发出的动作优先等结果，不因这次动作刚打开了箱子就立刻把箱子关上。
    public Status tick(PlayerContext context) {
        if (pending == null
                && (closePending != null || player.containerMenu != player.inventoryMenu
                || Minecraft.getInstance().screen != null)) {
            Status menuStatus = awaitWorldInputReady(context);
            if (menuStatus != null) {
                return menuStatus;
            }
        }
        if (button == Button.USE && entity == null) {
            return useAir(context);
        }
        return discrete(context);
    }

    /** 容器界面打开时不向世界发送攻击或使用按键，避免点击穿过菜单。 */
    // 发一次关闭菜单请求，然后逐刻等它完成；关闭失败就不能继续向世界点击。
    private Status awaitWorldInputReady(PlayerContext context) {
        if (closePending == null) {
            closePending = menuActions.close(context, CONFIRM_TIMEOUT_TICKS);
            return Status.RUNNING;
        }
        if (!closePending.terminal()) {
            closePending = menuActions.poll(context, closePending);
        }
        if (!closePending.terminal()) {
            return Status.RUNNING;
        }
        PendingMenuAction.Status status = closePending.status();
        String detail = closePending.detail();
        closePending = null;
        if (status == PendingMenuAction.Status.CONFIRMED_APPLIED) {
            // 关界面本身占用了这一刻的交互机会；世界动作留到下一刻，不在同一刻再出手。
            return context.canInteractThisTick() ? null : Status.RUNNING;
        }
        failReason = "出手前没能关掉开着的界面：" + detail;
        return Status.FAILED;
    }

    // ---- 右键空气：点按或持续使用食物、弓等物品 ----

    // 例如吃东西：先按一次右键，观察手中物品、菜单或使用状态，再决定继续按住还是松开。
    // 点一下只要求这次使用得到确认；持续使用才会等到吃完或到达按住时限。
    private Status useAir(PlayerContext context) {
        playerInput.halt(player);
        if (pending == null) {
            if (player.isUsingItem()) {
                failReason = "手上已经在用别的东西，没去动它";
                return Status.FAILED;
            }
            if (!context.canInteractThisTick()) return Status.RUNNING;
            ItemStack before = player.getItemInHand(hand).copy();
            int beforeMenu = player.containerMenu.containerId;
            InteractionConfirmation confirmation = InteractionConfirmation.anyOf(
                    InteractionConfirmation.heldItemChanged(hand, before),
                    InteractionConfirmation.menuChanged(beforeMenu),
                    c -> c.localPlayer().isUsingItem()
                            ? InteractionConfirmation.Verdict.APPLIED
                            : InteractionConfirmation.Verdict.PENDING);
            PendingInteraction submitted = sender.useItem(
                    context, hand, confirmation, timing.hold
                            ? Math.max(CONFIRM_TIMEOUT_TICKS, (int) Math.min(1200L,
                                    (long) before.getUseDuration(player) + CONFIRM_TIMEOUT_TICKS))
                            : CONFIRM_TIMEOUT_TICKS);
            // 提交入口这一刻不收（上一次使用还没结清）：不算失败，下一刻原样再提交。
            if (submitted == null) return Status.RUNNING;
            pending = submitted;
            return Status.RUNNING;
        }
        pending = sender.poll(context, pending);
        if (!pending.terminal()) {
            return Status.RUNNING;
        }
        // 已经发出松开请求时，只等待松开结果，不重新开始使用。
        if (releasing) {
            if (pending.status() == PendingInteraction.Status.CONFIRMED_APPLIED) {
                return Status.DONE;
            }
            failReason = "松开使用键没得到确认：" + pending.detail();
            return Status.FAILED;
        }
        if (pending.status() != PendingInteraction.Status.CONFIRMED_APPLIED) {
            failReason = "使用手上的东西没得到确认：" + pending.detail();
            return Status.FAILED;
        }
        if (!timing.hold) {
            return Status.DONE;
        }
        // 持续使用是否还归本交互管，以交互提交方认不认这笔使用为准。
        if (player.isUsingItem() && !sender.ownsItemUse(pending)) {
            failReason = "按着的这次使用已经不归这次交互管了";
            return Status.FAILED;
        }
        if (!player.isUsingItem()) {
            return Status.DONE;
        }
        if (timing.maxHold > 0 && held + 1 >= timing.maxHold && !context.canInteractThisTick()) {
            return Status.RUNNING;
        }
        if (timing.maxHold > 0 && ++held >= timing.maxHold) {
            PendingInteraction released = sender.releaseUsingItem(context, pending);
            // 本刻没有交互机会就先不松：按住投影两刻内自然过期，下一刻再发松开。
            if (released != null) {
                pending = released;
                releasing = true;
            }
            return Status.RUNNING;
        }
        return Status.RUNNING;
    }

    // ---- 离散操作：攻击实体（单次） ----

    // 一次点击没确认完就不计次；确认一次后先等间隔，再开始下一次。
    private Status discrete(PlayerContext context) {
        if (cooldown > 0) {
            cooldown--;
            return Status.RUNNING;
        }
        boolean fired = fireAttackEntity(context);
        if (hardFail) return Status.FAILED;
        if (!fired) return Status.RUNNING;             // 攻击冷却未结束时等待，不将其视为失败。
        if (++fires >= timing.limit) return Status.DONE;
        cooldown = timing.interval;
        return Status.RUNNING;
    }

    // 上次攻击尚未确认就继续等；准备新攻击时先停止移动、瞄准活着的目标，
    // 还要等攻击冷却和目标的短暂无敌时间结束，才发出攻击。
    private boolean fireAttackEntity(PlayerContext context) {
        if (pending != null) return settleAttack(context);
        if (entity == null || !entity.isAlive()) {
            failReason = "要打的目标已经不在了";
            hardFail = true;
            return false;
        }
        playerInput.halt(player);
        if (plants.busy(context)) return false;
        Vec3 aimPoint = stableEntityAimPoint(entity);
        playerInput.lookAt(player, aimPoint);
        // 近战遵守当前身体的真实实体触及属性，不能沿用右键方块的四点五格范围。
        HitResult aimed = nativeRaytrace(player, InteractionRange.entityReach(player));
        boolean onTarget = aimed instanceof EntityHitResult entityHit && entityHit.getEntity() == entity;
        LOG.debug("[近战瞄点] {}", MeleeAim.describe(player, entity, aimPoint, aimed,
                strikeAimSettled(onTarget, player.getViewVector(1.0f), aimPoint.subtract(player.getEyePosition()))));
        // 镜头到位：视线离瞄点 7° 以内，或准星已经先碰到它。对着横移的目标瞄点提前了，镜头对着的是它此刻的身体，
        // 离提前后的瞄点还差两刻的位移（3 格外疾跑横移约 12°），只按 7° 判就一直等；准星落在它身上这一刀就打得中。
        if (!strikeAimSettled(onTarget, player.getViewVector(1.0f), aimPoint.subtract(player.getEyePosition()))) {
            return attackWait("镜头还没转到");
        }
        if (!onTarget) {
            if (plants.clear(context, aimed)) return attackWait("先砍掉挡在前面的草叶");
            String blocked = MeleeAim.aimBlockedBy(aimed, player.getEyePosition().distanceTo(aimPoint));
            // 镜头到位了准星还落不到它身上（它站在柱顶、台沿上被台面挡着，或离得差一点）：等几刻给镜头收尾，
            // 还不行就按打不到收场交回去，让走位换个方位或记成够不着——不在这儿无限等，等到任务的停滞判定来收。
            if (++aimBlockedTicks >= AIM_BLOCKED_GIVE_UP_TICKS) {
                failReason = "够得着却打不到：" + blocked;
                hardFail = true;
                return false;
            }
            return attackWait(blocked);
        }
        aimBlockedTicks = 0;
        boolean recovering = entity instanceof LivingEntity living
                && living.hurtTime > 0;
        // 目标还在短暂无敌期、主手这一刻才换上（这一刀会按旧东西算，下一刻原版就清冷却）、攻击条没恢复到位，
        // 都先等；否则伤害会被吞掉或打折。够格线与换手隔一刻都按攻击充能那一份。
        boolean handSettled = AttackCharge.handSettled(context.mainHandHeldSince(), context.clientTick());
        if (recovering || !handSettled || player.getAttackStrengthScale(0.0f) < AttackCharge.READY
                || !context.canInteractThisTick()) {
            return attackWait(recovering ? "它刚挨过打" : !handSettled ? "主手这一刻才换上，隔一刻再出手"
                    : "攻击条还没回满或这一刻没有交互机会");
        }
        PendingInteraction submitted = sender.attack(
                context,
                entity,
                strikeConfirmation != null ? strikeConfirmation : InteractionConfirmation.entityStruck(player, entity),
                CONFIRM_TIMEOUT_TICKS);
        // 提交入口这一刻不收（上一次出手如举盾还没结清）：不算失败，下一刻原样再挥。
        if (submitted == null) {
            return attackWait("上一动作还在等确认，交互入口这一刻不收这一刀");
        }
        pending = submitted;
        return false;
    }

    // 近战这一下为什么还没出手：换了原因才记一行，实机对得上卡在哪一关（10-14 点名杀羊三十秒没挥，看不出原因）。
    private boolean attackWait(String why) {
        if (!why.equals(lastAttackWait)) {
            lastAttackWait = why;
            LOG.info("[近战] 打 {} 还没出手：{}", entity == null ? "?" : entity.getType().getDescriptionId(), why);
        }
        return false;
    }

    private boolean settleAttack(PlayerContext context) {
        // 沿原确认条件等待，不使用耐久、死亡记账或实体消失自行补造命中；已终结的确认可直接读历史结果。
        if (!pending.terminal()) {
            pending = sender.poll(context, pending);
        }
        if (!pending.terminal()) return false;
        PendingInteraction.Status status = pending.status();
        String detail = pending.detail(); pending = null;
        if (status == PendingInteraction.Status.CONFIRMED_APPLIED) return true;
        failReason = "攻击没得到确认：" + detail;
        hardFail = true;
        return false;
    }

    /** 视线方向与"眼睛到瞄点"差不超过 7°：转头到位的那条线。 */
    static boolean withinAimTolerance(Vec3 view, Vec3 toAimPoint) {
        if (toAimPoint.lengthSqr() < 1.0e-8) return true;
        return view.normalize().dot(toAimPoint.normalize()) >= Math.cos(Math.toRadians(7.0));
    }

    /**
     * 近战出手前镜头算不算到位：准星射线已经先碰到它，或视线离瞄点 7° 以内。打动着的目标瞄点提前了，
     * 镜头对着它此刻的身体时离瞄点本身还差一截，准星落在它身上就算到位。
     */
    static boolean strikeAimSettled(boolean crosshairOnTarget, Vec3 view, Vec3 toAimPoint) {
        return crosshairOnTarget || withinAimTolerance(view, toAimPoint);
    }

    // 近战瞄点：瞄它身上离眼睛最近的那一点（按 MeleeAim 算）；相邻帧只挪一部分，怪小幅晃动时镜头不猛抖。
    private Vec3 stableEntityAimPoint(Entity target) {
        AABB box = MeleeAim.leadBox(player, target);
        // 最近的那一点被台面挡着就瞄它露出来的部位（同一份近战瞄点）。
        Vec3 wanted = MeleeAim.visiblePoint(player, box);
        if (trackedEntityAim == null || trackedEntityAim.distanceToSqr(wanted) > 4.0) {
            trackedEntityAim = wanted;
        } else {
            trackedEntityAim = MeleeAim.insetClamp(trackedEntityAim.lerp(wanted, 0.55), box);
        }
        return trackedEntityAim;
    }

    /** 停止发出操作；原生使用动作的松开仍有确认，必须交由运行时完成结算。 */
    // 正在持续使用物品就发出松开，然后停止移动。
    // 未确认记录没有在此退役，外层结束时仍需处理它。
    public void stop(PlayerContext context) {
        plants.stop(context);
        if (pending != null && pending.kind() == PendingInteraction.Kind.USE_ITEM
                && player.isUsingItem() && !releasing) {
            // 本刻没有交互机会时不发松开：按住使用键的投影两刻内自然过期，原版就看到松键。
            if (context.canInteractThisTick()
                    && (sender.ownsItemUse(pending) || entity != null || !timing.hold)) {
                PendingInteraction released = sender.releaseUsingItem(context, pending);
                if (released != null) {
                    pending = released;
                    releasing = true;
                }
            }
        }
        playerInput.halt(player);
    }
}
