---
name: ae2-getting-started
description: 应用能源2起步与日常单机工具：充能器与木质曲柄防毁、压印器与石英切割刀刻名、振动室与物质聚合器、ME 箱子双面操作、陨石罗盘与陨石箱/储罐、水晶催生器、石英扳手拆解转正、便携存储元件与带电工具补电；第一次接触 AE2 或准备搭建第一套 ME 存储前读
metadata: {"requires-mods": "ae2"}
---

# 应用能源2（AE2）起步与日常工具

AE2 起步围绕四件事展开：**找陨石拿压印模板 → 充能赛特斯石英并泡水做福鲁伊克斯水晶 → 压印处理器 → 搭第一套带电存储（ME 箱子或 ME 驱动器）**。动手前先用 `lookup(topic=recipe, id=物品 ID)` 核对整合包配方，再查总览 `lookup(topic=knowledge, id=maicraft://knowledge/machine/ae2/index)`；单机与工具细节分别在 `maicraft://knowledge/machine/ae2/daily-machines` 与 `maicraft://knowledge/machine/ae2/tools-and-power`，查任意 AE2 物品时 `lookup(topic=knowledge, id=物品 ID)` 还会附带内置手册说明。

## 起步路线

1. **找陨石拿四块基础模板与赛特斯母岩**：身上带着陨石罗盘（`ae2:meteorite_compass`），看 `observe(what=scene)` 的 `mod_things` 沿方位前进，挖开陨石壳拿陨石箱子里的压印模板（逻辑、运算、工程、硅）与赛特斯石英。
2. **做充能赛特斯石英与福鲁伊克斯水晶**：放充能器（`ae2:charger`）并给电（没电网时贴木质曲柄），跑 `machine_run` 做出 `ae2:charged_certus_quartz_crystal`；做福鲁伊克斯水晶这类泡水转化配方，直接走 `obtain(item=ae2:fluix_crystal, via=ae2_transform)`，角色会自动找水或倒水扔料收回成品。
3. **压印处理器**：造压印器（`ae2:inscriber`）并用 `machine_run` 压出电路板与处理器；若还没搭起完整网络，可先造振动室或水晶共振发生器供电，再放一台 ME 箱子插上 1k/4k 存储元件过渡。

## 充能器与木质曲柄

- **接线与贴附面**：充能器（`ae2:charger`）除**正面（FRONT）外的其余 5 面**都可以接 ME 线缆、受外部电或安装木质曲柄（`ae2:crank`）。
- **两类用途都走 `machine_run`**：
  - 转化配方：例如 `machine_run(target=充能器, item=ae2:charged_certus_quartz_crystal, count=数量)`。
  - 带电工具补电：把未满电的便携元件、物质炮、熵变仪、上色器、充能法杖交给 `machine_run(target=充能器, item=工具 ID, count=1)`，角色会把身上那件低电工具放进去充满再收回，不另造新件。
- **正面掉落**：空手右键弹出成品时，物品掉在充能器**正面相邻格地上**，角色会自动走近捡起；摆机器时别把正面堵死。
- **木质曲柄防毁纪律**：曲柄每次转动 18 刻注入 160 AE。主机满电后继续空摇超过 10 次会**无掉落销毁曲柄**，因此充完或主机满电必须立即停摇；能接稳定电源时尽早换上电网或振动室。

## 压印器与石英切割刀

- **压印器（`ae2:inscriber`）**：
  - `machine_inspect` 在外面就能看到四个工作格（顶模板、底模板、中间输入、输出）；`machine_run` 会自动按配方放模板和主料、避开占槽冲突，并在收货时把本轮放进去的模板带回背包。
  - `machine_configure` 支持 3 项设置与 4 张加速卡：`separate_sides`（`yes`/`no`，开启后顶/底/四周分槽便于自动化）、`auto_export`（`yes`/`no`）、`input_capacity`（`1`/`4`/`64`，批量压印时调大很省事）、`upgrade.0..3`。
- **石英切割刀刻名（`ae2:certus_quartz_cutting_knife` / `ae2:nether_quartz_cutting_knife`）**：
  - 制作名称压印模板（`ae2:name_press`）时，确认身上至少有 1 块铁锭等 `#ae2:metal_ingots` 金属锭，调用 `machine_configure(item=ae2:certus_quartz_cutting_knife, settings={"engrave_name": "名称"})`。
  - 刻名结束后系统会自动把输入槽剩下的金属锭收进背包再关窗，避免残锭掉地。

## 振动室与物质聚合器

