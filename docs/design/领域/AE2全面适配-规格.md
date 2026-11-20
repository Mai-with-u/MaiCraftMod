# 领域规格：应用能源2（AE2）全面适配

> 状态：已批准（2026-11-20，全量推进阶段 1～4）。
> 依据：Applied Energistics 2 源码 `Applied-Energistics-2/`（分支 `1.21.1`，对应发行版 `19.2.17`，与开发客户端及实测实例 `appliedenergistics2-19.2.17.jar` 一致）、[机器联动模组事实清单](机器联动模组事实清单.md)第 1 节（1.6～1.15）、[联动模组需求总表](联动模组需求总表.md)第 3.2 节与第 4 节（A1–A5）、[机器-规格](机器-规格.md)。
> 范围：在已有 AE2 终端取存、装桶、缺口自动合成下单、无线终端绑定、落地取水、泡水转化、压印器基础运行、驱动器十槽读数、陨石箱存储与 ME 网络读数的基础上，补齐阶段 1～4 的全部工作方块、容器、自动合成闭环、线缆部件按面配置、磁盘与网络运维、量子环与奇点爆炸转化、空间存储、特色手持工具与知识补强，以及 4 处公共 SPI 极小扩展。
> 边界：本规格不新增任何顶层 MCP 工具或独立能力，所有交互全部挂接回既有能力（`machine_inspect` / `machine_review` / `machine_build` / `machine_connect` / `machine_configure` / `machine_run` / `obtain` / `deposit` / `use` / `fight` / `observe` / `lookup`）。同实例附属模组 `Applied-Mekanistics`（1.6.3）的 `MekanismKey` 化学品读写单列后续 AE2×Mekanism 评估；`appliedsticks`（1.2.0）仅做建筑棒连网转接，无需单独适配。

## 1 范围

| 阶段 | 模块 / 对象 | 主要挂接能力 | 是否依赖服务端只读 |
| --- | --- | --- | :---: |
| 基础 | 通用语义界面布局证明（`Ae2SemanticsLayout`）、设备操作委托（`Ae2DeviceOps`）、4 处公共 SPI 扩展 | 全部容器/机器/战斗/随身背包能力 | 否 |
| 阶段 1 | 充能器与木质曲柄（`ae2:charger`、`ae2:crank`） | `machine_inspect`、`machine_run`（`FullRun`）、`machine_build`（`PlacementRules`） | 否 |
| 阶段 1 | 振动室（`ae2:vibration_chamber`） | `machine_inspect`、`machine_run`（`FuelUse`）、`machine_configure` | 可选（不进界面时） |
| 阶段 1 | 物质聚合器（`ae2:condenser`） | `machine_inspect`、`machine_configure`、`machine_run` | 可选（不进界面时） |
| 阶段 1 | ME 箱子（`ae2:chest`） | `machine_inspect`、`use`、`obtain`、`deposit`、`machine_configure` | 否 |
| 阶段 1 | 陨石箱子与陨石储罐（`ae2:*sky_stone_chest`、`ae2:sky_stone_tank`） | `use`、`obtain`、`deposit`、`machine_inspect` | 否 |
| 阶段 1 | 水晶催生器（`ae2:growth_accelerator`） | `machine_inspect`、`machine_connect`、`lookup` | 否 |
| 阶段 1 | 石英扳手（`ae2:*_quartz_wrench`） | `machine_build`（`DismantleTool`、`PlacementRules`）、`use` | 否 |
| 阶段 1 | 10 种便携存储元件（`ae2:portable_*_cell_*`） | `CarriedBackpack`、`obtain`（`via=carried`）、`observe(self)` | 否 |
| 阶段 1 | 石英切割刀（`ae2:*_quartz_cutting_knife`） | `machine_configure(item=...)`（`ItemSettings`） | 否 |
| 阶段 1 | 陨石罗盘（`ae2:meteorite_compass`） | `observe(scene)`（`ModSight`）、`machine_inspect(item=...)`、`use` | 否（用 AE2 自带罗盘包） |
| 阶段 2 | 样板编码终端（`ae2:pattern_encoding_terminal`） | `machine_configure`、`machine_inspect` | 否 |
| 阶段 2 | 样板管理终端（`ae2:pattern_access_terminal`） | `machine_inspect`、`machine_configure` | 是（全量容器列表） |
| 阶段 2 | 样板供应器补全（`ae2:pattern_provider`、`ae2:cable_pattern_provider`） | `machine_inspect`、`machine_configure`、`use` | 可选 |
| 阶段 2 | 分子组装室（`ae2:molecular_assembler`） | `machine_inspect`、`machine_configure`、`machine_run` | 可选 |
| 阶段 2 | 压印器设置补全（`ae2:inscriber`） | `machine_inspect`、`machine_configure`、`machine_run` | 可选 |
| 阶段 2 | 合成 CPU 状态与取消（`ae2:crafting_*`、`ae2:craftingstatus`） | `machine_inspect`、`machine_configure` | 是（逐物料在途明细） |
| 阶段 2 | 合成终端 / 无线合成终端 3×3 手搓合成格 | `obtain`（`via=craft`） | 否 |
| 阶段 3 | 全线缆部件按面配置（输入/输出/存储总线、ME 接口、成型/破坏面板、标准/能量发信器、存储/转换监视器、6 种 P2P） | `machine_inspect`、`machine_configure`、`use` | 是（不同步配置与 P2P 出入端） |
| 阶段 3 | 元件工作台（`ae2:cell_workbench`） | `machine_inspect`、`machine_configure` | 可选 |
| 阶段 3 | ME IO 传输站（游戏方块 `ae2:io_port`） | `machine_inspect`、`machine_configure`、`machine_run` | 可选 |
| 阶段 3 | 无线访问点补全（`ae2:wireless_access_point`） | `machine_inspect`、`machine_configure` | 是（不开窗读增幅卡） |
| 阶段 3 | 内存卡与网络工具（`ae2:memory_card`、`ae2:network_tool`） | `machine_inspect`、`machine_configure`、`obtain`、`use`、`DismantleTool` | 是（网络耗电明细） |
| 阶段 4 | 量子环与量子链接仓（`ae2:quantum_ring`、`ae2:quantum_link`） | `machine_inspect`、`machine_configure`、`machine_build`、`machine_connect` | 是（奇点频率与连通） |
| 阶段 4 | 奇点爆炸转化 | `obtain`（`via=ae2_explosion_transform`） | 否 |
| 阶段 4 | 空间存储（空间塔、空间传输站 `ae2:spatial_io_port`、空间锚） | `machine_inspect`、`machine_configure`、`machine_run`、`machine_build` | 是（尺寸、能耗、加载区块） |
| 阶段 4 | 特色手持工具（熵变仪、物质炮、上色器、充能法杖）与通用带电物品 | `use`、`fight`、`machine_inspect`、`machine_configure`、`observe(self)` | 否 |
| 阶段 4 | 知识层补强（中文主题资料 + GuideME 按需只读检索） | `lookup` | 否 |

**既有能力衔接**：
- 既有 `compat.ae2` 包根保留共享入口 `Ae2Compat`、设备目录 `Ae2MachineCatalog`、主类型路由 `Ae2MachineType`、终端取存与缺口合成、压印器基础流程、驱动器十槽读数、陨石箱布局及 ME 网络读取器。
- 本轮新增模块按领域落入 `compat.ae2.machine`、`compat.ae2.part`、`compat.ae2.tool`、`compat.ae2.advanced` 四个子包，单向依赖包根，互不形成环；服务端扩展落入 `server.compat.ae2`；NeoForge 读写端按同样边界分文件放在 `neoforge.compat.ae2`。

## 2 真人怎么玩

刚起步还没有稳定电网时，真人会把充能器放在地上，侧面贴一只木质曲柄，把赛特斯石英或没电的便携元件放进充能器，有节奏地摇几圈曲柄充好，从正面地上捡起成品；也会在振动室里添煤炭临时发点电，或拿石英切割刀把金属锭刻成名字压印模板。背包带不下东西时，把多余的物塞进随身便携元件，顺手拿陨石罗盘辨别最近陨石的方向。发展到自动合成后，在合成终端的 3×3 合成格直接调网内材料手搓，用样板编码终端把合成、处理、锻造、切石配方编进空白样板，再放进对应机器旁的样板供应器或直接经样板管理终端调配；分子组装室塞满加速卡，合成卡住时打开合成 CPU 界面看缺什么或取消作业。铺设物流线时，在线缆各个面装上输入输出总线、存储总线、ME 接口、成型破坏面板、标准与能量发信器、监视器与 P2P 通道，逐面配好过滤、优先级、备料数量与红石模式，用内存卡批量复制粘贴配置或配对 P2P，用网络工具的升级卡工具箱随手抽卡、查看全网耗电明细，用元件工作台给磁盘分区、用 ME IO 传输站把旧盘数据倒进新盘，并用物质聚合器把垃圾压成物质球与奇点。到了后期，把奇点和末影珍珠丢在地上用迷你 TNT 炸出一对量子缠绕奇点，分别放进两端的量子环实现跨维连网；用空间塔围出房间、在空间传输站插上空间元件并给一次红石脉冲把整个房间装进硬盘，再用空间锚保持关键区块常载；出门随身带上装满弹药的物质炮、上色器、熵变仪与充能法杖，没电了回基地放进充能器充满再用。

## 3 新逻辑

### 3.1 通用基础：语义界面布局、设备解耦与服务端读数边界

#### 3.1.1 通用语义布局证明（`Ae2SemanticsLayout`）

所有继承 `UpgradeableMenu` 与 `MEStorageMenu` 的 AE2 界面在玩家背包带有 `ae2:network_tool` 时，会在界面最前面动态插入 9 个 `SlotSemantics.TOOLBOX` 槽，导致按固定下标或固定总槽数判定必然失效。

