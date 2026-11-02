// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;
import java.util.Optional;

import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquireVia;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemSource;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.kernel.task.Action;

/**
 * 联动模组交来的物品来源，登记表给它包的一层：模组停用后问价一律回答不支持并写明原因，
 * 开始执行时交回空让引擎换路；来源自己在问价、动手时碰到模组接口对不上，也在这里收住，
 * 不让它变成整个拿东西任务的内部错误。
 */
public final class CompatItemSource implements ItemSource {

    private final CompatModule module;
    private final ItemSource source;

    public CompatItemSource(CompatModule module, ItemSource source) {
        this.module = Objects.requireNonNull(module, "module");
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override public String describe() {
        return source.describe();
    }

    @Override public AcquireVia via() {
        return source.via();
    }

    @Override public SourceQuote quote(ItemRequest request, SourceContext context) {
        if (!module.active()) {
            return new SourceQuote.Unsupported(source.describe(), disabledReason());
        }
        // 联动来源这次都不认药水种类（ME 网络、随身背包、工具箱、仓储都只按物品 ID 数）：
        // 点名了种类的药水不从这里拿，如实说认不出，免得把水瓶当成抗火药水拿回来。
        if (request.wanted().potion() != null) {
            return new SourceQuote.Unavailable(source.describe(),
                    source.describe() + "认不出药水种类，点名了种类的药水不从这里拿");
        }
        try {
            return source.quote(request, context);
        } catch (ModApiMismatch broken) {
            return new SourceQuote.Unsupported(source.describe(), broken.getMessage());
        }
    }

    @Override public Optional<Action> begin(ItemRequest request, SourceQuote.Offer offer, SourceContext context) {
        if (!module.active()) {
            return Optional.empty();
        }
        try {
            // 交出的动作也包一层：推进到一半模组停用时按不支持收场，不当成内部错误。
            return source.begin(request, offer, context).map(action -> new CompatAction(module, action));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    @Override public Optional<Action> look(ItemRequest request, SourceContext context) {
        if (!module.active()) return Optional.empty();
        try {
            return source.look(request, context).map(action -> new CompatAction(module, action));
        } catch (ModApiMismatch broken) {
            return Optional.empty();
        }
    }

    private String disabledReason() {
        return module.name() + "的联动已停用：" + module.disabledReason().orElse("原因不明");
    }
}
