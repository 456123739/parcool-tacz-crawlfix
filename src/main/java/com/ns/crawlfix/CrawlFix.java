package com.ns.crawlfix;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/**
 * ParCool x TACZ 趴下状态协调器。
 *
 * <p>本模组只做一件事：当 ParCool 与 TACZ 同时存在时，把「趴下 / 爬行」这一状态收敛成
 * 唯一的一份，并保证松开趴下键后玩家一定能站起来。它不替换、不重写任何一个模组的玩法
 * 逻辑；两个模组缺少任意一个时，本模组完全不做事（既没有 Mixin，也没有事件监听）。
 */
@Mod(CrawlFix.MODID)
public final class CrawlFix {

    public static final String MODID = "crawlfix";

    public CrawlFix() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CrawlFixConfig.SPEC);
    }
}