- 新增读写接缝 `compat.ae2.Ae2MenuSlots`（附 `NONE` 空实现，由 NeoForge 读写端 `AppliedEnergisticsMenuSlots` 实现）：
  - 查询当前打开的 AE2 菜单中指定 `SlotSemantic` ID（如 `"UPGRADE"`、`"CONFIG"`、`"STORAGE"`、`"STORAGE_CELL"`、`"MACHINE_INPUT"`、`"MACHINE_OUTPUT"`、`"MACHINE_CRAFTING_GRID"`、`"ENCODED_PATTERN"`、`"BLANK_PATTERN"`、`"CRAFTING_GRID"`、`"CRAFTING_RESULT"`、`"PROCESSING_INPUTS"`、`"PROCESSING_OUTPUTS"`、`"STONECUTTING_INPUT"`、`"SMITHING_TABLE_TEMPLATE"`、`"SMITHING_TABLE_BASE"`、`"SMITHING_TABLE_ADDITION"`、`"INSCRIBER_PLATE_TOP"`、`"INSCRIBER_PLATE_BOTTOM"`、`"INSCRIBER_PLATE_MIDDLE"`、`"INSCRIBER_OUTPUT"`、`"TOOLBOX"`）对应的槽位索引列表（经 `SlotSemantics.get(id)` 与 `AEBaseMenu.getSlots(semantic)` 读取）；
  - 提供通用 AE2 菜单发包动作：`sendConfigButton`、`sendSetFilter`、`sendGuiAction`（含强类型封装）、`openSubMenu`、`returnToParentMenu`。
- 新增通用布局证明 `compat.ae2.Ae2SemanticsLayout`（实现 `MenuLayoutProof`）：按菜单注册 ID 接受声明式规则 `Rule(List<String> containerSemantics, Set<String> machineSemantics)`：
  - 绝不把 `CONFIG` / `CRAFTING_GRID` 等幽灵槽列进 `containerSlots` 或 `machineSlots`；
  - 绝不把动态 `TOOLBOX` 槽混入机器或容器存储槽；纯配置界面支持双空集合返回 `Supported(playerSlots, List.of(), Set.of())`；
  - 仅对包含恰好 36 个玩家槽的菜单给出 `MenuLayout.Supported`；不含 36 格玩家背包的子菜单（`ae2:priority`、`ae2:set_stock_amount`、`ae2:craftingcpu`、`ae2:craftingstatus`、`ae2:networkstatus`、`ae2:controller_networkstatus`、`ae2:spatialanchor`）不走 `MenuLayout`，由对应读写端直连收发包；
  - 严禁与既有 6 个已登记证明重复注册同一菜单 ID；既有 `Ae2InscriberLayout` 加固为优先经 `Ae2MenuSlots` 按语义识别 4 工作格与 4 升级格（无读写端替身时回退兼容原 8 槽逻辑）。

#### 3.1.2 设备操作委托与目录扩展

- `Ae2MachineCatalog` 补齐本轮新增设备种类：`ae2:crank`（木质曲柄，单机控制）、`ae2:sky_stone_tank`（陨石储罐，单机存储）、`ae2:growth_accelerator`（水晶催生器，联网加工）、`ae2:cell_workbench`（元件工作台，单机控制）、`ae2:spatial_pylon`（空间塔，联网存储）、`ae2:spatial_io_port`（空间传输站，联网存储）、`ae2:spatial_anchor`（空间锚，联网控制），同时保留全部既有 18 种设备（含 `ae2:io_port` 显示名统一为「ME IO 传输站」）。
- `Ae2MachineType` 抽出内部委托接口 `Ae2DeviceOps`（按 `kind.id()` 路由 `extraReadings`、`feeding`、`settings`、`installations`、`partSettings`，全部提供默认空实现），使四路实现分别落在 `machine` / `part` / `tool` / `advanced` 子包，避免单文件超行。

#### 3.1.3 服务端只读操作扩展（`ae2.network` 与 `ae2.device`）

- 严格遵守不反射、不透视、不替玩家动手三条底线：所有服务端读数均经 `AskedCell.nearPlayer` 校验（角色所在维度、已加载、距离 ≤ 128 格）。
- 扩展既有只读操作 `ae2.network`（向后兼容旧字段）：
  - 调用 `NetworkStatus.fromGrid(grid)` 补充 `power_injection`（平均输入 AE/t）、`power_usage`（平均消耗 AE/t）、`channel_power`（频道耗电）与按设备分组的耗电计数 `machines[]`（用于网络工具状态查看）；
  - 补充 `cpus[]`（每台 CPU 的 `serial`、`name`、`storage`、`co_processors`、`mode`、`busy`、`cant_store`、`current_job`、`progress`、`active[]` 在途与 `pending[]` 待产明细，直接读 `CraftingCPUCluster.craftingLogic`）；
  - 补充 `pattern_containers[]`（按 `PatternContainerGroup` 汇总活跃样板供应器与分子组装室的总槽数、已用槽数与样板产出列表，替代读取 `PatternAccessTermScreen` 私有字段）。
- 新增服务端只读操作 `ae2.device`（版本 1，由 `Ae2ServerCompat` 登记）：按格坐标（及可选 `side`）回答客户端流不同步的单机或部件权威字段：
  - 线缆部件：升级卡列表、`IConfigManager` 设置、过滤/备料槽清单、优先级、发信阈值与是否触发、P2P `isOutput` 与频率、破坏面板附魔；
  - 无线访问点：增幅卡张数、精确最大范围与耗电；
  - 振动室 / 物质聚合器 / 压印器 / 分子组装室 / 元件工作台 / ME IO 传输站：非同步配置项、升级卡、内部进度与缓冲电量；
  - 量子环与量子链接仓：内部奇点频率与对端连通状态；
  - 空间塔 / 空间传输站 / 空间锚：合围区域尺寸、效率、切换能耗、已加载区块数与多维统计。
- 服务端未装 MaiCraft 或策略关闭 `ae2.device` 时，客户端照常工作：能从客户端同步读的直接给，不同步的配置项按 `unknown` 展示，打开对应界面后把读回的值记入会话缓存。

### 3.2 四处公共 SPI 极小扩展

本轮对跨模组公共接缝做 4 处极小、完全向后兼容的扩展，均不改变 MCP 协议：

| 编号 | 所在包与接口 | 扩展内容 | 解决的问题 |
| --- | --- | --- | --- |
| SPI-1 | `behavior.inventory.spi.CarriedBackpack` 与 `bootstrap.AbilityCatalog` | 新增 `CombinedCarriedBackpacks` 合并全部已登记的 `CarriedBackpack`（`freeSlots()` 求和、`store()` 顺序尝试直到有背包给出动作、`carried()` 合并列表），替换 `AbilityCatalog` 中 `carriedBackpacks.stream().findFirst()` | 精妙背包与 AE2 便携存储元件同时安装时，`InventorySpace` 腾背包不会再因 `findFirst()` 丢弃 AE2 便携元件 |
| SPI-2 | `ability.machine.spi.MachineSettings`、`compat.CompatMachineSettings`、`ability.machine.MachineAreaReport` | 在 `MachineSettings` 增加默认方法 `default List<MachineSetting> partSettings(BlockPos host, Direction side) { return List.of(); }`；`CompatMachineSettings` 包一层停用转发；`MachineAreaReport` 构造部件 `DeviceView` 时读取并填入 `settings`（空时保持 `null`） | `machine_inspect` 查看 `ae2:cable_bus` 各面部件时，部件设置项直接呈现在对应 `DeviceView.settings` 中，无需混进 `state.readings` |
| SPI-3 | `ability.machine.spi.ItemSettings`、`compat.CompatItemSettings`、`ability.machine.ItemSettingsTask` | 在 `ItemSettings` 增加默认重载 `default boolean holds(ItemStack stack, Player player, String key, String wanted) { return holds(stack, key, wanted); }`；`CompatItemSettings` 转发；`ItemSettingsTask` 核对时传入当前角色 `Player` | 石英切割刀刻名（`engrave_name`）改完关窗后可核对角色背包是否产出了指定名字的 `ae2:name_press` |
| SPI-4 | `behavior.survival.defense.spi.RangedWeapon`、`compat.CompatRangedWeapon`、`behavior.combat.ModShot` | 在 `RangedWeapon` 增加可选默认方法 `default boolean usesInternalAmmo() { return false; }` 与 `default boolean shotFired(ItemStack before, ItemStack after, LocalPlayer self) { return false; }`；`ModShot` 确认发射条件并入 `rule.shotFired(beforeWeapon, currentMainHand, self)`；确认 `ArrowAim` 支持 `gravity == 0 && drag == 1.0` 的瞬发直线弹道 | 物质炮弹药存于武器内部且无原版冷却，避免 `ModShot` 因背包弹药数未减且无冷却永远判未确认 |

### 3.3 阶段 1：日常工作方块、容器与基础工具

#### 3.3.1 充能器与木质曲柄

- **读数（`machine_inspect`）**：
  - 充能器（`ae2:charger`）：从方块实体流读取 `working`（`isWorking()`）与槽 0 物品；若槽内为带电物品，直接经 `IAEItemPowerStorage` 读出 `item_energy` 与 `item_energy_max`。`exchangePoints` 标明除 `FRONT` 外的 5 面均可连网、受电与装曲柄。
  - 木质曲柄（`ae2:crank`）：读朝向 `facing`、所附着主机格与 `visible_rotation`。
- **放置与朝向（`machine_build` / `PlacementRules`）**：
  - 木质曲柄登记 `PlacementRules`：放置前须先就位目标主机（`placeAfter`），且仅可贴附支持 `ICrankable` 的有效面（充能器非正面 5 面、压印器非正面 5 面、水晶催生器前后 2 面）。
