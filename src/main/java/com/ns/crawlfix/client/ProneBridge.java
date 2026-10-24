package com.ns.crawlfix.client;

import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.impl.Crawl;
import com.alrex.parcool.common.action.impl.Slide;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.network.SyncActionStateMessage;
import com.alrex.parcool.config.ParCoolConfig;
import com.ns.crawlfix.CrawlFixConfig;
import com.ns.crawlfix.Platform;
import com.ns.crawlfix.arbiter.ProneArbiter;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.config.sync.SyncConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 两个模组之间的状态桥。
 *
 * <p>核心不变量（比“让两个模组状态一致”更强）：
 * <b>只有玩家真的还按着趴下键（或 ParCool 处于切换模式的“开”状态）时，才允许保持趴下。</b>
 * 一旦输入消失，无论 ParCool 的动作状态、TACZ 的本地/服务端状态、还是任何模组的强制姿势
 * 是否还残留，玩家都必须在极短时间内站起来。
 *
 * <p>为此这里做四件事：
 * <ol>
 *   <li>用输入状态过滤 ParCool 的 {@code Crawl/Slide}（输入没了就不再算“趴下”）；</li>
 *   <li>把 TACZ 的本地状态与服务端状态对齐到这个结果；</li>
 *   <li>趴下结束的下降沿立即恢复姿势；</li>
 *   <li>兜底：如果 ParCool 在输入消失后 2 tick 仍然“doing”（锁存/版本差异），强制结束它并同步服务端。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class ProneBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("crawlfix");
    private static final ProneArbiter ARBITER = new ProneArbiter();
    private static LocalPlayer trackedPlayer;
    private static boolean lastWant;
    /** 输入已松开、但 ParCool 仍然 doing 的连续 tick 数。 */
    private static int latchedTicks;

    private ProneBridge() {
    }

    public static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            trackedPlayer = null;
            ARBITER.reset();
            latchedTicks = 0;
            return;
        }
        if (player != trackedPlayer) {
            trackedPlayer = player;
            ARBITER.reset();
            latchedTicks = 0;
        }

        Parkourability parkourability = Parkourability.get(player);
        if (parkourability == null) {
            return;
        }
        if (!parkourability.getActionInfo().can(Crawl.class)) {
            // ParCool 的爬行被客户端设置或服务器限制关闭：不再由它决定趴下。
            // 但必须清掉残留的防抖状态与可能残留的 TACZ 趴下，否则玩家会卡在趴下姿势。
            ARBITER.reset();
            latchedTicks = 0;
            clearTaczCrawl(player);
            enforceStanding(player, parkourability);
            return;
        }

        boolean inputHeld = crawlInputHeld(player, parkourability);
        boolean actionsDoing = isParCoolProne(parkourability);
        boolean prone = actionsDoing && inputHeld;

        // 兜底：输入已经松开，ParCool 却还在 doing —— 说明它的“结束”路径没生效
        // （版本差异、被其它模组/异常状态打断等）。连续 2 tick 就强制结束并同步服务端。
        if (actionsDoing && !inputHeld) {
            latchedTicks++;
            if (latchedTicks >= 2) {
                stopProneActions(player, parkourability);
                latchedTicks = 0;
                prone = false;
                debug("ParCool 趴下动作在松键后仍处于 doing，已强制结束");
            }
        } else {
            latchedTicks = 0;
        }

        CrawlStateAccess taczCrawl = CrawlStateHolder.get(player);
        boolean taczCrawling = taczCrawl != null
                ? taczCrawl.crawlfix$isCrawling()
                : IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();
        boolean taczSynchronized = taczCrawl != null
                ? taczCrawl.crawlfix$isSynchronized()
                : IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();

        ProneArbiter.Output output = ARBITER.tick(
                true,
                prone,
                taczCrawling,
                taczSynchronized,
                canCrawlWithCurrentGun(player),
                CrawlFixConfig.getInt(CrawlFixConfig.STAND_DEBOUNCE_TICKS, 1),
                CrawlFixConfig.get(CrawlFixConfig.RESTORE_POSE_ON_END, true)
        );

        if (output.shouldApplyTacz()) {
            if (taczCrawl != null) {
                taczCrawl.crawlfix$apply(output.taczValue());
            } else {
                // TACZ 的 Mixin 没有生效（极少数版本差异）：退回公开 API + 清理强制姿势。
                IClientPlayerGunOperator.fromLocalPlayer(player).crawl(output.taczValue());
                if (!output.taczValue() && player.getForcedPose() == Pose.SWIMMING) {
                    player.setForcedPose(null);
                }
            }
        }
        if (output.taczValue() != lastWant) {
            lastWant = output.taczValue();
            debug("TACZ 趴下 -> " + lastWant);
        }
        if (output.restorePose()) {
            restorePose(player, parkourability);
        }

        // 最后一道保险：输入已松开、没有任何趴下来源时，不允许玩家继续停在 SWIMMING 姿势。
        enforceStanding(player, parkourability);
    }

    /** 关闭 TACZ 的趴下（本地 + 服务端），用于“ParCool 爬行不可用”或空闲兜底。 */
    private static void clearTaczCrawl(LocalPlayer player) {
        CrawlStateAccess access = CrawlStateHolder.get(player);
        if (access != null) {
            if (access.crawlfix$isCrawling()) {
                access.crawlfix$apply(false);
            }
            return;
        }
        try {
            if (IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl()) {
                IClientPlayerGunOperator.fromLocalPlayer(player).crawl(false);
            }
            if (player.getForcedPose() == Pose.SWIMMING) {
                player.setForcedPose(null);
            }
        } catch (LinkageError | RuntimeException ignored) {
            // TACZ 不可用时不处理
        }
    }

    /** 空闲时强制恢复站立（清理残留强制姿势 + SWIMMING 姿势）。 */
    private static void enforceStanding(LocalPlayer player, Parkourability parkourability) {
        if (!CrawlFixConfig.get(CrawlFixConfig.ENFORCE_STANDING, true)) {
            return;
        }
        if (crawlInputHeld(player, parkourability) || isParCoolProne(parkourability)) {
            return;
        }
        if (taczCrawlingNow(player, CrawlStateHolder.get(player))) {
            return;
        }
        if (CrawlFixConfig.get(CrawlFixConfig.CLEAR_STALE_FORCED_POSE, true)
                && player.getForcedPose() == Pose.SWIMMING && !player.isInWater()) {
            player.setForcedPose(null);
            debug("清除残留的 SWIMMING 强制姿势");
        }
        if (player.getPose() == Pose.SWIMMING) {
            restorePose(player, parkourability);
            debug("空闲状态下强制恢复站立姿势");
        }
    }

    private static boolean taczCrawlingNow(LocalPlayer player, CrawlStateAccess access) {
        return access != null ? access.crawlfix$isCrawling() : IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();
    }

    /**
     * 趴下输入当前是否“存在”：
     * <ul>
     *   <li>PressKey 模式：趴下键是否按下；</li>
     *   <li>Toggle 模式：{@code toggleStatus} 是否为开；</li>
     *   <li>读不到（版本差异/配置未加载）时返回 false（fail-closed：宁可站起来）。</li>
     * </ul>
     */
    public static boolean crawlInputHeld(Player player, Parkourability parkourability) {
        return parcoolCrawlKeyHeld(parkourability.get(Crawl.class));
    }

    /** 供 {@code ParCoolCrawlMixin} 使用；语义与 {@link #crawlInputHeld} 一致。 */
    public static boolean parcoolCrawlKeyHeld(Crawl crawl) {
        if (forceHoldToCrawl()) {
            return crawlKeyDown();
        }
        try {
            if (ParCoolConfig.Client.CrawlControl.get() == Crawl.ControlType.Toggle) {
                return crawl.toggleStatus;
            }
        } catch (LinkageError | RuntimeException ex) {
            // fail-closed
        }
        return crawlKeyDown();
    }

    /** 趴下键是否按下（读不到时 fail-closed 返回 false，保证能站起来）。 */
    public static boolean crawlKeyDown() {
        try {
            return KeyBindings.getKeyCrawl().isDown();
        } catch (LinkageError | RuntimeException ex) {
            return false;
        }
    }

    /** 是否强制“按住式”语义（即使 ParCool 配置为 Toggle）。 */
    public static boolean forceHoldToCrawl() {
        return CrawlFixConfig.get(CrawlFixConfig.FORCE_HOLD_TO_CRAWL, true);
    }

    /**
     * 是否屏蔽 TACZ 自带的趴下键。
     *
     * <p>只要两个模组都在并且配置开启就接管 —— 不再依赖 ParCool 的
     * {@code ActionInfo#can}（那个值会随服务器限制同步短暂变化；一旦在按键那一刻为 false，
     * TACZ 的切换式按键就会漏进来，造成“站起来又立刻趴下”）。
     */
    public static boolean shouldSuppressTaczCrawlInput() {
        return CrawlFixConfig.get(CrawlFixConfig.DISABLE_TACZ_CRAWL_KEY, true) && Platform.bothModsLoaded();
    }

    /**
     * 供 {@code Player#updatePlayerPose} 的 Mixin 调用：是否应当把本地玩家固定为趴下姿势。
     *
     * <p>条件：ParCool 处于爬行/滑铲、<b>并且趴下输入仍然存在</b>；或处于结束防抖窗口。
     * 输入已经松开但动作锁存时不会继续固定姿势 —— 这是“松手必起身”的最后一道保险。
     */
    public static boolean shouldForcePronePose(Player player) {
        if (!(player instanceof LocalPlayer)) {
            return false;
        }
        if (!Platform.activeOnClient()) {
            return false;
        }
        Parkourability parkourability = Parkourability.get(player);
        if (parkourability == null) {
            return false;
        }
        boolean prone = (isParCoolProne(parkourability) && crawlInputHeld(player, parkourability))
                || ARBITER.isArmed();
        if (!prone) {
            return false;
        }
        Pose forced = player.getForcedPose();
        if (forced != null && forced != Pose.SWIMMING) {
            return false;
        }
        return canEnterPose(player, Pose.SWIMMING);
    }

    private static boolean isParCoolProne(Parkourability parkourability) {
        return parkourability.get(Crawl.class).isDoing() || parkourability.get(Slide.class).isDoing();
    }

    /**
     * 强制结束 ParCool 的爬行/滑铲，并给服务端发送对应的 finish 消息，
     * 避免服务端继续保留趴下姿势或碰撞箱。
     */
    private static void stopProneActions(Player player, Parkourability parkourability) {
        try {
            Crawl crawl = parkourability.get(Crawl.class);
            Slide slide = parkourability.get(Slide.class);
            boolean stopped = false;
            SyncActionStateMessage.Encoder encoder = SyncActionStateMessage.Encoder.reset();
            if (slide.isDoing()) {
                slide.setDoing(false);
                slide.onStopInLocalClient(player);
                slide.onStop(player);
                encoder.appendFinishMsg(parkourability, slide);
                stopped = true;
            }
            if (crawl.isDoing()) {
                crawl.setDoing(false);
                crawl.onStopInLocalClient(player);
                crawl.onStop(player);
                encoder.appendFinishMsg(parkourability, crawl);
                stopped = true;
            }
            if (stopped) {
                SyncActionStateMessage.sync(player, encoder);
            }
        } catch (LinkageError | RuntimeException ex) {
            LOGGER.warn("crawlfix: failed to force-stop ParCool prone actions", ex);
        }
    }

    /**
     * 复刻 TACZ 自身进入趴下时的前置条件（持枪、该枪允许趴下、站在地面、数据已加载、服务端开启趴下）。
     * 条件不满足时不去写 TACZ 的状态，避免每 tick 反复发无效同步包。
     */
    private static boolean canCrawlWithCurrentGun(LocalPlayer player) {
        try {
            if (!SyncConfig.ENABLE_CRAWL.get()) {
                return false;
            }
            if (player.isSpectator() || player.isPassenger() || !player.onGround()) {
                return false;
            }
            ItemStack stack = player.getMainHandItem();
            IGun gun = IGun.getIGunOrNull(stack);
            if (gun == null || !gun.isCanCrawl(stack)) {
                return false;
            }
            return TimelessAPI.getClientGunIndex(gun.getGunId(stack)).isPresent();
        } catch (LinkageError | RuntimeException ex) {
            // 不同 TACZ 小版本之间的 API 差异不应让玩家崩游戏：保守地视为“可以”，
            // 由服务端自己的校验兜底。
            return true;
        }
    }

    /**
     * ParCool 的 Crawl/Slide 结束后，玩家姿势会停留在 SWIMMING，直到下一个原版
     * {@code Player#updatePlayerPose} 才会被改写。这里在同一个 tick 内直接纠正。
     *
     * <p>只在“因为输入消失而结束”的场景下纠正：其它 ParCool 动作自己写姿势时跳过，
     * 但 Crawl/Slide 本身即使还残留 doing 也不再阻止纠正。
     */
    private static void restorePose(LocalPlayer player, Parkourability parkourability) {
        if (player.getPose() != Pose.SWIMMING) {
            return;
        }
        if (player.isInWater() || player.isPassenger() || player.isFallFlying()
                || player.isSleeping() || player.isSpectator()) {
            return;
        }
        for (Action action : parkourability.getList()) {
            if (action.isDoing() && !(action instanceof Crawl) && !(action instanceof Slide)) {
                return;
            }
        }
        if (player.isShiftKeyDown() && canEnterPose(player, Pose.CROUCHING)) {
            player.setPose(Pose.CROUCHING);
        } else if (canEnterPose(player, Pose.STANDING)) {
            player.setPose(Pose.STANDING);
        } else if (canEnterPose(player, Pose.CROUCHING)) {
            player.setPose(Pose.CROUCHING);
        }
        // 低矮空间里既站不起来也蹲不下：维持 SWIMMING，与原版行为一致。
    }

    private static boolean canEnterPose(Player player, Pose pose) {
        return player.level().noCollision(
                player,
                player.getDimensions(pose).makeBoundingBox(player.position()).deflate(1.0E-7)
        );
    }

    private static void debug(String message) {
        if (CrawlFixConfig.get(CrawlFixConfig.DEBUG_LOG, false)) {
            LOGGER.info("[crawlfix] {}", message);
        }
    }
}
