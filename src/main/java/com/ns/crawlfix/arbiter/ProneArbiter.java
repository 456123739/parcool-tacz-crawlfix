package com.ns.crawlfix.arbiter;

/**
 * 纯粹的状态仲裁逻辑（不依赖 Minecraft，便于单元测试）。
 *
 * <p>不变量：<b>TACZ 的“趴下”状态必须与 ParCool 的趴下/滑铲状态一致</b>，
 * 并且这个一致性同时覆盖“本地状态”和“服务端已被告知的状态”。
 *
 * <p>另外内置一个极短的“离地防抖”（默认 1 tick）：ParCool 的滑铲需要松开再按下趴下键，
 * 松开的那个 tick 里 {@code Crawl} 会短暂结束。若立刻把 TACZ 关掉并恢复站姿，
 * 玩家会看到一次 1 tick 的站/趴闪烁，同时产生一对无意义的网络包。
 * 防抖窗口内保持趴下，既消除闪烁，也不会让正常松手明显延迟（默认只延迟 1 tick，约 50ms）。
 */
public final class ProneArbiter {

    /** 一次 tick 的决策结果。 */
    public record Output(boolean updateTacz, boolean taczValue, boolean restorePose) {

        public static final Output NONE = new Output(false, false, false);

        public boolean shouldApplyTacz() {
            return updateTacz;
        }
    }

    /** 当前是否“保持在趴下”（包含防抖窗口）。 */
    private boolean armed;

    /** 连续未趴下的 tick 数。 */
    private int offTicks;

    public void reset() {
        armed = false;
        offTicks = 0;
    }

    public boolean isArmed() {
        return armed;
    }

    /**
     * @param parcoolAllowed     ParCool 的 Crawl 动作当前是否可用（被限制关闭时返回 false）
     * @param parcoolProne       ParCool 的 Crawl 或 Slide 是否正在执行
     * @param taczCrawling       TACZ 本地趴下状态
     * @param taczSynchronized   TACZ 认为“服务端已知”的趴下状态
     * @param canCrawlWithGun    当前主手武器是否允许 TACZ 进入趴下
     * @param debounceTicks      松开后保持趴下的 tick 数（0 表示不防抖）
     * @param restorePoseEnabled 配置项：趴下结束时是否立即恢复姿势
     */
    public Output tick(boolean parcoolAllowed,
                       boolean parcoolProne,
                       boolean taczCrawling,
                       boolean taczSynchronized,
                       boolean canCrawlWithGun,
                       int debounceTicks,
                       boolean restorePoseEnabled) {
        if (!parcoolAllowed) {
            // ParCool 的爬行被关掉了：本模组完全退出，TACZ 保持它自己的行为。
            reset();
            return Output.NONE;
        }

        boolean wasArmed = armed;
        if (parcoolProne) {
            offTicks = 0;
            armed = true;
        } else if (armed && ++offTicks > Math.max(0, debounceTicks)) {
            armed = false;
        }

        boolean wantTacz = armed && canCrawlWithGun;
        boolean updateTacz = taczCrawling != wantTacz || taczSynchronized != wantTacz;
        boolean restorePose = wasArmed && !armed && restorePoseEnabled;
        return new Output(updateTacz, wantTacz, restorePose);
    }
}
