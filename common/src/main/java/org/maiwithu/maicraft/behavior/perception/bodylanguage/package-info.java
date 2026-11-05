// SPDX-License-Identifier: GPL-3.0-only
/**
 * 看懂别的玩家冲角色做的肢体动作：蹲起、挥手、拍一下、拿出东西、点头、摇头、原地跳、扔东西、走开又回头。
 *
 * <p>从客户端收得到的东西（同步的姿势与视角、挥手包、挖方块进度包、伤害包、新掉落物）认出来，
 * 按人归成一段发事件 {@code player_body_language}。只报冲着角色的、看得见的；拍一下是身体感觉得到的，背后也报。
 * 被拍了一下当刻回头看他几秒（借控制循环的打断机制插进来，看完手上的活接着做）。
 *
 * <p>Java 类型名里不用 Body（这个词保留给"角色"以外的旧叫法，构建检查拦着），肢体语言在类型名里写 Nonverbal；
 * MCP 上的名字照旧是 body_language。只指向感知的包根（观察编号、方位说法）与看向（转头、对准），它们不引用这里。
 */
package org.maiwithu.maicraft.behavior.perception.bodylanguage;
