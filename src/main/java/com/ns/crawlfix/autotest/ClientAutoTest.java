package com.ns.crawlfix.autotest;

import com.alrex.parcool.client.input.KeyBindings;
import com.alrex.parcool.common.action.impl.Crawl;
import com.alrex.parcool.common.action.impl.Slide;
import com.alrex.parcool.common.capability.Parkourability;
import com.ns.crawlfix.CrawlFix;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅用于开发的真实客户端自动化测试（发布 JAR 中不包含本包）。
 *
 * <p>在真实 Minecraft 客户端里创建超平坦世界，用真实的 {@link net.minecraft.client.KeyMapping}
 * 模拟趴下键，逐 tick 检查 ParCool / TACZ / 玩家姿势三者是否满足修复目标。
 *
 * <p>启用方式：{@code -Dcrawlfix.autotest=true}。
 */
@Mod.EventBusSubscriber(modid = CrawlFix.MODID, value = Dist.CLIENT)
public final class ClientAutoTest {

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("crawlfix.autotest", "false"));
    private static final int GLOBAL_TIMEOUT_TICKS = 20 * 240;

    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;

    private static boolean worldRequested;
    private static int bootTicks;
    private static int ticksInWorld;
    private static int stepIndex;
    private static int stepTick;
    private static boolean gunGiven;
    private static boolean finished;
    private static boolean ready;
    private static int readyTicks;

    // 逐 tick 采样（用于检查姿势抖动 / 状态掉线）
    private static boolean sampling;
    private static String samplingLabel;
    private static int sampledPoseBad;
    private static int sampledTaczDrops;
    private static boolean lastSampledTacz;
    private static final List<String> badPoseDetails = new ArrayList<>();

    private static final List<Step> STEPS = buildSteps();

    private record Step(String name, int ticks, boolean crawl, boolean fastRun, boolean jump, Runnable check) {
    }

    private ClientAutoTest() {
    }

    /**
     * 输入必须在 ParCool 的 KeyRecorder（NORMAL）之前写入，才能模拟真实的“本 tick 按下”；
     * 真实客户端里 GLFW 事件也是在客户端 tick 开始前处理的。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTickStart(TickEvent.ClientTickEvent event) {
        if (!ENABLED || finished || event.phase != TickEvent.Phase.START) {
            return;
        }
        onTickStart();
    }

    /** 断言放在最后，确保能看到本 tick 修复层的最终结果。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onClientTickEnd(TickEvent.ClientTickEvent event) {
        if (!ENABLED || finished || event.phase != TickEvent.Phase.END) {
            return;
        }
        onTickEnd();
    }

    private static void onTickStart() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        Step step = currentStep();
        if (step != null) {
            applyInputs(mc, step);
        }
    }

    private static void onTickEnd() {
        Minecraft mc = Minecraft.getInstance();
        bootTicks++;

        if (mc.player == null || mc.level == null) {
            if (mc.screen != null && !worldRequested && bootTicks > 80) {
                createWorld(mc);
            }
            if (bootTicks > GLOBAL_TIMEOUT_TICKS / 2) {
                fail("world", "客户端在超时前没有进入世界（screen=" + mc.screen + "）");
                finish();
            }
            return;
        }

        ticksInWorld++;
        if (ticksInWorld > GLOBAL_TIMEOUT_TICKS) {
            fail("timeout", "测试整体超时，卡在步骤 " + (currentStep() == null ? "<done>" : currentStep().name()));
            finish();
            return;
        }

        if (ticksInWorld < 20) {
            prepareClient(mc);
            return;
        }

        if (!gunGiven) {
            giveGun(mc.player);
            gunGiven = true;
            return;
        }

        if (!ready) {
            // 等 ParCool 的限制同步完成、TACZ 的枪械索引加载完成、玩家稳定站在地面，
            // 否则第一组用例可能跑在模组还没就绪的时候（CI 上尤其明显）。
            if (isReady(mc.player)) {
                if (++readyTicks >= 10) {
                    ready = true;
                    log("setup", "ParCool / TACZ 已就绪，开始测试");
                }
            } else {
                readyTicks = 0;
            }
            if (ticksInWorld > 400) {
                fail("setup", "等待 ParCool / TACZ 就绪超时");
                finish();
            }
            return;
        }

        if (sampling) {
            sample(mc.player);
        }

        Step step = currentStep();
        if (step == null) {
            finish();
            return;
        }
        stepTick++;
        if (stepTick >= step.ticks()) {
            if (step.check() != null) {
                try {
                    step.check().run();
                    pass(step.name());
                } catch (AssertionError error) {
                    fail(step.name(), error.getMessage());
                }
            } else {
                pass(step.name());
            }
            stepIndex++;
            stepTick = 0;
        }
    }

    private static void applyInputs(Minecraft mc, Step step) {
        KeyBindings.getKeyCrawl().setDown(step.crawl());
        KeyBindings.getKeyFastRunning().setDown(step.fastRun());
        mc.options.keySprint.setDown(step.fastRun());
        mc.options.keyUp.setDown(step.fastRun());
        mc.options.keyJump.setDown(step.jump());
    }

    private static void sample(LocalPlayer player) {
        boolean poseProne = player.getPose() == Pose.SWIMMING;
        if (!poseProne) {
            sampledPoseBad++;
            if (badPoseDetails.size() < 6) {
                badPoseDetails.add("step=" + (currentStep() == null ? "?" : currentStep().name())
                        + " tick=" + stepTick
                        + " pose=" + player.getPose()
                        + " forced=" + player.getForcedPose()
                        + " parcool=" + parcoolProne(player)
                        + " tacz=" + IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl()
                        + " onGround=" + player.onGround()
                        + " key=" + com.alrex.parcool.client.input.KeyBindings.getKeyCrawl().isDown());
            }
        }
        boolean tacz = IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();
        if (lastSampledTacz && !tacz && parcoolProne(player)) {
            sampledTaczDrops++;
        }
        lastSampledTacz = tacz;
    }

    private static void startSampling(String label) {
        sampling = true;
        samplingLabel = label;
        sampledPoseBad = 0;
        sampledTaczDrops = 0;
        badPoseDetails.clear();
        lastSampledTacz = true;
    }

    private static void stopSamplingAndAssert(String label, boolean expectPoseProne, boolean expectTaczStable) {
        sampling = false;
        if (expectPoseProne && sampledPoseBad > 0) {
            throw new AssertionError(label + " 期间有 " + sampledPoseBad + " 个 tick 姿势不是 SWIMMING（姿势抖动）: "
                    + badPoseDetails);
        }
        if (expectTaczStable && sampledTaczDrops > 0) {
            throw new AssertionError(label + " 期间 TACZ 趴下状态掉了 " + sampledTaczDrops + " 次（状态错相/闪烁）");
        }
    }

    private static boolean isReady(LocalPlayer player) {
        Parkourability parkourability = Parkourability.get(player);
        if (parkourability == null || !parkourability.getActionInfo().can(Crawl.class)) {
            return false;
        }
        if (!player.onGround() || player.getPose() == Pose.SWIMMING) {
            return false;
        }
        try {
            return com.tacz.guns.api.TimelessAPI.getClientGunIndex(new ResourceLocation("tacz", "ak47")).isPresent();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static void prepareClient(Minecraft mc) {
        mc.options.pauseOnLostFocus = false;
        mc.options.renderDistance().set(2);
        mc.options.enableVsync().set(false);
    }

    private static void createWorld(Minecraft mc) {
        worldRequested = true;
        try {
            LevelSettings settings = new LevelSettings(
                    "crawlfix_autotest",
                    GameType.CREATIVE,
                    false,
                    Difficulty.PEACEFUL,
                    true,
                    new GameRules(),
                    WorldDataConfiguration.DEFAULT
            );
            WorldOptions options = new WorldOptions(42424242L, false, false);
            mc.createWorldOpenFlows().createFreshLevel(
                    "crawlfix_autotest",
                    settings,
                    options,
                    access -> access.registryOrThrow(Registries.WORLD_PRESET)
                            .getOrThrow(WorldPresets.FLAT)
                            .createWorldDimensions()
            );
            log("world", "已请求创建超平坦测试世界");
        } catch (Throwable throwable) {
            fail("world", "创建世界失败: " + throwable);
            finish();
        }
    }

    private static void giveGun(LocalPlayer player) {
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, GunItemBuilder.create()
                    .setId(new ResourceLocation("tacz", "ak47"))
                    .setCount(1)
                    .setAmmoCount(30)
                    .forceBuild());
            log("setup", "已把 tacz:ak47 放进主手");
        } catch (Throwable throwable) {
            fail("setup", "无法生成 TACZ 枪械: " + throwable);
        }
    }

    private static Step currentStep() {
        return stepIndex < STEPS.size() ? STEPS.get(stepIndex) : null;
    }

    // ------------------------------------------------------------------
    // 测试步骤
    // ------------------------------------------------------------------

    private static List<Step> buildSteps() {
        List<Step> steps = new ArrayList<>();

        // 1. 快速点一下趴下键：松手后必须站起
        steps.add(new Step("tap_press", 1, true, false, false, null));
        steps.add(new Step("tap_release_stands_up", 6, false, false, false,
                () -> assertStanding("快速点按后松手")));

        // 2. 按住趴下键：应进入趴下、TACZ 同步，并且全程姿势不得抖动
        steps.add(new Step("hold_start", 1, true, false, false, () -> startSampling("长按趴下")));
        steps.add(new Step("hold_monitor", 24, true, false, false, () -> {
            stopSamplingAndAssert("长按趴下", true, true);
            assertProneAndSynced();
        }));
        steps.add(new Step("hold_release_stands_up", 6, false, false, false,
                () -> assertStanding("长按后松手")));

        // 3. 连续快速点按 10 次（旧实现会在这里错相/抽搐）
        for (int i = 0; i < 10; i++) {
            final int round = i + 1;
            steps.add(new Step("repeat_tap_" + round + "_down", 1, true, false, false, null));
            steps.add(new Step("repeat_tap_" + round + "_up", 4, false, false, false,
                    () -> assertStanding("第 " + round + " 次快速点按")));
        }

        // 4. 爬行中“再点一下”（松开 2 tick 再按）：趴下状态不得掉线、姿势不得闪
        steps.add(new Step("retap_hold", 12, true, false, false, null));
        steps.add(new Step("retap_monitor_start", 1, true, false, false, () -> startSampling("爬行中再点一下")));
        steps.add(new Step("retap_gap", 2, false, false, false, null));
        steps.add(new Step("retap_press_again", 10, true, false, false, () -> {
            stopSamplingAndAssert("爬行中再点一下", true, true);
            assertProneAndSynced();
        }));
        steps.add(new Step("retap_release", 6, false, false, false,
                () -> assertStanding("爬行中再点一下之后松手")));

        // 5. 冲刺 + 疾跑 + 趴下 -> 触发滑铲；滑铲中途松手
        steps.add(new Step("fastrun_build_up", 25, false, true, false, null));
        steps.add(new Step("slide_start_press", 1, true, true, false, null));
        steps.add(new Step("slide_monitor_start", 1, true, true, false, () -> startSampling("滑铲")));
        steps.add(new Step("slide_monitor", 8, true, true, false, () -> {
            stopSamplingAndAssert("滑铲", true, true);
            assertProneAndSynced();
        }));
        steps.add(new Step("slide_release_mid", 6, false, false, false,
                () -> assertStanding("滑铲中途松手")));

        // 6. 再次滑铲并一直按到滑铲自然结束（滑铲 -> 爬行），随后松手
        steps.add(new Step("slide2_fastrun", 25, false, true, false, null));
        steps.add(new Step("slide2_start", 1, true, true, false, null));
        steps.add(new Step("slide2_hold", 70, true, false, false, () -> {
            assertProneAndSynced();
        }));
        steps.add(new Step("slide2_release", 6, false, false, false,
                () -> assertStanding("滑铲转爬行后松手")));

        // 7. 复原旧版残留的 TACZ 趴下状态：修复层应在一 tick 内把它清掉
        steps.add(new Step("stale_tacz_prone", 1, false, false, false,
                () -> IClientPlayerGunOperator.fromLocalPlayer(player()).crawl(true)));
        steps.add(new Step("stale_tacz_prone_cleared", 5, false, false, false, () -> {
            LocalPlayer player = player();
            boolean tacz = IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();
            boolean pose = player.getPose() == Pose.SWIMMING;
            if (tacz || pose) {
                throw new AssertionError("残留趴下未被清理: tacz=" + tacz + " pose=" + player.getPose()
                        + " forced=" + player.getForcedPose());
            }
        }));

        // 8. 空中爬行：ParCool 允许、TACZ 不能；姿势仍必须稳定不抖，落地松手能站起
        steps.add(new Step("air_jump", 1, false, false, true, null));
        steps.add(new Step("air_start", 1, false, false, false, () -> startSampling("空中爬行")));
        steps.add(new Step("air_crawl", 4, true, false, false, () -> {
            if (!parcoolProne(player())) {
                throw new AssertionError("空中未能进入 ParCool 爬行（EnableCrawlInAir）");
            }
            if (IClientPlayerGunOperator.fromLocalPlayer(player()).isCrawl()) {
                throw new AssertionError("空中时 TACZ 不应进入趴下");
            }
            stopSamplingAndAssert("空中爬行", true, false);
        }));
        steps.add(new Step("air_release", 40, false, false, false, () -> assertStanding("空中爬行后松手")));

        // 9. 空手：ParCool 仍然能趴下，TACZ 不参与；姿势必须稳定、松手能站起
        steps.add(new Step("no_gun_setup", 1, false, false, false,
                () -> player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)));
        steps.add(new Step("no_gun_hold_start", 1, true, false, false, () -> startSampling("空手趴下")));
        steps.add(new Step("no_gun_hold", 12, true, false, false, () -> {
            assertParCoolProne("空手趴下");
            if (IClientPlayerGunOperator.fromLocalPlayer(player()).isCrawl()) {
                throw new AssertionError("空手时 TACZ 不应进入趴下");
            }
            stopSamplingAndAssert("空手趴下", true, false);
        }));
        steps.add(new Step("no_gun_release", 6, false, false, false, () -> assertStanding("空手松手")));

        return steps;
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    private static void assertStanding(String what) {
        LocalPlayer player = player();
        boolean park = parcoolProne(player);
        boolean tacz = IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl();
        boolean pose = player.getPose() == Pose.SWIMMING;
        if (park || tacz || pose) {
            throw new AssertionError(what + " 之后仍然趴着: parcool=" + park + " tacz=" + tacz
                    + " pose=" + player.getPose() + " forced=" + player.getForcedPose());
        }
    }

    private static void assertProneAndSynced() {
        LocalPlayer player = player();
        if (!parcoolProne(player)) {
            throw new AssertionError("ParCool 没有处于趴下");
        }
        if (!IClientPlayerGunOperator.fromLocalPlayer(player).isCrawl()) {
            throw new AssertionError("ParCool 已趴下，但 TACZ 没有同步为趴下（两套状态错相）");
        }
        if (player.getPose() != Pose.SWIMMING) {
            throw new AssertionError("趴下时姿势不是 SWIMMING: " + player.getPose());
        }
    }

    private static void assertParCoolProne(String what) {
        if (!parcoolProne(player())) {
            throw new AssertionError(what + " 未生效（parcool crawl/slide 都没有 doing）");
        }
    }

    private static boolean parcoolProne(LocalPlayer player) {
        Parkourability parkourability = Parkourability.get(player);
        if (parkourability == null) {
            return false;
        }
        return parkourability.get(Crawl.class).isDoing() || parkourability.get(Slide.class).isDoing();
    }

    private static LocalPlayer player() {
        return Minecraft.getInstance().player;
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    private static void pass(String name) {
        REPORT.add("PASS  " + name);
        System.out.println("[AUTOTEST] PASS " + name);
    }

    private static void fail(String name, String detail) {
        failures++;
        REPORT.add("FAIL  " + name + "  ->  " + detail);
        System.out.println("[AUTOTEST] FAIL " + name + " -> " + detail);
    }

    private static void log(String name, String detail) {
        REPORT.add("INFO  " + name + "  ->  " + detail);
        System.out.println("[AUTOTEST] INFO " + name + " -> " + detail);
    }

    private static void finish() {
        if (finished) {
            return;
        }
        finished = true;
        REPORT.add(failures == 0 ? "RESULT PASS" : "RESULT FAIL (" + failures + ")");
        REPORT.forEach(System.out::println);
        try {
            Files.write(Path.of("autotest-report.txt"), REPORT, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 报告写不出去也不影响退出码
        }
        System.out.flush();
        Runtime.getRuntime().halt(failures == 0 ? 0 : 1);
    }
}
