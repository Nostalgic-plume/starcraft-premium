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
 */
@Tag(name = "RAG 索引管理")
@RestController
@RequestMapping("/api/admin/rag")
@RequiredArgsConstructor
public class IndexAdminController {

    private final ProductIndexer productIndexer;

    @PostMapping("/rebuild")
    @SaCheckPermission("product:edit")
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

    @GetMapping("/status")
    @SaCheckPermission("product:edit")
    @Operation(summary = "查看索引状态（Qdrant + FULLTEXT）")
    public Result<Map<String, Object>> status() {
        Map<String, Object> data = new HashMap<>();
        data.put("indexedCount", productIndexer.getIndexedCount());
        data.put("lastRebuildTime", productIndexer.getLastRebuildTime() > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(productIndexer.getLastRebuildTime()),
                        ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                : "未重建");
        // 添加 FULLTEXT 索引健康检查
        data.put("fulltextIndex", productIndexer.checkFulltextIndex());
        return Result.success(data);
    }
}
