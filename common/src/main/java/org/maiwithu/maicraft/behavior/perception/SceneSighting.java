// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception;

/**
 * 场景里带观察编号的一条观察：实体、地形特征、设施、告示牌、模组的东西。
 *
 * <p>场景把五路条目收在一份列表里；按编号查此刻条目、失效时撤下条目、列出与远处计数，
 * 都走这一份，不再各路抄一遍——新增一种可观察物时登记进这份列表加渲染分支就够了。
 * 条目是什么种类由条目自己的类型说，各视图按种类解包渲染，不立统一自述接口。
 */
public sealed interface SceneSighting permits SceneEntity, TerrainFeature, FacilitySighting, SceneSign, SceneThing {

    /** 这条观察的编号。 */
    String id();
}
