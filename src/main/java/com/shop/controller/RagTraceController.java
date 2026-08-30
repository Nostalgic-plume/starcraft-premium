package com.shop.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.shop.common.Result;
import com.shop.mapper.RagTraceMapper;
import com.shop.model.entity.RagTraceDO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RAG 行为反馈埋点接口（Phase 4 T5）
 * 记录用户"搜索 → 点击商品"的隐式反馈，异步落库、失败不影响主链路
 */
@Slf4j
@Tag(name = "RAG 行为反馈")
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagTraceController {

    private final RagTraceMapper ragTraceMapper;

    @PostMapping("/trace")
    @Operation(summary = "上报用户行为（搜索→点击），失败不影响主链路")
    public Result<Void> trace(@RequestBody TraceRequest request) {
        try {
            Long userId = null;
            try {
                userId = StpUtil.getLoginIdAsLong();
            } catch (Exception ignored) {
                // 未登录也允许上报（user_id 为 null）
            }
            RagTraceDO record = new RagTraceDO();
            record.setQuery(request.getQuery());
            record.setProductId(request.getProductId());
            record.setAction(request.getAction());
            record.setUserId(userId);
            ragTraceMapper.insert(record);
            return Result.success();
        } catch (Exception e) {
            // 埋点失败不影响主链路（前端异步调用 + 吞异常）
            log.warn("行为埋点失败（不影响主链路）: {}", e.getMessage());
            return Result.error("埋点失败");
        }
    }

    @Data
    public static class TraceRequest {
        private String query;
        private Long productId;
        private String action;
    }
}