- **振动室（`ae2:vibration_chamber`）**：
  - 临时烧燃料直注 ME 电网（无内部电池缓冲），用 `machine_run(target=振动室, item=minecraft:coal, count=数量)` 投料；`machine_configure` 可在 `upgrade.0..2` 插能量卡或加速卡。
  - **别烧岩浆桶**：岩浆桶烧完留下的空桶会卡在燃料格里，优先烧煤炭、木炭或木板。
- **物质聚合器（`ae2:condenser`）**：
  - 用 `machine_configure` 配 `mode`（`trash` / `matter_balls` / `singularity`）与 `storage_component`（`in:<组件 ID>` 或 `out`）。
  - 蓄能前提：不插存储组件无法蓄能；做物质球需 256 能量（至少 1k 组件），做奇点需 **256,000** 能量（至少 64k 组件）。用 `machine_run` 投低值废料并在输出槽收产物。

## ME 箱子、陨石箱、陨石储罐与水晶催生器

- **ME 箱子（`ae2:chest`）双面交互**：
  - 点击**相对顶面**（旋转后朝上的那一面，不一定是世界 `up`）打开盘内终端界面存取物品；点击**其余 5 面**打开元件插槽界面换盘（`machine_configure` 写 `cell=in:<元件 ID>` 或 `out`）。摆在地上时给顶面和至少一个侧面留空。
- **陨石箱子（`ae2:sky_stone_chest` / `ae2:smooth_sky_stone_chest`）**：
  - 36 格普通存储容器，支持 `use`、`obtain`、`deposit`；**不能用石英扳手潜行拆解**，拆走请直接用镐挖。
- **陨石储罐（`ae2:sky_stone_tank`）**：
  - 容量 16 桶（16,000 mB），`machine_inspect` 客户端直接看到流体种类与毫桶数，拿水桶/空桶 `use` 即可双向存取。
- **水晶催生器（`ae2:growth_accelerator`）**：
  - **只有 `FRONT` 和 `BACK` 两面能接 ME 线缆、受电或贴木质曲柄**，但六个相邻面都会对贴着的母岩与可催生方块生效。布线时先用 `machine_inspect` 看清连接口方向。

## 陨石罗盘

- **携带才报、绝不透视**：只有角色主手、副手或背包里真正带着 `ae2:meteorite_compass` 时，`observe(what=scene)` 的 `mod_things` 才会出现 `meteorite_compass` 条目，也可以用 `machine_inspect(item=ae2:meteorite_compass)` 单独查看。
- **三种肉眼可见指针状态**：
  - `pointing`：离最近陨石超过 6 格，给出八向水平方位（如东北、正西），**不透出精确距离与高度 Y**；
  - `nearby_spinning`：已在 6 格以内慢转，说明陨石就在附近或脚下，直接原地探测开挖；
  - `searching_spinning`：当前范围内暂无目标或正在查询。

## 石英扳手、网络工具与拆解转正

- **石英扳手（`ae2:certus_quartz_wrench` / `ae2:nether_quartz_wrench`）**：
  - 潜行右键：整块拆解 AE2 设备与线缆上的单个面部件（掉落直接进背包，排除陨石箱子）；
  - 普通右键：旋转 AE2 方块朝向，或切换方块版样板供应器的单面推送方向。
- **网络工具（`ae2:network_tool`）**：
  - 潜行右键同样可以拆解 AE2 设备，但**只能拆不能旋转方块**；
  - 对空右键打开 9 格升级卡工具箱，`obtain` 可直接从随身工具箱取升级卡。身上带着网络工具打开其他 AE2 界面时会自动多出 9 个工具箱槽，本模组按槽位语义操作，不会错槽。

## 便携存储元件与带电工具补电

- **10 种便携存储元件**：涵盖物品 1k/4k/16k/64k/256k 与流体 1k/4k/16k/64k/256k。
  - 物品便携元件可与精妙背包共存参与腾背包，也出现在 `observe(what=self)` 的 `carried_backpacks` 中；物品与流体便携元件都支持 `obtain(..., via=carried)` 随身取货（流体装桶需带空桶）。
  - **绝不对空便携元件潜行右键**：潜行右键空盘会直接把元件拆解回外壳与存储组件，打开一律用普通右键。
- **通用补电**：所有带电物品（10 种便携元件、无线终端、物质炮、熵变仪、上色器、充能法杖）的电量条都会出现在 `observe(what=self)` 的 `gear_bars`。电量见底时不会自动打断主目标，直接把工具交给充能器执行 `machine_run` 补满即可。

## 什么时候停下

附近一直找不到陨石或关键压印模板、没有可用电源且缺乏石英/木质曲柄材料，或改动他人已有设备前未获许可时，把当前现场读数与缺少的材料写进结果，停下来交由调用方决定。
