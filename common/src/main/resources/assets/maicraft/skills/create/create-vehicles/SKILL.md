---
name: create-vehicles
description: 机械动力航空学一套（Simulated、Aeronautics、Offroad）的飞艇与小车：选部件、搭船身、给无线红石打字机绑键、组装成会动的结构、先看再试按、开过去，开不好时怎么改；要自己造一艘飞艇或一辆车、或要开现成的载具时读
metadata: {"requires-mods": "simulated"}
---

# 机械动力航空学：飞艇与小车

一辆载具从无到开走要过七段：选部件 → 搭 → 绑键 → 组装 → 先看 → 试按 → 开，开不好再拆回改。每段有自己的证据，前一段成了不冒充后一段：方块对上蓝图不等于组装成了结构，组装成了不等于托得起来，试按有反应不等于开得到、停得稳。

用到的能力：`machine_build` 搭和补，`machine_configure` 绑键、改操纵表，`vehicle_assemble` 组装与拆回，`machine_inspect` 看（指 `s#`），`vehicle_drive` 试按与开，`travel(via=vehicle)` 开着去一个地方再下船走完。部件怎么摆读资料 `lookup(topic=knowledge, id=maicraft://knowledge/vehicle/parts)`，这里只讲先后和怎么判断。

## 先定做什么

- 现场已经有船或车：`observe` 的 `mod_things.structure` 里拿 `s#`，`machine_inspect(target=s#)` 看 `kind` 和 `control_map`，能开就直接开，别为一趟路再造一辆。
- 飞艇翻山越水，车只适合平地直线。第一辆做小、做对称：重心好找，受力好调，拆回改也快。
- 飞艇至少要：升力（气球加热空气燃烧室，或浮空水晶加向上的推力）、推进（螺旋桨轴承加风帆）、转向（左右两个桨分开开最省事）、船上的转动来源（便携式引擎）、座位、无线红石打字机、每个键一个收端、物理组装器。车把升力和桨换成车轮悬架加车轮，前轮两侧的收端管转向。
- 材料与配方：`lookup(topic=recipe, id=物品 ID)`；部件的思索在它的物品资料页（`lookup(topic=knowledge, id=物品 ID)`）。拿料交给 `obtain`。

## 搭（machine_build）

- 写一份机器蓝图，`machine_build(blueprint=…, target=锚点, name=档案名)`：`cells` 逐格，朝向写进 `properties`；胶可以不写：组装时会按这份档案自己补；要自己写就写成安装段 `{"kind": "create:super_glue", "offsets": [一角, 对角]}`，两角都得是方块、在框里由方块连着、相距不到 25 格，大船分几块、互相叠一层；收端可以在 `settings` 里写好 `link.mode` = `receive` 和两个频率格 `link.frequency.1`、`link.frequency.2`。
- 船身架空一格起建（车轮悬架下面是满方块时会被抬高一格），垫的东西放在胶框外面，不然组装时连地面一起带走。头顶留出净空。
- 施工在后台推进：下达后去备下一段要的东西（频率物品、引擎燃料），用 `events` 等结果。
- 下达前可以 `machine_review` 看材料和身上缺多少；它不算载具的受力，受力要组装后看。
- 证据：`changes` 动作做成没有，`check` 结构对不对，`installations[]` 胶粘上没有。这时它还只是一堆方块。

## 绑键（machine_configure，组装前在世界里做）

- `machine_configure(target=无线红石打字机那一格, settings={"key.W": "物品甲 + 物品乙", "key.空格": "…"})`：身上要有这两样物品，原来绑的键不会被抹掉，结果 `applied[]` 是读回的键表。
- 一个键一组频率，船上对应的收端放同样两样物品。整辆用一套自己的组合（第一格都放这辆独有的一样东西），别和门、电梯、别的载具撞频。
- 键随便挑，按习惯排好记：W 前进、S 倒车、A 和 D 左右、空格升力。开的时候按的是操纵表里的角色，不是键名。
- 组装成结构以后在船上改键、改部件设置都还不会：要改先拆回。

## 组装（vehicle_assemble）

- `vehicle_assemble(target=物理组装器的坐标或记过的地点)`：走过去拉一次杆。组装器落在机器档案里时，拉杆前先把档案里没被强力胶框住的地方补上（`glued` 写补了哪几框），档案外的方块不粘；不在档案里就不补，胶要自己在蓝图里装好。
- 补胶没补成（拿不到强力胶、走不到角跟前）就不拉杆，照 `glued.remaining` 看还差哪几框，备好胶或腾出路再下一次。
- 只拉一次：拉过不管成没成都不再拉（再拉会拆回去）。看 `converted`（真的转换了）、`blocks`（几块进了结构）、`archive.structure`（新结构的 `s#`）。
- 拉了没变成结构不算失败，照 `note` 改：组装器没放在船身的方块上、胶框里有方块没挨着、结构太大；不在档案里时还可能是胶没框全。改完再下一次 `vehicle_assemble`。
- `blocks` 比预想的少，或地面被带走了：胶框没盖全或框多了，拆回改；后来另加、没进档案的方块不会被补胶，要先并进档案。

## 先看（machine_inspect(target=s#)）

- `kind` 是 `airship` 或 `car` 才开得了；`structure`（没有座位或控制件）、`plane`（只有螺旋桨）、`unknown`（太大读不全或有格没加载）都开不了，先改船。
- `seats[]` 里有 `driver`；`controls[]` 列出每个绑了频率的键、收端几个、接着什么、推出来的 `role`；`control_map.missing` 写开这一类还缺哪个必需角色（飞艇要动力、升力、偏航，车要动力、转向）。
- 受力（服务端也装了 MaiCraft 才有）：`balance=forces` 看 `lift_ratio`（升力比重量，大于 1 才托得住）和两种情况 `cases` 的 `steady`、`tendency`；`balance=ballast` 再给几处放铁块的位置。第一次问要多等几刻。这是静态预测，不是实飞。
- 配重不在天上加，也不在组装后的船上施工：停在地上量好，拆回成方块，按建议的位置补铁块（拆回时船会转正，偏一格就照船身对应的那格放），再组装。

