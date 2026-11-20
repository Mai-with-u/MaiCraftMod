// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.ae2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;

/**
 * AE2 内置 GuideME 手册资源（assets/ae2/ae2guide）的 NeoForge 读端：
 * 从已加载的 AE2 模组文件树扫描全部 Markdown 路径并按需读取原文，避免启动时把 125 篇全文常驻内存。
 */
final class AppliedEnergisticsToolsGuide {

    private static final String MOD_ID = "ae2";

    List<String> listPages() {
        Optional<Path> rootOpt = guideRoot();
        if (rootOpt.isEmpty()) return List.of();
        Path root = rootOpt.get();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(relative -> relative.endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    Optional<String> readPage(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return Optional.empty();
        Optional<Path> rootOpt = guideRoot();
        if (rootOpt.isEmpty()) return Optional.empty();
        Path root = rootOpt.get().normalize();
        Path target = root.resolve(relativePath).normalize();
        // 防止非法相对路径越出 ae2guide 根目录。
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static Optional<Path> guideRoot() {
        try {
            IModFileInfo modFile = ModList.get().getModFileById(MOD_ID);
            if (modFile == null || modFile.getFile() == null) return Optional.empty();
            Path root = modFile.getFile().findResource("assets", "ae2", "ae2guide");
            return root != null && Files.isDirectory(root) ? Optional.of(root) : Optional.empty();
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }
}
