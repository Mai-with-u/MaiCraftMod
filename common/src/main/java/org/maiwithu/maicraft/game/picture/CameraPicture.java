// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.picture;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * 一幅取到的画面：像素（从左上角起一行一行，每格 0xRRGGBB），连同拍下那一刻的镜头。
 *
 * <p>镜头按渲染世界时用的那一份记：镜头位置、朝向、投影。世界里一点换成图上的像素，和渲染时的算法一样：
 * 先减去镜头位置，再按镜头朝向转到镜头前方是负 Z 的坐标里，最后乘投影；所以标注落在图上的位置和画面严丝合缝。
 *
 * @param width        图宽（像素）
 * @param height       图高（像素）
 * @param rgb          像素，长度是宽乘高
 * @param cameraX      镜头所在点
 * @param rotation     镜头朝向（渲染世界时用的那一份）
 * @param projection   投影（渲染世界时用的那一份，含视野）
 * @param firstPerson  第一人称为真，第三人称（F5）为假
 * @param menuIncluded 连界面一起拍的（这一帧整个画完以后取）
 */
public record CameraPicture(int width, int height, int[] rgb, double cameraX, double cameraY, double cameraZ,
                            Quaternionf rotation, Matrix4f projection, boolean firstPerson, boolean menuIncluded) {

    public CameraPicture {
        Objects.requireNonNull(rgb, "rgb");
        if (width <= 0 || height <= 0 || rgb.length != width * height) {
            throw new IllegalArgumentException("像素数和宽高对不上");
        }
        rotation = new Quaternionf(Objects.requireNonNull(rotation, "rotation"));
        projection = new Matrix4f(Objects.requireNonNull(projection, "projection"));
    }

    /**
     * 世界里一点落在图上的哪里。
     *
     * @param x       图上横坐标（像素，从左量），点在镜头后面时没有意义
     * @param y       图上纵坐标（像素，从上量）
     * @param inFront 点在镜头前面；在后面的点不会出现在画面里
     */
    public record Projected(double x, double y, boolean inFront) {
        /** 落在画面里：在镜头前面，横纵都在图内。 */
        public boolean inFrame(int width, int height) {
            return inFront && x >= 0 && y >= 0 && x < width && y < height;
        }
    }

    /** 世界里一点换成图上的像素：和渲染世界时同一套镜头与投影。纯计算。 */
    public Projected project(double worldX, double worldY, double worldZ) {
        // 先移到以镜头为原点，再按镜头朝向的反向转过去：镜头正前方成了负 Z。
        Vector3f relative = new Vector3f((float) (worldX - cameraX), (float) (worldY - cameraY), (float) (worldZ - cameraZ));
        relative.rotate(new Quaternionf(rotation).conjugate());
        Vector4f clip = new Vector4f(relative, 1f).mul(projection);
        if (clip.w <= 1e-6f) return new Projected(0, 0, false);
        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        return new Projected((ndcX + 1) / 2 * width, (1 - ndcY) / 2 * height, true);
    }

    /** 画面左右一半视野的正切：投影第一列的缩放是它的倒数。取景算"退几格能装下"用。 */
    public double tanHalfWidth() {
        return 1.0 / projection.m00();
    }

    /** 画面上下一半视野的正切。 */
    public double tanHalfHeight() {
        return 1.0 / projection.m11();
    }
}
