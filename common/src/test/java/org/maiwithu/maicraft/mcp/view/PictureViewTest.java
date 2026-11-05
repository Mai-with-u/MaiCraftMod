// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp.view;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.game.picture.CameraPicture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * observe(picture) 的文字那一块：画面里的编号各在图上哪里、标没标；给了 id 时转没转、装没装下、
 * 只拍到一部分时退几格；界面开着没拍进去时提一句 with_menu。镜头面朝南、在原点。
 */
class PictureViewTest {

    private static final int W = 1280;
    private static final int H = 720;
    private static final PictureView.Camera CAMERA = new PictureView.Camera("正前方", "南", "平视");

    private static CameraPicture picture(boolean menuIncluded) {
        Quaternionf south = new Quaternionf().rotationYXZ((float) Math.PI, 0f, 0f);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 512f);
        return new CameraPicture(W, H, new int[W * H], 0, 0, 0, south, projection, true, menuIncluded);
    }

    private static PictureView.Sighting zombie(String id, double x, double z) {
        return new PictureView.Sighting(id, "type", "minecraft:zombie", null, PictureLabels.Rank.HOSTILE, x, 0, z,
                Math.hypot(x, z));
    }

    private static JsonObject item(JsonObject data, String id) {
        for (var element : data.getAsJsonArray("in_picture")) {
            if (element.getAsJsonObject().get("id").getAsString().equals(id)) return element.getAsJsonObject();
        }
        throw new AssertionError("in_picture 里没有 " + id);
    }

    @Test
    void 画面里的编号列出图上位置_身后的不列() {
        PictureView.Sighting ahead = zombie("e1", 0, 10);
        PictureView.Sighting behind = zombie("e2", 0, -10);
        PictureView.Sighting player = new PictureView.Sighting("e3", "type", "minecraft:player", "Alex",
                PictureLabels.Rank.PLAYER, -2, 0, 8, 8.2);
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(ahead, behind, player), null, false, null, false));
        JsonObject data = result.view();
        assertEquals(2, data.getAsJsonArray("in_picture").size());
        JsonArray at = item(data, "e1").getAsJsonArray("at");
        assertEquals(640, at.get(0).getAsInt());
        assertEquals("minecraft:zombie", item(data, "e1").get("type").getAsString());
        assertEquals("Alex", item(data, "e3").get("name").getAsString());
        assertTrue(item(data, "e1").get("labeled").getAsBoolean());
        assertEquals("南", data.getAsJsonObject("camera").get("compass").getAsString());
        assertTrue(data.getAsJsonObject("camera").get("first_person").getAsBoolean());
        assertEquals("image/jpeg", data.getAsJsonObject("picture").get("format").getAsString());
        assertTrue(data.get("summary").getAsString().contains("看得见 2 个编号，标了 2 个"));
    }

    @Test
    void 不标时照样列出位置() {
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(zombie("e1", 0, 10)), null, false, null, true));
        assertFalse(result.view().getAsJsonObject("picture").get("labeled").getAsBoolean());
        assertFalse(item(result.view(), "e1").get("labeled").getAsBoolean());
        assertFalse(item(result.view(), "e1").has("not_labeled_because"));
        assertTrue(result.view().get("summary").getAsString().contains("图上没标编号"));
    }

    @Test
    void 转过去整个装下() {
        PictureView.Box box = new PictureView.Box(-0.3, 0, 9.7, 0.3, 1.9, 10.3);
        PictureView.Toward toward = new PictureView.Toward("e1", true, null, box, true, true);
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(zombie("e1", 0, 10)), null, false, toward, false));
        JsonObject data = result.view().getAsJsonObject("toward");
        assertTrue(data.get("turned").getAsBoolean());
        assertEquals("whole", data.get("framed").getAsString());
        assertFalse(data.has("step_back_blocks"));
        assertTrue(result.placements().get(0).candidate().target(), "要看的那一样标成要看的");
        assertEquals(PictureLabels.Rank.HOSTILE, result.placements().get(0).candidate().rank(), "种类照旧，边框颜色按它画");
    }

    @Test
    void 建筑只拍到一部分时说退几格() {
        PictureView.Box house = new PictureView.Box(-15, -2, 4, 15, 10, 20);
        PictureView.Toward toward = new PictureView.Toward("小屋", true, null, house, true, false);
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(), null, false, toward, false));
        JsonObject data = result.view().getAsJsonObject("toward");
        assertEquals("partly", data.get("framed").getAsString());
        assertTrue(data.get("step_back_blocks").getAsInt() > 0);
        assertTrue(result.view().get("summary").getAsString().contains("往后退约"));
    }

    @Test
    void 没转时写明原因_在身后的说不在画面里() {
        PictureView.Box box = new PictureView.Box(-0.5, 0, -10.5, 0.5, 1, -9.5);
        PictureView.Toward toward = new PictureView.Toward("f2", false, "手上在做「挖矿」", box, true, false);
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(), null, false, toward, false));
        JsonObject data = result.view().getAsJsonObject("toward");
        assertFalse(data.get("turned").getAsBoolean());
        assertEquals("手上在做「挖矿」", data.get("not_turned_because").getAsString());
        assertEquals("outside", data.get("framed").getAsString());
        assertTrue(result.view().get("summary").getAsString().contains("它不在画面里"));
    }

    @Test
    void 看不见的算隐藏() {
        PictureView.Box box = new PictureView.Box(-0.5, 0, 9.5, 0.5, 1, 10.5);
        PictureView.Toward toward = new PictureView.Toward("f2", true, null, box, false, false);
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(), null, false, toward, false));
        assertEquals("hidden", result.view().getAsJsonObject("toward").get("framed").getAsString());
    }

    @Test
    void 开着界面没拍进去时提一句with_menu() {
        PictureView.Result result = PictureView.of(picture(false),
                new PictureView.Facts(CAMERA, List.of(), "chest", false, null, false));
        assertEquals("chest", result.view().get("menu_open").getAsString());
        assertTrue(result.view().get("summary").getAsString().contains("with_menu=true"));
        PictureView.Result withMenu = PictureView.of(picture(true),
                new PictureView.Facts(CAMERA, List.of(), "chest", true, null, false));
        assertTrue(withMenu.view().getAsJsonObject("picture").get("menu_included").getAsBoolean());
        assertTrue(withMenu.view().get("summary").getAsString().contains("连同开着的界面一起拍的"));
    }

    @Test
    void 俯仰的说法() {
        assertEquals("平视", PictureView.pitchWord(5f));
        assertEquals("略向下", PictureView.pitchWord(20f));
        assertEquals("向下", PictureView.pitchWord(60f));
        assertEquals("略向上", PictureView.pitchWord(-20f));
        assertEquals("向上", PictureView.pitchWord(-60f));
    }
}
