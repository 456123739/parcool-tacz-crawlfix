package com.ns.crawlfix.mixin;

import com.ns.crawlfix.client.ProneBridge;
import com.tacz.guns.client.input.CrawlKey;
import net.minecraftforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 两个模组同时存在时，趴下输入统一交给 ParCool 的趴下键。
 *
 * <p>TACZ 自带的按键是“切换式”（按一次趴下，再按一次起身）且带 10 tick 冷却，而
 * ParCool 的爬行是“按住式”。同一个默认按键（C）同时驱动两套语义不同的状态机，
 * 就是状态错相、站不起来的直接来源。屏蔽它之后，趴下只有一份输入、一份状态。
 *
 * <p>仅当 ParCool 的爬行动作确实可用时才屏蔽；ParCool 被限制关闭时 TACZ 保持原样。
 */
@Mixin(value = CrawlKey.class, remap = false)
public class CrawlKeyMixin {

    @Inject(method = "onCrawlPress", at = @At("HEAD"), cancellable = true)
    private static void crawlfix$suppressKeyboardInput(InputEvent.Key event, CallbackInfo ci) {
        if (ProneBridge.shouldSuppressTaczCrawlInput()) {
            ci.cancel();
        }
    }

    @Inject(method = "onCrawlControllerPress", at = @At("HEAD"), cancellable = true)
    private static void crawlfix$suppressControllerInput(boolean pressed, CallbackInfoReturnable<Boolean> cir) {
        if (ProneBridge.shouldSuppressTaczCrawlInput()) {
            cir.setReturnValue(false);
        }
    }
}
