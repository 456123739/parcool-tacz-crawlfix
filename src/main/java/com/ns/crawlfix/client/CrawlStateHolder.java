package com.ns.crawlfix.client;

import net.minecraft.client.player.LocalPlayer;

/**
 * 记录本地玩家的 {@code LocalPlayerCrawl} 实例。
 *
 * <p>TACZ 把它放在 {@code LocalPlayer} 的 Mixin 私有字段里，外部拿不到；
 * 我们在 {@code LocalPlayerCrawl#tickCrawl} 的 HEAD 处登记一次（每 tick 都刷新），
 * 之后由客户端 tick 末尾的仲裁器取用。实例与玩家一一对应，玩家对象换掉时自然失效。
 */
public final class CrawlStateHolder {

    private static LocalPlayer player;
    private static CrawlStateAccess access;

    private CrawlStateHolder() {
    }

    public static void put(LocalPlayer owner, CrawlStateAccess state) {
        player = owner;
        access = state;
    }

    public static CrawlStateAccess get(LocalPlayer owner) {
        return owner == player ? access : null;
    }

    public static void clear() {
        player = null;
        access = null;
    }
}
