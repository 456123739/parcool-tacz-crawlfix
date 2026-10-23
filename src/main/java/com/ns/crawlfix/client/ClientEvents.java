package com.ns.crawlfix.client;

import com.ns.crawlfix.CrawlFix;
import com.ns.crawlfix.Platform;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端入口：在每个客户端 tick 的末尾做一次状态收敛。
 *
 * <p>选择 {@code ClientTickEvent.END} 的原因：
 * <ul>
 *   <li>TACZ 在 {@code LocalPlayer#tick} 的 HEAD 调用 {@code tickCrawl()}；</li>
 *   <li>ParCool 在 {@code PlayerTickEvent.END} 里推进它的动作状态并写姿势；</li>
 *   <li>两者都发生在客户端 tick 内部，因此 tick 末尾读到的是本 tick 的最终状态，
 *       纠正动作也在同一 tick 内生效，渲染前就已经是正确姿势。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = CrawlFix.MODID, value = Dist.CLIENT)
public final class ClientEvents {

    private ClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // 注意：这里只调用 Platform（不引用 ParCool / TACZ 的类）。
        // 只有两个模组都在时才会真正加载 ProneBridge。
        if (!Platform.activeOnClient()) {
            return;
        }
        ProneBridge.tick(Minecraft.getInstance());
    }
}
