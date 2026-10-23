package com.ns.crawlfix;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * Mixin 插件使用的“模组是否存在”判定。
 *
 * <p>Mixin 的应用时机远早于模组构造，普通 {@link ModList} 这时可能还没准备好，
 * 因此优先查询 FML 的 {@link LoadingModList}（它来自 mods 目录扫描结果）。
 */
public final class MixinSupport {

    private MixinSupport() {
    }

    public static boolean bothModsPresent() {
        return present(Platform.PARCOOL) && present(Platform.TACZ);
    }

    private static boolean present(String modId) {
        LoadingModList loading = LoadingModList.get();
        if (loading != null && loading.getModFileById(modId) != null) {
            return true;
        }
        ModList loaded = ModList.get();
        return loaded != null && loaded.isLoaded(modId);
    }
}
