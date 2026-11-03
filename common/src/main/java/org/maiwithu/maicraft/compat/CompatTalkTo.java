// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.chat.spi.TalkTo;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 联动模组交来的说话办法，登记表给它包的一层：模组停用后认不出它的生物（说话能力按"联动没登记"说）；
 * 读它的状态时碰到模组接口对不上，按听不了如实交回；说到一半停用或对不上，按不支持收场。
 */
final class CompatTalkTo implements TalkTo {

    private final CompatModule module;
    private final TalkTo talk;

    CompatTalkTo(CompatModule module, TalkTo talk) {
        this.module = Objects.requireNonNull(module, "module");
        this.talk = Objects.requireNonNull(talk, "talk");
    }

    @Override public Optional<Listener> listener(PlayerContext context, Entity creature) {
        if (!module.active()) return Optional.empty();
        try {
            return talk.listener(context, creature).map(Guarded::new);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 包一层的听法：读名字、长度、在不在想都可能碰模组，对不上时如实说听不了。 */
    private final class Guarded implements Listener {

        private final Listener listener;

        Guarded(Listener listener) {
            this.listener = listener;
        }

        @Override public String name() {
            try {
                return listener.name();
            } catch (ModApiMismatch broken) {
                return "（读不出名字）";
            }
        }

        @Override public int maxLength() {
            try {
                return listener.maxLength();
            } catch (ModApiMismatch broken) {
                return Integer.MAX_VALUE;
            }
        }

        @Override public Optional<Problem> refusal() {
            try {
                return listener.refusal();
            } catch (ModApiMismatch broken) {
                return Optional.of(Problem.of(Problem.Kind.UNSUPPORTED, broken.getMessage(), null));
            }
        }

        @Override public boolean thinking() {
            try {
                return listener.thinking();
            } catch (ModApiMismatch broken) {
                return false;
            }
        }

        @Override public Talk talk(String message, int maxSeconds, boolean typewriter, Permissions permissions) {
            try {
                return new GuardedTalk(listener.talk(message, maxSeconds, typewriter, permissions));
            } catch (ModApiMismatch broken) {
                return new Broken(broken.getMessage());
            }
        }
    }

    /** 说话动作包一层：推进照转，模组半路停用按不支持收场；结局读的是动作自己记下的，不碰模组。 */
    private final class GuardedTalk implements Talk {

        private final Talk talk;
        private final Action guarded;

        GuardedTalk(Talk talk) {
            this.talk = talk;
            this.guarded = CompatAction.of(module, talk);
        }

        @Override public ActionStatus tick(TickContext context) {
            return guarded.tick(context);
        }

        @Override public Interruptibility interruptibility() {
            return guarded.interruptibility();
        }

        @Override public Interruptibility interruptibility(TickContext context) {
            return guarded.interruptibility(context);
        }

        @Override public boolean stopSafely() {
            return guarded.stopSafely();
        }

        @Override public boolean stoppedSafely() {
            return guarded.stoppedSafely();
        }

        @Override public void pause() {
            guarded.pause();
        }

        @Override public void close() {
            guarded.close();
        }

        @Override public String describe() {
            return guarded.describe();
        }

        @Override public Heard heard() {
            return talk.heard();
        }
    }

    /** 一开口就对不上模组：第一刻就按不支持收场。 */
    private static final class Broken implements Talk {

        private final String why;

        Broken(String why) {
            this.why = why;
        }

        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, why, null));
        }

        @Override public String describe() {
            return "说话（联动对不上）";
        }

        @Override public Heard heard() {
            return Heard.nothing();
        }
    }
}
