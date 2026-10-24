# Changelog

## 1.0.1 — 2026-10-25

修复 1.0.0 在真实整合包里仍会出现的“松手站起延迟 / 偶尔站不起来 / 站起来又立刻趴下”。

### 根因补充

* 1.0.0 仍然把 ParCool 的 `Crawl#isDoing()` 当作“趴下”的充分条件。只要 ParCool 因为
  切换模式（`CrawlControl=Toggle`）、版本差异或被其它模组干扰而在松键后仍保持 doing，
  修复层就会继续把 TACZ 与姿势锁在趴下 —— 表现为站起延迟或永久趴着；此时跳跃有时能结束
  ParCool 的动作，所以“按跳跃有时管用”。
* TACZ 趴下键的接管依赖 `ActionInfo#can(Crawl)`，该值会随服务器限制同步短暂变化；
  一旦在按键那一刻为 false，TACZ 的切换式按键就会漏进来，造成“站起来又立刻趴下”。

### 修复

* **输入权威**：只有趴下键仍按下（或强制按住式下）才算趴下；输入消失后 ParCool 的 doing 状态
  不再能维持趴下姿势。
* **强制按住式**：`force_hold_to_crawl=true`（默认）下，即使 ParCool 配置为 Toggle，
  也按“按下才趴、松开即起”工作（屏蔽 toggle 翻转，并同步 `toggleStatus`）。
* **锁存兜底**：输入消失后 ParCool 仍 doing 连续 2 tick，就强制结束 Crawl/Slide 并发送
  ParCool 的 finish 同步包（服务端姿势/碰撞箱一并恢复）。
* **空闲站立兜底**：每 tick 检查，若输入松开、没有任何趴下来源，仍处于 `SWIMMING`
  或残留 `forcedPose(SWIMMING)`，直接恢复站立/潜行（低矮空间保持趴下）。
* **输入接管不再依赖 `ActionInfo#can`**：两个模组都在即接管 TACZ 趴下键，避免限制同步抖动时漏键。
* **ParCool 爬行被禁用时**也会清掉可能残留的 TACZ 趴下状态，不再卡住。
* 新增 `debug_log`，可输出状态切换日志用于排查。

### 验证

* 真实客户端自动化测试新增：动作锁存对抗、Toggle 模式、残留强制姿势；
  共 **59 项断言全部通过**（TACZ 1.1.7）。
* 单元测试全部通过。

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
