// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.bootstrap;

import java.nio.file.Path;
import java.util.Objects;

import org.maiwithu.maicraft.kernel.storage.DocumentStore;

/**
 * 存放位置：这个世界的文档库与世界身份编号、实例的配置目录——
 * 设计库、机器档案与导出按它们落盘。新加一个落盘的去处，加在这里并传入所在组即可。
 */
record Stores(
        DocumentStore worldDocuments,
        /** 这个世界的身份编号：机器档案按它分开存。 */
        String worldKey,
        /** 这个游戏实例的配置目录：设计库放在这里，导出写到实例的 schematics 目录。 */
        Path configDirectory) {

    Stores {
        Objects.requireNonNull(worldDocuments, "worldDocuments");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(configDirectory, "configDirectory");
    }
}
