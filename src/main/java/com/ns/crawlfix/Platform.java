package com.ns.crawlfix;

import net.minecraftforge.fml.ModList;

/**
 * 模组存在性 / 总开关判定。
 *
 * <p>这里刻意不引用 ParCool、TACZ 或任何客户端类，保证在缺少其中任意一个模组、
 * 甚至专用服务端环境下也能安全加载。
 */
public final class Platform {

    public static final String PARCOOL = "parcool";
    public static final String TACZ = "tacz";

    private Platform() {
    }

    /** 两个模组是否都装了（任意环境可调用）。 */
    public static boolean bothModsLoaded() {
        ModList list = ModList.get();
        return list != null && list.isLoaded(PARCOOL) && list.isLoaded(TACZ);
    }

    /** 修复逻辑是否应当在客户端运行。 */
    public static boolean activeOnClient() {
        return bothModsLoaded() && CrawlFixConfig.get(CrawlFixConfig.ENABLED, true);
    }
}
