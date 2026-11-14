// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.acquire.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.acquire.spi.AcquisitionCost;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemNeeds;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceContext;
import org.maiwithu.maicraft.behavior.acquire.spi.SourceQuote;
import org.maiwithu.maicraft.behavior.permission.PermissionCheck;
import org.maiwithu.maicraft.behavior.permission.Protection;
import org.maiwithu.maicraft.behavior.permission.ReadsCreatureSituation;
import org.maiwithu.maicraft.behavior.permission.ReadsOwnHome;
import org.maiwithu.maicraft.behavior.permission.SeesVillagers;
import org.maiwithu.maicraft.behavior.permission.TrustedPlayers;
import org.maiwithu.maicraft.kernel.goal.Permissions;
import org.maiwithu.maicraft.kernel.goal.WorldPosition;
import org.maiwithu.maicraft.kernel.result.Problem;
import org.maiwithu.maicraft.kernel.task.Action;
import org.maiwithu.maicraft.kernel.task.ActionStatus;
import org.maiwithu.maicraft.kernel.task.TickContext;
import org.maiwithu.maicraft.behavior.acquire.mining.ScansMinables;
import org.maiwithu.maicraft.behavior.acquire.mining.MinableSpot;
import org.maiwithu.maicraft.behavior.acquire.mining.DigsStairsDown;
import org.maiwithu.maicraft.behavior.acquire.mining.CollectsBlocks;
import org.maiwithu.maicraft.behavior.acquire.field.DropsFrom;
import org.maiwithu.maicraft.behavior.acquire.spi.WantedItem;
import org.maiwithu.maicraft.behavior.acquire.spi.ItemRequest;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsWorkTools;
import org.maiwithu.maicraft.behavior.acquire.cook.ReadsToolRequirements;
import org.maiwithu.maicraft.behavior.acquire.StubTick;
import org.maiwithu.maicraft.behavior.acquire.FakeOffhand;
import org.maiwithu.maicraft.behavior.acquire.FakeTags;
import org.maiwithu.maicraft.behavior.acquire.FakeBackpack;

/**
 * 采掘的来源：加工品明确说挖不出来；矿石按许可筛格子；缺合用的镐先按同一套需求去弄；
 * 挖的动作走挖方块的执行接缝，接缝没接上就交回空由引擎换路。
 */
class MiningSourceTest {

    private static final SourceContext CONTEXT = SourceContext.unscoped(
            WorldPosition.here(0, 64, 0), Permissions.DEFAULT);

    private final FakeBackpack backpack = new FakeBackpack(36);
    private final FakeOffhand offhand = new FakeOffhand();
    private final CapturingNeeds needs = new CapturingNeeds();
    /** 物品标签：备工具前数身上的原木、木板、石料要用；默认空着。 */
    private final FakeTags tags = new FakeTags();

    /** 替身：记下递归进来的内部需求，动作当场做完。 */
    private static final class CapturingNeeds implements ItemNeeds {
        final List<ItemRequest> asked = new ArrayList<>();

