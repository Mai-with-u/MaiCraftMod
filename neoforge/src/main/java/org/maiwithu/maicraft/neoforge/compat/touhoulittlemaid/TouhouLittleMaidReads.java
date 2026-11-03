// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.neoforge.compat.touhoulittlemaid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidTaskEnableEvent;
import com.github.tartaricacid.touhoulittlemaid.api.game.gomoku.Point;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
import com.github.tartaricacid.touhoulittlemaid.block.properties.GomokuPart;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.AIChatScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.other.CheckSchedulePosGui;
import com.github.tartaricacid.touhoulittlemaid.client.resource.CustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.config.subconfig.AIConfig;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidSchedule;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleRegister;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.WaitingChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntityTombstone;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku;
import com.github.tartaricacid.touhoulittlemaid.util.ParseI18n;
import com.mojang.datafixers.util.Pair;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

import org.maiwithu.maicraft.compat.touhoulittlemaid.GomokuState;
import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidReads;
import org.maiwithu.maicraft.compat.touhoulittlemaid.MaidState;

/**
 * 车万女仆的读端：读女仆同步给周围玩家的实体数据（皮肤、工作、班次、跟随或驻守、好感、气泡）、本机模型表、
 * 眼前的对话框、五子棋盘同步下来的方块实体（棋子、轮到谁、这一局的状态、座位上坐的是谁）。
 * 文字气泡的正文没有读法，用车万女仆登记的那份序列化器写进临时缓冲区再按同样的格式读回来，不用反射。只翻译，不判断。
 */
public final class TouhouLittleMaidReads implements MaidReads {

    /** 和没有预设人设的女仆说第一句时，她只发这句的翻译键。 */
    private static final String PERSONA_KEY = "ai.touhou_little_maid.chat.llm.auto_gen_setting";

    @Override public boolean isMaid(Entity entity) {
        return entity instanceof EntityMaid;
    }

    @Override public MaidState state(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        String skinId = maid.getModelId();
        String skinName = CustomPackLoader.MAID_MODELS.getInfo(skinId)
                .map(info -> ParseI18n.parse(info.getName()).getString()).orElse(null);
        return new MaidState(maid.getId(), maid.getName().getString(), skinId, skinName,
                maid.getTask().getUid().toString(), maid.getTask().getName().getString(),
                shift(maid.getSchedule()), shiftNow(maid.getScheduleDetail()), maid.isHomeModeEnable(),
                maid.getRestrictCenter(), maid.getRestrictRadius(), maid.isPickup(), maid.isRideable(),
                maid.getFavorability(), maid.getExperience(), maid.isBegging(), maid.getIsInvulnerable(),
                maid.getMaidBackpackType().getId().toString(), bubbles(maid));
    }

    private static MaidState.Shift shift(MaidSchedule schedule) {
        return switch (schedule) {
            case DAY -> MaidState.Shift.DAY;
            case NIGHT -> MaidState.Shift.NIGHT;
            case ALL -> MaidState.Shift.ALL;
        };
    }

    // 车万女仆按班次与世界时刻算好的此刻活动：WORK 工作、IDLE 休息、REST 睡觉。
    private static MaidState.ShiftNow shiftNow(Activity activity) {
        if (activity == Activity.WORK) return MaidState.ShiftNow.WORK;
        if (activity == Activity.IDLE) return MaidState.ShiftNow.REST;
        return MaidState.ShiftNow.SLEEP;
    }

    // 她头上的气泡按编号（到期时刻）先后：文字气泡读回正文，在想的气泡读副文本（她在调哪个工具）。
    private static List<MaidState.Bubble> bubbles(EntityMaid maid) {
        ChatBubbleDataCollection collection = maid.getChatBubbleManager().getChatBubbleDataCollection();
        List<MaidState.Bubble> bubbles = new ArrayList<>();
        for (long key : collection.keySet()) {
            IChatBubbleData data = collection.get(key);
            if (data instanceof TextChatBubbleData) {
                bubbles.add(new MaidState.Bubble(key, MaidState.Bubble.Kind.TEXT, text(data)));
            } else if (data instanceof WaitingChatBubbleData waiting) {
                Component note = waiting.getSecondaryText();
                bubbles.add(new MaidState.Bubble(key, MaidState.Bubble.Kind.THINKING,
                        note == null ? null : note.getString()));
            } else {
                bubbles.add(new MaidState.Bubble(key, MaidState.Bubble.Kind.OTHER, null));
            }
        }
        return bubbles;
    }