## 试按（vehicle_drive(operation=test)）

- 停在地上或水面上试：`vehicle_drive(vehicle=s#, operation=test, inputs=[{"control": "打字机:W", "ticks": 20}, {"control": "打字机:空格", "ticks": 120}])`，最多 16 条，每条 1～200 刻。气球是慢慢充起来的，升力键按久一点。
- 每条看 `response` 和 `effect`（沿结构自己的轴挪了几格、转了几度，已扣掉漂移）。试过的作用记进操纵表；第一次试出动力时，船往哪边走哪边就是车头，所以桨朝哪推都行。
- 推错了、试不出来（在地上推不动、作用太小记成 `none`）：照你的接线直接定，`machine_configure(target=s#, settings={"打字机:A": "yaw_left", "打字机:D": "yaw_right", "forward": "+z"})`，你定的以你为准，之后试按不会盖掉。
- 证据：试按只证明这个键让船怎么动，不证明开得到。`control_map.missing` 空了、`forward` 也有了再开。

## 开

- 只想开到某处停住：`vehicle_drive(vehicle=s#, target=…)`，`target` 给坐标、记过的地点，或"往东南西北哪边开多远"。要去一个地方、到了下船走完最后一段：`travel(target=…, via=vehicle, vehicle=s#)`；不给 `via=vehicle` 时 `travel` 不会擅自开船。
- 飞艇的阶段：垂直离地约 8 格、姿态稳了才前进 → 保持巡航高度对准落点 → 离落点 24 格内移到它正上方减速 → 垂直下降 → 接地松开所有键、停稳。结果 `drove` 里 `phases` 写阶段怎么变，`landing` 落在哪、怎么选的，`arrived`、`parked` 分开写到没到、停没停稳。
- `cruise_altitude` 默认取当前与目的地高的那个再加 32。越高升力越弱，升不上去就给低一点；前方地形高了它会自己往上抬，不绕。
- 车和船朝目标开直线，到了刹住；路上的障碍不绕，按着动力 5 秒挪不动就照实停下。
- 装了 Create: Flight Control 的飞艇：高度稳定器、智能姿态稳定器、线速度耦合器都接着在转的轴、接了飞控计算机或开了直接施力，而且无线红石打字机有四个键经飞控红石代理接到耦合器沿船身 +x、-x、+z、-z 时，开的时候改成设目标高度加给方向，船不转头直接滑过去，不查升力、转向键和车头。先看 `machine_inspect` 的 `stabilizers.driven_by`：是 `stabilizers` 就走这条路，是 `keys` 时 `why` 写缺什么，照旧按键开；开完 `drove.driven_by` 写实际怎么开的。
- 开远路是后台目标：下达后去想下一步（落点附近要干什么、要不要备料），用 `events` 等结果。中途要暂停、取消或去办不急的事，它会先就近降落或刹住再停。

## 开不好时怎么改

| 现象 | 先看 | 改什么 |
| --- | --- | --- |
| 开着升力 30 秒没离地 | `balance=forces` 的 `lift_ratio` | 加大气球、多一台燃烧室、减重；检查燃烧室正上方第一块是不是气球顶 |
| 起飞就歪、一头栽 | `balance` 两种 `cases` 的 `tendency` | `balance=ballast` 的位置加铁块；推进装到重心高度附近 |
| 飞到一半掉高度 | 巡航高度、`lift_ratio` | `cruise_altitude` 给低一点，或加升力 |
| 落地了但没停稳 | `drove.parked_samples` 与 `drove.landing` | 换平整的落点；调配重 |
| 车卡住不动 | `drove.distance_left`、应力读数 | 换一个直线上没挡的目标分段开；查引擎燃料和过载 |
| 一个键开了两样东西、松开还在动 | `controls[]` 的 `receivers`、`drives` | 挪收端；换这辆专用的频率 |

改船的做法：

1. 停稳在地面附近拆回：`vehicle_assemble(target=s#, operation=disassemble)`。在高空、还在动的船拆不了。
2. 在世界里改：就地拆回、方块落回原处的，`machine_build(target=档案名, blueprint=补丁)` 改，不要的格写 `minecraft:air`；开走过的船拆回后位置和朝向都可能变了，按现场坐标补。改了键接线就重新绑键。
3. 从**同一台**组装器再组装。结果 `archive.state` 是 `followed` 就是认出同一艘，操纵表跟到新的 `s#`；`archive.changes` 列出多了少了什么、哪些键接线变了，`archive.dropped` 列出没带过去的行。接线变了的键要重新 `vehicle_drive(test)`。

## 现在还不会的

- 固定翼不会自动起降；只靠朝上的螺旋桨飞的也算固定翼。
- 车只开直线，不绕障碍。
- 组装后不能在船上改无线红石打字机的键和部件设置，也不能在船上施工（加配重也要拆回）；在动着的结构上更不行。
- 舵轮、油门拉杆、讲台遥控器开不了。
- 只会用强力胶粘，不会用蜂蜜胶（手粘的蜂蜜胶补胶时不算数）；飞控红石代理哪一面接哪个动作、稳定器的增益与模式不会配，已经配好的照样能开。

## 什么时候停下

部件拿不到，或连着两种不同布局都托不起来、开不动时，把试过的布局、受力读数和试按结果写进结果，让调用方决定。
