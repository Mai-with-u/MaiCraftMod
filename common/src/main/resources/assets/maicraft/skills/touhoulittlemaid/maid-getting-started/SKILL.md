---
name: maid-getting-started
description: 车万女仆入门：女仆是什么、怎么得到、先换皮肤再说话、和她说话、坐下喂食抱起、让她种田干活、给她定工作和睡觉的地方、下五子棋、她死了怎么取墓碑；第一次见到女仆、要和她互动或让她干活前读
metadata: {"requires-mods": "touhou_little_maid"}
---

# 车万女仆入门

女仆是车万女仆模组里的一种可驯服生物，不是角色，也不是玩家。"酒狐"是她的一种皮肤，不是另一种生物。`observe` 里她和普通生物一样有 e#，`mod_things` 的 `maid` 多给她的皮肤、工作、班次、跟着还是驻守、好感、在想什么、头上冒的话。

## 怎么得到

- 进服送的魂符：`use(item=touhou_little_maid:smart_slab_init, target=<地面一格>, face=up)` 对着地面一点，放出一只认你做主人的女仆，是哪个角色看运气。
- 野生的：前哨站、林地府邸里的悦灵有两成被换成未驯服的女仆，拿蛋糕 `use(item=minecraft:cake, target=e#)` 驯服。
- 每人默认一共只能驯服或用魂符放出 5 次（服务器可调），她死了也不退名额。名额满时魂符放不出来、服务器什么都不说，所以放完先 `observe` 核对附近多了一只"驯服：你的"女仆。

## 先换皮肤，再开口

只有这 19 个皮肤 ID 带酒狐的人设：`geckolib:winefox`、`geckolib:winefox_blue`、`geckolib:winefox_nine_tailed`、`geckolib:winefox_hanfu`、`geckolib:winefox_new_year`、`geckolib:winefox_jk`、`geckolib:winefox_astronaut`、`geckolib:winefox_kongfu`、`geckolib:winefox_magical`、`geckolib:winefox_saint`、`geckolib:winefox_elf`、`geckolib:winefox_salesperson`、`geckolib:winefox_little`、`geckolib:winefox_matured`、`geckolib:winefox_momo`、`geckolib:winefox_wedding`、`geckolib:winefox_tactics`、`geckolib:winefox_mini`、`geckolib:winefox_survivor`。`authors_and_credits:wine_fox_*` 和带十六进制后缀的换肤版没有。

和没有预设人设的女仆说第一句时，她只会生成一份人设、不回答（结果里 `persona_generated=true`），而且这份人设以后换皮肤也不会变。想要酒狐的性格：先 `maid_configure(target=e#, settings={"skin": "geckolib:winefox"})`，再说话，优先挑还没聊过天的女仆。

## 和她说话

- `chat(target=e#, message=…)`：角色走到她跟前、开她的对话框逐字打字、回车，再站着等她回话（`max_seconds`，默认 30 秒）。只能对自己的女仆说。
- 一件事说清楚就停，回去干活，不要一直聊。她头上冒"在想"时会先等她想完再说。
- 她改没改状态（坐下、跟上、换工作）看结果的 `changes` 和 `observe` 里的 `maid` 字段，不看她嘴上怎么说。
- 没等到回话（`reply_pending`）不用重说，她的回话之后会进 `events(topic=chat)`。

## 用现有能力和她互动

| 想做的 | 怎么做 |
| --- | --- |
| 坐下、站起 | 空手 `use(target=e#, sneak=true)`；手里拿着东西时潜行右键会变成别的用法，一定空手 |
| 喂金苹果、药水、牛奶 | `use(item=…, target=e#, sneak=true)`；药水只认普通药水瓶 |
| 引她过来乞食 | `equip(item=minecraft:cake, slot=mainhand)` 拿着蛋糕，她的 `begging` 变 true；拿蛋糕右键自己的女仆是打开她的界面，不是喂 |
| 抱起、放下 | `use(item=minecraft:saddle, target=e#)` 抱起；`use(item=minecraft:saddle)` 不给目标就是放下。她不让抱（`rideable=false`）时抱不起来 |
| 收进魂符、放出 | 空魂符 `use(target=e#)` 收；`use(target=<地面一格>, face=up)` 放。她自己不过传送门，跨维度前先收进魂符 |
| 往她背包放东西 | `deposit(target=e#, …)`：只放进她的储物格，不碰她手上身上的 |
| 看她背包里有什么 | 空手 `use(target=e#)` 打开她的界面 |
| 改皮肤、工作、班次、跟着还是驻守、拾取、手上身上的东西 | `maid_configure`，见下 |

## 让她干活

顺序：先给工具、再给材料、再切工作、最后让她驻守在活儿旁边。