    // 文字气泡的正文：序列化器先写正文再写背景图，写进去再把头一段读回来。
    private static String text(IChatBubbleData data) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ChatBubbleRegister.CODEC_MAP.get(TextChatBubbleData.ID).writeToBuff(buffer, data);
            return buffer.readJsonWithCodec(ComponentSerialization.CODEC).getString();
        } finally {
            buffer.release();
        }
    }

    // 墓碑名是车万女仆同步给周围玩家的文字组件，样式跟着同步过来，原样交出去，女仆的 UUID 由观察那一侧读。
    @Override public Optional<Component> tombstoneName(Entity entity) {
        if (!(entity instanceof EntityTombstone tombstone)) return Optional.empty();
        return Optional.of(tombstone.getMaidName());
    }

    @Override public Map<String, String> skins() {
        Map<String, String> skins = new TreeMap<>();
        for (String id : CustomPackLoader.MAID_MODELS.getModelIdSet()) {
            String name = CustomPackLoader.MAID_MODELS.getInfo(id)
                    .map(info -> ParseI18n.parse(info.getName()).getString()).orElse(id);
            skins.put(id, name);
        }
        return skins;
    }

    @Override public boolean aiChatEnabled() {
        return AIConfig.LLM_ENABLED.get();
    }

    @Override public boolean dialogOpenFor(int entityId) {
        return Minecraft.getInstance().screen instanceof AIChatScreen screen && screen.getMaid().getId() == entityId;
    }

    // 输入框是对话框里唯一的文字框：从它的子控件里找出来读字。
    @Override public String dialogText() {
        if (!(Minecraft.getInstance().screen instanceof AIChatScreen screen)) return null;
        for (GuiEventListener child : screen.children()) {
            if (child instanceof EditBox box) return box.getValue();
        }
        return null;
    }

    @Override public String personaGeneratedText() {
        return Component.translatable(PERSONA_KEY).getString();
    }

    // 她界面工作列表里的那几项：按钮灰不灰和界面一样判（事件拦了、或工作自己说不能用），条件文字照界面拼。
    @Override public List<Work> works(Entity entity) {
        EntityMaid maid = (EntityMaid) entity;
        List<Work> works = new ArrayList<>();
        for (IMaidTask task : TaskManager.getNotHiddenTaskList(maid)) {
            List<Pair<String, Predicate<EntityMaid>>> enableConditions = new ArrayList<>();
            boolean enabled = true;
            if (task != TaskManager.getIdleTask()) {
                if (NeoForge.EVENT_BUS.post(new MaidTaskEnableEvent(task, maid, enableConditions)).isCanceled()) {
                    enabled = false;
                } else if (!task.isEnable(maid)) {
                    enableConditions.addAll(task.getEnableConditionDesc(maid));
                    enabled = false;
                }
            }
            List<String> conditions = new ArrayList<>();
            String prefix = "task." + task.getUid().getNamespace() + "." + task.getUid().getPath();
            if (!enabled) conditions.addAll(lines(prefix + ".enable_condition.", enableConditions, maid));
            conditions.addAll(lines(prefix + ".condition.", task.getConditionDescription(maid), maid));
            works.add(new Work(task.getUid().toString(), task.getName().getString(), enabled, conditions));
        }
        return works;
    }

    private static List<String> lines(String keyPrefix, List<Pair<String, Predicate<EntityMaid>>> conditions,
            EntityMaid maid) {
        List<String> lines = new ArrayList<>();
        for (Pair<String, Predicate<EntityMaid>> line : conditions) {
            String text = Component.translatable(keyPrefix + line.getFirst()).getString();
            lines.add(text + "：" + (line.getSecond().test(maid) ? "满足" : "不满足"));
        }
        return lines;
    }

    @Override public boolean schedulePosScreenOpen() {
        return Minecraft.getInstance().screen instanceof CheckSchedulePosGui;
    }

    // 棋盘上任一格都认：按方块状态里的部件位置退回中心那一格，读中心的方块实体。
    @Override public Optional<GomokuState> gomoku(Level level, BlockPos at) {
        if (level == null) return Optional.empty();
        BlockState state = level.getBlockState(at);
        if (!(state.getBlock() instanceof BlockGomoku)) return Optional.empty();
        GomokuPart part = state.getValue(BlockGomoku.PART);
        BlockPos center = at.offset(-part.getPosX(), 0, -part.getPosY());
        if (!(level.getBlockEntity(center) instanceof TileEntityGomoku board)) return Optional.empty();
        return Optional.of(read(level, board));
    }

    // 按区块翻方块实体表：只看客户端已经有的区块，没加载的不去加载。
    @Override public List<GomokuState> gomokuBoards(Level level, AABB region) {
        List<GomokuState> found = new ArrayList<>();
        if (level == null) return found;
        for (int chunkX = (int) Math.floor(region.minX) >> 4; chunkX <= (int) Math.floor(region.maxX) >> 4; chunkX++) {
            for (int chunkZ = (int) Math.floor(region.minZ) >> 4; chunkZ <= (int) Math.floor(region.maxZ) >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof TileEntityGomoku board && region.contains(Vec3.atCenterOf(board.getBlockPos()))) {
                        found.add(read(level, board));
                    }
                }
            }
        }
        return found;
    }

    // 棋子数组按 x、y 原样抄出来；座位上坐没坐着女仆，和车万女仆落子前核对的一样：座位实体还在、第一个乘客是女仆。
    private static GomokuState read(Level level, TileEntityGomoku board) {
        BlockPos center = board.getBlockPos();
        byte[][] chess = board.getChessData();
        int[][] stones = new int[chess.length][];
        for (int x = 0; x < chess.length; x++) {
            stones[x] = new int[chess[x].length];
            for (int y = 0; y < chess[x].length; y++) stones[x][y] = chess[x][y];
        }
        Point last = board.getLatestChessPoint();
        GomokuState.Ending ending = switch (board.getStatue()) {
            case IN_PROGRESS -> GomokuState.Ending.NOT_OVER;
            case WIN -> GomokuState.Ending.WON;
            case DRAW -> GomokuState.Ending.DRAW;
        };
        UUID sitId = board.getSitId();
        boolean seated = !level.getEntities((Entity) null, new AABB(center).inflate(3),
                entity -> entity.getUUID().equals(sitId) && entity.isAlive()
                        && entity.getFirstPassenger() instanceof EntityMaid).isEmpty();
        return new GomokuState(center, level.getBlockState(center).getValue(BlockGomoku.FACING), stones,
                board.isPlayerTurn(), ending, board.getChessCounter(), last.x, last.y, last.type, seated);
    }
}
