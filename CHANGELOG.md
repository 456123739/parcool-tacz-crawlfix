# Changelog

## 1.0.0 — 2026-10-23

首个版本。

### 修复

* **ParCool 姿态锁存**：`Crawl#canContinue` 在 `isVisuallyCrawling()` 时会跳过按键检查，
  被 TACZ 的 `forcedPose(SWIMMING)` 触发后导致“点一下趴下键就永久趴下”。现在输入检查无条件执行。
* **TACZ 趴下状态与服务端同步**：本地状态与服务端已知状态都强制对齐 ParCool 的 `Crawl || Slide`；
  绕过 10 tick 冷却与 `onGround` 取消门槛；对“只清本地不发包”的分支补发同步包。
* **姿势抽搐**：ParCool 趴下期间由 `Player#updatePlayerPose` 直接产出 `SWIMMING`，
  不再每 tick 被两个写入者反复改写；结束时立即恢复站立/潜行。
* **滑铲“再点一下”闪烁**：加入默认 2 tick 的离地防抖，短暂松手不会闪、不产生无意义网络包。
* **输入语义冲突**：两个模组都在时，TACZ 的趴下键由 ParCool 接管（可配置）。

### 验证

* 单元测试 10 项全部通过。
* 真实 Minecraft 1.20.1 Forge 客户端自动化测试（xvfb + Mesa 软件渲染）：
  9 组场景、50 项断言全部通过，覆盖快速点按、连续点按、滑铲中途松手、滑铲转爬行、
  空中爬行、空手、残留状态恢复等。
