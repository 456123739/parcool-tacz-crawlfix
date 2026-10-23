package com.ns.crawlfix.client;

/**
 * 由 Mixin 注入到 TACZ 的 {@code LocalPlayerCrawl} 上的访问接口。
 *
 * <p>需要它是因为 TACZ 的趴下状态是私有的，而且它自带的 {@code crawl(boolean)} 有两个会
 * 丢输入的入口：10 tick 冷却、以及取消趴下时的 {@code onGround} 前置条件。更麻烦的是
 * {@code tickCrawl()} 在若干分支里会**只改本地状态、不发同步包**（例如切枪、离地、游泳），
 * 服务端于是继续保留趴下的强制姿势。
 *
 * <p>因此这里提供两个能力：
 * <ul>
 *   <li>{@link #crawlfix$isSynchronized()}：我们**最后一次告诉服务端**的状态；</li>
 *   <li>{@link #crawlfix$apply(boolean)}：写入状态、刷新姿势，并在与服务端认知不一致时发包。</li>
 * </ul>
 */
public interface CrawlStateAccess {

    /** 当前 TACZ 本地状态。 */
    boolean crawlfix$isCrawling();

    /** 服务端当前被通知到的状态（由本模组负责维护）。 */
    boolean crawlfix$isSynchronized();

    /**
     * 强制把 TACZ 的趴下状态写成 {@code want}：
     * 清掉 10 tick 冷却、立即刷新强制姿势，并在服务端认知不一致时发送
     * {@code ClientMessagePlayerCrawl}。
     */
    void crawlfix$apply(boolean want);
}
