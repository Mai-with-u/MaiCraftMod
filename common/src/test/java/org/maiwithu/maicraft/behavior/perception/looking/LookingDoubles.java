// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.perception.looking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.maiwithu.maicraft.game.player.PlayerContext;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;

/** 闲时看离线测试用的替身：现场、界面与刻号。眼睛在原点上方 1.62 格。 */
final class LookingDoubles {

    static final double EYE_Y = 1.62;

    private LookingDoubles() {}

    /** 刻号从 1 起逐刻加一；没有角色上下文，转头走替身。 */
    static final class Clock {
        long tick;

        TickContext next() {
            long now = ++tick;
            return new TickContext() {
                @Override public long gameTick() { return now; }
                @Override public PlayerContext player() { return null; }
            };
        }
    }

    /** 现场替身：角色能不能看、身边有什么、编号对应什么，都由测试摆好。 */
    static final class FakeWorld implements LookingWorld {
        boolean playerOk = true;
        final List<Candidate> around = new ArrayList<>();
        final List<Sighted> spots = new ArrayList<>();
        final Map<String, Sighted> known = new HashMap<>();
        final Map<String, Aim> things = new HashMap<>();

        @Override public boolean playerCanLook(TickContext context) { return playerOk; }

        @Override public Optional<Aim> aim(TickContext context, LookTarget target) {
            return switch (target) {
                case LookTarget.Thing thing -> Optional.ofNullable(things.get(thing.sceneId()));
                case LookTarget.Spot spot -> Optional.of(Aim.between(0, EYE_Y, 0, spot.x(), spot.y(), spot.z()));
                case LookTarget.Direction direction -> Optional.of(new Aim(direction.yaw(), direction.pitch()));
                case LookTarget.Standing standing -> Optional.empty();
            };
        }

        @Override public List<Candidate> around(TickContext context) { return around; }
        @Override public List<Sighted> sceneSpots(TickContext context) { return spots; }
        @Override public Optional<Sighted> resolve(TickContext context, String id) {
            return Optional.ofNullable(known.get(id));
        }

        @Override public List<Sighted> places(TickContext context, List<WorldPosition> places) {
            List<Sighted> sighted = new ArrayList<>();
            for (WorldPosition place : places) sighted.add(spot("place:" + place, place.x(), place.y(), place.z()));
            return sighted;
        }
    }

    /** 界面替身：记开了什么、关了几次；容器开不开、有没有配方查看器由测试定。 */
    static final class FakeScreens implements LookScreens {
        boolean others;
        boolean ours;
        boolean pages = true;
        boolean peekOpens = true;
        int inventoryOpens;
        int peeks;
        int peeksClosed;
        int recipePages;
        int closes;
        final List<String> opened = new ArrayList<>();

        @Override public boolean othersOpen() { return others; }
        @Override public boolean oursOpen() { return ours; }

        @Override public boolean showInventory() {
            inventoryOpens++;
            ours = true;
            opened.add("inventory");
            return true;
        }

        @Override public Action peek(LookPlans.Screen.Container container) {
            peeks++;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    if (!peekOpens) return ActionStatus.failed(Problem.of(Problem.Kind.REFUSED_BY_GAME, "锁着", null));
                    ours = true;
                    opened.add("container " + container.blockType());
                    return ActionStatus.done();
                }
                @Override public void close() { peeksClosed++; }
                @Override public String describe() { return "替身开箱"; }
            };
        }

        @Override public boolean opensRecipePages() { return pages; }

        @Override public boolean openRecipePage(String itemId, boolean uses) {
            recipePages++;
            ours = true;
            opened.add("recipe " + itemId + (uses ? " uses" : ""));
            return true;
        }

        @Override public void closeOurs() {
            closes++;
            ours = false;
        }
    }

    /** 世界里一个点的可看对象：偏航按眼睛在原点算。 */
    static LookingWorld.Sighted spot(String key, double x, double y, double z) {
        Aim aim = Aim.between(0, EYE_Y, 0, x + 0.5, y + 0.5, z + 0.5);
        return new LookingWorld.Sighted(key, new LookTarget.Spot(x + 0.5, y + 0.5, z + 0.5, false),
                LookingWorld.Kind.OTHER, aim.yaw(), Math.sqrt(x * x + y * y + z * z), null);
    }

    /** 手边一只容器：开箱现场由测试给。 */
    static LookingWorld.Sighted container(String key, int x, int y, int z, LookingWorld.ContainerFacts facts) {
        LookingWorld.Sighted at = spot(key, x, y, z);
        return new LookingWorld.Sighted(key, at.target(), LookingWorld.Kind.CONTAINER, at.yaw(), at.distance(), facts);
    }

    /** 开箱五条都满足的现场。 */
    static LookingWorld.ContainerFacts openable(int x, int y, int z) {
        return new LookingWorld.ContainerFacts(WorldPosition.here(x, y, z), "minecraft:chest", true, true, false, false);
    }

    /** 一个看得见的玩家候选。 */
    static LookingWorld.Candidate player(String key, UUID id, float yaw, double distance, boolean approaching) {
        return new LookingWorld.Candidate(LookingWorld.CandidateKind.PLAYER, key, new LookTarget.Thing(key), yaw,
                distance, true, approaching, id);
    }

    /** 一个看得见的生物候选。 */
    static LookingWorld.Candidate mob(String key, float yaw, double distance, boolean moving) {
        return new LookingWorld.Candidate(LookingWorld.CandidateKind.MOB, key, new LookTarget.Thing(key), yaw,
                distance, moving, false, null);
    }

    /** 一个听见的声音来处。 */
    static LookingWorld.Candidate sound(String key, float yaw) {
        return new LookingWorld.Candidate(LookingWorld.CandidateKind.SOUND, key, new LookTarget.Direction(yaw, 0f), yaw,
                8, false, false, null);
    }
}
