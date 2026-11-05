// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.picture;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 取画面的观察口：收"要一幅画面"的请求，在取图点把主画面缓冲拷一份交回请求线程。
 *
 * <p>请求在 MCP 的请求线程上提；取图在渲染线程上、由渲染的 Mixin 在每一帧的三个点叫进来：
 * 一帧开始（记帧时间）、世界画完物品栏还没画（只拍世界）、整帧画完（连界面一起拍）。
 * 拷贝先在显卡上缩到长边 {@link #LONG_EDGE} 像素再读回，读回只在有人要图的那一帧做，平时不动。
 *
 * <p>同一时刻只拍一张，两张之间至少隔 {@link #MIN_GAP_NANOS}：读回要占渲染线程一下，连着拍会让直播画面卡顿。
 * 每拍一张记一行日志：这一帧多长、读回占了多少、之前一段平均与最长，拍画面卡不卡直播看这一行。
 */
public final class CameraPictures {
    private static final Logger LOG = LoggerFactory.getLogger(CameraPictures.class);

    /** 画面长边缩到多少像素：看清几格外告示牌大小的东西够用，单张一两百 KB。 */
    public static final int LONG_EDGE = 1280;
    /** 两张之间至少隔多久（纳秒）。类别：游戏事实（读回占渲染线程一帧，连拍会让直播卡顿）。 */
    public static final long MIN_GAP_NANOS = 1_000_000_000L;
    /** 记多少帧的帧时间，用来和拍画面那一帧比。 */
    static final int FRAME_HISTORY = 120;

    // GL 常量：读、写画面缓冲分开绑，按颜色缩放拷贝，线性取样。
    private static final int READ_FRAMEBUFFER = 0x8CA8;
    private static final int DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int COLOR_BUFFER_BIT = 0x4000;
    private static final int LINEAR = 0x2601;

    /** 此刻要拍还没拍成的那一张；没人要时为 null。 */
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private volatile long lastTakenAt;
    private volatile boolean takenBefore;
    // 下面这些只在渲染线程上读写。
    private final Matrix4f worldProjection = new Matrix4f();
    private boolean projectionSeen;
    private boolean worldDrawnThisFrame;
    private TextureTarget scratch;
    private final long[] frameNanos = new long[FRAME_HISTORY];
    private int frameCount;
    private long frameStartedAt;
    private long captureCostNanos = -1;

    private record Pending(boolean withMenu, CompletableFuture<CameraPicture> picture) {}

    /** 请求的结论：收下了（等这张图），或此刻不能拍（正在拍一张、离上一张不到一秒）。 */
    public sealed interface Asked {
        /** 收下了：图在下一帧画到取图点时交回。 */
        record Accepted(CompletableFuture<CameraPicture> picture) implements Asked {}

        /** 正在拍上一张。 */
        record AlreadyTaking() implements Asked {}

        /** 离上一张不到一秒：再等这么多毫秒。 */
        record TooSoon(long waitMillis) implements Asked {}
    }

    /** 此刻能不能拍：不能时给出原因；能拍为 null。请求线程上调，转头前先问一句，免得转了头又拍不成。 */
    public Asked refusal(long nowNanos) {
        if (pending.get() != null) return new Asked.AlreadyTaking();
        long since = nowNanos - lastTakenAt;
        if (takenBefore && since < MIN_GAP_NANOS) return new Asked.TooSoon((MIN_GAP_NANOS - since + 999_999) / 1_000_000);
        return null;
    }

    /**
     * 要一幅画面：下一帧画到取图点时交回。
     *
     * @param withMenu 真：连同此刻开着的界面一起拍（这一帧整个画完以后取）
     */
    public Asked request(boolean withMenu, long nowNanos) {
        Asked refused = refusal(nowNanos);
        if (refused != null) return refused;
        Pending asked = new Pending(withMenu, new CompletableFuture<>());
        if (!pending.compareAndSet(null, asked)) return new Asked.AlreadyTaking();
        return new Asked.Accepted(asked.picture());
    }

    /** 请求线程等不及了（1 秒没出帧）：撤掉这一张，下一张照常能要。 */
    public void giveUp(CompletableFuture<CameraPicture> picture) {
        Pending current = pending.get();
        if (current != null && current.picture() == picture && pending.compareAndSet(current, null)) {
            picture.cancel(false);
        }
    }

    /** 渲染线程：一帧开始。记下上一帧多长；上一帧拍过画面的，记一行日志。 */
    public void frameStarted() {
        long now = System.nanoTime();
        worldDrawnThisFrame = false;
        if (frameStartedAt != 0) {
            long frame = now - frameStartedAt;
            if (captureCostNanos >= 0) {
                logCaptureFrame(frame);
                captureCostNanos = -1;
            }
            frameNanos[frameCount % FRAME_HISTORY] = frame;
            frameCount++;
        }
        frameStartedAt = now;
    }

    /** 渲染线程：世界的投影这一帧定下来了（含视野、晃动）。抄一份，取图时按它换算像素。 */
    public void worldProjection(Matrix4f projection) {
        worldProjection.set(projection);
        projectionSeen = true;
    }

    /** 渲染线程：世界（含手、后期效果）画完，物品栏和界面还没画。只拍世界的请求在这里取。 */
    public void worldDrawn() {
        worldDrawnThisFrame = true;
        Pending asked = pending.get();
        if (asked != null && !asked.withMenu()) capture(asked, false);
    }

    /** 渲染线程：这一帧整个画完（物品栏、界面都在）。连界面一起拍的请求在这里取；这一帧没画世界的不取。 */
    public void frameDrawn() {
        Pending asked = pending.get();
        if (asked != null && asked.withMenu() && worldDrawnThisFrame) capture(asked, true);
    }

    // 取一张：在显卡上把主画面缩小拷进备用缓冲，读回内存，再把主画面重新绑回去，接下来的物品栏照常画。
    private void capture(Pending asked, boolean menuIncluded) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!projectionSeen || minecraft.level == null) return;
        long started = System.nanoTime();
        RenderTarget main = minecraft.getMainRenderTarget();
        int[] size = scaledSize(main.width, main.height, LONG_EDGE);
        int[] abgr;
        try {
            TextureTarget target = scratch(size[0], size[1]);
            GlStateManager._glBindFramebuffer(READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(DRAW_FRAMEBUFFER, target.frameBufferId);
            GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, size[0], size[1],
                    COLOR_BUFFER_BIT, LINEAR);
            try (NativeImage image = new NativeImage(size[0], size[1], false)) {
                RenderSystem.bindTexture(target.getColorTextureId());
                // 不让原版逐格补不透明：那一步在渲染线程上逐像素做，挪到请求线程上做。
                image.downloadTexture(0, false);
                abgr = image.getPixelsRGBA();
            }
        } catch (RuntimeException failure) {
            pending.compareAndSet(asked, null);
            asked.picture().completeExceptionally(failure);
            return;
        } finally {
            // 不管拷没拷成，主画面都要重新绑回去：后面的物品栏、界面画在它上面。
            main.bindWrite(true);
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        int[] rgb = topDownRgb(abgr, size[0], size[1]);
        CameraPicture picture = new CameraPicture(size[0], size[1], rgb, camera.getPosition().x, camera.getPosition().y,
                camera.getPosition().z, camera.rotation(), worldProjection, !camera.isDetached(), menuIncluded);
        captureCostNanos = System.nanoTime() - started;
        lastTakenAt = System.nanoTime();
        takenBefore = true;
        pending.compareAndSet(asked, null);
        asked.picture().complete(picture);
    }

    private TextureTarget scratch(int width, int height) {
        if (scratch == null) {
            scratch = new TextureTarget(width, height, false, Minecraft.ON_OSX);
        } else if (scratch.width != width || scratch.height != height) {
            scratch.resize(width, height, Minecraft.ON_OSX);
        }
        return scratch;
    }

    // 拍画面那一帧多长、读回占了多少，和之前一段的平均、最长比：直播卡没卡一眼看得出。
    private void logCaptureFrame(long frame) {
        int count = Math.min(frameCount, FRAME_HISTORY);
        long total = 0;
        long longest = 0;
        for (int i = 0; i < count; i++) {
            total += frameNanos[i];
            longest = Math.max(longest, frameNanos[i]);
        }
        LOG.info("取画面：这一帧 {} 毫秒（读回占 {} 毫秒）；之前 {} 帧平均 {} 毫秒、最长 {} 毫秒",
                millis(frame), millis(captureCostNanos), count, millis(count == 0 ? 0 : total / count), millis(longest));
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }

    /** 按长边缩到不超过 longEdge，宽高比不变；本来就小的不放大。纯函数。 */
    static int[] scaledSize(int width, int height, int longEdge) {
        int edge = Math.max(width, height);
        if (edge <= longEdge) return new int[] {Math.max(1, width), Math.max(1, height)};
        double scale = (double) longEdge / edge;
        return new int[] {Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    /**
     * 显卡读回的像素是从下往上一行一行、每格按 ABGR 排的：翻成从上往下、每格 0xRRGGBB。纯函数。
     */
    static int[] topDownRgb(int[] abgr, int width, int height) {
        int[] rgb = new int[width * height];
        for (int row = 0; row < height; row++) {
            int from = (height - 1 - row) * width;
            int to = row * width;
            for (int column = 0; column < width; column++) {
                int pixel = abgr[from + column];
                int red = pixel & 0xFF;
                int green = (pixel >>> 8) & 0xFF;
                int blue = (pixel >>> 16) & 0xFF;
                rgb[to + column] = red << 16 | green << 8 | blue;
            }
        }
        return rgb;
    }
}
