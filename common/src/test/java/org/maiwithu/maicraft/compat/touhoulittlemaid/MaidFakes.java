// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import org.maiwithu.maicraft.behavior.interaction.ClientGameRefusals;
import org.maiwithu.maicraft.behavior.interaction.Interactions;
import org.maiwithu.maicraft.behavior.inventory.hands.GearChanges;
import org.maiwithu.maicraft.behavior.menu.MenuLayouts;
import org.maiwithu.maicraft.behavior.permission.GuessesPlayerMade;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.spi.PlayerServices;
import org.maiwithu.maicraft.game.ReceivedChat;
import org.maiwithu.maicraft.game.interaction.DigToolRule;
import org.maiwithu.maicraft.game.interaction.OverlayMessages;
import org.maiwithu.maicraft.game.player.BackpackStack;
import org.maiwithu.maicraft.game.player.BackpackView;
import org.maiwithu.maicraft.game.player.GearSlotName;
import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.game.serverlink.ServerReads;
import org.maiwithu.maicraft.game.world.BlockScanService;
import org.maiwithu.maicraft.kernel.event.ChatEventLog;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 车万女仆联动测试的替身：读写端、聊天栏、玩家服务与一只不在世界里的"女仆"。 */
final class MaidFakes {

    private MaidFakes() {}

    /** 一只女仆的状态，测试按需改字段再交给读端。 */
    static MaidState maid(int id, String name, List<MaidState.Bubble> bubbles) {
        return new MaidState(id, name, "geckolib:winefox", "酒狐", "touhou_little_maid:idle", "空闲",
                MaidState.Shift.DAY, MaidState.ShiftNow.WORK, false, BlockPos.ZERO, 12, true, true, 70, 10,
                false, false, "touhou_little_maid:empty", bubbles);
    }

    static MaidState.Bubble thinking(long key) {
        return new MaidState.Bubble(key, MaidState.Bubble.Kind.THINKING, null);
    }

    static MaidState.Bubble text(long key, String text) {
        return new MaidState.Bubble(key, MaidState.Bubble.Kind.TEXT, text);
    }

    /** 同一只女仆换了皮肤、工作或设置以后的样子。 */
    static MaidState with(MaidState s, String skinId, String workId, MaidState.Shift shift, boolean stayInArea,
            boolean pickup) {
        return new MaidState(s.entityId(), s.name(), skinId, s.skinName(), workId, s.workName(), shift, s.shiftNow(),
                stayInArea, s.areaCenter(), s.areaRadius(), pickup, s.rideable(), s.affection(), s.experience(),
                s.begging(), s.invulnerable(), s.backpackId(), s.bubbles());
    }

    /** 读端替身：女仆状态、眼前的对话框、工作列表、日程点确认屏都由测试摆。 */
    static final class Reads implements MaidReads {
        final Map<Integer, MaidState> states = new HashMap<>();
        Integer dialogFor;
        StringBuilder dialog = new StringBuilder();
        boolean aiChat = true;
        final List<Work> works = new ArrayList<>(List.of(
                new Work("touhou_little_maid:idle", "空闲", true, List.of()),
                new Work("touhou_little_maid:farm", "种田", true, List.of("主手需要锄：不满足"))));
        boolean schedulePos;

        @Override public boolean isMaid(Entity entity) {
            return states.containsKey(entity.getId());
        }

        @Override public MaidState state(Entity maid) {
            return states.get(maid.getId());
        }

        @Override public Optional<String> tombstoneName(Entity entity) {
            return Optional.empty();
        }

        final Map<String, String> skins = new TreeMap<>(Map.of("geckolib:winefox", "酒狐",
                "geckolib:winefox_blue", "酒狐（蓝）", "geckolib:winefox_hanfu", "酒狐（汉服）",
                "touhou_little_maid:hakurei_reimu", "博丽灵梦"));

        @Override public Map<String, String> skins() {
            return skins;
        }

        @Override public boolean aiChatEnabled() {
            return aiChat;
        }

        @Override public boolean dialogOpenFor(int entityId) {
            return dialogFor != null && dialogFor == entityId;
        }

        @Override public String dialogText() {
            return dialogFor == null ? null : dialog.toString();
        }

        @Override public String personaGeneratedText() {
            return "角色设定文件已经自动生成完毕";
        }

        @Override public List<Work> works(Entity maid) {
            return List.copyOf(works);
        }

        @Override public boolean schedulePosScreenOpen() {
            return schedulePos;
        }

        /** 摆着的那个五子棋盘；没有为 null。 */
        GomokuState board;

        // 棋盘上任一格都认：中心那一格前后左右各一格以内。
        @Override public Optional<GomokuState> gomoku(Level level, BlockPos at) {
            if (board == null) return Optional.empty();
            BlockPos center = board.center();
            boolean onBoard = at.getY() == center.getY() && Math.abs(at.getX() - center.getX()) <= 1
                    && Math.abs(at.getZ() - center.getZ()) <= 1;
            return onBoard ? Optional.of(board) : Optional.empty();
        }

        @Override public List<GomokuState> gomokuBoards(Level level, AABB region) {
            return board == null ? List.of() : List.of(board);
        }
    }

    /** 一个五子棋盘：中心在 (0, 64, 0)、朝北（她坐在东边，你在西边）；棋子按 x、y 摆。 */
    static GomokuState board(int[][] stones, boolean yourTurn, GomokuState.Ending ending, int moves, int lastX,
            int lastY, int lastStone) {
        return new GomokuState(new BlockPos(0, 64, 0), Direction.NORTH, stones, yourTurn, ending, moves, lastX, lastY,
                lastStone, true);
    }

