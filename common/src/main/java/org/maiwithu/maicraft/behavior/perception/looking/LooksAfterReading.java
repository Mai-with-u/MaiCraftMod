// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

/**
 * 读后看的入口：读信息的路径读完了告诉闲时看"刚读了什么"，角色闲着时就顺着看过去。
 *
 * <p>只交代读了什么，当场返回，不等角色看完；读到的内容不因此改变。只在客户端线程上调用。
 */
public interface LooksAfterReading {

    /** 什么都不做：测试与不在世界里时用。 */
    LooksAfterReading NONE = what -> { };

    /** LLM 刚读了这个。 */
    void justRead(WhatWasRead what);
}