- **运行（`machine_run`，经 `MachineFeeding.fullRun`）**：
  - 支持两类用途：① 充能转化配方（如 `item=ae2:charged_certus_quartz_crystal`，将赛特斯石英放入充能）；② 带电物品补电（`item` 为任意实现 `IAEItemPowerStorage` 且未满电的随身工具，如 `ae2:portable_item_cell_1k`、`ae2:matter_cannon`、`ae2:entropy_manipulator`）。
  - 执行流程：
    1. 槽 0 若已被其他物品占用，先空手右键弹出并从充能器正面地上捡回（若目标物已在槽 0 中且已满电/已转化则直接弹出捡回）。
    2. 备齐待充物品（转化原料走 M4；带电工具从身上取目标物品，不另造新件），手持右键放入槽 0（一次放入 1 件）。
    3. 监测进度：若接有外部电网或正在工作（`isWorking()`），等待转化完成或带电物品电量充满；若持续未工作且侧面附有木质曲柄（或允许在旁安装随身曲柄），按 `rotation < 3` 的节拍右键摇动曲柄，每转 18 tick 等其落稳注入 160 AE。
    4. **防毁曲柄纪律**：每次摇曲柄前核对物品是否已充完、或曲柄是否因满电拒转（`canTurn() == false`）；一旦满电立即停摇，绝不连续空摇超过阈值触发无掉落销毁。
    5. 完成后空手右键充能器，物品会掉落在正面相邻格地面，走近捡起并核对背包增量；重复直至达到 `count`。

#### 3.3.2 振动室

- **读数（`machine_inspect`）**：客户端读 `active`（`ACTIVE` / `isOn`）；打开界面或经 `ae2.device` 时补齐燃料槽内容、`remaining_burn_percent`、`power_per_tick`、`fuel_efficiency` 与 3 个升级槽（能量卡/加速卡）。
- **投料发电（`machine_run`，经 `MachineFeeding.fuelUse`）**：
  - `fuelUse` 返回 `FuelUse.fuel("接受任意可燃烧物品（如煤炭、木炭、木板）；岩浆桶烧完会留空桶阻塞燃料格")`。
  - 备齐燃料后经 `MenuFeed` 放入 `MACHINE_INPUT` 槽即完成；若燃料槽残留空桶或其他异物导致无法放入，在结果中明确点名槽内占用物与原因。
- **升级卡配置（`machine_configure`）**：支持 `upgrade.0..2` 放取 `ae2:energy_card` / `ae2:speed_card`。

#### 3.3.3 物质聚合器

- **读数（`machine_inspect`）**：客户端方块流无同步字段，未开窗且无服务端读数时标 `unknown` 并说明须开界面或依赖服务端 `ae2.device`；读到时给出 `mode`（`trash` / `matter_balls` / `singularity`）、`stored_power`、`required_energy`（256 / 256,000）、所装存储元件与输出槽内容。
- **配置（`machine_configure`）**：
  - `mode`：`trash` / `matter_balls` / `singularity`，开 `ae2:condenser` 发 `ConfigButtonPacket(Settings.CONDENSER_OUTPUT, false)` 循环切至目标值并核对 `@GuiSync(2)`；
  - `storage_component`：向 `STORAGE_CELL` 槽放入或取出存储组件（`in:<item_id>` / `out`）；切 `matter_balls` 至少需 1k 组件（8,192 容量），切 `singularity` 至少需 64k 组件（524,288 容量），元件不足时只提示事实，不预判阻止切换模式。
- **运行（`machine_run`）**：`item=ae2:matter_ball` 或 `ae2:singularity` 时，检查聚合器模式并按需投废料（只投非保留、非贵重的可消耗废料或 LLM 指定的低值杂物），从输出槽收回产物。

#### 3.3.4 ME 箱子

- **读数（`machine_inspect`）**：客户端直接读 `online`（`isPowered()`）、`cell_item`（槽内元件物品 ID）、`cell_status`（`CellState`）与颜色；注明顶面（相对朝向的 `getTop()`，非固定 `UP`）开存储终端界面、其余 5 面开元件插槽界面。
- **双面交互**：
  - 存取盘内物品：经顶面打开 `ae2:basic_cell_chest`（继承 `MEStorageMenu`），复用终端存取协议；无元件时如实按游戏拒绝（`ChestCannotReadStorageCell`）回报。
  - 换盘（`machine_configure` 的 `cell=in:<item_id>|out` 或 `use` 点非顶面）：避开相对顶面，点击其余 5 个可达面打开 `ae2:me_chest` 放取 `STORAGE_CELL` 槽。

#### 3.3.5 陨石箱子与陨石储罐

- **陨石箱子（`ae2:sky_stone_chest`、`ae2:smooth_sky_stone_chest`）**：保持 `ae2:skychest` 36 格存储布局（供 `use` / `obtain` / `deposit`）；`DismantleTool` 显式排除陨石箱子，拆解时走普通镐挖掘（因为 `SkyChestBlockEntity.disassembleWithWrench` 恒返回 `FAIL`）。
- **陨石储罐（`ae2:sky_stone_tank`）**：`machine_inspect` 从客户端同步的 `FluidTank` 直读 `fluid`、`fluid_amount`（mB）与 `fluid_capacity`（16,000 mB）；装桶与倒流体复用 `use(item=..., target=...)` 走原版与 `FluidUtil` 双向交互。

#### 3.3.6 水晶催生器

- **读数与接线（`machine_inspect`、`machine_connect`）**：客户端读 `POWERED` 状态；`exchangePoints` 仅向 `FRONT` 与 `BACK` 两面暴露 ME 连网/受电接口（其余 4 面不可连线缆也不可装曲柄，但全部 6 个相邻面均对 `#ae2:growth_acceleratable` 生效）；催生规则写入知识层。

#### 3.3.7 石英扳手

- **拆解（`DismantleTool`）**：主手持有满足标签 `#c:tools/wrench` 的石英扳手（`ae2:certus_quartz_wrench`、`ae2:nether_quartz_wrench`）或网络工具，对除陨石箱子外的 AE2 设备与线缆部件执行潜行右键整块拆解（掉落直进背包，不落地），并按 `CableBusBlockEntity` 选区规则精确命中目标面部件、中心线缆或伪装板。
- **转正（`PlacementRules.turnsTo`）**：主手持有石英扳手（排除 `ae2:network_tool`，因其不具备旋转功能）普通右键按 `IOrientationStrategy` 旋转方块朝向；对 `ae2:pattern_provider` 普通右键切换 `PUSH_DIRECTION`。

#### 3.3.8 便携存储元件

- 覆盖全部 10 种便携元件（物品 1k/4k/16k/64k/256k 与流体 1k/4k/16k/64k/256k）。
- **随身背包（`CarriedBackpack`）**：在 `compat.ae2.tool.PortableCellBackpack` 实现，经 SPI-1 `CombinedCarriedBackpacks` 与精妙背包共存：
  - 客户端免开界面读取 `AEComponents.STORED_ENERGY` 与 `StorageCells.getCellInventory(stack, null)`，精确计算可用类型与剩余字节，折算 `freeSlots()`（仅对物品便携元件参与腾背包放杂物；电量耗尽时 `freeSlots()` 报 0）；
  - `store()` 换到主手**普通右键**（绝不潜行，防止空元件被 `disassembleDrive` 拆解）打开 `ae2:portable_item_cell`，沿用终端 Shift 存入动作并核对背包变化后关窗；
  - `carried()` 向 `observe(self).carried_backpacks` 输出元件 ID、所在栏位、已读标志、估算空槽数及盘内物品汇总。
- **随身来源（`ItemSource`，`via=carried`）**：在 `compat.ae2.tool.PortableCellSource` 实现：问价直接读客户端同步的 `STORAGE_CELL_INV`（无需先开窗），有货且有电时报价；动手普通右键打开对应 `MEStorageMenu` 复用 `MenuTerminalTakes` 协议取物（物品）或装桶（流体），取完关窗。
- **低电处理**：电量不足无法开启或操作时如实以 `NOT_POSSIBLE_HERE` 收场，报出当前电量与上限，并建议通过充能器 `machine_run` 补电。

#### 3.3.9 石英切割刀

- 挂接至 `ItemSettings`（`machine_configure(item="ae2:certus_quartz_cutting_knife", settings={"engrave_name": "<名称>"})` 或下界石英版），依靠 SPI-3 的 `holds(ItemStack, Player, String, String)` 核验结果：
  1. 校验名称非空且身上有至少 1 个满足 `#ae2:metal_ingots` 的金属锭（没有则先按 `NEED_ITEM` 提示，不偷偷替造）；
  2. 将切割刀换到主手普通右键打开 `ae2:quartzknife`；
  3. 将 1 个金属锭移入 `MACHINE_INPUT`（槽 0），发 `GuiActionPacket("setName", wantedName)`；
  4. 从 `MACHINE_OUTPUT`（槽 1）取走带对应 `AEComponents.NAME_PRESS_NAME` 的 `ae2:name_press` 到角色背包（消耗 1 锭、1 耐久）；
  5. **关窗防掉锭纪律**：关闭菜单前检查槽 0，若仍有剩余金属锭先取回玩家背包再关窗，避免 `QuartzKnifeMenu.removed()` 把锭直接丢到地上；
  6. 关窗后经 `holds(stack, player, "engrave_name", wantedName)` 核对角色背包存在对应命名压印模板。

#### 3.3.10 陨石罗盘

- **反透视与感知（`ModSight`）**：`compat.ae2.tool.MeteoriteCompassSight` 每轮先检查角色主手、副手或背包中是否真实携带 `ae2:meteorite_compass`；**未携带时直接返回空，绝不调用 `CompassManager`**。携带时调用 `CompassManager.INSTANCE.getClosestMeteorite(chunkPos, true)`，若已有服务端响应坐标，向 `observe(scene).mod_things` 输出种类 `meteorite_compass`，字段含 `direction`（八向方位）、`horizontal_distance`（水平距离格数）与 `status`（`found` / `searching` / `none_in_range`，不伪造高度 Y）。
- **显式查看（`ItemSettings`）**：`machine_inspect(item="ae2:meteorite_compass")` 返回只读项 `meteorite_direction`、`meteorite_distance`、`compass_status`；`use` 持罗盘可触发一次刷新等待。

---

### 3.4 阶段 2：完整自动合成闭环

#### 3.4.1 样板编码终端

