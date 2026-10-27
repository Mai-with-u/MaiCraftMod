看一台机器或一片机器区有哪些网、哪些设备、设备之间怎么连着、各自转不转，只看不碰。target 指看哪里：机器的某一格（观察编号或坐标）、记过的地点、机器档案名，不给就是脚下；radius 是范围，默认 16 格（指档案名时按档案的大小看）。没装对应模组的联动时，模组机器照样列出来，只是写明没有机器类型认领它。

给 `item`（物品 ID）时不看机器，看身上这种物品自己的设置（机械动力的过滤器、属性过滤器的规则）：`stacks[]` 每一件一行，写明在第几格（`inventory_slot`，副手 40）、现在每项是什么；要改用 machine_configure 带同样的 item。

target 给物理结构的观察编号 `s#`（装了 Simulated 时）就是看一辆载具，不扫世界里的方块，结果是 `vehicle` 一节：

- `kind`：`airship` 飞艇（有燃烧器、蒸汽喷口、气球或浮空水晶）/ `car` 车（有车轮悬架）/ `boat` 船（底下是水）/ `plane` 固定翼（只有螺旋桨）；没有座位或控制件的是 `structure`（只是会动的结构，不是载具）；没读全说不清是 `unknown`。
- `seats[]`：每个座位此刻在世界里的位置，`driver` 是坐着够得着无线红石打字机的驾驶座。
- `controls[]`：无线红石打字机每个绑了频率的键——`control` 就是 `vehicle_drive` 的 `inputs` 里写的名字（`打字机:W`），`frequency` 两格频率物品，`receivers` 结构上收这个频率的收端几个，`drives` 收端旁边接着的执行件，`role` 推出来的角色（`power` 动力、`lift` 升力、`steer` 转向，左右要试按）与 `basis` 依据；隔着离合器、齿轮、红石线的推不出，是 `unknown`，用 `vehicle_drive(test)` 试按看它让船怎么动。
- `state`：`moving`、`speed` 每秒几格、`heading` 结构自己的 +z（造的时候朝南那头）此刻朝哪（0 南、90 西、180 北、270 东）、`pitch` 与 `roll` 绕自己的 x、z 轴斜了几度、`contact` 底面压着地面 `grounded` / 悬空 `airborne` / 底下有格没加载 `unknown`、`driving` 谁在驾驶（`self` 角色坐在驾驶座上、`other` 别人在用无线红石打字机）。
- `control_map`：操纵表——每个输入一行（`role` 角色、`source` 来源：`inferred` 推出来的 / `tested` 试按过的 / `llm` 你定的，以后者为准、`note` 依据），`forward` 车头朝结构自己的哪根水平轴（试按动力时看出来或你定的），`missing` 开这一类载具还缺的必需角色；角色推错了或想换用 machine_configure 对同一个 `s#` 改。
- `balance`（参数 `balance=forces` 或 `ballast` 时才有，要服务端也装了 MaiCraft）：受力。`cases` 是两种静态情况——`开着动力`（此刻记到的全部力，动力没开时没有这一种）与 `关着动力`（去掉推进）——各自绕结构自己的 x、z 轴的角加速度（`tilt_x`、`tilt_z`，度每二次方秒）、`steady` 稳不稳、`tendency` 往哪头倒；`lift_ratio` 升力（不算推进）比重量，1 就是刚好托住；`mass` 质量。`balance=ballast` 时 `ballast` 给几处放一块铁块能让两种情况都倒得慢的位置（世界坐标、`before` / `after` 放前放后最坏的那种情况，同样几处的局部坐标在 `local.ballast`），重心跟着挪也算了；不自动放，要放用 `machine_build(target=s#)` 直接在船上补，停在地上放，不在天上加。这是静态预测，不是实飞：阻力跟着速度走没算，开着动力那种要在开着时看；实飞用 `vehicle_drive` 验。服务端第一次被问时才开始记各处的力，所以第一次要多等几刻。
- `stabilizers`（装了 Create: Flight Control、船上有它的稳定器时才有）：飞行稳定器。`parts[]` 每个稳定器——`kind` 是 `altitude_hold` 高度稳定器 / `attitude_hold` 智能姿态稳定器 / `velocity_coupler` 线速度耦合器，`on` 开没开，`powered` 底下的轴转没转（没动力它什么都不做），`output` 怎么出力（`computer` 接了飞控计算机 / `direct` 直接施力 / `none` 都没有，算了也不出力），`mode` 模式（高度稳定器 `hold_current` / `hold_set` / `minimum`，姿态稳定器 `level` 配平 / `level_heading` 配平并保持航向 / `hold_attitude`），`target` 与 `altitude` 高度稳定器的目标高度与此刻高度（结构转动点的世界 y，不是船底），`max_x` / `max_y` / `max_z` 耦合器沿机体三根轴的最大目标速度（格每秒，0 就是只会刹）；`inputs[]` 飞控红石代理每个接了动作的面——`control` 是哪个无线红石打字机键发它旁边那个无线收端的频率（没有键发为空），`frequency`，`device` 转给谁，`action` 动作原名（耦合器的 `body_vx_pos` 就是沿机体 +x）；`driven_by` 是 `vehicle_drive` 开它会走哪条路：`stabilizers`（设目标高度、按键给方向，不用升力和转向键）或 `keys`（照旧按键），后者 `why` 写为什么。要走稳定器得三样都能干活，而且无线红石打字机的键经代理接到耦合器沿机体 +x、-x、+z、-z 四个方向。
- `local`（装了 Sable 时）：结构局部坐标，在船上施工（`machine_build` 指 `s#`）照这一套写偏移。`origin` 原点是哪一格——船上跟着机器档案时是档案锚点（造船时蓝图的偏移照用），没有档案是物理组装器，再没有是地块中心；`archive` 跟在船上的机器档案名；`min` / `max` 方块范围；`seats` 座位、`ballast` 配重建议各在哪一格。
- `complete` 为 false 时种类与回路只按读到的部分；`note` 写明没读的部分（舵轮、油门拉杆的回路、外面同频的发射端）。

