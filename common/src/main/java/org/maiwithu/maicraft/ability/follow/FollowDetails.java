// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.follow;

import org.maiwithu.maicraft.kernel.result.ResultDetails;

/**
 * 跟随任务的结果细节：跟的是谁、结束时的距离与最后一次看到目标在哪个方位。
 */
record FollowDetails(String targetId, Double keptDistance, String lastDirection) implements ResultDetails {}
