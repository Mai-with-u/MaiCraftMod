// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.landing.spi.LandingMaterials;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/**
 * 联动模组交来的落地补料来源，登记表给它包的一层：模组停用后取不到（落地防护照旧说没东西接）；
 * 取到一半碰到模组接口对不上，就把这次取物按取不到收场，如实说原因，不让它变成生存需求的内部错误。
 */
final class CompatLandingMaterials implements LandingMaterials {

    private final CompatModule module;
    private final LandingMaterials materials;

    CompatLandingMaterials(CompatModule module, LandingMaterials materials) {
        this.module = Objects.requireNonNull(module, "module");
        this.materials = Objects.requireNonNull(materials, "materials");
    }

    @Override public Optional<Action> fetch(PlayerContext context, List<String> itemIds) {
        if (!module.active()) return Optional.empty();
        try {
            return materials.fetch(context, itemIds).map(Guarded::new);
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public String describe() {
        return materials.describe();
    }

    /** 取物过程每一步都收住模组接口对不上：之后算取不到，原因写进失败。 */
    private static final class Guarded implements Action {

        private final Action inner;
        private boolean broken;
        private String detail;

        Guarded(Action inner) {
            this.inner = inner;
        }

        @Override public ActionStatus tick(TickContext tick) {
            if (broken) return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, detail));
            try {
                return inner.tick(tick);
            } catch (ModApiMismatch failure) {
                broken = true;
                detail = "取出落地料时模组接口对不上：" + failure.getMessage();
                return ActionStatus.failed(Problem.of(Problem.Kind.UNSUPPORTED, detail));
            }
        }

        @Override public void pause() {
            if (broken) return;
            try {
                inner.pause();
            } catch (ModApiMismatch failure) {
                broken = true;
                detail = "暂停取落地料时模组接口对不上：" + failure.getMessage();
            }
        }

        @Override public void close() {
            if (broken) return;
            try {
                inner.close();
            } catch (ModApiMismatch failure) {
                broken = true;
                detail = "收尾取落地料时模组接口对不上：" + failure.getMessage();
            }
        }

        @Override public String describe() {
            return broken ? detail : inner.describe();
        }
    }
}
