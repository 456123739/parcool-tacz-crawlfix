# 根因分析：ParCool 滑铲/爬行 与 TACZ 趴下 的冲突

分析对象：

| 模组 | 版本 | 形式 | 来源 |
| --- | --- | --- | --- |
| ParCool! | 1.20.1-3.3.1.0 | 生产 JAR（SRG 名） | 官方发布 JAR（`refmods/ParCool-1.20.1-3.3.1.0.jar`，与 Modrinth CDN 上的文件 SHA-1 一致）；上游仓库已删除 3.x tag，因此用 Vineflower 反编译分析 |
| TACZ | 1.20.1-1.1.8-hotfix2（源码）/ 1.20.1-1.1.7-hotfix2（整合包实际 JAR） | GitHub `1.20.1` 分支源码 + 生产 JAR | 趴下逻辑两版一致，差异见文末 |
| Minecraft | 1.20.1 | Forge 47.4.3 | `Player#updatePlayerPose`、`Entity#setPose/isVisuallyCrawling`、`Entity#refreshDimensions` |

> **结论**：这不是某一个模组的“小 bug”，而是 **4 个独立缺陷叠加**：
>
> 1. **ParCool 的姿态锁存**（主因）：只要玩家姿势是 `SWIMMING`，ParCool 就跳过“按键是否还按着”的检查，
>    爬行永不结束。单独用 ParCool 时，原版每 tick 会把姿势算回 `STANDING`，把这个缺陷掩盖了；
>    TACZ 用 `setForcedPose(SWIMMING)` 把姿势钉死，于是“按一下 C 就永久趴下”。
> 2. **两套趴下状态 + 两种按键语义**：ParCool 按住、TACZ 切换，且默认都绑 `C`；同一次点击会同时驱动两套状态，
>    第二次点击就会让它们错相（一个趴、一个站）。
> 3. **TACZ 取消趴下的路径会丢输入**：10 tick 冷却、`onGround` 前置条件、以及若干“只清本地不发包”的分支，
>    导致服务端继续保留趴下强制姿势。
> 4. **两个姿势写入者**：ParCool 在 `PlayerTickEvent.END` 直接 `setPose`，TACZ 用优先级更高的 `forcedPose`；
>    两者不同步时每 tick 姿势被改两次，碰撞箱/眼高跟着跳 —— 这就是“抽搐”。

---

## 1. 四个缺陷的代码证据

### 1.1 ParCool：按住式爬行，但“已经趴下”时不再检查按键

`com.alrex.parcool.common.action.impl.Crawl`（3.3.1.0，反编译）：

```java
public boolean canStart(Player player, Parkourability park, IStamina stamina, ByteBuffer startInfo) {
    return (ParCoolConfig.Client.CrawlControl.get() == Crawl.ControlType.PressKey
                && KeyRecorder.keyCrawlState.isPressed()          // 按下沿
            || ParCoolConfig.Client.CrawlControl.get() == Crawl.ControlType.Toggle && this.toggleStatus)
        && ... && (player.onGround() || EnableCrawlInAir.get());
}

public boolean canContinue(Player player, Parkourability park, IStamina stamina) {
    if (!player.isVisuallyCrawling()) {          // ← 注意这个条件
        switch (CrawlControl.get()) {
            case Toggle:   if (!this.toggleStatus) return false; break;
            case PressKey: if (!KeyBindings.getKeyCrawl().isDown()) return false;   // 松开即结束
        }
    }
    return ...;
}

public void onWorkingTick(Player player, Parkourability park, IStamina stamina) {
    player.setShiftKeyDown(false);
    player.setPose(Pose.SWIMMING);      // 直接写姿势（不是 forcedPose）
}
```

`Entity#isVisuallyCrawling()`（1.20.1）：

```java
public boolean isVisuallySwimming() { return this.hasPose(Pose.SWIMMING); }
public boolean isVisuallyCrawling() { return this.isVisuallySwimming() && !this.isInWater() && ...; }
```

也就是说：**玩家当前姿势是 `SWIMMING`、且不在水里时，`canContinue` 完全跳过输入检查**。
`Crawl` 自己每 tick 会把姿势写成 `SWIMMING`，所以在“动作结算”这一刻，姿势取决于**谁在本 tick 更早写了姿势**：

