package com.shop.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.shop.common.Result;
import com.shop.rag.ProductIndexer;
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
 * 提供全量重建 Qdrant 向量索引与 MySQL FULLTEXT 索引、查看索引状态等能力
 *
 * @author shop
 */
@Tag(name = "RAG 索引管理")
@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class IndexAdminController {

    private final ProductIndexer productIndexer;

    /**
     * 触发全量重建索引（需 product:edit 权限）
     * 异步线程执行强重建（不清除已有 Qdrant 数据，而是全量重写），接口立即返回
     *
     * @return 触发成功提示（实际重建进度需通过状态接口查询）
     */
    @PostMapping("/rebuild")
    @SaCheckPermission(value = {"product:edit"}, type ="admin")
    @Operation(summary = "触发全量重建索引（Qdrant 向量 + MySQL FULLTEXT）")
    public Result<Map<String, Object>> rebuild() {
        // 异步强重建（不清除已有 Qdrant 数据，而是全量重写）
        new Thread(() -> {
            productIndexer.forceRebuildAll();
        }, "rag-rebuild-thread").start();

        Map<String, Object> data = new HashMap<>();
        data.put("message", "全量索引重建已触发，请稍后查看状态");
        return Result.success(data);
    }

    /**
     * 查看索引状态（需 product:edit 权限）
     * <p>
     * 返回 Qdrant 已索引商品数、最近重建时间与**重建结果**（成功/失败 + 说明）、FULLTEXT 健康检查。
     * ⚠️ `lastRebuildTime` 只表示「动作已结束」，不代表成功：重建首步会先删集合，
     * 失败时集合可能已为空。前端必须以 `lastRebuildSuccess` 判断成败，否则会把
     * 「索引为空 + 向量服务故障」显示成「重建完成」。
     *
     * @return 索引状态 Map（indexedCount/lastRebuildTime/lastRebuildSuccess/lastRebuildMessage/fulltextIndex）
     */
    @GetMapping("/status")
    @SaCheckPermission(value = {"product:edit"}, type ="admin")
    @Operation(summary = "查看索引状态（Qdrant + FULLTEXT）")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("indexedCount", productIndexer.getIndexedCount());
        data.put("lastRebuildTime", productIndexer.getLastRebuildTime() > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(productIndexer.getLastRebuildTime()),
                        ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : "未重建");
        data.put("lastRebuildSuccess", productIndexer.isLastRebuildSuccess());
        data.put("lastRebuildMessage", productIndexer.getLastRebuildMessage());
        // 添加 FULLTEXT 索引健康检查
        data.put("fulltextIndex", productIndexer.checkFulltextIndex());
        return Result.success(data);
    }
}