- 挂接在 `ae2:cable_bus` 上对应面部件的 `machine_configure`（键带 `<side>.` 前缀），支持 `CRAFTING`、`PROCESSING`、`SMITHING_TABLE`、`STONECUTTING` 全部 4 种编码模式。
- 配置键设计：
  - `<side>.encode_mode`：`crafting` / `processing` / `smithing` / `stonecutting`（发 `setMode`）；
  - `<side>.substitute` / `<side>.substitute_fluids`：`true` / `false`（发 `setSubstitution` / `setFluidSubstitution`）；
  - `<side>.stonecutting_recipe`：切石配方 ID（发 `setStonecuttingRecipeId`）；
  - `<side>.encode_pattern`：单次完整编码请求（JSON 字符串描述 `mode`、`inputs[]`、`outputs[]`、`substitute`、`substitute_fluids`、`stonecutting_recipe`），动作流程如下：
    1. 核对角色背包或终端空白样板槽有 `ae2:blank_pattern`（没有则 `NEED_ITEM`）；
    2. 打开该面 `ae2:patternterm` 菜单，先切到目标 `EncodingMode`，发 `clear()` 清空旧幽灵格；
    3. 按模式写幽灵槽：发 `InventoryActionPacket(InventoryAction.SET_FILTER, slotIndex, stack)`；`PROCESSING` 模式下支持流体与 >64 数量（经 `GenericStack.wrapInItemStack` 包装，0 号输出格必须为非空主产物）；
    4. **防洗样板纪律**：编码前先检查输出槽（`ENCODED_PATTERN`），若已占着旧编码样板先取出到背包；**并在调用 `encode()` 前核验当前模式配方有效**（如 `CRAFTING` 的 `CRAFTING_RESULT` 非空、`PROCESSING` 输入与主产物均非空、`STONECUTTING` 已选定合法配方 ID、`SMITHING_TABLE` 三槽齐全且成品非空），绝不在无效配方下发 `encode()` 触发 `clearPattern()` 把旧样板洗回空白样板；
    5. 放入空白样板 → 发 `encode()` → 等 `ENCODED_PATTERN` 槽出现已编码样板 → 取出到角色背包 → 关窗；结果写明新编码样板的产出物品。

#### 3.4.2 样板管理终端

- **查看（`machine_inspect`）**：部件本身客户端只同步在线态与过滤选项；通过服务端 `ae2.network` 扩展的 `pattern_containers[]` 读取全网样板容器分组（目标机器名、已用/总槽数、每张样板产出），避免反射读取 `PatternAccessTermScreen.byId`。
- **放取样板（`machine_configure`）**：
  - `<side>.show_providers`：`visible` / `not_full` / `all`（`ConfigButtonPacket(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, false)`）；
  - `<side>.put_pattern`：值为 `<target_group_name>:<output_item_id>`，打开 `ae2:patternaccessterminal` 找到身上匹配产出物的编码样板，按同组容器 `serverId` 列表发 `QuickMovePatternPacket` 快速送入，核对背包样板少 1 后关窗。需要从特定样板供应器精确取出时，优先引导直接对该样板供应器调用 `machine_configure`。

#### 3.4.3 样板供应器补全（方块与部件）

保留既有 `pattern.<output_item>=in|out`，并补齐全部配置键（方块版无前缀，部件版 `ae2:cable_pattern_provider` 带 `<side>.` 前缀）：

| 键 | 取值 | 交互方式 |
| --- | --- | --- |
| `pattern.<item_id>` | `in` / `out` | 打开 `ae2:pattern_provider`，Shift 快速移动对应样板（仅碰 `ENCODED_PATTERN` 槽，绝不碰返回槽） |
| `blocking_mode` | `yes` / `no` | `ConfigButtonPacket(Settings.BLOCKING_MODE, false)`，核对 `getBlockingMode()` |
| `lock_crafting_mode` | `none` / `until_pulse` / `while_high` / `while_low` / `until_result` | `ConfigButtonPacket(Settings.LOCK_CRAFTING_MODE, false)`，核对 `getLockCraftingMode()` |
| `show_in_access_terminal` | `yes` / `no` | `ConfigButtonPacket(Settings.PATTERN_ACCESS_TERMINAL, false)`，核对 `getShowInAccessTerminal()` |
| `priority` | 整数 | `SwitchGuisPacket.openSubMenu(PriorityMenu.TYPE)` → `setPriority(int)` → `returnToParentMenu()` |
| `push_direction`（仅方块版） | `all` / `north` / `south` / `east` / `west` / `up` / `down` | 主手石英扳手普通右键切换方块 `PUSH_DIRECTION` 属性（推送面不再连网） |

`machine_inspect` 另在读到时展示 `crafting_locked_reason` 与 `unlock_stack`（等待解锁的产物）。

#### 3.4.4 分子组装室

- **读数（`machine_inspect`）**：客户端读 `POWERED`；开界面或经 `ae2.device` 补读 `craft_progress`（0..100）、5 个加速卡槽数量与单机样板槽占用情况；若单机样板槽非空，显式附注「已放入单机样板，相邻样板供应器不再向其推送合成作业」。
- **配置（`machine_configure`）**：`pattern=<output_item_id>|empty`（放取单机样板槽 `ENCODED_PATTERN`）、`upgrade.0..4=ae2:speed_card|empty`。
- **单机运行（`machine_run`）**：当分子组装室处于单机模式（已放单机样板）时，支持经 `MenuFeed` 往合成格投料并经 `MenuOutputLook` 从 `MACHINE_OUTPUT` 收产物。

#### 3.4.5 压印器设置补全

- 加固 `Ae2InscriberLayout` 优先经 `Ae2MenuSlots` 按 `SlotSemantics` 识别 4 个工作格与 4 个升级格（无读写端替身时回退兼容原 8 槽逻辑），免疫背包带 `ae2:network_tool` 时的 9 槽偏移。
- `machine_configure` 补齐全部 3 项设置与升级卡：
  - `separate_sides`：`yes` / `no`（`Settings.INSCRIBER_SEPARATE_SIDES`）；
  - `auto_export`：`yes` / `no`（`Settings.AUTO_EXPORT`）；
  - `input_capacity`：`1` / `4` / `64`（`Settings.INSCRIBER_INPUT_CAPACITY`）；
  - `upgrade.0..3`：`ae2:speed_card` / `empty`。

#### 3.4.6 合成 CPU 状态与取消

- **查看（`machine_inspect`）**：对任意合成 CPU 方块（`ae2:crafting_unit` 等）或 ME 网络读取时，通过服务端 `ae2.network` 展示每台 CPU 的 `serial`、`name`、`storage`、`co_processors`、`mode`、`busy`、`cant_store`（网络存储满溢告警）、`current_job`、`progress` 及逐物料 `active` / `pending` 明细。无服务端读数时，可打开 `ae2:craftingcpu` 或从终端切换 `ae2:craftingstatus` 读回 `cpuList` 与 `cantStoreItems` 缓存。
- **操作（`machine_configure`）**：对 CPU 方块支持：
  - `scheduling_mode`：`any` / `player_only` / `machine_only`（打开 `ae2:craftingcpu` 发 `ConfigButtonPacket(Settings.CPU_SELECTION_MODE, false)`）；
  - `job_action`：`cancel`（发 `cancelCrafting()` 取消当前卡住的作业）或 `toggle_pause`（发 `toggleScheduling()` 暂停/恢复）。

#### 3.4.7 合成终端与无线合成终端 3×3 手搓合成格

- 接入 `obtain` 的手搓合成途径（当角色持有已绑定无线合成终端或身处附近在线合成终端旁，且需要手搓多步或直接使用网络库存原料时优先复用）：
  1. 打开 `ae2:craftingterm` 或 `ae2:wirelesscraftingterm`，经 `Ae2MenuSlots` 按 `SlotSemantics.CRAFTING_GRID` 与 `CRAFTING_RESULT` 定位槽号（完全兼容有线版与无线版截然不同的槽位排列及 `ToolboxMenu`）；
  2. 发 `FillCraftingGridFromRecipePacket(recipeId, templates9, false)`（**严格传 `craftMissing = false`**，避免缺料时强行切屏至 `CraftConfirmMenu`）；
  3. 核对 `CRAFTING_RESULT` 槽出现目标成品后，发 `InventoryActionPacket(CRAFT_SHIFT / CRAFT_ITEM, outputSlotIndex, 0)` 取货（绝不走原版 `pickup`，因 `CraftingTermSlot.mayPickup` 恒为 false），以角色背包增量确认产出；
  4. 结束后发 `clearCraftingGrid()` 将合成格余料退回 ME 网络（放不下时发 `clearToPlayerInventory()` 退回背包），再关窗。
- 与现有 `Ae2TerminalLayout` 完全兼容共存：网络存取继续走 `MEInteractionPacket`，手搓合成独立按语义槽走上述包流程。

---

### 3.5 阶段 3：线缆部件按面配置与磁盘/网络运维

#### 3.5.1 线缆部件按面配置键名规范与 `partSettings` 读出规则

1. **键名格式**：线缆格 `ae2:cable_bus` 的 `MachineSettings.settings(BlockPos host)` 对每个已安装部件按面名加前缀暴露配置键：`<side>.<key>`，其中 `<side>` 固定为小写六向 `north` / `south` / `east` / `west` / `up` / `down`。
2. **`partSettings` 读出规则（SPI-2）**：`MachineSettings.partSettings(BlockPos host, Direction side)` 返回该面部件**不带 `<side>.` 前缀**的局部设置列表，`MachineAreaReport` 将其直接写入 `DeviceView(role="part", host=host, side=side).settings`；调用 `machine_configure` 修改该线缆格上的部件时，则使用带 `<side>.` 前缀的键（如 `north.redstone_mode`、`east.priority`），确保同一线缆格 6 面同时装有不同部件时零冲突。
3. **客户端同步与读回核对机制**：
   - 未打开过界面且无服务端 `ae2.device` 读数时，部件仍静态列出全部合法键名，当前值标 `"unknown"`（确保 `MachineConfigureTask.checkKeys()` 校验放行）；
   - 执行 `change()` 打开部件界面并改完设置后、**关闭界面前**，立即从当前菜单的 `@GuiSync` / 槽位把最新值写入会话本地缓存；`MachineConfigureTask` 在动作结束后的下一刻同步调用 `settings()` 即可精确读回刚改好的值并通过核对；
   - 若服务端 `ae2.device` 已协商可用，优先以服务端返回的权威值刷新显示。