* 只有 ParCool 时：`Player#updatePlayerPose()`（每 tick 开头）看到 `forcedPose == null`、没在游泳、没潜行，
  把姿势算回 `STANDING`；随后动作结算看到“没在趴”，于是按键检查照常执行 → 松手能结束。
* 装了 TACZ 时：`updatePlayerPose()` 里 `forcedPose != null` 直接 `setPose(SWIMMING)`；
  动作结算看到“已经在趴”，**按键检查被跳过** → 松手也永远结束不了。

这就是“点一下趴下键就再也站不起来”的主因，与 TACZ 的切换语义无关：
哪怕 TACZ 只是把姿势钉住，ParCool 也会锁存。

`com.alrex.parcool.common.action.impl.Slide`（滑铲）是爬行的派生状态：

```java
public boolean canStart(...) { ... && KeyRecorder.keyCrawlState.isPressed()
                                   && park.get(Crawl.class).isDoing()
                                   && park.get(FastRun.class).getDashTick(...) > 5; }
public boolean canContinue(...) { return this.getDoingTick() < maxSlidingTick && park.get(Crawl.class).isDoing(); }
```

滑铲、爬行共享同一个 `C` 键，`Crawl` 锁存时 `Slide` 也会跟着一直“doing”。

### 1.2 ParCool / TACZ 共用默认按键，语义相反

```java
// ParCool
new KeyMapping("key.parcool.Crawl", 67, "key.categories.parcool");      // 67 = C，按住式（默认 PressKey）

// TACZ
new KeyMapping("key.tacz.crawl.desc", KeyConflictContext.IN_GAME, KeyModifier.NONE, Type.KEYSYM, 67, ...);
boolean action = true;
if (!KeyConfig.HOLD_TO_CRAWL.get()) action = !operator.isCrawl();      // 默认 false => 切换式
```

同一个物理按键，一边“按住才趴”，一边“按一下趴、再按一下起”。
第二次点击时，ParCool 仍处于趴下（见 1.1 的锁存），TACZ 却切成站立 —— 两套状态从此错相。

### 1.3 TACZ：取消趴下的三条丢输入路径

`com.tacz.guns.client.gameplay.LocalPlayerCrawl`：

```java
private static final int COOLDOWN_TICKS = 10;

public void crawl(boolean isCrawl) {
    ...
    if (crawCooldownTicks > 0) return;                                  // ① 10 tick 内第二次输入被吞
    if (player.isSpectator() || player.isPassenger() || !player.onGround()) return;  // ② 空中无法取消
    ...
    this.isCrawling = isCrawl;
    this.crawCooldownTicks = COOLDOWN_TICKS;
    NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerCrawl(isCrawl));
}

public void tickCrawl() {
    ...
    if (!(mainHandItem.getItem() instanceof IGun iGun)) { isCrawling = false; setCrawlPose(); return; }  // ③
    if (!iGun.isCanCrawl(mainHandItem))               { isCrawling = false; setCrawlPose(); return; }  // ③
    if (TimelessAPI.getCommonGunIndex(gunId).isEmpty()){ isCrawling = false; setCrawlPose(); return; }  // ③
    if (... || player.jumping || player.isSwimming() || !player.onGround()) {
        isCrawling = false; setCrawlPose(); return;                     // ③ ①③ 只清本地，不发包
    }
    this.setCrawlPose();
}

private void setCrawlPose() {
    if (isCrawling) player.setForcedPose(Pose.SWIMMING);   // 强制姿势
    else            player.setForcedPose(null);
}
```

服务端还有一份状态：`LivingEntityCrawl#tickCrawling()` 也会对 `ServerPlayer` 调 `setForcedPose(SWIMMING)`；
客户端只能通过 `ClientMessagePlayerCrawl` 通知它，而 ①②③ 都会让这次通知发不出去。

### 1.4 两个姿势写入者：每 tick 改两次

`Player#updatePlayerPose()`（1.20.1，位于 `Player#tick()` 末尾）：

```java
protected void updatePlayerPose() {
    if (this.forcedPose != null) {
        this.setPose(this.forcedPose);        // TACZ 的趴下在这里生效，其它逻辑全部跳过
    } else {
        ... 按 isFallFlying / isSleeping / isSwimming / isShiftKeyDown 计算 STANDING/CROUCHING ...
        this.setPose(pose1);
    }
}
```

