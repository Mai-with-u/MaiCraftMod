// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import net.minecraft.util.Mth;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 一回看：转过去 → 停一会儿（开界面的打开看几秒再关上）→ 下一处 → 都看完转回站定朝向。
 *
 * <p>看东西时目标点随机偏开三度以内，停着时视线也带一点漂移，不像监控摄像头盯死一个点；
 * 会动的东西跟着看，不见了就停在最后看的方向。被收手时开着的界面关上，镜头停在哪就是哪，不往回甩。
 * 转头不算真实进展，也不是哪个任务的动作：它只在闲着时由闲时看推进。
 */
final class LookOnce implements Action {

    /** 转一个方向最多转多久（刻）：人按 F8 拿回了角色、镜头不动时不卡在半路。 */
    static final int TURN_LIMIT_TICKS = 40;
    /** 等界面打开最多多久（刻）：背包一刻就开；容器要等服务器同步内容。 */
    static final int INVENTORY_LIMIT_TICKS = 20;
    static final int PEEK_LIMIT_TICKS = 60;
    /** 等界面关上最多多久（刻）。 */
    static final int CLOSE_LIMIT_TICKS = 20;
    /** 目标点随机偏开的幅度与停着时视线漂移的幅度（度）。 */
    static final float JITTER_DEGREES = 3f;
    static final float DRIFT_DEGREES = 1.5f;
    /** 远处只朝方位看时视线压在平视上下多少度以内。 */
    static final float FAR_PITCH_LIMIT = 20f;

    private enum Stage { TURN, OPEN, HOLD, CLOSE, BACK, DONE }

    private final LookPlans.Plan plan;
    private final Function<TickContext, TurnsHead> heads;
    private final LookingWorld world;
    private final LookScreens screens;
    private final RandomGenerator random;
    private final float restPitch;
    private final HeadMotion motion;
    private Stage stage = Stage.TURN;
    private int index;
    private int ticks;
    private int held;
    private boolean screenOpened;
    private boolean keepScreen;
    private Action peek;
    private float jitterYaw;
    private float jitterPitch;
    private float driftYaw;
    private float driftPitch;
    private int nextDrift;
    private Aim lastAim;

