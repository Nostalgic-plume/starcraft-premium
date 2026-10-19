package com.shop.controller;

import cn.hutool.core.collection.CollUtil;
import com.shop.common.Result;
import com.shop.rag.search.RagHitVO;
import com.shop.rag.search.RagSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
     * @param categoryId **一级分类** ID 过滤（与索引 payload 的 {@code firstCategoryId} 比对），可空表示不限分类
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
        // 是否命中「字面直接相关」的商品：向量路会返回语义相近但字面无关的结果
        // （如搜「面包」返回方便面），前端据此区分「搜索结果」与「相关推荐」。
        data.put("exactMatch", isExactMatch(hits, query));

        return Result.success(data);
    }

    /**
     * 判断命中列表中是否存在与查询词「字面直接相关」的商品。
     *
     * <p>长句/口语化查询（「适合跑步的轻便鞋子」）没有空格分隔，不能要求整句出现在商品名中。
     * 这里对超过 2 字的 token 做 2 字滑窗，提取「跑步」「鞋子」「零食」这类核心词，
     * 并过滤含口语停用字的窗口（「适合」「的轻」「高的」），只要任一核心词出现在
     * 商品名称或摘要中即判定为直接相关。</p>
     *
     * <p>短查询（「面包」）不做滑窗，整体匹配；「方便面」不会被误判为「面包」的直接相关结果。</p>
     *
     * @param hits  命中列表
     * @param query 原始查询词
     * @return true=存在直接相关商品；false=只有语义相近的推荐结果
     */
    private boolean isExactMatch(List<RagHitVO> hits, String query) {
        if (CollUtil.isEmpty(hits) || query == null || query.isBlank()) {
            return false;
        }
        List<String> matchUnits = buildMatchUnits(query.trim().toLowerCase(Locale.ROOT));
        if (matchUnits.isEmpty()) {
            return false;
        }
        for (RagHitVO hit : hits) {
            String text = ((hit.getName() == null ? "" : hit.getName()) + " "
                    + (hit.getExcerpt() == null ? "" : hit.getExcerpt())).toLowerCase(Locale.ROOT);
            for (String unit : matchUnits) {
                if (text.contains(unit)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 口语/虚词：含这些字的 2 字窗口不参与字面匹配，避免「适合」「的轻」「高的」造成误判 */
    private static final String STOP_CHARS = "的了是在有和与或我想买点个款求请适合高低便宜一些那这么呢吧啊帮找看要";

    /**
     * 把查询词拆成用于字面匹配的单元：短词整体保留，长词取 2 字滑窗并过滤口语停用字。
     */
    private List<String> buildMatchUnits(String query) {
        java.util.LinkedHashSet<String> units = new java.util.LinkedHashSet<>();
        for (String token : query.split("[\\s,，、;；/|]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.length() <= 2) {
                units.add(token);
                continue;
            }
            for (int i = 0; i + 2 <= token.length(); i++) {
                String unit = token.substring(i, i + 2);
                if (STOP_CHARS.indexOf(unit.charAt(0)) < 0 && STOP_CHARS.indexOf(unit.charAt(1)) < 0) {
                    units.add(unit);
                }
            }
        }
        return new java.util.ArrayList<>(units);
    }
}