#### 3.5.2 全部线缆部件配置键总表

以下部件安装在 `ae2:cable_bus` 某面时，在 `machine_configure` 中加 `<side>.` 前缀；方块形态的 ME 接口（`ae2:interface`）不加前缀。

| 部件 / 方块 | 设置键（局部名） | 取值与动作 |
| --- | --- | --- |
| 输入总线（`ae2:import_bus`） | `redstone_mode`、`fuzzy_mode`、`key_type.item`、`key_type.fluid`、`filter.<0..62>`、`upgrade.<0..4>` | 红石/模糊走 `ConfigButtonPacket`；导入类型走 `SelectKeyTypePacket`；幽灵过滤槽走 `SET_FILTER`（超 18 格自动提示需容量卡）；升级槽移卡 |
| 输出总线（`ae2:export_bus`） | `redstone_mode`、`fuzzy_mode`、`craft_only`、`scheduling_mode`、`filter.<0..62>`、`upgrade.<0..4>` | 配置按钮走 `ConfigButtonPacket`；过滤槽走 `SET_FILTER`；升级槽移卡 |
| 存储总线（`ae2:storage_bus`） | `access`（`read_write`/`read`/`write`）、`storage_filter`、`filter_on_extract`、`fuzzy_mode`、`priority`、`filter.<0..62>`、`action`（`partition`/`clear`）、`upgrade.<0..4>` | 权限/过滤策略走 `ConfigButtonPacket`；优先级切 `PriorityMenu`；分区/清空发 `GuiActionPacket("partition"/"clear")`；`machine_inspect` 另报 `connected_to` |
| ME 接口（`ae2:interface` / `ae2:cable_interface`） | `fuzzy_mode`、`priority`、`stock.<0..8>`（`<item_id> [count]` 或 `empty`）、`upgrade.0` | 先用 `SET_FILTER` 设备料物品，若指定数量（或流体毫桶）则发 `GuiActionPacket("setAmount", slot)` 等换页至 `ae2:set_stock_amount` 再发 `GuiActionPacket("setStockAmount", amount)` 自动返回；支持合成卡自动备料 |
| 成型面板（`ae2:formation_plane`） | `place_mode`（`block`/`drop`）、`fuzzy_mode`、`priority`、`filter.<0..62>`、`upgrade.<0..4>` | `PLACE_BLOCK` / `FUZZY_MODE` 走 `ConfigButtonPacket`；优先级切 `PriorityMenu` |
| 破坏面板（`ae2:annihilation_plane`） | 无配置键（只读） | `machine_inspect` 经服务端 `ae2.device` 报附魔列表（时运/精准采集等） |
| 标准发信器（`ae2:level_emitter`） | `redstone_mode`、`fuzzy_mode`、`craft_via_redstone`、`reporting_value`（非负整数）、`filter.0`、`upgrade.0` | 打开 `ae2:storage_level_emitter` 读首次同步值，设阈值发 `GuiActionPacket("setReportingValue", Long)` |
| 能量发信器（`ae2:energy_level_emitter`） | `redstone_mode`、`reporting_value`（非负整数） | 打开 `ae2:energy_level_emitter`，发 `GuiActionPacket("setReportingValue", Long)` |
| 存储监视器 / 转换监视器（`ae2:storage_monitor`、`ae2:conversion_monitor`） | `locked`（`true`/`false`）、`displayed`（`<item_or_fluid_id>` / `empty`） | 客户端全状态直接同步进 `machine_inspect`（展示物、数量、能否合成、锁定态）；`locked` 走空手潜行右键部件盒，`displayed` 走解锁后持样本右键（容器物品再点一次切流体）；转换监视器存/取复用 `use` 或左键交互 |
| 6 种 P2P 通道（`ae2:*_p2p_tunnel`） | `attunement`（`me`/`redstone`/`item`/`fluid`/`fe`/`light`）、`p2p_role`（`input` / `output`） | `attunement` 备对应调谐标签物品右键替换（不消耗）；`p2p_role` **强制使用主手** `ae2:memory_card`（输入端潜行右键复制并分配频率，输出端普通右键粘贴并自动转成同型输出）；`machine_inspect` 客户端读频率、服务端补 `is_output` |

#### 3.5.3 元件工作台

- 打开 `ae2:cellworkbench`（受 `ToolboxMenu` 影响，按 `Ae2SemanticsLayout` 识别）：
- `machine_configure` 支持：
  - `cell`：`in:<cell_item_id>` / `out`（放取 `STORAGE_CELL` 槽）；
  - `copy_mode`：`clear_on_remove` / `keep_on_remove`（`GuiActionPacket("nextCopyMode")`）；
  - `fuzzy_mode`：`ignore_all` / `percent_99` / `percent_75` / `percent_50` / `percent_25`（发 `GuiActionPacket("setFuzzyMode", FuzzyMode)` 写入元件物品组件，**不走 `ConfigButtonPacket`**）；
  - `action`：`partition`（按元件现存物自动分区）/ `clear`（清空分区）；
  - `filter.<0..62>`：手动 `SET_FILTER` 写分区格；
  - `upgrade.<0..7>`：仅在当前元件实际激活的升级槽范围内放取升级卡。

#### 3.5.4 ME IO 传输站（游戏方块 `ae2:io_port`）

- 内部代码命名 `CellTransfer`（避开禁用词 `Port`），菜单 `ae2:ioport`（受 `ToolboxMenu` 影响）。
- **配置（`machine_configure`）**：
  - `operation_mode`：`empty`（盘→网）/ `fill`（网→盘），`ConfigButtonPacket(Settings.OPERATION_MODE, false)`；
  - `fullness_mode`：`empty` / `half` / `full`，`ConfigButtonPacket(Settings.FULLNESS_MODE, false)`；
  - `redstone_mode`：`ignore` / `high_signal` / `low_signal`（**明确说明游戏事实**：装有红石卡时 `ignore` 在 AE2 19.2.17 中仍按低电平逻辑生效）；
  - `upgrade.0..2`：放取加速卡/红石卡。
- **运行（`machine_run`，经 `FullRun`）**：把待倒盘的存储元件（或物质炮/上色器等实现 `IBasicCellItem` 的带槽工具）放入 `MACHINE_INPUT`（6 格），等待转移完成出现在 `MACHINE_OUTPUT`（6 格）后收回角色背包。

#### 3.5.5 无线访问点补全

- **读数（`machine_inspect`）**：客户端不开窗时 `be.getRange()` 恒返回 16.0 且不传增幅卡数量；读端显式区分「已从服务端/开窗读到精确增幅卡数」与「未同步（不要把 16.0 当真实范围）」。读到 `boosters`（0..64）后按 AE2 公式算出并展示 `range = 16 + boosters^1.5` 与 `power_drain = 8 + boosters^(1 + boosters / 64)`。
- **配置（`machine_configure`）**：`boosters=<0..64>`，打开 `ae2:wireless_access_point` 按差额向 `STORAGE` 槽放入或取出 `ae2:wireless_booster`，关窗前刷新缓存；保留已有无线终端绑定流程（`WirelessBinding`）。

#### 3.5.6 内存卡与网络工具

- **内存卡（`ae2:memory_card`）**：
  - `ItemSettings`（`machine_inspect(item="ae2:memory_card")` / `machine_configure(item="ae2:memory_card")`）：客户端直接从 12 个同步 `AEComponents` 读出 `source`、`custom_name`、`priority`、`p2p_type`、`p2p_frequency`、`color`、`settings_summary`、`upgrades_count`；配置支持 `clear=true`（主手潜行对空右键清空全部导出组件）。
  - 对方块或线缆部件复制/粘贴复用 `use(item="ae2:memory_card", target=..., sneak=true|false)`，跨 P2P 配对亦可直接经线缆部件 `machine_configure` 完成。
- **网络工具（`ae2:network_tool`）**：
  - 9 格升级卡工具箱：接入 `Ae2SemanticsLayout`（`ae2:networktool`）供 `use` 查看，同时实现轻量随身来源 `ItemSource(via=carried)`：当其他任务或配置需要升级卡（如速度卡、容量卡、模糊卡、红石卡、能量卡）且主背包没有时，可直接从随身网络工具箱（客户端读 `DataComponents.CONTAINER`）取出。
  - 拆解：与石英扳手一同接入 `DismantleTool`（潜行右键拆解 AE2 设备，但排除旋转）。
  - 耗电与设备分组：不打开易遇私有字段的 `NetworkStatusScreen`，统一从服务端 `ae2.network`（`NetworkStatus.fromGrid`）透出。

---

### 3.6 阶段 4：量子环、空间存储、特色手持工具与知识补强

#### 3.6.1 量子环与量子链接仓

- 代码命名 `QuantumRing` / `QuantumLink`（避开禁用词 `Bridge`）。
- **读数（`machine_inspect`）**：客户端直接读 `formed`（`isFormed()`）、`corner`（`isCorner()`）、`powered`（`isPowered()`）、`has_singularity`（`hasQES()`）；经服务端 `ae2.device` 或开 `ae2:qnb` 菜单补读 `frequency`（`getQEFrequency()`）与对端是否已跨维连通。
- **接线约束（`exchangePoints` / `machine_build`）**：`ae2:quantum_ring` 仅 4 条边中点暴露 ME 线缆接口（`isCorner() == true` 的四角不暴露，防止 `machine_connect` 把线缆误接到四角）；施工审阅检查 3×3×1 平面结构（8 环 + 中心 1 链接仓）及两端所在区块均须加载的提示。
- **放取奇点（`machine_configure`）**：对中心 `ae2:quantum_link` 支持 `singularity=in|out`，打开 `ae2:qnb` 放取 1 个带 `AEComponents.ENTANGLED_SINGULARITY_ID` 的 `ae2:quantum_entangled_singularity`。

