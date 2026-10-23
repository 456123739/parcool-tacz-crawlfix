package com.ns.crawlfix.mixin;

import com.ns.crawlfix.client.CrawlStateAccess;
import com.ns.crawlfix.client.CrawlStateHolder;
import com.tacz.guns.client.gameplay.LocalPlayerCrawl;
import com.tacz.guns.network.NetworkHandler;
import com.tacz.guns.network.message.ClientMessagePlayerCrawl;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管 TACZ 的趴下状态写入。
 *
 * <p>原版实现里会丢“站起来”这条指令的地方：
 * <ul>
 *   <li>{@code crawl(boolean)} 有 10 tick 冷却，快速点按的第二次输入直接被吞；</li>
 *   <li>{@code crawl(boolean)} 要求 {@code onGround}，空中松键时取消包发不出去；</li>
 *   <li>{@code tickCrawl()} 在切枪 / 离地 / 游泳 / 跳起等分支里只清本地状态、不发包，
 *       服务端的强制姿势会残留。</li>
 * </ul>
 * 这里提供一个不受上述前置条件影响的写入入口，并额外记录“服务端被通知到的状态”，
 * 让桥接层能在任何一条分支之后把两边重新对齐。
 */
@Mixin(value = LocalPlayerCrawl.class, remap = false)
public abstract class LocalPlayerCrawlMixin implements CrawlStateAccess {

    @Shadow
    @Final
    private LocalPlayer player;

    @Shadow
    private boolean isCrawling;

    @Shadow
    private int crawCooldownTicks;

    @Shadow
    private void setCrawlPose() {
        throw new AssertionError();
    }

    /** 我们最后一次同步给服务端的状态。 */
    @Unique
    private boolean crawlfix$lastSynced;

    /** 是否已经同步过（避免登录首 tick 发无意义的 false）。 */
    @Unique
    private boolean crawlfix$hasSynced;

    @Inject(method = "tickCrawl", at = @At("HEAD"))
    private void crawlfix$rememberInstance(CallbackInfo ci) {
        CrawlStateHolder.put(this.player, this);
    }

    @Override
    public boolean crawlfix$isCrawling() {
        return this.isCrawling;
    }

    @Override
    public boolean crawlfix$isSynchronized() {
        return this.crawlfix$hasSynced ? this.crawlfix$lastSynced : this.isCrawling;
    }

    @Override
    public void crawlfix$apply(boolean want) {
        boolean localChanged = this.isCrawling != want;
        this.isCrawling = want;
        // 清掉 10 tick 冷却：之后的纠正/按键都应立即生效。
        this.crawCooldownTicks = 0;
        this.setCrawlPose();
        if (!this.crawlfix$hasSynced || this.crawlfix$lastSynced != want || localChanged) {
            NetworkHandler.CHANNEL.sendToServer(new ClientMessagePlayerCrawl(want));
            this.crawlfix$hasSynced = true;
            this.crawlfix$lastSynced = want;
        }
        // 防御：TACZ 的 setCrawlPose 在 want=false 时会把 forcedPose 置空，
        // 但若它的内部状态已经提前清掉（上面提到的分支），这里再显式确认一次。
        if (!want && this.player.getForcedPose() == Pose.SWIMMING) {
            this.player.setForcedPose(null);
        }
    }
}
