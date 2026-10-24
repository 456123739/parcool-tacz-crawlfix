# 修复设计

## 目标

在不修改 ParCool / TACZ 任何文件、不改变两个模组各自玩法含义的前提下，保证：

1. **松开趴下键必定结束趴下** —— 无论姿势当前被谁固定（ParCool 自己、TACZ 的 `forcedPose`、其它模组）。
2. **只有一份趴下状态** —— TACZ 的本地状态与服务端状态都与 ParCool 的 `Crawl || Slide` 对齐。
3. **姿势稳定** —— 趴下期间姿势不会每 tick 被两个写入者反复改写（不再抽搐），结束后立即恢复站立/潜行。
4. **不丢输入** —— 绕过 TACZ 的 10 tick 冷却、`onGround` 取消门槛与“只清本地不发包”的分支。
5. **完全惰性** —— 缺少任一前置模组、或 ParCool 的爬行被限制关闭时，本模组不做任何事。

## 修复支柱

### ① 修掉 ParCool 的“姿态锁存”（主因）

`Crawl#canContinue` 在 `player.isVisuallyCrawling()` 为真时会**跳过**按键检查（见 `docs/ANALYSIS.md` 1.1）。
只要姿势被固定在 `SWIMMING`，爬行就永远不会结束。

`ParCoolCrawlMixin` 在 `Crawl#canContinue` 的 HEAD 处无条件执行输入检查（仅本地玩家）：

```java
if (!player.isLocalPlayer()) return;
if (!ProneBridge.parcoolCrawlKeyHeld((Crawl) (Object) this)) cir.setReturnValue(false);
```

`parcoolCrawlKeyHeld` 按 ParCool 自己的配置区分模式：

```java
ControlType.Toggle -> crawl.toggleStatus          // 切换模式仍然按切换状态
default            -> KeyBindings.getKeyCrawl().isDown()   // 按住模式看按键
```

这一步让“松手即结束”不再依赖“姿势恰好在这一 tick 被原版算回 STANDING”这种偶然条件。

### ② 单一一套趴下状态（含服务端）

`LocalPlayerCrawlMixin` 给 TACZ 的 `LocalPlayerCrawl` 增加一个不受冷却/地面限制的写入入口，并记录
“我们最后一次告诉服务端的状态”：

```java
isCrawling = want;
crawCooldownTicks = 0;          // 清掉 10 tick 冷却
setCrawlPose();                 // 设置/清除 forcedPose
if (!hasSynced || lastSynced != want || localChanged) {
    NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerCrawl(want));
    lastSynced = want;
}
```

每客户端 tick 末尾，`ProneBridge` 用 `ProneArbiter` 计算目标状态：

```
want = (ParCool.Crawl.isDoing() || ParCool.Slide.isDoing()，含防抖窗口) && 当前武器允许趴下
若 (TACZ 本地状态 != want) 或 (服务端已知状态 != want) → 写入
```

“当前武器允许趴下”完整复刻 TACZ 的前置条件（`SyncConfig.ENABLE_CRAWL`、非观察者/骑乘/离地、
主手 `IGun` 且 `isCanCrawl`、`ClientGunIndex` 已加载），避免无意义的同步包。

`CrawlKeyMixin` 在 ParCool 爬行可用时接管 TACZ 的趴下键（键盘与手柄两个入口），
让趴下输入只剩 ParCool 一份，从源头消除“切换式 vs 按住式”的语义冲突。

### ③ 姿势权威

`PlayerPoseMixin` 在 `Player#updatePlayerPose` 的 HEAD 处接管：只要 ParCool 仍处于爬行/滑铲
（或仍处于防抖窗口），就直接 `setPose(SWIMMING)` 并跳过原版逻辑。

* 姿势在整个 tick 内稳定，不再“原版先算 STANDING、ParCool 再写 SWIMMING”；
* 空手、空中爬行等 TACZ 不参与趴下的场景同样稳定；
* 只在本地玩家、且 `SWIMMING` 确实放得下（`noCollision`）时接管；
* 别的模组设置的强制姿势（非 `SWIMMING`）不碰。

趴下结束的那一 tick，`ProneBridge#restorePose` 立刻按“潜行 → 站立 → 蹲下”的顺序恢复姿势；
低矮空间里都放不下时保持 `SWIMMING`（与原版一致）。

### ④ 防抖：消除“再点一下”造成的 1 tick 站/趴闪烁

ParCool 的滑铲需要在爬行中再点一下趴下键，这一次点击必然包含一次松手。
松手的那个 tick 里 `Crawl` 会短暂结束；若立刻站起，会出现 1 tick 的站/趴闪烁与一对无意义的网络包。

`ProneArbiter` 内置 `stand_debounce_ticks`（默认 2）：松开后先保持趴下状态若干 tick，
期间如果重新按下则完全无感；真正松手时最多延迟 2 tick（约 100ms）站起。

### ⑤ 输入权威（1.0.1）

