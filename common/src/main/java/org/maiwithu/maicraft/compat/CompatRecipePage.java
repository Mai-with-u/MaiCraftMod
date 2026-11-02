// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat;

import java.util.Objects;

import org.maiwithu.maicraft.behavior.recipe.OpensRecipePage;

/**
 * 联动模组交来的"翻开配方页"，登记表给它包的一层：模组停用后回答翻不开；碰到模组接口对不上，
 * 联动入口已经把它转成 ModApiMismatch 并停用模组，这里按翻不开收场，闲时看就不翻页。
 */
public final class CompatRecipePage implements OpensRecipePage {

    private final CompatModule module;
    private final OpensRecipePage page;

    public CompatRecipePage(CompatModule module, OpensRecipePage page) {
        this.module = Objects.requireNonNull(module, "module");
        this.page = Objects.requireNonNull(page, "page");
    }

    @Override public boolean open(String itemId, boolean uses) {
        if (!module.active()) return false;
        try {
            return page.open(itemId, uses);
        } catch (ModApiMismatch broken) {
            return false;
        }
    }
}
