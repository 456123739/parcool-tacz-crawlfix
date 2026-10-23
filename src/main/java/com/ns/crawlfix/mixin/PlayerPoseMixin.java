package com.ns.crawlfix.mixin;

import com.ns.crawlfix.client.ProneBridge;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让“趴下”成为原版姿势计算的唯一结果，而不是每 tick 被改两次。
 *
 * <p>背景：ParCool 在 {@code PlayerTickEvent.END} 里直接 {@code setPose(SWIMMING)}，
 * 这个时机在原版 {@code updatePlayerPose()} **之后**。于是每个 tick 都会发生：
 * <ol>
 *   <li>原版按“没游泳、没潜行”算成 {@code STANDING}；</li>
 *   <li>ParCool 再写回 {@code SWIMMING}。</li>
 * </ol>
 * 只有 ParCool 时最终渲染状态是稳定的，但只要第二个写入者（TACZ 的 {@code forcedPose}）
 * 状态不同步，就会表现为站立/趴下抽搐、碰撞箱与眼高抖动。
 *
 * <p>这里在 {@code updatePlayerPose} 的 HEAD 处直接接管：只要 ParCool 仍处于爬行/滑铲，
 * 就让原版算出 {@code SWIMMING} 并跳过其余逻辑。这样：
 * <ul>
 *   <li>姿势在整个 tick 内稳定，不依赖 TACZ 是否也在趴下（空手、空中爬行同样稳定）；</li>
 *   <li>趴下结束后原版逻辑立刻恢复正常，配合桥接层的立即纠正不会残留；</li>
 *   <li>只在本地玩家、且姿势确实放得下时接管；别的模组设置的强制姿势（非 SWIMMING）不碰。</li>
 * </ul>
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {

    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void crawlfix$stablePronePose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (ProneBridge.shouldForcePronePose(player)) {
            player.setPose(Pose.SWIMMING);
            ci.cancel();
        }
    }
}
