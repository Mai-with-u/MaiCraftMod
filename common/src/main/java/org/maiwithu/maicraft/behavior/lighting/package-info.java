// SPDX-License-Identifier: GPL-3.0-only
/**
 * 照明的玩家行为：量光（样本采样与分类、复测）、选灯位（资格与贪心）、随行插火——从灯位判断
 * 到换手、瞄准、插下、确认的整套放火把动作链，以及区域补光与太暗需求共用的随行补光设置。
 *
 * <p>成批放灯交给施工引擎，这里管"哪里暗、灯插哪、亮没亮"和顺手插一支火把怎么落地。
 */
package org.maiwithu.maicraft.behavior.lighting;
