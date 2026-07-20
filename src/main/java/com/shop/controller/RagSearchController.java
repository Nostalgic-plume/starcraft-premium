package com.shop.controller;

import cn.hutool.core.collection.CollUtil;
import com.shop.common.Result;
import com.shop.rag.RagHitVO;
import com.shop.rag.RagSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 语义搜索接口
 * 替代传统的 MySQL LIKE 关键词搜索
 */
@Tag(name = "RAG 语义搜索")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class RagSearchController {

    private final RagSearchService ragSearchService;

    @GetMapping("/rag-search")
    @Operation(summary = "语义搜索商品")
    public Result<Map<String, Object>> search(
            @RequestParam("q") String query,
            @RequestParam(value = "topK", defaultValue = "10") int topK,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "minPrice", required = false) BigDecimal minPrice,
            @RequestParam(value = "maxPrice", required = false) BigDecimal maxPrice) {

        List<RagHitVO> hits = ragSearchService.search(query, topK, categoryId, minPrice, maxPrice);

        Map<String, Object> data = new HashMap<>();
        data.put("hits", hits != null ? hits : List.of());
        data.put("total", CollUtil.isEmpty(hits) ? 0 : hits.size());
        data.put("query", query);

        return Result.success(data);
    }
}
