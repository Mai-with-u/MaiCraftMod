// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.debug;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.maiwithu.maicraft.behavior.navigation.debug.NavigationPathSnapshot;

/**
 * 在世界里画出角色正在走的路线、目的地和下一个要走到的点。路线开关（F9+N）开着、界面没有隐藏时由面板调用；
 * 只读路线，不请求移动。分两遍按深度绘制：先以低透明度画被墙挡住的远段，再用高对比实色画眼前可见段，
 * 避免远处绕路叠画在近处墙面之前让人误以为路线穿墙。
 */
public final class NavigationPathRenderer {
    /** 眼前可见段颜色（清晰实色，走 LEQUAL 深度测试）。 */
    private static final int ROUTE_VISIBLE = 0xE635D9FF;
    private static final int STEERING_VISIBLE = 0xFFFFD641;
    private static final int DESTINATION_VISIBLE = 0xFF55FF88;
    /** 墙后遮挡段颜色（显著低透明度弱透视提示，走 GREATER 深度测试）。 */
    private static final int ROUTE_OCCLUDED = 0x3835D9FF;
    private static final int STEERING_OCCLUDED = 0x55FFD641;
    private static final int DESTINATION_OCCLUDED = 0x5555FF88;
    /** 两端都离镜头这么远的线段不画：看不清，也白占绘制。 */
    private static final double DRAW_RANGE = 128;

    private NavigationPathRenderer() {}

    /** 画一条路线；没有路线时什么都不画。 */
    public static void render(Camera camera, Matrix4f view, Matrix4f projection, NavigationPathSnapshot route) {
        if (route == null) return;
        Vec3 cameraPosition = camera.getPosition();
        // 先画墙后被遮挡的低透明弱提示段，再画眼前未被地形挡住的清晰实色段；
        // 两遍深度条件互斥（GREATER 与 LEQUAL），同一像素不会双重叠色。
        renderPass(cameraPosition, view, projection, route, OverlayRenderTypes.LINES_OCCLUDED,
                ROUTE_OCCLUDED, DESTINATION_OCCLUDED, STEERING_OCCLUDED);
        renderPass(cameraPosition, view, projection, route, OverlayRenderTypes.LINES_VISIBLE,
                ROUTE_VISIBLE, DESTINATION_VISIBLE, STEERING_VISIBLE);
    }

    private static void renderPass(Vec3 cameraPosition, Matrix4f view, Matrix4f projection,
            NavigationPathSnapshot route, RenderType renderType, int routeColor, int destinationColor,
            int steeringColor) {
        BufferBuilder lines = Tesselator.getInstance().begin(
                VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        List<Vec3> points = route.points();
        for (int i = 1; i < points.size(); i++) {
            segment(lines, points.get(i - 1), points.get(i), cameraPosition, routeColor);
        }
        marker(lines, route.destination(), cameraPosition, destinationColor, 0.35);
        marker(lines, route.steeringTarget(), cameraPosition, steeringColor, 0.22);
        var mesh = lines.build();
        if (mesh == null) return;
        renderType.setupRenderState();
        try {
            // 用原版的临时绘制缓冲区，这里不留跨帧的模型或世界引用。
            VertexBuffer buffer = DefaultVertexFormat.POSITION_COLOR_NORMAL.getImmediateDrawVertexBuffer();
            buffer.bind();
            buffer.upload(mesh);
            buffer.drawWithShader(view, projection, RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
            renderType.clearRenderState();
        }
    }

    // 在点的周围画三条交叉短线，标出目的地和下一个要走到的点。
    private static void marker(BufferBuilder lines, Vec3 point, Vec3 camera, int color, double size) {
        if (point == null) return;
        segment(lines, point.add(-size, 0, 0), point.add(size, 0, 0), camera, color);
        segment(lines, point.add(0, -size, 0), point.add(0, size, 0), camera, color);
        segment(lines, point.add(0, 0, -size), point.add(0, 0, size), camera, color);
    }

    // 两端都太远就不画；线段几乎为零也跳过；坐标换成相对镜头的位置再交给绘制。
    private static void segment(BufferBuilder lines, Vec3 from, Vec3 to, Vec3 camera, int color) {
        double range = DRAW_RANGE * DRAW_RANGE;
        if (from.distanceToSqr(camera) > range && to.distanceToSqr(camera) > range) return;
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 1.0E-8) return;
        Vec3 normal = direction.normalize();
        vertex(lines, from.subtract(camera), normal, color);
        vertex(lines, to.subtract(camera), normal, color);
    }

    private static void vertex(BufferBuilder lines, Vec3 point, Vec3 normal, int color) {
        lines.addVertex((float) point.x, (float) point.y, (float) point.z).setColor(color)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }
}
