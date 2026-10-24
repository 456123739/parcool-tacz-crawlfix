# 验证计划与结果

## 1. 自动化单元测试（不启动游戏）

```bash
./gradlew test
```

`src/test/java/com/ns/crawlfix/arbiter/ProneArbiterTest.java` 覆盖仲裁逻辑：

| 用例 | 覆盖场景 |
| --- | --- |
| `quickTapDoesNotLatch` | 点一下趴下键：防抖窗口后必须退出趴下并触发姿势纠正 |
| `zeroDebounceStandsImmediately` | 防抖为 0 时松手即站起 |
| `repeatedTapsAlwaysReturnToStanding` | 连续 10 次点按，每次都能站起 |
| `retapGapKeepsTaczState` | 爬行中再点一下（1 tick 松开）不产生状态掉线与无意义同步包 |
| `releaseAtSlideToCrawlBoundary` | 滑铲结束瞬间松手 |
| `noGunKeepsTaczStanding` | 空手/不可趴下的枪：TACZ 不参与 |
| `staleTaczProneIsCleared` | 本地残留趴下被清理 |
| `serverOnlyStaleStateIsResynced` | 本地已清、服务端仍是趴下：补发取消包 |
| `silentLocalClearWhileProneIsRestored` | ParCool 仍趴下时 TACZ 本地被静默清掉：恢复且不重复发包 |
| `inactiveWhenParCoolCrawlDisabled` | ParCool 爬行被限制关闭：完全退出 |

## 2. 真实客户端自动化测试（本项目的主要验证手段）

`src/main/java/com/ns/crawlfix/autotest/ClientAutoTest.java`（**不进入发布 JAR**）：

* 在真实 Minecraft 1.20.1 Forge 客户端里自动创建超平坦世界；
* 用真实的 `KeyMapping#setDown` 模拟趴下键（在 `ClientTickEvent.START` 的最高优先级写入，
  与真实 GLFW 事件的处理时机一致）；
* 主手生成 `tacz:ak47`（`can_crawl: true`），逐 tick 读取
  ParCool `Crawl/Slide`、TACZ `isCrawl()`、`Player#getPose/getForcedPose`；
* 逐 tick 采样姿势与 TACZ 状态，用于检查“抽搐”（姿势不是 SWIMMING）和“错相”（TACZ 状态掉线）；
* 结束后写 `autotest-report.txt` 并用退出码报告结果。

运行方式：

```bash
# 本机（需要 X11/Wayland + OpenGL；无显卡时用 Mesa 软件渲染）
./gradlew runAutotest --offline

# CI（GitHub Actions 里用 xvfb + llvmpipe）
xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runAutotest --no-daemon
```

场景清单：

| # | 场景 | 断言 |
| --- | --- | --- |
| 1 | 快速点一下趴下键 | 松手后站起 |
| 2 | 长按趴下 24 tick | 全程姿势为 SWIMMING、TACZ 同步、无抖动 |
| 3 | 连续快速点按 10 次 | 每次松手都能站起 |
| 4 | 爬行中再点一下（松开 2 tick 再按） | 期间姿势不闪、TACZ 不掉线，之后松手能站起 |
| 5 | 疾跑 + 趴下触发滑铲，滑铲中松手 | 站起 |
| 6 | 滑铲自然结束转爬行后松手 | 站起 |
| 7 | 模拟旧版残留的 TACZ 趴下 | 1 tick 内被清理 |
| 8 | 空中爬行（TACZ 不参与） | 姿势稳定，落地松手站起 |
| 9 | 空手趴下 | TACZ 不参与，姿势稳定，松手站起 |
| 10 | **动作锁存对抗**：松键后仍强制 ParCool doing + TACZ 趴下 | 不保持趴下姿势、TACZ 被清掉、解除后站立 |
| 11 | **Toggle 模式**：ParCool 配成 Toggle | 仍然“松手即起” |
| 12 | **残留强制姿势**：手动设置 `forcedPose(SWIMMING)` | 1 tick 内被清理并站起 |

## 3. 结果

| 环境 | 组合 | 结果 |
| --- | --- | --- |
| 本机真实客户端（Xwayland + Mesa llvmpipe 软件渲染） | ParCool 3.3.1.0 + TACZ 1.1.7-hotfix2 | **PASS，59/59 断言** |
| 本机真实客户端 | ParCool 3.3.1.0 + **TACZ 1.1.8-hotfix2** | **PASS，59/59 断言** |
| GitHub Actions（ubuntu + xvfb + llvmpipe） | ParCool 3.3.1.0 + TACZ 1.1.7-hotfix2 | **PASS，59/59 断言**（main 与 v1.0.1） |
| 单元测试 | — | **10/10 通过** |

**反向验证**：在加入 `ParCoolCrawlMixin`（姿态锁存修复）之前，同一套真实客户端测试出现
**17 项失败**，失败信息与玩家反馈完全一致（`松手后仍然趴着: parcool=true tacz=true pose=SWIMMING`、
连续点按后状态错相等）。加入修复后全部通过 —— 说明测试确实能捕获该缺陷，而不是“碰巧通过”。

发布 JAR 检查：`crawlfix-1.0.1-forge.jar` 包含 `META-INF/mods.toml`、`crawlfix.mixins.json`、
`crawlfix.refmap.json` 与 4 个 Mixin 类，**不含** `com/ns/crawlfix/autotest/**`，也不含 ParCool / TACZ 的任何类。

## 4. 游戏内手工验收（发布前建议）

1. 装 ParCool 3.3.1.0 + TACZ 1.1.7 + 本模组，主手拿 AK47，默认键位；
2. 站立点一下 `C`：趴下后立刻站起；
3. 按住 `C` 爬行再点一下 `C` 进入滑铲，中途松手：站起；
4. 滑铲自然结束转爬行时松手：站起；
5. 连续快速点按 10 次：每次都能站起，无抽搐；
6. 趴下时切枪/收枪、站到台阶边缘滑铲后松手：都能站起；
7. 低矮 1 格空间内松手：保持趴下，离开后自动站起；
8. 服务器关闭 ParCool 爬行：TACZ 趴下键恢复原生切换式行为；
9. 只装其中一个模组：行为与不装本模组一致。
