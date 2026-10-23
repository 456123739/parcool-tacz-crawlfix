package com.ns.crawlfix.mixin;

import com.alrex.parcool.common.action.impl.Crawl;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.ns.crawlfix.client.ProneBridge;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修掉 ParCool 爬行的“姿态锁存”。
 *
 * <p>ParCool 3.3.1.0 的 {@code Crawl#canContinue} 长这样：
 *
 * <pre>{@code
 * if (!player.isVisuallyCrawling()) {          // 注意这个条件
 *     switch (CrawlControl.get()) {
 *         case Toggle:   if (!toggleStatus) return false; break;
 *         case PressKey: if (!KeyBindings.getKeyCrawl().isDown()) return false;
 *     }
 * }
 * ...
 * }</pre>
 *
 * <p>{@code isVisuallyCrawling()} 的判定是“姿势为 SWIMMING 且不在水里”。也就是说：
 * <b>一旦玩家当前已经被摆成趴下姿势，松开按键的检查就被整个跳过，爬行永远不会结束。</b>
 * 单独使用 ParCool 时，原版 {@code updatePlayerPose} 会在动作结算前把姿势算回 STANDING，
 * 所以这个缺陷被掩盖了；而 TACZ 用 {@code setForcedPose(SWIMMING)} 每 tick 把姿势钉住，
 * 于是“按一下 C 就永久趴下、松手也起不来”。
 *
 * <p>这里把输入检查提升为无条件执行（只对本地玩家；服务端的动作状态由 ParCool 自己同步）。
 * 这样无论谁在维持 SWIMMING 姿势，松开趴下键都一定会结束爬行。
 */
@Mixin(value = Crawl.class, remap = false)
public abstract class ParCoolCrawlMixin {

    @Inject(method = "canContinue", at = @At("HEAD"), cancellable = true)
    private void crawlfix$enforceCrawlInput(Player player,
                                            Parkourability parkourability,
                                            IStamina stamina,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (!player.isLocalPlayer()) {
            return;
        }
        if (!ProneBridge.parcoolCrawlKeyHeld((Crawl) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}
