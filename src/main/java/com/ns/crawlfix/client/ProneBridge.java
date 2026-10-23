package com.ns.crawlfix.client;

import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.impl.Crawl;
import com.alrex.parcool.common.action.impl.Slide;
import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.common.capability.Parkourability;
import com.ns.crawlfix.CrawlFixConfig;
import com.ns.crawlfix.Platform;
import com.ns.crawlfix.arbiter.ProneArbiter;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.config.sync.SyncConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 两个模组之间的状态桥。
 *
 * <p>负责：
 * <ol>
 *   <li>读取 ParCool 的趴下/滑铲状态；</li>
 *   <li>把 TACZ 的趴下状态（本地 + 服务端）强制对齐到它；</li>
 *   <li>在趴下结束的那一 tick 把残留的 SWIMMING 姿势纠正回站立/潜行；</li>
 *   <li>在原版姿势计算阶段把趴下姿势固定下来（见 {@code PlayerPoseMixin}）。</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public final class ProneBridge {

    private static final ProneArbiter ARBITER = new ProneArbiter();
    private static LocalPlayer trackedPlayer;

    private ProneBridge() {
    }

    public static void tick(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) {
            trackedPlayer = null;
            ARBITER.reset();
            return;
        }
        if (player != trackedPlayer) {
            trackedPlayer = player;
            ARBITER.reset();
        }

        CrawlStateAccess taczCrawl = CrawlStateHolder.get(player);
        if (taczCrawl == null) {
            // TACZ 还没初始化（理论上只在登录首 tick 出现）。
            return;
        }

        Parkourability parkourability = Parkourability.get(player);
        if (parkourability == null || !parkourability.getActionInfo().can(Crawl.class)) {
            // ParCool 的爬行被客户端设置或服务器限制关闭：本模组不介入，TACZ 保持原行为。
            ARBITER.reset();
            return;
        }

        ProneArbiter.Output output = ARBITER.tick(
                true,
                isParCoolProne(parkourability),
                taczCrawl.crawlfix$isCrawling(),
                taczCrawl.crawlfix$isSynchronized(),
                canCrawlWithCurrentGun(player),
                CrawlFixConfig.getInt(CrawlFixConfig.STAND_DEBOUNCE_TICKS, 2),
                CrawlFixConfig.get(CrawlFixConfig.RESTORE_POSE_ON_END, true)
        );

        if (output.shouldApplyTacz()) {
            taczCrawl.crawlfix$apply(output.taczValue());
        }
        if (output.restorePose()) {
            restorePose(player, parkourability);
        }
    }

    /**
     * ParCool 的爬行在当前配置下是否仍应继续（按住模式看按键，切换模式看 toggleStatus）。
     *
     * <p>供 {@code ParCoolCrawlMixin} 在 {@code Crawl#canContinue} 的 HEAD 处调用，
     * 用于绕开 ParCool 原版“已处于趴下姿势就跳过输入检查”的锁存缺陷。
     */
    public static boolean parcoolCrawlKeyHeld(Crawl crawl) {
        try {
            if (ParCoolConfig.Client.CrawlControl.get() == Crawl.ControlType.Toggle) {
                return crawl.toggleStatus;
            }
            return KeyBindings.getKeyCrawl().isDown();
        } catch (LinkageError | RuntimeException ex) {
            // 判断不了就不要强行结束爬行
            return true;
        }
    }

    /**
     * 是否屏蔽 TACZ 自带的趴下键。仅在 ParCool 的爬行确实可用时接管，
     * 这样 ParCool 被关闭/限制时 TACZ 仍然可以按原样使用。
     */
    public static boolean shouldSuppressTaczCrawlInput() {
        if (!CrawlFixConfig.get(CrawlFixConfig.DISABLE_TACZ_CRAWL_KEY, true)) {
            return false;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        Parkourability parkourability = Parkourability.get(player);
        return parkourability != null && parkourability.getActionInfo().can(Crawl.class);
    }

    /**
     * 供 {@code Player#updatePlayerPose} 的 Mixin 调用：是否应当把本地玩家固定为趴下姿势。
     *
     * <p>只在 ParCool 真的处于爬行/滑铲、姿势放得下、且没有别的模组设置非 SWIMMING 的
     * 强制姿势时接管。
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
        boolean prone = isParCoolProne(parkourability) || ARBITER.isArmed();
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
     * {@code Player#updatePlayerPose} 才会被改写。这里在同一个 tick 内直接纠正，
     * 让“松手即起身”没有延迟，也避免残留姿势被其它模组读到。
     */
    private static void restorePose(LocalPlayer player, Parkourability parkourability) {
        if (player.getPose() != Pose.SWIMMING) {
            return;
        }
        if (player.isInWater() || player.isPassenger() || player.isFallFlying()
                || player.isSleeping() || player.isSpectator()) {
            return;
        }
        // 其它 ParCool 动作（翻滚、翻越、跳水……）也在写姿势，交给它们。
        for (Action action : parkourability.getList()) {
            if (action.isDoing()) {
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
}
