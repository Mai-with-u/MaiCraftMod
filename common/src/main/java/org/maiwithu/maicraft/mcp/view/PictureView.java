// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.maiwithu.maicraft.game.picture.CameraPicture;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * observe(picture) 的文字那一块：镜头朝哪、转没转过去、装没装下、画面里看得见哪些编号各在图上哪里。纯函数。
 *
 * <p>图本身另走一个图片内容块；这里只整理和图对得上的结构化事实，好让模型看到图里"那个人""那只箱子"
 * 就知道它是哪个编号，直接拿来下目标。编号的方位、距离不重复写，要看去 scene、detail。
 */
public final class PictureView {

    private PictureView() {}

    /**
     * 画面里可能标的一样东西：客户端线程上按镜头的视线读出来的，坐标是世界里的标注点。
     *
     * @param kindField 种类字段名，与场景同名：实体 type、设施与告示牌 block、地形特征 kind、模组的东西 category
     * @param name      玩家的名字；别的为 null
     * @param distance  离镜头多远（格），同一档里近的先标
     */
    public record Sighting(String id, String kindField, String kindValue, String name, PictureLabels.Rank rank,
                           double x, double y, double z, double distance) {}

    /** 一样东西在世界里的外框（格）。 */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        double centerX() {
            return (minX + maxX) / 2;
        }

        double centerY() {
            return (minY + maxY) / 2;
        }

        double centerZ() {
            return (minZ + maxZ) / 2;
        }

        /** 横着的一半：水平对角线的一半，从哪个方向看都装得下。 */
        double halfWidth() {
            double dx = maxX - minX;
            double dz = maxZ - minZ;
            return Math.sqrt(dx * dx + dz * dz) / 2;
        }

        double halfHeight() {
            return (maxY - minY) / 2;
        }