#### 3.6.2 奇点爆炸转化

- 新增独立物品来源 `compat.ae2.advanced.Ae2ExplosionTransformSource`（途径 `via=ae2_explosion_transform`），不复用 `FluidTransforms`。
- 触发与执行流程：
  1. 仅对 `ae2:quantum_entangled_singularity` 报价（产出恒为 2 个同频缠绕奇点）；
  2. 备料：`ae2:singularity` × 1 + 末影珍珠粉或末影珍珠（`#c:dusts/ender_pearl` / `#c:ender_pearls`）× 1 + 引爆组合（**优先选不破坏方块的 `ae2:tiny_tnt`** + 点火或红石脉冲件；若无迷你 TNT 且许可放开 `change_blocks=any` 才允许用原版 `minecraft:tnt`）；
  3. 选点与安全校验：寻找远离记住的区域、额外保护的地标、自家建筑与机器的安全空地，逐格过 `PermissionCheck`；
  4. 执行：放置迷你 TNT → 紧贴其旁丢出奇点与末影珍珠/粉（确保在 ±1 格 2×2×2 扫描盒内）→ 点燃并立即退至爆炸安全距离外 → 等待爆炸完成、核对掉落物变为成对的 `ae2:quantum_entangled_singularity` → 走近捡齐两枚并确认同频。

#### 3.6.3 空间存储

- **空间塔（`ae2:spatial_pylon`，代码名 `SpatialPylons`）**：`machine_inspect` 客户端读 `powered`、`online`、`axis`、`axis_position`；服务端 `ae2.device` 补全合围内腔尺寸（`xSize × ySize × zSize`）、当前塔块数、所需塔块数、效率与切换所需总电量。
- **空间传输站（游戏方块 `ae2:spatial_io_port`，代码名 `SpatialTransfer`）**：
  - `machine_inspect`：开窗或经 `ae2.device` 读区域尺寸、效率、当前/最大/所需电量及槽 0 / 槽 1 中的空间元件状态（含是否已绑定 `AEComponents.SPATIAL_PLOT_INFO` 及其锁定尺寸）。
  - `machine_run`（经 `FullRun`）：
    1. 核对目标空间元件（`ae2:spatial_storage_cell_2` / `16` / `128`）并放入槽 0（`MACHINE_INPUT`），确认槽 1（`MACHINE_OUTPUT`）为空；
    2. 若相邻有可用红石开关（拉杆/按钮），触发一次低→高红石上升沿脉冲（无开关时放好元件并明确提示需外部红石上升沿触发）；
    3. 等待元件从槽 0 移至槽 1 确认空间互换生效，将完成元件取回背包；若因区域无效、尺寸超出元件上限或与已分配尺寸不符、网络电量不足导致游戏未切换，如实回报实测读数，不替玩家作弊或伪造切换。
- **空间锚（`ae2:spatial_anchor`，代码名 `SpatialAnchor`）**：
  - `machine_inspect`：经 `ae2.device` 或开 `ae2:spatialanchor` 读 `power_consumption`（`80 + n*(n+1)/2` AE/t）、`loaded_chunks`、`overlay_mode` 与全网已加载区块统计；
  - `machine_configure`：`overlay_mode=yes|no`（`ConfigButtonPacket(Settings.OVERLAY_MODE, false)`）。

#### 3.6.4 特色手持工具与通用带电物品

- **熵变仪（`ae2:entropy_manipulator`）**：
  - 加热/冷却与点 TNT 复用 `use(item="ae2:entropy_manipulator", target=..., sneak=false|true)`（非潜行加热/烧炼/点 TNT，潜行冷却；支持对着流体使用）；
  - `ItemSettings` 提供只读电量（`energy`、`energy_max`，提示单次耗电 1600 AE，点 TNT 不耗电）。
- **物质炮（`ae2:matter_cannon`）**：
  - 接入 `behavior.survival.defense.spi.RangedWeapon`（`compat.ae2.tool.MatterCannonWeapon`）：
    - `covers` 认 `ae2:matter_cannon`；
    - 弹药读法：直接从物品内部 `StorageCells.getCellInventory(weapon, null)` 读取已装弹药，并经客户端 `RecipeManager.byType(AERecipeTypes.MATTER_CANNON_AMMO)` 查权重，**严格排除 `PaintBallItem`（`penetration == 0` 零伤彩弹）**；
    - 仅在电量 `>= 1600` AE 且内部含至少 1 发有效伤害弹药时返回 `Shot`；弹道给直线瞬发（`gravity = 0`、`drag = 1.0`、`chargeTicks = 0`、`cooldownTicks = 0`）；
    - 实现 SPI-4 `shotFired(before, after, self)`：比较发射前后主手物质炮的 `STORED_ENERGY` 减少或内部弹药总数减少即确认发射成功。
  - `ItemSettings` 只读展示 `energy`、`energy_max`、`ammo`、`ammo_count`、`estimated_damage`（并提示通过 ME 箱子或 ME IO 传输站 `FILL` 装弹）。
- **上色器（`ae2:color_applicator`）**：
  - `ItemSettings`：客户端直读 `energy`、`energy_max`、`active_color`（`AEComponents.SELECTED_COLOR`）与内部各色颜料余量；配置键 `color=<ae_color|transparent>` 换到主手直接发送 `ColorApplicatorSelectColorPacket(@Nullable AEColor)` 切换当前颜色（仅当包内已有该色时游戏才接受，改完读 `getActiveColor` 核对）；
  - 染色操作复用 `use(item="ae2:color_applicator", target=...)`；放染色线缆时支持副手持上色器自动染色。
- **充能法杖（`ae2:charged_staff`）**：近战战斗识别其满电（`> 300` AE）附加 6 点魔法伤害与电量门槛，没电时按普通低伤棍子计并提示补电。
- **通用带电物品**：所有 `IAEItemPowerStorage`（上述工具、10 种便携元件、无线终端、能源元件物品）的电量条已自动进入 `observe(self).gear_bars`；任一工具因低电无法工作时，失败说明固定给出当前/上限电量与「可用充能器 `machine_run` 补电」建议，不增加越权打断主任务的自动充电生存需求。

#### 3.6.5 知识层补强

- **中文主题资料（`Ae2MachineKnowledge` 扩充）**：拆分为清晰的按主题索引条目（日常单机与曲柄、自动合成与样板编码、线缆部件与 P2P 配对、磁盘分区与网络运维、量子环与空间存储、手持工具装填与补电），覆盖全部新增设备、设置键与核心避坑提示。
- **GuideME 手册按需只读检索（`compat.ae2.tool.Ae2GuideKnowledge`）**：实现 `KnowledgeSource`，启动时扫描 AE2 jar 资源 `assets/ae2/ae2guide/**/*.md` 的 YAML frontmatter（`item_ids`、`title`、`categories`）建轻量索引；`entriesAbout(registryId)` 命中对应条目，`read(uri)` 按需读取正文并剥离 `<GameScene>`、`<BlockImage>`、`<RecipeFor>` 等 GuideME JSX 标签，保留文字与 `<ItemLink>` 文本供 LLM 查阅深层机制。

## 4 能力与接口挂接总览

- **不新增顶层 MCP 工具或新能力**，`ParamNames` 无需新增参数名。
- **新增 `obtain` 的 `via` 取值**：`ae2_explosion_transform`（奇点爆炸转化，装了 AE2 才出现）。
- **新增 `observe(scene).mod_things` 种类**：`meteorite_compass`（仅在角色持有或背包携带陨石罗盘时出现）。
- **服务端只读操作**：
  - `ae2.network`（既有，向后兼容扩展 `power_injection`、`power_usage`、`channel_power`、`machines[]`、`cpus[]`、`pattern_containers[]`）；
  - `ae2.device`（新增，按格与可选面返回客户端流不同步的设备/部件权威配置与读数）。
- **`machine_inspect` 的部件展示**：借 SPI-2 `MachineSettings.partSettings(host, side)` 在 `devices[]` 的每个部件条目（`role="part"`）上直接填出 `settings` 映射。
- **`machine_configure` 的线缆部件键**：统一为 `<side>.<key>`（如 `north.redstone_mode`、`east.stock.0`、`up.p2p_role`）。

## 5 约束登记（失败与拒绝归类）

本表仅包含 **F（游戏事实）** 与 **C（玩家常识）** 两类；**不存在任何 P 补丁、G 预判拦截或 S 过严规则**。

