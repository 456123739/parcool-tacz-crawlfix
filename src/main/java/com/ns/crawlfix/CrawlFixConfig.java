package com.ns.crawlfix;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端配置。所有选项都只影响本地玩家，不同步到服务端。
 */
public final class CrawlFixConfig {

    public static final ForgeConfigSpec SPEC;

    /** 总开关。关闭后本模组等同于不存在。 */
    public static final ForgeConfigSpec.BooleanValue ENABLED;

    /** ParCool 的趴下结束时，是否立刻把残留的 SWIMMING 姿势纠正回站立/潜行。 */
    public static final ForgeConfigSpec.BooleanValue RESTORE_POSE_ON_END;

    /** 是否让 ParCool 的趴下键独占趴下输入（屏蔽 TACZ 自带的切换式按键）。 */
    public static final ForgeConfigSpec.BooleanValue DISABLE_TACZ_CRAWL_KEY;

    /** 松开趴下键后保持趴下的 tick 数（消除滑铲再点一下造成的 1 tick 站/趴闪烁）。 */
    public static final ForgeConfigSpec.IntValue STAND_DEBOUNCE_TICKS;

    /** 是否输出状态切换日志（排查问题时打开）。 */
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    /** 强制“按住式”趴下（即使 ParCool 配的是 Toggle 模式）。 */
    public static final ForgeConfigSpec.BooleanValue FORCE_HOLD_TO_CRAWL;

    /** 空闲时若姿势仍是 SWIMMING，强制恢复站立/潜行。 */
    public static final ForgeConfigSpec.BooleanValue ENFORCE_STANDING;

    /** 清理别的来源残留的 SWIMMING 强制姿势。 */
    public static final ForgeConfigSpec.BooleanValue CLEAR_STALE_FORCED_POSE;

    private CrawlFixConfig() {
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment(
                "ParCool x TACZ crawl fix",
                "Fixes the stuck-prone / prone-standing flutter when ParCool's crawl/slide and TACZ's prone share one key.",
                "Every option is client-side only."
        );
        builder.push("general");
        ENABLED = builder
                .comment("Master switch. When false the mod does nothing at all.")
                .define("enabled", true);
        RESTORE_POSE_ON_END = builder
                .comment("When ParCool's crawl/slide ends, immediately restore the standing/crouching pose",
                        "instead of waiting for the next vanilla pose update tick.",
                        "Only touches a leftover SWIMMING pose that this mod previously owned.")
                .define("restore_pose_on_end", true);
        DISABLE_TACZ_CRAWL_KEY = builder
                .comment("Let ParCool's crawl key own the prone input while both mods are installed.",
                        "This removes TACZ's latched toggle and its 10 tick input cooldown from the picture.",
                        "Set to false only if you deliberately want TACZ's own toggle to keep running.")
                .define("disable_tacz_crawl_key", true);
        DEBUG_LOG = builder
                .comment("Log prone state transitions to the game log (for troubleshooting).")
                .define("debug_log", false);
        FORCE_HOLD_TO_CRAWL = builder
                .comment("Always use hold-to-crawl semantics, even if ParCool is configured as Toggle.",
                        "With TACZ installed, ParCool's Toggle mode is a common source of",
                        "'released the key but still prone' and 'stood up then went prone again'.",
                        "Set to false to keep ParCool's Toggle behaviour untouched.")
                .define("force_hold_to_crawl", true);
        ENFORCE_STANDING = builder
                .comment("Every tick, if no prone source is active and the crawl key is released,",
                        "force the player out of a leftover SWIMMING pose (when it is safe to stand).")
                .define("enforce_standing", true);
        CLEAR_STALE_FORCED_POSE = builder
                .comment("Clear a leftover forced pose of SWIMMING that no active prone source owns.")
                .define("clear_stale_forced_pose", true);
        STAND_DEBOUNCE_TICKS = builder
                .comment("How many ticks the prone state is kept after the crawl state ends.",
                        "ParCool's slide is entered by tapping the crawl key again; that tap makes Crawl",
                        "end for exactly one tick. A value of 1 hides that gap without a noticeable delay",
                        "when the player really releases the key. 0 disables the debounce.")
                .defineInRange("stand_debounce_ticks", 1, 0, 5);
        builder.pop();
        SPEC = builder.build();
    }

    public static int getInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    public static boolean get(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return SPEC.isLoaded() ? value.get() : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