        @Override public Action actionFor(ItemRequest request, Permissions permissions) {
            asked.add(request);
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "备" + request.wanted().describe();
                }
            };
        }
    }

    /** 替身：固定一批可挖的方块，或声明世界上根本没有方块直接掉它；buried 表示要的是埋在脚下的石头掉的；complete 表示扫完了。 */
    private record FakeMinables(List<MinableSpot> spots, boolean anyDrops, boolean buried, boolean complete)
            implements ScansMinables {
        FakeMinables(List<MinableSpot> spots, boolean anyDrops) {
            this(spots, anyDrops, false, true);
        }
        FakeMinables(List<MinableSpot> spots, boolean anyDrops, boolean buried) {
            this(spots, anyDrops, buried, true);
        }
        @Override public boolean scanComplete() {
            return complete;
        }
        @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center, int radiusBlocks) {
            // 问做石制工具的料时只认掉圆石的格子：一片树林里看不见石头；问别的东西时掉圆石的格子不算它的来路。
            if ("minecraft:stone_tool_materials".equals(wanted.tagId())) {
                return spots.stream().filter(spot -> spot.droppedItem().equals("minecraft:cobblestone")).toList();
            }
            return spots.stream().filter(spot -> !spot.droppedItem().equals("minecraft:cobblestone")
                    || spot.droppedItem().equals(wanted.itemId())).toList();
        }
        @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) {
            return anyDrops;
        }
        @Override public boolean buriedUnderfoot(WantedItem wanted) {
            return buried;
        }
        @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
            return blockTypeId.equals("minecraft:stone");
        }
        // 粗铁是铁矿掉的，知道铁矿在哪几层生成：附近看不见时长程挖矿。远古残骸掉自己，在下界的那几层。
        @Override public Optional<String> depositOre(WantedItem wanted) {
            if ("minecraft:ancient_debris".equals(wanted.itemId())) return Optional.of("minecraft:ancient_debris");
            return "minecraft:raw_iron".equals(wanted.itemId()) ? Optional.of("minecraft:iron_ore") : Optional.empty();
        }
        // 连锁逐格核：64 层都是天然煤矿，只有 x=4 那格是搭出来的。
        @Override public Optional<String> minableAt(WantedItem wanted, DropsFrom dropsFrom, BlockPos pos) {
            return pos.getY() == 64 && pos.getX() != 4 ? Optional.of("minecraft:coal_ore") : Optional.empty();
        }
    }

    /** 替身：记下要往下挖几格、哪些方块算数，动作当场做完；长程挖矿记下往哪一层挖。 */
    private static final class FakeStairs implements DigsStairsDown {
        int wantedCells;
        Predicate<String> yields;
        Tunnel tunnel;

        @Override public Optional<Action> tunnelToward(Tunnel tunnel, Permissions permissions) {
            this.tunnel = tunnel;
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "长程挖矿";
                }
            });
        }

        @Override public Action digDown(Predicate<String> yields, int wantedCells,
                Permissions permissions) {
            this.yields = yields;
            this.wantedCells = wantedCells;
            return new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "往下挖楼梯";
                }
            };
        }
    }

    private final FakeStairs stairs = new FakeStairs();

    /** 替身：挖一格记一格，当场做完；接不上时返回 empty。 */
    private static final class FakeDigs implements CollectsBlocks {
        final List<BlockPos> dug = new ArrayList<>();
        private boolean wired = true;

        FakeDigs wired(boolean wired) {
            this.wired = wired;
            return this;
        }

        @Override public Optional<Action> collect(BlockPos target, Permissions permissions) {
            return collectBatch(List.of(target), permissions);
        }

        /** 成批收：一批记一次，格子按先后记进 dug。 */
        final List<List<BlockPos>> batches = new ArrayList<>();

        /** 来源交来的连锁判断：连锁挖到的格能不能挖。 */
        Predicate<BlockPos> chainable;

        @Override public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions,
                Predicate<BlockPos> chainable) {
            this.chainable = chainable;
            return collectBatch(cells, permissions);
        }

        @Override public Optional<Action> collectBatch(List<BlockPos> cells, Permissions permissions) {
            if (!wired) return Optional.empty();
            dug.addAll(cells);
            batches.add(List.copyOf(cells));
            return Optional.of(new Action() {
                @Override public ActionStatus tick(TickContext context) {
                    return ActionStatus.done();
                }
                @Override public String describe() {
                    return "收 " + cells.size() + " 格";
                }
            });
        }
    }

    /** 替身许可：矿石要铁镐，石头要木镐；带对了镐才算够格。 */
    private static final class FakeTools implements ReadsToolRequirements {
        @Override public Optional<String> toolRequired(String blockType) {
            if (blockType.equals("minecraft:stone")) return Optional.of("minecraft:wooden_pickaxe");
            return blockType.endsWith("_ore") ? Optional.of("minecraft:iron_pickaxe") : Optional.empty();
        }
        @Override public boolean sufficient(String toolItemId, String blockType) {
            return blockType.equals("minecraft:stone") ? toolItemId.endsWith("_pickaxe")
                    : toolItemId.equals("minecraft:iron_pickaxe");
        }
    }

    private static MinableSpot coalAt(int x, int z) {
        return new MinableSpot(new BlockPos(x, 64, z), "minecraft:coal_ore", "minecraft:coal");
    }

    private MiningSource source(ScansMinables minables, CollectsBlocks digs) {
        // 真许可检查点加全空的替身保护：本场景里没有任何受保护的东西，挡路的只有许可档位。
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new MiningSource(minables, digs, stairs, new FakeTools(), check, backpack, offhand, tags,
                needs, null);
    }

    // 带一栋建筑档案的保护：x 在 0 到 4 之间、y 是 64 的格都在「小屋」图上。
    private MiningSource sourceWithHut(ScansMinables minables) {
        ReadsOwnHome hut = new ReadsOwnHome() {
            @Override public Optional<WorldPosition> sleptBed() {
                return Optional.empty();
            }

            @Override public List<Building> ownBuildings() {
                return List.of();
            }

            @Override public Optional<String> archivedBuildingAt(WorldPosition position) {
                return position.x() >= 0 && position.x() <= 4 && position.y() == 64 ? Optional.of("小屋") : Optional.empty();
            }
        };
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(), name -> Optional.empty(),
                        "self", SeesVillagers.NOBODY, hut, id -> id),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        return new MiningSource(minables, new FakeDigs(), stairs, new FakeTools(), check, backpack, offhand, tags,
                needs, null);
    }

    @Test
    void 缺材料自己挑点_不拆建筑档案里房子上的格_都在房子上时说清楚() {
        WorldPosition at = new WorldPosition(0, 64, 0, "minecraft:overworld");
        SourceContext context = SourceContext.unscoped(at, Permissions.DEFAULT);
        ItemRequest coal = new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "火把");
        // 房子墙上那格煤矿不挑，房子外那格照挑。
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class,
                sourceWithHut(new FakeMinables(List.of(coalAt(3, 0), coalAt(6, 0)), true)).quote(coal, context));
        assertEquals(1, offer.obtainableCount());
        // 看得见的都在房子上：不拆，说清是哪一栋、要拆就点名。
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class,
                sourceWithHut(new FakeMinables(List.of(coalAt(3, 0)), true)).quote(coal, context));
        assertTrue(unavailable.reason().contains("建筑档案「小屋」"), unavailable.reason());
        assertTrue(unavailable.reason().contains("gather"), unavailable.reason());
    }

    @Test
    void 加工品明确说挖不出来() {
        SourceQuote quote = source(new FakeMinables(List.of(), false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:iron_ingot"), 2, "工具准备"), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("合成或烧炼"));
    }

    @Test
    void 附近有矿_按格子数报价_风险写明掉落看运气() {
        SourceQuote quote = source(new FakeMinables(List.of(coalAt(3, 0), coalAt(6, 0)), true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "火把"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals(2, offer.obtainableCount());
        assertEquals(3.0, offer.cost().distanceBlocks(), 0.001);
        assertTrue(offer.risk().contains("不一定掉一件"));
    }

    @Test
    void 连锁挖整条矿脉_每格都要天然_会掉要的_在范围里() {
        FakeDigs digs = new FakeDigs();
        backpack.add("minecraft:iron_pickaxe", 1);
        Action action = source(new FakeMinables(List.of(coalAt(3, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .orElseThrow();
        runAll(action);
        assertTrue(digs.chainable.test(new BlockPos(2, 64, 0)), "旁边埋着的天然煤矿一起挖");
        assertTrue(digs.chainable.test(new BlockPos(56, 64, 0)), "顺势伸出计划搜索半径几格的天然煤矿允许并入连锁");
        assertFalse(digs.chainable.test(new BlockPos(4, 64, 0)), "搭出来的那格不连锁");
        assertFalse(digs.chainable.test(new BlockPos(3, 63, 0)), "不掉煤的不连锁");
        assertFalse(digs.chainable.test(new BlockPos(300, 64, 0)), "远超自然延伸余量的地方不连锁");
    }

    @Test
    void 身上没有合用的镐_动手时先备工具再挖() {
        FakeDigs digs = new FakeDigs();
        Action action = source(new FakeMinables(List.of(coalAt(3, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .orElseThrow();
        runAll(action);
        assertEquals(1, needs.asked.size());
        assertEquals("minecraft:iron_pickaxe", needs.asked.getFirst().wanted().specifier());
        assertTrue(needs.asked.getFirst().purpose().contains("工具"));
        assertEquals(List.of(new BlockPos(3, 64, 0)), digs.dug);
    }

    @Test
    void 身上有合用的镐_直接挖() {
        backpack.addGear("minecraft:iron_pickaxe");
        FakeDigs digs = new FakeDigs();
        Action action = source(new FakeMinables(List.of(coalAt(3, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .orElseThrow();
        runAll(action);
        assertTrue(needs.asked.isEmpty());
        assertEquals(1, digs.dug.size());
    }

    @Test
    void 挖的入口没接上_交回空由引擎换路() {
        FakeDigs unwired = new FakeDigs().wired(false);
        assertTrue(source(new FakeMinables(List.of(coalAt(3, 0)), true), unwired)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 1, "火把"),
                        new SourceQuote.Offer("采掘", 1, new AcquisitionCost(3, 1), null), CONTEXT)
                .isEmpty());
    }

    private static void runAll(Action action) {
        for (int tick = 1; tick <= 100; tick++) {
            if (!(action.tick(new StubTick(tick)) instanceof ActionStatus.Running)) return;
        }
        throw new AssertionError("一百刻还没做完");
    }

    @Test
    void 看得见的再多_动作数也只按要挖的格数算_不舍近求远() {
        // 眼前一片矿露着 120 格，要 2 块煤：按挖 2 格算代价，比 100 格外记得的箱子便宜，不跑去开箱。
        List<MinableSpot> vein = new ArrayList<>();
        for (int i = 0; i < 120; i++) vein.add(coalAt(3 + i % 12, i / 12));
        MiningSource mining = source(new FakeMinables(vein, true), new FakeDigs());

        SourceQuote.Offer two = assertInstanceOf(SourceQuote.Offer.class,
                mining.quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "火把"), CONTEXT));
        assertEquals(120, two.obtainableCount(), "能拿多少照旧按看得见的全部算");
        assertEquals(2, two.cost().actionCount());
        assertTrue(two.cost().compareTo(new AcquisitionCost(100, 4)) < 0, "眼前的矿比远处的箱子省事");

        // 要的比看得见的多：看得见的都挖上，动作数到头就是这么多格。
        SourceQuote.Offer all = assertInstanceOf(SourceQuote.Offer.class,
                mining.quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 500, "火把"), CONTEXT));
        assertEquals(120, all.cost().actionCount());
    }

    @Test
    void 要几件就挖几格_不把附近的矿挖光() {
        FakeDigs digs = new FakeDigs();
        backpack.add("minecraft:iron_pickaxe", 1);
        source(new FakeMinables(List.of(coalAt(3, 0), coalAt(4, 0), coalAt(5, 0), coalAt(6, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 2, "火把"),
                        new SourceQuote.Offer("采掘", 4, new AcquisitionCost(3, 4), null), CONTEXT)
                .orElseThrow();
        assertEquals(2, digs.dug.size());
    }

    @Test
    void 看不见石头_要圆石时往下挖楼梯报价() {
        // 实机：新世界地表全是土，obtain 圆石只回答"附近看得见的地方没有"。石头谁都知道埋在脚下。
        SourceQuote quote = source(new FakeMinables(List.of(), true, true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertEquals(3, offer.obtainableCount());
        assertTrue(offer.risk().contains("楼梯"), offer.risk());
        assertTrue(offer.risk().contains("minecraft:wooden_pickaxe"), "没带镐要先说会去备：" + offer.risk());
    }

    @Test
    void 看不见煤_照旧说附近没有_不往下挖() {
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), CONTEXT);
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    @Test
    void 不许挖天然方块_往下挖楼梯要许可() {
        SourceContext onlyTemporary = SourceContext.unscoped(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.TEMPORARY, null, null, null, null, null));
        SourceQuote quote = source(new FakeMinables(List.of(), true, true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), onlyTemporary);
        SourceQuote.NeedsApproval approval = assertInstanceOf(SourceQuote.NeedsApproval.class, quote);
        assertTrue(approval.problem().message().contains("change_blocks"), approval.problem().message());
    }

    @Test
    void 往下挖楼梯_没镐先备木镐_挖够要的件数() {
        Action action = source(new FakeMinables(List.of(), true, true), new FakeDigs())
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"),
                        new SourceQuote.Offer("采掘", 3, new AcquisitionCost(4, 7), null), CONTEXT)
                .orElseThrow();
        runAll(action);

        assertEquals("minecraft:wooden_pickaxe", needs.asked.getFirst().wanted().itemId());
        assertEquals(3, stairs.wantedCells);
        assertTrue(stairs.yields.test("minecraft:stone"));
        assertTrue(!stairs.yields.test("minecraft:dirt"), "挖开的土不算挖到圆石");
    }

    @Test
    void 附近看不见铁矿_报长程挖矿_往下挖到铁矿最多的那层() {
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 3, "铁锭"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertTrue(offer.risk().contains("往下挖三格高的台阶到 Y=16"), offer.risk());
        assertTrue(offer.risk().contains("两格高的通道"), offer.risk());
    }

    /** 替身：附近看不见煤，只知道煤矿在哪几层；这一列的地表在 surfaceY。 */
    private record CoalUnseen(int surfaceY) implements ScansMinables {
        @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center, int radiusBlocks) {
            return List.of();
        }
        @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) {
            return true;
        }
        @Override public boolean scanComplete() {
            return true;
        }
        @Override public boolean buriedUnderfoot(WantedItem wanted) {
            return false;
        }
        @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) {
            return blockTypeId.equals("minecraft:coal_ore");
        }
        @Override public Optional<String> depositOre(WantedItem wanted) {
            return Optional.of("minecraft:coal_ore");
        }
        @Override public OptionalInt surfaceY(WorldPosition feet) {
            return OptionalInt.of(surfaceY);
        }
    }

    @Test
    void 站在地表要煤_先往下挖到地表下八格再横着挖() {
        // 煤最多在 95 层，人站在 64 层的地面上：原地横挖只是在地上开沟，先下到 56 层。
        SourceQuote quote = source(new CoalUnseen(64), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertTrue(offer.risk().contains("往下挖三格高的台阶到 Y=56"), offer.risk());
    }

    @Test
    void 已经在地下够深的地方要煤_就在这一层横着挖() {
        SourceQuote quote = source(new CoalUnseen(90), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), CONTEXT);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertTrue(offer.risk().contains("就在这一层（Y=64）横着挖"), offer.risk());
    }

    @Test
    void 在主世界要远古残骸_说清只在下界生成_先过去再要() {
        SourceContext overworld = SourceContext.unscoped(new WorldPosition(0, 64, 0, "minecraft:overworld"),
                Permissions.DEFAULT);
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:ancient_debris"), 1, "下界合金"), overworld);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().contains("只在 minecraft:the_nether 生成"), unavailable.reason());
        assertTrue(unavailable.reason().contains("Y=15 最多"), unavailable.reason());
        assertTrue(unavailable.reason().contains("travel"), unavailable.reason());
    }

    @Test
    void 在下界要远古残骸_往下挖台阶到Y15横着挖() {
        SourceContext nether = SourceContext.unscoped(new WorldPosition(0, 32, 0, "minecraft:the_nether"),
                Permissions.DEFAULT);
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:ancient_debris"), 1, "下界合金"), nether);
        SourceQuote.Offer offer = assertInstanceOf(SourceQuote.Offer.class, quote);
        assertTrue(offer.risk().contains("远古残骸在 Y 8 到 119 生成，Y=15 最多"), offer.risk());
        assertTrue(offer.risk().contains("往下挖三格高的台阶到 Y=15"), offer.risk());
    }

    @Test
    void 长程挖矿也要许可挖天然方块() {
        SourceContext onlyTemporary = SourceContext.unscoped(WorldPosition.here(0, 64, 0),
                Permissions.DEFAULT.mergedWith(Permissions.BlockChanges.TEMPORARY, null, null, null, null, null));
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 3, "铁锭"), onlyTemporary);
        SourceQuote.NeedsApproval approval = assertInstanceOf(SourceQuote.NeedsApproval.class, quote);
        assertTrue(approval.problem().message().contains("change_blocks"), approval.problem().message());
    }

    @Test
    void 在铁矿生成范围以下_不往上挖() {
        SourceContext deep = SourceContext.unscoped(WorldPosition.here(0, -70, 0), Permissions.DEFAULT);
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 3, "铁锭"), deep);
        assertInstanceOf(SourceQuote.Unavailable.class, quote);
    }

    @Test
    void 长程挖矿_先备镐_往16层挖_斜线不出铁矿的生成范围_看见了就去挖() {
        FakeDigs digs = new FakeDigs();
        Action action = source(new FakeMinables(List.of(), true, false), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 3, "铁锭"),
                        new SourceQuote.Offer("采掘", 3, new AcquisitionCost(80, 80), null), CONTEXT)
                .orElseThrow();
        runAll(action);
        assertEquals("minecraft:iron_pickaxe", needs.asked.getFirst().wanted().itemId(), "先备挖铁矿的镐");
        assertEquals(16, stairs.tunnel.targetY());
        assertEquals(-64, stairs.tunnel.floorY());
        assertEquals(3, stairs.tunnel.wantedCells());
    }

    /** 替身：一开始附近看不见铁矿，挖进通道以后看见一格。 */
    private static final class SeenLater implements ScansMinables {
        List<MinableSpot> spots = List.of();
        @Override public List<MinableSpot> minable(WantedItem wanted, DropsFrom dropsFrom, WorldPosition center, int radiusBlocks) { return spots; }
        @Override public boolean anyBlockDrops(WantedItem wanted, DropsFrom dropsFrom) { return true; }
        @Override public boolean scanComplete() { return true; }
        @Override public boolean buriedUnderfoot(WantedItem wanted) { return false; }
        @Override public boolean dropsWanted(WantedItem wanted, DropsFrom dropsFrom, String blockTypeId) { return blockTypeId.equals("minecraft:iron_ore"); }
        @Override public Optional<String> depositOre(WantedItem wanted) { return Optional.of("minecraft:iron_ore"); }
    }

    @Test
    void 长程挖矿看见铁矿_从站的地方连一批整脉连锁挖了() {
        SeenLater minables = new SeenLater();
        FakeDigs digs = new FakeDigs();
        backpack.add("minecraft:iron_pickaxe", 1);
        runAll(source(minables, digs).begin(new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 3, "铁锭"),
                new SourceQuote.Offer("采掘", 3, new AcquisitionCost(80, 80), null), CONTEXT).orElseThrow());
        BlockPos feet = new BlockPos(0, 16, -40);
        assertFalse(stairs.tunnel.inSight().test(feet), "还没看见");
        BlockPos ore = new BlockPos(2, 16, -41);
        minables.spots = List.of(new MinableSpot(ore, "minecraft:iron_ore", "minecraft:raw_iron"));
        assertTrue(stairs.tunnel.inSight().test(feet));
        runAll(stairs.tunnel.mineInSight().apply(feet).orElseThrow());
        assertEquals(List.of(List.of(ore)), digs.batches);
        assertTrue(digs.chainable != null, "看见的那片按整脉连锁挖");
        assertTrue(stairs.tunnel.yields().test("minecraft:iron_ore"), "通道正好穿过铁矿也算挖到了");
    }

    @Test
    void 附近还没扫完_回答再问_不说没有() {
        // 实机：方块索引分刻建，刚进世界第一问常常只扫了一部分；不能把没扫完当成"附近没有"去挖楼梯或放弃。
        SourceQuote quote = source(new FakeMinables(List.of(), true, true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), CONTEXT);
        assertInstanceOf(SourceQuote.NotYet.class, quote);
    }

    @Test
    void 扫完之后不再答再问_附近没有就落报价() {
        // 实机：raw_iron 问价跨三次调用约 70 秒都答"附近还没看完"，扫描早停了报价落不了地。
        // 同一处现场、补账停了（complete）：附近真没有就答明确的没有，长程挖矿的给长程挖矿的报价，
        // 不再按"还没扫完"拖着重估。
        FakeMinables minables = new FakeMinables(List.of(), true, false, true);
        SourceQuote quote = source(minables, new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:raw_iron"), 4, "铁锭"), CONTEXT);
        assertInstanceOf(SourceQuote.Offer.class, quote, "附近看不见铁矿但知道生成层，落成长程挖矿的报价");
        SourceQuote stone = source(new FakeMinables(List.of(), true, true, true), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:cobblestone"), 3, "石镐"), CONTEXT);
        assertInstanceOf(SourceQuote.Offer.class, stone, "埋在脚下谁都有的石头，落成往下挖楼梯的报价");
    }

    @Test
    void 附近没有的回答写明半径() {
        SourceQuote quote = source(new FakeMinables(List.of(), true, false), new FakeDigs())
                .quote(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"), CONTEXT);
        SourceQuote.Unavailable unavailable = assertInstanceOf(SourceQuote.Unavailable.class, quote);
        assertTrue(unavailable.reason().startsWith("半径 48 格内"), unavailable.reason());
    }

    @Test
    void 相邻的矿连成一批一次收_远处的不算() {
        // 煤矿 (3,0)(4,0)(5,0) 连着，(20,0) 远远的一格：要 3 件就收连着的那 3 格，一批、一次捡。
        FakeDigs digs = new FakeDigs();
        backpack.add("minecraft:iron_pickaxe", 1);
        source(new FakeMinables(List.of(coalAt(3, 0), coalAt(4, 0), coalAt(20, 0), coalAt(5, 0)), true), digs)
                .begin(new ItemRequest(WantedItem.ofItem("minecraft:coal"), 3, "火把"),
                        new SourceQuote.Offer("采掘", 4, new AcquisitionCost(3, 4), null), CONTEXT)
                .orElseThrow();
        assertEquals(1, digs.batches.size());
        assertEquals(List.of(new BlockPos(3, 64, 0), new BlockPos(4, 64, 0), new BlockPos(5, 64, 0)), digs.batches.getFirst());
    }

    /** 替身效率工具：成批砍原木该先备石斧；开局还要先徒手砍几根由 bootstrap 定。 */
    private static ReadsWorkTools axeFirst(int bootstrap) {
        return new ReadsWorkTools() {
            @Override public Optional<String> toolFor(String blockTypeId, int work) {
                return blockTypeId.endsWith("_log") ? Optional.of("minecraft:stone_axe") : Optional.empty();
            }

            @Override public int bootstrap(String blockTypeId) {
                return bootstrap;
            }
        };
    }

    private static List<MinableSpot> tree(int height) {
        List<MinableSpot> logs = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            logs.add(new MinableSpot(new BlockPos(0, 64 + y, 0), "minecraft:oak_log", "minecraft:oak_log"));
        }
        return logs;
    }

    @Test
    void 成批砍树先备一把石斧再砍() {
        // 身上有三块圆石：石斧的料在手边，先备石斧。
        FakeDigs digs = new FakeDigs();
        tags.put("minecraft:cobblestone", "minecraft:stone_tool_materials");
        backpack.add("minecraft:cobblestone", 3);
        MiningSource source = source(new FakeMinables(tree(6), true), digs);
        source.prepareWorkTools(axeFirst(0), List::of);
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertEquals("minecraft:stone_axe", needs.asked.getFirst().wanted().specifier());
        assertEquals(6, digs.dug.size());
    }

    @Test
    void 附近看得见石头_身上没有石料也照样先备石斧() {
        // 身上没有圆石，但旁边露着一块石头：挖三块就够，照旧先备石斧。
        FakeDigs digs = new FakeDigs();
        List<MinableSpot> spots = new ArrayList<>(tree(6));
        spots.add(new MinableSpot(new BlockPos(5, 63, 0), "minecraft:stone", "minecraft:cobblestone"));
        MiningSource source = source(new FakeMinables(spots, true), digs);
        source.prepareWorkTools(axeFirst(0), List::of);
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertEquals("minecraft:stone_axe", needs.asked.getFirst().wanted().specifier());
    }

    @Test
    void 海边看不见石头_身上有原木木棍_改备木斧不去挖楼梯() {
        // 实机 11-04：海边要 6 根橡木，身上 4 根橡木 2 根木棍，没有石头：不为石斧往下挖楼梯，改做一把木斧再砍。
        FakeDigs digs = new FakeDigs();
        tags.put("minecraft:oak_log", "minecraft:logs");
        backpack.add("minecraft:oak_log", 4);
        backpack.add("minecraft:stick", 2);
        MiningSource source = source(new FakeMinables(tree(6), true), digs);
        source.prepareWorkTools(axeFirst(0), List::of);
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertEquals(List.of("minecraft:wooden_axe"),
                needs.asked.stream().map(request -> request.wanted().specifier()).toList());
        assertEquals(6, digs.dug.size());
    }

    @Test
    void 看不见石头_身上已有木斧_直接砍不再备() {
        FakeDigs digs = new FakeDigs();
        tags.put("minecraft:oak_log", "minecraft:logs");
        backpack.add("minecraft:oak_log", 4);
        backpack.add("minecraft:wooden_axe", 1);
        MiningSource source = source(new FakeMinables(tree(6), true), digs);
        source.prepareWorkTools(axeFirst(0), List::of);
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertTrue(needs.asked.isEmpty(), "拿着木斧砍");
        assertEquals(6, digs.dug.size());
    }

    @Test
    void 看不见石头_木料也不够_徒手砍() {
        FakeDigs digs = new FakeDigs();
        MiningSource source = source(new FakeMinables(tree(6), true), digs);
        source.prepareWorkTools(axeFirst(0), List::of);
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertTrue(needs.asked.isEmpty(), "什么工具都不备");
        assertEquals(6, digs.dug.size());
    }

    @Test
    void 两手空空先徒手砍够做工具的那几根() {
        FakeDigs digs = new FakeDigs();
        MiningSource source = source(new FakeMinables(tree(6), true), digs);
        source.prepareWorkTools(axeFirst(3), List::of);
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertTrue(needs.asked.isEmpty(), "这一趟不去备斧头");
        assertEquals(3, digs.dug.size(), "只砍做工具要的三根，清点后再来");
    }

    @Test
    void 正在为做石斧砍木头_不再先要石斧() {
        FakeDigs digs = new FakeDigs();
        MiningSource source = source(new FakeMinables(tree(6), true), digs);
        source.prepareWorkTools(axeFirst(0),
                () -> List.of(new ItemRequest(WantedItem.ofItem("minecraft:stone_axe"), 1, "成批采掘前先备一把顺手的工具")));
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做石斧的木棍"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertTrue(needs.asked.isEmpty());
    }

    @Test
    void 正在为做木斧砍木头_身上没有原木_不再套一层备斧头_徒手砍() {
        // 身上没有原木，为做木斧去砍木头：旁边看得见石头也不再先要石斧或木斧，徒手把木头砍来。
        FakeDigs digs = new FakeDigs();
        List<MinableSpot> spots = new ArrayList<>(tree(6));
        spots.add(new MinableSpot(new BlockPos(5, 63, 0), "minecraft:stone", "minecraft:cobblestone"));
        MiningSource source = source(new FakeMinables(spots, true), digs);
        source.prepareWorkTools(axeFirst(0),
                () -> List.of(new ItemRequest(WantedItem.ofItem("minecraft:wooden_axe"), 1, "成批采掘前先备一把顺手的工具")));
        runAll(source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木斧的木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow());
        assertTrue(needs.asked.isEmpty(), "不为做斧头再要斧头");
        assertEquals(6, digs.dug.size());
    }

    @Test
    void 斧头备不来照样徒手砍() {
        FakeDigs digs = new FakeDigs();
        ItemNeeds failing = new ItemNeeds() {
            @Override public Action actionFor(ItemRequest request, Permissions permissions) {
                return new Action() {
                    @Override public ActionStatus tick(TickContext context) {
                        return ActionStatus.failed(Problem.of(Problem.Kind.NEED_ITEM, "没有圆石"));
                    }

                    @Override public String describe() {
                        return "备石斧";
                    }
                };
            }
        };
        PermissionCheck check = new PermissionCheck(
                new Protection((dimension, x, y, z) -> Optional.empty(), () -> List.of(),
                        name -> Optional.empty(), "self"),
                new ReadsCreatureSituation() {
                    @Override public Optional<CreatureSituation> situationOf(UUID entityId) {
                        return Optional.empty();
                    }
                });
        MiningSource source = new MiningSource(new FakeMinables(tree(6), true), digs, stairs, new FakeTools(), check,
                backpack, offhand, new FakeTags(), failing, null);
        source.prepareWorkTools(axeFirst(0), List::of);
        Action action = source.begin(new ItemRequest(WantedItem.ofItem("minecraft:oak_log"), 6, "做木板"),
                new SourceQuote.Offer("采掘", 6, new AcquisitionCost(3, 6), null), CONTEXT).orElseThrow();
        ActionStatus status = ActionStatus.running();
        for (int tick = 1; tick <= 100 && status instanceof ActionStatus.Running; tick++) status = action.tick(new StubTick(tick));
        assertInstanceOf(ActionStatus.Done.class, status);
        assertEquals(6, digs.dug.size());
    }

    @Test
    void 原木只沿树干竖向成批_旁边的横梁不算() {
        // 砍树：树干 (0,64..66) 连成一批；旁边同高的一根 (1,64) 是横梁，不并进来。
        MinableSpot trunk0 = new MinableSpot(new BlockPos(0, 64, 0), "minecraft:oak_log", "minecraft:oak_log");
        MinableSpot trunk1 = new MinableSpot(new BlockPos(0, 65, 0), "minecraft:oak_log", "minecraft:oak_log");
        MinableSpot trunk2 = new MinableSpot(new BlockPos(0, 66, 0), "minecraft:oak_log", "minecraft:oak_log");
        MinableSpot beam = new MinableSpot(new BlockPos(1, 64, 0), "minecraft:oak_log", "minecraft:oak_log");
        List<MinableSpot> batch = MiningSource.batchOf(List.of(trunk0, beam, trunk1, trunk2), 8);
        assertEquals(List.of(trunk0, trunk1, trunk2), batch);
    }
}
