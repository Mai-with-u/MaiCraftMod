// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

/**
 * 一回看要看的地方：一只会动的东西（跟着它看）、一个点、一个方向，或者转回站定朝向。
 */
sealed interface LookTarget {

    /** 场景里带观察编号的实体：看着它的头，它动就跟着看；不见了就停在最后看的方向。 */
    record Thing(String sceneId) implements LookTarget {}

    /**
     * 世界里的一个点。
     *
     * @param far 远（六十四格外）：只朝那个方位看，视线压在平视上下二十度以内
     */
    record Spot(double x, double y, double z, boolean far) implements LookTarget {}

    /** 一个方向：偏航与俯仰（俯仰正数是低头）。 */
    record Direction(float yaw, float pitch) implements LookTarget {}

    /** 转回站定朝向，俯仰回到闲着时平常的高度。 */
    record Standing() implements LookTarget {}
}
