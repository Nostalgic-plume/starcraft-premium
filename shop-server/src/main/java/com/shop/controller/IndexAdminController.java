package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.rag.config.RagElasticsearchProperties;
import com.shop.rag.index.EsProductIndexer;
import com.shop.rag.index.ProductIndexSyncService;
import com.shop.rag.index.ProductIndexer;
import com.shop.rag.search.KeywordSearchRouter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * RAG 索引管理接口（管理后台）
 * 提供全量重建 Qdrant 向量索引、MySQL FULLTEXT 索引与 Elasticsearch 关键词索引，
 * 以及查看三套索引与关键词路由状态的能力。
 * <p>
 * 功能编号 001：新增 ES 索引与关键词路由（ES ↔ FULLTEXT）状态展示。
 *
 * @author shop
 */
@Tag(name = "RAG 索引管理")
@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class IndexAdminController {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 重建门面：一次触发 Qdrant + ES 两套索引 */
    private final ProductIndexSyncService productIndexSyncService;
    /** Qdrant 状态读取 */
    private final ProductIndexer productIndexer;
    /** ES 索引状态读取 */
    private final EsProductIndexer esProductIndexer;
    /** 关键词路由状态（当前走 ES 还是降级到 FULLTEXT） */
    private final KeywordSearchRouter keywordSearchRouter;
    private final RagElasticsearchProperties esProperties;

    /**
     * 触发全量重建索引（需 product:edit 权限）
     * 异步线程执行强重建（不清除已有 Qdrant 数据，而是全量重写），接口立即返回
     *
     * @return 触发成功提示（实际重建进度需通过状态接口查询）
     */
    @PostMapping("/rebuild")
    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @Operation(summary = "触发全量重建索引（Qdrant 向量 + MySQL FULLTEXT + ES）")
    public Result<Map<String, Object>> rebuild() {
        // 异步强重建（不清除已有 Qdrant 数据，而是全量重写）
        new Thread(() -> productIndexSyncService.forceRebuildAll(), "rag-rebuild-thread").start();

        Map<String, Object> data = new HashMap<>();
        data.put("message", "全量索引重建已触发（Qdrant + FULLTEXT + ES），请稍后查看状态");
        return Result.success(data);
    }

    /**
     * 查看索引状态（需 product:edit 权限）
     * <p>
     * 返回 Qdrant 已索引商品数、最近重建时间与**重建结果**（成功/失败 + 说明）、
     * FULLTEXT 健康检查、ES 索引状态与关键词路由状态。
     * ⚠️ `lastRebuildTime` 只表示「动作已结束」，不代表成功：重建首步会先删集合，
     * 失败时集合可能已为空。前端必须以 `lastRebuildSuccess` 判断成败。
     *
     * @return 索引状态 Map
     */
    @GetMapping("/status")
    @SaCheckPermission(value = {"product:edit"}, type = "admin")
    @Operation(summary = "查看索引状态（Qdrant + FULLTEXT + ES）")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("indexedCount", productIndexer.getIndexedCount());
        data.put("lastRebuildTime", formatTime(productIndexer.getLastRebuildTime()));
        data.put("lastRebuildSuccess", productIndexer.isLastRebuildSuccess());
        data.put("lastRebuildMessage", productIndexer.getLastRebuildMessage());
        data.put("fulltextIndex", productIndexer.checkFulltextIndex());
        data.put("elasticsearch", esStatus());
        data.put("keywordRoute", keywordSearchRouter.preferredRoute());
        return Result.success(data);
    }

    /** ES 索引与关键词路由的聚合状态 */
    private Map<String, Object> esStatus() {
        Map<String, Object> es = new HashMap<>();
        es.put("enabled", esProperties.isEnabled());
        es.put("available", esProductIndexer.isAvailable());
        es.put("indexedCount", esProductIndexer.countDocuments());
        es.put("lastRebuildTime", formatTime(esProductIndexer.getLastRebuildTime()));
        es.put("lastRebuildSuccess", esProductIndexer.isLastRebuildSuccess());
        es.put("lastRebuildMessage", esProductIndexer.getLastRebuildMessage());
        es.put("circuitOpen", keywordSearchRouter.isCircuitOpen());
        es.put("consecutiveFailures", keywordSearchRouter.getConsecutiveFailures());
        es.put("lastFailureReason", keywordSearchRouter.getLastFailureReason());
        return es;
    }

    private static String formatTime(long epochMillis) {
        if (epochMillis <= 0) {
            return "未重建";
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                .format(TIME_FORMAT);
    }
}
