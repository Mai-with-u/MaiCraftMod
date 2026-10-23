// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

/**
 * 关界面的补试机会：会话到期限还没关上时，再请游戏关一次的机会只有一次，补试也到期限才认输。
 *
 * <p>实机教训：只请一次就认输的话，游戏可能根本没把界面关上，付款格里的东西留在开着的界面上，
 * 背包里看着像没了。等待期限不加新预算：补试重认领一份会话，期限按会话自己的关闭期限重算，
 * 两次尝试合计与"认领不了会话时自己数的期限"同一个量级。
 */
final class MenuCloseRetry {

    /** 已经用掉的那一次补试。 */
    private boolean used;

    /** 这一回失败后还能不能再补试：第一次失败给机会并记下已用，补试再失败就没有了。 */
    boolean grantOne() {
        if (used) return false;
        used = true;
        return true;
    }
}
