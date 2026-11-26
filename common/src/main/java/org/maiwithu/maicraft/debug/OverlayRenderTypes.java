// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import java.util.List;
import java.util.OptionalDouble;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

/**
 * 世界叠加共用的画法：透过地形的线（蓝图包围盒）、按深度区分可见与墙后遮挡的线（导航路线），
 * 以及按深度遮挡的半透明面（预览里的目标方块）。
 * 全部只写颜色不写深度，画完把深度开关与比较函数恢复为默认（开启深度测试、LEQUAL），不影响原版接下来画的东西。
 */
final class OverlayRenderTypes {

    private OverlayRenderTypes() {}

    /** 线：暂时关掉深度比较，隔着墙也看得见（用于蓝图包围盒）。 */
    static final RenderType LINES = Overlay.LINES;

    /** 眼前可见线：开启深度比较（LEQUAL），只画没有被方块挡住的实色清晰段。 */
    static final RenderType LINES_VISIBLE = Overlay.LINES_VISIBLE;

    /** 墙后遮挡线：开启深度比较（GREATER），只画被地形挡住的墙后弱透视段，与可见段零重叠。 */
    static final RenderType LINES_OCCLUDED = Overlay.LINES_OCCLUDED;

    /** 半透明面：按深度遮挡，正反面都画。 */
    static final RenderType FILLED = Overlay.FILLED;

    // RenderStateShard 的各段状态是受保护的常量，只有 RenderType 的子类拿得到，所以画法在这个内部类里定义。
    private static final class Overlay extends RenderType {
        // 导航路线分两遍画：可见段只过 LEQUAL、墙后段只过 GREATER，画完均恢复原版默认深度状态（开启 + LEQUAL）。
        private static final RenderStateShard VISIBLE_DEPTH = new RenderStateShard(
                "maicraft_overlay_visible_depth",
                () -> {
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                },
                () -> {
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                }) {};

        private static final RenderStateShard OCCLUDED_DEPTH = new RenderStateShard(
                "maicraft_overlay_occluded_depth",
                () -> {
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_GREATER);
                },
                () -> {
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(GL11.GL_LEQUAL);
                }) {};

        static final RenderType LINES = new Overlay("maicraft_overlay_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                VertexFormat.Mode.LINES, List.of(RENDERTYPE_LINES_SHADER, new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)),
                        TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE,
                        new RenderStateShard("maicraft_overlay_no_depth", RenderSystem::disableDepthTest, RenderSystem::enableDepthTest) {},
                        MAIN_TARGET));
        static final RenderType LINES_VISIBLE = new Overlay("maicraft_overlay_lines_visible",
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES,
                List.of(RENDERTYPE_LINES_SHADER, new RenderStateShard.LineStateShard(OptionalDouble.of(2.5)),
                        TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE, VISIBLE_DEPTH, MAIN_TARGET));
        static final RenderType LINES_OCCLUDED = new Overlay("maicraft_overlay_lines_occluded",
                DefaultVertexFormat.POSITION_COLOR_NORMAL, VertexFormat.Mode.LINES,
                List.of(RENDERTYPE_LINES_SHADER, new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)),
                        TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE, OCCLUDED_DEPTH, MAIN_TARGET));
        static final RenderType FILLED = new Overlay("maicraft_overlay_filled", DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS, List.of(POSITION_COLOR_SHADER, TRANSLUCENT_TRANSPARENCY, NO_CULL, COLOR_WRITE,
                        LEQUAL_DEPTH_TEST, MAIN_TARGET));

        private Overlay(String name, VertexFormat format, VertexFormat.Mode mode, List<RenderStateShard> states) {
            super(name, format, mode, 32768, false, false,
                    () -> states.forEach(RenderStateShard::setupRenderState),
                    () -> states.forEach(RenderStateShard::clearRenderState));
        }
    }
}