1. 工具放进她手上：`maid_configure(target=e#, settings={"mainhand": "minecraft:iron_hoe"})`，东西要先在角色背包里。
2. 材料放进她背包：`deposit(target=e#, items=[…])`。
3. 切工作：`maid_configure(target=e#, settings={"work": "farm"})`。结果的 `work_hint` 写着界面上这份工作的条件满足没有，不满足她只会站着；`shift_now` 不是 `work` 时要等她的班次开始才动。
4. 驻守：带她到田边，`maid_configure(target=e#, settings={"stay": "in_area"})`，她就围着那儿干活。

常用工作和要的东西（以 `work_hint` 为准）：

| 工作 ID | 干什么 | 要的东西 |
| --- | --- | --- |
| `idle` | 空闲 | — |
| `farm` | 种田 | 背包里放种子；主手拿锄时收割把成熟作物整格打掉 |
| `sugar_cane`、`cocoa`、`grass` | 收甘蔗、可可、割草 | — |
| `melon` | 收西瓜南瓜 | 主手要精准采集的工具 |
| `snow` | 铲雪 | 主手拿锹 |
| `shears` | 剪羊毛 | 主手拿剪刀 |
| `milk` | 挤奶 | 桶 |
| `honey` | 收蜂蜜 | 玻璃瓶和剪刀 |
| `torch` | 插火把 | 火把 |
| `feed` | 喂你吃东西 | 背包里放食物 |
| `feed_animal` | 喂动物 | 对应的饲料，界面还写着要武器 |
| `fishing` | 钓鱼 | 钓竿 |
| `attack`、`ranged_attack`、`crossbow_attack`、`trident_attack`、`danmaku_attack` | 打怪 | 近战武器、弓和箭、弩和弹药、三叉戟、御币 |
| `extinguishing` | 灭火 | 灭火器 |
| `board_games` | 陪你下棋 | 附近有棋盘 |

## 给她定工作、休息、睡觉的地方

- 拿河童罗盘依次点三处方块：`use(item=touhou_little_maid:kappa_compass, target=<方块>)`，第一下是工作点、第二下是休息点、第三下是睡觉点，三处同一维度、彼此不超过 64 格。
- 再对她点一下 `use(item=touhou_little_maid:kappa_compass, target=e#)` 写进她身上，聊天栏会回一句写进去了；之后驻守（`stay=in_area`）就围着这些点活动。
- 潜行点方块清空罗盘，潜行点她清掉她身上的点。拿罗盘潜行右键她不会让她坐下。
- 女仆床 `touhou_little_maid:maid_bed` 摆在睡觉点旁边，她到睡觉时段会去睡。

## 她会碍事的地方

- 她跟着时会捡角色要 `gather` 的东西（身边 8 格内的掉落物）：采集前让她坐下或驻守，或者关拾取 `{"pickup": "false"}`。
- 离你超过 10 格会被瞬移到你身边，可能正好挡住施工站位：施工前让她坐下或驻守。
- 过传送门前先收进魂符。

## 下五子棋

- 摆一张五子棋盘。休息时段她自己会去附近的空棋盘边坐下；工作时段先 `maid_configure` 把工作切成 `board_games`。
- `observe` 的 `mod_things` 里 `gomoku_board`（b#）写着轮到谁、下了几手、最后一手、她坐没坐上；`observe(detail)` 给整个棋盘。
- `gomoku_play(target=b#, move=H8)` 落子并等她应手。记谱站在她座位的对面看：列 A–O 从左到右，行 1–15 离你最近的是 1。这一局结束了先 `operation=reset` 重开。
- 你赢她一局，她的段位可能升一级，越往后越难赢；新女仆好赢。赢了自己的女仆会涨她的好感，赢别人的女仆不涨。

## 她死了

聊天栏会出现一行 `[name:…, x:…, y:…, z:…, dim:…]`：它以角色自己的名义出现（`kind=player`、发言人是角色），但不是角色说的话；`name` 是皮肤路径，不是她的名字。按坐标 `travel` 过去，墓碑在 `mod_things` 的 `maid_tombstone`，空手 `use(target=<墓碑 e#>)` 取回她的东西。胶片可以在祭坛或神龛复活她。

## 妖精与 P 点

妖精是会发弹幕的敌对生物，打掉后走近把 P 点球吸过来。P 点只在主手拿御币时显示。

## 只用现有能力能做、但结果以游戏为准的

- 相机拍照：`use(item=touhou_little_maid:camera, target=e#)` 对自己的女仆右键，会把她整只收进照片——她从世界上消失，照片掉在角色脚下，记得捡起；她在睡觉时拍不了。拿照片 `use(item=touhou_little_maid:photo, target=<地面一格>, face=up)` 放出她。
- 神龛复活：先把胶片放进神龛，再空手右键神龛，要花掉一半多的血，血不够先回血。
- 祭坛：`design` 读祭坛的结构文件、`build` 施工、`use` 把料放到柱顶。合成要的料和摆法 `lookup(topic=recipe, …)` 查，结果如实以游戏为准。