| 场景与收场方式 | 类 | 错误码（`Problem.Kind`） | 依据 |
| --- | :---: | --- | --- |
| 木质曲柄在充能器/主机满电（`canTurn() == false`）时立刻停止摇动，不继续空摇 | F | —（正常完成或停摇） | AE2 在满电下连续空摇 `hits > 10` 次会无掉落销毁曲柄 |
| 便携存储元件打开时绝不潜行右键空元件 | F | —（操作规程） | AE2 对空便携元件潜行右键会触发 `disassembleDrive` 直接拆解回组件 |
| 便携存储元件、物质炮、熵变仪、上色器电量不足时如实停下并报当前/上限电量 | F | `NOT_POSSIBLE_HERE` | AE2 电量不足时原生操作无效，电量已同步在物品组件上 |
| 石英切割刀缺金属锭拒绝开窗，且关窗前把输入槽余锭收回背包 | F / C | `NEED_ITEM` | 刻名必须消耗 `#ae2:metal_ingots`；AE2 关窗 `removed()` 会直接把残留锭掉在地上 |
| 样板编码终端在当前模式配方无效时拒绝发送 `encode()`，且先取出输出槽已有编码样板 | F | `INVALID_PARAMETER` | AE2 在配方无效时调 `encode()` 会触发 `clearPattern()` 将输出槽已有编码样板洗成空白样板 |
| 样板编码缺少空白样板时收场 | F | `NEED_ITEM` | 编码必须消耗 `ae2:blank_pattern` |
| 合成终端手搓固定传 `craftMissing = false`，缺料不自动跳确认页 | C | `NEED_ITEM` | 避免手搓中途被强制切页触发意外自动合成下单 |
| 物质炮弹药列表排除 `PaintBallItem` | F | —（弹药过滤） | 彩弹 `penetration == 0` 造成 0 伤害，不是战斗弹药 |
| 陨石罗盘未在角色身上携带时不调用 `CompassManager` | C | —（不产出条目） | 原版仅在玩家持有罗盘渲染时查询，未带不查严守反透视原则 |
| 陨石箱子排除在石英扳手拆解之外，走镐挖 | F | —（拆法路由） | `SkyChestBlockEntity.disassembleWithWrench` 恒返回 `FAIL` |
| 量子环四角不暴露 ME 接线点 | F | —（接线路由） | 量子环仅 4 条边中点可接外部线缆，四角不可连 |
| 奇点爆炸转化受 `Permissions` 与 `Protection` 约束，无安全空地或无迷你 TNT 且未开 `change_blocks=any` 时拒绝引爆 | C | `NEED_APPROVAL` / `NOT_POSSIBLE_HERE` | 防止在基地或受保护区域炸毁方块与设备 |
| 空间传输站因区域无效、尺寸超限/与 `SPATIAL_PLOT_INFO` 冲突、缺电或槽 1 非空导致脉冲后未切换，如实回报现场读数 | F | `REFUSED_BY_GAME` / `NOT_POSSIBLE_HERE` | 交由游戏原生结算，不预判拦截，也绝不伪造成功 |
| 服务端未装 MaiCraft 或策略关 `ae2.device` 时，非同步配置项读作 `unknown` 并注明原因 | F | —（如实标注） | 客户端方块流不携带这些字段，读不到就如实说明，不反射也不猜 |
| P2P 配对强制使用主手内存卡 | F | `NEED_ITEM` | AE2 源码对 `hand == OFF_HAND` 直接返回 false |
| `ae2:io_port` 装红石卡时把 `redstone_mode` 设为 `ignore` 给出实效提示 | F | —（结果附注） | AE2 19.2.17 中装红石卡后 `IGNORE` 实际按低电平激活，提示 LLM 避免误判 |

**明确不拦的事**：充能器/压印器/组装室/空间传输站/量子环没电、没分到频道、聚合器未插足够大存储组件时，不以「预测会失败」提前拦截配置或投料尝试；照常按原生操作执行，并将实际结果与读数如实回报。

## 6 行为变化清单

| # | 场景 | 之前 | 之后 |
| --- | --- | --- | --- |
| 1 | 携带 `ae2:network_tool` 打开压印器等菜单 | `Ae2InscriberLayout` 硬编码 8 槽直接失效 | `Ae2SemanticsLayout` 按 `SlotSemantics` 识别，全部 AE2 菜单免疫 9 槽动态工具箱 |
| 2 | 精妙背包与 AE2 便携存储元件同装 | `InventorySpace` 只取第一个 `CarriedBackpack`，便携元件被屏蔽 | `CombinedCarriedBackpacks` 合并全部随身背包，便携元件正常用于腾背包、取货与自身观察 |
| 3 | 充能器、振动室、物质聚合器、ME 箱子、储罐、催生器、切割刀、罗盘 | 仅认出部分方块名，无法运行或配置 | 全部支持读数、运行（含摇曲柄防毁、燃料发电、刻名不掉锭、罗盘反透视感知）与配置 |
| 4 | 自动合成闭环 | 只能从终端下单已有样板；样板编码不做；CPU 不可控；合成终端不手搓 | 补全样板编码 4 模式、样板管理终端、供应器全设置、组装室、压印器全设置、CPU 监控取消、合成终端 3×3 手搓 |
| 5 | 线缆部件与磁盘网络运维 | `machine_inspect` 部件 `settings` 恒为 null，无法配置总线/接口/发信器/P2P；无线访问点读数失真 | 经 `partSettings` 与 `<side>.<key>` 覆盖全线缆部件配置；补全元件工作台、IO 传输站、无线访问点增幅卡与精确公式、内存卡与网络工具 |
| 6 | 量子环、空间存储与特色工具 | 空间存储不做；量子环只认方块；无爆炸转化；无物质炮/上色器/熵变仪适配 | 完整支持量子环与奇点爆炸转化、空间塔/空间传输站/空间锚、物质炮远程战斗、上色器/熵变仪/充能法杖及 GuideME 按需检索 |

## 7 游戏事实归属与代码落点

| 包 / 位置 | 职责 |
| --- | --- |
| `behavior.inventory`、`ability.machine.spi`、`behavior.survival.defense.spi` | 4 处公共 SPI 极小补丁（`CombinedCarriedBackpacks`、`MachineSettings.partSettings`、`ItemSettings.holds(..., Player, ...)`、`RangedWeapon.shotFired`） |
| `compat.ae2`（包根） | 共享入口 `Ae2Compat`、设备目录 `Ae2MachineCatalog`、主类型路由 `Ae2MachineType`、内部委托 `Ae2DeviceOps`、语义布局 `Ae2SemanticsLayout` / `Ae2MenuSlots` 及既有终端/网络/压印器基础 |
| `compat.ae2.machine` | 充能器含曲柄 `FullRun`、振动室 `FuelUse`、物质聚合器、ME 箱子、陨石储罐、水晶催生器、样板编码终端、样板管理终端、样板供应器全设置、分子组装室、压印器设置补全、合成 CPU 控制、合成终端 3×3 手搓 |
| `compat.ae2.part` | 全线缆部件按面配置（输入/输出/存储总线、ME 接口、成型/破坏面板、标准/能量发信器、存储/转换监视器、6 种 P2P）、元件工作台、ME IO 传输站（`CellTransfer`）、无线访问点增幅卡与公式 |
| `compat.ae2.tool` | 石英扳手（`DismantleTool` / `PlacementRules`）、10 种便携存储元件（`PortableCellBackpack` / `PortableCellSource`）、石英切割刀、上色器、内存卡、网络工具箱、物质炮（`MatterCannonWeapon`）、熵变仪、充能法杖、陨石罗盘（`MeteoriteCompassSight`）、GuideME 知识源（`Ae2GuideKnowledge`） |
| `compat.ae2.advanced` | 量子环与量子链接仓（`QuantumRing` / `QuantumLink`）、奇点爆炸转化来源（`Ae2ExplosionTransformSource`）、空间存储（`SpatialPylons` / `SpatialTransfer` / `SpatialAnchor`） |
| `server.compat.ae2` | 扩展 `ae2.network`（耗电分组、CPU 在途明细、样板容器总览），新增 `ae2.device`（`Ae2DeviceServerReads`），全部经 `AskedCell.nearPlayer` 限视距已加载格 |
| `neoforge.compat.ae2` | 直接调用 `appeng.*` 的读写端，按同样四路拆分（`AppliedEnergisticsMachines`、`AppliedEnergisticsParts`、`AppliedEnergisticsTools`、`AppliedEnergisticsAdvanced`、`AppliedEnergisticsDeviceServerReads`），只翻译不判断，禁用反射 |

## 8 不做的事

- **禁止反射访问 AE2 私有字段**：`PatternAccessTermScreen`、`CraftingCPUScreen`、`NetworkStatusScreen` 的私有状态一律改走服务端 `IGrid` / `NetworkStatus.fromGrid` 只读操作。
- **服务端绝不替角色动手，也不跨视距透视**：所有服务端操作只读、且只回答角色所在维度 128 格内已加载方块。
- **不硬编码 AE2 菜单槽位下标**：彻底通过 `AEBaseMenu.getSlots(SlotSemantic)` 定位。
- **不混入附属模组化学品逻辑**：`Applied-Mekanistics`（`MekanismKey`）单列后续评估，`appliedsticks` 不做单独适配。
- **不新增自动打断主任务的低电自动充电生存需求**：带电工具低电通过 `gear_bars` 与明确报错建议交由 LLM 决定何时去充能器补电。

## 9 验收

### 9.1 离线单元测试清单（全用替身，不用 `Unsafe` 与反射）

1. **前置公共 SPI 与基础（Stage 0）**：
   - `CombinedCarriedBackpacksTest`：精妙背包 + AE2 便携元件共存时 `freeSlots()` 求和、`store()` 第一只拒收自动回落第二只、`carried()` 合并两家摘要；
   - `MachineAreaReportPartSettingsTest`：`MachineSettings.partSettings` 返回值正确填入 `DeviceView(role="part").settings`，不影响方块设备设置；
   - `ItemSettingsTaskPlayerHoldsTest`：调用 `holds(ItemStack, Player, key, wanted)` 重载按背包产出物判定完成；
   - `ModShotFiredCallbackTest`：背包弹药不变且无冷却时，`RangedWeapon.shotFired` 返回 true 可确认发射，返回 false 保持等待；
   - `Ae2SemanticsLayoutTest`：覆盖携带/不带 `ae2:network_tool`（多出 9 个 `TOOLBOX` 槽）的 `UpgradeableMenu` 与 `MEStorageMenu` 布局识别、幽灵槽隔离、无 36 格玩家槽菜单返回 `Unsupported`、全表菜单 ID 无冲突。
2. **工作方块与自动合成（Worker A，`compat.ae2.machine`）**：
   - 充能器 `FullRun`：转化配方与带电工具补电、正面掉落拾取、无外电按 `rotation < 3` 摇曲柄、满电立即停摇绝不空摇销毁；
   - 振动室 `FuelUse` 投燃料与空桶占用提示；物质聚合器模式切换、蓄能阈值（256 / 256,000）与元件容量提示；
   - ME 箱子按相对顶面与其余 5 面分流；陨石储罐 16,000 mB 客户端读数；水晶催生器仅 `FRONT`/`BACK` 连网接口；
   - 样板编码终端 4 种模式编码、流体与 >64 数量 `GenericStack` 包装、无效配方拦截防 `clearPattern()` 洗样板、输出槽先腾空；
   - 样板供应器 5 项设置 + 推送方向；分子组装室单机样板警告与 5 速卡读数；压印器 3 项设置；合成 CPU 取消/暂停/调度模式；
   - 合成终端与无线合成终端按语义定位 3×3 格、`craftMissing=false`、`CRAFT_*` 取产物与完工清格。
