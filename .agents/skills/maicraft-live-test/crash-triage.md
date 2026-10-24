// SPDX-License-Identifier: GPL-3.0-only

# 客户端崩溃与启动失败排查（实机测试的参考手册）

配套 `SKILL.md` 使用：SKILL.md 管「只认重启后的时间戳」与「无新崩溃才读日志」两条纪律，本文管「读什么、怎么读、读完怎么办」。全部路径相对客户端的 run 目录（本项目是 `neoforge/run/`）。

## 文件在哪

| 文件 | 内容 | 用途 |
| --- | --- | --- |
| `crash-reports/crash-<时间>-fml.txt` | 模组装载期崩溃，成对出现 | 装载失败的第一读物 |
| `crash-reports/crash-<时间>-client.txt` | 运行期/初始化期崩溃 | 与 fml 版对照看次生与首因 |
| `logs/latest.log` | 最近一次运行的完整日志（实时写入） | 启动进度、装载到哪一步、报错上下文 |
| `logs/debug.log` | 更详细的调试日志 | latest.log 不够时再挖 |
| `logs/<日期>-N.log.gz`、`debug-N.log.gz` | 按次滚动的归档 | 查**过去某次**会话的行为 |

多实例并行时 logs 目录共享，`latest.log` 属最后写入者——查特定实例优先用它的 IDEA 运行控制台输出，或让验证会话在回报里自带证据，别依赖共享文件归属。

## 阅读顺序

1. **时间窗**：只看 mtime/文件名时间晚于本次重启的文件。历史崩溃是最大干扰源（已两次把验证会话带偏）。
2. **装载期崩溃**：`crash-*-fml.txt`。`-- Mod loading issue --` 一节逐条列出失败项（文件、Failure message、异常）——装载类失败**一次报全**，逐条处置，修一个不等于只剩一个。
3. **运行期崩溃**：`crash-*-client.txt`。看 `Description` 行与最顶栈帧，按帧里的 mod id 与类名归因；注意次生现象——前一个 mod 的装载失败可能把后面的初始化带崩，首因在 fml 报告里。
4. **没崩溃但起不来/行为异常**：`latest.log` 末尾往回翻，找 `ERROR`/`WARN` 与最后一条正常日志之间的空窗。

## 常见类别与处置（真实案例沉淀）

| 报错特征 | 定性 | 处置 |
| --- | --- | --- |
| `… is a Fabric mod and cannot be loaded` | Fabric 侧 mod 混入 NeoForge 客户端 | 移出（放备份目录）；若整合包靠 Fabric 桥跑它，见下行 |
| `[Connector] Fabric mod discovery failed` / `Could not determine clean minecraft artifact path` | Fabric 桥（Sinytra Connector 一类）在**开发运行环境**初始化失败——要找生产形态的 Minecraft 工件，dev runClient 没有 | dev 客户端无解：把桥与靠桥的 Fabric mod 全部移出，别试图修桥 |
| 同一 mod id 出现两次（`Duplicate mod` 类报错） | 旧版本发布 jar 与开发构建混装 | 移出旧 jar（例：`maicraft-neoforge-0.1.7.jar` 撞开发构建） |
| `Rendering overlay: … Config not found` 一类初始化期报错 | 多半是装载失败的次生现象 | 先解决 fml 报告里的装载失败，重启再看 |
| 起得来但行为诡异（反复重试、读数对不上） | 不是崩溃，是行为缺陷 | 走 issue 流程取证，别在日志里找答案 |

## 处置纪律

- 移出的问题 jar 进 `mods-backup-<日期>/` 一类备份目录，**不删**——整合包文件是用户的。
- 每挪一批就重启重验一次，一次只改一个变量，才能归因。
- 定性与处置记进派单方账本；给上游 mod 作者报问题前先确认在**生产启动器**里可复现（dev 环境特有限制不算上游的锅）。

## 案例对照：2026-10-23 整包全量 mods 启动失败

现象：把整合包 115 个 mod 全量放进 mods 后 runClient 装载崩溃。四层原因并存：

1. 三个纯 Fabric mod 被拒（NeoForge 不认）；
2. Fabric 桥在 dev 环境初始化失败（上表第 2 行，环境限制非配置错）；
3. 未被当轮报出的隐藏雷：仓库旧版本的发布 jar 与开发构建同 modid（复制整合包时混入）；
4. 渲染期崩溃是装载失败的次生。

处置：按上表移出 7 个 jar（ Fabric 三个、桥、桥前置、旧版本 jar、桥伴生包），其余保留，一次重启成功。教训：**整包往 dev 客户端搬之前，先按「Fabric 的、桥、同 id 旧版」三类预筛**。