    /** 一张空棋盘。 */
    static int[][] emptyStones() {
        return new int[GomokuState.SIZE][GomokuState.SIZE];
    }

    /** 写端替身：开框、打字、回车照真人的效果改读端；回车时的反应由测试摆。 */
    static final class Actions implements MaidActions {
        final Reads reads;
        /** 发了开框的包，对话框弹不弹出来。 */
        boolean dialogPops = true;
        final List<Integer> opened = new ArrayList<>();
        final List<String> entered = new ArrayList<>();
        int closed;
        Runnable onEnter = () -> {};
        /** 服务器让不让换皮肤：不让时皮肤不变，由测试往聊天栏写回应。 */
        boolean skinAllowed = true;
        /** 开驻守时车万女仆弹日程点确认屏拒绝。 */
        boolean schedulePosPops;
        /** 发了包却什么都不变（网络丢了、服务器没理）。 */
        boolean ignored;
        final List<String> sent = new ArrayList<>();

        Actions(Reads reads) {
            this.reads = reads;
        }

        @Override public void openDialog(int entityId) {
            opened.add(entityId);
            if (dialogPops) {
                reads.dialogFor = entityId;
                reads.dialog = new StringBuilder();
            }
        }

        @Override public void typeIntoDialog(String text) {
            reads.dialog.append(text);
        }

        @Override public void pressEnterInDialog() {
            entered.add(reads.dialog.toString());
            reads.dialogFor = null;
            onEnter.run();
        }

        @Override public void closeDialog() {
            closed++;
            reads.dialogFor = null;
        }

        @Override public void setSkin(int entityId, String skinId) {
            sent.add("skin " + skinId);
            MaidState s = reads.states.get(entityId);
            if (skinAllowed && !ignored) {
                reads.states.put(entityId, with(s, skinId, s.workId(), s.shift(), s.stayInArea(), s.pickup()));
            }
        }

        @Override public void setWork(int entityId, String workId) {
            sent.add("work " + workId);
            MaidState s = reads.states.get(entityId);
            if (!ignored) reads.states.put(entityId, with(s, s.skinId(), workId, s.shift(), s.stayInArea(), s.pickup()));
        }

        @Override public void setConfig(int entityId, boolean stayInArea, boolean pickup, boolean rideable,
                MaidState.Shift shift) {
            sent.add("config " + stayInArea + " " + pickup + " " + rideable + " " + shift);
            MaidState s = reads.states.get(entityId);
            if (ignored) return;
            if (schedulePosPops && stayInArea && !s.stayInArea()) {
                reads.schedulePos = true;
                return;
            }
            reads.states.put(entityId, with(s, s.skinId(), s.workId(), shift, stayInArea, pickup));
        }

        @Override public void closeSchedulePosScreen() {
            reads.schedulePos = false;
        }
    }

    /** 不在世界里的一只实体：只为拿一个编号、一个包围盒。 */
    static Entity creature(int id) {
        Entity entity = new Entity(EntityType.PIG, null) {
            @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {}
            @Override protected void readAdditionalSaveData(CompoundTag tag) {}
            @Override protected void addAdditionalSaveData(CompoundTag tag) {}
        };
        entity.setId(id);
        entity.setPos(2.0, 64.0, 2.0);
        return entity;
    }

    /** 聊天栏收到一条服务端系统消息。 */
    static void systemMessage(ChatEventLog chat, String text) {
        chat.append(ReceivedChat.fromServer(text, false).orElseThrow());
    }

    /** 不在世界里的玩家服务：靠近当场到、游戏回应读这本聊天栏、背包里什么都没有。 */
    static PlayerServices services(ChatEventLog chat) {
        return services(chat, List.of());
    }

    /** 同上，背包里带着这些东西（每样一件）。 */
    static PlayerServices services(ChatEventLog chat, List<String> carried) {
        Protection nobodyOwns = new Protection((dimension, x, y, z) -> Optional.empty(), List::of,
                name -> Optional.empty(), GuessesPlayerMade.NOTHING, "self");
        BackpackView empty = new BackpackView() {
            @Override public List<BackpackStack> stacks() {
                return carried.stream().map(id -> new BackpackStack(id, 1, 1, true, false, false, false)).toList();
            }
            @Override public int usedSlots() { return carried.size(); }
            @Override public int totalSlots() { return 36; }
        };
        GearChanges noGear = new GearChanges() {
            @Override public Optional<Action> wear(String itemId, GearSlotName slot) { return Optional.empty(); }
            @Override public Optional<Action> takeOff(GearSlotName slot) { return Optional.empty(); }
        };
        return new PlayerServices(() -> null, (target, permissions) -> new Arrived(), new Interactions(null),
                itemId -> Optional.empty(), noGear, inner -> inner, (request, permissions) -> null, nobodyOwns,
                itemId -> itemId.endsWith("_hoe") ? Set.of("minecraft:hoes") : Set.of(), MenuLayouts.VANILLA, new BlockScanService(), ServerReads.NONE, empty,
                DigToolRule.HELD, new ClientGameRefusals(new OverlayMessages(), List::of, () -> 0L, chat));
    }

    /** 当场就到了的靠近。 */
    static final class Arrived implements Action {
        @Override public ActionStatus tick(TickContext context) {
            return ActionStatus.done();
        }

        @Override public String describe() {
            return "到了";
        }
    }

    /** 刻号由测试推进的本刻上下文；不在世界里。 */
    static final class Tick implements TickContext {
        long gameTick = 1000;

        @Override public long gameTick() {
            return gameTick;
        }

        @Override public PlayerContext player() {
            return null;
        }
    }
}
