// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.menu;

/**
 * 界面上的一下点击：在哪一格上、快速移动还是普通左右键、这一下会不会把东西拿到光标上。
 * 拿到光标上的要点给界面会话登记来源格，中途关界面才放得回原处。
 */
public record MenuClick(int slotId, boolean quickMove, int button, boolean takesToCursor) {}
