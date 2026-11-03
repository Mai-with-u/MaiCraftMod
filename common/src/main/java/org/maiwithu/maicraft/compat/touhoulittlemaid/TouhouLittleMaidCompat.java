// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.compat.CompatModule;
import org.maiwithu.maicraft.compat.CompatRegistry;

/**
 * 车万女仆（TouhouLittleMaid）的联动入口。交给登记表：观察（女仆 maid 与女仆墓碑 maid_tombstone，
 * 和原版实体共用 e#）、对女仆说话（{@code chat} 点名她时走到跟前开她的对话框）、她的 8 种界面的布局证明、
 * 改她的设置（{@code maid_configure}）、五子棋盘（观察里的 gomoku_board 与 {@code gomoku_play}）。
 * 每一下读写都经联动入口包一层：装的版本和编译时用的对不上时停用联动，不让游戏崩。
 */
public final class TouhouLittleMaidCompat extends CompatModule {

    public static final String MOD_ID = "touhou_little_maid";
    /** 女仆的实体类型。 */
    static final String MAID_TYPE = MOD_ID + ":maid";

    private final MaidReads reads;
    private final MaidActions actions;

    public TouhouLittleMaidCompat(MaidReads reads, MaidActions actions) {
        super(MOD_ID, "车万女仆");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    @Override public void contribute(CompatRegistry registry) {
        // 观察里多给女仆的状态：皮肤、工作、班次、跟随或驻守、好感、在想；墓碑写着谁的。
        registry.modSight(this, new MaidSight(this));
        // 点名自己的女仆说话：走到跟前、对准、开她的对话框、逐字打字、回车、认她的回话。
        registry.talkTo(this, services -> new MaidTalk(this, services));
        // 她的 8 种界面认得出：角色背包 36 格，容器侧只列内置 6 格与背包扩展的储物格。
        registry.menuLayout(this, new MaidMenuLayout());
        // 改她的设置：皮肤、工作、班次、跟随或驻守、拾取、手上与身上的装备，打开她的界面一项项改。
        registry.maidSettings(this, services -> new MaidConfigure(this, services));
        // 和坐在对面的她下五子棋：空手对准那一路落子、等她应手，点棋盒重开。
        registry.gomokuBoards(this, services -> new MaidGomoku(this, services));
    }

    boolean isMaid(Entity entity) {
        return call("认女仆", () -> reads.isMaid(entity));
    }

    MaidState state(Entity maid) {
        return call("读女仆的状态", () -> reads.state(maid));
    }

    Optional<String> tombstoneName(Entity entity) {
        return call("认女仆墓碑", () -> reads.tombstoneName(entity));
    }

    Map<String, String> skins() {
        return call("读本机模型表", reads::skins);
    }

    boolean aiChatEnabled() {
        return call("读 AI 对话开关", reads::aiChatEnabled);
    }

    boolean dialogOpenFor(int entityId) {
        return call("认女仆的对话框", () -> reads.dialogOpenFor(entityId));
    }

    String dialogText() {
        return call("读对话框里的字", reads::dialogText);
    }

    String personaGeneratedText() {
        return call("读人设生成提示", reads::personaGeneratedText);
    }

    void openDialog(int entityId) {
        run("发打开对话框的包", () -> actions.openDialog(entityId));
    }

    void typeIntoDialog(String text) {
        run("往对话框里打字", () -> actions.typeIntoDialog(text));
    }

    void pressEnterInDialog() {
        run("在对话框里按回车", actions::pressEnterInDialog);
    }

    void closeDialog() {
        run("关对话框", actions::closeDialog);
    }

    List<MaidReads.Work> works(Entity maid) {
        return call("读工作列表", () -> reads.works(maid));
    }

    boolean schedulePosScreenOpen() {
        return call("认日程点确认屏", reads::schedulePosScreenOpen);
    }

    void setSkin(int entityId, String skinId) {
        run("发换皮肤的包", () -> actions.setSkin(entityId, skinId));
    }

    void setWork(int entityId, String workId) {
        run("发换工作的包", () -> actions.setWork(entityId, workId));
    }

    void setConfig(int entityId, boolean stayInArea, boolean pickup, boolean rideable, MaidState.Shift shift) {
        run("发女仆设置包", () -> actions.setConfig(entityId, stayInArea, pickup, rideable, shift));
    }

    void closeSchedulePosScreen() {
        run("关日程点确认屏", actions::closeSchedulePosScreen);
    }

    Optional<GomokuState> gomoku(Level level, BlockPos at) {
        return call("读五子棋盘", () -> reads.gomoku(level, at));
    }

    List<GomokuState> gomokuBoards(Level level, AABB region) {
        return call("找附近的五子棋盘", () -> reads.gomokuBoards(level, region));
    }
}