3. **线缆部件与磁盘运维（Worker B，`compat.ae2.part`）**：
   - 线缆格 6 面多部件 `<side>.<key>` 键名路由、`partSettings` 去前缀读出、未读项 `unknown` 与改后本地缓存立即读回命中；
   - 输入/输出总线（含 `SelectKeyTypePacket` 与容量卡解锁提示）、存储总线（权限/分区/清空/优先级）、ME 接口（备料数量换页至 `ae2:set_stock_amount`）、成型面板、破坏面板附魔读数、标准/能量发信器阈值、监视器锁定与展示、6 种 P2P 调谐与主手内存卡配对（副手报错）；
   - 元件工作台 `setFuzzyMode` 走动作而非 `ConfigButtonPacket` 与动态升级槽数；
   - ME IO 传输站 `operation_mode`/`fullness_mode`、红石卡 `ignore` 提示与倒盘 `FullRun`；
   - 无线访问点精确范围与能耗公式（0/1/16/64 张增幅卡）及未同步标注。
4. **便携元件、手持工具、罗盘与知识（Worker C，`compat.ae2.tool`）**：
   - 石英扳手 `DismantleTool`（排除陨石箱、线缆分拆部件/中心线缆）与 `PlacementRules.turnsTo`（排除网络工具）；
   - 便携存储元件客户端直读 `STORAGE_CELL_INV` 折算 `freeSlots()`、普通右键打开绝不潜行、低电报 0 与清晰报错；
   - 石英切割刀刻名流程、缺锭 `NEED_ITEM`、关窗前清空槽 0 防掉锭、`holds(..., Player, ...)` 核验背包产出；
   - 内存卡 12 组件只读解析与清空；网络工具 9 格升级卡来源；上色器直切颜色与核对；熵变仪/充能法杖低电门槛；
   - 物质炮客户端配方查伤害、排除 0 伤彩弹、`gravity=0` 直线瞄准与 `shotFired` 确认；
   - 陨石罗盘未携带时零调用反透视测试、携带时方位与距离换算；GuideME JSX 标签剥离与 `item_ids` 索引单测。
5. **高级机制与服务端扩展（Worker D，`compat.ae2.advanced` + `server.compat.ae2`）**：
   - 量子环 3×3 平面校验、四角不暴露接线点、链接仓奇点放取与跨维状态读数；
   - 奇点爆炸转化来源：优先选迷你 TNT、权限/保护校验、丢料范围与安全撤离、成对同频确认；
   - 空间塔需求块数/效率/能耗公式、空间传输站上升沿切换与 `SPATIAL_PLOT_INFO` 尺寸冲突回报、空间锚能耗 `80+n*(n+1)/2` 与覆盖层开关；
   - `ae2.network` 扩展与 `ae2.device` 的 `AskedCell` 128 格边界、未加载/跨维拒绝及只读 JSON 序列化测试。
6. **架构与命名检查**：全套 `architecture` 架构测试（含 `LayerRulesTest`、`SourceRulesTest`、`MemberNamesTest`、`CodingRulesTest`）与 `:fabric:compileJava`、`:neoforge:compileJava` 全部通过。

### 9.2 实机验收清单

1. **工具箱动态插槽免疫**：背包放一个 `ae2:network_tool`，先后打开压印器、振动室、分子组装室、输入总线与合成终端执行投料/配置/取货，全部精准命中对应语义槽，不发生错槽。
2. **充能器与曲柄**：无外电充能器旁放木质曲柄，对赛特斯石英与低电便携元件各执行一次 `machine_run`；验证摇曲柄充完自动停摇、满电不炸曲柄、成品从正面地面捡回背包。
3. **振动室与物质聚合器**：振动室 `machine_run` 投煤炭发电读数正常；聚合器放 1k 元件切 `matter_balls` 投杂物产出物质球，换 64k 元件切 `singularity` 读数显示需求 256,000。
4. **ME 箱子、陨石储罐与切割刀**：ME 箱子分别从顶面取物、从侧面换盘；陨石储罐装水读 16,000 mB；切割刀 `machine_configure(item=..., settings={"engrave_name": "test"})` 背包产出命名模板且地面无掉落残锭。
5. **便携元件与精妙背包双共存**：身上同时背精妙背包与 4k 便携存储元件，填满主背包触发腾地方，验证两只随身容器均在 `observe(self).carried_backpacks` 显示且精妙背包满后继续无缝存入便携元件；空便携元件打开不误拆解。
6. **陨石罗盘反透视**：背包不带罗盘时 `observe(scene).mod_things` 无 `meteorite_compass`；放入罗盘后报出水平方位与距离；丢弃后立即消失。
7. **完整自动合成闭环**：在样板编码终端先后编出合成样板与处理样板（含流体或 >64 数量），故意给无效配方验证不洗掉已有样板；将样板装入样板供应器并配阻挡/优先级，在终端下单后于 CPU 查看在途明细并成功取消一次作业；用合成终端 3×3 合成格手搓工作台物品并自动清格。
8. **线缆部件多面配置与 P2P**：同一根线缆东面装输出总线、西面装存储总线、北面装 ME 接口（配 16 个备料）、南面装标准发信器（设阈值 64），验证 `machine_inspect` 各部件 `settings` 清晰分面展示且 `machine_configure` 按 `<side>.<key>` 生效；用主手内存卡配对一对 ME P2P 通道并读出出入端与频率。
9. **元件工作台、IO 传输站与无线访问点**：在元件工作台给磁盘分区并改模糊模式；用 ME IO 传输站将旧盘数据倒进网络并收回空盘；给无线访问点插入 16 张增幅卡，`machine_inspect` 精确报出 80 格范围与对应耗电。
10. **量子环、爆炸转化与空间存储**：用 `obtain(item="ae2:quantum_entangled_singularity", via="ae2_explosion_transform")` 迷你 TNT 安全炸出成对奇点，装入两座 3×3 量子环验证跨区连网；建空间塔围房，经空间传输站脉冲将房间收入空间元件并再次放回；开启空间锚核对能耗与加载区块数。
11. **特色手持工具与知识查询**：用熵变仪加热圆石成石、冷却水成冰；用上色器切色染线缆；用装填铁粒的物质炮在 `fight` 中命中目标并确认发射、确认装彩弹时不选为杀伤弹药；用 `lookup` 查到 AE2 中文专题与 GuideME 剥离标签后的条目正文。

## 10 需登记 09 的新名字

| 中文 | 代码 / 标识 | 意思 |
| --- | --- | --- |
| AE2 语义界面证明 | `compat.ae2.Ae2SemanticsLayout`（接缝 `Ae2MenuSlots`） | 按 `SlotSemantics` 识别 AE2 菜单槽位并免疫网络工具箱动态 9 槽的通用布局证明 |
| AE2 设备操作委托 | `compat.ae2.Ae2DeviceOps` | `Ae2MachineType` 按设备 ID 分派读数、设置、运行与部件操作的内部接口 |
| 部件按面设置 | `ability.machine.spi.MachineSettings.partSettings`；键 `<side>.<key>` | 宿主方块某面装着的部件的配置项；查看时按面去前缀挂在部件上，修改时带面名前缀 |
| ME IO 传输站 | `compat.ae2.part.CellTransfer`（游戏方块 `ae2:io_port`） | 在存储元件与 ME 网络之间批量倒盘的设备；代码名避开 `Port` |
| 空间传输站 | `compat.ae2.advanced.SpatialTransfer`（游戏方块 `ae2:spatial_io_port`） | 触发空间塔区域与空间元件内容互换的设备；代码名避开 `Port` |
| 空间塔与空间锚 | `compat.ae2.advanced.SpatialPylons`、`SpatialAnchor` | 合围空间存储区域的塔列与维持区块加载的锚块 |
| 量子环与量子链接仓 | `compat.ae2.advanced.QuantumRing`、`QuantumLink` | 3×3 平面量子环与中心奇点链接仓；代码名避开 `Bridge` |
| 爆炸转化来源 | `compat.ae2.advanced.Ae2ExplosionTransformSource`（途径 `ae2_explosion_transform`） | 丢出奇点与末影珍珠/粉并安全引爆生成量子缠绕奇点的物品来源 |
| 合并随身背包 | `behavior.inventory.CombinedCarriedBackpacks` | 合并全部已登记 `CarriedBackpack` 的包装，使精妙背包与 AE2 便携存储元件共存 |
| 便携存储元件随身适配 | `compat.ae2.tool.PortableCellBackpack`、`PortableCellSource` | AE2 10 种便携元件接入随身背包与随身物品来源的实现 |
| 按角色核对物品设置 | `ability.machine.spi.ItemSettings.holds(ItemStack, Player, String, String)` | 允许物品设置改完后检查角色背包产物的核对重载（如石英切割刀刻名） |
| 远程武器发射确认 | `behavior.survival.defense.spi.RangedWeapon.shotFired` | 弹药存于武器内部且无冷却的远程武器按主手前后状态确认已开火的回调 |
| AE2 服务端设备读数 | 服务端只读操作 `ae2.device`（`server.compat.ae2.Ae2DeviceServerReads`） | 视距 128 格已加载范围内只读回答客户端不同步的 AE2 设备与部件状态 |
| 陨石罗盘观察 | `compat.ae2.tool.MeteoriteCompassSight`（`mod_things` 种类 `meteorite_compass`） | 仅在角色持有或携带陨石罗盘时向场景感知报出最近陨石的水平方位与距离 |
| AE2 指南资料 | `compat.ae2.tool.Ae2GuideKnowledge` | 按物品 ID 索引 AE2 内置 GuideME 手册并在读取时剥离界面标签的知识来源 |
