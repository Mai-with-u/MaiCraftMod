// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 第一次装上三项都关着，画面里不多出东西；开关按下的选择存在 config 目录里，重启照旧。 */
class PanelSettingsTest {
    @Test
    void 第一次装上什么都不画(@TempDir Path config) {
        PanelSettings settings = new PanelSettings(config);
        assertSame(PanelLevel.OFF, settings.level(), "面板默认关着");
        assertFalse(settings.pathLines(), "导航路线默认不画");
        assertFalse(settings.blueprintOverlay(), "施工预览默认不画");
    }

    @Test
    void 打开施工预览后重启还开着(@TempDir Path config) throws IOException {
        new PanelSettings(config).blueprintOverlay(true);
        assertTrue(new PanelSettings(config).blueprintOverlay(), "存下的开关重启后照旧");
    }
}