    /**
     * @param restPitch 看完转回站定朝向时俯仰停在哪（闲着时平常的高度）
     */
    LookOnce(LookPlans.Plan plan, Function<TickContext, TurnsHead> heads, LookingWorld world, LookScreens screens,
            RandomGenerator random, float restPitch) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.heads = Objects.requireNonNull(heads, "heads");
        this.world = Objects.requireNonNull(world, "world");
        this.screens = Objects.requireNonNull(screens, "screens");
        this.random = Objects.requireNonNull(random, "random");
        this.restPitch = restPitch;
        this.motion = new HeadMotion(plan.degreesPerSecond());
        if (plan.steps().isEmpty()) stage = Stage.BACK;
        else startStep();
    }

    @Override public ActionStatus tick(TickContext context) {
        if (stage == Stage.DONE) return ActionStatus.done();
        TurnsHead head = heads.apply(context);
        if (head == null) return ActionStatus.running();
        ticks++;
        switch (stage) {
            case TURN -> turn(context, head);
            case OPEN -> open(context);
            case HOLD -> hold(context, head);
            case CLOSE -> closing();
            case BACK -> back(head);
            default -> { }
        }
        return stage == Stage.DONE ? ActionStatus.done() : ActionStatus.running();
    }

    // 转过去：镜头到了目标附近、或转够了时限，开界面的去开，其余的停下来看。
    private void turn(TickContext context, TurnsHead head) {
        Optional<Aim> aim = aimAt(context, head, step().target());
        if (aim.isEmpty()) {
            // 要看的东西不见了（实体走了）：这一处跳过，不对着空处发呆。
            nextStep();
            return;
        }
        motion.aim(aim.get().yaw(), aim.get().pitch());
        boolean arrived = motion.step(head);
        if (arrived || ticks >= TURN_LIMIT_TICKS) {
            enter(step().opensScreen() ? Stage.OPEN : Stage.HOLD);
        }
    }

    // 开界面：背包按 E 打开；容器站在原地点开、内容同步完记进记忆；配方页翻到那一样。没开成就到此为止，不重试。
    private void open(TickContext context) {
        switch (step().screen()) {
            case LookPlans.Screen.Inventory inventory -> {
                if (screens.showInventory()) opened();
                else if (ticks >= INVENTORY_LIMIT_TICKS) enter(Stage.HOLD);
            }
            case LookPlans.Screen.Container container -> {
                if (peek == null) peek = screens.peek(container);
                ActionStatus status = peek.tick(context);
                if (status instanceof ActionStatus.Done) {
                    finishPeek();
                    opened();
                } else if (status instanceof ActionStatus.Failed || ticks >= PEEK_LIMIT_TICKS) {
                    // 点了没开、开了没同步上：只是顺手看一眼，不告诉 LLM，也不再点第二次。
                    finishPeek();
                    nextStep();
                }
            }
            case LookPlans.Screen.Recipe recipe -> {
                if (screens.openRecipePage(recipe.itemId(), recipe.uses())) opened();
                else enter(Stage.HOLD);
            }
            case LookPlans.Screen.None none -> enter(Stage.HOLD);
        }
    }

    private void opened() {
        screenOpened = true;
        enter(Stage.HOLD);
    }

    // 停着看：跟着会动的东西，视线带一点漂移；停够了开着界面的先关上，再去下一处。
    private void hold(TickContext context, TurnsHead head) {
        held++;
        if (screenOpened && !screens.oursOpen()) {
            // 界面被关掉了（人按了 Esc、服务器关了）：这一处就看到这里。
            screenOpened = false;
            nextStep();
            return;
        }
        if (!screenOpened) {
            drift();
            aimAt(context, head, step().target()).ifPresent(aim -> motion.aim(aim.yaw(), aim.pitch()));
            motion.step(head);
        }
        if (held >= step().holdTicks()) {
            // 上一回交过来的配方页这一回没翻成，界面也还开着：照样关上，不留着。
            if (screenOpened || screens.oursOpen()) enter(Stage.CLOSE);
            else nextStep();
        }
    }

    // 关界面：请游戏关上自己开的，关完（或等够了）再去下一处。
    private void closing() {
        if (screens.oursOpen() && ticks <= CLOSE_LIMIT_TICKS) {
            screens.closeOurs();
            return;
        }
        screenOpened = false;
        nextStep();
    }

    // 都看完了：转回站定朝向，俯仰回到平常的高度。
    private void back(TurnsHead head) {
        motion.aim(head.standingYaw(), restPitch);
        if (motion.step(head) || ticks >= TURN_LIMIT_TICKS) stage = Stage.DONE;
    }

    private void nextStep() {
        index++;
        if (index < plan.steps().size()) {
            startStep();
            enter(Stage.TURN);
        } else {
            enter(Stage.BACK);
        }
    }

    // 换一处看：目标点重新随机偏开一点；转头从镜头此刻所在接着转。
    private void startStep() {
        boolean exact = step().target() instanceof LookTarget.Standing;
        jitterYaw = exact ? 0f : (random.nextFloat() * 2f - 1f) * JITTER_DEGREES;
        jitterPitch = exact ? 0f : (random.nextFloat() * 2f - 1f) * JITTER_DEGREES;
        driftYaw = 0f;
        driftPitch = 0f;
        nextDrift = 0;
        held = 0;
        lastAim = null;
    }

    private void enter(Stage next) {
        stage = next;
        ticks = 0;
        if (next == Stage.HOLD) held = 0;
    }

    // 停着时视线每隔一会儿挪一点点：零点六到一点二秒换一次，幅度一度半以内。
    private void drift() {
        if (held < nextDrift) return;
        driftYaw = (random.nextFloat() * 2f - 1f) * DRIFT_DEGREES;
        driftPitch = (random.nextFloat() * 2f - 1f) * DRIFT_DEGREES;
        nextDrift = held + LookPace.between(12, 24, random);
    }

    // 这一处此刻要转到的方向：站定朝向按站定的算；会动的东西按它此刻在哪算，不见了停在最后看的方向。
    private Optional<Aim> aimAt(TickContext context, TurnsHead head, LookTarget target) {
        Aim aim = switch (target) {
            case LookTarget.Standing standing -> new Aim(head.standingYaw(), restPitch);
            case LookTarget.Direction direction -> new Aim(direction.yaw(), direction.pitch());
            default -> world.aim(context, target).orElse(lastAim);
        };
        if (aim == null) return Optional.empty();
        lastAim = aim;
        float pitch = aim.pitch() + jitterPitch + driftPitch;
        if (target instanceof LookTarget.Spot spot && spot.far()) {
            pitch = Mth.clamp(pitch, -FAR_PITCH_LIMIT, FAR_PITCH_LIMIT);
        }
        return Optional.of(new Aim(Aim.wrap(aim.yaw() + jitterYaw + driftYaw), Mth.clamp(pitch, -90f, 90f)));
    }

    private LookPlans.Step step() {
        return plan.steps().get(index);
    }

    private void finishPeek() {
        if (peek != null) {
            peek.close();
            peek = null;
        }
    }

    /**
     * 这一下看够了没有，能不能让给新来的请求：正在看的这一下先看够最短时间（转到位后零点八秒，
     * 开着界面的一点五秒）再换；在两处之间、在关界面、在转回来时随时可以换。
     */
    boolean heldEnough() {
        return switch (stage) {
            case TURN -> index > 0;
            case OPEN -> false;
            case HOLD -> held >= (screenOpened ? LookPace.MIN_SCREEN_TICKS : LookPace.MIN_HOLD_TICKS);
            case CLOSE, BACK, DONE -> true;
        };
    }

    /** 此刻开着配方页：连着查几样配方时不关界面，交给下一回直接翻过去。 */
    boolean showingRecipePage() {
        return screenOpened && stage == Stage.HOLD && step().screen() instanceof LookPlans.Screen.Recipe;
    }

    /** 收尾时不关界面：下一回接着用开着的配方页。 */
    void handOverScreen() {
        keepScreen = true;
    }

    /** 这一回开过界面还没关上。 */
    boolean screenOpen() {
        return screenOpened || peek != null;
    }

    // 被收手或被新请求替换：开着的界面关上（交给下一回的配方页除外），镜头停在哪就是哪。
    @Override public void close() {
        finishPeek();
        // 按"闲时看开的界面还开着"判断，不只看自己记的：背包请求发出去、界面下一刻才出来的那种也关上。
        if (!keepScreen && screens.oursOpen()) screens.closeOurs();
        screenOpened = false;
        stage = Stage.DONE;
    }

    @Override public String describe() {
        return stage == Stage.BACK ? "看完转回来" : "看一眼";
    }
}