target 指跟到船上的机器档案名（组装后档案会跟着船走）时也是看这辆载具：给 `vehicle` 与 `local`，再按局部坐标和档案整机比对（`archive_check`：没对上的格、多出来的格、安装段与部件装成没有，格都按局部坐标写）。船这会儿不在附近看不到时照实说。

## 读成机器蓝图（operation=read_site）

主播手搭的机器想照着再建一台、或者以后要改它：`operation=read_site`，target 指那台机器的一格（观察编号、坐标、记过的地点），可选 `name` 给档案起名（不给按锚点起"机器@x,y,z"）。挑 target 落在的那一台（不在任何一台上就挑最近的），按它的包围盒把里面每一格都抄下来，连夹在中间的箱子、原版方块、水源一起，存成一份机器档案。只读，不动角色、不改世界。

- `site.blueprint` 就是 `machine_build` 的 `blueprint` 参数，原样喂回去：照着建一台用 `machine_build(target=新地方, blueprint=site.blueprint)`；改这一台用 `machine_build(target=档案名, blueprint=补丁)`，补丁和它同一个锚点（`site.anchor`）；之后 `machine_inspect(target=档案名)` 看它和档案差在哪。
- 传送带抄成两头的轴加一段 `create:belt` 安装段，链条输送机之间的链、机械臂（连取放点）、ME 线缆与线缆上的部件也都写成安装段与部件，照着建时按模组自己的装法装。
- 方块的摆放属性（朝向、轴向、上下半）写进去当验收标准；开关、充能、亮灭、在不在线这些转起来会变的不写。空气不写：照着建时那些格里原有的东西会留着，要清空就在 `cells` 里补写 `minecraft:air`。流动的水不写，倒下水源它自己会流。
- 箱子与机器里的东西、Mekanism 侧面和过滤规则这类要开界面才看得见的设置不抄：照着建完再 `machine_configure`。
- 指着档案名读时不另建档，`site` 照现场给、`check` 照给整机比对；`name` 已经有档案在用时不覆盖（参数错误）。
- `site.complete` 为 false 时看 `site.notes`：包围盒里有格没加载、机器碰到了这次看的范围边上（加大 `radius` 再读）、带子伸出了这台机器这类没抄全的地方都写在那里。

## 结果怎么读

结果分三块：网、设备、连接。线缆、传动件（轴、齿轮、链式传动箱）、管道这些只负责连接的格不单列，合进它们所在的网。

