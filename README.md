# ParCool × TACZ 趴下修复（1.20.1 Forge）

[![CI](https://github.com/456123739/parcool-tacz-crawlfix/actions/workflows/ci.yml/badge.svg)](https://github.com/456123739/parcool-tacz-crawlfix/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/456123739/parcool-tacz-crawlfix)](https://github.com/456123739/parcool-tacz-crawlfix/releases)

**下载**：见 [Releases](https://github.com/456123739/parcool-tacz-crawlfix/releases)（`crawlfix-1.0.0-forge.jar`）。

一个小而专一的客户端修复模组：当 **ParCool!（爬行 / 滑铲）** 与 **Timeless & Classics Guns: Zero（趴下）**
同时安装时，把玩家“趴下”这件事收敛成**唯一的一份状态**，并修掉 ParCool 的姿态锁存缺陷，彻底解决：

* 快速点一下趴下键后，**松手也站不起来**；
* 在**滑铲即将变成趴下**的瞬间松手，人物**卡在趴下**；
* 在站立与趴下之间**反复抽搐**；
* 空中松手、切枪、落水后**仍然趴着**。

> 根因分析（含代码证据）：[`docs/ANALYSIS.md`](docs/ANALYSIS.md)
> 修复设计：[`docs/DESIGN.md`](docs/DESIGN.md)
> 验证方式与结果：[`docs/TESTPLAN.md`](docs/TESTPLAN.md)

---

## 1. 根因（四个叠加缺陷）

1. **ParCool 的姿态锁存（主因）**
   `Crawl#canContinue` 在 `player.isVisuallyCrawling()`（姿势为 `SWIMMING` 且不在水里）时**完全跳过**
   “趴下键是否还按着”的检查。ParCool 自己每 tick 会把姿势写成 `SWIMMING`；单独用它时，原版
   `updatePlayerPose()` 会在动作结算前把姿势算回 `STANDING`，缺陷被掩盖。TACZ 用
   `setForcedPose(SWIMMING)` 把姿势钉死，于是**按一下 C 就永久趴下**。
2. **两套趴下状态 + 两种按键语义**：ParCool 按住、TACZ 切换，默认都绑 `C`；同一次点击驱动两套状态，
   第二次点击必然错相。
3. **TACZ 取消趴下的路径会丢输入**：10 tick 冷却、`onGround` 门槛、以及切枪/离地/游泳等
   “只清本地不发包”的分支，服务端于是继续保留趴下强制姿势。
4. **两个姿势写入者**：ParCool 在 `PlayerTickEvent.END` 直接 `setPose`，TACZ 用优先级更高的
   `forcedPose`；两者不一致时每 tick 姿势被改两次，碰撞箱/眼高跟着跳 —— 这就是“抽搐”。

## 2. 修复内容

| # | 修复 | 实现 |
| --- | --- | --- |
| ① | 去掉 ParCool 的姿态锁存：无条件执行趴下键检查（按住模式看按键、切换模式看 `toggleStatus`） | `ParCoolCrawlMixin` |
| ② | TACZ 趴下状态（本地 + 服务端）与 ParCool `Crawl \|\| Slide` 强制对齐；写入不受 10 tick 冷却与 `onGround` 限制；跟踪“服务端已知状态”并补发 | `LocalPlayerCrawlMixin` + `ProneBridge` |
| ③ | 趴下输入只保留一份：两个模组都在时接管 TACZ 的趴下键（键盘 + 手柄） | `CrawlKeyMixin` |
| ④ | 姿势权威：ParCool 趴下期间由 `updatePlayerPose` 直接产出 `SWIMMING`，不再每 tick 被改两次；结束时立刻恢复站立/潜行 | `PlayerPoseMixin` + `ProneBridge#restorePose` |
| ⑤ | 防抖：滑铲“再点一下”造成的 1 tick 松手不会闪、不会产生无意义同步包 | `ProneArbiter`（`stand_debounce_ticks`，默认 1） |
| ⑥ | **输入权威**：只有趴下键仍按下（或切换模式开启）才算趴下；输入消失后任何 doing 状态都不能维持趴下 | `ProneBridge` |
| ⑦ | **强制按住式**：即使 ParCool 配成 Toggle，也按“松开即起”工作（可关） | `ParCoolCrawlMixin` + `ProneBridge#forceHoldToCrawl` |
| ⑧ | **锁存兜底**：松键后 ParCool 仍 doing 超过 2 tick，强制结束 Crawl/Slide 并同步服务端 | `ProneBridge#stopProneActions` |
| ⑨ | **空闲站立兜底**：每 tick 检查，无趴下来源时清除残留的 `SWIMMING` 与强制姿势 | `ProneBridge#enforceStanding` |

**不做的事**：不修改 ParCool / TACZ 的文件，不替换它们的趴下效果，不改它们的配置，
不在缺少任一模组或 ParCool 爬行被限制关闭时做任何事。

## 3. 安装

1. **Forge 1.20.1（47.x）** + **ParCool 1.20.1-3.3.1.0** + **TACZ 1.20.1-1.1.7 / 1.1.8**；
2. 把 `crawlfix-1.0.0-forge.jar` 放进 `mods/`（客户端安装即可，服务端装不装都不影响）；
3. 键位保持默认（两个模组的趴下键都是 `C`）即可。

下载：见本仓库的 **Releases** 页面（CI 构建产物同时作为 workflow artifact 提供）。

## 4. 配置

`config/crawlfix-client.toml`（仅客户端）：

| 选项 | 默认 | 说明 |
| --- | --- | --- |
| `general.enabled` | `true` | 总开关；关闭后模组完全不做事 |
| `general.restore_pose_on_end` | `true` | 趴下结束时立刻纠正残留姿势 |
| `general.disable_tacz_crawl_key` | `true` | 由 ParCool 的趴下键独占输入 |
| `general.stand_debounce_ticks` | `1` | 松开后保持趴下的 tick 数（0–5），用于吸收滑铲“再点一下”的短暂松手 |
| `general.force_hold_to_crawl` | `true` | 即使 ParCool 配置为 Toggle，也强制“按住才趴、松开即起” |
| `general.enforce_standing` | `true` | 空闲时若仍是趴下姿势，强制恢复站立/潜行 |
| `general.clear_stale_forced_pose` | `true` | 清理其它来源残留的 `SWIMMING` 强制姿势 |
| `general.debug_log` | `false` | 输出状态切换日志（排查用） |

## 5. 构建与测试

```bash
# 依赖：JDK 17；首次构建需要联网
./scripts/fetch-deps.sh          # 下载仅用于编译的 ParCool 3.3.1.0 / TACZ 1.1.7 JAR 到 libs/
./gradlew build                  # 单元测试 + 编译 + 重混淆，产物 build/libs/crawlfix-1.0.0-forge.jar

# 真实客户端自动化测试（需要 X11/Wayland + OpenGL；无显卡时用 Mesa 软件渲染）
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runAutotest
```

* `runAutotest` 会在真实客户端里自动创建超平坦世界、模拟趴下键，逐 tick 校验
  ParCool / TACZ / 玩家姿势三者的一致性，并输出 `run/autotest/autotest-report.txt`；
* CI（GitHub Actions）会在 `xvfb + llvmpipe` 下重复这套测试，并上传报告与完整日志；
* 发布 JAR 不包含测试代码（`com/ns/crawlfix/autotest/**`）。

## 6. 验证结果

| 环境 | 组合 | 结果 |
| --- | --- | --- |
| 真实客户端（本机，Mesa 软件渲染） | ParCool 3.3.1.0 + TACZ 1.1.7 | **59/59 断言通过**（含动作锁存对抗、Toggle 模式、残留强制姿势） |
| 真实客户端（本机） | ParCool 3.3.1.0 + TACZ 1.1.8 | **50/50 断言通过**（1.0.0 基线） |
| GitHub Actions（xvfb + llvmpipe） | ParCool 3.3.1.0 + TACZ 1.1.7 | **通过**（main 与 v1.0.0 基线；1.0.1 由 CI 复跑） |
| 单元测试 | — | 10/10 通过 |

修复前的同一套真实客户端测试有 **17 项失败**，现象与玩家反馈一致；修复后全部通过。
详见 [`docs/TESTPLAN.md`](docs/TESTPLAN.md)。

## 7. 兼容性

| 组件 | 版本 |
| --- | --- |
| Minecraft / Forge | 1.20.1 / 47.x（编译于 47.4.3） |
| ParCool | **1.20.1-3.3.1.0**（编译目标）；3.4.x 的公开 API 未变，亦可工作 |
| TACZ | 1.1.7 与 1.1.8（`LocalPlayerCrawl` / `CrawlKey` 的相关成员一致） |

## 8. 目录结构

```
parcool-tacz-crawlfix/
├── build.gradle                 # NeoForge moddev legacyforge 2.0.148 + Mixin
├── gradle.properties
├── scripts/fetch-deps.sh        # 下载仅编译期的模组 JAR
├── libs/                        # 仅编译期（.gitignore，不随仓库分发）
├── docs/{ANALYSIS,DESIGN,TESTPLAN}.md
├── src/main/java/com/ns/crawlfix/
│   ├── CrawlFix.java            # 模组入口
│   ├── CrawlFixConfig.java      # 客户端配置
│   ├── Platform.java            # 模组存在性/开关（不引用模组类）
│   ├── MixinSupport.java
│   ├── arbiter/ProneArbiter.java        # 纯逻辑仲裁（单元测试覆盖）
│   ├── client/                  # ProneBridge / ClientEvents / 状态持有
│   ├── mixin/                   # 4 个客户端 Mixin + 插件
│   └── autotest/                # 真实客户端自动化测试（不打包）
├── src/test/java/               # 仲裁逻辑单元测试
└── .github/workflows/ci.yml     # 构建 + xvfb 真实客户端测试 + Release
```

## 9. 隔离与来源

* 本工程为独立目录，自带 Gradle 包装器与独立的 Gradle 缓存（`.gradle-home/`，已 gitignore）；
  开发过程未修改工作区内任何其它文件（只读取现成的 ParCool/TACZ JAR 用于分析与编译）。
* ParCool 上游仓库已删除 3.x tag，3.3.1.0 的分析来自官方发布 JAR（Vineflower 反编译，
  与 Modrinth CDN 文件 SHA-1 一致）；TACZ 使用其 GitHub `1.20.1` 分支源码（1.1.8-hotfix2），
  并与 1.1.7-hotfix2 生产 JAR 交叉核对。
* 本工程以 MIT 许可发布；`libs/` 内的第三方 JAR 不属于本工程，也不随仓库分发。