而 `Player#tick()` 的顺序是：

```java
this.updatePlayerPose();
ForgeEventFactory.onPlayerPostTick(this);      // Forge 在这里发 PlayerTickEvent.END
```

ParCool 在 `PlayerTickEvent.END` 里写 `SWIMMING`，写在原版计算**之后**。两个写入者状态不一致时，
每 tick 姿势在 `SWIMMING` / `STANDING` 之间来回切；`Entity#setPose` → `onSyncedDataUpdated` → `refreshDimensions()`
会连带改碰撞箱与眼高，这就是玩家看到的“在站起和趴下之间抽搐”。

---

## 2. 现象如何对应到缺陷

| 现象 | 触发链 |
| --- | --- |
| 点一下 C 就永久趴下、按什么都没用 | 1.1 锁存（TACZ 的 `forcedPose` 让“已趴下”成立）+ 1.2 的切换语义 + 1.3① 冷却吞掉第二次点击 |
| 滑铲即将转爬行时松手仍趴着 | 同上；滑铲结束与否取决于 `Crawl.isDoing()`，而它被锁存 |
| 站着/趴着抽搐 | 1.2 的错相（一次点击让 TACZ 站立、ParCool 仍趴下）+ 1.4 每 tick 两次姿势写入 |
| 空中松手仍趴着 | 1.3②（`crawl(false)` 被 `onGround` 挡住，取消包发不出）+ 服务端强制姿势残留 |
| 切枪 / 落水后仍趴着 | 1.3③ 只清本地不发包，服务端仍在强制姿势 |
| 只装 ParCool 没问题 | 1.1 里 `updatePlayerPose` 会把姿势算回 `STANDING`，掩盖锁存；且没有第二个姿势写入者 |

---

## 2.5 真实整合包里仍然可能“松手不起”的两条漏网路径（1.0.1 补修）

1. **把 `Crawl#isDoing()` 当作趴下成立的充分条件**。
   即使修掉了 1.1 的锁存，只要 ParCool 因为别的原因在松键后继续 doing，修复层仍会维持趴下：
   * ParCool 的 `CrawlControl` 被配置为 `Toggle`：松手本来就该继续趴，直到再按一次；
   * 其它模组/异常状态让 `Slide` 或 `Crawl` 多活几 tick；
   * 版本差异导致 `ParCoolCrawlMixin` 的注入没有生效。

   现象就是“站起延迟 / 偶尔站不起来”；而跳跃有时能触发 ParCool 的跳跃取消/让动作结束，
   所以“按跳跃有时管用”。

2. **TACZ 趴下键的接管条件依赖 `ActionInfo#can(Crawl)`**。
   该值 = `ParCool.isActive() && 客户端设置 && 服务器限制`，会随服务器限制同步、维度切换等
   短暂变成 false。若按键恰好发生在那个窗口，`CrawlKey` 的切换式逻辑就会执行：
   玩家站起来后立刻又被切回趴下 —— 正是“站起来又立马趴下去”。

修法见 `docs/DESIGN.md`：输入权威 + 强制按住式 + 锁存兜底 + 空闲站立兜底 + 无条件接管按键。

## 3. 版本差异备注

* TACZ 1.1.8 的 `tickCrawl()` 额外检查 `player.jumping` 与 `player.isSwimming()`；1.1.7 没有。
  两者都不改变结论，本修复涉及的 `LocalPlayerCrawl#crawl/#tickCrawl/#isCrawling`、字段
  `isCrawling` `crawCooldownTicks` `player` 与 `CrawlKey#onCrawlPress` 在两版中完全一致。
* ParCool 3.4.x 的 `Parkourability` 内部实现有变化（多了 `BehaviorEnforcer`），但本修复用到的
  `Parkourability.get(Player)`、`get(Class)`、`Action#isDoing()`、`Crawl`/`Slide` 类名、
  `Crawl.ControlType`、`Crawl#toggleStatus`（public 字段）与 `ActionInfo#can(Class)` 均保持不变，
  因此 3.4.3.2 也能工作（编译目标是 3.3.1.0）。