- `networks[]`：每张网一条。`id` 网络编号、`kind` 种类（`me`、`kinetic`……）、`description` 一句说明；`readable`、`readings`、`note` 是整张网的读数（频道、能量、应力、过载；通用机械的管网给存着多少电或流体、化学品，电按 FE、流体按毫桶，物品管网不在网里存东西），只在这里给一次。`connectors` 是这张网在范围里的连接件：共几格（`cells`）、在哪一片（`at` 或 `box`）、按类型计数（`types`）、按读数分组的 `states`（每组几格、在哪一片、读数，例如几格在转、什么转速、几格没电）；范围里一格连接件都没有（设备直接贴着）时没有 `connectors`。没有网络读取器的连接件（流体管道这类）按贴着连成段，`kind` 是 `adjacent`，`id` 是 `adjacent@最低那一格`，没有整网读数，管道里装着什么看 `states`。
- `devices[]`：其余机器方块。同一块里同种、相邻的格合成一个设备（8 格保险库算一个、一排机械手算一个），线缆格某一面装着的部件（总线、终端）各算一个。`id` 是这一眼里的编号（d1、d2……），只对这次结果有效；想再指某一个，用它的格（观察编号或坐标）。`type` 机器类型（部件写部件的物品 ID）、`role` 在机器里干什么（部件是 `part`）；一格的给 `at`，多格的给 `cells` 几格与 `box`；部件给 `host` 装在哪一格、`side` 哪一面。`on` 挂在哪些网上（网络编号，含贴着的管道段）。读数各格都一样时给一份 `state`（`activity` 在干活 / 停着 / 过载 / 离线 / 不知道、`speed` 转速、`progress` 进度、`shown` 看得见的东西、`readings` 其余读数、`note`），不一样时给 `states`，每组列出是哪几格（几台机械手各拿着什么）。和所在网整网读数同名同值的项（应力、容量）不在设备上重复。`ports` 只列网表示不了的进出口：Mekanism 的侧面配置、置物台台面怎么放东西这类；已经挂在 ME 网或应力网上的设备不再逐面列 ME 面与转动面，还没接上网的照列（看从哪一面接）。
- `connections[]`：设备之间的连接，`from` 从哪个设备、`to` 连到哪个设备；那一格不是设备时写 `to_cell` 在哪、`to_block` 是什么方块（没加载时没有 `to_block`）。`kind`：`facing` 部件朝着那一格（输出总线对着的箱子、存储总线读的保险库；朝着空气的终端不算）、`processing_spot` 那一格是这个设备的加工处（压机、搅拌器、注液器正下方第二格，机械手前方第二格，鼓风机前方第一格；空着也写）、`touching` 两个设备贴着（漏斗贴着保险库、置物台贴着传送带）。
- `unrecognized[]`：看着像机器但没有机器类型认领的格，按方块 ID 归并；装了对应模组的联动才能认出来。
- `unloaded_cells`：范围内没加载的格数，这些格是什么不知道，不按空气算。
- `scan_complete`：范围扫完了没有；没扫完时列出的只是已经看到的部分。
- `archive`、`check`：指档案名时，和档案的整机蓝图比一遍。`check.counts` 是每种结论几格（matches、wrong_block、wrong_state、missing、unknown、checked_by_machine、extra），`check.differences` 列出每一格没对上的位置、结论、蓝图要的、现场是什么，`check.extra` 是蓝图范围里没声明却有东西的格（只作信息，水流、产物这些正常变化也会出现在这里），`check.installations` 与 `check.parts` 是安装段、部件装没装成；有格没加载时 `check.complete` 为 false，不下"整机一致"的结论。
- 机器能不能转以各设备的 `state` 为准：结构和档案一致不等于能转。机器内部与箱子里的东西这里看不到，要看就 use 打开界面。有格落在所看档案里的设备与网带 `archive`。
- `site`（只有 read_site 才有）：`devices` 抄进来的是这一眼里的哪些设备（连接件随设备一起抄，不另列）、`archive` 档案名、`archived` 这次建没建档、`anchor` 锚点、`size` 包围盒东西上下南北各几格、`blueprint` 蓝图参数、`cells` / `installations` / `parts` 各几条、`unread` 没加载的格数、`complete`、`notes`。

## 问题种类

- `TARGET_GONE`：观察编号指的东西不在了；重新 `observe` 拿新编号。
- `NOT_FOUND`：没有叫这个名字的记过地点或机器档案；read_site 时这一片一台认得的机器都没有。
- `INVALID_PARAMETER`：read_site 的 `name` 已经有档案在用；`name` 没配 read_site；read_site 配了 `item` 或载具的 `s#`。
- `UNREACHABLE`：目标在另一个维度，或看到一半离开了世界。