        List<double[]> corners() {
            List<double[]> corners = new ArrayList<>();
            for (double x : new double[] {minX, maxX})
                for (double y : new double[] {minY, maxY})
                    for (double z : new double[] {minZ, maxZ}) corners.add(new double[] {x, y, z});
            return corners;
        }
    }

    /**
     * 给了 id 时要看的那样东西。
     *
     * @param turned            转过去了，并且转到位了
     * @param notTurnedBecause  没转（或没转到位）的一句话；转到位了为 null
     * @param box               它的外框；找不到它此刻在哪为 null
     * @param visible           此刻看不看得见它
     * @param entity            是实体：不算"往后退几格能装下"
     */
    public record Toward(String id, boolean turned, String notTurnedBecause, Box box, boolean visible,
                         boolean entity) {}

    /** 拍下这一帧时镜头朝哪：相对站定朝向的方位、东南西北、俯仰的说法。 */
    public record Camera(String direction, String compass, String pitch) {}

    /**
     * 整理文字那一块要的事实。
     *
     * @param menuOpen  开着的界面是哪种（取值同 observe(menu) 的 kind）；没开为 null
     * @param menuAsked 调用方要连界面一起拍
     * @param toward    给了 id 时要看的那样东西；没给为 null
     */
    public record Facts(Camera camera, List<Sighting> sightings, String menuOpen, boolean menuAsked, Toward toward,
                        boolean unlabeled) {
        public Facts {
            sightings = List.copyOf(sightings);
        }
    }

    /** 文字那一块，加上标签摆在哪（画图时照着画）。 */
    public record Result(JsonObject view, List<PictureLabels.Placement> placements) {}

    /** 按拍下的那一帧整理文字那一块，并摆好标签。 */
    public static Result of(CameraPicture picture, Facts facts) {
        Objects.requireNonNull(picture, "picture");
        int width = picture.width();
        int height = picture.height();
        String targetId = facts.toward() == null ? null : facts.toward().id();
        // 落在画面里的编号换成像素；要看的那一样最先摆，其他标签不压进它的外框。
        List<PictureLabels.Candidate> candidates = new ArrayList<>();
        List<Sighting> inFrame = new ArrayList<>();
        for (Sighting sighting : facts.sightings()) {
            CameraPicture.Projected at = picture.project(sighting.x(), sighting.y(), sighting.z());
            if (!at.inFrame(width, height)) continue;
            candidates.add(new PictureLabels.Candidate(sighting.id(), sighting.rank(), sighting.id().equals(targetId),
                    at.x(), at.y(), sighting.distance()));
            inFrame.add(sighting);
        }
        PictureLabels.Rect avoid = targetRect(picture, facts.toward());
        List<PictureLabels.Placement> placements = PictureLabels.place(candidates, width, height, avoid,
                facts.unlabeled());

        JsonObject view = new JsonObject();
        JsonObject about = new JsonObject();
        about.addProperty("width", width);
        about.addProperty("height", height);
        about.addProperty("format", "image/jpeg");
        about.addProperty("labeled", !facts.unlabeled());
        about.addProperty("menu_included", picture.menuIncluded());
        view.add("picture", about);
        JsonObject camera = new JsonObject();
        camera.addProperty("direction", facts.camera().direction());
        camera.addProperty("compass", facts.camera().compass());
        camera.addProperty("pitch", facts.camera().pitch());
        camera.addProperty("first_person", picture.firstPerson());
        view.add("camera", camera);
        PictureFraming.Framed framed = null;
        if (facts.toward() != null) {
            JsonObject toward = new JsonObject();
            Toward target = facts.toward();
            toward.addProperty("id", target.id());
            toward.addProperty("turned", target.turned());
            if (target.notTurnedBecause() != null) toward.addProperty("not_turned_because", target.notTurnedBecause());
            framed = framed(picture, target);
            toward.addProperty("framed", framed.wireName());
            if (framed == PictureFraming.Framed.PARTLY && !target.entity() && target.box() != null) {
                toward.addProperty("step_back_blocks", stepBack(picture, target.box()));
            }
            view.add("toward", toward);
        }
        JsonArray listed = new JsonArray();
        int labeled = 0;
        for (PictureLabels.Placement placement : placements) {
            Sighting sighting = find(inFrame, placement.candidate().id());
            JsonObject item = new JsonObject();
            item.addProperty("id", sighting.id());
            item.addProperty(sighting.kindField(), sighting.kindValue());
            if (sighting.name() != null) item.addProperty("name", sighting.name());
            JsonArray at = new JsonArray();
            at.add(Math.round(placement.candidate().x()));
            at.add(Math.round(placement.candidate().y()));
            item.add("at", at);
            item.addProperty("labeled", placement.labeled());
            if (placement.notLabeledBecause() != null) item.addProperty("not_labeled_because", placement.notLabeledBecause());
            if (placement.labeled()) labeled++;
            listed.add(item);
        }
        view.add("in_picture", listed);
        if (facts.menuOpen() != null) view.addProperty("menu_open", facts.menuOpen());
        view.addProperty("summary", summary(picture, facts, framed, placements.size(), labeled));
        return new Result(view, placements);
    }

    /** 拍下时俯仰的说法：正数是低头。 */
    public static String pitchWord(float pitch) {
        if (pitch > 35) return "向下";
        if (pitch > 10) return "略向下";
        if (pitch < -35) return "向上";
        if (pitch < -10) return "略向上";
        return "平视";
    }

    private static PictureFraming.Framed framed(CameraPicture picture, Toward toward) {
        if (toward.box() == null) return PictureFraming.Framed.OUTSIDE;
        List<CameraPicture.Projected> corners = new ArrayList<>();
        for (double[] corner : toward.box().corners()) corners.add(picture.project(corner[0], corner[1], corner[2]));
        return PictureFraming.framed(corners, picture.width(), picture.height(), toward.visible());
    }

    private static int stepBack(CameraPicture picture, Box box) {
        double dx = box.centerX() - picture.cameraX();
        double dy = box.centerY() - picture.cameraY();
        double dz = box.centerZ() - picture.cameraZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return PictureFraming.stepBackBlocks(box.halfWidth(), box.halfHeight(), distance, picture.tanHalfWidth(),
                picture.tanHalfHeight());
    }

    // 要看的那样东西在图上占的方块：外框角落里在镜头前面的那些围起来，裁到画面里。
    private static PictureLabels.Rect targetRect(CameraPicture picture, Toward toward) {
        if (toward == null || toward.box() == null || !toward.visible()) return null;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] corner : toward.box().corners()) {
            CameraPicture.Projected at = picture.project(corner[0], corner[1], corner[2]);
            if (!at.inFront()) continue;
            minX = Math.min(minX, at.x());
            minY = Math.min(minY, at.y());
            maxX = Math.max(maxX, at.x());
            maxY = Math.max(maxY, at.y());
        }
        if (minX > maxX) return null;
        double left = Math.max(0, minX);
        double top = Math.max(0, minY);
        double right = Math.min(picture.width(), maxX);
        double bottom = Math.min(picture.height(), maxY);
        if (right <= left || bottom <= top) return null;
        return new PictureLabels.Rect(left, top, right - left, bottom - top);
    }

    private static Sighting find(List<Sighting> sightings, String id) {
        for (Sighting sighting : sightings) if (sighting.id().equals(id)) return sighting;
        throw new IllegalStateException("标签对不上编号 " + id);
    }

    // 一句话：转没转、装没装下、标了几个、界面拍没拍进去。
    private static String summary(CameraPicture picture, Facts facts, PictureFraming.Framed framed, int listed,
                                  int labeled) {
        List<String> parts = new ArrayList<>();
        Toward toward = facts.toward();
        if (toward != null) {
            String turn = toward.turned() ? "转过去看 " + toward.id() : "没转过去看 " + toward.id() + "（" + toward.notTurnedBecause() + "）";
            String frame = switch (framed) {
                case WHOLE -> "整个装进画面";
                case PARTLY -> toward.entity() || toward.box() == null ? "只拍到一部分" : "只拍到一部分，往后退约 "
                        + stepBack(picture, toward.box()) + " 格能装下";
                case HIDDEN -> "隔着东西看不见它";
                case OUTSIDE -> "它不在画面里";
            };
            parts.add(turn + "，" + frame);
        }
        if (facts.unlabeled()) {
            parts.add("图上没标编号；画面里看得见 " + listed + " 个编号，位置在 in_picture");
        } else {
            parts.add("画面里看得见 " + listed + " 个编号，标了 " + labeled + " 个");
        }
        if (picture.menuIncluded()) {
            parts.add("连同开着的界面一起拍的");
        } else if (facts.menuOpen() != null) {
            parts.add("开着界面（" + facts.menuOpen() + "），没拍进去；要连界面一起拍用 with_menu=true");
        } else if (facts.menuAsked()) {
            parts.add("没有开着的界面，只拍了世界");
        }
        return String.join("；", parts) + "。";
    }
}
