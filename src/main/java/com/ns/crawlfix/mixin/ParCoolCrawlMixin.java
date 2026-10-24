package com.ns.crawlfix.mixin;

import com.alrex.parcool.common.action.impl.Crawl;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.ns.crawlfix.client.ProneBridge;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * 让 ParCool 的爬行真正“按住才趴、松手必起”。
 *
 * <p>修两件事：
 *
 * <h3>1. 姿态锁存（ParCool 3.3.1.0 的真实缺陷）</h3>
 * {@code Crawl#canContinue} 的输入检查被包在 {@code if (!player.isVisuallyCrawling())} 里面：
 * 只要玩家姿势是 {@code SWIMMING}（且不在水里），松手检查就被整个跳过，爬行永不结束。
 * 单独用 ParCool 时原版每 tick 会把姿势算回 {@code STANDING}，缺陷被掩盖；
 * TACZ 用 {@code setForcedPose(SWIMMING)} 钉住姿势后就会“按一下永久趴下”。
 * 这里把输入检查提升为无条件执行。
 *
 * <h3>2. 切换模式</h3>
 * 若 ParCool 被配置为 {@code Toggle}，它的语义是“按一下趴、再按一下起”，
 * 与 TACZ 的趴下/滑铲组合会产生“松手仍趴着 / 站起又立刻趴下”。
 * 默认强制按住式（{@code force_hold_to_crawl=true}）：屏蔽 toggle 翻转，
 * 由趴下键的按下状态直接决定 canStart/canContinue。
 */
@Mixin(value = Crawl.class, remap = false)
public abstract class ParCoolCrawlMixin {

    /** 强制按住式时，禁掉 ParCool 自己的 toggle 翻转。 */
    @Inject(method = "onClientTick", at = @At("HEAD"), cancellable = true)
    private void crawlfix$disableToggleFlip(Player player,
                                            Parkourability parkourability,
                                            IStamina stamina,
                                            CallbackInfo ci) {
        if (ProneBridge.forceHoldToCrawl()) {
            ci.cancel();
        }
    }

    /** 强制按住式时，用“键是否按下”驱动 Toggle 模式的 canStart。 */
    @Inject(method = "canStart", at = @At("HEAD"))
    private void crawlfix$syncToggleStatus(Player player,
                                           Parkourability parkourability,
                                           IStamina stamina,
                                           ByteBuffer startInfo,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (ProneBridge.forceHoldToCrawl()) {
            ((Crawl) (Object) this).toggleStatus = ProneBridge.crawlKeyDown();
        }
    }

    /** 松手必起：无条件执行输入检查，并在强制按住式下同步 toggleStatus。 */
    @Inject(method = "canContinue", at = @At("HEAD"), cancellable = true)
    private void crawlfix$enforceCrawlInput(Player player,
                                            Parkourability parkourability,
                                            IStamina stamina,
                                            CallbackInfoReturnable<Boolean> cir) {
        Crawl crawl = (Crawl) (Object) this;
        if (ProneBridge.forceHoldToCrawl()) {
            boolean down = ProneBridge.crawlKeyDown();
            crawl.toggleStatus = down;
            if (!down) {
                cir.setReturnValue(false);
            }
            return;
        }
        if (!player.isLocalPlayer()) {
            return;
        }
        if (!ProneBridge.parcoolCrawlKeyHeld(crawl)) {
            cir.setReturnValue(false);
        }
    }
}
