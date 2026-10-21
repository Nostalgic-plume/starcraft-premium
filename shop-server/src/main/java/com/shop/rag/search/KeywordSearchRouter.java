package com.shop.rag.search;

import com.shop.rag.config.RagElasticsearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 关键词检索路由：ES（首选）↔ MySQL FULLTEXT（兜底）
 * <p>
 * 对调用方（{@link RagSearchService}）暴露与 {@link KeywordSearchService} 完全一致的签名，
 * 内部按「配置开关 + 熔断状态」选择实现。设计见
 * {@code docs/specs/001-es-keyword-search/design.md}。
 * <p>
 * 熔断语义（与 Phase 5 对话模型的熔断器同构，但**不复用同一个 bean**：
 * 那是生成层专用单例，共用会让检索故障反过来熔断对话模型）：
 * <ul>
 *   <li>连续失败 {@value #FAILURE_THRESHOLD} 次 → 打开熔断，期间直接走 FULLTEXT；</li>
 *   <li>超过 {@code rag.elasticsearch.recovery-interval-ms} → 半开，放行一次 ES 请求；</li>
 *   <li>半开成功 → 关闭熔断；失败 → 重新计时。</li>
 * </ul>
 * <p>
 * 为什么不让 ES 失败直接返回空列表：空列表与「引擎不可用」在下游无法区分，
 * 用户会看到静默空结果。这里必须「抛→捕获→换路」，让降级显式发生。
 *
 * @author shop
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordSearchRouter {

    /** 实际走 ES */
    public static final String ROUTE_ES = "es";
    /** ES 已关闭（配置开关），走 FULLTEXT */
    public static final String ROUTE_FULLTEXT = "fulltext";
    /** ES 启用但当前不可用/熔断，降级走 FULLTEXT */
    public static final String ROUTE_FULLTEXT_DEGRADED = "fulltext(降级)";

    /** 连续失败阈值 */
    private static final int FAILURE_THRESHOLD = 3;

    private final EsKeywordSearchService esKeywordSearchService;
    private final KeywordSearchService mysqlKeywordSearchService;
    private final RagElasticsearchProperties properties;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile boolean circuitOpen = false;
    private volatile long openedAt = 0L;
    private volatile String lastFailureReason = null;

    /** 本次请求实际走的路由（同线程读取，供检索日志标注） */
    private final ThreadLocal<String> currentRoute = new ThreadLocal<>();

    /**
     * 关键词检索（ES 优先，失败自动回退 MySQL FULLTEXT）
     *
     * @param query      查询关键词（同义词扩展后的串）
     * @param limit      返回条数上限
     * @param categoryId 一级分类 ID 过滤（可选）
     * @param minPrice   最低价过滤（可选）
     * @param maxPrice   最高价过滤（可选）
     * @return 候选列表；两套实现语义一致
     */
    public List<RagCandidate> searchByKeyword(String query, int limit,
                                              Long categoryId, BigDecimal minPrice, BigDecimal maxPrice) {
        if (properties.isEnabled() && !isCircuitOpen()) {
            try {
                List<RagCandidate> hits = esKeywordSearchService.searchByKeyword(
                        query, limit, categoryId, minPrice, maxPrice);
                onSuccess();
                currentRoute.set(ROUTE_ES);
                return hits;
            } catch (Exception e) {
                onFailure(e);
                currentRoute.set(ROUTE_FULLTEXT_DEGRADED);
                return mysqlKeywordSearchService.searchByKeyword(
                        query, limit, categoryId, minPrice, maxPrice);
            }
        }

        currentRoute.set(properties.isEnabled() ? ROUTE_FULLTEXT_DEGRADED : ROUTE_FULLTEXT);
        return mysqlKeywordSearchService.searchByKeyword(query, limit, categoryId, minPrice, maxPrice);
    }

    /**
     * 本次请求实际走的关键词路（供日志使用）
     * <p>
     * ⚠️ 基于 ThreadLocal，只在刚调用过 {@link #searchByKeyword} 的同一线程内准确；
     * 管理接口等外部线程请用 {@link #preferredRoute()}。
     *
     * @return {@link #ROUTE_ES} / {@link #ROUTE_FULLTEXT} / {@link #ROUTE_FULLTEXT_DEGRADED}
     */
    public String currentRoute() {
        return currentRoute.get() == null ? ROUTE_FULLTEXT : currentRoute.get();
    }

    /**
     * 当前优选的关键词路（按开关与熔断状态推导，任意线程可读）
     * <p>
     * 供管理接口展示「下一次检索会走哪条路」。注意它不等于「上一次实际走的路」：
     * 单次 ES 失败会临时回退，但未达熔断阈值时优选路仍是 ES。
     *
     * @return {@link #ROUTE_ES} / {@link #ROUTE_FULLTEXT} / {@link #ROUTE_FULLTEXT_DEGRADED}
     */
    public String preferredRoute() {
        if (!properties.isEnabled()) {
            return ROUTE_FULLTEXT;
        }
        return isCircuitOpen() ? ROUTE_FULLTEXT_DEGRADED : ROUTE_ES;
    }

    /**
     * 熔断是否应拦截本次 ES 调用（含半开判定）
     * <p>
     * 打开且未过 TTL → true（直接走 FULLTEXT）；已过 TTL → false（半开，放行一次重试）。
     *
     * @return true 表示本次不应调用 ES
     */
    public boolean isCircuitOpen() {
        if (!circuitOpen) {
            return false;
        }
        return System.currentTimeMillis() - openedAt < properties.getRecoveryIntervalMs();
    }

    /** 连续失败次数（成功一次即清零），供管理接口展示 */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** 最近一次 ES 失败原因 */
    public String getLastFailureReason() {
        return lastFailureReason;
    }

    private void onSuccess() {
        boolean wasOpen = circuitOpen;
        consecutiveFailures.set(0);
        circuitOpen = false;
        openedAt = 0L;
        if (wasOpen) {
            log.info("[关键词路] ES 已恢复，重新作为首选检索路");
        }
    }

    private void onFailure(Exception e) {
        lastFailureReason = e.getMessage();
        int fails = consecutiveFailures.incrementAndGet();
        if (fails >= FAILURE_THRESHOLD) {
            boolean wasOpen = circuitOpen;
            circuitOpen = true;
            openedAt = System.currentTimeMillis();
            if (!wasOpen) {
                log.warn("[关键词路] ES 连续失败 {} 次，熔断 {}ms，期间走 MySQL FULLTEXT: {}",
                        fails, properties.getRecoveryIntervalMs(), e.getMessage());
            }
        } else {
            log.warn("[关键词路降级] ES 检索失败（{}/{}），本次回退 MySQL FULLTEXT: {}",
                    fails, FAILURE_THRESHOLD, e.getMessage());
        }
    }
}