前四条仍然把 `Crawl#isDoing()` 当作“趴下”的充分条件。真实整合包里，ParCool 可能因为
`CrawlControl=Toggle`、版本差异或被其它模组干扰而在松键后继续 doing；这时修复层会继续
把 TACZ 与姿势锁在趴下 —— 表现为站起延迟/永久趴着，而跳跃有时能结束动作，所以“时灵时不灵”。

1.0.1 把**输入**提升为最终权威：

```
proneForState = (Crawl.isDoing() || Slide.isDoing()) && crawlInputHeld()
```

`crawlInputHeld()`：PressKey 模式看按键；Toggle 模式看 `toggleStatus`；
默认 `force_hold_to_crawl=true` 时**一律看按键**（并屏蔽 ParCool 的 toggle 翻转、同步
`toggleStatus`），保证“松开即起”。

配套三道兜底：

* **锁存兜底**：输入消失后仍 doing 连续 2 tick → 强制 `setDoing(false)` + `onStop`，
  并通过 `SyncActionStateMessage.Encoder#appendFinishMsg` 通知服务端（姿势/碰撞箱一起恢复）。
* **空闲站立兜底**：每 tick 检查，输入松开且没有任何趴下来源时，若姿势仍是 `SWIMMING`
  或残留 `forcedPose(SWIMMING)`，直接恢复站立/潜行（低矮空间保持趴下）。
* **ParCool 爬行被禁用时**：清掉可能残留的 TACZ 趴下状态，不再卡住。

另外，TACZ 趴下键的接管不再依赖 `ActionInfo#can(Crawl)`（该值会随服务器限制同步短暂变化，
漏键时 TACZ 的切换式按键会漏进来，造成“站起来又立刻趴下”）。

## 时序

```
ClientTickEvent.END（所有玩家 tick 之后、渲染之前）
  └─ ProneBridge.tick
       ├─ 读 ParCool: Parkourability.get(player).get(Crawl/Slide).isDoing()
       ├─ 读 TACZ:   CrawlStateAccess.crawlfix$isCrawling / isSynchronized
       ├─ ProneArbiter.tick(...)         ← 纯逻辑，单元测试覆盖
       ├─ crawlfix$apply(want)           ← 写状态、刷姿势、必要时发包
       └─ restorePose(...)               ← 仅在“趴下 -> 站起”的下降沿
```

* TACZ 在 `LocalPlayer#tick` 的 HEAD 调 `tickCrawl()`；
* ParCool 在 `PlayerTickEvent.END` 推进动作并写姿势；
* `ClientTickEvent.END` 在整个客户端 tick 之后，因此读到的是本 tick 的最终状态，
  纠正也在渲染前完成。

## 覆盖到的“其它路径”

| 路径 | 处理 |
| --- | --- |
| 滑铲中松手 / 滑铲自然结束转爬行 | 状态由 `Crawl||Slide` 推导，任意时刻松手都能结束 |
| 空中松手（TACZ 的 `crawl(false)` 被 `onGround` 挡住） | 写入入口不检查 `onGround`，且一定发包 |
| 切枪 / 收枪 / 落水 / 跳跃导致 TACZ 静默清本地状态 | `lastSynced` 跟踪服务端认知，必要时补发 |
| TACZ 10 tick 冷却吞掉纠正 | 写入时清零冷却 |
| 快速连点 | 每次按下/松开都按同一套状态推导，不存在错相 |
| 爬行中再点一下（1~2 tick 松手） | 防抖窗口内保持趴下，不闪、不发包 |
| 空手 / 不可趴下的枪 | TACZ 不参与，ParCool 姿势仍稳定 |
| 空中爬行 | TACZ 不参与，姿势稳定 |
| 低矮空间 | 站不起来时保持趴下，与原版一致 |
| ParCool 爬行被服务器限制关闭 | 整个模组退出，TACZ 恢复原生行为 |
| 死亡/重生/换维度 | 每 tick 重新对齐；服务端状态随新实体重建 |
| 其它模组的强制姿势 | 只在 `forcedPose == null || == SWIMMING` 时接管；恢复姿势时避开其它动作 |

## 非破坏性

* 只在 ParCool 与 TACZ **同时存在**时应用 4 个客户端 Mixin（`CrawlFixMixinPlugin` 判定），
  缺少任一模组时 Mixin 不应用、事件不注册，等同没装。
* 不替换 TACZ 的趴下效果：状态照常写进 TACZ，枪械精度/服务端逻辑不变；只是由 ParCool 决定“何时趴”。
* 不注入 ParCool 的动作状态机，只修 `canContinue` 的输入条件（并保持两种控制模式语义）。
* 所有行为都有配置开关；`enabled=false` 时整个模组不做事。

## 已知边界

* 两个模组的趴下键默认都是 `C`。若玩家把 TACZ 的趴下键改到别处，在 ParCool 爬行可用时该键会被接管；
  想保留它请把 `disable_tacz_crawl_key` 设为 `false`（此时仍会对齐状态）。
* ParCool 若配置为 `Toggle` 模式，“松手站起”不适用（这是 ParCool 的显式选择），但两套状态仍然一致。
* 只处理本地玩家；其它玩家由他们自己的客户端上报。
