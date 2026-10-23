package com.ns.crawlfix.arbiter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实报告里的临界情况验证仲裁逻辑。
 *
 * <p>每个用例都模拟“连续若干 tick 的输入/状态”，检查最终是否一定回到站立。
 */
class ProneArbiterTest {

    /** 模拟一整套客户端侧状态。 */
    private static final class Sim {
        final ProneArbiter arbiter = new ProneArbiter();
        boolean tacz;
        boolean taczSynced;
        boolean lastRestore;

        /** @param parcoolProne ParCool 趴下中 @param gun 主手允许趴下 @param debounce 防抖 tick 数 */
        ProneArbiter.Output tick(boolean parcoolProne, boolean gun, int debounce) {
            ProneArbiter.Output out = arbiter.tick(true, parcoolProne, tacz, taczSynced, gun, debounce, true);
            if (out.shouldApplyTacz()) {
                tacz = out.taczValue();
                taczSynced = out.taczValue();
            }
            lastRestore = out.restorePose();
            return out;
        }
    }

    @Test
    @DisplayName("快速点一下趴下键：ParCool 只趴 1 tick，TACZ 也必须只趴 1 tick")
    void quickTapDoesNotLatch() {
        Sim sim = new Sim();
        sim.tick(true, true, 1);
        assertTrue(sim.tacz, "按下的同一 tick 应把 TACZ 也切到趴下");

        ProneArbiter.Output releaseTick1 = sim.tick(false, true, 1);
        assertTrue(sim.tacz, "防抖窗口内保持趴下，避免 1 tick 闪烁");

        ProneArbiter.Output releaseTick2 = sim.tick(false, true, 1);
        assertFalse(sim.tacz, "防抖结束后 TACZ 必须退出趴下（旧实现会一直锁存）");
        assertTrue(releaseTick2.restorePose(), "下降沿应触发姿势纠正");
        assertFalse(releaseTick1.restorePose());
    }

    @Test
    @DisplayName("防抖为 0 时立即站起")
    void zeroDebounceStandsImmediately() {
        Sim sim = new Sim();
        sim.tick(true, true, 0);
        ProneArbiter.Output out = sim.tick(false, true, 0);
        assertFalse(sim.tacz);
        assertTrue(out.restorePose());
    }

    @Test
    @DisplayName("连续快速点按：每次松手都回到站立，不会出现状态错相")
    void repeatedTapsAlwaysReturnToStanding() {
        Sim sim = new Sim();
        for (int i = 0; i < 10; i++) {
            sim.tick(true, true, 1);
            assertTrue(sim.tacz, "第 " + i + " 次按下后 TACZ 应在趴下");
            sim.tick(false, true, 1);
            sim.tick(false, true, 1);
            assertFalse(sim.tacz, "第 " + i + " 次松手后 TACZ 必须站起");
        }
    }

    @Test
    @DisplayName("爬行中再点一下（松开 1 tick）：TACZ 不允许掉线，也不允许发包")
    void retapGapKeepsTaczState() {
        Sim sim = new Sim();
        sim.tick(true, true, 1);
        ProneArbiter.Output gap = sim.tick(false, true, 1);   // 松开的那个 tick
        assertFalse(gap.shouldApplyTacz(), "1 tick 的松开不应该产生一对无意义的同步包");
        assertTrue(sim.tacz);
        ProneArbiter.Output again = sim.tick(true, true, 1);
        assertFalse(again.shouldApplyTacz());
        assertTrue(sim.tacz);
    }

    @Test
    @DisplayName("滑铲尾部 1 tick 内松开：滑铲与爬行同时结束也能站起")
    void releaseAtSlideToCrawlBoundary() {
        Sim sim = new Sim();
        sim.tick(true, true, 1);
        ProneArbiter.Output slideEnded = sim.tick(true, true, 1);
        assertFalse(slideEnded.shouldApplyTacz(), "状态没有变化时不该发无意义的同步包");

        sim.tick(false, true, 1);
        ProneArbiter.Output released = sim.tick(false, true, 1);
        assertTrue(released.shouldApplyTacz());
        assertFalse(sim.tacz);
        assertTrue(released.restorePose());
    }

    @Test
    @DisplayName("ParCool 趴下但手里没有可趴下的枪：TACZ 保持站立且不发包")
    void noGunKeepsTaczStanding() {
        Sim sim = new Sim();
        ProneArbiter.Output output = sim.tick(true, false, 1);
        assertFalse(output.shouldApplyTacz());
        assertFalse(sim.tacz);
    }

    @Test
    @DisplayName("TACZ 本地残留趴下、服务端认知不一致：立即清掉")
    void staleTaczProneIsCleared() {
        Sim sim = new Sim();
        sim.tacz = true;
        sim.taczSynced = false;
        ProneArbiter.Output output = sim.tick(false, true, 1);
        assertTrue(output.shouldApplyTacz());
        assertFalse(sim.tacz);
        assertFalse(output.restorePose(), "本模组没管过趴下时不应擅自改姿势");
    }

    @Test
    @DisplayName("TACZ 本地已清但服务端仍是趴下（tickCrawl 静默清状态）：必须补发取消包")
    void serverOnlyStaleStateIsResynced() {
        Sim sim = new Sim();
        sim.tick(true, true, 0);
        assertTrue(sim.taczSynced);

        sim.tacz = false;            // 模拟 TACZ 自己静默清掉了本地状态（没发包）
        ProneArbiter.Output output = sim.tick(false, true, 0);
        assertTrue(output.shouldApplyTacz(), "服务端认知不一致时必须补发");
        assertFalse(sim.taczSynced, "补发之后服务端认知应更新为站立");
    }

    @Test
    @DisplayName("ParCool 仍趴下、TACZ 本地被静默清掉：恢复本地状态且不重复发包")
    void silentLocalClearWhileProneIsRestored() {
        Sim sim = new Sim();
        sim.tick(true, true, 1);
        sim.tacz = false;
        ProneArbiter.Output output = sim.tick(true, true, 1);
        assertTrue(output.shouldApplyTacz());
        assertTrue(sim.tacz, "应恢复 TACZ 本地趴下状态");
        assertTrue(sim.taczSynced, "服务端本来就知道是趴下，不需要再次发包");
    }

    @Test
    @DisplayName("ParCool 爬行被服务器限制关闭：本模组完全退出，不碰 TACZ")
    void inactiveWhenParCoolCrawlDisabled() {
        ProneArbiter arbiter = new ProneArbiter();
        ProneArbiter.Output output = arbiter.tick(false, false, true, true, true, 1, true);
        assertFalse(output.shouldApplyTacz());
        assertFalse(output.restorePose());
    }
}
