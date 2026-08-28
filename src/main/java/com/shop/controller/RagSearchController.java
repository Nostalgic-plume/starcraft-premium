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
 *
 * @author 陈
 */
@Tag(name = "RAG 语义搜索")
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class RagSearchController {

    private final RagSearchService ragSearchService;

    /**
     * 语义搜索商品（公开接口）
     * 基于向量语义检索，支持分类与价格区间过滤；向量路失败时自动降级 MySQL FULLTEXT 关键词路
     *
     * @param query      搜索关键词，必填
     * @param topK       返回结果数量上限，默认 10
     * @param categoryId 分类ID过滤，可空表示不限分类
     * @param minPrice   最低价格过滤，可空表示不设下限
     * @param maxPrice   最高价格过滤，可空表示不设上限
     * @return 搜索结果 Map（hits 命中列表、total 命中数、query 原查询词）
     */
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
