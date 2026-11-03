// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.world.entity.Entity;

import org.maiwithu.maicraft.ability.maid.spi.MaidSettings;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.Interruptibility;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 联动模组交来的改女仆设置的办法，登记表给它包的一层：模组停用后认不出她（能力按"联动没登记"说）；
 * 改到一半停用或碰到模组接口对不上，按不支持收场，已经改成的照样交代。
 */
final class CompatMaidSettings implements MaidSettings {

    private final CompatModule module;
    private final MaidSettings settings;

    CompatMaidSettings(CompatModule module, MaidSettings settings) {
        this.module = Objects.requireNonNull(module, "module");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public Optional<Maid> maid(PlayerContext context, Entity creature) {
        if (!module.active()) return Optional.empty();
        try {
            return settings.maid(context, creature).map(Guarded::new);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    /** 包一层的女仆：读名字碰到对不上时给一句说明，开始改时对不上第一刻按不支持收场。 */
    private final class Guarded implements Maid {

        private final Maid maid;

        Guarded(Maid maid) {
            this.maid = maid;
        }

        @Override public String name() {
            try {
                return maid.name();
            } catch (ModApiMismatch broken) {
                return "（读不出名字）";
            }
        }

        @Override public List<String> keys() {
            return maid.keys();
        }

        @Override public Configure configure(List<Setting> settings, Permissions permissions) {
            try {
                return new GuardedConfigure(maid.configure(settings, permissions));
            } catch (ModApiMismatch broken) {
                return new Broken(broken.getMessage());
            }
        }
    }

    /** 改设置的动作包一层：推进照转，模组半路停用按不支持收场；结局读的是动作自己记下的。 */
    private final class GuardedConfigure implements Configure {

        private final Configure configure;
        private final Action guarded;

        GuardedConfigure(Configure configure) {
            this.configure = configure;
            this.guarded = CompatAction.of(module, configure);
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

        @Override public Report report() {
            return configure.report();
        }
    }

    /** 一开始就对不上模组：第一刻就按不支持收场。 */
    private static final class Broken implements Configure {

        private final String why;

        Broken(String why) {
            this.why = why;
        }

        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, why, null));
        }

        @Override public String describe() {
            return "改女仆的设置（联动对不上）";
        }

        @Override public Report report() {
            return Report.nothing();
        }
    }
}
