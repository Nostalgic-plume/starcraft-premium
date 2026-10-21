package com.shop.rag.search;

import com.shop.rag.config.RagElasticsearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 关键词路路由与熔断测试（功能编号 001）
 * <p>
 * 锁定的契约：
 * <ol>
 *   <li>ES 正常时优先走 ES；</li>
 *   <li>ES 抛异常时回退 MySQL FULLTEXT，且路由标记为降级；</li>
 *   <li>开关关闭时完全不触碰 ES；</li>
 *   <li>连续失败达阈值后熔断，熔断期内不再调用 ES。</li>
 * </ol>
 *
 * @author shop
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeywordSearchRouter - ES/MySQL 路由与熔断")
class KeywordSearchRouterTest {

    @Mock
    private EsKeywordSearchService esKeywordSearchService;
    @Mock
    private KeywordSearchService mysqlKeywordSearchService;

    private RagElasticsearchProperties properties;
    private KeywordSearchRouter router;

    private static final List<RagCandidate> ES_HITS = List.of(new RagCandidate(1L, 0.9));
    private static final List<RagCandidate> MYSQL_HITS = List.of(new RagCandidate(2L, 0.8));

    @BeforeEach
    void setUp() {
        properties = new RagElasticsearchProperties();
        properties.setEnabled(true);
        router = new KeywordSearchRouter(esKeywordSearchService, mysqlKeywordSearchService, properties);
    }

    @Test
    @DisplayName("ES 正常时优先走 ES，不调用 MySQL")
    void esAvailable_usesEs() {
        when(esKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(ES_HITS);

        List<RagCandidate> result = router.searchByKeyword("手机", 10, null, null, null);

        assertEquals(ES_HITS, result);
        assertEquals(KeywordSearchRouter.ROUTE_ES, router.currentRoute());
        verifyNoInteractions(mysqlKeywordSearchService);
    }

    @Test
    @DisplayName("ES 抛异常时回退 MySQL，并标记为降级")
    void esFailure_fallsBackToMysql() {
        when(esKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenThrow(new KeywordSearchUnavailableException("connection refused"));
        when(mysqlKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(MYSQL_HITS);

        List<RagCandidate> result = router.searchByKeyword("手机", 10, null, null, null);

        assertEquals(MYSQL_HITS, result);
        assertEquals(KeywordSearchRouter.ROUTE_FULLTEXT_DEGRADED, router.currentRoute());
        assertFalse(router.isCircuitOpen());
        assertEquals("connection refused", router.getLastFailureReason());
    }

    @Test
    @DisplayName("开关关闭时只走 MySQL，完全不触碰 ES")
    void disabled_usesMysqlOnly() {
        properties.setEnabled(false);
        when(mysqlKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(MYSQL_HITS);

        List<RagCandidate> result = router.searchByKeyword("手机", 10, null, null, null);

        assertEquals(MYSQL_HITS, result);
        assertEquals(KeywordSearchRouter.ROUTE_FULLTEXT, router.currentRoute());
        verifyNoInteractions(esKeywordSearchService);
    }

    @Test
    @DisplayName("连续失败达阈值后熔断：熔断期内不再调用 ES")
    void circuitOpens_afterThreshold_andSkipsEs() {
        when(esKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenThrow(new KeywordSearchUnavailableException("timeout"));
        when(mysqlKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(MYSQL_HITS);

        // 前 3 次：每次都尝试 ES，失败后回退
        for (int i = 0; i < 3; i++) {
            assertEquals(MYSQL_HITS, router.searchByKeyword("手机", 10, null, null, null));
        }
        assertTrue(router.isCircuitOpen());

        // 第 4 次：熔断期内直接走 MySQL，ES 调用次数停在 3
        assertEquals(MYSQL_HITS, router.searchByKeyword("手机", 10, null, null, null));
        verify(esKeywordSearchService, times(3)).searchByKeyword(anyString(), anyInt(), any(), any(), any());
        assertEquals(KeywordSearchRouter.ROUTE_FULLTEXT_DEGRADED, router.currentRoute());
    }

    @Test
    @DisplayName("半开重试成功后关闭熔断并恢复 ES 优先")
    void halfOpenSuccess_closesCircuit() {
        // 链式 stub：前 3 次失败（触发熔断），第 4 次（半开）成功。
        // 不用「先 thenThrow 再重新 when」—— 对同一 mock 重复 stubbing 时，
        // thenThrow 的桩会在 when() 参数求值阶段直接抛出，导致测试自身报错（Mockito 固有行为）。
        when(esKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenThrow(new KeywordSearchUnavailableException("timeout"))
                .thenThrow(new KeywordSearchUnavailableException("timeout"))
                .thenThrow(new KeywordSearchUnavailableException("timeout"))
                .thenReturn(ES_HITS);
        when(mysqlKeywordSearchService.searchByKeyword(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(MYSQL_HITS);

        for (int i = 0; i < 3; i++) {
            router.searchByKeyword("手机", 10, null, null, null);
        }
        assertTrue(router.isCircuitOpen());

        // 把半开间隔调成 0，下一次立即进入半开
        properties.setRecoveryIntervalMs(0);
        assertFalse(router.isCircuitOpen());

        assertEquals(ES_HITS, router.searchByKeyword("手机", 10, null, null, null));
        assertFalse(router.isCircuitOpen());
        assertEquals(0, router.getConsecutiveFailures());
        assertEquals(KeywordSearchRouter.ROUTE_ES, router.currentRoute());
    }
}
